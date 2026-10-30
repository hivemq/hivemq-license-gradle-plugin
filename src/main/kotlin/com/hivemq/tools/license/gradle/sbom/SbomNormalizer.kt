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
package com.hivemq.tools.license.gradle.sbom

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Turns the JSON BOM written by the CycloneDX Gradle plugin into a file that is identical for identical inputs.
 *
 * It replaces the current time and the random `serialNumber`, drops the `build-system` reference to the CI run and
 * writes the remote the caller passes in as the `vcs` and the `source-distribution` reference instead of CycloneDX's.
 */
internal object SbomNormalizer {

    private val mapper = ObjectMapper()

    // the system line separator would make the output differ between operating systems
    private val printer = DefaultPrettyPrinter()
        .withObjectIndenter(DefaultIndenter("  ", "\n"))
        .withArrayIndenter(DefaultIndenter("  ", "\n"))

    // the owner/repo after a GitHub host, with an optional .git, trailing slash, query or fragment
    private const val GITHUB_REPO = """([^/?#]+/[^/?#]+?)(?:\.git)?/?(?:[?#].*)?$"""

    // every GitHub form by host: any scheme, user info or port, also ssh.github.com (ssh on port 443), then : or /
    private val gitHub = Regex(
        """^(?:[\w+]+://)?(?:[^@/]+@)?(?:ssh\.)?github\.com(?::\d+)?[:/]$GITHUB_REPO""",
        RegexOption.IGNORE_CASE,
    )

    // like git, a remote without a scheme whose first colon comes before any slash
    private val scpStyle = Regex("""^([^@/:]+)@([^@/:]+):(.+)$""")

    /**
     * [vcsUrl], without credentials, becomes the only `vcs` reference and also the only `source-distribution` one;
     * null, or a value that is not a network remote, leaves the SBOM without either.
     */
    fun normalize(bom: ByteArray, timestamp: Instant, vcsUrl: String?): ByteArray =
        normalize(BomJson.read(bom), timestamp, vcsUrl)

    /** [normalize] for [root] as [BomJson.read] returns it, which this changes. */
    fun normalize(root: ObjectNode, timestamp: Instant, vcsUrl: String?): ByteArray {
        // BomJson.read rejected a metadata that is not an object, so there is nothing else to replace here
        val metadata = root.get("metadata") as? ObjectNode ?: root.putObject("metadata")
        metadata.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp.truncatedTo(ChronoUnit.SECONDS)))
        (metadata.get("component") as? ObjectNode)?.let { normalizeExternalReferences(it, vcsUrl) }
        (root.get("dependencies") as? ArrayNode)?.let(::sortDependencies)

        // the serial number names the content, so it is computed over everything else
        root.remove("serialNumber")
        // the hash covers the timestamp too, so the same dependencies with a different timestamp get a different serial
        val serial = UUID.nameUUIDFromBytes(write(root))
        return write(withSerialNumber(root, "urn:uuid:$serial"))
    }

    private fun normalizeExternalReferences(component: ObjectNode, vcsUrl: String?) {
        val references = when (val existing = component.get("externalReferences")) {
            null -> mapper.createArrayNode()
            is ArrayNode -> existing
            else -> return
        }
        val kept = mapper.createArrayNode()
        for (reference in references) {
            when (reference.get("type")?.asText()) {
                // points at the CI run that built it and is not even a task input, so it differs per build
                "build-system" -> continue
                // CycloneDX's guess, replaced by the project's own remote below
                "vcs" -> continue
                // written below from the same remote, so a document that is normalized again must not collect another
                "source-distribution" -> continue
            }
            kept.add(reference)
        }
        vcsUrl?.let(::canonicalVcsUrl)?.let { url ->
            kept.addObject().put("type", "vcs").put("url", url)
            // the source of the project itself is its repository
            kept.addObject().put("type", "source-distribution").put("url", url)
        }
        // whether CycloneDX wrote references that were dropped above must not show in the output
        if (kept.isEmpty) {
            component.remove("externalReferences")
        } else {
            component.set("externalReferences", kept)
        }
    }

    /**
     * [url] without credentials, as the SBOM ships: GitHub as https://github.com/org/repo, http(s) without user info,
     * query and fragment, ssh with no user but git, scp-style as ssh://; null for anything else.
     */
    internal fun canonicalVcsUrl(url: String): String? {
        gitHub.find(url)?.groupValues?.get(1)?.let { return "https://github.com/$it" }
        val candidate = scpStyle.matchEntire(url)?.destructured
            ?.let { (user, host, path) -> "ssh://$user@$host/${path.removePrefix("/")}" }
            ?: url
        val uri = try {
            URI(candidate)
        } catch (_: URISyntaxException) {
            return null
        }
        // a host Java cannot parse leaves the authority, user info included, unsplit
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return when (val scheme = uri.scheme?.lowercase()) {
            "http", "https" -> "$scheme://$host$port${uri.rawPath}"
            "ssh" -> {
                // git is the shared user of a hosted repository; any other user is a person's login name, which would
                // put that person into the SBOM and make it differ between clones, and a password is a secret
                val user = if (uri.rawUserInfo?.substringBefore(':') == "git") "git@" else ""
                "ssh://$user$host$port${uri.rawPath}"
            }
            else -> null
        }
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

    /** Keeps CycloneDX's field order, where the serial number follows the spec version. */
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
