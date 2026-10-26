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

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class SbomNormalizerTest {

    private val mapper = ObjectMapper()
    private val timestamp: Instant = Instant.parse("2026-01-02T03:04:05Z")

    /** A BOM the way the CycloneDX Gradle plugin writes it: random serial, wall-clock timestamp, CI references. */
    private fun bom(
        serial: String = "urn:uuid:fcc72669-6094-47f1-a9fe-81e7c08e2d54",
        timestamp: String = "2026-10-16T10:01:10Z",
        vcs: String = "ssh://git@github.com:hivemq/hivemq-kafka-extension.git",
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
                { "type": "vcs", "url": "$vcs" }
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

    private fun normalize(input: ByteArray = bom()) = SbomNormalizer.normalize(input, timestamp)

    private fun tree(bytes: ByteArray) = mapper.readTree(bytes)

    // --- Timestamp ---

    @Test
    fun `sets the timestamp in ISO-8601 UTC without fractions`() {
        val result = tree(normalize())

        assertThat(result["metadata"]["timestamp"].asText()).isEqualTo("2026-01-02T03:04:05Z")
    }

    @Test
    fun `truncates a timestamp with nanoseconds to seconds`() {
        val result = tree(SbomNormalizer.normalize(bom(), Instant.parse("2026-01-02T03:04:05.678Z")))

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
    fun `leaves a dependsOn list with a non-text entry unsorted`() {
        val result = tree(normalize(bom(dependsOn = """["pkg:maven/b/b@1", 1, "pkg:maven/a/a@1"]""")))

        assertThat(result["dependencies"][1]["dependsOn"].map { it.asText() })
            .containsExactly("pkg:maven/b/b@1", "1", "pkg:maven/a/a@1")
    }

    @Test
    fun `sorts the dependencies by ref`() {
        val result = tree(normalize())

        assertThat(result["dependencies"].map { it["ref"].asText() })
            .containsExactly("pkg:maven/a/a@1", "pkg:maven/com.hivemq/app@1.0.0?project_path=%3A")
    }

    @Test
    fun `sorts the dependsOn lists`() {
        val result = tree(normalize())

        assertThat(result["dependencies"][1]["dependsOn"].map { it.asText() })
            .containsExactly("pkg:maven/a/a@1", "pkg:maven/b/b@1")
    }

    // --- External references ---

    @Test
    fun `canonicalizes a GitHub https remote with a git suffix`() {
        val result = tree(normalize(bom(vcs = "https://github.com/hivemq/hivemq-kafka-extension.git")))

        assertThat(result["metadata"]["component"]["externalReferences"][0]["url"].asText())
            .isEqualTo("https://github.com/hivemq/hivemq-kafka-extension")
    }

    @Test
    fun `canonicalizes a GitHub ssh remote to https`() {
        val result = tree(normalize())

        assertThat(result["metadata"]["component"]["externalReferences"].map { it["url"].asText() })
            .containsExactly("https://github.com/hivemq/hivemq-kafka-extension")
    }

    @Test
    fun `canonicalizes a plain git ssh remote`() {
        val result = tree(normalize(bom(vcs = "git@github.com:hivemq/hivemq-kafka-extension.git")))

        assertThat(result["metadata"]["component"]["externalReferences"][0]["url"].asText())
            .isEqualTo("https://github.com/hivemq/hivemq-kafka-extension")
    }

    @Test
    fun `leaves a non-GitHub vcs url alone`() {
        val result = tree(normalize(bom(vcs = "https://gitlab.example.com/team/app.git")))

        assertThat(result["metadata"]["component"]["externalReferences"][0]["url"].asText())
            .isEqualTo("https://gitlab.example.com/team/app.git")
    }

    @Test
    fun `leaves a vcs reference with a non-text url alone`() {
        val input = """{"metadata":{"component":{"externalReferences":[{"type":"vcs","url":5}]}}}"""

        val result = tree(normalize(input.toByteArray()))

        assertThat(result["metadata"]["component"]["externalReferences"][0]["url"].isInt).isTrue()
    }

    @Test
    fun `leaves a vcs reference without a url alone`() {
        val input = """{"metadata":{"component":{"externalReferences":[{"type":"vcs"}]}}}"""

        val result = tree(normalize(input.toByteArray()))

        assertThat(result["metadata"]["component"]["externalReferences"][0].has("url")).isFalse()
    }

    @Test
    fun `removes the build-system reference`() {
        val result = tree(normalize())

        assertThat(result["metadata"]["component"]["externalReferences"].map { it["type"].asText() })
            .containsExactly("vcs")
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

        val result = tree(normalize(input.toByteArray()))

        val references = result["metadata"]["component"]["externalReferences"]
        assertThat(references[0].asText()).isEqualTo("text")
        assertThat(references[1]["url"].asText()).isEqualTo("https://github.com/a/b")
    }

    // --- Output ---

    @Test
    fun `is idempotent`() {
        val once = normalize()

        val twice = SbomNormalizer.normalize(once, timestamp)

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `rejects input that is not a JSON object`() {
        assertThatThrownBy { normalize("[]".toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("JSON object")
    }

    @Test
    fun `rejects input that is not valid JSON`() {
        assertThatThrownBy { normalize("{".toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not valid JSON")
    }

    @Test
    fun `rejects metadata that is not a JSON object`() {
        assertThatThrownBy { normalize("""{"metadata":[]}""".toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("metadata is not a JSON object")
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
