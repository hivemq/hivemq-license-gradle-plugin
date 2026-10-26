/*
 * Copyright 2020-present HiveMQ and the HiveMQ Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hivemq.tools.license.gradle

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Turns the JSON BOM written by the CycloneDX Gradle plugin into a file that is identical for identical inputs.
 *
 * The CycloneDX plugin writes the current time as `metadata.timestamp`, a random `serialNumber`, a `build-system`
 * reference taken from CI environment variables and the `vcs` remote in whatever form the clone used, so two builds of
 * the same commit never produce the same bytes. Every rule here removes one of those differences.
 */
object SbomNormalizer {

    private val mapper = ObjectMapper()

    // the system line separator would make the output differ between operating systems
    private val printer = DefaultPrettyPrinter()
        .withObjectIndenter(DefaultIndenter("  ", "\n"))
        .withArrayIndenter(DefaultIndenter("  ", "\n"))

    private val gitHubSsh = Regex("""^(?:ssh://)?git@github\.com[:/]([^/]+/[^/]+?)(?:\.git)?/?$""")
    private val gitHubHttps = Regex("""^https://github\.com/([^/]+/[^/]+?)(?:\.git)?/?$""")

    fun normalize(bom: ByteArray, timestamp: Instant): ByteArray {
        val root = read(bom) as? ObjectNode
            ?: throw IllegalArgumentException("The BOM is not a JSON object")

        val metadata = when (val existing = root.get("metadata")) {
            null -> root.putObject("metadata")
            is ObjectNode -> existing
            else -> throw IllegalArgumentException("The BOM's metadata is not a JSON object")
        }
        metadata.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp.truncatedTo(ChronoUnit.SECONDS)))
        (metadata.get("component") as? ObjectNode)?.let(::normalizeExternalReferences)
        (root.get("dependencies") as? ArrayNode)?.let(::sortDependencies)

        // the serial number names the content, so it is computed over everything else
        root.remove("serialNumber")
        // the hash covers the timestamp too, so the same dependencies with a different timestamp get a different serial
        val serial = UUID.nameUUIDFromBytes(write(root))
        return write(withSerialNumber(root, "urn:uuid:$serial"))
    }

    private fun read(bom: ByteArray): JsonNode = try {
        mapper.readTree(bom)
    } catch (e: JsonProcessingException) {
        throw IllegalArgumentException("The BOM is not valid JSON", e)
    }

    private fun normalizeExternalReferences(component: ObjectNode) {
        val references = component.get("externalReferences") as? ArrayNode ?: return
        val kept = mapper.createArrayNode()
        for (reference in references) {
            when (reference.get("type")?.asText()) {
                // points at the CI run that built it and is not even a task input, so it differs per build
                "build-system" -> continue
                "vcs" -> rewriteVcsUrl(reference)
            }
            kept.add(reference)
        }
        component.set<JsonNode>("externalReferences", kept)
    }

    private fun rewriteVcsUrl(reference: JsonNode) {
        val url = reference.get("url")
        if (reference is ObjectNode && url?.isTextual == true) {
            reference.put("url", canonicalVcsUrl(url.asText()))
        }
    }

    // CI clones over https, developers over ssh; the repository is the same
    private fun canonicalVcsUrl(url: String): String {
        val repository = gitHubSsh.find(url)?.groupValues?.get(1)
            ?: gitHubHttps.find(url)?.groupValues?.get(1)
            ?: return url
        return "https://github.com/$repository"
    }

    private fun sortDependencies(dependencies: ArrayNode) {
        // a ref that is not text sorts first and keeps its relative order, it is not turned into a string
        val sorted = dependencies.sortedBy { dependency ->
            dependency.get("ref")?.takeIf { it.isTextual }?.asText().orEmpty()
        }
        for (dependency in sorted) {
            val dependsOn = dependency.get("dependsOn") as? ArrayNode ?: continue
            // sorting would have to turn the entries into strings, so a list with other entries stays as it is
            if (!dependsOn.all { it.isTextual }) continue
            val refs = dependsOn.map { it.asText() }.sorted()
            dependsOn.removeAll()
            refs.forEach(dependsOn::add)
        }
        dependencies.removeAll()
        sorted.forEach(dependencies::add)
    }

    // keeps CycloneDX's field order, where the serial number follows the spec version
    private fun withSerialNumber(root: ObjectNode, serialNumber: String): ObjectNode {
        val ordered = mapper.createObjectNode()
        var placed = false
        for ((name, value) in root.properties()) {
            ordered.set<JsonNode>(name, value)
            if (name == "specVersion") {
                ordered.put("serialNumber", serialNumber)
                placed = true
            }
        }
        if (!placed) ordered.put("serialNumber", serialNumber)
        return ordered
    }

    private fun write(node: ObjectNode): ByteArray =
        (mapper.writer(printer).writeValueAsString(node) + "\n").toByteArray()
}
