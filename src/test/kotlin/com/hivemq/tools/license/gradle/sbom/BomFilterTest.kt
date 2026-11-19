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
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.hivemq.tools.license.gradle.OwnModules
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class BomFilterTest {

    /**
     * A BOM the way the CycloneDX Gradle plugin writes it, reduced to what the filter touches. Two configurations
     * resolve the provided module in two versions: one is a dependency of the application, the other of the library.
     */
    private fun bom(): ObjectNode = BomJson.read(
        """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "version": 1,
          "metadata": {
            "component": {
              "type": "application", "bom-ref": "$APP",
              "group": "com.hivemq", "name": "app", "version": "1.0.0"
            }
          },
          "components": [
            {
              "type": "library", "bom-ref": "$LIB",
              "group": "org.example", "name": "lib", "version": "1.0"
            },
            {
              "type": "library", "bom-ref": "$PROVIDED_1",
              "group": "org.example", "name": "provided", "version": "1.0"
            },
            {
              "type": "library", "bom-ref": "$PROVIDED_2",
              "group": "org.example", "name": "provided", "version": "2.0"
            }
          ],
          "dependencies": [
            { "ref": "$APP", "dependsOn": [ "$LIB", "$PROVIDED_2" ] },
            { "ref": "$LIB", "dependsOn": [ "$PROVIDED_1" ] },
            { "ref": "$PROVIDED_1", "dependsOn": [] },
            { "ref": "$PROVIDED_2", "dependsOn": [] }
          ]
        }
        """.trimIndent().toByteArray()
    )

    /**
     * [bom] with the components [EXTRA] and [PROVIDED_API] added and [entries] as its dependency entries, each a
     * `bom-ref` with the refs it depends on.
     */
    private fun graph(vararg entries: Pair<String, List<String>>): ObjectNode = bom().apply {
        val components = get("components") as ArrayNode
        for ((ref, name) in listOf(EXTRA to "extra", PROVIDED_API to "provided-api")) {
            components.addObject().put("type", "library").put("bom-ref", ref)
                .put("group", "org.example").put("name", name).put("version", "1.0")
        }
        val dependencies = (get("dependencies") as ArrayNode).removeAll()
        for ((ref, dependsOn) in entries) {
            val list = dependencies.addObject().put("ref", ref).putArray("dependsOn")
            dependsOn.forEach(list::add)
        }
    }

    /** The identified components when the given modules are provided. */
    private fun identified(root: ObjectNode, modules: Set<String>): Set<String> =
        BomFilter.directlyDependedOn(root) { BomFilter.moduleIdOf(it) in modules }

    private fun JsonNode.refs(): List<String> = this["dependencies"].map { it["ref"].asText() }

    private fun JsonNode.dependsOn(ref: String): List<String> =
        this["dependencies"].first { it["ref"].asText() == ref }["dependsOn"].map { it.asText() }

    // --- Components ---

    @Test
    fun `drops every version of a given module`() {
        val root = bom()

        BomFilter.dropModules(root, PROVIDED)

        assertThat(root["components"].map { it["bom-ref"].asText() }).containsExactly(LIB)
    }

    @Test
    fun `keeps the top-level component even when its coordinates match`() {
        val root = bom()
        val component = root["metadata"]["component"].deepCopy<JsonNode>()

        BomFilter.dropModules(root, PROVIDED + "com.hivemq:app")

        assertThat(root["metadata"]["component"]).isEqualTo(component)
        // its dependency entry stays as well, so the SBOM still names the component's direct dependencies
        assertThat(root.refs()).containsExactly(APP, LIB)
    }

    @Test
    fun `knows a component with incomplete Maven metadata as one that may be public`() {
        val lib = bom()["components"][0] as ObjectNode

        assertThat(BomFilter.unresolvedMetadata(lib)).isNull()
        assertThat(BomFilter.mayBePublic(lib)).isFalse()
        lib.putArray("properties").addObject().put("name", "cdx:maven:package:metadata-unresolved")
            .put("value", "pom-unresolved")

        assertThat(BomFilter.unresolvedMetadata(lib)).isEqualTo("pom-unresolved")
        assertThat(BomFilter.mayBePublic(lib)).isTrue()
    }

    @Test
    fun `leaves the tree alone for an empty set`() {
        val root = bom()
        val unchanged = root.deepCopy()

        BomFilter.dropModules(root, emptySet())

        assertThat(root).isEqualTo(unchanged)
    }

    @Test
    fun `leaves the tree alone for modules it does not contain`() {
        val root = bom()
        val unchanged = root.deepCopy()

        // a module is matched as a whole, so neither part of a name nor the same name in another group matches
        BomFilter.dropModules(root, setOf("org.example:prov", "org.other:provided"))

        assertThat(root).isEqualTo(unchanged)
    }

    @Test
    fun `tells a declared license from none, an empty list and an empty entry`() {
        val lib = bom()["components"][0] as ObjectNode

        assertThat(BomFilter.declaresLicense(lib)).isFalse()
        lib.putArray("licenses")
        assertThat(BomFilter.declaresLicense(lib)).isFalse()
        lib.putArray("licenses").addObject()
        assertThat(BomFilter.declaresLicense(lib)).isFalse()
        lib.putArray("licenses").addObject().putObject("license")
        assertThat(BomFilter.declaresLicense(lib)).isFalse()
        lib.putArray("licenses").addObject().putObject("license").put("name", " ")
        assertThat(BomFilter.declaresLicense(lib)).isFalse()
        lib.putArray("licenses").addObject().put("expression", "")
        assertThat(BomFilter.declaresLicense(lib)).isFalse()
        // a POM license CycloneDX could not map to an SPDX id comes as a name, which counts
        lib.putArray("licenses").addObject().putObject("license").put("name", "Proprietary")
        assertThat(BomFilter.declaresLicense(lib)).isTrue()
        lib.putArray("licenses").addObject().put("expression", "MIT OR ISC")
        assertThat(BomFilter.declaresLicense(lib)).isTrue()
    }

    // --- Dependencies ---

    @Test
    fun `drops every reference to a dropped component, the root's included`() {
        val root = bom()

        BomFilter.dropModules(root, PROVIDED)

        assertThat(root.dependsOn(APP)).containsExactly(LIB)
        assertThat(root.dependsOn(LIB)).isEmpty()
    }

    @Test
    fun `drops the dependency entries of the dropped components`() {
        val root = bom()

        BomFilter.dropModules(root, PROVIDED)

        assertThat(root.refs()).containsExactly(APP, LIB)
    }

    @Test
    fun `links a component reached only through a dropped one to the dropped component's dependant`() {
        val root = graph(
            APP to listOf(LIB, PROVIDED_2),
            LIB to listOf(PROVIDED_1),
            PROVIDED_1 to listOf(EXTRA),
            PROVIDED_2 to emptyList(),
            EXTRA to emptyList(),
        )

        BomFilter.dropModules(root, PROVIDED)

        assertThat(root.dependsOn(LIB)).containsExactly(EXTRA)
        assertThat(root.dependsOn(APP)).containsExactly(LIB)
        assertThat(root.refs()).containsExactly(APP, LIB, EXTRA)
    }

    @Test
    fun `links a component reached through two dropped ones once`() {
        val root = graph(
            APP to listOf(LIB, PROVIDED_1, PROVIDED_API),
            LIB to emptyList(),
            PROVIDED_1 to listOf(EXTRA, LIB),
            PROVIDED_API to listOf(EXTRA),
            EXTRA to emptyList(),
        )

        BomFilter.dropModules(root, PROVIDED_WITH_API)

        // in the order the refs first appear, the one the root already had included
        assertThat(root.dependsOn(APP)).containsExactly(LIB, EXTRA)
    }

    @Test
    fun `links across a chain of dropped components`() {
        val root = graph(
            APP to listOf(PROVIDED_2),
            PROVIDED_2 to listOf(PROVIDED_API),
            PROVIDED_API to listOf(EXTRA),
            EXTRA to emptyList(),
        )

        BomFilter.dropModules(root, PROVIDED_WITH_API)

        assertThat(root.dependsOn(APP)).containsExactly(EXTRA)
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `links across a cycle of dropped components`() {
        val root = graph(
            APP to listOf(LIB),
            LIB to listOf(PROVIDED_1),
            PROVIDED_1 to listOf(PROVIDED_API),
            PROVIDED_API to listOf(PROVIDED_1, EXTRA),
            EXTRA to emptyList(),
        )

        BomFilter.dropModules(root, PROVIDED_WITH_API)

        assertThat(root.dependsOn(LIB)).containsExactly(EXTRA)
    }

    @Test
    fun `never links a component to itself`() {
        val root = graph(
            APP to listOf(LIB),
            LIB to listOf(PROVIDED_1),
            PROVIDED_1 to listOf(LIB, EXTRA),
            EXTRA to emptyList(),
        )

        BomFilter.dropModules(root, PROVIDED)

        assertThat(root.dependsOn(LIB)).containsExactly(EXTRA)
    }

    // --- Nested components and other references ---

    /** Nests a component [ref] named [name] inside the component [parent] of this BOM. */
    private fun ObjectNode.nest(parent: String, ref: String, name: String): ObjectNode {
        val component = this["components"].first { it["bom-ref"].asText() == parent } as ObjectNode
        val nested = component.get("components") as? ArrayNode ?: component.putArray("components")
        nested.addObject().put("type", "library").put("bom-ref", ref)
            .put("group", "org.example").put("name", name).put("version", "1.0")
        return component
    }

    @Test
    fun `cleans the other references when the BOM has no dependencies array`() {
        val root = bom()
        root.remove("dependencies")
        root.putArray("vulnerabilities").addObject().put("id", "CVE-2026-0001")
            .putArray("affects").addObject().put("ref", PROVIDED_1)

        BomFilter.dropModules(root, PROVIDED)

        assertThat(root["components"].map { it["bom-ref"].asText() }).containsExactly(LIB)
        // the vulnerability affected the dropped component only, so it goes with it
        assertThat(root["vulnerabilities"]).isEmpty()
        assertThat(root.has("dependencies")).isFalse()
    }

    @Test
    fun `drops a composition it empties, keeping one with a bom-ref as not specified`() {
        val root = bom()
        val compositions = root.putArray("compositions")
        compositions.addObject().put("aggregate", "complete").putArray("assemblies").add(PROVIDED_2)
        compositions.addObject().put("bom-ref", "composition-1").put("aggregate", "complete")
            .putArray("dependencies").add(PROVIDED_1)
        compositions.addObject().put("aggregate", "complete").putArray("assemblies").add(PROVIDED_2).add(LIB)
        compositions.addObject().put("aggregate", "complete")

        BomFilter.dropModules(root, PROVIDED)

        // a composition left naming nothing would claim completeness about nothing; one the filter did not change stays
        assertThat(root["compositions"].map { it.toString() }).containsExactly(
            """{"bom-ref":"composition-1","aggregate":"not_specified"}""",
            """{"aggregate":"complete","assemblies":["$LIB"]}""",
            """{"aggregate":"complete"}""",
        )
    }

    @Test
    fun `drops a matching component nested in another component, and the references to it`() {
        val root = bom()
        // CycloneDX lets a component carry components of its own; the filter sees them like top-level ones
        val lib = root.nest(LIB, EXTRA, "extra")
        (root["dependencies"] as ArrayNode).addObject().put("ref", EXTRA).putArray("dependsOn")
        ((root["dependencies"].first { it["ref"].asText() == LIB } as ObjectNode)["dependsOn"] as ArrayNode).add(EXTRA)

        BomFilter.dropModules(root, setOf("org.example:extra"))

        // no empty list is left behind, as CycloneDX writes none
        assertThat(lib.has("components")).isFalse()
        assertThat(root.refs()).doesNotContain(EXTRA)
        assertThat(root.dependsOn(LIB)).containsExactly(PROVIDED_1)
    }

    @Test
    fun `drops a matching component nested in the top-level component`() {
        val root = bom()
        (root["metadata"]["component"] as ObjectNode).putArray("components").addObject().put("type", "library")
            .put("bom-ref", PROVIDED_3).put("group", "org.example").put("name", "provided").put("version", "3.0")
        ((root["dependencies"].first { it["ref"].asText() == APP } as ObjectNode)["dependsOn"] as ArrayNode)
            .add(PROVIDED_3)

        BomFilter.dropModules(root, PROVIDED)

        assertThat(root["metadata"]["component"].has("components")).isFalse()
        assertThat(root["metadata"]["component"]["name"].asText()).isEqualTo("app")
        assertThat(root.dependsOn(APP)).containsExactly(LIB)
    }

    @Test
    fun `drops the components nested in a dropped one, and the references to them`() {
        val root = bom()
        root.nest(PROVIDED_1, EXTRA, "extra")
        ((root["dependencies"].first { it["ref"].asText() == APP } as ObjectNode)["dependsOn"] as ArrayNode).add(EXTRA)

        BomFilter.dropModules(root, PROVIDED)

        assertThat(root["components"].map { it["bom-ref"].asText() }).containsExactly(LIB)
        assertThat(root.dependsOn(APP)).containsExactly(LIB)
    }

    @Test
    fun `drops what is nested in the given components at any depth`() {
        val root = bom()
        val mid = root.nest(LIB, MID, "mid")["components"].single() as ObjectNode
        mid.putArray("components").addObject().put("type", "library").put("bom-ref", EXTRA)
            .put("group", "org.example").put("name", "extra").put("version", "1.0")
        (root["dependencies"] as ArrayNode).addObject().put("ref", MID).putArray("dependsOn").add(EXTRA)
        ((root["dependencies"].first { it["ref"].asText() == APP } as ObjectNode)["dependsOn"] as ArrayNode).add(MID)

        BomFilter.dropNested(root, { BomFilter.moduleIdOf(it) == "org.example:lib" })

        assertThat(root.refs()).containsExactly(APP, LIB, PROVIDED_1, PROVIDED_2)
        assertThat(root.dependsOn(APP)).containsExactly(LIB, PROVIDED_2)
    }

    @Test
    fun `drops what is nested in the given components, and the references to it`() {
        // the host provides lib whole, so what the BOM nests in it does not ship either
        val root = bom()
        root.nest(LIB, EXTRA, "extra")
        (root["dependencies"] as ArrayNode).addObject().put("ref", EXTRA).putArray("dependsOn")
        ((root["dependencies"].first { it["ref"].asText() == APP } as ObjectNode)["dependsOn"] as ArrayNode).add(EXTRA)

        BomFilter.dropNested(root, { BomFilter.moduleIdOf(it) == "org.example:lib" })

        assertThat(root["components"].first { it["bom-ref"].asText() == LIB }.has("components")).isFalse()
        assertThat(root.refs()).containsExactly(APP, LIB, PROVIDED_1, PROVIDED_2)
        assertThat(root.dependsOn(APP)).containsExactly(LIB, PROVIDED_2)
    }

    @Test
    fun `finds a reference to a dropped component in a place the filter does not rewrite`() {
        val root = bom()
        // the second key has a slash and a tilde, which a JSON pointer escapes
        root.putObject("declarations").putArray("claims").addObject()
            .put("target", PROVIDED_1).put("a/b~c", PROVIDED_2)
        val before = BomFilter.refsIn(root)

        BomFilter.dropModules(root, PROVIDED)

        // the dependency entries are rewritten, the claim is not, so it holds the only references left to dropped ones
        assertThat(BomFilter.referencesTo(root, before - BomFilter.refsIn(root))).containsExactly(
            entry("/declarations/claims/0/target", PROVIDED_1),
            entry("/declarations/claims/0/a~1b~0c", PROVIDED_2),
        )
    }

    @Test
    fun `finds a reference to a vulnerability that went with the dropped component it affected`() {
        val root = bom()
        root.putArray("vulnerabilities").addObject().put("bom-ref", "vuln-1").put("id", "CVE-2026-0001")
            .putArray("affects").addObject().put("ref", PROVIDED_1)
        root.putObject("declarations").putArray("claims").addObject().put("target", "vuln-1")
        val before = BomFilter.refsIn(root)

        BomFilter.dropModules(root, PROVIDED)

        // the vulnerability had nothing left to affect, so it went too, and the claim about it is now dangling
        assertThat(BomFilter.referencesTo(root, before - BomFilter.refsIn(root)))
            .containsExactly(entry("/declarations/claims/0/target", "vuln-1"))
    }

    @Test
    fun `keeps a nested component the predicate accepts below one it rejects, which goes with the dropped parent`() {
        // a third-party component nested in a provided one does not ship either; the public SDK below it stays
        val root = bom()
        val mid = root.nest(PROVIDED_1, MID, "mid")["components"].single() as ObjectNode
        mid.putArray("components").addObject().put("type", "library").put("bom-ref", EXTRA)
            .put("group", "org.example").put("name", "extra").put("version", "1.0")
        (root["dependencies"] as ArrayNode).addObject().put("ref", MID).putArray("dependsOn").add(EXTRA)

        BomFilter.dropModules(root, PROVIDED) { it["name"].asText() == "extra" }

        assertThat(root["components"].map { it["bom-ref"].asText() }).containsExactly(LIB, EXTRA)
        assertThat(root.refs()).containsExactly(APP, LIB)
        assertThat(root.dependsOn(LIB)).containsExactly(EXTRA)
    }

    @Test
    fun `keeps a nested component the predicate accepts when dropping its parent, at any depth`() {
        // a public SDK nested below a provided component and a provided one in between stays as the connecting point
        val root = bom()
        val providedApi = root.nest(PROVIDED_1, PROVIDED_API, "provided-api")["components"].single() as ObjectNode
        providedApi.putArray("components").addObject().put("type", "library").put("bom-ref", EXTRA)
            .put("group", "org.example").put("name", "extra").put("version", "1.0")

        BomFilter.dropModules(root, PROVIDED_WITH_API) { it["name"].asText() == "extra" }

        assertThat(root["components"].map { it["bom-ref"].asText() }).containsExactly(LIB, EXTRA)
        assertThat(root.refs()).containsExactly(APP, LIB)
        // the dependant of the dropped component depends on what moved up out of it
        assertThat(root.dependsOn(LIB)).containsExactly(EXTRA)
    }

    @Test
    fun `keeps one of the compositions that pruning makes equal`() {
        val root = bom()
        val compositions = root.putArray("compositions")
        compositions.addObject().put("aggregate", "complete").putArray("assemblies").add(PROVIDED_2).add(LIB)
        compositions.addObject().put("aggregate", "complete").putArray("assemblies").add(LIB)

        BomFilter.dropModules(root, PROVIDED)

        // the schema wants each composition once
        assertThat(root["compositions"].map { it.toString() })
            .containsExactly("""{"aggregate":"complete","assemblies":["$LIB"]}""")
    }

    @Test
    fun `links a component that moves up to the dependants of the dropped one`() {
        // app depends on the provided component, which contains extra without a dependency edge to it
        val root = bom()
        root.nest(PROVIDED_2, EXTRA, "extra")

        BomFilter.dropModules(root, PROVIDED) { true }

        assertThat(root["components"].map { it["bom-ref"].asText() }).containsExactly(LIB, EXTRA)
        assertThat(root.dependsOn(APP)).containsExactly(LIB, EXTRA)
    }

    @Test
    fun `links what moves up out of a ref-less nested component through one the predicate rejects`() {
        // keep accepts all but mid: the ref-less one moves up, then goes as provided, taking mid along
        val root = bom()
        val provided = root["components"].first { it["bom-ref"].asText() == PROVIDED_1 } as ObjectNode
        val refless = provided.putArray("components").addObject().put("type", "library")
            .put("group", "org.example").put("name", "provided").put("version", "3.0")
        val mid = refless.putArray("components").addObject().put("type", "library").put("bom-ref", MID)
            .put("group", "org.example").put("name", "mid").put("version", "1.0")
        mid.putArray("components").addObject().put("type", "library").put("bom-ref", EXTRA)
            .put("group", "org.example").put("name", "extra").put("version", "1.0")

        BomFilter.dropModules(root, PROVIDED) { it["bom-ref"]?.asText() != MID }

        // the outer one stands in for the ref-less one, so lib, the outer one's dependant, reaches the innermost one
        assertThat(root["components"].map { it["bom-ref"].asText() }).containsExactly(LIB, EXTRA)
        assertThat(root.dependsOn(LIB)).containsExactly(EXTRA)
    }

    @Test
    fun `links what moves up out of a ref-less nested component to the dependants of its dropped parent`() {
        // a provided component holds a ref-less provided one, which holds a third-party one; keep accepts every one
        val root = bom()
        val provided = root["components"].first { it["bom-ref"].asText() == PROVIDED_1 } as ObjectNode
        val refless = provided.putArray("components").addObject().put("type", "library")
            .put("group", "org.example").put("name", "provided").put("version", "3.0")
        refless.putArray("components").addObject().put("type", "library").put("bom-ref", EXTRA)
            .put("group", "org.example").put("name", "extra").put("version", "1.0")

        BomFilter.dropModules(root, PROVIDED) { true }

        // the ref-less one went too, so the third-party one moved up twice; lib, the outer one's dependant, reaches it
        assertThat(root["components"].map { it["bom-ref"].asText() }).containsExactly(LIB, EXTRA)
        assertThat(root.dependsOn(LIB)).containsExactly(EXTRA)
    }

    @Test
    fun `lists every component, nested ones included, and the top-level component excluded`() {
        val root = bom()
        root.nest(LIB, EXTRA, "extra")

        assertThat(BomFilter.components(root).map { it["bom-ref"].asText() })
            .containsExactly(LIB, EXTRA, PROVIDED_1, PROVIDED_2)
    }

    @Test
    fun `lists the components nested in the top-level component, not the top-level component`() {
        val root = bom()
        (root["metadata"]["component"] as ObjectNode).putArray("components").addObject()
            .put("bom-ref", EXTRA).put("group", "org.example").put("name", "extra")

        assertThat(BomFilter.components(root).map { it["bom-ref"].asText() })
            .containsExactly(LIB, PROVIDED_1, PROVIDED_2, EXTRA)
    }

    @Test
    fun `moves the components nested below several dropped levels up when told to`() {
        val root = bom()
        val providedApi = root.nest(PROVIDED_1, PROVIDED_API, "provided-api")["components"].single() as ObjectNode
        providedApi.putArray("components").addObject().put("type", "library").put("bom-ref", EXTRA)
            .put("group", "org.example").put("name", "extra").put("version", "1.0")
        (root["dependencies"] as ArrayNode).addObject().put("ref", EXTRA).putArray("dependsOn")
        ((root["dependencies"].first { it["ref"].asText() == PROVIDED_1 } as ObjectNode)["dependsOn"] as ArrayNode)
            .add(EXTRA)

        BomFilter.dropModules(root, PROVIDED_WITH_API) { true }

        // the moved component matched as well, so its own nested component moved up in turn
        assertThat(root["components"].map { it["bom-ref"].asText() }).containsExactly(LIB, EXTRA)
        assertThat(root.dependsOn(LIB)).containsExactly(EXTRA)
    }

    @Test
    fun `moves the components nested in a dropped one to its place when told to`() {
        val root = bom()
        root.nest(PROVIDED_1, EXTRA, "extra")
        (root["dependencies"] as ArrayNode).addObject().put("ref", EXTRA).putArray("dependsOn")
        ((root["dependencies"].first { it["ref"].asText() == PROVIDED_1 } as ObjectNode)["dependsOn"] as ArrayNode)
            .add(EXTRA)

        BomFilter.dropModules(root, PROVIDED) { true }

        // extra takes the place of the dropped component, and the dependant of that component now depends on extra
        assertThat(root["components"].map { it["bom-ref"].asText() }).containsExactly(LIB, EXTRA)
        assertThat(root.dependsOn(LIB)).containsExactly(EXTRA)
        assertThat(root.refs()).containsExactly(APP, LIB, EXTRA)
    }

    @Test
    fun `removes a dropped component from the annotations`() {
        val root = bom()
        root.putArray("annotations").addObject().put("text", "reviewed")
            .putArray("subjects").add(PROVIDED_1).add(LIB)

        BomFilter.dropModules(root, PROVIDED)

        assertThat(root["annotations"][0]["subjects"].map { it.asText() }).containsExactly(LIB)
    }

    @Test
    fun `removes a dropped component from the vulnerabilities and compositions`() {
        val root = bom()
        root.putArray("vulnerabilities").addObject().put("id", "CVE-2026-0001")
            .putArray("affects").apply { addObject().put("ref", PROVIDED_1); addObject().put("ref", LIB) }
        root.putArray("compositions").addObject().put("aggregate", "complete").apply {
            putArray("assemblies").add(PROVIDED_2).add(LIB)
            putArray("dependencies").add(PROVIDED_1).add(APP)
        }

        BomFilter.dropModules(root, PROVIDED)

        assertThat(root["vulnerabilities"][0]["affects"].map { it["ref"].asText() }).containsExactly(LIB)
        assertThat(root["compositions"][0]["assemblies"].map { it.asText() }).containsExactly(LIB)
        assertThat(root["compositions"][0]["dependencies"].map { it.asText() }).containsExactly(APP)
    }

    @Test
    fun `removes a dropped component from what another one provides`() {
        val root = bom()
        (root["dependencies"].first { it["ref"].asText() == LIB } as ObjectNode)
            .putArray("provides").add(PROVIDED_1).add(EXTRA)

        BomFilter.dropModules(root, PROVIDED)

        val lib = root["dependencies"].first { it["ref"].asText() == LIB }
        assertThat(lib["provides"].map { it.asText() }).containsExactly(EXTRA)
    }

    @Test
    fun `removes a vulnerability that affected dropped components only`() {
        val root = bom()
        root.putArray("vulnerabilities").apply {
            addObject().put("id", "CVE-2026-0001").putArray("affects").addObject().put("ref", PROVIDED_1)
            addObject().put("id", "CVE-2026-0002").putArray("affects")
                .apply { addObject().put("ref", PROVIDED_2); addObject().put("ref", LIB) }
            addObject().put("id", "CVE-2026-0003").putArray("affects")
        }

        BomFilter.dropModules(root, PROVIDED)

        // the one with nothing left to affect goes, the one that still affects lib and the one that never did stay
        assertThat(root["vulnerabilities"].map { it["id"].asText() }).containsExactly("CVE-2026-0002", "CVE-2026-0003")
        assertThat(root["vulnerabilities"][0]["affects"].map { it["ref"].asText() }).containsExactly(LIB)
    }

    @Test
    fun `removes an annotation about a license of a dropped component`() {
        val root = bom()
        // a license may carry a bom-ref of its own, which an annotation can be about
        (root["components"].first { it["bom-ref"].asText() == PROVIDED_1 } as ObjectNode)
            .putArray("licenses").addObject().putObject("license").put("id", "MIT").put("bom-ref", "lic-1")
        root.putArray("annotations").apply {
            addObject().put("text", "about the license").putArray("subjects").add("lic-1")
            addObject().put("text", "about the license and lib").putArray("subjects").add("lic-1").add(LIB)
        }

        BomFilter.dropModules(root, PROVIDED)

        assertThat(root["annotations"].map { it["text"].asText() }).containsExactly("about the license and lib")
        assertThat(root["annotations"][0]["subjects"].map { it.asText() }).containsExactly(LIB)
    }

    @Test
    fun `removes an annotation that was about a removed annotation only`() {
        val root = bom()
        root.putArray("annotations").apply {
            addObject().put("bom-ref", "note-1").put("text", "component").putArray("subjects").add(PROVIDED_1)
            addObject().put("bom-ref", "note-2").put("text", "about note-1").putArray("subjects").add("note-1")
            addObject().put("text", "about note-2 and lib").putArray("subjects").add("note-2").add(LIB)
        }

        BomFilter.dropModules(root, PROVIDED)

        // the chain goes link by link; the last annotation is still about lib
        assertThat(root["annotations"].map { it["text"].asText() }).containsExactly("about note-2 and lib")
        assertThat(root["annotations"][0]["subjects"].map { it.asText() }).containsExactly(LIB)
    }

    @Test
    fun `removes an annotation that was about dropped components or vulnerabilities only`() {
        val root = bom()
        root.putArray("vulnerabilities").apply {
            addObject().put("bom-ref", "vuln-1").put("id", "CVE-2026-0001").putArray("affects")
                .addObject().put("ref", PROVIDED_1)
            addObject().put("bom-ref", "vuln-2").put("id", "CVE-2026-0002").putArray("affects")
                .addObject().put("ref", LIB)
        }
        root.putArray("annotations").apply {
            addObject().put("text", "component").putArray("subjects").add(PROVIDED_1)
            addObject().put("text", "vulnerability").putArray("subjects").add("vuln-1")
            addObject().put("text", "kept").putArray("subjects").add(PROVIDED_2).add("vuln-2")
            addObject().put("text", "none").putArray("subjects")
        }

        BomFilter.dropModules(root, PROVIDED)

        // the two with nothing left to be about go; the one still about a vulnerability that stays, and the one that
        // never was about anything, stay
        assertThat(root["annotations"].map { it["text"].asText() }).containsExactly("kept", "none")
        assertThat(root["annotations"][0]["subjects"].map { it.asText() }).containsExactly("vuln-2")
    }

    @Test
    fun `reports no empty text as a reference, even after dropping a component with an empty bom-ref`() {
        val root = bom()
        root.nest(PROVIDED_1, "", "inner")
        (root["metadata"]["component"] as ObjectNode).put("description", "")
        val before = BomFilter.refsIn(root)

        BomFilter.dropModules(root, PROVIDED)

        // the nested component's empty bom-ref went with it, and an empty text names nothing
        assertThat(before - BomFilter.refsIn(root)).contains("")
        assertThat(BomFilter.referencesTo(root, before - BomFilter.refsIn(root))).isEmpty()
    }

    @Test
    fun `strips a vulnerability that goes from the compositions as well`() {
        val root = bom()
        root.putArray("vulnerabilities").addObject().put("bom-ref", "vuln-1").put("id", "CVE-2026-0001")
            .putArray("affects").addObject().put("ref", PROVIDED_1)
        root.putArray("compositions").addObject().put("aggregate", "complete")
            .putArray("vulnerabilities").add("vuln-1").add("vuln-2")

        BomFilter.dropModules(root, PROVIDED)

        assertThat(root["vulnerabilities"]).isEmpty()
        assertThat(root["compositions"][0]["vulnerabilities"].map { it.asText() }).containsExactly("vuln-2")
    }

    // --- Edges and properties of kept components ---

    @Test
    fun `detaches a component without a dependency entry by giving it an empty one`() {
        val root = bom()
        (root["dependencies"] as ArrayNode).removeIf { it["ref"].asText() == LIB }

        BomFilter.detachComponents(root, setOf(LIB))

        // explicitly no dependencies, which CycloneDX tells apart from unknown ones; the entry sits in ref order
        assertThat(root.refs()).containsExactly(APP, LIB, PROVIDED_1, PROVIDED_2)
        assertThat(root.dependsOn(LIB)).isEmpty()
    }

    @Test
    fun `detaches components in a chain, handing on what lies outside the set but not the chained component`() {
        // two modules the host provides, one depending on the other, both ahead of a shipped library
        val root = graph(
            APP to listOf(PROVIDED_API),
            PROVIDED_API to listOf(PROVIDED_1),
            PROVIDED_1 to listOf(EXTRA),
            EXTRA to emptyList(),
        )

        BomFilter.detachComponents(root, setOf(PROVIDED_API, PROVIDED_1))

        // the chained one is not handed on: a detached component has a dependant of its own, see directlyDependedOn
        assertThat(root.dependsOn(APP)).containsExactly(PROVIDED_API, EXTRA)
        assertThat(root.dependsOn(PROVIDED_API)).isEmpty()
        assertThat(root.dependsOn(PROVIDED_1)).isEmpty()
    }

    @Test
    fun `detaches the given components from their dependencies and hands those to the dependants`() {
        val root = bom()

        BomFilter.detachComponents(root, setOf(LIB))

        assertThat(root.dependsOn(LIB)).isEmpty()
        // lib's former target follows lib, in the order the references first appear
        assertThat(root.dependsOn(APP)).containsExactly(LIB, PROVIDED_1, PROVIDED_2)
        assertThat(root["components"].map { it["bom-ref"].asText() }).containsExactly(LIB, PROVIDED_1, PROVIDED_2)
    }

    @Test
    fun `removes the properties of a prefix from the given components only`() {
        val root = bom()
        for (ref in listOf(LIB, PROVIDED_1)) {
            val component = root["components"].first { it["bom-ref"].asText() == ref } as ObjectNode
            component.putArray("properties").apply {
                addObject().put("name", "bsi:component:filename").put("value", "a.jar")
                addObject().put("name", "cdx:maven:package:test").put("value", "false")
            }
        }

        BomFilter.removeProperties(root, setOf(LIB), "bsi:component:")

        val names = { ref: String ->
            root["components"].first { it["bom-ref"].asText() == ref }["properties"].map { it["name"].asText() }
        }
        assertThat(names(LIB)).containsExactly("cdx:maven:package:test")
        assertThat(names(PROVIDED_1)).containsExactly("bsi:component:filename", "cdx:maven:package:test")
    }

    // --- Shipped components ---

    @Test
    fun `hands the tree without the internal modules to the pick and keeps what it picks of the provided ones`() {
        // app is this build's own and internal, so it goes before the pick runs; lib is third-party and ships
        val root = graph(
            APP to listOf(LIB),
            LIB to listOf(PROVIDED_1, PROVIDED_2),
            PROVIDED_1 to listOf(EXTRA),
        )
        val app = (root["components"] as ArrayNode).addObject().put("type", "library").put("bom-ref", "internal")
            .put("group", "com.hivemq").put("name", "internal").put("version", "1.0")
        var seen = emptySet<String>()

        val kept = BomFilter.keepShipped(root, OwnModules(setOf("com.hivemq"), emptySet()), PROVIDED) { tree, _ ->
            seen = tree["components"].map { it["bom-ref"].asText() }.toSet()
            val pick = { component: ObjectNode -> component["bom-ref"].asText() == PROVIDED_2 }
            pick
        }

        assertThat(seen).doesNotContain("internal").contains(LIB, PROVIDED_1, PROVIDED_2)
        assertThat(kept.map { it["bom-ref"].asText() }).containsExactly(PROVIDED_2)
        // the api is not provided here, so it stays like every other third-party component
        assertThat(root["components"].map { it["bom-ref"].asText() })
            .containsExactly(LIB, PROVIDED_2, EXTRA, PROVIDED_API)
        // extra takes the place of the dropped provided component it was below
        assertThat(root.dependsOn(LIB)).containsExactly(EXTRA, PROVIDED_2)
        assertThat(app["name"].asText()).isEqualTo("internal")
    }

    // --- Identified components ---

    @Test
    fun `identifies a component nested in one of the set that a component outside depends on directly`() {
        // extra is not provided itself but sits inside a provided component, so it is the first on its path
        val root = bom()
        root.nest(PROVIDED_1, EXTRA, "extra")
        ((root["dependencies"].first { it["ref"].asText() == APP } as ObjectNode)["dependsOn"] as ArrayNode).add(EXTRA)

        assertThat(identified(root, PROVIDED)).containsExactly(EXTRA, PROVIDED_1, PROVIDED_2)
    }

    @Test
    fun `identifies nothing reached only through another component of the set`() {
        val root = graph(
            APP to listOf(PROVIDED_API),
            PROVIDED_API to listOf(PROVIDED_1),
            PROVIDED_1 to listOf(EXTRA),
        )

        assertThat(identified(root, PROVIDED_WITH_API)).containsExactly(PROVIDED_API)
    }

    @Test
    fun `identifies nothing that a component nested in one of the set depends on`() {
        // the nested component goes with its parent, so what it depends on has no dependant left
        val root = bom()
        root.nest(PROVIDED_1, EXTRA, "extra")
        ((root["dependencies"].first { it["ref"].asText() == APP } as ObjectNode)["dependsOn"] as ArrayNode)
            .removeIf { it.asText() == PROVIDED_2 }
        (root["dependencies"] as ArrayNode).addObject().put("ref", EXTRA).putArray("dependsOn").add(PROVIDED_2)

        assertThat(identified(root, PROVIDED)).containsExactly(PROVIDED_1)
    }

    @Test
    fun `identifies nothing without a dependencies array`() {
        val root = bom()
        root.remove("dependencies")

        assertThat(identified(root, PROVIDED)).isEmpty()
    }

    @Test
    fun `identifies one version of a module and not another that only a component of the set leads to`() {
        val root = graph(
            APP to listOf(PROVIDED_1, EXTRA),
            EXTRA to listOf(PROVIDED_2),
        )

        assertThat(identified(root, PROVIDED + "org.example:extra"))
            .containsExactly(EXTRA, PROVIDED_1)
    }

    @Test
    fun `identifies the components of the set that a component outside it depends on directly`() {
        // the top-level component depends on one, a shipped library on the other
        val root = graph(
            APP to listOf(LIB, PROVIDED_API),
            LIB to listOf(PROVIDED_1),
            PROVIDED_API to listOf(PROVIDED_1),
        )

        assertThat(identified(root, PROVIDED_WITH_API)).containsExactly(PROVIDED_API, PROVIDED_1)
    }

    // --- Output ---

    @Test
    fun `changes nothing on a second pass`() {
        val once = bom().also { BomFilter.dropModules(it, PROVIDED) }
        val twice = once.deepCopy()

        BomFilter.dropModules(twice, PROVIDED)

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `changes nothing on a second pass when nested components move up`() {
        val once = bom().also { it.nest(PROVIDED_1, EXTRA, "extra") }
        BomFilter.dropModules(once, PROVIDED) { true }
        val twice = once.deepCopy()

        BomFilter.dropModules(twice, PROVIDED) { true }

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `detaches nothing on a second pass`() {
        val once = bom().also { BomFilter.detachComponents(it, setOf(LIB)) }
        val twice = once.deepCopy()

        BomFilter.detachComponents(twice, setOf(LIB))

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `drops nothing nested on a second pass`() {
        val isLib = { component: ObjectNode -> BomFilter.moduleIdOf(component) == "org.example:lib" }
        val once = bom().also { it.nest(LIB, EXTRA, "extra") }
        BomFilter.dropNested(once, isLib)
        val twice = once.deepCopy()

        BomFilter.dropNested(twice, isLib)

        assertThat(twice).isEqualTo(once)
    }

    private companion object {
        // the bom-refs CycloneDX writes: a purl, which for a project of this build carries the project path
        const val APP = "pkg:maven/com.hivemq/app@1.0.0?project_path=%3A"
        const val EXTRA = "pkg:maven/org.example/extra@1.0?type=jar"
        const val LIB = "pkg:maven/org.example/lib@1.0?type=jar"
        const val MID = "pkg:maven/org.example/mid@1.0?type=jar"
        const val PROVIDED_1 = "pkg:maven/org.example/provided@1.0?type=jar"
        const val PROVIDED_2 = "pkg:maven/org.example/provided@2.0?type=jar"
        const val PROVIDED_3 = "pkg:maven/org.example/provided@3.0?type=jar"
        const val PROVIDED_API = "pkg:maven/org.example/provided-api@1.0?type=jar"

        // what the plugin reads from a hivemqProvided configuration, by group and name without a version
        val PROVIDED = setOf("org.example:provided")
        val PROVIDED_WITH_API = PROVIDED + "org.example:provided-api"
    }
}
