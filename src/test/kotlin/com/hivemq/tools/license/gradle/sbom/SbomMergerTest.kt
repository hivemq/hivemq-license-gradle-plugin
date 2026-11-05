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
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.cyclonedx.Version
import org.cyclonedx.parsers.JsonParser
import org.junit.jupiter.api.Test
import java.time.Instant

class SbomMergerTest {

    private val mapper = ObjectMapper()

    private val product = ProductDetails(
        name = "product",
        version = "1.0.0",
        artifactFileName = "product-1.0.0.zip",
        creatorName = "HiveMQ GmbH",
        creator = "security@example.com",
        expectedParts = emptySet(),
    )

    /**
     * The SBOM of one part in the shape `generateSbom` writes, reduced to what the merger reads. Its top-level
     * component is [name] in [group], a `library` on purpose, so that the conversion to `application` shows; [source]
     * is its file, by default named after the part; [dependsOn] must name entries of [components], or the merge fails.
     */
    private fun part(
        name: String,
        components: String = library("lib", "aa"),
        dependsOn: List<String> = listOf(ref("lib")),
        aggregate: String? = "complete",
        specVersion: String = "1.6",
        group: String = "org.example",
        source: String = "$name.cdx.json",
    ): SbomPart {
        val primary = primaryRef(name, group)
        val refs = (listOf(primary) + dependsOn).distinct().joinToString(", ") { "\"$it\"" }
        val compositions = aggregate
            ?.let { """, "compositions": [ { "aggregate": "$it", "dependencies": [ $refs ] } ]""" }
            .orEmpty()
        val targets = dependsOn.joinToString(", ") { "\"$it\"" }
        // the entry of the top-level component, then an empty entry per target
        val entries = listOf("""{ "ref": "$primary", "dependsOn": [ $targets ] }""") +
            dependsOn.map { """{ "ref": "$it", "dependsOn": [] }""" }
        return SbomPart(
            source,
            """
            {
              "bomFormat": "CycloneDX", "specVersion": "$specVersion", "version": 1,
              "metadata": {
                "timestamp": "2026-10-16T10:01:10Z",
                "component": {
                  "type": "library", "bom-ref": "$primary",
                  "group": "$group", "name": "$name", "version": "1.0",
                  "properties": [ { "name": "bsi:component:filename", "value": "$name-1.0.jar" } ]
                }
              },
              "components": [ $components ],
              "dependencies": [ ${entries.joinToString(", ")} ]$compositions
            }
            """.trimIndent().toByteArray(),
        )
    }

    /** The `bom-ref` of the top-level component of the part [name] in [group], as `part` writes it. */
    private fun primaryRef(name: String, group: String = "org.example") = "pkg:maven/$group/$name@1.0?project_path=%3A"

    private fun ref(name: String) = "pkg:maven/org.example/$name@1.0?type=jar"

    /**
     * A library whose SHA-256 is [hash] repeated to the 64 hex digits the schema requires, and whose SHA-1 is [sha1]
     * repeated to 40 when given. Its `bom-ref` is [bomRef], by default the one of a jar called [name]; it is in [group]
     * at [version], and has the [purl] and the [scope] when given.
     */
    private fun library(
        name: String,
        hash: String,
        description: String = "a library",
        sha1: String? = null,
        bomRef: String = ref(name),
        group: String = "org.example",
        version: String = "1.0",
        purl: String? = null,
        scope: String? = null,
    ): String {
        val moreHashes = sha1?.let { """, { "alg": "SHA-1", "content": "${it.repeat(20)}" }""" }.orEmpty()
        val purlField = purl?.let { """"purl": "$it", """ }.orEmpty()
        val scopeField = scope?.let { """"scope": "$it", """ }.orEmpty()
        return """
            {
              "type": "library", "bom-ref": "$bomRef", "group": "$group", "name": "$name", "version": "$version",
              $purlField$scopeField"description": "$description",
              "hashes": [ { "alg": "SHA-256", "content": "${hash.repeat(32)}" }$moreHashes ]
            }
        """.trimIndent()
    }

    /** The library `lib` without hashes, like a Maven BOM that resolves to a POM and no file. */
    private fun libraryWithoutHashes() = """
        { "type": "library", "bom-ref": "${ref("lib")}", "group": "org.example", "name": "lib", "version": "1.0" }
    """.trimIndent()

    /** The library `lib` with [hashes], pairs of algorithm and digest, as its `hashes`, in that order. */
    private fun libraryWithHashes(vararg hashes: Pair<String, String>): String {
        val list = hashes.joinToString(", ") { (alg, content) -> """{ "alg": "$alg", "content": "$content" }""" }
        return """
            {
              "type": "library", "bom-ref": "${ref("lib")}", "group": "org.example", "name": "lib", "version": "1.0",
              "hashes": [ $list ]
            }
        """.trimIndent()
    }

    /** The library `lib` with the license [id] as its only `licenses` entry. */
    private fun libraryLicensedAs(id: String) = """
        {
          "type": "library", "bom-ref": "${ref("lib")}", "group": "org.example", "name": "lib", "version": "1.0",
          "licenses": [ { "license": { "id": "$id" } } ]
        }
    """.trimIndent()

    /** The library `lib` with [properties], pairs of name and value, as its `properties`. */
    private fun libraryWithProperties(vararg properties: Pair<String, String>): String {
        val list = properties.joinToString(", ") { (name, value) -> """{ "name": "$name", "value": "$value" }""" }
        return """
            {
              "type": "library", "bom-ref": "${ref("lib")}", "group": "org.example", "name": "lib", "version": "1.0",
              "properties": [ $list ]
            }
        """.trimIndent()
    }

    /** The library `lib` with [nested], the text of a JSON array, as its `components`. */
    private fun libraryWithNestedComponents(nested: String) = """
        {
          "type": "library", "bom-ref": "${ref("lib")}", "group": "org.example", "name": "lib", "version": "1.0",
          "components": $nested
        }
    """.trimIndent()

