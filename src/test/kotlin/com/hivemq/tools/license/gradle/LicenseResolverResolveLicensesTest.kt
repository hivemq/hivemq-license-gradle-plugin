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

    /** A BOM with one component, `com.example:expr-lib:1.0.0`, declaring its license as [expression]. */
    private fun writeExpressionBom(expression: String) = writeBom(
        """
        {
          "components": [
            {
              "group": "com.example",
              "name": "expr-lib",
              "version": "1.0.0",
              "licenses": [
                { "expression": "$expression" }
              ]
            }
          ]
        }
        """.trimIndent()
    )

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
        assertThat(entry.licenses).containsExactly(KnownLicense.APACHE_2_0)
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
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:dual-license-lib"]!!.licenses).containsExactly(KnownLicense.APACHE_2_0)
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
        assertThat(result["javax.annotation:javax.annotation-api"]!!.licenses).containsExactly(KnownLicense.CDDL_1_1)
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
        assertThat(result["javax.annotation:javax.annotation-api"]!!.licenses).containsExactly(KnownLicense.CDDL_1_1)
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
    fun `reports an unknown overridden license together with the other problems`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "overridden-lib",
                  "version": "1.0.0",
                  "licenses": []
                },
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
            LicenseResolver.resolveLicenses(
                bom,
                defaultIgnoredPrefixes,
                defaultAllowedArtifacts,
                overriddenLicenses = mapOf("com.example:overridden-lib" to "UNKNOWN-LICENSE"),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("2 license problems found in '${bom.absolutePath}':")
            .hasMessageContaining("Overridden license 'UNKNOWN-LICENSE' for 'com.example:overridden-lib' is not a known SPDX license ID")
            .hasMessageNotContaining("overridden-lib:1.0.0")
            .hasMessageContaining("No license found for 'com.example:no-license-lib:1.0.0'")
    }

    @Test
    fun `built-in license resolves when component has no licenses`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "javax.annotation",
                  "name": "javax.annotation-api",
                  "version": "1.3.2"
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result).hasSize(1)
        assertThat(result["javax.annotation:javax.annotation-api"]!!.licenses).containsExactly(KnownLicense.CDDL_1_1)
    }

    @Test
    fun `built-in license resolves when component has empty licenses`() {
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

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result).hasSize(1)
        assertThat(result["javax.annotation:javax.annotation-api"]!!.licenses).containsExactly(KnownLicense.CDDL_1_1)
    }

    @Test
    fun `user override takes precedence over built-in license`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "javax.annotation",
                  "name": "javax.annotation-api",
                  "version": "1.3.2"
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            overriddenLicenses = mapOf("javax.annotation:javax.annotation-api" to "Apache-2.0"),
        )

        assertThat(result).hasSize(1)
        assertThat(result["javax.annotation:javax.annotation-api"]!!.licenses).containsExactly(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `excluded dependency resolved from overriddenLicenses`() {
        val bom = writeBom("""{ "components": [] }""")

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            overriddenLicenses = mapOf("org.jline:jline" to "BSD-3-Clause"),
            excludedDependencies = mapOf("org.jline:jline" to "4.0.2"),
        )

        assertThat(result).hasSize(1)
        val entry = result["org.jline:jline"]!!
        assertThat(entry.coordinates.group).isEqualTo("org.jline")
        assertThat(entry.coordinates.name).isEqualTo("jline")
        assertThat(entry.coordinates.version).isEqualTo("4.0.2")
        assertThat(entry.licenses).containsExactly(KnownLicense.BSD_3_CLAUSE)
    }

    @Test
    fun `excluded dependency resolved from BUILT_IN_LICENSES`() {
        val bom = writeBom("""{ "components": [] }""")

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            excludedDependencies = mapOf("javax.annotation:javax.annotation-api" to "1.3.2"),
        )

        assertThat(result).hasSize(1)
        val entry = result["javax.annotation:javax.annotation-api"]!!
        assertThat(entry.coordinates.version).isEqualTo("1.3.2")
        assertThat(entry.licenses).containsExactly(KnownLicense.CDDL_1_1)
    }

    @Test
    fun `excluded dependency overriddenLicenses takes precedence over BUILT_IN_LICENSES`() {
        val bom = writeBom("""{ "components": [] }""")

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            overriddenLicenses = mapOf("javax.annotation:javax.annotation-api" to "Apache-2.0"),
            excludedDependencies = mapOf("javax.annotation:javax.annotation-api" to "1.3.2"),
        )

        assertThat(result).hasSize(1)
        assertThat(result["javax.annotation:javax.annotation-api"]!!.licenses).containsExactly(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `excluded dependency fails when not in overriddenLicenses or BUILT_IN_LICENSES`() {
        val bom = writeBom("""{ "components": [] }""")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(
                bom,
                defaultIgnoredPrefixes,
                defaultAllowedArtifacts,
                excludedDependencies = mapOf("com.unknown:unknown-lib" to "1.0.0"),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Excluded dependency")
            .hasMessageContaining("com.unknown:unknown-lib")
            .hasMessageContaining("no license defined")
    }

    @Test
    fun `excluded dependency fails when overriddenLicense has unknown SPDX ID`() {
        val bom = writeBom("""{ "components": [] }""")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(
                bom,
                defaultIgnoredPrefixes,
                defaultAllowedArtifacts,
                overriddenLicenses = mapOf("org.jline:jline" to "INVALID-LICENSE"),
                excludedDependencies = mapOf("org.jline:jline" to "4.0.2"),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("1 license problem found in")
            .hasMessageContaining("Overridden license 'INVALID-LICENSE'")
            .hasMessageContaining("is not a known SPDX license ID")
    }

    @Test
    fun `excluded dependencies are merged with BOM dependencies`() {
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

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            overriddenLicenses = mapOf("org.jline:jline" to "BSD-3-Clause"),
            excludedDependencies = mapOf("org.jline:jline" to "4.0.2"),
        )

        assertThat(result).hasSize(2)
        assertThat(result).containsKey("com.google.guava:guava")
        assertThat(result).containsKey("org.jline:jline")
    }

    @Test
    fun `multiple excluded dependencies are all resolved`() {
        val bom = writeBom("""{ "components": [] }""")

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            overriddenLicenses = mapOf(
                "org.jline:jline" to "BSD-3-Clause",
                "org.jline:jline-picocli" to "BSD-3-Clause",
            ),
            excludedDependencies = mapOf(
                "org.jline:jline" to "4.0.2",
                "org.jline:jline-picocli" to "4.0.2",
            ),
        )

        assertThat(result).hasSize(2)
        assertThat(result["org.jline:jline"]!!.licenses).containsExactly(KnownLicense.BSD_3_CLAUSE)
        assertThat(result["org.jline:jline-picocli"]!!.licenses).containsExactly(KnownLicense.BSD_3_CLAUSE)
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
        assertThat(result["com.example:some-lib"]!!.licenses).containsExactly(KnownLicense.APACHE_2_0)
    }

    // --- SPDX expressions ---

    @Test
    fun `resolves a component that declares an SPDX expression`() {
        // what cyclonedx-gradle-plugin writes for javax.servlet:javax.servlet-api, whose POM names
        // "CDDL + GPLv2 with classpath exception"
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "dual-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "expression": "(CDDL-1.0 OR GPL-2.0-with-classpath-exception)" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result).hasSize(1)
        assertThat(result["com.example:dual-lib"]!!.licenses).containsExactly(KnownLicense.CDDL_1_0)
    }

    @Test
    fun `chooses the preferred license from an SPDX expression`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "dual-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "expression": "EPL-2.0 OR Apache-2.0" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:dual-lib"]!!.licenses).containsExactly(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `resolves an SPDX conjunction when every license is approved`() {
        val bom = writeExpressionBom("MIT AND ISC")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.MIT, KnownLicense.ISC)
    }

    @Test
    fun `rejects an SPDX conjunction with an unapproved license`() {
        val bom = writeExpressionBom("MIT AND GPL-3.0-only")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT AND GPL-3.0-only")
    }

    @Test
    fun `binds AND tighter than OR in an SPDX expression`() {
        val bom = writeExpressionBom("GPL-3.0-only OR MIT AND ISC")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.MIT, KnownLicense.ISC)
    }

    @Test
    fun `rejects an SPDX conjunction whose grouped alternative is approved but whose other operand is not`() {
        val bom = writeExpressionBom("(GPL-3.0-only OR MIT) AND GPL-2.0-only")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': (GPL-3.0-only OR MIT) AND GPL-2.0-only")
    }

    @Test
    fun `tolerates spaces inside the parentheses of an SPDX expression`() {
        val bom = writeExpressionBom("( MIT OR Apache-2.0 )")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `rejects an SPDX license with an exception`() {
        val bom = writeExpressionBom("GPL-2.0-only WITH Classpath-exception-2.0")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': GPL-2.0-only WITH Classpath-exception-2.0")
    }

    @Test
    fun `resolves an SPDX disjunction with an unapproved alternative`() {
        val bom = writeExpressionBom("GPL-3.0-only OR MIT")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.MIT)
    }

    @Test
    fun `rejects an SPDX expression with a stray closing parenthesis`() {
        val bom = writeExpressionBom("MIT OR )")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT OR )")
    }

    @Test
    fun `rejects an SPDX expression with an operator in place of a license`() {
        val bom = writeExpressionBom("MIT OR AND")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT OR AND")
    }

    @Test
    fun `rejects an SPDX expression with a missing operand`() {
        val bom = writeExpressionBom("MIT OR")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT OR")
    }

    @Test
    fun `rejects an SPDX expression with an unclosed parenthesis`() {
        val bom = writeExpressionBom("(MIT OR Apache-2.0")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': (MIT OR Apache-2.0")
    }

    @Test
    fun `rejects an SPDX expression with tokens after a complete expression`() {
        val bom = writeExpressionBom("MIT Apache-2.0")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT Apache-2.0")
    }

    @Test
    fun `prefers the SPDX alternative with the fewest obligations`() {
        val bom = writeExpressionBom("(Apache-2.0 AND ISC) OR MIT")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.MIT)
    }

    @Test
    fun `resolves an SPDX conjunction with a grouped disjunction`() {
        val bom = writeExpressionBom("MIT AND (ISC OR Apache-2.0)")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.APACHE_2_0, KnownLicense.MIT)
    }

    @Test
    fun `prefers the SPDX alternative with fewer licenses when obligations are equal`() {
        val bom = writeExpressionBom("(Apache-2.0 AND MIT) OR MIT")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.MIT)
    }

    @Test
    fun `rejects an SPDX expression with a parenthesis in place of an exception`() {
        val bom = writeExpressionBom("Apache-2.0 OR MIT WITH (")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': Apache-2.0 OR MIT WITH (")
    }

    @Test
    fun `rejects an SPDX expression with a bare WITH`() {
        val bom = writeExpressionBom("MIT WITH")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT WITH")
    }

    @Test
    fun `treats a license entry and an expression entry as alternatives`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "expr-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "name": "Some Proprietary License" } },
                    { "expression": "MIT AND ISC" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.MIT, KnownLicense.ISC)
    }

    @Test
    fun `names every entry when neither a license entry nor an expression entry is approved`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "expr-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "name": "Some Proprietary License" } },
                    { "expression": "GPL-3.0-only" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': Some Proprietary License, GPL-3.0-only")
    }

    @Test
    fun `ranks the alternatives of an SPDX expression only once it is complete`() {
        val bom = writeExpressionBom("MIT AND (Apache-2.0 OR MIT)")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.MIT)
    }

    @Test
    fun `ranks the alternatives of an SPDX expression with a shared operand once it is complete`() {
        val bom = writeExpressionBom("ISC AND (MIT OR ISC)")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.ISC)
    }

    @Test
    fun `rejects an SPDX expression with an id outside the SPDX character set`() {
        val bom = writeExpressionBom("MIT OR ???")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT OR ???")
    }

    @Test
    fun `rejects an SPDX expression with a slash in place of an operator`() {
        val bom = writeExpressionBom("MIT OR MIT/ISC")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT OR MIT/ISC")
    }

    @Test
    fun `keeps only the alternatives of an SPDX expression that no smaller alternative covers`() {
        val bom = writeExpressionBom("(Apache-2.0 OR MIT) AND (Apache-2.0 OR ISC) AND (Apache-2.0 OR BSD-3-Clause)")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `resolves an SPDX license reference as unknown rather than malformed`() {
        val bom = writeExpressionBom("MIT OR DocumentRef-spdx:LicenseRef-custom")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.MIT)
    }

    @Test
    fun `resolves an SPDX id with a plus suffix as unknown rather than malformed`() {
        val bom = writeExpressionBom("MIT OR GPL-2.0+")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.MIT)
    }

    @Test
    fun `rejects an SPDX expression with a lone plus as an id`() {
        val bom = writeExpressionBom("MIT OR +")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT OR +")
    }

    @Test
    fun `rejects an SPDX expression with a doubled plus suffix`() {
        val bom = writeExpressionBom("MIT OR GPL-2.0-only++")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT OR GPL-2.0-only++")
    }

    @Test
    fun `rejects an SPDX expression with a colon as an exception id`() {
        val bom = writeExpressionBom("MIT OR MIT WITH :")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT OR MIT WITH :")
    }

    @Test
    fun `rejects an SPDX expression with more distinct alternatives than it evaluates`() {
        // eleven pairs of distinct approved licenses have 2048 ways to be satisfied, none covering another
        val ids = LicenseResolver.SPDX_ID_MAP.keys.filter { ' ' !in it }.take(22)
        val expression = ids.chunked(2).joinToString(" AND ") { (a, b) -> "($a OR $b)" }
        val bom = writeExpressionBom(expression)

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': $expression")
    }

    @Test
    fun `matches SPDX ids in an expression regardless of case`() {
        val bom = writeExpressionBom("mit AND isc")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.MIT, KnownLicense.ISC)
    }

    @Test
    fun `ranks SPDX alternatives regardless of the case of their ids`() {
        val bom = writeExpressionBom("MIT OR apache-2.0")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `rejects an SPDX license reference without a name`() {
        val bom = writeExpressionBom("MIT OR LicenseRef-")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT OR LicenseRef-")
    }

    @Test
    fun `rejects an SPDX license reference with a plus suffix`() {
        val bom = writeExpressionBom("MIT OR LicenseRef-custom+")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT OR LicenseRef-custom+")
    }

    @Test
    fun `gives up on an SPDX expression that would expand beyond its budget before pruning it`() {
        // ten pairs of distinct approved licenses, conjoined with themselves: a million candidates that prune back to
        // the same thousand alternatives
        val ids = LicenseResolver.SPDX_ID_MAP.keys.filter { ' ' !in it }.take(20)
        val group = ids.chunked(2).joinToString(" AND ") { (a, b) -> "($a OR $b)" }
        val expression = "($group) AND ($group)"
        val bom = writeExpressionBom(expression)

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': $expression")
    }

    @Test
    fun `resolves an SPDX expression nested in a few parentheses`() {
        val bom = writeExpressionBom("(((MIT)))")

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.MIT)
    }

    @Test
    fun `rejects an SPDX expression nested deeper than it evaluates`() {
        // deep enough to overflow the stack if every parenthesis were parsed by recursion
        val expression = "(".repeat(10_000) + "MIT" + ")".repeat(10_000)
        val bom = writeExpressionBom(expression)

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(
                "License not on the approved list for 'com.example:expr-lib:1.0.0': $expression (SPDX expression too complex to evaluate)"
            )
    }

    @Test
    fun `says when an SPDX expression is malformed`() {
        val bom = writeExpressionBom("MIT OR )")

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:expr-lib:1.0.0': MIT OR ) (malformed SPDX expression)")
    }

    @Test
    fun `says when an SPDX expression is too complex to evaluate`() {
        val ids = LicenseResolver.SPDX_ID_MAP.keys.filter { ' ' !in it }.take(22)
        val expression = ids.chunked(2).joinToString(" AND ") { (a, b) -> "($a OR $b)" }
        val bom = writeExpressionBom(expression)

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(": $expression (SPDX expression too complex to evaluate)")
    }

    @Test
    fun `reports the SPDX expression when none of its licenses is approved`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "copyleft-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "expression": "(GPL-2.0-only OR GPL-3.0-only)" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:copyleft-lib:1.0.0': (GPL-2.0-only OR GPL-3.0-only)")
    }

    // --- failure reporting ---

    @Test
    fun `reports every offending component in one failure`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "no-license-lib",
                  "version": "1.0.0",
                  "licenses": []
                },
                {
                  "group": "com.example",
                  "name": "proprietary-lib",
                  "version": "2.0.0",
                  "licenses": [
                    { "license": { "name": "Some Proprietary License" } }
                  ]
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
                excludedDependencies = mapOf("com.unknown:unknown-lib" to "3.0.0"),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No license found for 'com.example:no-license-lib:1.0.0'")
            .hasMessageContaining("License not on the approved list for 'com.example:proprietary-lib:2.0.0': Some Proprietary License")
            .hasMessageContaining("Excluded dependency 'com.unknown:unknown-lib:3.0.0' has no license defined in overriddenLicenses or BUILT_IN_LICENSES")
    }

    @Test
    fun `names the BOM file in the failure`() {
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
            .hasMessageContaining(bom.absolutePath)
    }

    @Test
    fun `names unresolved metadata recorded in the BOM`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "unreadable-lib",
                  "version": "1.0.0",
                  "licenses": [],
                  "properties": [
                    { "name": "cdx:maven:package:metadata-unresolved", "value": "pom-unparseable" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No license found for 'com.example:unreadable-lib:1.0.0', its Maven metadata is incomplete (pom-unparseable)")
    }

    @Test
    fun `names unresolved metadata on a component whose licenses are not approved`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "partial-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "name": "Some Proprietary License" } }
                  ],
                  "properties": [
                    { "name": "cdx:maven:package:metadata-unresolved", "value": "parent-unresolved" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:partial-lib:1.0.0': Some Proprietary License, its Maven metadata is incomplete (parent-unresolved)")
    }

    @Test
    fun `resolves a component with an approved license despite unresolved metadata`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "partial-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ],
                  "properties": [
                    { "name": "cdx:maven:package:metadata-unresolved", "value": "model-incomplete" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result).hasSize(1)
        assertThat(result["com.example:partial-lib"]!!.licenses).containsExactly(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `lists one problem per line under a header that counts them`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "no-license-lib",
                  "version": "1.0.0",
                  "licenses": []
                },
                {
                  "group": "com.example",
                  "name": "proprietary-lib",
                  "version": "2.0.0",
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
            .hasMessage(
                "2 license problems found in '${bom.absolutePath}':\n" +
                        "  No license found for 'com.example:no-license-lib:1.0.0'\n" +
                        "  License not on the approved list for 'com.example:proprietary-lib:2.0.0': Some Proprietary License"
            )
    }

    @Test
    fun `names the origin of the BOM when given`() {
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
            LicenseResolver.resolveLicenses(
                bom,
                defaultIgnoredPrefixes,
                defaultAllowedArtifacts,
                dependencyLicenseOrigin = { "restored from the build cache" },
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("1 license problem found in '${bom.absolutePath}' (restored from the build cache):")
    }

    @Test
    fun `does not ask for the origin of the BOM when every license resolves`() {
        // finding out the origin may wait for a build event, so it must only happen when the message is needed
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

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            dependencyLicenseOrigin = { error("the origin must not be asked for when nothing fails") },
        )

        assertThat(result).hasSize(1)
    }

    @Test
    fun `counts a single problem in the singular`() {
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
            .hasMessageContaining("1 license problem found in '")
    }

    // --- pnpm reports (no "components" key) ---

    @Test
    fun `resolves pnpm licenses when the report has no components`() {
        val report = writeBom(
            """
            {
              "MIT": [
                { "name": "react", "versions": ["18.2.0"], "license": "MIT" }
              ],
              "ISC": [
                { "name": "lru-cache", "versions": ["10.2.0"], "license": "ISC" }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(report, emptySet(), emptySet())

        assertThat(result).hasSize(2)
        assertThat(result["react"]!!.coordinates.version).isEqualTo("18.2.0")
        assertThat(result["react"]!!.licenses.single().id).isEqualTo("MIT")
        assertThat(result["lru-cache"]!!.licenses.single().id).isEqualTo("ISC")
    }

    @Test
    fun `pnpm ignores scoped packages via ignoredGroupPrefixes`() {
        val report = writeBom(
            """
            {
              "MIT": [
                { "name": "react", "versions": ["18.2.0"], "license": "MIT" },
                { "name": "@example/internal-widget", "versions": ["1.0.0"], "license": "MIT" }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(report, setOf("@example/"), emptySet())

        assertThat(result).containsOnlyKeys("react")
    }

    @Test
    fun `pnpm resolves compound SPDX expression`() {
        val report = writeBom(
            """
            {
              "bucket": [
                { "name": "some-pkg", "versions": ["1.0.0"], "license": "Apache-2.0 AND MIT" }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(report, emptySet(), emptySet())

        assertThat(result).hasSize(1)
        assertThat(result["some-pkg"]!!.licenses).containsExactly(KnownLicense.APACHE_2_0, KnownLicense.MIT)
    }

    @Test
    fun `pnpm rejects a compound SPDX expression with an unapproved license`() {
        val report = writeBom(
            """
            {
              "bucket": [
                { "name": "some-pkg", "versions": ["1.0.0"], "license": "MIT AND GPL-3.0-only" }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(report, emptySet(), emptySet())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'some-pkg:1.0.0': MIT AND GPL-3.0-only")
    }

    @Test
    fun `pnpm resolves a license id that contains a space`() {
        val report = writeBom(
            """
            {
              "Public Domain": [
                { "name": "free-pkg", "versions": ["1.0.0"], "license": "Public Domain" }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(report, emptySet(), emptySet())

        assertThat(result["free-pkg"]!!.licenses).containsExactly(KnownLicense.PUBLIC_DOMAIN)
    }

    @Test
    fun `pnpm resolves a license id that contains a space inside a compound expression`() {
        val report = writeBom(
            """
            {
              "bucket": [
                { "name": "free-pkg", "versions": ["1.0.0"], "license": "Public Domain OR GPL-3.0-only" },
                { "name": "other-pkg", "versions": ["1.0.0"], "license": "MIT AND Public Domain" }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(report, emptySet(), emptySet())

        assertThat(result["free-pkg"]!!.licenses).containsExactly(KnownLicense.PUBLIC_DOMAIN)
        assertThat(result["other-pkg"]!!.licenses).containsExactly(KnownLicense.MIT, KnownLicense.PUBLIC_DOMAIN)
    }

    @Test
    fun `pnpm rejects a license id that contains a space when an operator is glued to it`() {
        val report = writeBom(
            """
            {
              "bucket": [
                { "name": "glued-pkg", "versions": ["1.0.0"], "license": "MIT AND Public DomainOR GPL-3.0-only" }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(report, emptySet(), emptySet())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'glued-pkg:1.0.0': MIT AND Public DomainOR GPL-3.0-only")
    }

    @Test
    fun `pnpm says when a license expression is malformed`() {
        val report = writeBom(
            """
            {
              "bucket": [
                { "name": "odd-pkg", "versions": ["1.0.0"], "license": "MIT OR )" }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(report, emptySet(), emptySet())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'odd-pkg:1.0.0': MIT OR ) (malformed SPDX expression)")
    }

    @Test
    fun `pnpm reports an unknown overridden license and resolves the package no further`() {
        val report = writeBom(
            """
            {
              "MIT": [
                { "name": "odd-pkg", "versions": ["1.0.0"], "license": "MIT" }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(
                report,
                emptySet(),
                emptySet(),
                overriddenLicenses = mapOf("odd-pkg" to "NOT-A-LICENSE"),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("1 license problem found in '${report.absolutePath}':")
            .hasMessageContaining("Overridden license 'NOT-A-LICENSE' for 'odd-pkg' is not a known SPDX license ID")
    }

    @Test
    fun `pnpm applies overriddenLicenses`() {
        val report = writeBom(
            """
            {
              "Unknown": [
                { "name": "mystery-pkg", "versions": ["2.0.0"], "license": "Unknown" }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            report,
            emptySet(),
            emptySet(),
            overriddenLicenses = mapOf("mystery-pkg" to "MIT"),
        )

        assertThat(result).hasSize(1)
        assertThat(result["mystery-pkg"]!!.licenses.single().id).isEqualTo("MIT")
    }

    @Test
    fun `pnpm reports every offending package in one failure`() {
        val report = writeBom(
            """
            {
              "Unknown": [
                { "name": "mystery-pkg", "versions": ["2.0.0"], "license": "Unknown" }
              ],
              "Proprietary": [
                { "name": "closed-pkg", "versions": ["1.0.0"], "license": "Proprietary" }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(report, emptySet(), emptySet())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(report.absolutePath)
            .hasMessageContaining("No license found for 'mystery-pkg:2.0.0'")
            .hasMessageContaining("License not on the approved list for 'closed-pkg:1.0.0': Proprietary")
    }
}
