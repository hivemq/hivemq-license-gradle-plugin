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

    // the SDK as an extension that builds against a released version identifies it
    private val releasedSdk = "pkg:maven/com.hivemq/hivemq-extension-sdk@4.1.0?type=jar"

    // the SDK as the broker describes it in a composite build that includes the SDK's build
    private val compositeSdk = "pkg:maven/com.hivemq/hivemq-extension-sdk@4.58.0-SNAPSHOT?project_path=%3A"

    private val declaredGap = listOf("the extensions run on the broker of the platform image, whose SBOM describes it")

    // the remedy of identifiedGap for a host part, which no part fills
    private val ofHost =
        "no part fills the gap of a host part; to accept it, declare it in declaredIdentifiedGapReasons"

    // how identifiedGap ends where host parts are named: the cause, then how to accept the gap
    private val accept = "to accept the gap, declare it in declaredIdentifiedGapReasons"

    // the product of a deliverable that ships the broker, which hosts the extensions
    private val hosted = product.copy(hostParts = setOf("broker"))

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

    /** The library [name] whose only `licenses` entry is `MIT` under the license bom-ref [licenseRef]. */
    private fun libraryWithLicenseRef(name: String, licenseRef: String) = """
        {
          "type": "library", "bom-ref": "${ref(name)}", "group": "org.example", "name": "$name", "version": "1.0",
          "licenses": [ { "license": { "bom-ref": "$licenseRef", "id": "MIT" } } ]
        }
    """.trimIndent()

    /** The library `lib` with [licenses], texts of entries such as [licenseEntry] writes, as its `licenses`. */
    private fun libraryWithLicenses(vararg licenses: String) = """
        {
          "type": "library", "bom-ref": "${ref("lib")}", "group": "org.example", "name": "lib", "version": "1.0",
          "licenses": [ ${licenses.joinToString(", ")} ]
        }
    """.trimIndent()

    /** A `licenses` entry for the license object [id], tagged [acknowledgement]. */
    private fun licenseEntry(id: String, acknowledgement: String = "concluded") =
        """{ "license": { "id": "$id", "acknowledgement": "$acknowledgement" } }"""

    /** A `licenses` entry for the SPDX [expression], tagged `concluded`. */
    private fun expressionEntry(expression: String) =
        """{ "expression": "$expression", "acknowledgement": "concluded" }"""

    /** The library [name] in [group] at [version] whose purl is its [bomRef]. */
    private fun module(group: String, name: String, version: String, bomRef: String) =
        library(name, "cc", bomRef = bomRef, group = group, version = version, purl = bomRef)

    /** A library with the bom-ref [bomRef] and, when given, the [purl]. */
    private fun packaged(bomRef: String, purl: String? = null): String {
        val purlField = purl?.let { """, "purl": "$it"""" }.orEmpty()
        return """{ "type": "library", "bom-ref": "$bomRef", "name": "package"$purlField }"""
    }

    /** The bom-ref of `logback-classic` at [version] and the component, like [slf4j]. */
    private fun logback(version: String): Pair<String, String> {
        val bomRef = "pkg:maven/ch.qos.logback/logback-classic@$version?type=jar"
        return bomRef to module("ch.qos.logback", "logback-classic", version, bomRef)
    }

    /** The bom-ref of `slf4j-api` at [version] and the component, in the shape [broker] and [partIdentifying] take. */
    private fun slf4j(version: String): Pair<String, String> {
        val bomRef = "pkg:maven/org.slf4j/slf4j-api@$version?type=jar"
        return bomRef to module("org.slf4j", "slf4j-api", version, bomRef)
    }

    /** The extension SDK at [version] under [bomRef]. */
    private fun sdk(version: String, bomRef: String) = module("com.hivemq", "hivemq-extension-sdk", version, bomRef)

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

    /**
     * The warning that the part [name] identifies [refs] that no host part describes, with the [remedy] for a merge
     * without host parts unless another is given.
     */
    private fun identifiedGap(name: String, vararg refs: String, remedy: String = withoutHosts(name)): String =
        "the SBOM of '$name' declares its dependency graph incomplete, and no host part describes " +
            refs.joinToString(", ") { "'$it'" } + " ($remedy)"

    /** The remedy of [identifiedGap] for [ref], whose package the host parts describe in no version or in several. */
    private fun notByHosts(ref: String) =
        "the host parts describe the package of '$ref' in no version or in several; $accept"

    /** The remedy of [identifiedGap] when the host part `broker` has no SBOM in the merge. */
    private fun missingHost() = "the host part 'broker' has no SBOM in this merge; $accept"

    /** The remedy of [identifiedGap] for the identified component `sdk-4.1.0`, which has no purl. */
    private fun noPackage() = "'sdk-4.1.0' has no package URL to match another version by; $accept"

    /** The remedy of [identifiedGap] for [releasedSdk], whose dependency entry in the identifying part has targets. */
    private fun ownDependencies() =
        "'$releasedSdk' has dependencies of its own, which another version cannot take over; $accept"

    /** Why the part [name] keeps the product open: it declares [refs] incomplete without labelling them. */
    private fun unlabelledGap(name: String, vararg refs: String) =
        "the SBOM of '$name' declares ${refs.joinToString(", ") { "'$it'" }} incomplete without the " +
            "${SbomEnricher.PROVIDED_BY_HOST} label, so the merge cannot tell a component its host provides from " +
            "one it ships (a part built before the plugin wrote that label needs a rebuild; a merged product loses " +
            "the label where one of its parts ships the component)"

    /** The remedy of [identifiedGap] for the part [name] in a merge without host parts. */
    private fun withoutHosts(name: String) =
        "to fill the gap, name the part that hosts '$name' in hostParts; to accept it, declare it in " +
            "declaredIdentifiedGapReasons"

    /** The compositions of the document, each as its aggregate and the refs its `dependencies` list. */
    private fun JsonNode.compositions(): List<Pair<String, List<String>>> =
        this["compositions"].map { it["aggregate"].asText() to it["dependencies"].map { ref -> ref.asText() } }

    private fun JsonNode.componentRefs(): List<String> = this["components"].map { it["bom-ref"].asText() }

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

    /** This part with [SbomEnricher.PROVIDED_BY_HOST] on the components [refs], as `generateSbom` labels them. */
    private fun SbomPart.labelling(vararg refs: String): SbomPart = edited { root ->
        for (component in root["components"].filter { it["bom-ref"]?.asText() in refs }) {
            val properties = (component as ObjectNode).withArray("properties")
            properties.addObject().put("name", SbomEnricher.PROVIDED_BY_HOST).put("value", "true")
        }
    }

    /** This part without [SbomEnricher.PROVIDED_BY_HOST] on any component, as a part from another tool comes. */
    private fun SbomPart.unlabelled(): SbomPart = edited { root ->
        for (component in root["components"]) {
            (component["properties"] as? ArrayNode)?.removeIf { it["name"].asText() == SbomEnricher.PROVIDED_BY_HOST }
        }
    }

    /** The refs the dependencies of the document name that are neither a component nor its top-level component. */
    private fun JsonNode.danglingRefs(): List<String> {
        val known = this["components"].map { it["bom-ref"].asText() } +
            this["metadata"]["component"]["bom-ref"].asText()
        return this["dependencies"].flatMap { entry -> listOf(entry["ref"]) + entry["dependsOn"].toList() }
            .map { it.asText() }
            .filterNot { it in known }
    }

    /** Whether the component [ref] of the document carries [SbomEnricher.PROVIDED_BY_HOST]. */
    private fun JsonNode.labelled(ref: String): Boolean =
        this["components"].first { it["bom-ref"].asText() == ref }["properties"]
            ?.any { it["name"].asText() == SbomEnricher.PROVIDED_BY_HOST } == true

    /** This part with the component [ref] naming [tool] as the tool of its evidence. */
    private fun SbomPart.withEvidence(ref: String, tool: String): SbomPart = edited { root ->
        val component = root["components"].first { it["bom-ref"].asText() == ref } as ObjectNode
        val identity = component.putObject("evidence").putArray("identity").addObject().put("field", "purl")
        identity.putArray("tools").add(tool)
    }

    /** The warning about the replaced component [ref] the product keeps, which another version replaces for dns. */
    private fun keptGap(ref: String) =
        "the product keeps '$ref', in whose place another version comes for 'dns', only because another component " +
            "refers to it, and states nothing about its dependencies (to accept it, declare it in " +
            "declaredIdentifiedGapReasons)"

    /** This part with the tool `tool` defined in `metadata.tools`, which the merge does not carry. */
    private fun SbomPart.withTool(): SbomPart = edited {
        val tool = (it["metadata"] as ObjectNode).putObject("tools").putArray("components").addObject()
        tool.put("type", "application").put("bom-ref", "tool").put("name", "tool")
    }

    /** This part with [compositions], the text of a JSON array, in place of the one `part` writes. */
    private fun SbomPart.withCompositions(compositions: String): SbomPart =
        edited { it.set<JsonNode>("compositions", mapper.readTree(compositions)) }

    /**
     * [name] depending on `lib` and on [identified], bom-refs to components, with the compositions the enricher writes
     * when it identifies those as components the host provides: complete but for them.
     */
    private fun partIdentifying(name: String, identified: Map<String, String>): SbomPart {
        val raw = part(
            name,
            (listOf(library("lib", "aa")) + identified.values).joinToString(", "),
            listOf(ref("lib")) + identified.keys,
            aggregate = null,
        )
        val details = SbomDetails(
            creatorName = product.creatorName,
            creator = product.creator,
            artifactFileName = null,
            componentFileNames = emptyMap(),
            concludedLicenses = emptyMap(),
            incompleteReasons = emptyList(),
            identifiedComponents = identified.keys,
        )
        return SbomPart(raw.source, mapper.writeValueAsBytes(SbomEnricher.enrich(BomJson.read(raw.bytes), details)))
    }

    /** [name] identifying `sdk`; see [partIdentifying]. */
    private fun partIdentifyingSdk(name: String = "alpha"): SbomPart =
        partIdentifying(name, mapOf(ref("sdk") to library("sdk", "bb")))

    /** The extension [name], which identifies the SDK it builds against, [releasedSdk]. */
    private fun extension(name: String = "dns"): SbomPart =
        partIdentifying(name, mapOf(releasedSdk to sdk("4.1.0", releasedSdk)))

    /** The part `broker`, which describes [components], bom-refs to components, and depends on each of them. */
    private fun broker(components: Map<String, String> = mapOf(compositeSdk to sdk("4.58.0-SNAPSHOT", compositeSdk))) =
        part("broker", components.values.joinToString(", "), components.keys.toList())

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
    fun `accepts identified components that conclude different licenses when the product leaves them out`() {
        fun sdkConcluding(id: String) = """
            {
              "type": "library", "bom-ref": "$releasedSdk", "group": "com.hivemq", "name": "hivemq-extension-sdk",
              "version": "4.1.0", "purl": "$releasedSdk", "licenses": [ ${licenseEntry(id)} ]
            }
        """.trimIndent()

        // the broker's SDK takes the place of both, so no conclusion of theirs reaches the product
        val merged = merge(
            partIdentifying("dns", mapOf(releasedSdk to sdkConcluding("Apache-2.0"))),
            partIdentifying("kafka", mapOf(releasedSdk to sdkConcluding("MIT"))),
            broker(),
            with = hosted,
        ).root

        assertThat(merged.componentRefs()).contains(compositeSdk).doesNotContain(releasedSdk)
    }

    @Test
    fun `accepts identified components with different hashes when the product leaves them out`() {
        fun sdkHashed(hash: String) =
            library("hivemq-extension-sdk", hash, bomRef = releasedSdk, group = "com.hivemq", version = "4.1.0")

        // two builds of the SDK under one bom-ref, neither of which the product ships
        val merged = merge(
            partIdentifying("dns", mapOf(releasedSdk to sdkHashed("aa"))),
            partIdentifying("kafka", mapOf(releasedSdk to sdkHashed("bb"))),
            broker(),
            with = hosted,
        ).root

        assertThat(merged.componentRefs()).contains(compositeSdk).doesNotContain(releasedSdk)
    }

    @Test
    fun `accepts parts that list the concluded licenses of a library in different orders`() {
        val merged = merge(
            part("alpha", components = libraryWithLicenses(licenseEntry("MIT"), licenseEntry("Apache-2.0"))),
            part("beta", components = libraryWithLicenses(licenseEntry("Apache-2.0"), licenseEntry("MIT"))),
        ).root

        assertThat(merged.component("lib")["licenses"].map { it["license"]["id"].asText() })
            .containsExactly("MIT", "Apache-2.0")
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
    fun `accepts two parts that list the same library with the same license bom-ref`() {
        val merged = merge(
            part("alpha", components = libraryWithLicenseRef("lib", "license-1")),
            part("beta", components = libraryWithLicenseRef("lib", "license-1")),
        ).root

        assertThat(merged.component("lib")["licenses"][0]["license"]["bom-ref"].asText()).isEqualTo("license-1")
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
    fun `drops the build-system reference of the top-level component of a part`() {
        val alpha = part("alpha").edited {
            val references = (it["metadata"]["component"] as ObjectNode).putArray("externalReferences")
            references.addObject().put("type", "build-system").put("url", "https://ci.example.com/run/1")
            references.addObject().put("type", "website").put("url", "https://example.com/alpha")
        }

        val merged = merge(alpha).root

        assertThat(merged.component("alpha")["externalReferences"].map { it["type"].asText() })
            .containsExactly("website")
    }

    @Test
    fun `drops the build-system reference that another part lists for the top-level component of a part`() {
        val url = "https://ci.example.com/run/1"
        val alpha = part("alpha").edited {
            val references = (it["metadata"]["component"] as ObjectNode).putArray("externalReferences")
            references.addObject().put("type", "build-system").put("url", url)
        }
        val listing = """
            {
              "type": "library", "bom-ref": "${primaryRef("alpha")}", "group": "org.example", "name": "alpha",
              "version": "1.0", "externalReferences": [ { "type": "build-system", "url": "$url" } ]
            }
        """.trimIndent()
        val beta = part("beta", components = listing, dependsOn = listOf(primaryRef("alpha")))

        val merged = merge(alpha, beta).root

        assertThat(merged.component("alpha").has("externalReferences")).isFalse()
    }

    @Test
    fun `drops the externalReferences of a top-level component that has only a build-system reference`() {
        val alpha = part("alpha").edited {
            val references = (it["metadata"]["component"] as ObjectNode).putArray("externalReferences")
            references.addObject().put("type", "build-system").put("url", "https://ci.example.com/run/1")
        }

        val merged = merge(alpha).root

        assertThat(merged.component("alpha").has("externalReferences")).isFalse()
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
    fun `keeps a website reference another part lists for a top-level component that has only a CI reference`() {
        val alpha = part("alpha").edited {
            val references = (it["metadata"]["component"] as ObjectNode).putArray("externalReferences")
            references.addObject().put("type", "build-system").put("url", "https://ci.example.com/run/1")
        }
        val listing = """
            {
              "type": "library", "bom-ref": "${primaryRef("alpha")}", "group": "org.example", "name": "alpha",
              "version": "1.0", "externalReferences": [ { "type": "website", "url": "https://example.org/alpha" } ]
            }
        """.trimIndent()
        val beta = part("beta", components = listing, dependsOn = listOf(primaryRef("alpha")))

        val merged = merge(alpha, beta).root

        // the CI reference goes before the union, so it cannot win the field over what beta lists
        assertThat(merged.component("alpha")["externalReferences"].map { it["type"].asText() })
            .containsExactly("website")
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
    fun `keeps the concluded licenses that licenseConflicts gives as an expression`() {
        val expression = "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0"
        val merged = merge(
            part("alpha", components = libraryWithLicenses(licenseEntry("EPL-2.0"))),
            part("beta", components = libraryWithLicenses(expressionEntry(expression))),
            with = product.copy(licenseConflicts = mapOf("org.example:lib" to expression)),
        ).root

        assertThat(merged.component("lib")["licenses"]).isEqualTo(mapper.readTree("[ ${expressionEntry(expression)} ]"))
    }

    @Test
    fun `keeps the concluded licenses that licenseConflicts gives as license ids separated by commas in any order`() {
        val merged = merge(
            part("alpha", components = libraryWithLicenses(licenseEntry("MIT"))),
            part("beta", components = libraryWithLicenses(licenseEntry("Apache-2.0"), licenseEntry("MIT"))),
            with = product.copy(licenseConflicts = mapOf("org.example:lib" to "MIT, Apache-2.0")),
        ).root

        assertThat(merged.component("lib")["licenses"].map { it["license"]["id"].asText() })
            .containsExactly("Apache-2.0", "MIT")
    }

    @Test
    fun `keeps the concluded licenses that licenseConflicts gives for a library whose parts conclude differently`() {
        val beta = libraryWithLicenses(licenseEntry("Apache-2.0", "declared"), licenseEntry("Apache-2.0"))
        val merged = merge(
            part("alpha", components = libraryWithLicenses(licenseEntry("MIT"))),
            part("beta", components = beta),
            with = product.copy(licenseConflicts = mapOf("org.example:lib" to "Apache-2.0")),
        ).root

        // the whole list of the part whose conclusion stands, its declared entries included
        assertThat(merged.component("lib")["licenses"]).isEqualTo(mapper.readTree(beta)["licenses"])
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
    fun `keeps the licenses of the part that sorts first when the parts declare different ones`() {
        val merged = merge(
            part("alpha", components = libraryWithLicenses(licenseEntry("MIT", "declared"))),
            part("beta", components = libraryWithLicenses(licenseEntry("Apache-2.0", "declared"))),
        ).root

        // only a conclusion is ours to vouch for, so declared entries that differ are no conflict
        assertThat(merged.component("lib")["licenses"])
            .isEqualTo(mapper.readTree("[ ${licenseEntry("MIT", "declared")} ]"))
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
    fun `matches a licenseConflicts expression only to an expression and license ids only to license objects`() {
        val expression = libraryWithLicenses(expressionEntry("Apache-2.0 AND MIT"))
        val objects = libraryWithLicenses(licenseEntry("Apache-2.0"), licenseEntry("MIT"))
        fun licensesFor(value: String) = merge(
            part("alpha", components = expression),
            part("beta", components = objects),
            with = product.copy(licenseConflicts = mapOf("org.example:lib" to value)),
        ).root.component("lib")["licenses"]

        assertThat(licensesFor("Apache-2.0 AND MIT")).isEqualTo(mapper.readTree(expression)["licenses"])
        assertThat(licensesFor("MIT, Apache-2.0")).isEqualTo(mapper.readTree(objects)["licenses"])
    }

    @Test
    fun `merges parts that differ only in the build-system reference to the same document`() {
        fun builtBy(run: Int) = part("alpha").edited {
            val references = (it["metadata"]["component"] as ObjectNode).putArray("externalReferences")
            references.addObject().put("type", "build-system").put("url", "https://ci.example.com/run/$run")
        }

        assertThat(merge(builtBy(1)).root.serialized()).isEqualTo(merge(builtBy(2)).root.serialized())
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
    fun `takes the licenses of a later part that concludes when the part that sorts first only declares them`() {
        val beta = libraryWithLicenses(licenseEntry("MIT", "declared"), licenseEntry("MIT"))
        val merged = merge(
            part("alpha", components = libraryWithLicenses(licenseEntry("MIT", "declared"))),
            part("beta", components = beta),
        ).root

        // the product states the conclusion one part made, whichever part wins the other fields
        assertThat(merged.component("lib")["licenses"]).isEqualTo(mapper.readTree(beta)["licenses"])
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
    fun `fails on a bom-ref that one part adds to a shared library and another component defines`() {
        // alpha's lib has no licenses, so the union gives it beta's, which define license-1 like alpha's other does
        val components = library("lib", "aa") + ", " + libraryWithLicenseRef("other", "license-1")
        val alpha = part("alpha", components = components, dependsOn = listOf(ref("lib"), ref("other")))
        val beta = part("beta", components = libraryWithLicenseRef("lib", "license-1"))

        assertThatThrownBy { merge(alpha, beta) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'beta.cdx.json' and 'alpha.cdx.json' define the bom-ref 'license-1' for different nodes; give each " +
                    "node its own"
            )
    }

    @Test
    fun `fails on a component that uses the bom-ref of the product`() {
        val clashing = library("lib", "aa", bomRef = "product@1.0.0")
        val beta = part("beta", components = clashing, dependsOn = listOf("product@1.0.0"))

        assertThatThrownBy { merge(part("alpha"), beta) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'beta.cdx.json' uses the bom-ref 'product@1.0.0' of the product; give the product another name or " +
                    "version"
            )
    }

    @Test
    fun `fails on a component with an empty bom-ref`() {
        val alpha = part("alpha", components = library("lib", "aa", bomRef = ""), dependsOn = emptyList())

        assertThatThrownBy { merge(alpha) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage("'alpha.cdx.json' has a component without a bom-ref; give every component one")
    }

    @Test
    fun `fails on a component with nested components`() {
        val nested = """[ { "type": "library", "bom-ref": "${ref("inner")}", "name": "inner", "version": "1.0" } ]"""

        // the graph names the nested component too, as a generator that nests components writes it
        val targets = listOf(ref("lib"), ref("inner"))
        val alpha = part("alpha", components = libraryWithNestedComponents(nested), dependsOn = targets)

        assertThatThrownBy { merge(alpha) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' has a component with nested components, which the merge does not flatten; list " +
                    "every component at the top level of the part's SBOM"
            )
    }

    @Test
    fun `fails on a component without a bom-ref`() {
        val component = """{ "type": "library", "group": "org.example", "name": "lib", "version": "1.0" }"""

        assertThatThrownBy { merge(part("alpha", components = component, dependsOn = emptyList())) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' has a component without a bom-ref; give every component one"
            )
    }

    @Test
    fun `fails on a declared identified gap that the product does not have`() {
        val declared = hosted.copy(declaredIdentifiedGapReasons = declaredGap)

        // a declaration that settles nothing would silence the warning about the next such gap
        assertThatThrownBy { merge(extension(), broker(), with = declared) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "declaredIdentifiedGapReasons declares a gap of identified components that no host part describes, " +
                    "but the product has none; remove the declaration"
            )
    }

    @Test
    fun `fails on a dependencies entry that references a tool defined in the metadata`() {
        val withEntry = part("alpha").withTool().edited {
            (it["dependencies"] as ArrayNode).addObject().put("ref", "tool").putArray("dependsOn")
        }

        assertThatThrownBy { merge(withEntry) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' has a dependency entry that references 'tool', which the merge does not carry; " +
                    "list 'tool' among the components"
            )
    }

    @Test
    fun `fails on a dependsOn target that references a tool defined in the metadata`() {
        val withTarget = part("alpha").withLibraryDependingOn("tool").withTool()

        assertThatThrownBy { merge(withTarget) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' has a dependency entry that references 'tool', which the merge does not carry; " +
                    "list 'tool' among the components"
            )
    }

    @Test
    fun `fails on a host part that is neither a part of the merge nor expected`() {
        val hosts = product.copy(expectedParts = setOf("agent"), hostParts = setOf("agent", "broker", "hivemq"))

        // a misspelt or stale name would quietly stop the host from filling any gap
        assertThatThrownBy { merge(extension(), broker(), with = hosts) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "hostParts names parts that are neither in the merge nor in expectedParts: 'hivemq'; correct or " +
                    "remove the names"
            )
    }

    @Test
    fun `fails on a library that lists an algorithm twice with different digests`() {
        val twice = libraryWithHashes("SHA-256" to "aa".repeat(32), "SHA-256" to "bb".repeat(32))

        assertThatThrownBy { merge(part("alpha", components = twice)) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' lists different SHA-256 digests for '${ref("lib")}'; list one digest per algorithm"
            )
    }

    @Test
    fun `fails on a library whose evidence references a tool defined in the metadata`() {
        val withEvidence = part("alpha").withTool().edited {
            val identity = (it["components"][0] as ObjectNode).putObject("evidence").putArray("identity")
            identity.addObject().put("field", "purl").putArray("tools").add(primaryRef("alpha")).add("tool")
        }

        assertThatThrownBy { merge(withEvidence) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' has evidence that references the tool 'tool', which the merge does not carry; list " +
                    "the tool among the components, or leave the reference out"
            )
    }

    @Test
    fun `fails on a library whose later part lists a different hash before the matching one`() {
        // beta lists the digest of alpha last but another before it, so beta is broken on its own, whatever alpha lists
        val different = libraryWithHashes("SHA-256" to "bb".repeat(32), "SHA-256" to "aa".repeat(32))

        assertThatThrownBy {
            merge(part("alpha", components = library("lib", "aa")), part("beta", components = different))
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'beta.cdx.json' lists different SHA-256 digests for '${ref("lib")}'; list one digest per algorithm"
            )
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
            .hasMessage(
                "'${ref("lib")}' has different hashes in 'alpha.cdx.json' and 'beta.cdx.json'; the parts ship " +
                    "different files under one bom-ref, so build them from the same artifact"
            )
    }

    @Test
    fun `fails on a library whose parts conclude an expression and one of its licenses`() {
        assertThatThrownBy {
            merge(
                part("alpha", components = libraryWithLicenses(expressionEntry("Apache-2.0 OR MIT"))),
                part("beta", components = libraryWithLicenses(licenseEntry("MIT"))),
            )
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessageStartingWith(
                "'${ref("lib")}' has different concluded licenses in 'alpha.cdx.json' (the expression Apache-2.0 OR " +
                    "MIT) and 'beta.cdx.json' (the license MIT); "
            )
    }

    @Test
    fun `fails on a library whose parts conclude an expression and the license objects it names`() {
        assertThatThrownBy {
            merge(
                part("alpha", components = libraryWithLicenses(expressionEntry("Apache-2.0 AND MIT"))),
                part("beta", components = libraryWithLicenses(licenseEntry("Apache-2.0"), licenseEntry("MIT"))),
            )
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessageStartingWith(
                "'${ref("lib")}' has different concluded licenses in 'alpha.cdx.json' (the expression Apache-2.0 " +
                    "AND MIT) and 'beta.cdx.json' (the licenses Apache-2.0, MIT); "
            )
    }

    @Test
    fun `fails on a library whose parts conclude different licenses`() {
        assertThatThrownBy {
            merge(
                part("alpha", components = libraryWithLicenses(licenseEntry("MIT"))),
                part("beta", components = libraryWithLicenses(licenseEntry("MIT"))),
                part("gamma", components = libraryWithLicenses(licenseEntry("Apache-2.0"), licenseEntry("MIT"))),
            )
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'${ref("lib")}' has different concluded licenses in 'alpha.cdx.json' (the license MIT) and " +
                    "'gamma.cdx.json' (the licenses Apache-2.0, MIT); build the parts with the same license " +
                    "settings, or put the conclusion the product states into licenseConflicts under " +
                    "'org.example:lib': an expression as its text, license objects as their ids separated by " +
                    "commas in any order"
            )
    }

    @Test
    fun `fails on a library with different hashes in two parts`() {
        assertThatThrownBy {
            merge(part("alpha", components = library("lib", "aa")), part("beta", components = library("lib", "bb")))
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'${ref("lib")}' has different hashes in 'alpha.cdx.json' and 'beta.cdx.json'; the parts ship " +
                    "different files under one bom-ref, so build them from the same artifact"
            )
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
            .hasMessage(
                "'${ref("lib")}' has different hashes in 'beta.cdx.json' and 'gamma.cdx.json'; the parts ship " +
                    "different files under one bom-ref, so build them from the same artifact"
            )
    }

    @Test
    fun `fails on a license that uses the bom-ref of the product`() {
        val alpha = part("alpha", components = libraryWithLicenseRef("lib", "product@1.0.0"))

        assertThatThrownBy { merge(alpha) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' uses the bom-ref 'product@1.0.0' of the product; give the product another name or " +
                    "version"
            )
    }

    @Test
    fun `fails on a licenseConflicts entry for a module whose parts conclude the same license`() {
        val conflicts = mapOf("org.example:lib" to "MIT", "org.example:gone" to "MIT")

        assertThatThrownBy {
            merge(
                part("alpha", components = libraryWithLicenses(licenseEntry("MIT"))),
                part("beta", components = libraryWithLicenses(licenseEntry("MIT"))),
                with = product.copy(licenseConflicts = conflicts),
            )
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "licenseConflicts names modules whose parts do not conclude different licenses: " +
                    "'org.example:gone', 'org.example:lib'; remove those entries"
            )
    }

    @Test
    fun `fails on a licenseConflicts entry that no conclusion of the parts matches`() {
        assertThatThrownBy {
            merge(
                part("alpha", components = libraryWithLicenses(licenseEntry("MIT"))),
                part("beta", components = libraryWithLicenses(licenseEntry("Apache-2.0"))),
                with = product.copy(licenseConflicts = mapOf("org.example:lib" to "BSD-3-Clause")),
            )
        }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "licenseConflicts gives 'BSD-3-Clause' for 'org.example:lib', but the parts conclude the license " +
                    "MIT in 'alpha.cdx.json', the license Apache-2.0 in 'beta.cdx.json' for '${ref("lib")}'; give " +
                    "one of those: an expression as its text, license objects as their ids separated by commas in " +
                    "any order"
            )
    }

    @Test
    fun `fails on a part before the minimum spec version`() {
        assertThatThrownBy { merge(part("alpha", specVersion = "1.5")) }
            .isInstanceOf(SbomException::class.java)
            .hasMessageStartingWith("'alpha.cdx.json': The BOM declares CycloneDX specVersion 1.5")
    }

    @Test
    fun `fails on a part that defines one bom-ref below two of its components`() {
        val components = libraryWithLicenseRef("lib", "license-1") + ", " + libraryWithLicenseRef("other", "license-1")
        val alpha = part("alpha", components = components, dependsOn = listOf(ref("lib"), ref("other")))

        assertThatThrownBy { merge(alpha) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' defines the bom-ref 'license-1' twice; give each node its own"
            )
    }

    @Test
    fun `fails on a part that has a dependency entry with provides`() {
        val withProvides = part("alpha").edited {
            (it["dependencies"][0] as ObjectNode).putArray("provides").add(ref("lib"))
        }

        assertThatThrownBy { merge(withProvides) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' has a dependency entry with provides, which the merge does not carry; leave " +
                    "provides out of the part's SBOM"
            )
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
            .hasMessage(
                "'alpha.cdx.json' has formulation, which the merge does not carry; leave them out of the part's SBOM"
            )
    }

    @Test
    fun `fails on a part that has services`() {
        val withServices = part("alpha").edited {
            it.putArray("services").addObject().put("bom-ref", "api").put("name", "api")
        }

        assertThatThrownBy { merge(withServices) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' has services, which the merge does not carry; leave them out of the part's SBOM"
            )
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
            .hasMessage("'alpha.cdx.json': The BOM's bomFormat is not text, but has to be CycloneDX")
    }

    @Test
    fun `fails on a part whose top-level component has an empty bom-ref`() {
        val alpha = part("alpha").edited { (it["metadata"]["component"] as ObjectNode).put("bom-ref", "") }

        assertThatThrownBy { merge(alpha) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' has no top-level component with a bom-ref and a name; give its metadata.component " +
                    "both, as the merge names a part by that component"
            )
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
            .hasMessage(
                "'headless.cdx.json' has no top-level component with a bom-ref and a name; give its " +
                    "metadata.component both, as the merge names a part by that component"
            )
    }

    @Test
    fun `fails on a pedigree component whose bom-ref is the bom-ref of a component of another part`() {
        val alpha = part("alpha").edited {
            val ancestors = (it["components"][0] as ObjectNode).putObject("pedigree").putArray("ancestors")
            ancestors.addObject().put("type", "library").put("bom-ref", ref("other")).put("name", "other")
        }
        val beta = part("beta", components = library("other", "bb"), dependsOn = listOf(ref("other")))

        assertThatThrownBy { merge(alpha, beta) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'beta.cdx.json' and 'alpha.cdx.json' define the bom-ref '${ref("other")}' for different nodes; give " +
                    "each node its own"
            )
    }

    @Test
    fun `fails on a pedigree component whose evidence references a tool defined in the metadata`() {
        val withEvidence = part("alpha").withTool().edited {
            val ancestors = (it["components"][0] as ObjectNode).putObject("pedigree").putArray("ancestors")
            val ancestor = ancestors.addObject().put("type", "library").put("bom-ref", "ancestor").put("name", "lib")
            val identity = ancestor.putObject("evidence").putArray("identity")
            identity.addObject().put("field", "purl").putArray("tools").add("tool")
        }

        assertThatThrownBy { merge(withEvidence) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' has evidence that references the tool 'tool', which the merge does not carry; list " +
                    "the tool among the components, or leave the reference out"
            )
    }

    @Test
    fun `fails on a top-level component that uses the bom-ref of the product`() {
        val beta = part("beta")
        val bytes = String(beta.bytes).replace(primaryRef("beta"), "product@1.0.0").toByteArray()

        assertThatThrownBy { merge(part("alpha"), SbomPart(beta.source, bytes)) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'beta.cdx.json' uses the bom-ref 'product@1.0.0' of the product; give the product another name or " +
                    "version"
            )
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
            .hasMessage(
                "'alpha.cdx.json' has evidence that references the tool 'tool', which the merge does not carry; list " +
                    "the tool among the components, or leave the reference out"
            )
    }

    @Test
    fun `fails on a top-level component with nested components`() {
        val alpha = part("alpha")
        val nested = """"components": [ { "type": "library", "bom-ref": "inner", "name": "inner" } ],"""
        // only the top-level component is named alpha
        val bytes = String(alpha.bytes).replace(""""name": "alpha", """, """"name": "alpha", $nested """).toByteArray()

        assertThatThrownBy { merge(SbomPart(alpha.source, bytes)) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' has a component with nested components, which the merge does not flatten; list " +
                    "every component at the top level of the part's SBOM"
            )
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
            .hasMessage(
                "'${ref("lib")}' names different components in 'alpha.cdx.json' and 'beta.cdx.json'; give the " +
                    "components bom-refs of their own, such as their purls"
            )
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
            .hasMessage(
                "'${ref("lib")}' names different components in 'alpha.cdx.json' and 'beta.cdx.json'; give the " +
                    "components bom-refs of their own, such as their purls"
            )
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
            .hasMessage(
                "'${ref("lib")}' names different components in 'alpha.cdx.json' and 'beta.cdx.json'; give the " +
                    "components bom-refs of their own, such as their purls"
            )
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
            .hasMessage(
                "'${ref("lib")}' names different components in 'alpha.cdx.json' and 'beta.cdx.json'; give the " +
                    "components bom-refs of their own, such as their purls"
            )
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
            .hasMessage(
                "'${ref("lib")}' names different components in 'beta.cdx.json' and 'gamma.cdx.json'; give the " +
                    "components bom-refs of their own, such as their purls"
            )
    }

    @Test
    fun `fails on two parts that define one bom-ref below different components`() {
        val alpha = part("alpha", components = libraryWithLicenseRef("lib", "license-1"))
        val other = libraryWithLicenseRef("other", "license-1")
        val beta = part("beta", components = other, dependsOn = listOf(ref("other")))

        assertThatThrownBy { merge(alpha, beta) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' and 'beta.cdx.json' define the bom-ref 'license-1' for different nodes; give each " +
                    "node its own"
            )
    }

    @Test
    fun `fails on two parts with the same top-level component`() {
        assertThatThrownBy { merge(part("alpha"), SbomPart("copy.cdx.json", part("alpha").bytes)) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' and 'copy.cdx.json' describe the same part " +
                    "'pkg:maven/org.example/alpha@1.0?project_path=%3A'; pass one SBOM per part"
            )
    }

    @Test
    fun `fails on two parts with the same top-level component whatever the order of the parts`() {
        assertThatThrownBy { merge(SbomPart("copy.cdx.json", part("alpha").bytes), part("alpha")) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "'alpha.cdx.json' and 'copy.cdx.json' describe the same part " +
                    "'pkg:maven/org.example/alpha@1.0?project_path=%3A'; pass one SBOM per part"
            )
    }

    @Test
    fun `rejects a creator that is neither an email address nor an http URL`() {
        assertThatThrownBy { merge(part("alpha"), with = product.copy(creator = "HiveMQ Security Team")) }
            .isInstanceOf(SbomException::class.java)
            .hasMessage(
                "The creator, hivemqLicense.sbomCreator or the creator of the task, must be an email address or " +
                    "an http(s) URL, but is 'HiveMQ Security Team'"
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
    fun `declares the product complete but for an identified component a host part lists without an entry`() {
        val extension = partIdentifyingSdk()
        // the broker's composition names the SDK, but its graph has no entry for it, so it states nothing about it
        val broker = part("broker").withCompositions(
            """[ { "aggregate": "complete", "dependencies": """ +
                """[ "${primaryRef("broker")}", "${ref("lib")}", "${ref("sdk")}" ] } ]"""
        )

        val merged = merge(extension, broker, with = hosted)

        assertThat(merged.incompleteReasons).containsExactly(
            identifiedGap("alpha", ref("sdk"), remedy = notByHosts(ref("sdk"))),
        )
        assertThat(merged.root.compositions()).containsExactly(
            "complete" to listOf(primaryRef("alpha"), primaryRef("broker"), ref("lib"), "product@1.0.0"),
            "incomplete" to listOf(ref("sdk")),
        )
    }

    @Test
    fun `declares the product complete but for the identified components that no host part describes`() {
        // an image that ships extensions without the broker they run on, so nothing describes the SDK
        val merged = merge(extension("dns"), extension("kafka"))

        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'dns' declares its dependency graph incomplete, and no host part describes '$releasedSdk' " +
                "(to fill the gap, name the part that hosts 'dns' in hostParts; to accept it, declare it in " +
                "declaredIdentifiedGapReasons)",
            identifiedGap("kafka", releasedSdk),
        )
        assertThat(merged.root.compositions()).containsExactly(
            "complete" to listOf(primaryRef("dns"), primaryRef("kafka"), ref("lib"), "product@1.0.0"),
            "incomplete" to listOf(releasedSdk),
        )
    }

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
    fun `declares the product complete when a host part describes another version of a component a part identifies`() {
        val merged = merge(extension(), broker(), with = hosted)

        assertThat(merged.incompleteReasons).isEmpty()
        assertThat(merged.root.compositions()).containsExactly(
            "complete" to listOf(compositeSdk, primaryRef("broker"), primaryRef("dns"), ref("lib"), "product@1.0.0"),
        )
    }

    @Test
    fun `declares the product complete when a host part describes other versions of all identified components`() {
        // what a host supplies to an extension such as kafka, each as the extension builds against it and as it ships
        val provided = listOf(
            listOf("com.hivemq", "hivemq-extension-sdk", "4.1.0", "4.58.0-SNAPSHOT"),
            listOf("org.slf4j", "slf4j-api", "2.0.9", "2.0.16"),
            listOf("ch.qos.logback", "logback-classic", "1.4.14", "1.5.6"),
            listOf("io.opentelemetry", "opentelemetry-api", "1.31.0", "1.40.0"),
            listOf("jakarta.ws.rs", "jakarta.ws.rs-api", "3.1.0", "4.0.0"),
            listOf("net.logstash.logback", "logstash-logback-encoder", "7.3", "7.4"),
        )
        // each module in the version at that index of its list, by bom-ref
        fun modules(index: Int) = provided.associate { coordinates ->
            val (group, name) = coordinates
            val ref = "pkg:maven/$group/$name@${coordinates[index]}?type=jar"
            ref to module(group, name, coordinates[index], ref)
        }

        val merged = merge(partIdentifying("kafka", modules(2)), broker(modules(3)), with = hosted)

        assertThat(merged.incompleteReasons).isEmpty()
        assertThat(merged.root.compositions().map { it.first }).containsExactly("complete")
        assertThat(merged.root.dependsOn(primaryRef("kafka")))
            .containsExactlyInAnyOrderElementsOf(modules(3).keys + ref("lib"))
        assertThat(merged.root.componentRefs()).doesNotContainAnyElementsOf(modules(2).keys)
    }

    @Test
    fun `declares the product complete when a host part describes the components a part declares incomplete`() {
        // an extension built against the SDK the broker provides: identified, so its own SBOM leaves its graph open
        val extension = partIdentifyingSdk()
        val broker = part("broker", library("sdk", "bb"), listOf(ref("sdk")))

        val merged = merge(extension, broker, with = hosted)

        // the broker's SBOM states what the SDK depends on, so the product's graph is complete
        assertThat(merged.incompleteReasons).isEmpty()
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("complete")
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
        val merged = merge(part("alpha")).root
        val productSbom = SbomPart("product.cdx.json", mapper.writeValueAsBytes(merged))

        val bundle = merge(productSbom, with = product.copy(name = "bundle"))

        assertThat(bundle.incompleteReasons).isEmpty()
        assertThat(bundle.root["compositions"][0]["aggregate"].asText()).isEqualTo("complete")
    }

    @Test
    fun `declares the product complete when it is merged again with a host part that describes what it identifies`() {
        // an image of extensions, complete but for the SDK, then bundled with a broker that ships another version
        val image = SbomPart("image.cdx.json", mapper.writeValueAsBytes(merge(extension()).root))

        val bundle = merge(image, broker(), with = hosted.copy(name = "bundle"))

        assertThat(bundle.incompleteReasons).isEmpty()
        assertThat(bundle.root.compositions().map { it.first }).containsExactly("complete")
        assertThat(bundle.root.componentRefs()).contains(compositeSdk).doesNotContain(releasedSdk)
        assertThat(bundle.root.dependsOn(primaryRef("dns"))).containsExactly(compositeSdk, ref("lib"))
    }

    @Test
    fun `declares the product complete when the host part has a composition without an aggregate`() {
        val extension = partIdentifyingSdk()
        // an entry without an aggregate says nothing either way, for the broker's own verdict and for what it describes
        val broker = part("broker", library("sdk", "bb"), listOf(ref("sdk"))).withCompositions(
            """
            [
              { "aggregate": "complete", "dependencies": [ "${primaryRef("broker")}", "${ref("sdk")}" ] },
              { "dependencies": [ "${ref("sdk")}" ] }
            ]
            """.trimIndent()
        )

        val merged = merge(extension, broker, with = hosted)

        assertThat(merged.incompleteReasons).isEmpty()
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("complete")
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
    fun `declares the product incomplete when a complete composition covers a dependency target without an entry`() {
        val merged = merge(partCoveringTargetWithoutEntry())

        // the part states nothing about what lib depends on, so its complete entry cannot speak for lib
        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares only part of its dependency graph complete",
        )
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `declares the product incomplete when a complete composition covers a top-level component without an entry`() {
        val alpha = part("alpha").edited { root ->
            (root["dependencies"] as ArrayNode).removeIf { it["ref"].asText() == primaryRef("alpha") }
        }

        val merged = merge(alpha)

        // the part's composition names alpha, but the graph states nothing about what alpha depends on
        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares only part of its dependency graph complete",
        )
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
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
    fun `declares the product incomplete when a composition of a part has the aggregate unknown`() {
        val alpha = part("alpha", library("lib", "aa") + ", " + library("sdk", "bb"), listOf(ref("lib"), ref("sdk")))
            .withCompositions(
                """
                [
                  { "aggregate": "complete", "dependencies": [ "${primaryRef("alpha")}", "${ref("lib")}" ] },
                  { "aggregate": "unknown", "dependencies": [ "${ref("sdk")}" ] }
                ]
                """.trimIndent()
            )
        val broker = part("broker", library("sdk", "bb"), listOf(ref("sdk")))

        val merged = merge(alpha, broker, with = hosted)

        // only an incomplete entry can be filled by another part; unknown says less than that
        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares its dependency graph incomplete",
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
    fun `declares the product incomplete when a part declares a component outside its graph incomplete`() {
        val ghost = "pkg:maven/org.example/ghost@1.0?type=jar"
        val alpha = part("alpha").withCompositions(
            """
            [
              { "aggregate": "complete", "dependencies": [ "${primaryRef("alpha")}", "${ref("lib")}" ] },
              { "aggregate": "incomplete", "dependencies": [ "$ghost" ] }
            ]
            """.trimIndent()
        )

        val merged = merge(alpha)

        // like one over assemblies alone, an incomplete entry over anything but its graph leaves the part open
        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares its dependency graph incomplete",
        )
        assertThat(merged.root.compositions().map { it.first }).containsExactly("incomplete")
    }

    @Test
    fun `declares the product incomplete when a part declares its own top-level component incomplete`() {
        // a reported gap of alpha itself: the entry names alpha, which the broker describing the rest cannot fill
        val primary = primaryRef("alpha")
        val alpha = part("alpha", library("lib", "aa") + ", " + library("sdk", "bb"), listOf(ref("lib"), ref("sdk")))
            .withCompositions(
                """[ { "aggregate": "incomplete", "dependencies": """ +
                    """[ "$primary", "${ref("lib")}", "${ref("sdk")}" ] } ]"""
            )
        // the broker even describes alpha, as a host that bundles the extension would
        val broker = part(
            "broker",
            library("lib", "aa") + ", " + library("sdk", "bb") + ", " + library("alpha", "cc", bomRef = primary),
            listOf(ref("lib"), ref("sdk"), primary),
        )

        val merged = merge(alpha, broker, with = hosted)

        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares its dependency graph incomplete",
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
              { "aggregate": "incomplete", "dependencies": [ "${ref("lib")}" ] }
            ]
            """.trimIndent()
        )

        val merged = merge(alpha)

        // alpha contradicts itself about lib, so lib is no identified component another part could describe
        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares its dependency graph incomplete",
        )
        assertThat(merged.root.compositions().map { it.first }).containsExactly("incomplete")
    }

    @Test
    fun `declares the product incomplete when a part identifies components and covers only part of its graph`() {
        // lib loses its dependency entry, so the complete composition can no longer speak for it
        val alpha = partIdentifyingSdk()
            .edited { root -> (root["dependencies"] as ArrayNode).removeIf { it["ref"].asText() == ref("lib") } }

        val merged = merge(alpha)

        assertThat(merged.incompleteReasons).containsExactly(
            identifiedGap("alpha", ref("sdk")),
            "the SBOM of 'alpha' declares only part of its dependency graph complete",
        )
        // a gap besides the identified components leaves the whole graph open
        assertThat(merged.root.compositions().map { it.first }).containsExactly("incomplete")
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
    fun `declares the product incomplete when an expected host part has no SBOM`() {
        val merged = merge(extension(), with = hosted.copy(expectedParts = setOf("dns", "broker")))

        // a missing broker warns like any missing part, whether or not it is a host
        assertThat(merged.incompleteReasons).containsExactly(
            identifiedGap("dns", releasedSdk, remedy = missingHost()),
            "no SBOM for part 'broker'",
        )
        assertThat(merged.root.compositions().map { it.first }).containsExactly("incomplete")
    }

    @Test
    fun `declares the product incomplete when an expected part has no SBOM`() {
        val merged = merge(part("alpha"), with = product.copy(expectedParts = setOf("alpha", "tool", "broker")))

        assertThat(merged.incompleteReasons).containsExactly("no SBOM for part 'broker'", "no SBOM for part 'tool'")
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `declares the product incomplete when an incomplete composition lists no dependencies`() {
        val primary = primaryRef("alpha")
        val alpha = part("alpha").withCompositions(
            """
            [
              { "aggregate": "complete", "dependencies": [ "$primary", "${ref("lib")}" ] },
              { "aggregate": "incomplete", "assemblies": [ "$primary" ] }
            ]
            """.trimIndent()
        )

        val merged = merge(alpha)

        // an incomplete entry about nothing in the graph still says the part is incomplete
        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares its dependency graph incomplete",
        )
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

    @Test
    fun `declares the product incomplete when the host part has an unknown composition over the component`() {
        val extension = partIdentifyingSdk()
        val broker = part("broker", library("sdk", "bb"), listOf(ref("sdk"))).withCompositions(
            """
            [
              { "aggregate": "complete", "dependencies": [ "${primaryRef("broker")}", "${ref("sdk")}" ] },
              { "aggregate": "unknown", "dependencies": [ "${ref("sdk")}" ] }
            ]
            """.trimIndent()
        )

        val merged = merge(extension, broker, with = hosted)

        // the broker contradicts itself about the SDK, so it describes it for no other part, whatever its own verdict
        assertThat(merged.incompleteReasons).containsExactly(
            identifiedGap("alpha", ref("sdk"), remedy = notByHosts(ref("sdk"))),
            "the SBOM of 'broker' declares its dependency graph incomplete",
        )
        assertThat(merged.root["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `declares the product incomplete when two parts each declare a component both complete and incomplete`() {
        fun contradicting(name: String) = part(name).withCompositions(
            """
            [
              { "aggregate": "complete", "dependencies": [ "${primaryRef(name)}", "${ref("lib")}" ] },
              { "aggregate": "incomplete", "dependencies": [ "${ref("lib")}" ] }
            ]
            """.trimIndent()
        )

        val merged = merge(contradicting("alpha"), contradicting("beta"))

        // a part that leaves lib open does not describe it, whatever else it says, so neither fills the other's gap
        assertThat(merged.incompleteReasons).containsExactly(
            "the SBOM of 'alpha' declares its dependency graph incomplete",
            "the SBOM of 'beta' declares its dependency graph incomplete",
        )
        assertThat(merged.root.compositions().map { it.first }).containsExactly("incomplete")
    }

    @Test
    fun `drops a chain of replaced components that only refer to each other`() {
        // slf4j-api names logback-classic, which sorts first, so a single pass would keep it for a component it drops
        val dns = partIdentifying("dns", mapOf(slf4j("2.0.9"), logback("1.4.14"))).withEvidence(
            slf4j("2.0.9").first,
            logback("1.4.14").first,
        )

        val merged = merge(dns, broker(mapOf(slf4j("2.0.16"), logback("1.5.6"))), with = hosted)

        assertThat(merged.root.componentRefs()).doesNotContain(slf4j("2.0.9").first, logback("1.4.14").first)
        assertThat(merged.incompleteReasons).isEmpty()
        assertThat(merged.root.compositions().map { it.first }).containsExactly("complete")
    }

    @Test
    fun `drops a cycle of replaced components that refer to each other`() {
        val dns = partIdentifying("dns", mapOf(slf4j("2.0.9"), logback("1.4.14")))
            .withEvidence(slf4j("2.0.9").first, logback("1.4.14").first)
            .withEvidence(logback("1.4.14").first, slf4j("2.0.9").first)

        val merged = merge(dns, broker(mapOf(slf4j("2.0.16"), logback("1.5.6"))), with = hosted)

        assertThat(merged.root.componentRefs()).doesNotContain(slf4j("2.0.9").first, logback("1.4.14").first)
        assertThat(merged.incompleteReasons).isEmpty()
        assertThat(merged.root.compositions().map { it.first }).containsExactly("complete")
    }

    @Test
    fun `keeps a chain of replaced components when a component that stays refers to its head`() {
        val dns = partIdentifying("dns", mapOf(slf4j("2.0.9"), logback("1.4.14")))
            .withEvidence(ref("lib"), slf4j("2.0.9").first)
            .withEvidence(slf4j("2.0.9").first, logback("1.4.14").first)

        val merged = merge(dns, broker(mapOf(slf4j("2.0.16"), logback("1.5.6"))), with = hosted)

        // lib stays and names slf4j-api, which names logback-classic in turn, so every reference leads somewhere
        assertThat(merged.root.componentRefs()).contains(slf4j("2.0.9").first, logback("1.4.14").first)
        assertThat(merged.incompleteReasons)
            .containsExactly(keptGap(logback("1.4.14").first), keptGap(slf4j("2.0.9").first))
        assertThat(merged.root.compositions().last())
            .isEqualTo("incomplete" to listOf(logback("1.4.14").first, slf4j("2.0.9").first))
    }

    @Test
    fun `keeps a component a part declares complete and incomplete when a host part describes another version`() {
        val primary = primaryRef("alpha")
        // alpha ships lib and contradicts itself about it, so lib is no identified component another version replaces
        val alpha = part("alpha").withCompositions(
            """
            [
              { "aggregate": "complete", "dependencies": [ "$primary", "${ref("lib")}" ] },
              { "aggregate": "incomplete", "dependencies": [ "${ref("lib")}" ] }
            ]
            """.trimIndent()
        )
        val newer = "pkg:maven/org.example/lib@2.0?type=jar"

        val merged = merge(
            alpha,
            part("gamma", module("org.example", "lib", "2.0", newer), listOf(newer)),
            with = product.copy(hostParts = setOf("gamma")),
        )

        assertThat(merged.root.componentRefs()).contains(ref("lib"), newer)
        assertThat(merged.root.dependsOn(primary)).containsExactly(ref("lib"))
        assertThat(merged.incompleteReasons)
            .containsExactly("the SBOM of 'alpha' declares its dependency graph incomplete")
        assertThat(merged.root.compositions().map { it.first }).containsExactly("incomplete")
    }

    @Test
    fun `keeps a replaced component that the version taking its place depends on in another part`() {
        val (provided, old) = slf4j("2.0.9")
        val (shipped, new) = slf4j("2.0.16")
        // kafka bundles the broker's version together with the one dns identifies, which the first depends on
        val kafka = part("kafka", "$old, $new", listOf(provided, shipped)).edited { root ->
            val entry = (root["dependencies"] as ArrayNode).first { it["ref"].asText() == shipped } as ObjectNode
            entry.putArray("dependsOn").add(provided)
        }

        val merged = merge(
            partIdentifying("dns", mapOf(provided to old)),
            kafka,
            broker(mapOf(shipped to new)),
            with = hosted,
        ).root

        // kafka still names it, so it stays, and no edge of the product leads nowhere
        assertThat(merged.dependsOn(primaryRef("dns"))).containsExactly(ref("lib"), shipped)
        assertThat(merged.dependsOn(shipped)).containsExactly(provided)
        assertThat(merged.componentRefs()).contains(provided)
        assertThat(merged.danglingRefs()).isEmpty()
    }

    @Test
    fun `keeps an identified component another version replaces while the evidence of another component names it`() {
        val dns = extension().edited { root ->
            val lib = (root["components"] as ArrayNode).first { it["name"].asText() == "lib" } as ObjectNode
            val identity = lib.putObject("evidence").putArray("identity").addObject().put("field", "purl")
            identity.putArray("tools").add(releasedSdk)
        }

        val result = merge(dns, broker(), with = hosted)

        // a reference the product keeps has to lead somewhere, and what it leads to stays marked open
        val merged = result.root
        assertThat(merged.componentRefs()).contains(releasedSdk, compositeSdk)
        assertThat(merged.dependsOn(primaryRef("dns"))).containsExactly(compositeSdk, ref("lib"))
        assertThat(merged.compositions().last()).isEqualTo("incomplete" to listOf(releasedSdk))
        assertThat(result.incompleteReasons).containsExactly(
            "the product keeps '$releasedSdk', in whose place another version comes for 'dns', only because " +
                "another component refers to it, and states nothing about its dependencies (to accept it, declare " +
                "it in declaredIdentifiedGapReasons)",
        )
    }

    @Test
    fun `keeps an identified component that one part replaces while another part still depends on it`() {
        // kafka reports a gap of its own, which covers the SDK as well, so it identifies nothing
        val kafka = extension("kafka").withCompositions(
            """[ { "aggregate": "incomplete", "dependencies": """ +
                """[ "${primaryRef("kafka")}", "${ref("lib")}", "$releasedSdk" ] } ]"""
        )

        val merged = merge(extension("dns"), kafka, broker(), with = hosted).root

        assertThat(merged.componentRefs()).contains(releasedSdk, compositeSdk)
        assertThat(merged.dependsOn(primaryRef("dns"))).containsExactly(compositeSdk, ref("lib"))
        assertThat(merged.dependsOn(primaryRef("kafka"))).containsExactly(releasedSdk, ref("lib"))
    }

    @Test
    fun `keeps the gap of a component a host part identifies when a part it hosts describes it`() {
        val (shared, component) = slf4j("2.0.16")

        // the broker identifies slf4j-api from its own host, which no part of the merge is
        val merged = merge(
            partIdentifying("broker", mapOf(shared to component)),
            part("dns", component, listOf(shared)),
            with = hosted,
        )

        assertThat(merged.incompleteReasons).containsExactly(identifiedGap("broker", shared, remedy = ofHost))
        assertThat(merged.root.compositions().last()).isEqualTo("incomplete" to listOf(shared))
    }

    @Test
    fun `keeps the gap of a component a host part identifies when another host part describes another version`() {
        val (provided, _) = slf4j("2.0.9")
        val (bundled, component) = slf4j("2.0.16")

        // what the agent bundles runs in the agent's classloader, which the broker does not see either
        val merged = merge(
            partIdentifying("broker", mapOf(slf4j("2.0.9"))),
            part("agent", component, listOf(bundled)),
            with = product.copy(hostParts = setOf("agent", "broker")),
        )

        assertThat(merged.incompleteReasons).containsExactly(identifiedGap("broker", provided, remedy = ofHost))
        assertThat(merged.root.dependsOn(primaryRef("broker"))).containsExactly(ref("lib"), provided)
    }

    @Test
    fun `keeps the gap of an identified component that the identifying part states dependencies of`() {
        // a part from another tool that gives an identified component a graph of its own
        val dns = extension().edited { root ->
            val entry = (root["dependencies"] as ArrayNode).first { it["ref"].asText() == releasedSdk } as ObjectNode
            entry.putArray("dependsOn").add(ref("lib"))
        }

        val merged = merge(dns, broker(), with = hosted)

        assertThat(merged.incompleteReasons)
            .containsExactly(identifiedGap("dns", releasedSdk, remedy = ownDependencies()))
        assertThat(merged.root.dependsOn(primaryRef("dns"))).containsExactly(releasedSdk, ref("lib"))
        assertThat(merged.root.dependsOn(releasedSdk)).containsExactly(ref("lib"))
    }

    @Test
    fun `keeps the gap of an identified component when a host part describes its name in another group`() {
        val forked = "pkg:maven/org.example/hivemq-extension-sdk@4.58.0?type=jar"

        val merged = merge(
            extension(),
            broker(mapOf(forked to module("org.example", "hivemq-extension-sdk", "4.58.0", forked))),
            with = hosted,
        )

        assertThat(merged.incompleteReasons).containsExactly(
            identifiedGap("dns", releasedSdk, remedy = notByHosts(releasedSdk)),
        )
        assertThat(merged.root.dependsOn(primaryRef("dns"))).containsExactly(releasedSdk, ref("lib"))
    }

    @Test
    fun `keeps the gap of an identified component when host parts describe several other versions of it`() {
        val older = "pkg:maven/com.hivemq/hivemq-extension-sdk@4.57.0?type=jar"

        // the merge cannot tell which of the two the extension runs on
        val merged = merge(
            extension(),
            broker(),
            part("swarm", sdk("4.57.0", older), listOf(older)),
            with = product.copy(hostParts = setOf("broker", "swarm")),
        )

        assertThat(merged.incompleteReasons).containsExactly(
            identifiedGap("dns", releasedSdk, remedy = notByHosts(releasedSdk)),
        )
        assertThat(merged.root.componentRefs()).contains(releasedSdk, older, compositeSdk)
        assertThat(merged.root.dependsOn(primaryRef("dns"))).containsExactly(releasedSdk, ref("lib"))
        assertThat(merged.root.compositions().last()).isEqualTo("incomplete" to listOf(releasedSdk))
    }

    @Test
    fun `keeps the gap of an identified component when only a part that is no host describes another version`() {
        val (provided, _) = slf4j("2.0.9")
        // two extensions without a broker: kafka bundles slf4j-api, which dns cannot reach from its own classloader
        val merged = merge(
            partIdentifying("dns", mapOf(slf4j("2.0.9"))),
            part("kafka", slf4j("2.0.16").second, listOf(slf4j("2.0.16").first)),
        )

        assertThat(merged.incompleteReasons).containsExactly(
            identifiedGap("dns", provided),
        )
        assertThat(merged.root.dependsOn(primaryRef("dns"))).containsExactly(ref("lib"), provided)
        assertThat(merged.root.componentRefs()).contains(provided, slf4j("2.0.16").first)
    }

    @Test
    fun `keeps the gap of an identified component when only a part that is no host describes the same version`() {
        val (shared, component) = slf4j("2.0.16")

        // the broker hosts dns, but describes no slf4j-api; kafka's copy is its own, even in the same version
        val merged = merge(
            partIdentifying("dns", mapOf(shared to component)),
            part("kafka", component, listOf(shared)),
            broker(),
            with = hosted,
        )

        assertThat(merged.incompleteReasons).containsExactly(
            identifiedGap("dns", shared, remedy = notByHosts(shared)),
        )
        assertThat(merged.root.compositions().last()).isEqualTo("incomplete" to listOf(shared))
        // kafka ships it, so the product does not say that a host provides it
        assertThat(merged.root.labelled(shared)).isFalse()
    }

    @Test
    fun `keeps the gap of an identified component without a purl`() {
        val merged = merge(
            partIdentifying("dns", mapOf("sdk-4.1.0" to packaged("sdk-4.1.0"))),
            broker(mapOf("sdk-4.2.0" to packaged("sdk-4.2.0"))),
            with = hosted,
        )

        // neither ref is a purl, so nothing tells the two apart from unrelated components
        assertThat(merged.incompleteReasons).containsExactly(
            identifiedGap("dns", "sdk-4.1.0", remedy = noPackage()),
        )
        assertThat(merged.root.dependsOn(primaryRef("dns"))).containsExactly(ref("lib"), "sdk-4.1.0")
    }

    @Test
    fun `keeps the label on an identified component that no host part describes`() {
        val merged = merge(extension()).root

        assertThat(merged.labelled(releasedSdk)).isTrue()
    }

    @Test
    fun `keeps the whole graph of a merged part open when a part of it ships what another identifies`() {
        val (provided, component) = slf4j("2.0.9")
        // kafka ships the very version dns identifies; without a host, the image leaves that gap open
        val image = merge(
            partIdentifying("dns", mapOf(provided to component)),
            part("kafka", component, listOf(provided)),
        )

        val bundle = merge(
            SbomPart("image.cdx.json", mapper.writeValueAsBytes(image.root)),
            broker(mapOf(slf4j("2.0.16"))),
            with = hosted.copy(name = "bundle"),
        )

        // the image lists it without the label, as kafka ships it, so the broker's version takes the place of nothing
        assertThat(bundle.incompleteReasons).containsExactly(unlabelledGap("product", provided))
        assertThat(bundle.root.compositions().map { it.first }).containsExactly("incomplete")
        assertThat(bundle.root.componentRefs()).contains(provided)
        assertThat(bundle.root.dependsOn(primaryRef("dns"))).contains(provided)
        assertThat(bundle.root.dependsOn(primaryRef("kafka"))).containsExactly(provided)
    }

    @Test
    fun `keeps the whole graph of a merged part open when a part ships with dependencies what another identifies`() {
        val (provided, component) = slf4j("2.0.9")
        val y = ref("y")
        // kafka bundles the very version dns identifies, with a dependency of its own
        val kafka = part("kafka", component + ", " + library("y", "dd"), listOf(provided, y)).edited { root ->
            val entry = (root["dependencies"] as ArrayNode).first { it["ref"].asText() == provided } as ObjectNode
            entry.putArray("dependsOn").add(y)
        }
        val image = merge(partIdentifying("dns", mapOf(provided to component)), kafka)

        val bundle = merge(
            SbomPart("image.cdx.json", mapper.writeValueAsBytes(image.root)),
            broker(mapOf(slf4j("2.0.16"))),
            with = hosted.copy(name = "bundle"),
        )

        // the broker's version takes the place of no component that kafka ships
        assertThat(bundle.root.componentRefs()).contains(provided, y)
        assertThat(bundle.root.dependsOn(primaryRef("kafka"))).contains(provided)
        assertThat(bundle.root.dependsOn(provided)).containsExactly(y)
        // the image lists it without the label, as kafka ships it, which keeps the image open as a whole
        assertThat(bundle.incompleteReasons).containsExactly(unlabelledGap("product", provided))
        assertThat(bundle.root.compositions().map { it.first }).containsExactly("incomplete")
    }

    @Test
    fun `keeps the whole graph of a part open when it declares components incomplete without labelling them`() {
        // the shape generateSbom writes, but from a tool that does not label what the host provides
        val merged = merge(extension().unlabelled(), broker(), with = hosted)

        assertThat(merged.incompleteReasons).containsExactly(unlabelledGap("dns", releasedSdk))
        assertThat(merged.root.dependsOn(primaryRef("dns"))).containsExactly(releasedSdk, ref("lib"))
        assertThat(merged.root.compositions().map { it.first }).containsExactly("incomplete")
    }

    @Test
    fun `leaves out an identified component that a host part describes in another version`() {
        val merged = merge(extension(), broker(), with = hosted).root

        // the image ships the SDK of the broker, not the one the extension was built against
        assertThat(merged.componentRefs()).contains(compositeSdk).doesNotContain(releasedSdk)
        assertThat(merged["dependencies"].map { it["ref"].asText() }).doesNotContain(releasedSdk)
    }

    @Test
    fun `matches the package of an identified component whatever the spelling of the purls`() {
        val spellings = mapOf(
            "unencoded npm scope" to ("pkg:npm/@types/node@18.0.0" to "pkg:npm/@types/node@20.0.0"),
            "percent-encoded npm scope" to ("pkg:npm/%40types/node@18.0.0" to "pkg:npm/@types/node@20.0.0"),
            "slashes after the scheme" to ("pkg://maven/com.hivemq/hivemq-extension-sdk@4.1.0" to compositeSdk),
            "subpath" to ("pkg:maven/com.hivemq/hivemq-extension-sdk@4.1.0#lib/sdk.jar" to compositeSdk),
            "type in upper case" to ("pkg:MAVEN/com.hivemq/hivemq-extension-sdk@4.1.0" to compositeSdk),
        )

        for ((spelling, purls) in spellings) {
            val (identified, described) = purls
            // the identified component's bom-ref is no purl, so the package comes from its purl field
            val merged = merge(
                partIdentifying("dns", mapOf("identified" to packaged("identified", identified))),
                broker(mapOf(described to packaged(described, described))),
                with = hosted,
            )

            assertThat(merged.root.dependsOn(primaryRef("dns"))).describedAs(spelling)
                .containsExactlyInAnyOrder(described, ref("lib"))
        }
    }

    @Test
    fun `points an identified component at another version without making that version depend on itself`() {
        val (provided, old) = slf4j("2.0.9")
        val (shipped, new) = slf4j("2.0.16")
        // dns lists the version the broker ships next to the one it identifies, and lets the first depend on the other
        val dns = part("dns", "$old, $new", listOf(provided, shipped))
            .edited { root ->
                val entry = (root["dependencies"] as ArrayNode).first { it["ref"].asText() == shipped } as ObjectNode
                entry.putArray("dependsOn").add(provided)
            }
            .withCompositions(
                """
                [
                  { "aggregate": "complete", "dependencies": [ "${primaryRef("dns")}", "$shipped" ] },
                  { "aggregate": "incomplete", "dependencies": [ "$provided" ] }
                ]
                """.trimIndent()
            )
            .labelling(provided)

        val merged = merge(dns, broker(mapOf(shipped to new)), with = hosted).root

        assertThat(merged.dependsOn(shipped)).isEmpty()
        assertThat(merged.dependsOn(primaryRef("dns"))).containsExactly(shipped)
    }

    @Test
    fun `points an identified component at the version the host part describes when another part bundles a third`() {
        val (shipped, component) = slf4j("2.0.16")

        // without the host, the copy kafka bundles would make two candidates for what dns identifies
        val merged = merge(
            partIdentifying("dns", mapOf(slf4j("2.0.9"))),
            part("kafka", slf4j("2.0.13").second, listOf(slf4j("2.0.13").first)),
            broker(mapOf(shipped to component)),
            with = hosted,
        )

        assertThat(merged.incompleteReasons).isEmpty()
        assertThat(merged.root.dependsOn(primaryRef("dns"))).containsExactly(ref("lib"), shipped)
        assertThat(merged.root.componentRefs()).contains(slf4j("2.0.13").first).doesNotContain(slf4j("2.0.9").first)
    }

    @Test
    fun `points the dependencies on an identified component at the other version the host part describes`() {
        val merged = merge(extension(), broker(), with = hosted).root

        assertThat(merged.dependsOn(primaryRef("dns"))).containsExactly(compositeSdk, ref("lib"))
    }

    @Test
    fun `removes the label from an identified component that a host part describes`() {
        val broker = part("broker", library("sdk", "bb"), listOf(ref("sdk")))

        val merged = merge(partIdentifyingSdk(), broker, with = hosted).root

        // the product describes the SDK through the broker, so it no longer only identifies it
        assertThat(merged.labelled(ref("sdk"))).isFalse()
    }

    @Test
    fun `removes the label from an identified component that a part which is no host ships`() {
        val (provided, component) = slf4j("2.0.9")

        val merged = merge(
            partIdentifying("dns", mapOf(provided to component)),
            part("kafka", component, listOf(provided)),
        )

        assertThat(merged.root.labelled(provided)).isFalse()
        assertThat(merged.root.compositions().last()).isEqualTo("incomplete" to listOf(provided))
    }

    @Test
    fun `reports no gap of identified components that the build declares`() {
        val merged = merge(extension(), with = product.copy(declaredIdentifiedGapReasons = declaredGap))

        assertThat(merged.incompleteReasons).isEmpty()
        // the document states the gap all the same
        assertThat(merged.root.compositions()).containsExactly(
            "complete" to listOf(primaryRef("dns"), ref("lib"), "product@1.0.0"),
            "incomplete" to listOf(releasedSdk),
        )
    }

    @Test
    fun `reports nothing about a replaced component the product keeps when the build declares the gap`() {
        val dns = extension().edited { root ->
            val lib = (root["components"] as ArrayNode).first { it["name"].asText() == "lib" } as ObjectNode
            lib.putObject("evidence").putArray("identity").addObject().put("field", "purl").putArray("tools")
                .add(releasedSdk)
        }

        // the kept component is all that is left of the gap, so the declaration still has something to cover
        val merged = merge(dns, broker(), with = hosted.copy(declaredIdentifiedGapReasons = declaredGap))

        assertThat(merged.incompleteReasons).isEmpty()
        assertThat(merged.root.compositions().last()).isEqualTo("incomplete" to listOf(releasedSdk))
    }

    @Test
    fun `reports the gap of a part that may hide the declared gap of identified components`() {
        // dns reports a gap over its whole graph, the SDK included, so the merge cannot tell the declaration is stale
        val dns = extension().withCompositions(
            """[ { "aggregate": "incomplete", "dependencies": """ +
                """[ "${primaryRef("dns")}", "${ref("lib")}", "$releasedSdk" ] } ]"""
        )

        val merged = merge(dns, with = product.copy(declaredIdentifiedGapReasons = declaredGap))

        assertThat(merged.incompleteReasons)
            .containsExactly("the SBOM of 'dns' declares its dependency graph incomplete")
    }

    @Test
    fun `reports the other gaps when the build declares the gap of identified components`() {
        val declared = product.copy(expectedParts = setOf("dns", "broker"), declaredIdentifiedGapReasons = declaredGap)

        val merged = merge(extension(), with = declared)

        assertThat(merged.incompleteReasons).containsExactly("no SBOM for part 'broker'")
        assertThat(merged.root.compositions().map { it.first }).containsExactly("incomplete")
    }

    // --- Output ---

    @Test
    fun `writes a document that is valid for the spec version it declares`() {
        val variants = mapOf(
            "two parts" to merge(part("alpha"), part("beta")),
            "url creator" to merge(part("alpha"), with = product.copy(creator = "https://www.hivemq.com/")),
            "no file" to merge(part("alpha"), with = product.copy(artifactFileName = null)),
            "incomplete" to merge(part("alpha", aggregate = "incomplete")),
            "identified gap" to merge(extension()),
            "replaced identified component" to merge(extension(), broker(), with = hosted),
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