    private fun merge(vararg parts: SbomPart, with: ProductDetails = product): MergedSbom =
        SbomMerger.merge(parts.toList(), with)

    private fun JsonNode.component(name: String): JsonNode = this["components"].first { it["name"].asText() == name }

    private fun JsonNode.dependsOn(ref: String): List<String> =
        this["dependencies"].first { it["ref"].asText() == ref }["dependsOn"].map { it.asText() }

    private fun JsonNode.propertyPairs(): List<Pair<String, String>> =
        this["properties"].map { it["name"].asText() to it["value"].asText() }

    /** The document as text, which tells keys written in another order apart where comparing the trees does not. */
    private fun JsonNode.serialized(): String = mapper.writeValueAsString(this)

    /** This part with the library `lib` depending on [refs], which `part` leaves without dependencies. */
    private fun SbomPart.withLibraryDependingOn(vararg refs: String): SbomPart {
        val bare = """"ref": "${ref("lib")}", "dependsOn": []"""
        val depending = """"ref": "${ref("lib")}", "dependsOn": [ ${refs.joinToString(", ") { "\"$it\"" }} ]"""
        return SbomPart(source, String(bytes).replace(bare, depending).toByteArray())
    }

    /** This part with [change] applied to its document, for the shapes `part` does not write. */
    private fun SbomPart.edited(change: (ObjectNode) -> Unit): SbomPart {
        val root = mapper.readTree(bytes) as ObjectNode
        change(root)
        return SbomPart(source, mapper.writeValueAsBytes(root))
    }

    /** This part with the tool `tool` defined in `metadata.tools`, which the merge does not carry. */
    private fun SbomPart.withTool(): SbomPart = edited {
        val tool = (it["metadata"] as ObjectNode).putObject("tools").putArray("components").addObject()
        tool.put("type", "application").put("bom-ref", "tool").put("name", "tool")
    }

    /** This part with [compositions], the text of a JSON array, in place of the one `part` writes. */
    private fun SbomPart.withCompositions(compositions: String): SbomPart =
        edited { it.set<JsonNode>("compositions", mapper.readTree(compositions)) }

    /** `alpha` whose entry targets `lib`, which has no entry of its own, under a composition covering both. */
    private fun partCoveringTargetWithoutEntry(): SbomPart = part("alpha")
        .edited { root -> (root["dependencies"] as ArrayNode).removeIf { it["ref"].asText() == ref("lib") } }
        .withCompositions(
            """[ { "aggregate": "complete", "dependencies": [ "${primaryRef("alpha")}", "${ref("lib")}" ] } ]"""
        )

    // --- Components ---

    @Test
    fun `accepts a component whose list of nested components is empty`() {
        val merged = merge(part("alpha", components = libraryWithNestedComponents("[]"))).root

        assertThat(merged["components"].map { it["name"].asText() }).containsExactly("alpha", "lib")
    }

    @Test
    fun `accepts a library that lists an algorithm twice with the same digest`() {
        val digest = "aa".repeat(32)
        val twice = libraryWithHashes("SHA-256" to digest, "SHA-256" to digest)

        val first = merge(part("alpha", components = twice), part("beta", components = library("lib", "aa"))).root
        val later = merge(part("alpha", components = libraryWithoutHashes()), part("beta", components = twice)).root

        for (merged in listOf(first, later)) {
            val digests = merged.component("lib")["hashes"].map { it["content"].asText() }
            assertThat(digests.distinct()).containsExactly(digest)
        }
    }

    @Test
    fun `accepts a part whose dependency entry has an empty list of provides`() {
        val merged = merge(part("alpha").edited { (it["dependencies"][0] as ObjectNode).putArray("provides") }).root

        assertThat(merged["components"].map { it["name"].asText() }).containsExactly("alpha", "lib")
    }

    @Test
    fun `accepts a part whose formulation is empty`() {
        val merged = merge(part("alpha").edited { it.putArray("formulation") }).root

        assertThat(merged["components"].map { it["name"].asText() }).containsExactly("alpha", "lib")
    }

    @Test
    fun `accepts a part whose list of services is empty`() {
        val merged = merge(part("alpha").edited { it.putArray("services") }).root

        assertThat(merged["components"].map { it["name"].asText() }).containsExactly("alpha", "lib")
    }

    @Test
    fun `accepts a shared library whose parts list different hash algorithms`() {
        val merged = merge(
            part("alpha", components = library("lib", "aa", sha1 = "bb")),
            part("beta", components = library("lib", "aa")),
        ).root

        assertThat(merged["components"].map { it["name"].asText() }).containsExactly("alpha", "beta", "lib")
        assertThat(merged.component("lib")["hashes"].size()).isEqualTo(2)
    }

    @Test
    fun `accepts a shared library whose parts write the same hash in different case`() {
        val merged = merge(
            part("alpha", components = library("lib", "aa")),
            part("beta", components = library("lib", "AA")),
        ).root

        // one entry for the algorithm, as the part that sorts first writes it
        assertThat(merged.component("lib")["hashes"].map { it["content"].asText() }).containsExactly("aa".repeat(32))
    }

    @Test
    fun `accepts two components with the same bom-ref when one lacks the identity fields the other has`() {
        val bare = """{ "type": "library", "bom-ref": "${ref("lib")}", "name": "lib" }"""
        val detailed = library("lib", "aa", purl = "pkg:maven/org.example/lib@1.0")

        val merged = merge(part("alpha", components = bare), part("beta", components = detailed)).root

        assertThat(merged["components"].map { it["name"].asText() }).containsExactly("alpha", "beta", "lib")
        assertThat(merged.component("lib")["purl"].asText()).isEqualTo("pkg:maven/org.example/lib@1.0")
    }

