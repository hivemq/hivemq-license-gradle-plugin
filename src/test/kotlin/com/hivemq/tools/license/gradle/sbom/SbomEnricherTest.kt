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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.hivemq.tools.license.gradle.Coordinates
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
            "org.example:lib:1.0" to ConcludedLicense.Elected(listOf("MIT")),
            "org.example:dual:1.0" to ConcludedLicense.Elected(listOf("MIT", "ISC")),
        ),
        incompleteReasons = emptyList(),
    )

    /** The jakarta.annotation-api case, [CHOICE_COMPONENT]: both declared alternatives in one concluded expression. */
    private val choiceDetails = concludingExpression(CHOICE_EXPRESSION)

    /** What the enricher writes for [choiceDetails]: the expression as the whole license list. */
    private val concludedChoiceList = concludedList(CHOICE_EXPRESSION)

    /** [details] with [expression] as the concluded license of `org.example:lib` 1.0. */
    private fun concludingExpression(expression: String) =
        details.copy(concludedLicenses = mapOf("org.example:lib:1.0" to ConcludedLicense.Expression(expression)))

    /** The license list the enricher writes for a concluded [expression]: that expression alone. */
    private fun concludedList(expression: String): JsonNode =
        mapper.readTree("""[ { "expression": "$expression", "acknowledgement": "concluded" } ]""")

    /** [details] with [ids] as the concluded licenses of `org.example:lib` in [version]. */
    private fun concluding(vararg ids: String, version: String = "1.0") =
        details.copy(concludedLicenses = mapOf("org.example:lib:$version" to ConcludedLicense.Elected(ids.toList())))

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
        SbomEnricher.enrich(BomJson.read(input), with)

    /**
     * [bom] with compositions of its own: one the SBOM's statement changes, one only its bom-ref keeps, one already not
     * specified, two that become equal and one it leaves alone.
     */
    private fun withOwnCompositions(): ByteArray {
        val lib = "pkg:maven/org.example/lib@1.0"
        val elsewhere = "pkg:maven/org.example/elsewhere@1.0"
        val root = BomJson.read(bom())
        val compositions = root.putArray("compositions")
        val changed = compositions.addObject().put("aggregate", "complete")
        changed.putArray("assemblies").add(lib)
        changed.putArray("dependencies").add(lib).add(elsewhere)
        compositions.addObject().put("bom-ref", "composition-1").put("aggregate", "complete")
            .putArray("dependencies").add(lib)
        compositions.addObject().put("bom-ref", "composition-2").put("aggregate", "not_specified")
        for (covered in listOf(lib, root["metadata"]["component"]["bom-ref"].asText())) {
            val twin = compositions.addObject().put("aggregate", "complete")
            twin.putArray("assemblies").add(lib)
            twin.putArray("dependencies").add(covered)
        }
        val untouched = compositions.addObject().put("aggregate", "complete")
        untouched.putArray("assemblies").add(elsewhere)
        return mapper.writeValueAsBytes(root)
    }

    private fun JsonNode.component(name: String): JsonNode = this["components"].first { it["name"].asText() == name }

    private fun JsonNode.property(name: String): String? =
        this["properties"]?.firstOrNull { it["name"].asText() == name }?.get("value")?.asText()

    // --- Input ---

    @Test
    fun `accepts a BOM that ends with a newline`() {
        val result = enrich(bom() + "\n".toByteArray())

        assertThat(result["metadata"]["manufacturer"]["name"].asText()).isEqualTo("HiveMQ GmbH")
    }

    // the added fields exist from the minimum on, and a later specification keeps every earlier field
    @ParameterizedTest
    @ValueSource(strings = ["1.6.1", "1.7", "1.10"])
    fun `accepts a spec version at or after the minimum`(specVersion: String) {
        val result = enrich(bom(specVersion = specVersion))

        assertThat(result["metadata"]["manufacturer"]["name"].asText()).isEqualTo("HiveMQ GmbH")
    }

    @Test
    fun `enriches a component nested in another one like a top-level one`() {
        val nested = """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/outer@1.0",
              "group": "org.example", "name": "outer", "version": "1.0",
              "licenses": [ { "license": { "id": "Apache-2.0" } } ],
              "components": [
                {
                  "type": "library", "bom-ref": "pkg:maven/org.example/lib@1.0",
                  "group": "org.example", "name": "lib", "version": "1.0",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ]
                }
              ]
            }
        """.trimIndent()

        val lib = enrich(bom(components = nested)).component("outer")["components"].single()

        assertThat(lib.property("bsi:component:filename")).isEqualTo("lib-1.0.jar")
        assertThat(lib["licenses"].map { it["license"]["acknowledgement"].asText() })
            .containsExactly("declared", "concluded")
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "garbage"])
    fun `rejects a BOM that is followed by more content`(trailing: String) {
        val input = "{\"specVersion\":\"1.6\"}\n$trailing"

        assertThatThrownBy { enrich(input.toByteArray()) }
            .isInstanceOf(SbomException::class.java)
            // a second JSON value is named as such, anything else as invalid JSON
            .hasMessageMatching("The BOM (has content after its JSON value|is not valid JSON: .+) at line 2, column 1")
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
        // the BOM task can be configured to write an older spec version
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

        for ((variant, with) in mapOf("elected licenses" to details, "an expression" to choiceDetails)) {
            val result = enrich(bom(components), with)

            assertThat(result.component("lib")["licenses"].asText()).describedAs(variant).isEqualTo("MIT")
            assertThat(result["metadata"]["manufacturer"]["name"].asText()).describedAs(variant)
                .isEqualTo("HiveMQ GmbH")
        }
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
            "security@example.com", "first.last@sub.example.com", "first.last+sbom@example.com",
            "security@my-company.com", "o'brien_x@example.com", "info@m\u00fcller-gmbh.de",
            "mailto:security@example.com",
            "http://localhost:8080/", "https://www.hivemq.com/", "HTTPS://www.hivemq.com/",
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

    // a name makes the SBOM invalid and the rest lead nowhere, though HiveMQ:Security and localhost:8080 have a scheme;
    // an at sign makes no address with a path, scheme, query, fragment, brackets, quotes, separator or bad label
    @ParameterizedTest
    @ValueSource(
        strings = [
            "HiveMQ Security Team", "www.hivemq.com", "",
            "HiveMQ:Security", "localhost:8080", "file:///tmp/x", "mailto:foo", "https:foo",
            "security@example.com/contact", "ssh://git@example.com", "security@localhost",
            "mailto:security@hivemq.com?subject=SBOM", "<security@hivemq.com>", "security@hivemq.com#sbom",
            "security@hivemq.com,", "security@hivemq.com;",
            "security@example..com", "security@.example.com", "security@example.com.", "security..team@example.com",
            "security@exam[ple.com", "security[team]@example.com", "\"security\"@example.com",
            "security@exa_mple.com", "security@-example.com",
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
    fun `declares everything incomplete when the build reports a gap, identified components included`() {
        val lib = "pkg:maven/org.example/lib@1.0"
        val result = enrich(
            with = details.copy(incompleteReasons = listOf(FILE_DEPENDENCY), identifiedComponents = setOf(lib))
        )

        // one composition says it all, a second one with the same aggregate would add nothing
        assertThat(result["compositions"]).hasSize(1)
        assertThat(result["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
        assertThat(result["compositions"][0]["dependencies"].map { it.asText() })
            .containsExactly("pkg:maven/com.hivemq/app@1.0.0?project_path=%3A", lib)
    }

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
    fun `declares the identified components incomplete in a composition of their own`() {
        val lib = "pkg:maven/org.example/lib@1.0"

        val result = enrich(with = details.copy(identifiedComponents = setOf(lib)))

        // an identified component depends on nothing here because the host's SBOM describes it, not because it has
        // no dependencies, so the graph is complete for the others only
        assertThat(result["compositions"].map { it["aggregate"].asText() }).containsExactly("complete", "incomplete")
        assertThat(result["compositions"][0]["dependencies"].map { it.asText() })
            .containsExactly("pkg:maven/com.hivemq/app@1.0.0?project_path=%3A")
        assertThat(result["compositions"][1]["dependencies"].map { it.asText() }).containsExactly(lib)
    }

    @Test
    fun `declares the top-level component incomplete for a gap in a BOM without a dependency graph`() {
        val result = enrich(NO_GRAPH.toByteArray(), details.copy(incompleteReasons = listOf(FILE_DEPENDENCY)))

        // the BOM's own composition names nothing, so it goes, and the top-level component is the only ref to carry it
        assertThat(result["compositions"]).hasSize(1)
        assertThat(result["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
        assertThat(result["compositions"][0]["dependencies"].map { it.asText() })
            .containsExactly("pkg:maven/com.hivemq/app@1.0.0")
    }

    @Test
    fun `declares the top-level component incomplete for a gap when the graph has no entry for it`() {
        val input = """
            {
              "specVersion": "1.6",
              "metadata": { "component": { "type": "application", "bom-ref": "app", "name": "app" } },
              "dependencies": [ { "ref": "lib" } ]
            }
        """.trimIndent()

        val result = enrich(input.toByteArray(), details.copy(incompleteReasons = listOf(FILE_DEPENDENCY)))

        // the gap is the artifact's, so it is stated for the artifact even where the graph does not list it
        assertThat(result["compositions"].map { it["aggregate"].asText() }).containsExactly("incomplete")
        assertThat(result["compositions"][0]["dependencies"].map { it.asText() }).containsExactly("app", "lib")
    }

    @Test
    fun `drops a composition entry of the BOM's own that is not an object`() {
        val input = """
            {
              "specVersion": "1.6",
              "dependencies": [ { "ref": "a" } ],
              "compositions": [ 1, { "aggregate": "complete", "assemblies": [ "a" ] } ]
            }
        """.trimIndent()

        val result = enrich(input.toByteArray())

        // the schema wants objects there, and only an object has a statement to keep
        assertThat(result["compositions"]).hasSize(2).allMatch { it.isObject }
    }

    @Test
    fun `drops a composition of the BOM's own left naming nothing, keeping one with a bom-ref as not specified`() {
        val lib = "pkg:maven/org.example/lib@1.0"
        val root = BomJson.read(bom())
        val compositions = root.putArray("compositions")
        compositions.addObject().put("aggregate", "unknown").putArray("dependencies").add(lib)
        compositions.addObject().put("bom-ref", "composition-1").put("aggregate", "incomplete")
            .putArray("dependencies").add(lib)
        // an empty bom-ref refers to nothing
        compositions.addObject().put("bom-ref", "").put("aggregate", "unknown").putArray("dependencies").add(lib)
        val vulnerable = compositions.addObject().put("aggregate", "complete")
        vulnerable.putArray("dependencies").add(lib)
        vulnerable.putArray("vulnerabilities").add("vulnerability-1")

        val result = SbomEnricher.enrich(root, details)

        // a reference elsewhere in the BOM may point at the second one, which keeps no claim about the refs it lost
        assertThat(result["compositions"].map { it["aggregate"].asText() })
            .containsExactly("complete", "not_specified", "complete")
        assertThat(result["compositions"][1]["bom-ref"].asText()).isEqualTo("composition-1")
        assertThat(result["compositions"][1].has("dependencies")).isFalse()
        // the last one still names a vulnerability
        assertThat(result["compositions"][2].toString())
            .isEqualTo("""{"aggregate":"complete","vulnerabilities":["vulnerability-1"]}""")
    }

    @Test
    fun `keeps a bare composition of the BOM's own with a bom-ref as not specified when the build reports a gap`() {
        val root = BomJson.read(NO_GRAPH.toByteArray())
        root.putArray("compositions").addObject().put("bom-ref", "composition-1").put("aggregate", "complete")

        val result = SbomEnricher.enrich(root, details.copy(incompleteReasons = listOf(FILE_DEPENDENCY)))

        // read as a claim about the whole BOM it would contradict the gap, but a reference may point at it
        assertThat(result["compositions"].map { it["aggregate"].asText() })
            .containsExactly("incomplete", "not_specified")
        assertThat(result["compositions"][1]["bom-ref"].asText()).isEqualTo("composition-1")
    }

    @Test
    fun `keeps a dependencies value of another type in a composition of the BOM's own`() {
        val root = BomJson.read(bom())
        root.putArray("compositions").addObject().put("aggregate", "complete").put("dependencies", "all")

        val result = SbomEnricher.enrich(root, details)

        assertThat(result["compositions"][1]["dependencies"].asText()).isEqualTo("all")
    }

    @Test
    fun `keeps a numeric dependency of a composition of the BOM's own, even where a covered ref has its digits`() {
        val input = """
            {
              "specVersion": "1.6",
              "dependencies": [ { "ref": "7" } ],
              "compositions": [ { "aggregate": "complete", "dependencies": [ 7 ] } ]
            }
        """.trimIndent()

        val result = enrich(input.toByteArray())

        // the SBOM's statement covers refs, which are text
        assertThat(result["compositions"][1]["dependencies"].single().isNumber).isTrue()
    }

    @Test
    fun `keeps the compositions of a BOM without a dependency graph when the build reports no gap`() {
        val result = enrich(NO_GRAPH.toByteArray())

        // nothing contradicts what the BOM states, and without a graph there is nothing to state instead
        assertThat(result["compositions"].map { it["aggregate"].asText() }).containsExactly("complete")
    }

    // with and without a graph, as the gap then covers the graph or only the top-level component
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `keeps what the compositions of the BOM's own say beyond the refs the SBOM covers`(withGraph: Boolean) {
        val root = BomJson.read(if (withGraph) bom() else NO_GRAPH.toByteArray())
        val topLevel = root["metadata"]["component"]["bom-ref"].asText()
        val elsewhere = "pkg:maven/org.example/elsewhere@1.0"
        val own = root.putArray("compositions").addObject().put("aggregate", "complete")
        own.putArray("assemblies").add(topLevel)
        own.putArray("dependencies").add(topLevel).add(elsewhere).add(7)
        own.putArray("vulnerabilities").add("vulnerability-1")

        val result = SbomEnricher.enrich(root, details.copy(incompleteReasons = listOf(FILE_DEPENDENCY)))

        // the dependency statement for the top-level component is the SBOM's now, the rest stays as the BOM said it
        assertThat(result["compositions"].map { it["aggregate"].asText() }).containsExactly("incomplete", "complete")
        val kept = result["compositions"][1]
        assertThat(kept["assemblies"].map { it.asText() }).containsExactly(topLevel)
        assertThat(kept["dependencies"].map { it.asText() }).containsExactly(elsewhere, "7")
        assertThat(kept["vulnerabilities"].map { it.asText() }).containsExactly("vulnerability-1")
    }

    @Test
    fun `rejects a BOM whose compositions are not an array when it has identified components, before changing it`() {
        val root = BomJson.read(bom())
        root.put("compositions", "complete")
        val unchanged = root.deepCopy()
        val identified = details.copy(identifiedComponents = setOf("pkg:maven/org.example/lib@1.0"))

        // an identified component is a gap of its own, which the SBOM has to state as well
        assertThatThrownBy { SbomEnricher.enrich(root, identified) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "The BOM's compositions are not a JSON array, so the SBOM cannot state that its dependency graph " +
                    "is incomplete: " +
                    "identified components (the first module on each path that hivemqProvided resolves), whose " +
                    "dependencies the host's SBOM describes"
            )
        assertThat(root).isEqualTo(unchanged)
    }

    // with and without a graph, so the check holds wherever the gap would be stated
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `rejects a BOM whose compositions are not an array when the build reports a gap, before changing it`(
        withGraph: Boolean,
    ) {
        val root = BomJson.read(if (withGraph) bom() else NO_GRAPH.toByteArray())
        root.put("compositions", "complete")
        val unchanged = root.deepCopy()

        assertThatThrownBy { SbomEnricher.enrich(root, details.copy(incompleteReasons = listOf(FILE_DEPENDENCY))) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "The BOM's compositions are not a JSON array, so the SBOM cannot state that its dependency graph " +
                    "is incomplete: " +
                    FILE_DEPENDENCY
            )
        assertThat(root).isEqualTo(unchanged)
    }

    // no top-level bom-ref as non-empty text; a dependency graph, or none in each form the enricher reads as none
    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"specVersion":"1.6","metadata":{"component":{"name":"app"}},"dependencies":[{"ref":"lib"}]}""",
            """{"specVersion":"1.6","metadata":{"component":{"name":"app"}}}""",
            """{"specVersion":"1.6","metadata":{"component":{"name":"app"}},"dependencies":[]}""",
            """{"specVersion":"1.6","metadata":{"component":{"name":"app"}},"dependencies":[{"ref":7}]}""",
            """{"specVersion":"1.6","metadata":{"component":{"name":"app"}},"dependencies":{}}""",
            """{"specVersion":"1.6","metadata":{"component":{"name":"app","bom-ref":7}}}""",
            """{"specVersion":"1.6","metadata":{"component":{"name":"app","bom-ref":""}}}""",
            """{"specVersion":"1.6"}""",
        ]
    )
    fun `rejects a BOM whose top-level component has no bom-ref for a reported gap, before changing it`(
        input: String,
    ) {
        val root = BomJson.read(input.toByteArray())
        val unchanged = root.deepCopy()

        assertThatThrownBy { SbomEnricher.enrich(root, details.copy(incompleteReasons = listOf(FILE_DEPENDENCY))) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "The BOM has no bom-ref on metadata.component, so the SBOM cannot state that its dependency graph " +
                    "is incomplete: $FILE_DEPENDENCY"
            )
        assertThat(root).isEqualTo(unchanged)
    }

    @Test
    fun `writes no composition without a dependency graph when the build reports no gap`() {
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

    @Test
    fun `writes the compositions in the place the BOM has them`() {
        val input = """{"specVersion":"1.6","compositions":[{"aggregate":"unknown"}],"dependencies":[{"ref":"a"}]}"""

        val result = enrich(input.toByteArray())

        // rewritten in place, so the field does not move between the BOM and the SBOM
        assertThat(result.fieldNames().asSequence().toList()).containsSubsequence("compositions", "dependencies")
        assertThat(result["compositions"].map { it["aggregate"].asText() }).containsExactly("complete")
    }

    @Test
    fun `writes two compositions of the BOM's own once when they become equal`() {
        val root = BomJson.read(bom())
        val compositions = root.putArray("compositions")
        for (ref in listOf("pkg:maven/org.example/lib@1.0", "pkg:maven/com.hivemq/app@1.0.0?project_path=%3A")) {
            val own = compositions.addObject().put("aggregate", "complete")
            own.putArray("assemblies").add("pkg:maven/org.example/lib@1.0")
            own.putArray("dependencies").add(ref)
        }

        val result = SbomEnricher.enrich(root, details)

        // the schema wants each composition once, and both lose their only dependency to the SBOM's own statement
        assertThat(result["compositions"]).hasSize(2)
        assertThat(result["compositions"][1].toString())
            .isEqualTo("""{"aggregate":"complete","assemblies":["pkg:maven/org.example/lib@1.0"]}""")
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
    fun `gives an identified component no file description, whatever the build resolved for its coordinates`() {
        val identified = details.copy(identifiedComponents = setOf("pkg:maven/org.example/lib@1.0"))

        val lib = enrich(with = identified).component("lib")

        assertThat(lib.property("bsi:component:filename")).isNull()
        assertThat(lib.property("bsi:component:executable")).isNull()
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
            concludedLicenses = mapOf(coordinates.toString() to ConcludedLicense.Elected(listOf("MIT"))),
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
    fun `adds a concluded expression to a component without any license`() {
        val components = """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/lib@1.0",
              "group": "org.example", "name": "lib", "version": "1.0"
            }
        """.trimIndent()

        val lib = enrich(bom(components), choiceDetails).component("lib")

        assertThat(lib["licenses"]).isEqualTo(concludedChoiceList)
    }

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
        val resolved = concluding("MIT", version = "2.0")

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
    fun `keeps the bom-ref of an expression license it turns into a named entry`() {
        val withRef = EXPRESSION_COMPONENT.replace(
            """"expression": "$EXPRESSION" }""",
            """"expression": "$EXPRESSION", "bom-ref": "lic-1" }""",
        )

        val lib = enrich(bom(withRef), concluding("CDDL-1.1")).component("lib")

        // something else may refer to the entry, so its ref moves with it
        assertThat(lib["licenses"][0]["license"]["bom-ref"].asText()).isEqualTo("lic-1")
    }

    @Test
    fun `replaces a declared expression by the concluded expression`() {
        val lib = enrich(bom(EXPRESSION_COMPONENT), concludingExpression(EXPRESSION_CANONICAL)).component("lib")

        assertThat(lib["licenses"]).isEqualTo(concludedList(EXPRESSION_CANONICAL))
    }

    @Test
    fun `replaces the declared licenses by the concluded expression`() {
        // with a URL, as CycloneDX writes for many POMs, which goes with its entry
        val withUrl = CHOICE_COMPONENT.replace(
            """{ "id": "EPL-2.0" }""",
            """{ "id": "EPL-2.0", "url": "https://www.eclipse.org/legal/epl-2.0" }""",
        )
        assertThat(withUrl).describedAs("the fixture with a URL").contains("\"url\"")

        val lib = enrich(bom(withUrl), choiceDetails).component("lib")

        // a CycloneDX 1.6 license list cannot hold them next to an expression, which names every alternative anyway
        assertThat(lib["licenses"]).isEqualTo(concludedChoiceList)
    }

    @Test
    fun `tags the licenses CycloneDX wrote as declared and adds ours as concluded`() {
        val lib = enrich().component("lib")

        assertThat(lib["licenses"].map { it["license"]["id"].asText() to it["license"]["acknowledgement"].asText() })
            .containsExactly("Apache-2.0" to "declared", "MIT" to "concluded")
    }

    @Test
    fun `turns an expression license into a named entry to fit the concluded one`() {
        val concluded = concluding("CDDL-1.1")

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
        val publicDomain = concluding("Public Domain")

        val lib = enrich(with = publicDomain).component("lib")

        val concluded = lib["licenses"].last()["license"]
        assertThat(concluded["name"]?.asText()).isEqualTo("Public Domain")
        assertThat(concluded.has("id")).isFalse()
        assertThat(concluded["acknowledgement"].asText()).isEqualTo("concluded")
    }

    @Test
    fun `writes several concluded licenses without an expression as one license each`() {
        // the shape a component keeps when its declared licenses give no SPDX expression
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
        val once = SbomEnricher.enrich(BomJson.read(bom(components)), details)

        val twice = SbomEnricher.enrich(once.deepCopy(), details)

        val lib = twice.component("lib")
        assertThat(lib["externalReferences"].map { it["type"].asText() }).containsExactly("vcs", "source-distribution")
        assertThat(twice.toString()).isEqualTo(once.toString())
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
        val once = SbomEnricher.enrich(BomJson.read(bom()), details)

        val twice = SbomEnricher.enrich(once.deepCopy(), details)

        assertThat(twice.toString()).isEqualTo(once.toString())
    }

    @Test
    fun `is idempotent for a concluded expression`() {
        val once = SbomEnricher.enrich(BomJson.read(bom(CHOICE_COMPONENT)), choiceDetails)

        val twice = SbomEnricher.enrich(once.deepCopy(), choiceDetails)

        assertThat(twice.toString()).isEqualTo(once.toString())
    }

    @Test
    fun `is idempotent for a concluded license that is not an SPDX id`() {
        val publicDomain = concluding("Public Domain")
        val once = SbomEnricher.enrich(BomJson.read(bom()), publicDomain)

        val twice = SbomEnricher.enrich(once.deepCopy(), publicDomain)

        assertThat(twice.toString()).isEqualTo(once.toString())
    }

    @Test
    fun `is idempotent for a gap in a BOM without a dependency graph`() {
        val incomplete = details.copy(incompleteReasons = listOf(FILE_DEPENDENCY))
        val once = SbomEnricher.enrich(BomJson.read(NO_GRAPH.toByteArray()), incomplete)

        val twice = SbomEnricher.enrich(once.deepCopy(), incomplete)

        assertThat(twice.toString()).isEqualTo(once.toString())
    }

    @Test
    fun `is idempotent for an expression license next to a concluded one`() {
        val concluded = concluding("CDDL-1.1")
        val once = SbomEnricher.enrich(BomJson.read(bom(EXPRESSION_COMPONENT)), concluded)

        val twice = SbomEnricher.enrich(once.deepCopy(), concluded)

        assertThat(twice.toString()).isEqualTo(once.toString())
    }

    @Test
    fun `is idempotent for an incomplete dependency graph`() {
        val incomplete = details.copy(incompleteReasons = listOf(FILE_DEPENDENCY))
        val once = SbomEnricher.enrich(BomJson.read(bom()), incomplete)

        val twice = SbomEnricher.enrich(once.deepCopy(), incomplete)

        assertThat(twice.toString()).isEqualTo(once.toString())
    }

    @Test
    fun `is idempotent with compositions of the BOM's own`() {
        val incomplete = details.copy(incompleteReasons = listOf(FILE_DEPENDENCY))
        val once = SbomEnricher.enrich(BomJson.read(withOwnCompositions()), incomplete)

        val twice = SbomEnricher.enrich(once.deepCopy(), incomplete)

        assertThat(twice.toString()).isEqualTo(once.toString())
    }

    @Test
    fun `is idempotent with identified components`() {
        val with = details.copy(identifiedComponents = setOf("pkg:maven/org.example/lib@1.0"))
        val once = SbomEnricher.enrich(BomJson.read(bom()), with)

        val twice = SbomEnricher.enrich(once.deepCopy(), with)

        assertThat(twice.toString()).isEqualTo(once.toString())
    }

    @Test
    fun `writes a document that is valid for the spec version it declares`() {
        val cddl = concluding("CDDL-1.1")
        val publicDomain = concluding("Public Domain")
        val variants = mapOf(
            "default" to (bom() to details),
            "email creator" to (bom() to details.copy(creator = "security@example.com")),
            "dotted email creator" to (bom() to details.copy(creator = "first.last@sub.example.com")),
            "tagged email creator" to (bom() to details.copy(creator = "first.last+sbom@example.com")),
            "international email creator" to (bom() to details.copy(creator = "info@m\u00fcller-gmbh.de")),
            "incomplete dependency graph" to (bom() to details.copy(incompleteReasons = listOf(FILE_DEPENDENCY))),
            "gap without a dependency graph" to
                (NO_GRAPH.toByteArray() to details.copy(incompleteReasons = listOf(FILE_DEPENDENCY))),
            "compositions of the BOM's own" to (withOwnCompositions() to details),
            "compositions of the BOM's own with a gap" to
                (withOwnCompositions() to details.copy(incompleteReasons = listOf(FILE_DEPENDENCY))),
            "identified components" to
                (bom() to details.copy(identifiedComponents = setOf("pkg:maven/org.example/lib@1.0"))),
            "license that is not an SPDX id" to (bom() to publicDomain),
            "concluded expression" to (bom(CHOICE_COMPONENT) to choiceDetails),
            "concluded expression over a declared expression" to
                (bom(EXPRESSION_COMPONENT) to concludingExpression(EXPRESSION_CANONICAL)),
            "expression license with a concluded one" to (bom(EXPRESSION_COMPONENT) to cddl),
            "expression license without a concluded one" to
                (bom(EXPRESSION_COMPONENT) to details.copy(concludedLicenses = emptyMap())),
        )

        for ((name, variant) in variants) {
            val (input, variantDetails) = variant
            val output = SbomEnricher.enrich(BomJson.read(input), variantDetails)

            // against the schema of the version the document itself claims, which the enricher must not change
            val schema = Version.fromVersionString(output["specVersion"].asText())
            val errors = JsonParser().validate(mapper.writeValueAsBytes(output), schema)

            assertThat(errors.map { it.message }).describedAs(name).isEmpty()
        }
    }

    private companion object {
        // a choice of two licenses in the expression form of a license list
        const val EXPRESSION = "(CDDL-1.0 OR GPL-2.0-with-classpath-exception)"

        // EXPRESSION in the canonical form the resolver gives it
        const val EXPRESSION_CANONICAL = "CDDL-1.0 OR GPL-2.0-only WITH Classpath-exception-2.0"

        // the two alternatives of jakarta.annotation-api in current SPDX form
        const val CHOICE_EXPRESSION = "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0"

        // what the plugin reports for a jar on the classpath that is no module, which CycloneDX leaves out
        const val FILE_DEPENDENCY = "runtimeClasspath has file dependencies: vendored.jar"

        // a BOM without a dependency graph that says it is complete, as a BOM from another tool can
        val NO_GRAPH = """
            {
              "bomFormat": "CycloneDX", "specVersion": "1.6",
              "metadata": {
                "component": { "type": "application", "bom-ref": "pkg:maven/com.hivemq/app@1.0.0", "name": "app" }
              },
              "compositions": [ { "aggregate": "complete" } ]
            }
        """.trimIndent()

        // the two license elements of the POM of jakarta.annotation-api, as CycloneDX writes them
        val CHOICE_COMPONENT = """
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/lib@1.0",
              "group": "org.example", "name": "lib", "version": "1.0",
              "licenses": [
                { "license": { "id": "EPL-2.0" } },
                { "license": { "id": "GPL-2.0-with-classpath-exception" } }
              ]
            }
        """.trimIndent()

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
                Arguments.of(
                    "/compositions",
                    """{"specVersion":"1.6","dependencies":[{"ref":"a"}],"compositions":null}""",
                ),
            )
        }
    }
}
