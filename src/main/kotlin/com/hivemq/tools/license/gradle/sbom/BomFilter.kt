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
import com.hivemq.tools.license.gradle.Coordinates
import com.hivemq.tools.license.gradle.OwnModules

/** Removes given `group:name` modules from a BOM tree with every reference to them, or only their outgoing edges. */
internal object BomFilter {

    /**
     * Every component below [root]: the top-level `components`, the ones nested in another component and the ones
     * nested in `metadata.component`, which itself is excluded.
     */
    fun components(root: ObjectNode): List<ObjectNode> = nested(root) + (topLevel(root)?.let(::nested).orEmpty())

    /** `group:name` of a BOM [component]; a missing group or name counts as empty. */
    fun moduleIdOf(component: JsonNode): String =
        "${component.get("group")?.asText().orEmpty()}:${component.get("name")?.asText().orEmpty()}"

    /** The coordinates of a BOM [component], or null for one without a group, which no license task classifies. */
    fun coordinatesOf(component: JsonNode): Coordinates? {
        val group = component.get("group")?.asText() ?: return null
        val name = component.get("name")?.asText().orEmpty()
        return Coordinates(group, name, component.get("version")?.asText().orEmpty())
    }

    /** Whether a BOM [component] is a project of this build or of an included one, by its purl or its bom-ref. */
    fun isProject(component: JsonNode): Boolean =
        OwnModules.isProjectComponent(component.get("purl")?.asText(), component.get("bom-ref")?.asText())

    /** `group:name` of every component of [root] that [own] classifies as internal, nested ones included. */
    fun internalModules(root: ObjectNode, own: OwnModules): Set<String> = components(root)
        .filter { component ->
            val coordinates = coordinatesOf(component) ?: return@filter false
            own.classify(coordinates, isProject(component)) == OwnModules.Kind.INTERNAL
        }
        .mapTo(sortedSetOf(), ::moduleIdOf)

    /**
     * Changes [root] in place: every version of [modules] goes, nested or not, `metadata.component` stays. A dropped
     * component takes its nested components with it, unless [keepNested] moves them to its place. A reference to a
     * dropped component leads to the kept ones below it instead, or is removed where nothing can take its place.
     */
    fun dropModules(root: ObjectNode, modules: Set<String>, keepNested: Boolean = false) {
        if (modules.isEmpty()) return
        val dropped = linkedSetOf<String>()
        for (component in components(root)) {
            if (moduleIdOf(component) !in modules) continue
            val gone = if (keepNested) listOf(component) else listOf(component) + nested(component)
            gone.forEach { it.get("bom-ref")?.asText()?.let(dropped::add) }
        }
        removeComponents(root.get("components"), modules, keepNested)
        removeComponents(topLevel(root)?.get("components"), modules, keepNested)
        if (dropped.isEmpty()) return
        relinkDependencies(root, dropped)
        dropReferences(root, dropped)
    }

    /**
     * Empties the `dependsOn` lists of the components of [modules] and hands their targets to their dependants, so
     * every component stays reachable while those components depend on nothing in this SBOM.
     */
    fun detachModules(root: ObjectNode, modules: Set<String>) {
        if (modules.isEmpty()) return
        val detached = components(root)
            .filter { moduleIdOf(it) in modules }
            .mapNotNullTo(linkedSetOf()) { it.get("bom-ref")?.asText() }
        val dependencies = root.get("dependencies") as? ArrayNode ?: return
        if (detached.isEmpty()) return
        // a detached component reached only through another detached one still needs a dependant
        val below = keptBelow(dependencies, detached, includeThrough = true)
        for (dependency in dependencies) {
            val ref = dependency.get("ref")?.asText()
            val dependsOn = dependency.get("dependsOn") as? ArrayNode ?: continue
            if (ref in detached) {
                dependsOn.removeAll()
                continue
            }
            if (dependsOn.none { it.asText() in detached }) continue
            val refs = linkedSetOf<JsonNode>()
            for (entry in dependsOn) {
                refs.add(entry)
                below[entry.asText()]?.filterTo(refs) { it.asText() != ref }
            }
            dependsOn.removeAll()
            refs.forEach(dependsOn::add)
        }
    }