    @Test
    fun `adds every entry of a property name that only a later part lists`() {
        val first = libraryWithProperties("a" to "1")
        val later = libraryWithProperties("b" to "1", "b" to "2", "a" to "2")

        val merged = merge(part("alpha", components = first), part("beta", components = later)).root

        // a property list may repeat a name, so both values of b come along
        assertThat(merged.component("lib").propertyPairs()).containsExactly("a" to "1", "b" to "1", "b" to "2")
    }

    @Test
    fun `adds the fields that only a later part lists to a shared library`() {
        val lean = libraryWithoutHashes()
        val detailed = """
            {
              "type": "library", "bom-ref": "${ref("lib")}", "group": "org.example", "name": "lib", "version": "1.0",
              "purl": "pkg:maven/org.example/lib@1.0?type=jar",
              "licenses": [ { "license": { "id": "Apache-2.0" } } ]
            }
        """.trimIndent()

        val forward = merge(part("alpha", components = lean), part("beta", components = detailed)).root
        val backward = merge(part("beta", components = detailed), part("alpha", components = lean)).root

        val lib = forward.component("lib")
        assertThat(lib["purl"].asText()).isEqualTo("pkg:maven/org.example/lib@1.0?type=jar")
        assertThat(lib["licenses"][0]["license"]["id"].asText()).isEqualTo("Apache-2.0")
        assertThat(backward.serialized()).isEqualTo(forward.serialized())
    }

    @Test
    fun `adds the hash algorithms that only a later part lists to a shared library`() {
        val merged = merge(
            part("alpha", components = library("lib", "aa")),
            part("beta", components = library("lib", "aa", sha1 = "bb")),
        ).root

        assertThat(merged.component("lib")["hashes"].map { it["alg"].asText() to it["content"].asText() })
            .containsExactly("SHA-256" to "aa".repeat(32), "SHA-1" to "bb".repeat(20))
    }

    @Test
    fun `adds the properties that only a later part lists to a shared library`() {
        val first = libraryWithProperties("cdx:maven:package:test" to "false")
        val later = libraryWithProperties(
            "bsi:component:filename" to "lib-1.0.jar",
            "cdx:maven:package:test" to "true",
        )

        val merged = merge(part("alpha", components = first), part("beta", components = later)).root

        // the first part keeps its entry for the name both list, and the other name is appended
        assertThat(merged.component("lib").propertyPairs()).containsExactly(
            "cdx:maven:package:test" to "false",
            "bsi:component:filename" to "lib-1.0.jar",
        )
    }

    @Test
    fun `keeps a library that only one of its parts lists hashes for`() {
        val hashed = library("lib", "aa")
        val unhashed = libraryWithoutHashes()

        val hashedFirst = merge(part("alpha", components = hashed), part("beta", components = unhashed)).root
        val hashedLast = merge(part("alpha", components = unhashed), part("beta", components = hashed)).root

        // an entry without hashes contradicts none, so they stay whichever part sorts first
        for (merged in listOf(hashedFirst, hashedLast)) {
            assertThat(merged["components"].map { it["name"].asText() }).containsExactly("alpha", "beta", "lib")
            assertThat(merged.component("lib")["hashes"].map { it["content"].asText() })
                .containsExactly("aa".repeat(32))
        }
    }

    @Test
    fun `keeps the application component of a part that another part lists as a library`() {
        val alphaPrimary = "pkg:maven/org.example/alpha@1.0?project_path=%3A"
        val betaPrimary = "pkg:maven/org.example/beta@1.0?project_path=%3A"

        // the part that lists the top-level component of the other sorts first, and then last
        val alphaListsBeta = merge(
            part("alpha", components = library("beta", "aa", bomRef = betaPrimary), dependsOn = listOf(betaPrimary)),
            part("beta"),
        ).root
        val betaListsAlpha = merge(
            part("alpha"),
            part("beta", components = library("alpha", "aa", bomRef = alphaPrimary), dependsOn = listOf(alphaPrimary)),
        ).root

        assertThat(alphaListsBeta.component("beta")["type"].asText()).isEqualTo("application")
        assertThat(betaListsAlpha.component("alpha")["type"].asText()).isEqualTo("application")
    }

    @Test
    fun `keeps the entry of the part that sorts first when a shared library differs in a description`() {
        val merged = merge(
            part("beta", components = library("lib", "aa", description = "from beta")),
            part("alpha", components = library("lib", "aa", description = "from alpha")),
        ).root

        assertThat(merged.component("lib")["description"].asText()).isEqualTo("from alpha")
    }

    @Test
    fun `keeps the evidence of a library that names another component as its tool`() {
        val link = "urn:cdx:f08a6ccd-4dce-4759-bd84-c626675d60a7/1#tool"
        val alpha = part("alpha", components = library("lib", "aa") + ", " + library("tool", "bb")).edited {
            val identity = (it["components"][0] as ObjectNode).putObject("evidence").putArray("identity")
            val tools = identity.addObject().put("field", "purl").putArray("tools")
            tools.add(primaryRef("alpha")).add(ref("tool")).add(link)
        }

        val merged = merge(alpha).root

        assertThat(merged.component("lib")["evidence"]["identity"][0]["tools"].map { it.asText() })
            .containsExactly(primaryRef("alpha"), ref("tool"), link)
    }

    @Test
    fun `keeps the licenses of the part that sorts first when another part lists others`() {
        val merged = merge(
            part("alpha", components = libraryLicensedAs("MIT")),
            part("beta", components = libraryLicensedAs("Apache-2.0")),
        ).root

        assertThat(merged.component("lib")["licenses"].map { it["license"]["id"].asText() }).containsExactly("MIT")
    }

    @Test
    fun `keeps the optional scope of a library that a single part lists`() {
        val merged = merge(part("alpha", components = library("lib", "aa", scope = "optional"))).root

        assertThat(merged.component("lib")["scope"].asText()).isEqualTo("optional")
    }

