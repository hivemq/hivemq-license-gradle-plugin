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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.cyclonedx.Version
import org.cyclonedx.parsers.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class SbomEnricherTest {

    private val mapper = ObjectMapper()

    private val details = SbomDetails(
        creatorName = "HiveMQ GmbH",
        creator = "https://www.hivemq.com/",
        artifactFileName = "app-1.0.0.jar",
        componentFileNames = mapOf(
            "org.example:lib:1.0" to "lib-1.0.jar",
            "org.example:zipped:2.0" to "zipped-2.0.zip",
            "org.example:other:3.0" to "other-3.0.so",
        ),
        concludedLicenses = mapOf(
            "org.example:lib:1.0" to listOf("MIT"),
            "org.example:dual:1.0" to listOf("MIT", "ISC"),
        ),
        incompleteReasons = emptyList(),
    )

    /** A BOM the way the CycloneDX Gradle plugin writes it, reduced to what the enricher touches. */
    private fun bom(components: String = DEFAULT_COMPONENTS, specVersion: String = "1.6") = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "$specVersion",
          "version": 1,
          "metadata": {
            "timestamp": "2026-10-16T10:01:10Z",
            "component": {
              "type": "application", "bom-ref": "pkg:maven/com.hivemq/app@1.0.0?project_path=%3A",
              "group": "com.hivemq", "name": "app", "version": "1.0.0"
            }
          },
          "components": [ $components ],
          "dependencies": [
            {
              "ref": "pkg:maven/com.hivemq/app@1.0.0?project_path=%3A",
              "dependsOn": [ "pkg:maven/org.example/lib@1.0" ]
            },
            { "ref": "pkg:maven/org.example/lib@1.0", "dependsOn": [] }
          ]
        }
    """.trimIndent().toByteArray()

    private fun enrich(input: ByteArray = bom(), with: SbomDetails = details): JsonNode =
        mapper.readTree(SbomEnricher.enrich(input, with))

    private fun JsonNode.component(name: String): JsonNode = this["components"].first { it["name"].asText() == name }

    private fun JsonNode.property(name: String): String? =
        this["properties"]?.firstOrNull { it["name"].asText() == name }?.get("value")?.asText()

    // --- Input ---

    // the added fields exist from the minimum on, and a later specification keeps every earlier field
    @ParameterizedTest
    @ValueSource(strings = ["1.6.1", "1.7", "1.10"])
    fun `accepts a spec version at or after the minimum`(specVersion: String) {
        val result = enrich(bom(specVersion = specVersion))

        assertThat(result["metadata"]["manufacturer"]["name"].asText()).isEqualTo("HiveMQ GmbH")
    }

    @ParameterizedTest
    @MethodSource("missingOrMalformedSpecVersions")
    fun `rejects a missing or malformed spec version`(document: String, declared: String) {
        assertThatThrownBy { enrich(document.toByteArray()) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "The BOM declares $declared, but the manufacturer, license acknowledgement and source-distribution " +
                    "fields need ${SbomEnricher.MINIMUM_SPEC_VERSION} or later"
            )
    }

    @Test
    fun `rejects a spec version before the minimum`() {
        // the cyclonedxDirectBom task can be configured to write an older spec version
        assertThatThrownBy { enrich(bom(specVersion = "1.5")) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "The BOM declares CycloneDX specVersion 1.5, but the manufacturer, license acknowledgement and " +
                    "source-distribution fields need ${SbomEnricher.MINIMUM_SPEC_VERSION} or later"
            )
    }

    // another major version may rename or drop the fields the enricher writes, so being later is not enough
    @ParameterizedTest
    @ValueSource(strings = ["0.9", "2.0"])
    fun `rejects a spec version with a major version other than 1`(specVersion: String) {
        assertThatThrownBy { enrich(bom(specVersion = specVersion)) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "The BOM declares CycloneDX specVersion $specVersion, but this task only handles specification " +
                    "${SbomEnricher.MINIMUM_SPEC_VERSION} and later 1.x versions"
            )
    }

    @Test
    fun `rejects input that is not a JSON object`() {
        assertThatThrownBy { enrich("[]".toByteArray()) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("The BOM is not a JSON object")
    }

    @Test
    fun `rejects input that is not valid JSON`() {
        assertThatThrownBy { enrich("{".toByteArray()) }
            .isInstanceOf(SbomException::class.java)
            .hasMessageStartingWith("The BOM is not valid JSON: ")
            .hasMessageContaining("at line 1")
    }

    @Test
    fun `rejects metadata that is not a JSON object`() {
        val input = """
            {"specVersion":"1.6","metadata":[],"components":[{"group":"org.example","name":"lib","version":"1.0"}]}
        """.trimIndent()

        // the normalizer cannot set the timestamp in it, so the enricher rejects it too instead of leaving it alone
        assertThatThrownBy { enrich(input.toByteArray()) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("The BOM's metadata is not a JSON object")
    }

    @Test
    fun `tolerates a licenses value that is not an array`() {
        val components = """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/lib@1.0",
              "group": "org.example", "name": "lib", "version": "1.0",
              "licenses": "MIT"
            }
        """.trimIndent()

        val result = enrich(bom(components))

        assertThat(result.component("lib")["licenses"].asText()).isEqualTo("MIT")
        assertThat(result["metadata"]["manufacturer"]["name"].asText()).isEqualTo("HiveMQ GmbH")
    }

    @ParameterizedTest
    @MethodSource("valuesOfAnUnexpectedType")
    fun `tolerates any other value of an unexpected type`(pointer: String, document: String) {
        val unexpected = mapper.readTree(document).at(pointer)

        val result = enrich(document.toByteArray())

        assertThat(unexpected.isMissingNode).isFalse()
        assertThat(result.at(pointer)).isEqualTo(unexpected)
    }

    // --- Creator ---

    @ParameterizedTest
    @ValueSource(
        strings = [
            "security@example.com", "mailto:security@example.com",
            "http://www.hivemq.com/", "https://www.hivemq.com/", "HTTPS://www.hivemq.com/",
        ]
    )
    fun `accepts a creator that is an email address, a mailto link or an http or https URL`(creator: String) {
        val result = enrich(with = details.copy(creator = creator))

        assertThat(result["metadata"]["manufacturer"]["name"].asText()).isEqualTo("HiveMQ GmbH")
    }

    @Test
    fun `leaves an existing component manufacturer alone`() {
        val components = """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/lib@1.0",
              "group": "org.example", "name": "lib", "version": "1.0",
              "manufacturer": { "name": "Example Corp" },
              "externalReferences": [ { "type": "website", "url": "https://example.org/lib" } ]
            }
        """.trimIndent()

        val lib = enrich(bom(components)).component("lib")

        assertThat(lib["manufacturer"]["name"].asText()).isEqualTo("Example Corp")
        assertThat(lib["manufacturer"].has("url")).isFalse()
    }

    // a name makes the SBOM invalid and the rest lead nowhere, though HiveMQ:Security and localhost:8080 have a scheme
    @ParameterizedTest
    @ValueSource(
        strings = [
            "HiveMQ Security Team", "www.hivemq.com", "",
            "HiveMQ:Security", "localhost:8080", "file:///tmp/x", "mailto:foo", "https:foo",
        ]
    )
    fun `rejects a creator that is neither an email address nor an http or https URL before changing the BOM`(
        creator: String,
    ) {
        val root = BomJson.read(bom())
        val unchanged = root.deepCopy()

        assertThatThrownBy { SbomEnricher.enrich(root, details.copy(creator = creator)) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("hivemqLicense.sbomCreator must be an email address or an http(s) URL, but is '$creator'")
        assertThat(root).isEqualTo(unchanged)
    }

    @Test
    fun `writes a mailto creator as a contact`() {
        for (creator in listOf("mailto:security@example.com", "MAILTO:security@example.com")) {
            val result = enrich(with = details.copy(creator = creator))

            val manufacturer = result["metadata"]["manufacturer"]
            assertThat(manufacturer["contact"][0]["email"].asText())
                .describedAs(creator)
                .isEqualTo("security@example.com")
            assertThat(manufacturer.has("url")).describedAs(creator).isFalse()
        }
    }

    @Test
    fun `writes a URL that contains an at sign as a URL`() {
        val result = enrich(with = details.copy(creator = "https://mastodon.social/@hivemq"))

        val manufacturer = result["metadata"]["manufacturer"]
        assertThat(manufacturer["url"].map { it.asText() }).containsExactly("https://mastodon.social/@hivemq")
        assertThat(manufacturer.has("contact")).isFalse()
    }

    @Test
    fun `writes an email creator as a contact`() {
        val result = enrich(with = details.copy(creator = "security@example.com"))

        val manufacturer = result["metadata"]["manufacturer"]
        assertThat(manufacturer["contact"][0]["email"].asText()).isEqualTo("security@example.com")
        assertThat(manufacturer.has("url")).isFalse()
    }

    @Test
    fun `writes the creator as the manufacturer of a project of this build`() {
        val result = enrich()

        assertThat(result.component("sub")["manufacturer"]).isEqualTo(result["metadata"]["manufacturer"])
    }

    @Test
    fun `writes the creator as the manufacturer of the SBOM and of its component`() {
        val result = enrich()

        val manufacturers = listOf(result["metadata"]["manufacturer"], result["metadata"]["component"]["manufacturer"])
        for (manufacturer in manufacturers) {
            assertThat(manufacturer["name"].asText()).isEqualTo("HiveMQ GmbH")
            assertThat(manufacturer["url"].map { it.asText() }).containsExactly("https://www.hivemq.com/")
            assertThat(manufacturer.has("contact")).isFalse()
        }
    }

    // --- Dependencies ---

    @Test
    fun `declares the dependency graph complete`() {
        val result = enrich()

        val composition = result["compositions"][0]
        assertThat(composition["aggregate"].asText()).isEqualTo("complete")
        assertThat(composition["dependencies"].map { it.asText() })
            .containsExactly("pkg:maven/com.hivemq/app@1.0.0?project_path=%3A", "pkg:maven/org.example/lib@1.0")
    }

    @Test
    fun `declares the dependency graph incomplete when the build reports a gap`() {
        val result = enrich(with = details.copy(incompleteReasons = listOf(FILE_DEPENDENCY)))

        val composition = result["compositions"][0]
        assertThat(composition["aggregate"].asText()).isEqualTo("incomplete")
        assertThat(composition["dependencies"].map { it.asText() })
            .containsExactly("pkg:maven/com.hivemq/app@1.0.0?project_path=%3A", "pkg:maven/org.example/lib@1.0")
    }

    @Test
    fun `writes no composition without a dependency graph`() {
        val input = """{"bomFormat":"CycloneDX","specVersion":"1.6","metadata":{"component":{"name":"app"}}}"""

        val result = enrich(input.toByteArray())

        assertThat(result.has("compositions")).isFalse()
    }

    @Test
    fun `writes the composition in the same order whatever the order of the dependencies`() {
        val input = """{"specVersion":"1.6","dependencies":[{"ref":"pkg:maven/b/b@1"},{"ref":"pkg:maven/a/a@1"}]}"""

        val result = enrich(input.toByteArray())

        assertThat(result["compositions"][0]["dependencies"].map { it.asText() })
            .containsExactly("pkg:maven/a/a@1", "pkg:maven/b/b@1")
    }

    @Test
    fun `writes the composition with each textual ref once`() {
        val input = """
            {
              "specVersion": "1.6",
              "dependencies": [
                {"ref":"pkg:maven/b/b@1"}, {"ref":7}, {"ref":"pkg:maven/a/a@1"}, {"ref":"pkg:maven/b/b@1"}
              ]
            }
        """.trimIndent()

        val result = enrich(input.toByteArray())

        assertThat(result["compositions"][0]["dependencies"].map { it.asText() })
            .containsExactly("pkg:maven/a/a@1", "pkg:maven/b/b@1")
    }

    // --- Files ---

    @Test
    fun `describes a jar as an executable structured archive`() {
        val lib = enrich().component("lib")

        assertThat(lib.property("bsi:component:filename")).isEqualTo("lib-1.0.jar")
        assertThat(lib.property("bsi:component:executable")).isEqualTo("executable")
        assertThat(lib.property("bsi:component:archive")).isEqualTo("archive")
        assertThat(lib.property("bsi:component:structured")).isEqualTo("structured")
    }

    @Test
    fun `describes a zip as a non-executable structured archive`() {
        val zipped = enrich().component("zipped")

        assertThat(zipped.property("bsi:component:executable")).isEqualTo("non-executable")
        assertThat(zipped.property("bsi:component:archive")).isEqualTo("archive")
        assertThat(zipped.property("bsi:component:structured")).isEqualTo("structured")
    }

    @Test
    fun `describes the top-level component by its artifact file name`() {
        val component = enrich()["metadata"]["component"]

        assertThat(component.property("bsi:component:filename")).isEqualTo("app-1.0.0.jar")
        assertThat(component.property("bsi:component:executable")).isEqualTo("executable")
    }

    @Test
    fun `keeps the existing properties in front of the new ones`() {
        val lib = enrich().component("lib")

        assertThat(lib["properties"][0]["name"].asText()).isEqualTo("cdx:maven:package:test")
    }

    @Test
    fun `keys a component without a group like Coordinates does`() {
        val components = """{ "type": "library", "name": "local", "version": "1" }"""
        val coordinates = Coordinates("", "local", "1")
        val withoutGroup = details.copy(
            componentFileNames = mapOf(coordinates.toString() to "local-1.jar"),
            concludedLicenses = mapOf(coordinates.toString() to listOf("MIT")),
        )

        val local = enrich(bom(components), withoutGroup).component("local")

        assertThat(local.property("bsi:component:filename")).isEqualTo("local-1.jar")
        assertThat(local["licenses"][0]["license"]["id"].asText()).isEqualTo("MIT")
    }

    @Test
    fun `leaves a component without a file alone`() {
        val bomOnly = enrich().component("bom-only")

        assertThat(bomOnly.has("properties")).isFalse()
        assertThat(bomOnly.has("manufacturer")).isFalse()
    }

    @Test
    fun `writes no file name for the top-level component when it is unknown`() {
        val component = enrich(with = details.copy(artifactFileName = null))["metadata"]["component"]

        assertThat(component.has("properties")).isFalse()
    }

    @Test
    fun `writes only the file name for a file that is neither a jar nor a zip`() {
        val other = enrich().component("other")

        assertThat(other.property("bsi:component:filename")).isEqualTo("other-3.0.so")
        assertThat(other.property("bsi:component:executable")).isNull()
    }

    // --- Licenses ---

    @Test
    fun `adds a concluded license to a component without any`() {
        val components = """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/lib@1.0",
              "group": "org.example", "name": "lib", "version": "1.0"
            }
        """.trimIndent()

        val lib = enrich(bom(components)).component("lib")

        assertThat(lib["licenses"].map { it["license"]["id"].asText() to it["license"]["acknowledgement"].asText() })
            .containsExactly("MIT" to "concluded")
    }

    @Test
    fun `adds the concluded license only to the version it was resolved for`() {
        // configurations resolve independently, so the BOM can hold a module in two versions, while the license
        // tasks resolve one of them
        val components = """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/lib@1.0",
              "group": "org.example", "name": "lib", "version": "1.0",
              "licenses": [ { "license": { "id": "Apache-2.0" } } ]
            },
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/lib@2.0",
              "group": "org.example", "name": "lib", "version": "2.0",
              "licenses": [ { "license": { "id": "Apache-2.0" } } ]
            }
        """.trimIndent()
        val resolved = details.copy(concludedLicenses = mapOf("org.example:lib:2.0" to listOf("MIT")))

        val result = enrich(bom(components), resolved)

        fun licenses(version: String) = result["components"].first { it["version"].asText() == version }["licenses"]
            .map { it["license"]["id"].asText() to it["license"]["acknowledgement"].asText() }
        assertThat(licenses("1.0")).containsExactly("Apache-2.0" to "declared")
        assertThat(licenses("2.0")).containsExactly("Apache-2.0" to "declared", "MIT" to "concluded")
    }

    @Test
    fun `keeps an expression license as it is without a concluded one`() {
        val withoutConcluded = details.copy(concludedLicenses = emptyMap())

        val lib = enrich(bom(EXPRESSION_COMPONENT), withoutConcluded).component("lib")

        // with nothing to put next to it, the expression only gets its tag
        assertThat(lib["licenses"]).hasSize(1)
        assertThat(lib["licenses"][0]["expression"].asText()).isEqualTo(EXPRESSION)
        assertThat(lib["licenses"][0]["acknowledgement"].asText()).isEqualTo("declared")
    }

    @Test
    fun `tags the licenses CycloneDX wrote as declared and adds ours as concluded`() {
        val lib = enrich().component("lib")

        assertThat(lib["licenses"].map { it["license"]["id"].asText() to it["license"]["acknowledgement"].asText() })
            .containsExactly("Apache-2.0" to "declared", "MIT" to "concluded")
    }

    @Test
    fun `turns an expression license into a named entry to fit the concluded one`() {
        val concluded = details.copy(concludedLicenses = mapOf("org.example:lib:1.0" to listOf("CDDL-1.1")))

        val lib = enrich(bom(EXPRESSION_COMPONENT), concluded).component("lib")

        // the expression stays in one piece, as ids it would read as both licenses instead of either
        val expected = mapper.readTree(
            """
            [
              { "license": { "name": "$EXPRESSION", "acknowledgement": "declared" } },
              { "license": { "id": "CDDL-1.1", "acknowledgement": "concluded" } }
            ]
            """
        )
        assertThat(lib["licenses"]).isEqualTo(expected)
    }

    @Test
    fun `writes a concluded license that is not an SPDX id by name`() {
        val publicDomain = details.copy(concludedLicenses = mapOf("org.example:lib:1.0" to listOf("Public Domain")))

        val lib = enrich(with = publicDomain).component("lib")

        val concluded = lib["licenses"].last()["license"]
        assertThat(concluded["name"]?.asText()).isEqualTo("Public Domain")
        assertThat(concluded.has("id")).isFalse()
        assertThat(concluded["acknowledgement"].asText()).isEqualTo("concluded")
    }

    @Test
    fun `writes several concluded licenses as one license each`() {
        val dual = enrich().component("dual")

        assertThat(dual["licenses"].map { it["license"]["id"].asText() to it["license"]["acknowledgement"].asText() })
            .containsExactly("CDDL-1.1" to "declared", "MIT" to "concluded", "ISC" to "concluded")
    }

    // --- References ---

    @Test
    fun `copies the vcs reference as the source distribution`() {
        val lib = enrich().component("lib")

        assertThat(lib["externalReferences"].map { it["type"].asText() to it["url"].asText() })
            .contains(
                "vcs" to "https://github.com/example/lib",
                "source-distribution" to "https://github.com/example/lib",
            )
    }

    @Test
    fun `does not add a second source distribution next to one without a url`() {
        val components = """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/lib@1.0",
              "group": "org.example", "name": "lib", "version": "1.0",
              "externalReferences": [
                { "type": "vcs", "url": "https://github.com/example/lib" },
                { "type": "source-distribution" }
              ]
            }
        """.trimIndent()
        val once = SbomEnricher.enrich(bom(components), details)

        val twice = SbomEnricher.enrich(once, details)

        val lib = mapper.readTree(twice).component("lib")
        assertThat(lib["externalReferences"].map { it["type"].asText() }).containsExactly("vcs", "source-distribution")
        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `takes the component creator from the vcs reference without a website`() {
        val dual = enrich().component("dual")

        assertThat(dual["manufacturer"]["url"].map { it.asText() }).containsExactly("https://github.com/example/dual")
    }

    @Test
    fun `takes the component creator from the website reference`() {
        val lib = enrich().component("lib")

        assertThat(lib["manufacturer"]["url"].map { it.asText() }).containsExactly("https://example.org/lib")
    }

    // --- Output ---

    @Test
    fun `is idempotent`() {
        val once = SbomEnricher.enrich(bom(), details)

        val twice = SbomEnricher.enrich(once, details)

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `is idempotent for a concluded license that is not an SPDX id`() {
        val publicDomain = details.copy(concludedLicenses = mapOf("org.example:lib:1.0" to listOf("Public Domain")))
        val once = SbomEnricher.enrich(bom(), publicDomain)

        val twice = SbomEnricher.enrich(once, publicDomain)

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `is idempotent for an expression license next to a concluded one`() {
        val concluded = details.copy(concludedLicenses = mapOf("org.example:lib:1.0" to listOf("CDDL-1.1")))
        val once = SbomEnricher.enrich(bom(EXPRESSION_COMPONENT), concluded)

        val twice = SbomEnricher.enrich(once, concluded)

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `is idempotent for an incomplete dependency graph`() {
        val incomplete = details.copy(incompleteReasons = listOf(FILE_DEPENDENCY))
        val once = SbomEnricher.enrich(bom(), incomplete)

        val twice = SbomEnricher.enrich(once, incomplete)

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `writes a document that is valid for the spec version it declares`() {
        val cddl = details.copy(concludedLicenses = mapOf("org.example:lib:1.0" to listOf("CDDL-1.1")))
        val publicDomain = details.copy(concludedLicenses = mapOf("org.example:lib:1.0" to listOf("Public Domain")))
        val variants = mapOf(
            "default" to (bom() to details),
            "email creator" to (bom() to details.copy(creator = "security@example.com")),
            "incomplete dependency graph" to (bom() to details.copy(incompleteReasons = listOf(FILE_DEPENDENCY))),
            "license that is not an SPDX id" to (bom() to publicDomain),
            "expression license with a concluded one" to (bom(EXPRESSION_COMPONENT) to cddl),
            "expression license without a concluded one" to
                (bom(EXPRESSION_COMPONENT) to details.copy(concludedLicenses = emptyMap())),
        )

        for ((name, variant) in variants) {
            val (input, variantDetails) = variant
            val output = SbomEnricher.enrich(input, variantDetails)

            // against the schema of the version the document itself claims, which the enricher must not change
            val schema = Version.fromVersionString(mapper.readTree(output)["specVersion"].asText())
            val errors = JsonParser().validate(output, schema)

            assertThat(errors.map { it.message }).describedAs(name).isEmpty()
        }
    }

    private companion object {
        // a dual license in the expression form of a license list
        const val EXPRESSION = "(CDDL-1.0 OR GPL-2.0-with-classpath-exception)"

        // what the plugin reports for a jar on the classpath that is no module, which CycloneDX leaves out
        const val FILE_DEPENDENCY = "runtimeClasspath has file dependencies: vendored.jar"

        val EXPRESSION_COMPONENT = """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/lib@1.0",
              "group": "org.example", "name": "lib", "version": "1.0",
              "licenses": [ { "expression": "$EXPRESSION" } ]
            }
        """.trimIndent()

        val DEFAULT_COMPONENTS = listOf(
            // lib: a declared license and one concluded, vcs and website references, a property of its own, a jar
            """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/lib@1.0",
              "group": "org.example", "name": "lib", "version": "1.0",
              "licenses": [ { "license": { "id": "Apache-2.0" } } ],
              "externalReferences": [
                { "type": "vcs", "url": "https://github.com/example/lib" },
                { "type": "website", "url": "https://example.org/lib" }
              ],
              "properties": [ { "name": "cdx:maven:package:test", "value": "false" } ]
            }
            """,
            // dual: two concluded licenses and a creator taken from the vcs reference, no file
            """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/dual@1.0",
              "group": "org.example", "name": "dual", "version": "1.0",
              "licenses": [ { "license": { "id": "CDDL-1.1" } } ],
              "externalReferences": [ { "type": "vcs", "url": "https://github.com/example/dual" } ]
            }
            """,
            // zipped: a file that is a zip
            """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/zipped@2.0",
              "group": "org.example", "name": "zipped", "version": "2.0"
            }
            """,
            // other: a file that is neither a jar nor a zip
            """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/other@3.0",
              "group": "org.example", "name": "other", "version": "3.0"
            }
            """,
            // bom-only: nothing at all, like a Maven BOM that resolves to a POM and no file
            """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/bom-only@1.0",
              "group": "org.example", "name": "bom-only", "version": "1.0"
            }
            """,
            // sub: a project of this build, which has no POM and so nothing to take a creator from
            """
            {
              "type": "library", "bom-ref": "pkg:maven/com.hivemq/sub@1.0.0?project_path=%3Asub",
              "purl": "pkg:maven/com.hivemq/sub@1.0.0?project_path=%3Asub",
              "group": "com.hivemq", "name": "sub", "version": "1.0.0"
            }
            """,
        ).joinToString(",")

        @JvmStatic
        fun missingOrMalformedSpecVersions(): List<Arguments> = listOf(
            Arguments.of("""{"bomFormat":"CycloneDX"}""", "no CycloneDX specVersion"),
            Arguments.of("""{"specVersion":"1.x"}""", "CycloneDX specVersion 1.x"),
            // a number cannot tell 1.1 from 1.10
            Arguments.of("""{"specVersion":1.6}""", "a CycloneDX specVersion that is not a string"),
        )

        @JvmStatic
        fun valuesOfAnUnexpectedType(): List<Arguments> {
            fun lib(name: String, value: String) = """
                {
                  "specVersion": "1.6",
                  "components": [ { "group": "org.example", "name": "lib", "version": "1.0", "$name": $value } ]
                }
            """.trimIndent()
            return listOf(
                Arguments.of("/metadata/component", """{"specVersion":"1.6","metadata":{"component":"app"}}"""),
                Arguments.of("/components", """{"specVersion":"1.6","components":{}}"""),
                Arguments.of("/components/0", """{"specVersion":"1.6","components":["lib"]}"""),
                Arguments.of("/components/0/properties", lib("properties", "1")),
                Arguments.of("/components/0/externalReferences", lib("externalReferences", "1")),
                Arguments.of("/dependencies", """{"specVersion":"1.6","dependencies":{}}"""),
                Arguments.of(
                    "/compositions",
                    """{"specVersion":"1.6","dependencies":[{"ref":"a"}],"compositions":1}""",
                ),
            )
        }
    }
}
