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

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class SbomNormalizerTest {

    private val mapper = ObjectMapper()
    private val timestamp: Instant = Instant.parse("2026-01-02T03:04:05Z")

    /** A BOM the way the CycloneDX Gradle plugin writes it: random serial, wall-clock timestamp, CI references. */
    private fun bom(
        serial: String = "urn:uuid:fcc72669-6094-47f1-a9fe-81e7c08e2d54",
        timestamp: String = "2026-10-16T10:01:10Z",
        dependsOn: String = """["pkg:maven/b/b@1", "pkg:maven/a/a@1"]""",
    ) = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "serialNumber": "$serial",
          "version": 1,
          "metadata": {
            "timestamp": "$timestamp",
            "component": {
              "type": "application",
              "bom-ref": "pkg:maven/com.hivemq/app@1.0.0?project_path=%3A",
              "name": "app",
              "version": "1.0.0",
              "externalReferences": [
                { "type": "build-system", "url": "https://github.com/hivemq/app/actions/runs/39618439086" },
                { "type": "vcs", "url": "ssh://git@github.com:other/wrong.git" }
              ]
            }
          },
          "components": [
            { "type": "library", "bom-ref": "pkg:maven/a/a@1", "group": "a", "name": "a", "version": "1" },
            { "type": "library", "bom-ref": "pkg:maven/b/b@1", "group": "b", "name": "b", "version": "1" }
          ],
          "dependencies": [
            { "ref": "pkg:maven/com.hivemq/app@1.0.0?project_path=%3A", "dependsOn": $dependsOn },
            { "ref": "pkg:maven/a/a@1", "dependsOn": [] }
          ]
        }
    """.trimIndent().toByteArray()

    private fun normalize(input: ByteArray = bom(), vcsUrl: String? = null) =
        SbomNormalizer.normalize(BomJson.read(input), timestamp, vcsUrl)

    private fun normalizeAt(timestamp: Instant) = SbomNormalizer.normalize(BomJson.read(bom()), timestamp, null)

    private fun references(bytes: ByteArray) = tree(bytes)["metadata"]["component"]["externalReferences"]

    // vcs and source-distribution share one remote, so a credential in either adds a second URL to this list
    private fun referenceUrls(bytes: ByteArray) = references(bytes).map { it["url"].asText() }.distinct()

    private fun tree(bytes: ByteArray) = mapper.readTree(bytes)

    // --- Input ---

    @Test
    fun `accepts a BOM that ends with a newline`() {
        val result = tree(normalize(bom() + "\n".toByteArray()))

        assertThat(result["bomFormat"].asText()).isEqualTo("CycloneDX")
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "garbage"])
    fun `rejects a BOM that is followed by more content`(trailing: String) {
        val input = "{\"bomFormat\":\"CycloneDX\"}\n$trailing"

        assertThatThrownBy { normalize(input.toByteArray()) }
            .isInstanceOf(SbomException::class.java)
            // a second JSON value is named as such, anything else as invalid JSON
            .hasMessageMatching("The BOM (has content after its JSON value|is not valid JSON: .+) at line 2, column 1")
    }

    @Test
    fun `rejects input that is not a JSON object`() {
        assertThatThrownBy { normalize("[]".toByteArray()) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("The BOM is not a JSON object")
    }

    @Test
    fun `rejects input that is not valid JSON`() {
        assertThatThrownBy { normalize("{".toByteArray()) }
            .isInstanceOf(SbomException::class.java)
            .hasMessageStartingWith("The BOM is not valid JSON: Unexpected end-of-input")
            .hasMessageContaining("at line 1")
    }

    @Test
    fun `rejects metadata that is not a JSON object`() {
        assertThatThrownBy { normalize("""{"metadata":[]}""".toByteArray()) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("The BOM's metadata is not a JSON object")
    }

    // --- Timestamp ---

    @Test
    fun `accepts the first and the last second a four-digit year can hold`() {
        val first = tree(normalizeAt(Instant.parse("0000-01-01T00:00:00Z")))
        val last = tree(normalizeAt(Instant.parse("9999-12-31T23:59:59.5Z")))

        assertThat(first["metadata"]["timestamp"].asText()).isEqualTo("0000-01-01T00:00:00Z")
        assertThat(last["metadata"]["timestamp"].asText()).isEqualTo("9999-12-31T23:59:59Z")
    }

    @Test
    fun `rejects a timestamp after the year 9999`() {
        // what SOURCE_DATE_EPOCH gives when it holds milliseconds
        assertThatThrownBy { normalizeAt(Instant.ofEpochSecond(1_700_000_000_000L)) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "The SBOM timestamp +55840-11-08T22:13:20Z is not a valid date: set hivemqLicense.sbomTimestamp to a " +
                    "date with a four-digit year, or fix the SOURCE_DATE_EPOCH environment variable, which holds " +
                    "seconds since 1970-01-01, not milliseconds"
            )
    }

    @Test
    fun `rejects a timestamp before the year 0000`() {
        assertThatThrownBy { normalizeAt(Instant.parse("-0001-12-31T23:59:59Z")) }
            .isInstanceOf(SbomException::class.java)
            .hasMessageContaining("is not a valid date")
    }

    @Test
    fun `sets the timestamp in ISO-8601 UTC without fractions`() {
        val result = tree(normalize())

        assertThat(result["metadata"]["timestamp"].asText()).isEqualTo("2026-01-02T03:04:05Z")
    }

    @Test
    fun `truncates a timestamp with nanoseconds to seconds`() {
        val result = tree(normalizeAt(Instant.parse("2026-01-02T03:04:05.678Z")))

        assertThat(result["metadata"]["timestamp"].asText()).isEqualTo("2026-01-02T03:04:05Z")
    }

    // --- Serial number ---

    @Test
    fun `changes the serial number when the content changes`() {
        val first = tree(normalize(bom(dependsOn = """["pkg:maven/a/a@1"]""")))
        val second = tree(normalize(bom(dependsOn = """["pkg:maven/b/b@1"]""")))

        assertThat(first["serialNumber"].asText()).isNotEqualTo(second["serialNumber"].asText())
    }

    @Test
    fun `derives the serial number from the content`() {
        val first = tree(normalize(bom(serial = "urn:uuid:11111111-1111-1111-1111-111111111111")))
        val second = tree(normalize(bom(serial = "urn:uuid:22222222-2222-2222-2222-222222222222")))

        assertThat(first["serialNumber"].asText())
            .matches("urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            .isEqualTo(second["serialNumber"].asText())
    }

    @Test
    fun `keeps the serial number right after the spec version`() {
        val result = tree(normalize())

        assertThat(result.fieldNames().asSequence().toList())
            .containsExactly(
                "bomFormat", "specVersion", "serialNumber", "version", "metadata", "components", "dependencies",
            )
    }

    // --- Dependencies ---

    @Test
    fun `keeps the dependencies in the order of the BOM`() {
        val result = tree(normalize())

        assertThat(result["dependencies"].map { it["ref"].asText() })
            .containsExactly("pkg:maven/com.hivemq/app@1.0.0?project_path=%3A", "pkg:maven/a/a@1")
    }

    @Test
    fun `leaves a dependsOn list with a non-text entry unsorted`() {
        val result = tree(normalize(bom(dependsOn = """["pkg:maven/b/b@1", 1, "pkg:maven/a/a@1"]""")))

        assertThat(result["dependencies"][0]["dependsOn"].map { it.asText() })
            .containsExactly("pkg:maven/b/b@1", "1", "pkg:maven/a/a@1")
    }

    @Test
    fun `sorts the dependsOn lists`() {
        val result = tree(normalize())

        assertThat(result["dependencies"][0]["dependsOn"].map { it.asText() })
            .containsExactly("pkg:maven/a/a@1", "pkg:maven/b/b@1")
    }

    // --- External references ---

    @Test
    fun `drops the vcs and source distribution references when the project has no remote`() {
        val website = """{"type":"website","url":"https://www.hivemq.com"}"""
        val vcs = """{"type":"vcs","url":"git@github.com:a/b.git"}"""
        val source = """{"type":"source-distribution","url":"git@github.com:a/b.git"}"""
        val input = """{"metadata":{"component":{"externalReferences":[$website,$vcs,$source]}}}"""

        val result = normalize(input.toByteArray(), vcsUrl = null)

        assertThat(references(result).map { it["type"].asText() }).containsExactly("website")
    }

    @Test
    fun `removes an empty externalReferences list`() {
        val result = tree(normalize(vcsUrl = null))

        assertThat(result["metadata"]["component"].has("externalReferences")).isFalse()
    }

    @Test
    fun `removes the build-system reference`() {
        val result = normalize(vcsUrl = "git@github.com:hivemq/app.git")

        assertThat(references(result).map { it["type"].asText() }).containsExactly("vcs", "source-distribution")
    }

    @Test
    fun `replaces the vcs reference CycloneDX wrote`() {
        val result = normalize(vcsUrl = "git@github.com:hivemq/app.git")

        assertThat(referenceUrls(result)).containsExactly("https://github.com/hivemq/app")
    }

    @Test
    fun `tolerates a component without external references`() {
        val input = """{"bomFormat":"CycloneDX","specVersion":"1.6","metadata":{"component":{"name":"app"}}}"""

        val result = tree(normalize(input.toByteArray()))

        assertThat(result["metadata"]["component"]["name"].asText()).isEqualTo("app")
        assertThat(result["metadata"]["timestamp"].asText()).isEqualTo("2026-01-02T03:04:05Z")
    }

    @Test
    fun `tolerates an external reference that is not an object`() {
        val vcs = """{"type":"vcs","url":"git@github.com:a/b.git"}"""
        val input = """{"metadata":{"component":{"externalReferences":["text",$vcs]}}}"""

        val result = normalize(input.toByteArray(), vcsUrl = "git@github.com:a/b.git")

        val kept = references(result)
        assertThat(kept).hasSize(3)
        assertThat(kept[0].asText()).isEqualTo("text")
        assertThat(kept[1]["url"].asText()).isEqualTo("https://github.com/a/b")
        assertThat(kept[2]["type"].asText()).isEqualTo("source-distribution")
    }

    @Test
    fun `writes the same bytes for a BOM with and without a build-system reference`() {
        val details = SbomDetails(
            creatorName = "HiveMQ GmbH",
            creator = "https://www.hivemq.com/",
            artifactFileName = "app-1.0.0.jar",
            componentFileNames = emptyMap(),
            concludedLicenses = emptyMap(),
            incompleteReasons = emptyList(),
        )

        // CycloneDX writes the reference on CI only, and the enricher adds its fields behind the ones the BOM has
        fun write(references: String): String {
            val bom = """
                {
                  "specVersion": "1.6",
                  "metadata": {
                    "component": { "type": "application", "name": "app", "version": "1.0.0"$references }
                  }
                }
            """.trimIndent()
            val enriched = SbomEnricher.enrich(BomJson.read(bom.toByteArray()), details)
            return String(SbomNormalizer.normalize(enriched, timestamp, "git@github.com:hivemq/app.git"))
        }
        val local = write(references = "")
        val ci = write(
            references = """, "externalReferences": [ { "type": "build-system", "url": "https://ci.example.com/1" } ]"""
        )

        assertThat(local).contains(""""url" : "https://github.com/hivemq/app"""")
        assertThat(ci).isEqualTo(local)
    }

    @Test
    fun `writes the vcs url as the source distribution too`() {
        val result = normalize(vcsUrl = "git@github.com:hivemq/app.git")

        assertThat(references(result).map { "${it["type"].asText()} ${it["url"].asText()}" })
            .containsExactly("vcs https://github.com/hivemq/app", "source-distribution https://github.com/hivemq/app")
    }

    // --- Remote URL ---

    @Test
    fun `canonicalizes a GitHub https remote with a git suffix`() {
        val result = normalize(vcsUrl = "https://github.com/hivemq/hivemq-kafka-extension.git")

        assertThat(referenceUrls(result)).containsExactly("https://github.com/hivemq/hivemq-kafka-extension")
    }

    @Test
    fun `canonicalizes a GitHub https remote with a mixed-case host`() {
        val result = normalize(vcsUrl = "https://GitHub.com/hivemq/x.git")

        assertThat(referenceUrls(result)).containsExactly("https://github.com/hivemq/x")
    }

    @Test
    fun `canonicalizes a GitHub https remote with a token`() {
        val result = normalize(vcsUrl = "https://token@github.com/hivemq/x.git")

        assertThat(referenceUrls(result)).containsExactly("https://github.com/hivemq/x")
    }

    @Test
    fun `canonicalizes a GitHub https remote with the default port`() {
        val result = normalize(vcsUrl = "https://github.com:443/hivemq/x.git")

        assertThat(referenceUrls(result)).containsExactly("https://github.com/hivemq/x")
    }

    @Test
    fun `canonicalizes a GitHub remote with a password`() {
        val result = normalize(vcsUrl = "ssh://git:secret@github.com/hivemq/x.git")

        assertThat(referenceUrls(result)).containsExactly("https://github.com/hivemq/x")
    }

    @Test
    fun `canonicalizes a GitHub remote with a query`() {
        val result = normalize(vcsUrl = "https://github.com/hivemq/x.git?ref=main")

        assertThat(referenceUrls(result)).containsExactly("https://github.com/hivemq/x")
    }

    @Test
    fun `canonicalizes a GitHub scp-style remote without a user`() {
        val result = normalize(vcsUrl = "github.com:org/repo.git")

        assertThat(referenceUrls(result)).containsExactly("https://github.com/org/repo")
    }

    @Test
    fun `canonicalizes a GitHub ssh remote on the https port`() {
        // GitHub's ssh over the https port, for a firewall that refuses ssh, uses the host ssh.github.com
        val result = normalize(vcsUrl = "ssh://git@ssh.github.com:443/org/repo.git")

        assertThat(referenceUrls(result)).containsExactly("https://github.com/org/repo")
    }

    @Test
    fun `canonicalizes a GitHub ssh remote to https`() {
        val result = normalize(vcsUrl = "ssh://git@github.com/hivemq/hivemq-kafka-extension.git")

        assertThat(referenceUrls(result)).containsExactly("https://github.com/hivemq/hivemq-kafka-extension")
    }

    @Test
    fun `canonicalizes a GitHub ssh remote with a colon after the host`() {
        val result = normalize(vcsUrl = "ssh://git@github.com:hivemq/x.git")

        assertThat(referenceUrls(result)).containsExactly("https://github.com/hivemq/x")
    }

    @Test
    fun `canonicalizes a plain git ssh remote`() {
        val result = normalize(vcsUrl = "git@github.com:hivemq/hivemq-kafka-extension.git")

        assertThat(referenceUrls(result)).containsExactly("https://github.com/hivemq/hivemq-kafka-extension")
    }

    @Test
    fun `drops a user other than git from an ssh remote`() {
        for (remote in listOf("jdoe@review.example.com:p", "ssh://jdoe@review.example.com/p")) {
            val result = normalize(vcsUrl = remote)

            assertThat(referenceUrls(result)).describedAs(remote).containsExactly("ssh://review.example.com/p")
        }
    }

    @Test
    fun `drops the password from an ssh remote`() {
        val result = normalize(vcsUrl = "ssh://git:secret@host/x.git")

        assertThat(referenceUrls(result)).containsExactly("ssh://git@host/x.git")
    }

    @Test
    fun `drops the user info from an https remote`() {
        val result = normalize(vcsUrl = "https://u:p@gitlab.example.com/t/a.git")

        assertThat(referenceUrls(result)).containsExactly("https://gitlab.example.com/t/a.git")
    }

    @Test
    fun `gives no vcs reference for a file URL`() {
        val result = tree(normalize(vcsUrl = "file:///tmp/repo"))

        assertThat(result["metadata"]["component"].has("externalReferences")).isFalse()
    }

    @Test
    fun `gives no vcs reference for a GitHub remote with a character GitHub does not allow in a name`() {
        for (remote in listOf("https://github.com/hivemq/bad name.git", "https://github.com/hivemq/app%ZZ.git")) {
            val result = tree(normalize(vcsUrl = remote))

            assertThat(result["metadata"]["component"].has("externalReferences")).describedAs(remote).isFalse()
        }
    }

    @Test
    fun `gives no vcs reference for a host Java cannot parse`() {
        // the authority stays unsplit, user info included, so nothing of it may be written
        val result = tree(normalize(vcsUrl = "https://u:secret@my_host.internal/r.git"))

        assertThat(result["metadata"]["component"].has("externalReferences")).isFalse()
    }

    @Test
    fun `gives no vcs reference for a local path`() {
        val result = tree(normalize(vcsUrl = "/tmp/repo"))

        assertThat(result["metadata"]["component"].has("externalReferences")).isFalse()
    }

    @Test
    fun `gives no vcs reference for a remote helper URL`() {
        // the part before :: names a git remote helper, not a host, and the address after it is the helper's own
        for (remote in listOf("hg::https://host/r", "hg::https://u:secret@host/r")) {
            val bytes = normalize(vcsUrl = remote)

            assertThat(tree(bytes)["metadata"]["component"].has("externalReferences")).describedAs(remote).isFalse()
            assertThat(String(bytes)).describedAs(remote).doesNotContain("secret")
        }
    }

    @Test
    fun `gives no vcs reference for a remote with a password and no scheme`() {
        // git knows no password in this form, so the text before the first colon is no host and must not be written
        for (remote in listOf("u:secret@host:r", "jdoe:secret@host:r", "jdoe:secret@host/r")) {
            val bytes = normalize(vcsUrl = remote)

            assertThat(tree(bytes)["metadata"]["component"].has("externalReferences")).describedAs(remote).isFalse()
            assertThat(String(bytes)).describedAs(remote).doesNotContain("secret")
        }
    }

    @Test
    fun `gives no vcs reference for a Windows drive path`() {
        // a single letter before the colon is a drive on Windows, not the host of an scp-style remote
        for (remote in listOf("C:/repo", """C:\repo""")) {
            val result = tree(normalize(vcsUrl = remote))

            assertThat(result["metadata"]["component"].has("externalReferences")).describedAs(remote).isFalse()
        }
    }

    @Test
    fun `gives no vcs reference for an unparsable remote`() {
        val result = tree(normalize(vcsUrl = "https://u:p@gitlab example.com/t/a.git"))

        assertThat(result["metadata"]["component"].has("externalReferences")).isFalse()
    }

    @Test
    fun `keeps the absolute path of an scp-style remote`() {
        val result = normalize(vcsUrl = "git@gitlab.example.com:/srv/git/a.git")

        assertThat(referenceUrls(result)).containsExactly("ssh://git@gitlab.example.com/srv/git/a.git")
    }

    @Test
    fun `keeps the explicit home of an scp-style remote`() {
        val result = normalize(vcsUrl = "git@host.example.com:~/a.git")

        assertThat(referenceUrls(result)).containsExactly("ssh://git@host.example.com/~/a.git")
    }

    @Test
    fun `keeps the git user and the port of an ssh remote`() {
        val result = normalize(vcsUrl = "ssh://git@gitlab.example.com:2222/t/a.git")

        assertThat(referenceUrls(result)).containsExactly("ssh://git@gitlab.example.com:2222/t/a.git")
    }

    @Test
    fun `keeps the home of a named user in an scp-style remote`() {
        val result = normalize(vcsUrl = "git@host.example.com:~alice/a.git")

        assertThat(referenceUrls(result)).containsExactly("ssh://git@host.example.com/~alice/a.git")
    }

    @Test
    fun `keeps the port and drops the query and fragment of an https remote`() {
        val result = normalize(vcsUrl = "https://gitlab.example.com:8443/t/a.git?private_token=secret#main")

        assertThat(referenceUrls(result)).containsExactly("https://gitlab.example.com:8443/t/a.git")
    }

    @Test
    fun `keeps the underscore and the inner dot of a GitHub repository name`() {
        val result = normalize(vcsUrl = "https://github.com/hivemq/hivemq_mqtt.client.git")

        assertThat(referenceUrls(result)).containsExactly("https://github.com/hivemq/hivemq_mqtt.client")
    }

    @Test
    fun `leaves a non-GitHub remote alone`() {
        val result = normalize(vcsUrl = "https://gitlab.example.com/team/app.git")

        assertThat(referenceUrls(result)).containsExactly("https://gitlab.example.com/team/app.git")
    }

    @Test
    fun `turns an scp-style remote into an ssh URL`() {
        val result = normalize(vcsUrl = "git@gitlab.example.com:t/a.git")

        assertThat(referenceUrls(result)).containsExactly("ssh://git@gitlab.example.com/t/a.git")
    }

    @Test
    fun `turns an scp-style remote without a user into an ssh URL`() {
        val result = normalize(vcsUrl = "gitlab.example.com:team/repo.git")

        assertThat(referenceUrls(result)).containsExactly("ssh://gitlab.example.com/team/repo.git")
    }

    // --- Output ---

    @Test
    fun `is idempotent`() {
        val once = normalize(vcsUrl = "git@github.com:hivemq/app.git")

        val twice = SbomNormalizer.normalize(BomJson.read(once), timestamp, "git@github.com:hivemq/app.git")

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `tolerates a BOM without dependencies and without a serial number`() {
        val input = """{"bomFormat":"CycloneDX","specVersion":"1.6"}"""

        val result = tree(normalize(input.toByteArray()))

        assertThat(result["serialNumber"].asText()).startsWith("urn:uuid:")
    }

    @Test
    fun `writes LF line endings and a trailing newline`() {
        val text = String(normalize())

        assertThat(text).doesNotContain("\r").endsWith("}\n")
    }
}