    @Test
    fun `keeps the scope of a library that every part excludes`() {
        val merged = merge(
            part("alpha", components = library("lib", "aa", scope = "excluded")),
            part("beta", components = library("lib", "aa", scope = "excluded")),
            part("gamma", components = library("lib", "aa", scope = "excluded")),
        ).root

        assertThat(merged.component("lib")["scope"].asText()).isEqualTo("excluded")
    }

    @Test
    fun `keeps the scope of the part that sorts first when it is none of the three texts`() {
        // a value the schema does not allow is no reason to fail, and the first part's value stands as for any field
        val unknownFirst = merge(
            part("alpha", components = library("lib", "aa", scope = "unknown")),
            part("beta", components = library("lib", "aa", scope = "excluded")),
        ).root
        val unknownLast = merge(
            part("alpha", components = library("lib", "aa", scope = "excluded")),
            part("beta", components = library("lib", "aa", scope = "unknown")),
        ).root

        assertThat(unknownFirst.component("lib")["scope"].asText()).isEqualTo("unknown")
        assertThat(unknownLast.component("lib")["scope"].asText()).isEqualTo("excluded")
    }

    @Test
    fun `lists a library two parts share once`() {
        val merged = merge(part("alpha"), part("beta")).root

        assertThat(merged["components"].map { it["name"].asText() }).containsExactly("alpha", "beta", "lib")
    }

    @Test
    fun `makes the top-level component of every part an application component`() {
        val merged = merge(part("alpha")).root

        val alpha = merged.component("alpha")
        assertThat(alpha["type"].asText()).isEqualTo("application")
        assertThat(alpha["bom-ref"].asText()).isEqualTo("pkg:maven/org.example/alpha@1.0?project_path=%3A")
        assertThat(alpha["properties"][0]["value"].asText()).isEqualTo("alpha-1.0.jar")
    }

    @Test
    fun `sorts parts by name before bom-ref`() {
        // the bom-refs and the sources both sort beta first, so only the name puts alpha first
        val alpha = part(
            "alpha",
            group = "org.z",
            source = "y.cdx.json",
            components = library("lib", "aa", description = "from alpha"),
        )
        val beta = part(
            "beta",
            group = "org.a",
            source = "x.cdx.json",
            components = library("lib", "aa", description = "from beta"),
        )

        val forward = merge(alpha, beta).root
        val backward = merge(beta, alpha).root

        assertThat(forward.component("lib")["description"].asText()).isEqualTo("from alpha")
        assertThat(backward.serialized()).isEqualTo(forward.serialized())
    }

    @Test
    fun `sorts parts with the same name by bom-ref`() {
        // the sources sort the other way round, so only the bom-ref puts org.a first
        val orgA = part(
            "alpha",
            group = "org.a",
            source = "z.cdx.json",
            components = library("lib", "aa", description = "from org.a"),
        )
        val orgB = part(
            "alpha",
            group = "org.b",
            source = "y.cdx.json",
            components = library("lib", "aa", description = "from org.b"),
        )

        val forward = merge(orgA, orgB).root
        val backward = merge(orgB, orgA).root

        assertThat(forward.component("lib")["description"].asText()).isEqualTo("from org.a")
        assertThat(backward.serialized()).isEqualTo(forward.serialized())
    }

    @Test
    fun `sorts the components by bom-ref whatever the order of the parts`() {
        val forward = merge(part("alpha"), part("beta")).root
        val backward = merge(part("beta"), part("alpha")).root

        assertThat(backward.serialized()).isEqualTo(forward.serialized())
        assertThat(forward["components"].map { it["bom-ref"].asText() }).isSorted()
    }

    @Test
    fun `writes no scope for a library that one part requires explicitly and another excludes`() {
        for ((first, second) in listOf("required" to "excluded", "excluded" to "required")) {
            val merged = merge(
                part("alpha", components = library("lib", "aa", scope = first)),
                part("beta", components = library("lib", "aa", scope = second)),
            ).root

            assertThat(merged.component("lib").has("scope")).describedAs("$first, then $second").isFalse()
        }
    }

    @Test
    fun `writes no scope for a library that the first part does not state and the second excludes`() {
        val merged = merge(
            part("alpha", components = library("lib", "aa")),
            part("beta", components = library("lib", "aa", scope = "excluded")),
        ).root

        // no scope means required, the strongest, whichever part says so
        assertThat(merged.component("lib").has("scope")).isFalse()
    }

    @Test
    fun `writes no scope for a library that the first part excludes and the second does not state`() {
        val merged = merge(
            part("alpha", components = library("lib", "aa", scope = "excluded")),
            part("beta", components = library("lib", "aa")),
        ).root

        assertThat(merged.component("lib").has("scope")).isFalse()
    }

    @Test
    fun `writes optional as the scope of a library that one part excludes and another marks optional`() {
        for ((first, second) in listOf("excluded" to "optional", "optional" to "excluded")) {
            val merged = merge(
                part("alpha", components = library("lib", "aa", scope = first)),
                part("beta", components = library("lib", "aa", scope = second)),
            ).root

            assertThat(merged.component("lib")["scope"].asText()).describedAs("$first, then $second")
                .isEqualTo("optional")
        }
    }

    @Test
    fun `writes the description of the first part when the scopes of the parts differ`() {
        val merged = merge(
            part("alpha", components = library("lib", "aa", description = "from alpha", scope = "excluded")),
            part("beta", components = library("lib", "aa", description = "from beta")),
        ).root

        // the scope goes by strength, and any other field by the part that sorts first
        assertThat(merged.component("lib").has("scope")).isFalse()
        assertThat(merged.component("lib")["description"].asText()).isEqualTo("from alpha")
    }

    // --- Dependencies ---

