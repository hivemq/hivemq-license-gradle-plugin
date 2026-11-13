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
import org.assertj.core.api.Assertions.assertThat
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
        assertThat(root["vulnerabilities"][0]["affects"]).isEmpty()
        assertThat(root.has("dependencies")).isFalse()
    }

    @Test
    fun `drops a matching component nested in another component, and the references to it`() {
        val root = bom()
        // CycloneDX lets a component carry components of its own; the filter sees them like top-level ones
        val lib = root.nest(LIB, EXTRA, "extra")
        (root["dependencies"] as ArrayNode).addObject().put("ref", EXTRA).putArray("dependsOn")
        ((root["dependencies"].first { it["ref"].asText() == LIB } as ObjectNode)["dependsOn"] as ArrayNode).add(EXTRA)

        BomFilter.dropModules(root, setOf("org.example:extra"))

        assertThat(lib["components"]).isEmpty()
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

        assertThat(root["metadata"]["component"]["components"]).isEmpty()
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

        BomFilter.dropModules(root, PROVIDED_WITH_API, keepNested = true)

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

        BomFilter.dropModules(root, PROVIDED, keepNested = true)

        // extra takes the place of the dropped component, and the dependant of that component now depends on extra
        assertThat(root["components"].map { it["bom-ref"].asText() }).containsExactly(LIB, EXTRA)
        assertThat(root.dependsOn(LIB)).containsExactly(EXTRA)
        assertThat(root.refs()).containsExactly(APP, LIB, EXTRA)
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

    // --- Edges and properties of kept components ---

    @Test
    fun `detaches modules in a chain and hands every one of them on, so none loses its dependant`() {
        // two public SDKs the host provides, one depending on the other, both ahead of a shipped library
        val root = graph(
            APP to listOf(PROVIDED_API),
            PROVIDED_API to listOf(PROVIDED_1),
            PROVIDED_1 to listOf(EXTRA),
            EXTRA to emptyList(),
        )

        BomFilter.detachModules(root, PROVIDED_WITH_API)

        assertThat(root.dependsOn(APP)).containsExactly(PROVIDED_API, PROVIDED_1, EXTRA)
        assertThat(root.dependsOn(PROVIDED_API)).isEmpty()
        assertThat(root.dependsOn(PROVIDED_1)).isEmpty()
    }

    @Test
    fun `detaches the given modules from their dependencies and hands those to the dependants`() {
        val root = bom()

        BomFilter.detachModules(root, setOf("org.example:lib"))

        assertThat(root.dependsOn(LIB)).isEmpty()
        // lib's former target follows lib, in the order the references first appear
        assertThat(root.dependsOn(APP)).containsExactly(LIB, PROVIDED_1, PROVIDED_2)
        assertThat(root["components"].map { it["bom-ref"].asText() }).containsExactly(LIB, PROVIDED_1, PROVIDED_2)
    }

    @Test
    fun `removes the properties of a prefix from the given modules only`() {
        val root = bom()
        for (ref in listOf(LIB, PROVIDED_1)) {
            val component = root["components"].first { it["bom-ref"].asText() == ref } as ObjectNode
            component.putArray("properties").apply {
                addObject().put("name", "bsi:component:filename").put("value", "a.jar")
                addObject().put("name", "cdx:maven:package:test").put("value", "false")
            }
        }

        BomFilter.removeProperties(root, setOf("org.example:lib"), "bsi:component:")

        val names = { ref: String ->
            root["components"].first { it["bom-ref"].asText() == ref }["properties"].map { it["name"].asText() }
        }
        assertThat(names(LIB)).containsExactly("cdx:maven:package:test")
        assertThat(names(PROVIDED_1)).containsExactly("bsi:component:filename", "cdx:maven:package:test")
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
        val once = bom().also { it.nest(PROVIDED_1, EXTRA, "extra") }.also { BomFilter.dropModules(it, PROVIDED, true) }
        val twice = once.deepCopy()

        BomFilter.dropModules(twice, PROVIDED, keepNested = true)

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `detaches nothing on a second pass`() {
        val once = bom().also { BomFilter.detachModules(it, setOf("org.example:lib")) }
        val twice = once.deepCopy()

        BomFilter.detachModules(twice, setOf("org.example:lib"))

        assertThat(twice).isEqualTo(once)
    }

    private companion object {
        // the bom-refs CycloneDX writes: a purl, which for a project of this build carries the project path
        const val APP = "pkg:maven/com.hivemq/app@1.0.0?project_path=%3A"
        const val EXTRA = "pkg:maven/org.example/extra@1.0?type=jar"
        const val LIB = "pkg:maven/org.example/lib@1.0?type=jar"
        const val PROVIDED_1 = "pkg:maven/org.example/provided@1.0?type=jar"
        const val PROVIDED_2 = "pkg:maven/org.example/provided@2.0?type=jar"
        const val PROVIDED_3 = "pkg:maven/org.example/provided@3.0?type=jar"
        const val PROVIDED_API = "pkg:maven/org.example/provided-api@1.0?type=jar"

        // what the plugin reads from a hivemqProvided configuration, by group and name without a version
        val PROVIDED = setOf("org.example:provided")
        val PROVIDED_WITH_API = PROVIDED + "org.example:provided-api"
    }
}
