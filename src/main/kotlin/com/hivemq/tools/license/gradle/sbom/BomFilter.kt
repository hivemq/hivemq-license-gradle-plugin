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

/** Removes the components of the given `group:name` modules from a BOM tree, and every reference to them. */
internal object BomFilter {

    /**
     * Changes [root] in place: every version of [modules] goes, `metadata.component` stays even when it matches. A
     * reference to a dropped component is replaced by the kept ones that component leads to, so the graph still
     * reaches a component it reached only through dropped ones.
     */
    fun dropModules(root: ObjectNode, modules: Set<String>) {
        if (modules.isEmpty()) return
        val components = root.get("components") as? ArrayNode ?: return
        val (kept, removed) = components.partition { component ->
            "${component.get("group")?.asText().orEmpty()}:${component.get("name")?.asText().orEmpty()}" !in modules
        }
        val dropped = removed.mapNotNull { it.get("bom-ref")?.asText() }.toSet()
        components.removeAll()
        kept.forEach(components::add)
        val dependencies = root.get("dependencies") as? ArrayNode ?: return
        // the walk reads the entries of the dropped components, so it runs before they go
        val keptBelow = keptBelow(dependencies, dropped)
        val keptDependencies = dependencies.filter { it.get("ref")?.asText() !in dropped }
        dependencies.removeAll()
        keptDependencies.forEach(dependencies::add)
        for (dependency in dependencies) {
            val dependsOn = dependency.get("dependsOn") as? ArrayNode ?: continue
            if (dependsOn.none { it.asText() in dropped }) continue
            val ref = dependency.get("ref")?.asText()
            val refs = linkedSetOf<JsonNode>()
            for (entry in dependsOn) {
                val below = keptBelow[entry.asText()]
                // a cycle through a dropped component leads back to the entry, which does not depend on itself
                if (below == null) refs.add(entry) else below.filterTo(refs) { it.asText() != ref }
            }
            dependsOn.removeAll()
            refs.forEach(dependsOn::add)
        }
    }

    /**
     * For each of the [dropped] refs, the refs outside [dropped] that its dependency entry leads to, directly or
     * through other dropped ones, in the order they first appear; each dropped ref is walked once, so a cycle ends.
     */
    private fun keptBelow(dependencies: ArrayNode, dropped: Set<String>): Map<String, Set<JsonNode>> {
        val dependsOn = mutableMapOf<String, MutableList<JsonNode>>()
        for (dependency in dependencies) {
            val ref = dependency.get("ref")?.asText()?.takeIf { it in dropped } ?: continue
            val refs = dependency.get("dependsOn") as? ArrayNode ?: continue
            dependsOn.getOrPut(ref) { mutableListOf() }.addAll(refs)
        }
        return dropped.associateWith { start ->
            val reached = linkedSetOf<JsonNode>()
            val walked = mutableSetOf(start)
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                for (entry in dependsOn[queue.removeFirst()].orEmpty()) {
                    val ref = entry.asText()
                    if (ref !in dropped) reached.add(entry) else if (walked.add(ref)) queue.add(ref)
                }
            }
            reached
        }
    }
}