    @Test
    fun `makes the product depend on every part`() {
        val merged = merge(part("beta"), part("alpha")).root

        assertThat(merged.dependsOn("product@1.0.0")).containsExactly(
            "pkg:maven/org.example/alpha@1.0?project_path=%3A",
            "pkg:maven/org.example/beta@1.0?project_path=%3A",
        )
    }

    @Test
    fun `sorts the dependencies of a library whichever part lists them first`() {
        val alpha = part("alpha", components = library("lib", "aa") + ", " + library("y", "bb"))
        val beta = part("beta", components = library("lib", "aa") + ", " + library("x", "cc"))

        // alpha sorts first and adds y, then beta adds x, which sorts before it
        val merged = merge(
            alpha.withLibraryDependingOn(ref("y")),
            beta.withLibraryDependingOn(ref("x")),
        ).root

        assertThat(merged.dependsOn(ref("lib"))).containsExactly(ref("x"), ref("y"))
    }

    @Test
    fun `unions the dependencies of a library two parts share`() {
        val alpha = part("alpha", components = library("lib", "aa") + ", " + library("x", "bb"))
        val beta = part("beta", components = library("lib", "aa") + ", " + library("y", "cc"))

        val merged = merge(
            alpha.withLibraryDependingOn(ref("x")),
            beta.withLibraryDependingOn(ref("y")),
        ).root

        assertThat(merged.dependsOn(ref("lib"))).containsExactly(ref("x"), ref("y"))
    }

    // --- Failures ---