    /** Removes the properties whose name starts with [prefix] from the components of [modules]. */
    fun removeProperties(root: ObjectNode, modules: Set<String>, prefix: String) {
        for (component in components(root)) {
            if (moduleIdOf(component) !in modules) continue
            val properties = component.get("properties") as? ArrayNode ?: continue
            properties.removeIf { it.get("name")?.asText()?.startsWith(prefix) == true }
            if (properties.isEmpty) component.remove("properties")
        }
    }

    private fun topLevel(root: ObjectNode): ObjectNode? =
        (root.get("metadata") as? ObjectNode)?.get("component") as? ObjectNode

    // the components nested in a node, at any depth, in document order
    private fun nested(node: JsonNode): List<ObjectNode> = buildList { collect(node.get("components"), this) }

    private fun collect(array: JsonNode?, into: MutableList<ObjectNode>) {
        for (component in array as? ArrayNode ?: return) {
            if (component !is ObjectNode) continue
            into.add(component)
            collect(component.get("components"), into)
        }
    }

    // from this array and the nested ones; a dropped component's nested components go with it or take its place
    private fun removeComponents(array: JsonNode?, modules: Set<String>, keepNested: Boolean) {
        val components = array as? ArrayNode ?: return
        val kept = mutableListOf<JsonNode>()
        for (component in components) {
            when {
                moduleIdOf(component) !in modules -> kept.add(component)
                keepNested -> (component.get("components") as? ArrayNode)?.let(kept::addAll)
            }
        }
        components.removeAll()
        kept.forEach(components::add)
        // a component that moved up may match as well, so the array is walked again until none does
        if (keepNested && kept.any { moduleIdOf(it) in modules }) return removeComponents(components, modules, true)
        for (component in components) {
            removeComponents(component.get("components"), modules, keepNested)
        }
    }

    private fun relinkDependencies(root: ObjectNode, dropped: Set<String>) {
        val dependencies = root.get("dependencies") as? ArrayNode ?: return
        // the walk reads the entries of the dropped components, so it runs before they go
        val keptBelow = keptBelow(dependencies, dropped)
        val keptDependencies = dependencies.filter { it.get("ref")?.asText() !in dropped }
        dependencies.removeAll()
        keptDependencies.forEach(dependencies::add)
        for (dependency in dependencies) {
            // nothing provides what a dropped component provided
            (dependency.get("provides") as? ArrayNode)?.removeIf { it.asText() in dropped }
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
     * For each of the [through] refs, the refs outside [through] that its dependency entry leads to, directly or
     * through other such refs ([includeThrough] adds those too), in the order they first appear; each ref is walked
     * once, so a cycle ends.
     */
    private fun keptBelow(
        dependencies: ArrayNode,
        through: Set<String>,
        includeThrough: Boolean = false,
    ): Map<String, Set<JsonNode>> {
        val dependsOn = mutableMapOf<String, MutableList<JsonNode>>()
        for (dependency in dependencies) {
            val ref = dependency.get("ref")?.asText()?.takeIf { it in through } ?: continue
            val refs = dependency.get("dependsOn") as? ArrayNode ?: continue
            dependsOn.getOrPut(ref) { mutableListOf() }.addAll(refs)
        }
        return through.associateWith { start ->
            val reached = linkedSetOf<JsonNode>()
            val walked = mutableSetOf(start)
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                for (entry in dependsOn[queue.removeFirst()].orEmpty()) {
                    val ref = entry.asText()
                    if (ref !in through || includeThrough) reached.add(entry)
                    if (ref in through && walked.add(ref)) queue.add(ref)
                }
            }
            reached
        }
    }

    // a reference from outside the dependency graph leads nowhere once its component is gone
    private fun dropReferences(root: ObjectNode, dropped: Set<String>) {
        (root.get("vulnerabilities") as? ArrayNode)?.forEach { vulnerability ->
            (vulnerability.get("affects") as? ArrayNode)?.removeIf { it.get("ref")?.asText() in dropped }
        }
        (root.get("compositions") as? ArrayNode)?.forEach { composition ->
            for (field in listOf("assemblies", "dependencies")) {
                (composition.get(field) as? ArrayNode)?.removeIf { it.asText() in dropped }
            }
        }
    }
}
