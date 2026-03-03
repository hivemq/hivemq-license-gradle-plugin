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
package com.hivemq.license.gradle

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DependencyReportTest {

    private val objectMapper = ObjectMapper()

    // --- Bom ---

    @Test
    fun `deserializes empty BOM`() {
        val bom = objectMapper.readValue("""{"components":[]}""", DependencyReport.Bom::class.java)
        assertThat(bom.components).isEmpty()
    }

    @Test
    fun `deserializes BOM with single component`() {
        val json = """
            {
              "components": [
                {
                  "group": "com.google.guava",
                  "name": "guava",
                  "version": "32.1.3-jre",
                  "licenses": []
                }
              ]
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        assertThat(bom.components).hasSize(1)
        assertThat(bom.components[0].group).isEqualTo("com.google.guava")
        assertThat(bom.components[0].name).isEqualTo("guava")
        assertThat(bom.components[0].version).isEqualTo("32.1.3-jre")
    }

    @Test
    fun `deserializes BOM with multiple components`() {
        val json = """
            {
              "components": [
                {
                  "group": "com.google.guava",
                  "name": "guava",
                  "version": "32.1.3-jre",
                  "licenses": []
                },
                {
                  "group": "org.slf4j",
                  "name": "slf4j-api",
                  "version": "2.0.9",
                  "licenses": []
                }
              ]
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        assertThat(bom.components).hasSize(2)
    }

    @Test
    fun `deserializes BOM ignoring unknown fields`() {
        val json = """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.4",
              "serialNumber": "urn:uuid:abc",
              "version": 1,
              "components": []
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        assertThat(bom.components).isEmpty()
    }

    @Test
    fun `BOM with empty components`() {
        val json = """{"components": []}"""
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        assertThat(bom.components).isEmpty()
    }

    // --- Component ---

    @Test
    fun `component with null group`() {
        val json = """
            {
              "components": [
                {
                  "name": "standalone-lib",
                  "version": "1.0.0"
                }
              ]
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        assertThat(bom.components[0].group).isNull()
    }

    @Test
    fun `component with null licenses`() {
        val json = """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "lib",
                  "version": "1.0.0"
                }
              ]
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        assertThat(bom.components[0].licenses).isNull()
    }

    @Test
    fun `component ignores unknown fields`() {
        val json = """
            {
              "components": [
                {
                  "type": "library",
                  "bom-ref": "pkg:maven/com.example/lib@1.0.0",
                  "group": "com.example",
                  "name": "lib",
                  "version": "1.0.0",
                  "purl": "pkg:maven/com.example/lib@1.0.0",
                  "licenses": []
                }
              ]
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        assertThat(bom.components[0].group).isEqualTo("com.example")
        assertThat(bom.components[0].name).isEqualTo("lib")
        assertThat(bom.components[0].version).isEqualTo("1.0.0")
    }

    // --- LicenseChoice ---

    @Test
    fun `license choice with license entry`() {
        val json = """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        val licenseChoice = bom.components[0].licenses!![0]
        assertThat(licenseChoice.license?.id).isEqualTo("Apache-2.0")
        assertThat(licenseChoice.expression).isNull()
    }

    @Test
    fun `license choice with expression`() {
        val json = """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "expression": "Apache-2.0 OR MIT" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        val licenseChoice = bom.components[0].licenses!![0]
        assertThat(licenseChoice.license).isNull()
        assertThat(licenseChoice.expression).isEqualTo("Apache-2.0 OR MIT")
    }

    @Test
    fun `license choice with null license and null expression`() {
        val json = """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "lib",
                  "version": "1.0.0",
                  "licenses": [
                    {}
                  ]
                }
              ]
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        val licenseChoice = bom.components[0].licenses!![0]
        assertThat(licenseChoice.license).isNull()
        assertThat(licenseChoice.expression).isNull()
    }

    // --- LicenseEntry ---

    @Test
    fun `license entry with id only`() {
        val json = """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "MIT" } }
                  ]
                }
              ]
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        val entry = bom.components[0].licenses!![0].license!!
        assertThat(entry.id).isEqualTo("MIT")
        assertThat(entry.name).isNull()
        assertThat(entry.url).isNull()
    }

    @Test
    fun `license entry with name only`() {
        val json = """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "name": "Apache License 2.0" } }
                  ]
                }
              ]
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        val entry = bom.components[0].licenses!![0].license!!
        assertThat(entry.id).isNull()
        assertThat(entry.name).isEqualTo("Apache License 2.0")
        assertThat(entry.url).isNull()
    }

    @Test
    fun `license entry with all fields`() {
        val json = """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "Apache-2.0", "name": "Apache License 2.0", "url": "https://example.com" } }
                  ]
                }
              ]
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        val entry = bom.components[0].licenses!![0].license!!
        assertThat(entry.id).isEqualTo("Apache-2.0")
        assertThat(entry.name).isEqualTo("Apache License 2.0")
        assertThat(entry.url).isEqualTo("https://example.com")
    }

    @Test
    fun `license entry ignores unknown fields`() {
        val json = """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "MIT", "text": { "content": "full text here" }, "acknowledgement": "declared" } }
                  ]
                }
              ]
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        val entry = bom.components[0].licenses!![0].license!!
        assertThat(entry.id).isEqualTo("MIT")
    }

    @Test
    fun `component with multiple license choices`() {
        val json = """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "dual-license-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "EPL-2.0" } },
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
        """.trimIndent()
        val bom = objectMapper.readValue(json, DependencyReport.Bom::class.java)
        val licenses = bom.components[0].licenses!!
        assertThat(licenses).hasSize(2)
        assertThat(licenses[0].license?.id).isEqualTo("EPL-2.0")
        assertThat(licenses[1].license?.id).isEqualTo("Apache-2.0")
    }
}