    @Test
    fun `fails on a component that uses the bom-ref of the product`() {
        val clashing = library("lib", "aa", bomRef = "product@1.0.0")
        val beta = part("beta", components = clashing, dependsOn = listOf("product@1.0.0"))

        assertThatThrownBy { merge(part("alpha"), beta) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'beta.cdx.json' uses the bom-ref 'product@1.0.0' of the product")
    }

    @Test
    fun `fails on a component with nested components`() {
        val nested = """[ { "type": "library", "bom-ref": "${ref("inner")}", "name": "inner", "version": "1.0" } ]"""

        // the graph names the nested component too, as a generator that nests components writes it
        val targets = listOf(ref("lib"), ref("inner"))
        val alpha = part("alpha", components = libraryWithNestedComponents(nested), dependsOn = targets)

        assertThatThrownBy { merge(alpha) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'alpha.cdx.json' has a component with nested components, which the merge does not flatten")
    }

    @Test
    fun `fails on a component without a bom-ref`() {
        val component = """{ "type": "library", "group": "org.example", "name": "lib", "version": "1.0" }"""

        assertThatThrownBy { merge(part("alpha", components = component, dependsOn = emptyList())) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'alpha.cdx.json' has a component without a bom-ref")
    }

    @Test
    fun `fails on a dependencies entry that references a tool defined in the metadata`() {
        val withEntry = part("alpha").withTool().edited {
            (it["dependencies"] as ArrayNode).addObject().put("ref", "tool").putArray("dependsOn")
        }

        assertThatThrownBy { merge(withEntry) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' has a dependency entry that references 'tool', which the merge does not carry"
            )
    }

    @Test
    fun `fails on a dependsOn target that references a tool defined in the metadata`() {
        val withTarget = part("alpha").withLibraryDependingOn("tool").withTool()

        assertThatThrownBy { merge(withTarget) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' has a dependency entry that references 'tool', which the merge does not carry"
            )
    }

    @Test
    fun `fails on a library that lists an algorithm twice with different digests`() {
        val twice = libraryWithHashes("SHA-256" to "aa".repeat(32), "SHA-256" to "bb".repeat(32))

        assertThatThrownBy { merge(part("alpha", components = twice)) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'alpha.cdx.json' lists different SHA-256 digests for '${ref("lib")}'")
    }

    @Test
    fun `fails on a library whose evidence references a tool defined in the metadata`() {
        val withEvidence = part("alpha").withTool().edited {
            val identity = (it["components"][0] as ObjectNode).putObject("evidence").putArray("identity")
            identity.addObject().put("field", "purl").putArray("tools").add(primaryRef("alpha")).add("tool")
        }

        assertThatThrownBy { merge(withEvidence) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'alpha.cdx.json' has evidence that references the tool 'tool', which the merge does not carry")
    }

    @Test
    fun `fails on a library whose later part lists a different hash before the matching one`() {
        // beta lists the digest of alpha last but another before it, so beta is broken on its own, whatever alpha lists
        val different = libraryWithHashes("SHA-256" to "bb".repeat(32), "SHA-256" to "aa".repeat(32))

        assertThatThrownBy {
            merge(part("alpha", components = library("lib", "aa")), part("beta", components = different))
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'beta.cdx.json' lists different SHA-256 digests for '${ref("lib")}'")
    }

    @Test
    fun `fails on a library whose parts agree on one hash algorithm and differ on another`() {
        assertThatThrownBy {
            merge(
                part("alpha", components = library("lib", "aa", sha1 = "bb")),
                part("beta", components = library("lib", "aa", sha1 = "cc")),
            )
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'${ref("lib")}' has different hashes in 'alpha.cdx.json' and 'beta.cdx.json'")
    }

    @Test
    fun `fails on a library with different hashes in two parts`() {
        assertThatThrownBy {
            merge(part("alpha", components = library("lib", "aa")), part("beta", components = library("lib", "bb")))
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'${ref("lib")}' has different hashes in 'alpha.cdx.json' and 'beta.cdx.json'")
    }

    @Test
    fun `fails on a library with different hashes in two parts after one without hashes`() {
        assertThatThrownBy {
            merge(
                part("alpha", components = libraryWithoutHashes()),
                part("beta", components = library("lib", "aa")),
                part("gamma", components = library("lib", "bb")),
            )
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'${ref("lib")}' has different hashes in 'beta.cdx.json' and 'gamma.cdx.json'")
    }

    @Test
    fun `fails on a part before the minimum spec version`() {
        assertThatThrownBy { merge(part("alpha", specVersion = "1.5")) }
            .isInstanceOf(SbomException::class.java)
            .hasMessageStartingWith("'alpha.cdx.json': The BOM declares CycloneDX specVersion 1.5")
    }

    @Test
    fun `fails on a part that has a dependency entry with provides`() {
        val withProvides = part("alpha").edited {
            (it["dependencies"][0] as ObjectNode).putArray("provides").add(ref("lib"))
        }

        assertThatThrownBy { merge(withProvides) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'alpha.cdx.json' has a dependency entry with provides, which the merge does not carry")
    }

    @Test
    fun `fails on a part that has formulation`() {
        val withFormulation = part("alpha").edited {
            val formula = it.putArray("formulation").addObject().put("bom-ref", "build")
            val tool = formula.putArray("components").addObject().put("bom-ref", "tool")
            tool.put("type", "library").put("name", "tool")
        }

        assertThatThrownBy { merge(withFormulation) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'alpha.cdx.json' has formulation, which the merge does not carry")
    }

    @Test
    fun `fails on a part that has services`() {
        val withServices = part("alpha").edited {
            it.putArray("services").addObject().put("bom-ref", "api").put("name", "api")
        }

        assertThatThrownBy { merge(withServices) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'alpha.cdx.json' has services, which the merge does not carry")
    }

    @Test
    fun `fails on a part that is not a BOM`() {
        assertThatThrownBy { merge(SbomPart("broken.cdx.json", "[]".toByteArray())) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'broken.cdx.json': The BOM is not a JSON object")
    }

    @Test
    fun `fails on a part whose bomFormat is not CycloneDX`() {
        val spdx = part("alpha").edited { it.put("bomFormat", "SPDX") }

        assertThatThrownBy { merge(spdx) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'alpha.cdx.json': The BOM declares bomFormat 'SPDX', not CycloneDX")
    }

    @Test
    fun `fails on a part whose bomFormat is not text`() {
        val numeric = part("alpha").edited { it.put("bomFormat", 1) }

        assertThatThrownBy { merge(numeric) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'alpha.cdx.json': The BOM declares no bomFormat")
    }

    @Test
    fun `fails on a part without a bomFormat`() {
        val unlabelled = part("alpha").edited { it.remove("bomFormat") }

        assertThatThrownBy { merge(unlabelled) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'alpha.cdx.json': The BOM declares no bomFormat")
    }

    @Test
    fun `fails on a part without a top-level component`() {
        val bytes = """{ "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1, "metadata": {} }""".toByteArray()

        assertThatThrownBy { merge(SbomPart("headless.cdx.json", bytes)) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'headless.cdx.json' has no top-level component with a bom-ref and a name")
    }

    @Test
    fun `fails on a top-level component that uses the bom-ref of the product`() {
        val beta = part("beta")
        val bytes = String(beta.bytes).replace(primaryRef("beta"), "product@1.0.0").toByteArray()

        assertThatThrownBy { merge(part("alpha"), SbomPart(beta.source, bytes)) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'beta.cdx.json' uses the bom-ref 'product@1.0.0' of the product")
    }

    @Test
    fun `fails on a top-level component whose evidence references a tool defined in the metadata`() {
        val withEvidence = part("alpha").withTool().edited {
            // one identity object in place of the list, the deprecated form
            val identity = (it["metadata"]["component"] as ObjectNode).putObject("evidence").putObject("identity")
            identity.put("field", "purl").putArray("tools").add("tool")
        }

        assertThatThrownBy { merge(withEvidence) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'alpha.cdx.json' has evidence that references the tool 'tool', which the merge does not carry")
    }

    @Test
    fun `fails on a top-level component with nested components`() {
        val alpha = part("alpha")
        val nested = """"components": [ { "type": "library", "bom-ref": "inner", "name": "inner" } ],"""
        // only the top-level component is named alpha
        val bytes = String(alpha.bytes).replace(""""name": "alpha", """, """"name": "alpha", $nested """).toByteArray()

        assertThatThrownBy { merge(SbomPart(alpha.source, bytes)) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'alpha.cdx.json' has a component with nested components, which the merge does not flatten")
    }

    @Test
    fun `fails on two components with the same bom-ref and different groups`() {
        assertThatThrownBy {
            merge(
                part("alpha", components = library("lib", "aa")),
                part("beta", components = library("lib", "aa", group = "org.other")),
            )
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'${ref("lib")}' names different components in 'alpha.cdx.json' and 'beta.cdx.json'")
    }

    @Test
    fun `fails on two components with the same bom-ref and different names`() {
        // the component of beta is `other`, under the bom-ref of `lib`
        assertThatThrownBy {
            merge(
                part("alpha", components = library("lib", "aa")),
                part("beta", components = library("other", "aa", bomRef = ref("lib"))),
            )
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'${ref("lib")}' names different components in 'alpha.cdx.json' and 'beta.cdx.json'")
    }

    @Test
    fun `fails on two components with the same bom-ref and different purls`() {
        assertThatThrownBy {
            merge(
                part("alpha", components = library("lib", "aa", purl = "pkg:maven/org.example/lib@1.0")),
                part("beta", components = library("lib", "aa", purl = "pkg:maven/org.other/lib@1.0")),
            )
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'${ref("lib")}' names different components in 'alpha.cdx.json' and 'beta.cdx.json'")
    }

    @Test
    fun `fails on two components with the same bom-ref and different versions`() {
        assertThatThrownBy {
            merge(
                part("alpha", components = library("lib", "aa")),
                part("beta", components = library("lib", "aa", version = "2.0")),
            )
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'${ref("lib")}' names different components in 'alpha.cdx.json' and 'beta.cdx.json'")
    }

    @Test
    fun `fails on two components with the same bom-ref naming the parts that listed the different purls`() {
        // alpha lists no purl, so the message names the parts that did
        assertThatThrownBy {
            merge(
                part("alpha", components = library("lib", "aa")),
                part("beta", components = library("lib", "aa", purl = "pkg:maven/org.example/lib@1.0")),
                part("gamma", components = library("lib", "aa", purl = "pkg:maven/org.other/lib@1.0")),
            )
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'${ref("lib")}' names different components in 'beta.cdx.json' and 'gamma.cdx.json'")
    }

    @Test
    fun `fails on two parts with the same top-level component`() {
        assertThatThrownBy { merge(part("alpha"), SbomPart("copy.cdx.json", part("alpha").bytes)) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' and 'copy.cdx.json' describe the same part " +
                    "'pkg:maven/org.example/alpha@1.0?project_path=%3A'"
            )
    }

    @Test
    fun `fails on two parts with the same top-level component whatever the order of the parts`() {
        assertThatThrownBy { merge(SbomPart("copy.cdx.json", part("alpha").bytes), part("alpha")) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' and 'copy.cdx.json' describe the same part " +
                    "'pkg:maven/org.example/alpha@1.0?project_path=%3A'"
            )
    }

    @Test
    fun `rejects a creator that is neither an email address nor an http URL`() {
        assertThatThrownBy { merge(part("alpha"), with = product.copy(creator = "HiveMQ Security Team")) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "hivemqLicense.sbomCreator must be an email address or an http(s) URL, " +
                    "but is 'HiveMQ Security Team'"
            )
    }

    @Test
    fun `reports the same unreadable part whatever the order of the parts`() {
        val first = SbomPart("a.cdx.json", "[]".toByteArray())
        val second = SbomPart("b.cdx.json", "{}".toByteArray())

        for (parts in listOf(listOf(first, second), listOf(second, first))) {
            assertThatThrownBy { merge(*parts.toTypedArray()) }
                .isInstanceOf(SbomException::class.java)
                .hasMessage("'a.cdx.json': The BOM is not a JSON object")
        }
    }

    // --- Product ---

    @Test
    fun `describes no file when the deliverable has no file name`() {
        val primary = merge(part("alpha"), with = product.copy(artifactFileName = null)).root["metadata"]["component"]

        assertThat(primary.has("properties")).isFalse()
    }

    @Test
    fun `describes the deliverable as a zip file`() {
        val primary = merge(part("alpha")).root["metadata"]["component"]

        assertThat(primary["properties"].map { it["name"].asText() to it["value"].asText() }).containsExactly(
            "bsi:component:filename" to "product-1.0.0.zip",
            "bsi:component:executable" to "non-executable",
            "bsi:component:archive" to "archive",
            "bsi:component:structured" to "structured",
        )
    }

    @Test
    fun `writes the creator as the manufacturer of the SBOM and of the product`() {
        val metadata = merge(part("alpha")).root["metadata"]

        for (manufacturer in listOf(metadata["manufacturer"], metadata["component"]["manufacturer"])) {
            assertThat(manufacturer["name"].asText()).isEqualTo("HiveMQ GmbH")
            assertThat(manufacturer["contact"][0]["email"].asText()).isEqualTo("security@example.com")
        }
    }

    @Test
    fun `writes the highest spec version of the parts`() {
        val merged = merge(part("alpha", specVersion = "1.6"), part("beta", specVersion = "1.7")).root

        assertThat(merged["specVersion"].asText()).isEqualTo("1.7")
    }

    @Test
    fun `writes the highest spec version of the parts comparing minor versions as numbers of any size`() {
        val small = merge(part("alpha", specVersion = "1.9"), part("beta", specVersion = "1.10")).root
        val large = merge(part("alpha", specVersion = "1.7"), part("beta", specVersion = "1.99999999999")).root

        assertThat(small["specVersion"].asText()).isEqualTo("1.10")
        assertThat(large["specVersion"].asText()).isEqualTo("1.99999999999")
    }

    @Test
    fun `writes the metadata with the component before the manufacturer`() {
        val metadata = merge(part("alpha")).root["metadata"]

        assertThat(metadata.fieldNames().asSequence().toList())
            .containsExactly("timestamp", "component", "manufacturer")
    }

    @Test
    fun `writes the product as the top-level component`() {
        val primary = merge(part("alpha")).root["metadata"]["component"]

        assertThat(primary["type"].asText()).isEqualTo("application")
        assertThat(primary["bom-ref"].asText()).isEqualTo("product@1.0.0")
        assertThat(primary["name"].asText()).isEqualTo("product")
        assertThat(primary["version"].asText()).isEqualTo("1.0.0")
    }

    // --- Completeness ---

    @Test
    fun `declares the product complete when a complete composition lists a non-text entry among the refs`() {
        val alpha = part("alpha").withCompositions(
            """[ { "aggregate": "complete", "dependencies": [ "${primaryRef("alpha")}", 7, "${ref("lib")}" ] } ]"""
        )

        val merged = merge(alpha)

        // the number is no ref, and the refs around it still count
        assertThat(merged.incompleteReasons).isEmpty()
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("complete")
    }

    @Test
    fun `declares the product complete when a composition covers a dependency target without an entry`() {
        val merged = merge(partCoveringTargetWithoutEntry())

        assertThat(merged.incompleteReasons).isEmpty()
        // the product's composition covers the target too, so the product passes the same rule as a part
        assertThat(merged.root["compositions"][0]["dependencies"].map { it.asText() })
            .containsExactly(primaryRef("alpha"), ref("lib"), "product@1.0.0")
    }

    @Test
    fun `declares the product complete when every part is complete and present`() {
        val merged = merge(part("alpha"), part("beta"), with = product.copy(expectedParts = setOf("alpha", "beta")))

        assertThat(merged.incompleteReasons).isEmpty()
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("complete")
        assertThat(merged.root["compositions"][0]["dependencies"].map { it.asText() }).containsExactly(
            "pkg:maven/org.example/alpha@1.0?project_path=%3A",
            "pkg:maven/org.example/beta@1.0?project_path=%3A",
            ref("lib"),
            "product@1.0.0",
        )
    }

    @Test
    fun `declares the product complete when it is merged again as a part`() {
        val merged = merge(partCoveringTargetWithoutEntry()).root
        val productSbom = SbomPart("product.cdx.json", mapper.writeValueAsBytes(merged))

        val bundle = merge(productSbom, with = product.copy(name = "bundle"))

        assertThat(bundle.incompleteReasons).isEmpty()
        assertThat(bundle.root["compositions"][0]["aggregate"].asText()).isEqualTo("complete")
    }

    @Test
    fun `declares the product complete when two complete compositions together cover a part`() {
        val primary = primaryRef("alpha")
        val alpha = part("alpha").withCompositions(
            """
            [
              { "aggregate": "complete", "dependencies": [ "$primary" ] },
              { "aggregate": "complete", "dependencies": [ "${ref("lib")}" ] }
            ]
            """.trimIndent()
        )

        val merged = merge(alpha)

        assertThat(merged.incompleteReasons).isEmpty()
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("complete")
    }

    @Test
    fun `declares the product incomplete when a complete composition covers only some dependencies of a part`() {
        val alpha = part("alpha").withCompositions(
            """[ { "aggregate": "complete", "dependencies": [ "${primaryRef("alpha")}" ] } ]"""
        )

        val merged = merge(alpha)

        // the library has an entry in the dependencies that no composition lists
        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares only part of its dependency graph complete",
        )
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `declares the product incomplete when a complete composition has dependencies that are not a list`() {
        val alpha = part("alpha").withCompositions(
            """[ { "aggregate": "complete", "dependencies": "${primaryRef("alpha")}" } ]"""
        )

        val merged = merge(alpha)

        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' does not declare whether its dependency graph is complete",
        )
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `declares the product incomplete when a complete composition leaves out a dependency target`() {
        // the library is a target of the entry of alpha and has no entry of its own
        val alpha = part("alpha")
            .edited { root -> (root["dependencies"] as ArrayNode).removeIf { it["ref"].asText() == ref("lib") } }
            .withCompositions("""[ { "aggregate": "complete", "dependencies": [ "${primaryRef("alpha")}" ] } ]""")

        val merged = merge(alpha)

        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares only part of its dependency graph complete",
        )
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `declares the product incomplete when a composition covers every entry but not the top-level component`() {
        // without a dependency entry of its own, the top-level component is in no entry the composition could list
        val alpha = part("alpha")
            .withCompositions("""[ { "aggregate": "complete", "dependencies": [ "${ref("lib")}" ] } ]""")
            .edited { root ->
                (root["dependencies"] as ArrayNode).removeIf { it["ref"].asText() == primaryRef("alpha") }
            }

        val merged = merge(alpha)

        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares only part of its dependency graph complete",
        )
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `declares the product incomplete when a composition of a part has no aggregate`() {
        val alpha = part("alpha")
        val bytes = String(alpha.bytes).replace(""""aggregate": "complete", """, "").toByteArray()

        val merged = merge(SbomPart(alpha.source, bytes))

        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' does not declare whether its dependency graph is complete",
        )
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `declares the product incomplete when a composition of a part lists only assemblies`() {
        val alpha = part("alpha").withCompositions(
            """[ { "aggregate": "complete", "assemblies": [ "${primaryRef("alpha")}", "${ref("lib")}" ] } ]"""
        )

        val merged = merge(alpha)

        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' does not declare whether its dependency graph is complete",
        )
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `declares the product incomplete when a part does not say whether it is complete`() {
        val merged = merge(part("alpha", aggregate = null))

        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' does not declare whether its dependency graph is complete",
        )
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `declares the product incomplete when a part has an incomplete composition next to a complete one`() {
        val primary = primaryRef("alpha")
        val alpha = part("alpha").withCompositions(
            """
            [
              { "aggregate": "complete", "dependencies": [ "$primary", "${ref("lib")}" ] },
              { "aggregate": "incomplete", "dependencies": [ "$primary" ] }
            ]
            """.trimIndent()
        )

        val merged = merge(alpha)

        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares its dependency graph incomplete",
        )
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `declares the product incomplete when a part is incomplete`() {
        val merged = merge(part("alpha", aggregate = "incomplete"))

        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares its dependency graph incomplete",
        )
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `declares the product incomplete when an expected part has no SBOM`() {
        val merged = merge(part("alpha"), with = product.copy(expectedParts = setOf("alpha", "tool", "broker")))

        assertThat(merged.incompleteReasons).containsExactly("no SBOM for part 'broker'", "no SBOM for part 'tool'")
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `declares the product incomplete when no complete composition covers the top-level component of a part`() {
        val alpha = part("alpha").withCompositions(
            """[ { "aggregate": "complete", "dependencies": [ "${ref("lib")}" ] } ]"""
        )

        val merged = merge(alpha)

        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares only part of its dependency graph complete",
        )
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    // --- Output ---

    @Test
    fun `writes a document that is valid for the spec version it declares`() {
        val variants = mapOf(
            "two parts" to merge(part("alpha"), part("beta")),
            "url creator" to merge(part("alpha"), with = product.copy(creator = "https://www.hivemq.com/")),
            "no file" to merge(part("alpha"), with = product.copy(artifactFileName = null)),
            "incomplete" to merge(part("alpha", aggregate = "incomplete")),
            "no parts" to merge(),
        )

        for ((name, merged) in variants) {
            val output = SbomNormalizer.normalize(
                merged.root,
                Instant.parse("2026-01-02T03:04:05Z"),
                "https://github.com/hivemq/x",
            )

            val schema = Version.fromVersionString(mapper.readTree(output)["specVersion"].asText())
            val errors = JsonParser().validate(output, schema)

            assertThat(errors.map { it.message }).describedAs(name).isEmpty()
        }
    }
}
