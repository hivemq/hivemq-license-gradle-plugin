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
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
        const val LIB = "pkg:maven/org.example/lib@1.0?type=jar"
        const val PROVIDED_1 = "pkg:maven/org.example/provided@1.0?type=jar"
        const val PROVIDED_2 = "pkg:maven/org.example/provided@2.0?type=jar"

        // what the plugin reads from a hivemqProvided configuration, by group and name without a version
        val PROVIDED = setOf("org.example:provided")
    }
}
