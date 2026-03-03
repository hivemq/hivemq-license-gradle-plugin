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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LicenseResolverResolveLicensesTest {

    @TempDir
    lateinit var tempDir: File

    private val defaultIgnoredPrefixes = setOf("com.hivemq")
    private val defaultAllowedArtifacts = setOf("com.hivemq:hivemq-mqtt-client")

    private fun writeBom(json: String): File {
        val file = tempDir.resolve("bom.json")
        file.writeText(json)
        return file
    }

    @Test
    fun `resolves single dependency with SPDX ID`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.google.guava",
                  "name": "guava",
                  "version": "32.1.3-jre",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result).hasSize(1)
        val entry = result["com.google.guava:guava"]!!
        assertThat(entry.coordinates.group).isEqualTo("com.google.guava")
        assertThat(entry.coordinates.name).isEqualTo("guava")
        assertThat(entry.coordinates.version).isEqualTo("32.1.3-jre")
        assertThat(entry.license).isEqualTo(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `resolves multiple dependencies sorted by module ID`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "org.slf4j",
                  "name": "slf4j-api",
                  "version": "2.0.9",
                  "licenses": [
                    { "license": { "id": "MIT" } }
                  ]
                },
                {
                  "group": "com.fasterxml.jackson.core",
                  "name": "jackson-databind",
                  "version": "2.15.0",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result).hasSize(2)
        val keys = result.keys.toList()
        assertThat(keys[0]).isEqualTo("com.fasterxml.jackson.core:jackson-databind")
        assertThat(keys[1]).isEqualTo("org.slf4j:slf4j-api")
    }

    @Test
    fun `ignores com_hivemq artifacts`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-extension-sdk",
                  "version": "4.0.0",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                },
                {
                  "group": "com.google.guava",
                  "name": "guava",
                  "version": "32.1.3-jre",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result).hasSize(1)
        assertThat(result).containsKey("com.google.guava:guava")
        assertThat(result).doesNotContainKey("com.hivemq:hivemq-extension-sdk")
    }

    @Test
    fun `includes allowed artifact hivemq-mqtt-client`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-mqtt-client",
                  "version": "1.3.0",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result).hasSize(1)
        assertThat(result).containsKey("com.hivemq:hivemq-mqtt-client")
    }

    @Test
    fun `skips components without group`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "name": "no-group-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "MIT" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        assertThat(result).isEmpty()
    }

    @Test
    fun `chooses preferred license when multiple are present`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "javax.annotation",
                  "name": "javax.annotation-api",
                  "version": "1.3.2",
                  "licenses": [
                    { "license": { "id": "EPL-2.0" } },
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["javax.annotation:javax.annotation-api"]!!.license).isEqualTo(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `throws on dependency with no licenses`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "no-license-lib",
                  "version": "1.0.0",
                  "licenses": []
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No license found")
    }

    @Test
    fun `throws on dependency with only unapproved licenses`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "proprietary-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "name": "Some Proprietary License" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list")
    }

    @Test
    fun `resolves empty BOM`() {
        val bom = writeBom("""{ "components": [] }""")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        assertThat(result).isEmpty()
    }

    @Test
    fun `override applies when component has no licenses in BOM`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "javax.annotation",
                  "name": "javax.annotation-api",
                  "version": "1.3.2",
                  "licenses": []
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            overriddenLicenses = mapOf("javax.annotation:javax.annotation-api" to "CDDL-1.1"),
        )

        assertThat(result).hasSize(1)
        assertThat(result["javax.annotation:javax.annotation-api"]!!.license).isEqualTo(KnownLicense.CDDL_1_1)
    }

    @Test
    fun `override applies even when component has licenses`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "javax.annotation",
                  "name": "javax.annotation-api",
                  "version": "1.3.2",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            overriddenLicenses = mapOf("javax.annotation:javax.annotation-api" to "CDDL-1.1"),
        )

        assertThat(result).hasSize(1)
        assertThat(result["javax.annotation:javax.annotation-api"]!!.license).isEqualTo(KnownLicense.CDDL_1_1)
    }

    @Test
    fun `override with unknown SPDX ID fails with clear error`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "javax.annotation",
                  "name": "javax.annotation-api",
                  "version": "1.3.2",
                  "licenses": []
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(
                bom,
                defaultIgnoredPrefixes,
                defaultAllowedArtifacts,
                overriddenLicenses = mapOf("javax.annotation:javax.annotation-api" to "UNKNOWN-LICENSE"),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Overridden license 'UNKNOWN-LICENSE'")
            .hasMessageContaining("is not a known SPDX license ID")
    }

    @Test
    fun `resolves name-based license matching`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "some-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "name": "The Apache Software License, Version 2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result).hasSize(1)
        assertThat(result["com.example:some-lib"]!!.license).isEqualTo(KnownLicense.APACHE_2_0)
    }
}
