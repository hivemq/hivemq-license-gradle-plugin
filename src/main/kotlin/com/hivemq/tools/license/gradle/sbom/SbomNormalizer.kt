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

    // the RFC 3339 date-time of the CycloneDX schema has a four-digit year; an Instant reaches far beyond that
    private val firstTimestamp = Instant.parse("0000-01-01T00:00:00Z")
    private val lastTimestamp = Instant.parse("9999-12-31T23:59:59Z")

    // the owner/repo after a GitHub host, in the characters GitHub allows in names so that the shortcut cannot write
    // an invalid URL, with an optional .git, trailing slash, query or fragment
    private const val GITHUB_REPO = """([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?/?(?:[?#].*)?$"""

    // every GitHub form by host: any scheme, user info or port, also ssh.github.com (ssh on port 443), then : or /
    private val gitHub = Regex(
        """^(?:[\w+]+://)?(?:[^@/]+@)?(?:ssh\.)?github\.com(?::\d+)?[:/]$GITHUB_REPO""",
        RegexOption.IGNORE_CASE,
    )

    // [user@]host:path, but no URL (//), remote helper (::) or @ in the path: credentials in front of the first colon
    // would pass as host and path and must never reach the SBOM. One letter is a Windows drive (C:\repo), not a host
    private val scpStyle = Regex("""^(?:([^@/:]+)@)?([^@/:]{2,}):(?!//|:)([^@]+)$""")

    /**
     * Normalizes [root], as [BomJson.read] returns it, in place and returns it as bytes. [vcsUrl], without credentials,
     * becomes the only `vcs` reference and also the only `source-distribution` one; null, or a value that is not a
     * network remote, leaves the SBOM without either.
     */
    fun normalize(root: ObjectNode, timestamp: Instant, vcsUrl: String?): ByteArray {
        // what the SBOM carries, so a value that rounds into the four-digit years still passes
        val seconds = timestamp.truncatedTo(ChronoUnit.SECONDS)
        if (seconds !in firstTimestamp..lastTimestamp) {
            throw SbomException(
                "The SBOM timestamp $seconds is not a valid date: set hivemqLicense.sbomTimestamp to a date with a " +
                    "four-digit year, or fix the SOURCE_DATE_EPOCH environment variable, which holds seconds since " +
                    "1970-01-01, not milliseconds"
            )
        }
        // BomJson.read rejected a metadata that is not an object, so there is nothing else to replace here
        val metadata = root.get("metadata") as? ObjectNode ?: root.putObject("metadata")
        // TODO: once the plugin depends on a release with CycloneDX/cyclonedx-gradle-plugin#964, pass sbomTimestamp
        //  to generateDependencyBom; the override and the year check (absent from #964) stay for a merged product SBOM
        metadata.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(seconds))
        (metadata.get("component") as? ObjectNode)?.let { normalizeExternalReferences(it, vcsUrl) }
        (root.get("dependencies") as? ArrayNode)?.let(::sortDependsOnLists)

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
        // The enricher appends its fields to the ones in the BOM, so a field replaced in place would come before
        // them when CycloneDX wrote it and after them when it did not. Removed first, it always comes last.
        component.remove("externalReferences")
        // nothing is written when no reference is left, so the ones dropped above do not show in the output
        if (!kept.isEmpty) component.set<JsonNode>("externalReferences", kept)
    }

    /**
     * [url] without credentials, as the SBOM ships: GitHub as https://github.com/org/repo, http(s) without user info,
     * query and fragment, ssh with no user but git, scp-style as ssh:// with an explicit `~` home kept; null for
     * anything else.
     */
    internal fun canonicalVcsUrl(url: String): String? {
        gitHub.find(url)?.groupValues?.get(1)?.let { return "https://github.com/$it" }
        val candidate = scpStyle.matchEntire(url)?.destructured
            ?.let { (user, host, path) ->
                // the user group is empty when the remote has none
                val userInfo = if (user.isEmpty()) "" else "$user@"
                // hosting services look a repository up by name and ignore a leading slash, so the path is written
                // absolute, like their own ssh URLs; a home-relative path on a plain ssh server needs ~/ or vcsUrl
                "ssh://$userInfo$host/${path.removePrefix("/")}"
            }
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

    // TODO: drop once the plugin depends on a CycloneDX release with CycloneDX/cyclonedx-gradle-plugin#964, which sorts
    //  the dependsOn lists upstream; CycloneDX already writes the entries sorted by ref
    private fun sortDependsOnLists(dependencies: ArrayNode) {
        for (dependency in dependencies) {
            val dependsOn = dependency.get("dependsOn") as? ArrayNode ?: continue
            // sorting would have to turn the entries into strings, so a list with other entries stays as it is
            if (!dependsOn.all { it.isTextual }) continue
            val refs = dependsOn.map { it.asText() }.sorted()
            dependsOn.removeAll()
            refs.forEach(dependsOn::add)
        }
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
