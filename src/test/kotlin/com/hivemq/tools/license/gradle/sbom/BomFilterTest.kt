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

    // --- Output ---

    @Test
    fun `changes nothing on a second pass`() {
        val once = bom().also { BomFilter.dropModules(it, PROVIDED) }
        val twice = once.deepCopy()

        BomFilter.dropModules(twice, PROVIDED)

        assertThat(twice).isEqualTo(once)
    }

    private companion object {
        // the bom-refs CycloneDX writes: a purl, which for a project of this build carries the project path
        const val APP = "pkg:maven/com.hivemq/app@1.0.0?project_path=%3A"
        const val EXTRA = "pkg:maven/org.example/extra@1.0?type=jar"
        const val LIB = "pkg:maven/org.example/lib@1.0?type=jar"
        const val PROVIDED_1 = "pkg:maven/org.example/provided@1.0?type=jar"
        const val PROVIDED_2 = "pkg:maven/org.example/provided@2.0?type=jar"
        const val PROVIDED_API = "pkg:maven/org.example/provided-api@1.0?type=jar"

        // what the plugin reads from a hivemqProvided configuration, by group and name without a version
        val PROVIDED = setOf("org.example:provided")
        val PROVIDED_WITH_API = PROVIDED + "org.example:provided-api"
    }
}
