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
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

    /** A BOM with one component, `com.example:lib:1.0.0`, whose license list holds [entries], JSON objects. */
    private fun writeEntriesBom(vararg entries: String) = writeModuleBom("com.example", "lib", *entries)

    /** A BOM with one component, [group]:[name]:1.0.0, whose license list holds [entries], JSON objects. */
    private fun writeModuleBom(group: String, name: String, vararg entries: String) = writeBom(
        """
        {
          "components": [
            {
              "group": "$group",
              "name": "$name",
              "version": "1.0.0",
              "licenses": [
                ${entries.joinToString(",\n                ")}
              ]
            }
          ]
        }
        """.trimIndent()
    )

    /** `com.example:lib` as resolved from a BOM whose license list holds [entries]. */
    private fun libWith(vararg entries: String): ResolvedDependency =
        LicenseResolver.resolveLicenses(writeEntriesBom(*entries), defaultIgnoredPrefixes, defaultAllowedArtifacts)
            .getValue("com.example:lib")

    private fun idEntry(spdxId: String) = """{ "license": { "id": "$spdxId" } }"""

    private fun nameEntry(name: String) = """{ "license": { "name": "$name" } }"""

    private fun expressionEntry(expression: String) = """{ "expression": "$expression" }"""

    /**
     * Eleven pairs of distinct approved licenses joined by AND: 2048 ways to be satisfied, none covering another, more
     * than the parser keeps.
     */
    private fun tooManyAlternatives(): String {
        val ids = LicenseResolver.SPDX_ID_MAP.keys.filter { ' ' !in it }.take(22)
        check(ids.size == 22) { "needs 22 approved licenses with an SPDX id, found ${ids.size}" }
        return ids.chunked(2).joinToString(" AND ") { (a, b) -> "($a OR $b)" }
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

    // --- own modules and nested components ---

    @ParameterizedTest
    @ValueSource(strings = ["1.0", "1.2", "1.2.1"])
    fun `fails for a BOM declaring a CycloneDX version too old for component properties`(specVersion: String) {
        val bom = writeBom(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "$specVersion",
              "components": [
                { "group": "com.hivemq", "name": "hivemq-brand-new", "version": "1.0.0" }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, OwnModules.PUBLIC_HIVEMQ_MODULES)
        }
            .isInstanceOf(IllegalStateException::class.java)
            // such a BOM cannot carry the marker of incomplete metadata, so the module above may be public or internal
            .hasMessageContaining("The BOM '${bom.absolutePath}'")
            .hasMessageContaining("declares CycloneDX specVersion $specVersion, which has no component properties, so ")
            .hasMessageContaining("cannot be told from an internal one; set the schemaVersion of ")
            .hasMessageContaining("generateDependencyBom, or of the task that writes it, to 1.3 or later")
    }

    @Test
    fun `fails for a provided own module that declares a license without being listed`() {
        // a new public SDK the broker provides must be listed, not disappear with the provided modules
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-brand-new-sdk",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
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
                OwnModules.PUBLIC_HIVEMQ_MODULES,
                providedModules = setOf("com.hivemq:hivemq-brand-new-sdk"),
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            // once, although the filter and the resolver both see the module
            .hasMessageContaining("1 license problem found")
            .hasMessageContaining("Own module 'com.hivemq:hivemq-brand-new-sdk' (ignored group prefix 'com.hivemq')")
    }

    @Test
    fun `fails for a published public HiveMQ module that declares an unapproved license`() {
        // the list says which modules are public, not which license they carry; a published POM is read like any other
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-mqtt-client",
                  "version": "1.4.2",
                  "purl": "pkg:maven/com.hivemq/hivemq-mqtt-client@1.4.2?type=jar",
                  "licenses": [ { "license": { "id": "Proprietary-9.9" } } ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, OwnModules.PUBLIC_HIVEMQ_MODULES)
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(
                "License not on the approved list for 'com.hivemq:hivemq-mqtt-client:1.4.2': Proprietary-9.9"
            )
    }

    @Test
    fun `fails for a published public HiveMQ module without a license in the BOM`() {
        // only a project of the build has no POM; a published version without a license is a metadata problem
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-mqtt-client",
                  "version": "1.4.2",
                  "purl": "pkg:maven/com.hivemq/hivemq-mqtt-client@1.4.2?type=jar",
                  "properties": [
                    { "name": "cdx:maven:package:metadata-unresolved", "value": "pom-unresolved" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, OwnModules.PUBLIC_HIVEMQ_MODULES)
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No license found for 'com.hivemq:hivemq-mqtt-client:1.4.2'")
            .hasMessageContaining("(its Maven metadata is incomplete: pom-unresolved)")
    }

    @Test
    fun `fails for a published public HiveMQ module without a license next to the project of the same module`() {
        // the project is what has no POM; the published version next to it is judged on its own
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-extension-sdk",
                  "version": "4.57.0-SNAPSHOT",
                  "purl": "pkg:maven/com.hivemq/hivemq-extension-sdk@4.57.0-SNAPSHOT?project_path=%3Asdk"
                },
                {
                  "group": "com.hivemq",
                  "name": "hivemq-extension-sdk",
                  "version": "4.56.0",
                  "purl": "pkg:maven/com.hivemq/hivemq-extension-sdk@4.56.0?type=jar",
                  "properties": [
                    { "name": "cdx:maven:package:metadata-unresolved", "value": "pom-unresolved" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, OwnModules.PUBLIC_HIVEMQ_MODULES)
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("1 license problem found")
            .hasMessageContaining("No license found for 'com.hivemq:hivemq-extension-sdk:4.56.0'")
    }

    @Test
    fun `fails for an unlisted own module naming the license its other version declares`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-brand-new",
                  "version": "1.0.0",
                  "properties": [
                    { "name": "cdx:maven:package:metadata-unresolved", "value": "pom-unresolved" }
                  ]
                },
                {
                  "group": "com.hivemq",
                  "name": "hivemq-brand-new",
                  "version": "2.0.0",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, OwnModules.PUBLIC_HIVEMQ_MODULES)
        }
            .isInstanceOf(IllegalStateException::class.java)
            // whatever the order of the versions, the license is what tells a public module
            .hasMessageContaining("1 license problem found")
            .hasMessageContaining("declares a license but is not in allowedArtifacts")
            .hasMessageNotContaining("Maven metadata")
    }

    @Test
    fun `fails for an unlisted own module that declares a license, with the other problems`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-brand-new",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                },
                {
                  "group": "com.example",
                  "name": "no-license-lib",
                  "version": "1.0.0"
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(
                bom,
                defaultIgnoredPrefixes,
                OwnModules.PUBLIC_HIVEMQ_MODULES,
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("2 license problems found in")
            .hasMessageContaining(
                "Own module 'com.hivemq:hivemq-brand-new' (ignored group prefix 'com.hivemq') declares a license but " +
                    "is not in allowedArtifacts"
            )
            .hasMessageContaining("No license found for 'com.example:no-license-lib:1.0.0'")
    }

    @Test
    fun `fails for an unlisted own module with a license and incomplete metadata naming the license`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-brand-new",
                  "version": "1.0.0",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ],
                  "properties": [
                    { "name": "cdx:maven:package:metadata-unresolved", "value": "parent-unresolved" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, OwnModules.PUBLIC_HIVEMQ_MODULES)
        }
            .isInstanceOf(IllegalStateException::class.java)
            // the license is the stronger sign of a public module, so the message is about it
            .hasMessageContaining("declares a license but is not in allowedArtifacts")
            .hasMessageNotContaining("Maven metadata")
    }

    @Test
    fun `fails for an unlisted own module with incomplete Maven metadata, naming the reason`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-brand-new",
                  "version": "1.0.0",
                  "properties": [
                    { "name": "cdx:maven:package:metadata-unresolved", "value": "pom-unresolved" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, OwnModules.PUBLIC_HIVEMQ_MODULES)
        }
            .isInstanceOf(IllegalStateException::class.java)
            // CycloneDX writes no license when it cannot read the POM, which must not pass as an internal module; the
            // classification is the one problem, the missing license is not reported on top
            .hasMessageContaining("1 license problem found")
            .hasMessageContaining(
                "Own module 'com.hivemq:hivemq-brand-new' (ignored group prefix 'com.hivemq') is not in " +
                    "allowedArtifacts and its Maven metadata is incomplete (pom-unresolved), so it may be a public " +
                    "module: list it there, or fix its POM or the repository it comes from"
            )
    }

    @Test
    fun `fails for an unlisted own module with the classification message even without a version`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-brand-new",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
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
                OwnModules.PUBLIC_HIVEMQ_MODULES,
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            // the classification is the only problem: the dropped component neither lacks a license nor fails binding
            .hasMessageContaining("1 license problem found")
            .hasMessageContaining("Own module 'com.hivemq:hivemq-brand-new' (ignored group prefix 'com.hivemq')")
    }

    @Test
    fun `fails once for an unlisted own module in two versions`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-brand-new",
                  "version": "1.0.0",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ]
                },
                {
                  "group": "com.hivemq",
                  "name": "hivemq-brand-new",
                  "version": "2.0.0",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, OwnModules.PUBLIC_HIVEMQ_MODULES)
        }
            .isInstanceOf(IllegalStateException::class.java)
            // the module is the problem, not each of its versions
            .hasMessageContaining("1 license problem found")
    }

    @Test
    fun `leaves a project of the build in an own group out without listing it`() {
        // with a license, so that only being a project of the build keeps the component internal
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "some-internal-project",
                  "version": "1.0.0",
                  "purl": "pkg:maven/com.hivemq/some-internal-project@1.0.0?project_path=%3Asome-internal-project",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, emptySet())

        assertThat(result).isEmpty()
    }

    @Test
    fun `leaves an own module without a license out and lists a public one`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-internal-sdk",
                  "version": "4.0.0"
                },
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

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            OwnModules.PUBLIC_HIVEMQ_MODULES,
        )

        assertThat(result).containsOnlyKeys("com.google.guava:guava", "com.hivemq:hivemq-extension-sdk")
    }

    // 1.3 and later pass; another major, or a value that is no text, is left to generateSbom, which rejects both
    @ParameterizedTest
    @ValueSource(strings = ["\"1.3\"", "\"1.10\"", "\"1.6.1\"", "\"2.0\"", "1.2"])
    fun `leaves an own module without a license out whatever specVersion the old-version check lets through`(
        specVersion: String,
    ) {
        val bom = writeBom(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": $specVersion,
              "components": [
                { "group": "com.hivemq", "name": "hivemq-brand-new", "version": "1.0.0" }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, OwnModules.PUBLIC_HIVEMQ_MODULES)

        // without a license or the marker of incomplete metadata the module counts as internal
        assertThat(result).isEmpty()
    }

    @Test
    fun `leaves out a nested provided component but resolves what is nested in a hidden one`() {
        // a provided component nested in a kept one, and a third-party component nested in an internal one
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "org.example",
                  "name": "lib",
                  "version": "1.0",
                  "licenses": [ { "license": { "id": "MIT" } } ],
                  "components": [
                    {
                      "group": "org.example",
                      "name": "provided",
                      "version": "1.0",
                      "licenses": [ { "license": { "id": "MIT" } } ]
                    }
                  ]
                },
                {
                  "group": "com.hivemq",
                  "name": "hivemq-internal-sdk",
                  "version": "1.0",
                  "components": [
                    {
                      "group": "org.example",
                      "name": "below-internal",
                      "version": "1.0",
                      "licenses": [ { "license": { "id": "MIT" } } ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            OwnModules.PUBLIC_HIVEMQ_MODULES,
            providedModules = setOf("org.example:provided"),
        )

        // the hidden module ships with what is nested in it, the provided one does not ship at all
        assertThat(result).containsOnlyKeys("org.example:below-internal", "org.example:lib")
    }

    @Test
    fun `leaves out a project of the build it recognises by its bom-ref alone`() {
        // with a license, so that only being a project of the build keeps the component internal
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "some-internal-project",
                  "version": "1.0.0",
                  "bom-ref": "pkg:maven/com.hivemq/some-internal-project@1.0.0?project_path=%3Asome-internal-project",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, emptySet())

        assertThat(result).isEmpty()
    }

    @Test
    fun `leaves out a provided component nested in the top-level component`() {
        val bom = writeBom(
            """
            {
              "metadata": {
                "component": {
                  "group": "com.hivemq", "name": "app", "version": "1.0.0",
                  "components": [
                    {
                      "group": "org.example",
                      "name": "provided",
                      "version": "1.0",
                      "licenses": [ { "license": { "id": "MIT" } } ]
                    }
                  ]
                }
              },
              "components": []
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            providedModules = setOf("org.example:provided"),
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `leaves out an internal component without a version`() {
        // the filter removes it before the typed model, which needs a version, is built
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-internal-sdk"
                },
                {
                  "group": "org.slf4j",
                  "name": "slf4j-api",
                  "version": "2.0.9",
                  "licenses": [
                    { "license": { "id": "MIT" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            OwnModules.PUBLIC_HIVEMQ_MODULES,
        )

        assertThat(result).containsOnlyKeys("org.slf4j:slf4j-api")
    }

    @Test
    fun `leaves out what is nested in a hidden provided module`() {
        // the hidden module is provided too, so what the BOM nests in it does not ship and is not listed either
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "org.example",
                  "name": "lib",
                  "version": "1.0",
                  "licenses": [ { "license": { "id": "MIT" } } ]
                },
                {
                  "group": "com.hivemq",
                  "name": "hivemq-internal-sdk",
                  "version": "1.0",
                  "components": [
                    {
                      "group": "org.example",
                      "name": "inner",
                      "version": "1.0",
                      "licenses": [ { "license": { "id": "MIT" } } ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            OwnModules.PUBLIC_HIVEMQ_MODULES,
            providedModules = setOf("com.hivemq:hivemq-internal-sdk"),
        )

        assertThat(result).containsOnlyKeys("org.example:lib")
    }

    @Test
    fun `leaves out what is nested in a public module that a provided one carries`() {
        // the public SDK is provided by being part of a provided module, so what is nested in it does not ship either
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "org.example",
                  "name": "provided",
                  "version": "1.0",
                  "components": [
                    {
                      "group": "com.hivemq",
                      "name": "hivemq-extension-sdk",
                      "version": "4.0.0",
                      "licenses": [ { "license": { "id": "Apache-2.0" } } ],
                      "components": [
                        {
                          "group": "org.example",
                          "name": "embedded",
                          "version": "1.0"
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            OwnModules.PUBLIC_HIVEMQ_MODULES,
            providedModules = setOf("org.example:provided"),
        )

        assertThat(result).containsOnlyKeys("com.hivemq:hivemq-extension-sdk")
    }

    @Test
    fun `lists a public HiveMQ module built by this build under the Apache license although the BOM carries none`() {
        // a project of the build has no POM, so CycloneDX writes no license for it
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-extension-sdk",
                  "version": "4.57.0-SNAPSHOT",
                  "purl": "pkg:maven/com.hivemq/hivemq-extension-sdk@4.57.0-SNAPSHOT?project_path=%3Asdk"
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, OwnModules.PUBLIC_HIVEMQ_MODULES)

        assertThat(result.getValue("com.hivemq:hivemq-extension-sdk").licenses).containsExactly(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `recognises a CycloneDX BOM without a top-level components array`() {
        val bom = writeBom(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "metadata": {
                "component": {
                  "group": "com.hivemq", "name": "app", "version": "1.0.0",
                  "components": [
                    {
                      "group": "org.example",
                      "name": "part",
                      "version": "1.0",
                      "licenses": [ { "license": { "id": "MIT" } } ]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result).containsOnlyKeys("org.example:part")
    }

    @Test
    fun `resolves a component nested in another one like a top-level one`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "org.example",
                  "name": "outer",
                  "version": "1.0",
                  "licenses": [ { "license": { "id": "MIT" } } ],
                  "components": [
                    {
                      "group": "org.example",
                      "name": "inner",
                      "version": "1.0",
                      "licenses": [ { "license": { "id": "Apache-2.0" } } ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result).containsOnlyKeys("org.example:inner", "org.example:outer")
    }

    @Test
    fun `resolves a component nested in the top-level component like a top-level one`() {
        val bom = writeBom(
            """
            {
              "metadata": {
                "component": {
                  "group": "com.hivemq", "name": "app", "version": "1.0.0",
                  "components": [
                    {
                      "group": "org.example",
                      "name": "part",
                      "version": "1.0",
                      "licenses": [ { "license": { "id": "MIT" } } ]
                    }
                  ]
                }
              },
              "components": []
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result).containsOnlyKeys("org.example:part")
    }

    // --- license ids regardless of case ---

    @Test
    fun `matches a single license id regardless of case`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "lower-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "mit" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:lower-lib"]!!.licenses).containsExactly(KnownLicense.MIT)
    }

    @Test
    fun `accepts an overridden license id regardless of case`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "lower-lib",
                  "version": "1.0.0",
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
            overriddenLicenses = mapOf("com.example:lower-lib" to "apache-2.0"),
        )

        assertThat(result["com.example:lower-lib"]!!.licenses).containsExactly(KnownLicense.APACHE_2_0)
    }

    // --- provided modules ---

    @Test
    fun `does not fail on the license problem of a provided module`() {
        val bom = writeBom(
            """
            {
              "components": [
                { "group": "com.example", "name": "provided-lib", "version": "1.0.0" },
                { "group": "com.google.guava", "name": "guava", "version": "33.4.0-jre",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ] }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            providedModules = setOf("com.example:provided-lib"),
        )

        assertThat(result.keys).containsExactly("com.google.guava:guava")
    }

    @Test
    fun `keeps a provided module that is public`() {
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
                  "group": "org.slf4j",
                  "name": "slf4j-api",
                  "version": "2.0.9",
                  "licenses": [
                    { "license": { "id": "MIT" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            OwnModules.PUBLIC_HIVEMQ_MODULES,
            providedModules = setOf("com.hivemq:hivemq-extension-sdk", "org.slf4j:slf4j-api"),
        )

        assertThat(result).containsOnlyKeys("com.hivemq:hivemq-extension-sdk")
    }

    @Test
    fun `keeps a public provided module nested in a provided one`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-internal-sdk",
                  "version": "4.0.0",
                  "components": [
                    {
                      "group": "com.hivemq",
                      "name": "hivemq-extension-sdk",
                      "version": "4.0.0",
                      "licenses": [ { "license": { "id": "Apache-2.0" } } ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            OwnModules.PUBLIC_HIVEMQ_MODULES,
            providedModules = setOf("com.hivemq:hivemq-internal-sdk", "com.hivemq:hivemq-extension-sdk"),
        )

        assertThat(result).containsOnlyKeys("com.hivemq:hivemq-extension-sdk")
    }

    @Test
    fun `leaves out a provided component without a version`() {
        val bom = writeBom(
            """
            {
              "components": [
                { "group": "com.example", "name": "provided-lib" },
                { "group": "com.google.guava", "name": "guava", "version": "33.4.0-jre",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ] }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            providedModules = setOf("com.example:provided-lib"),
        )

        assertThat(result.keys).containsExactly("com.google.guava:guava")
    }

    @Test
    fun `leaves out a provided third-party module whatever the public patterns match`() {
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
                  "group": "com.hivemq",
                  "name": "hivemq-anything",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        // the public exception covers own modules only, so a pattern that matches everything keeps just our own
        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            setOf("*"),
            providedModules = setOf("org.slf4j:slf4j-api", "com.hivemq:hivemq-anything"),
        )

        assertThat(result).containsOnlyKeys("com.hivemq:hivemq-anything")
    }

    @Test
    fun `leaves out what is nested in a public provided module`() {
        // the host provides the SDK whole, so a library the BOM nests in it does not ship, licensed or not
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-extension-sdk",
                  "version": "4.0.0",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ],
                  "components": [
                    {
                      "group": "org.example",
                      "name": "embedded",
                      "version": "1.0"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            OwnModules.PUBLIC_HIVEMQ_MODULES,
            providedModules = setOf("com.hivemq:hivemq-extension-sdk"),
        )

        assertThat(result).containsOnlyKeys("com.hivemq:hivemq-extension-sdk")
    }

    @Test
    fun `skips a provided module`() {
        val bom = writeBom(
            """
            {
              "components": [
                { "group": "org.slf4j", "name": "slf4j-api", "version": "2.0.16",
                  "licenses": [ { "license": { "id": "MIT" } } ] },
                { "group": "com.google.guava", "name": "guava", "version": "33.4.0-jre",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ] }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            providedModules = setOf("org.slf4j:slf4j-api"),
        )

        assertThat(result.keys).containsExactly("com.google.guava:guava")
    }

    @Test
    fun `skips every version of a provided module`() {
        val bom = writeBom(
            """
            {
              "components": [
                { "group": "org.slf4j", "name": "slf4j-api", "version": "1.7.36",
                  "licenses": [ { "license": { "id": "MIT" } } ] },
                { "group": "org.slf4j", "name": "slf4j-api", "version": "2.0.16",
                  "licenses": [ { "license": { "id": "MIT" } } ] },
                { "group": "com.google.guava", "name": "guava", "version": "33.4.0-jre",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ] }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            providedModules = setOf("org.slf4j:slf4j-api"),
        )

        assertThat(result.keys).containsExactly("com.google.guava:guava")
    }

    @Test
    fun `still fails on the license problem of a module that is not provided`() {
        val bom = writeBom(
            """
            {
              "components": [
                { "group": "com.example", "name": "provided-lib", "version": "1.0.0" },
                { "group": "com.example", "name": "no-license-lib", "version": "1.0.0" }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(
                bom,
                defaultIgnoredPrefixes,
                defaultAllowedArtifacts,
                providedModules = setOf("com.example:provided-lib"),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("1 license problem found")
            .hasMessageContaining("No license found for 'com.example:no-license-lib:1.0.0'")
            .hasMessageNotContaining("provided-lib")
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
        val expression = tooManyAlternatives()
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
        val expression = tooManyAlternatives()
        val bom = writeExpressionBom(expression)

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(": $expression (SPDX expression too complex to evaluate)")
    }

    @Test
    fun `resolves an SPDX disjunction that repeats one license beyond the candidate budget`() {
        val bom = writeExpressionBom(List(20_000) { "MIT" }.joinToString(" OR "))

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.MIT)
    }

    @Test
    fun `resolves an SPDX conjunction that repeats one license beyond the candidate budget`() {
        val bom = writeExpressionBom(List(20_000) { "(MIT)" }.joinToString(" AND "))

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).containsExactly(KnownLicense.MIT)
    }

    @Test
    @Timeout(10)
    fun `resolves an SPDX conjunction that repeats an operand its alternatives contain in bounded time`() {
        // ten pairs without MIT give 1024 alternatives; every further "AND MIT" must not rescan them all
        val ids = LicenseResolver.SPDX_ID_MAP.keys.filter { ' ' !in it && it != "MIT" }.take(20)
        val pairs = ids.chunked(2).joinToString(" AND ") { (a, b) -> "($a OR $b)" }
        val bom = writeExpressionBom("MIT AND $pairs AND " + List(1_000) { "MIT" }.joinToString(" AND "))

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).hasSize(11).contains(KnownLicense.MIT)
    }

    @Test
    @Timeout(10)
    fun `resolves an SPDX expression nested in many parentheses in bounded time`() {
        val ids = LicenseResolver.SPDX_ID_MAP.keys.filter { ' ' !in it }.take(20)
        val pairs = ids.chunked(2).joinToString(" AND ") { (a, b) -> "($a OR $b)" }
        val bom = writeExpressionBom("(".repeat(30) + pairs + ")".repeat(30))

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        assertThat(result["com.example:expr-lib"]!!.licenses).hasSize(10)
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

    // --- declared expressions ---

    @Test
    fun `declares a conjunction as an expression`() {
        val lib = libWith(expressionEntry("MIT AND ISC"))

        assertThat(lib.licenses).containsExactly(KnownLicense.MIT, KnownLicense.ISC)
        assertThat(lib.declaredExpression).isEqualTo("MIT AND ISC")
    }

    @Test
    fun `declares a license entry and an expression entry together`() {
        val lib = libWith(idEntry("EPL-2.0"), expressionEntry("MIT AND ISC"))

        // MIT and ISC together rank above EPL-2.0 alone, as both come earlier in LICENSE_ORDER
        assertThat(lib.licenses).containsExactly(KnownLicense.MIT, KnownLicense.ISC)
        assertThat(lib.declaredExpression).isEqualTo("EPL-2.0 OR (MIT AND ISC)")
    }

    @Test
    fun `declares an expression entry in canonical form`() {
        val lib = libWith(expressionEntry("(CDDL-1.0 OR GPL-2.0-with-classpath-exception)"))

        assertThat(lib.licenses).containsExactly(KnownLicense.CDDL_1_0)
        assertThat(lib.declaredExpression).isEqualTo("CDDL-1.0 OR GPL-2.0-only WITH Classpath-exception-2.0")
    }

    @Test
    fun `declares no expression for a built-in license`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "javax.servlet",
                  "name": "javax.servlet-api",
                  "version": "4.0.1",
                  "licenses": [ { "expression": "(CDDL-1.0 OR GPL-2.0-with-classpath-exception)" } ]
                }
              ]
            }
            """.trimIndent()
        )

        val lib = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
            .getValue("javax.servlet:javax.servlet-api")

        assertThat(lib.licenses).containsExactly(KnownLicense.CDDL_1_1)
        assertThat(lib.declaredExpression).isNull()
    }

    @Test
    fun `declares no expression for a license given only by name`() {
        val lib = libWith(idEntry("EPL-2.0"), idEntry("MIT"), nameEntry("GNU Lesser General Public License v2.1 only"))

        // the name maps to LGPL-2.1-or-later, which grants more than "v2.1 only"; "EPL-2.0 OR MIT" would drop it
        assertThat(lib.licenses).containsExactly(KnownLicense.MIT)
        assertThat(lib.declaredExpression).isNull()
    }

    @Test
    fun `declares no expression for a project module without a license`() {
        val bom = writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-extension-sdk",
                  "version": "4.57.0-SNAPSHOT",
                  "purl": "pkg:maven/com.hivemq/hivemq-extension-sdk@4.57.0-SNAPSHOT?project_path=%3Asdk"
                }
              ]
            }
            """.trimIndent()
        )

        val sdk = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, OwnModules.PUBLIC_HIVEMQ_MODULES)
            .getValue("com.hivemq:hivemq-extension-sdk")

        assertThat(sdk.licenses).containsExactly(KnownLicense.APACHE_2_0)
        assertThat(sdk.declaredExpression).isNull()
    }

    @Test
    fun `declares no expression for a single license`() {
        val lib = libWith(idEntry("Apache-2.0"))

        assertThat(lib.licenses).containsExactly(KnownLicense.APACHE_2_0)
        assertThat(lib.declaredExpression).isNull()
    }

    @Test
    fun `declares no expression for an entry with only a URL`() {
        val lib = libWith(idEntry("MIT"), """{ "license": { "url": "https://www.apache.org/licenses/LICENSE-2.0" } }""")

        assertThat(lib.licenses).containsExactly(KnownLicense.MIT)
        assertThat(lib.declaredExpression).isNull()
    }

    @Test
    fun `declares no expression for an id with a space`() {
        // no SPDX id has one, so this is free text in the wrong field
        val lib = libWith(idEntry("MIT"), idEntry("Apache 2.0"))

        assertThat(lib.licenses).containsExactly(KnownLicense.MIT)
        assertThat(lib.declaredExpression).isNull()
    }

    @Test
    fun `declares no expression for an override`() {
        val bom = writeEntriesBom(idEntry("EPL-2.0"), idEntry("GPL-2.0-with-classpath-exception"))

        val lib = LicenseResolver.resolveLicenses(
            bom, defaultIgnoredPrefixes, defaultAllowedArtifacts,
            overriddenLicenses = mapOf("com.example:lib" to "MIT"),
        ).getValue("com.example:lib")

        assertThat(lib.licenses).containsExactly(KnownLicense.MIT)
        assertThat(lib.declaredExpression).isNull()
    }

    @Test
    fun `declares no expression when a malformed entry sits next to an approved one`() {
        val lib = libWith(idEntry("MIT"), expressionEntry("MIT OR )"))

        assertThat(lib.licenses).containsExactly(KnownLicense.MIT)
        assertThat(lib.declaredExpression).isNull()
    }

    @Test
    fun `declares no expression when an entry is too complex to evaluate`() {
        val lib = libWith(idEntry("MIT"), expressionEntry(tooManyAlternatives()))

        assertThat(lib.licenses).containsExactly(KnownLicense.MIT)
        assertThat(lib.declaredExpression).isNull()
    }

    @Test
    fun `declares no expression when two entries name the same license`() {
        val lib = libWith(idEntry("MIT"), idEntry("mit"))

        assertThat(lib.licenses).containsExactly(KnownLicense.MIT)
        assertThat(lib.declaredExpression).isNull()
    }

    @Test
    fun `declares the alternatives around an entry that is neither a license nor an expression`() {
        // such an entry offers nothing to choose, so it is no alternative
        val lib = libWith(idEntry("EPL-2.0"), "{ }", idEntry("MIT"))

        assertThat(lib.licenses).containsExactly(KnownLicense.MIT)
        assertThat(lib.declaredExpression).isEqualTo("EPL-2.0 OR MIT")
    }

    @Test
    fun `declares the alternatives of several license entries as one expression`() {
        // the two license elements of the POM of jakarta.annotation-api, as CycloneDX writes them
        val lib = libWith(idEntry("EPL-2.0"), idEntry("GPL-2.0-with-classpath-exception"))

        assertThat(lib.licenses).containsExactly(KnownLicense.EPL_2_0)
        assertThat(lib.declaredExpression).isEqualTo("EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0")
    }

    // --- built-in expressions ---

    @Test
    fun `applies an override before the built-in expression of a module`() {
        val bom = writeModuleBom(
            "net.logstash.logback", "logstash-logback-encoder", idEntry("Apache-2.0"), idEntry("MIT"),
        )

        val result = LicenseResolver.resolveLicenses(
            bom,
            defaultIgnoredPrefixes,
            defaultAllowedArtifacts,
            overriddenLicenses = mapOf("net.logstash.logback:logstash-logback-encoder" to "MIT"),
        )

        val lib = result.getValue("net.logstash.logback:logstash-logback-encoder")
        assertThat(lib.licenses).containsExactly(KnownLicense.MIT)
        assertThat(lib.declaredExpression).isNull()
    }

    @Test
    fun `fails like any module without a license when one with a built-in expression declares none`() {
        val bom = writeModuleBom("net.logstash.logback", "logstash-logback-encoder")

        assertThatThrownBy { LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No license found for 'net.logstash.logback:logstash-logback-encoder:1.0.0'")
            .hasMessageNotContaining("built-in expression")
    }

    @Test
    fun `fails when the declared licenses of a module with a built-in expression differ from the recorded ones`() {
        val bom = writeModuleBom("net.logstash.logback", "logstash-logback-encoder", idEntry("Apache-2.0"))

        assertThatThrownBy { LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(
                "Declared licenses of 'net.logstash.logback:logstash-logback-encoder:1.0.0' are 'Apache-2.0', " +
                    "but the plugin's built-in expression 'Apache-2.0 AND MIT' was written for 'Apache-2.0', 'MIT': " +
                    "update the plugin, or name the license in overriddenLicenses"
            )
    }

    @Test
    fun `resolves a module with a built-in expression as if its POM declared it`() {
        val bom = writeModuleBom(
            "net.logstash.logback", "logstash-logback-encoder", idEntry("Apache-2.0"), idEntry("MIT"),
        )

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        // the POM says in a comment that the MIT entry covers copied code, so both apply and the report lists both
        val lib = result.getValue("net.logstash.logback:logstash-logback-encoder")
        assertThat(lib.licenses).containsExactly(KnownLicense.APACHE_2_0, KnownLicense.MIT)
        assertThat(lib.declaredExpression).isEqualTo("Apache-2.0 AND MIT")
    }

    @Test
    fun `resolves a module with a built-in expression over a license given by name`() {
        val gpl = nameEntry("GNU General Public License, version 2")
        val bom = writeModuleBom("org.rocksdb", "rocksdbjni", idEntry("Apache-2.0"), gpl)

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        // the name has no SPDX form of its own; the expression names it, and the approved alternative is elected
        val lib = result.getValue("org.rocksdb:rocksdbjni")
        assertThat(lib.licenses).containsExactly(KnownLicense.APACHE_2_0)
        assertThat(lib.declaredExpression).isEqualTo("Apache-2.0 OR GPL-2.0-only")
    }

    @Test
    fun `resolves a module with a built-in expression whatever the order and URLs of its entries`() {
        val gpl = """{ "license": { "name": "GNU General Public License, version 2", "url": "https://gnu.org/gpl" } }"""
        val apache = """{ "license": { "id": "Apache-2.0", "url": "https://opensource.org/license/apache-2-0" } }"""
        val bom = writeModuleBom("org.rocksdb", "rocksdbjni", gpl, apache)

        val result = LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)

        // neither OR nor AND depends on the order, and a URL CycloneDX writes differs between its versions
        assertThat(result.getValue("org.rocksdb:rocksdbjni").declaredExpression).isEqualTo("Apache-2.0 OR GPL-2.0-only")
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
            LicenseResolver.resolveLicenses(bom, defaultIgnoredPrefixes, defaultAllowedArtifacts)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No license found for 'com.example:no-license-lib:1.0.0'")
            .hasMessageContaining("License not on the approved list for 'com.example:proprietary-lib:2.0.0': Some Proprietary License")
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
            .hasMessageContaining("No license found for 'com.example:unreadable-lib:1.0.0' (its Maven metadata is incomplete: pom-unparseable)")
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
            .hasMessageContaining("License not on the approved list for 'com.example:partial-lib:1.0.0' (its Maven metadata is incomplete: parent-unresolved): Some Proprietary License")
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

    // --- pnpm reports (neither "bomFormat" nor "components") ---

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
    fun `pnpm leaves the packages of an own scope out, license or not`() {
        // internal frontend packages declare licenses, so a license is no sign of a public package here
        val report = writeBom(
            """
            {
              "MIT": [
                { "name": "react", "versions": ["18.2.0"], "license": "MIT" },
                { "name": "@example/internal-widget", "versions": ["1.0.0"], "license": "MIT" }
              ],
              "Unknown": [
                { "name": "@example/internal-theme", "versions": ["1.0.0"], "license": "Unknown" }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(report, setOf("@example/"), emptySet())

        assertThat(result).containsOnlyKeys("react")
    }

    @Test
    fun `pnpm lists a scoped package allowedArtifacts names`() {
        val report = writeBom(
            """
            {
              "MIT": [
                { "name": "@example/public-widget", "versions": ["1.0.0"], "license": "MIT" }
              ]
            }
            """.trimIndent()
        )

        val result = LicenseResolver.resolveLicenses(report, setOf("@example/"), setOf("@example/public-widget"))

        assertThat(result).containsOnlyKeys("@example/public-widget")
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

    @Test
    fun `pnpm reads a report with a license named like the BOM marker as a report`() {
        // pnpm keys the report by license text, so a license named "bomFormat" puts that key at the root
        val report = writeBom(
            """
            {
              "bomFormat": [
                { "name": "odd-pkg", "versions": ["1.0.0"], "license": "bomFormat" }
              ],
              "MIT": [
                { "name": "react", "versions": ["18.2.0"], "license": "MIT" }
              ]
            }
            """.trimIndent()
        )

        assertThatThrownBy {
            LicenseResolver.resolveLicenses(report, emptySet(), emptySet())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'odd-pkg:1.0.0': bomFormat")
    }
}
