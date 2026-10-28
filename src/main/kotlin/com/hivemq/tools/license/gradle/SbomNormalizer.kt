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
 * The CycloneDX plugin writes the current time as `metadata.timestamp`, a random `serialNumber`, a `build-system`
 * reference taken from CI environment variables and a `vcs` reference from `GIT_URL` or the remote of the Gradle
 * daemon's working directory, in whatever form the clone used, so two builds of the same commit never produce the
 * same bytes. Every rule here removes one of those differences: the `vcs` reference is replaced by the project's own
 * remote, which the caller passes in, and that repository URL is written as the `source-distribution` reference too.
 */
object SbomNormalizer {

    private val mapper = ObjectMapper()

    // the system line separator would make the output differ between operating systems
    private val printer = DefaultPrettyPrinter()
        .withObjectIndenter(DefaultIndenter("  ", "\n"))
        .withArrayIndenter(DefaultIndenter("  ", "\n"))

    // the owner/repo after a GitHub host, with an optional .git, trailing slash, query or fragment
    private const val GITHUB_REPO = """([^/?#]+/[^/?#]+?)(?:\.git)?/?(?:[?#].*)?$"""

    // GitHub over https, also with the default port written out, which the form below does not accept
    private val gitHubHttps =
        Regex("""^https://(?:[^@/]+@)?github\.com(?::443)?/$GITHUB_REPO""", RegexOption.IGNORE_CASE)

    // every other GitHub form, by host: any scheme, any user info, a colon or a slash after the host
    private val gitHub =
        Regex("""^(?:[\w+]+://)?(?:[^@/]+@)?github\.com[:/]$GITHUB_REPO""", RegexOption.IGNORE_CASE)

    // like git, a remote without a scheme whose first colon comes before any slash
    private val scpStyle = Regex("""^([^@/:]+)@([^@/:]+):(.+)$""")

    /**
     * [vcsUrl], without credentials, becomes the only `vcs` reference and also the only `source-distribution` one;
     * null, or a value that is not a network remote, leaves the SBOM without either.
     */
    fun normalize(bom: ByteArray, timestamp: Instant, vcsUrl: String?): ByteArray {
        val root = BomJson.read(bom)

        val metadata = when (val existing = root.get("metadata")) {
            null -> root.putObject("metadata")
            is ObjectNode -> existing
            else -> throw IllegalArgumentException("The BOM's metadata is not a JSON object")
        }
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

    // The SBOM ships, so no credential may reach it: GitHub becomes https://github.com/org/repo, http(s) drops user
    // info, query and fragment, ssh drops a password, scp-style becomes ssh://, anything else gives no reference.
    // SbomVcsUrlSource applies it too, so the raw remote never becomes a task input.
    internal fun canonicalVcsUrl(url: String): String? {
        (gitHubHttps.find(url) ?: gitHub.find(url))?.groupValues?.get(1)?.let { return "https://github.com/$it" }
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
                // the user of an ssh remote is a login name like git, only a password after it is a secret
                val user = uri.rawUserInfo?.substringBefore(':')?.let { "$it@" }.orEmpty()
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
