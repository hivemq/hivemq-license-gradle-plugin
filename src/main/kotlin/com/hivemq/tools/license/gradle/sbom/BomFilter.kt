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
import com.hivemq.tools.license.gradle.LicenseResolver
import com.hivemq.tools.license.gradle.OwnModules
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Removes components, by module or by predicate, from a BOM tree with the references to them from `dependsOn`,
 * `provides`, vulnerabilities, compositions and annotations, or only their edges; [referencesTo] finds what else still
 * names them. It handles nested components, although the CycloneDX Gradle plugin writes flat BOMs: the tasks read any
 * BOM a build points them at.
 */
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

    /** The `bom-ref` of a BOM [component], or null for one without. */
    fun refOf(component: JsonNode): String? = component.get("bom-ref")?.asText()

    /** The bom-ref of [node] as non-empty text; an empty one refers to nothing, so it counts as missing. */
    fun nonEmptyRefOf(node: JsonNode): String? =
        node.get("bom-ref")?.takeIf { it.isTextual && it.asText().isNotEmpty() }?.asText()

    /**
     * Whether to keep a [composition] the plugin changed, or wrote its own compositions next to: false when it names
     * nothing and has no non-empty bom-ref, so the caller drops it; one only its bom-ref keeps is set to the aggregate
     * `not_specified`, the schema's default.
     */
    fun keepComposition(composition: ObjectNode): Boolean {
        val namesSomething = compositionLists.any { field ->
            // a value of another type is not the plugin's to judge, so it counts as naming something
            composition.get(field)?.let { it !is ArrayNode || !it.isEmpty } == true
        }
        if (namesSomething) return true
        if (nonEmptyRefOf(composition) == null) return false
        composition.put("aggregate", "not_specified")
        return true
    }

    /** Removes every entry of [list] equal to an earlier one, as the schema's `uniqueItems` wants. */
    fun dropDuplicates(list: ArrayNode) {
        val seen = hashSetOf<JsonNode>()
        list.removeIf { !seen.add(it) }
    }

    /** Whether a BOM [component] declares at least one license, as the POM of a published module does. */
    fun declaresLicense(component: JsonNode): Boolean = (component.get("licenses") as? ArrayNode)?.any { entry ->
        // an entry with nothing in it, as another producer may write, says nothing
        val license = entry.get("license")
        listOf("id", "name", "url").any { !license?.get(it)?.textValue().isNullOrBlank() } ||
            !entry.get("expression")?.textValue().isNullOrBlank()
    } == true

    /**
     * How CycloneDX failed to complete the Maven metadata of a BOM [component] (POM, parent or model), so that its
     * license, if any, is unknown; null when it succeeded.
     */
    fun unresolvedMetadata(component: JsonNode): String? = (component.get("properties") as? ArrayNode)
        ?.firstOrNull { it.get("name")?.asText() == LicenseResolver.METADATA_UNRESOLVED_PROPERTY }
        ?.get("value")?.asText()

    /** Whether a BOM [component] may be a public module: it declares a license, or its metadata is incomplete. */
    fun mayBePublic(component: JsonNode): Boolean = declaresLicense(component) || unresolvedMetadata(component) != null

    /** `group:name` of every component of [root] that [own] classifies as [kind], nested ones included. */
    fun modulesOfKind(root: ObjectNode, own: OwnModules, kind: OwnModules.Kind): Set<String> = components(root)
        .filter { component ->
            val coordinates = coordinatesOf(component) ?: return@filter false
            own.classify(coordinates, isProject(component), mayBePublic(component)) == kind
        }
        .mapTo(sortedSetOf(), ::moduleIdOf)

    /**
     * Leaves in [root] what ships with the artifact: the internal modules of [own] go, the components nested in them
     * taking their place, then every component of the [provided] modules, or nested in one, except those the predicate
     * [keepProvided] builds from the tree as it stands by then (a hidden provided module has let its dependencies move
     * up) accepts, which stay where they are. Returns those, the provided components that stay, by identity.
     */
    fun keepShipped(
        root: ObjectNode,
        own: OwnModules,
        provided: Set<String>,
        keepProvided: (root: ObjectNode, isProvided: (ObjectNode) -> Boolean) -> (ObjectNode) -> Boolean,
    ): Set<ObjectNode> {
        // what is nested in a provided component is provided too, also once a hidden parent lets it move up
        val providedInside = nestedIn(root) { moduleIdOf(it) in provided }
        val isProvided = { component: ObjectNode -> moduleIdOf(component) in provided || component in providedInside }
        dropModules(root, modulesOfKind(root, own, OwnModules.Kind.INTERNAL)) { true }
        val keep = keepProvided(root, isProvided)
        dropComponents(root, { isProvided(it) && !keep(it) }, keep)
        return components(root).filterTo(Collections.newSetFromMap(IdentityHashMap())) { isProvided(it) && keep(it) }
    }

    /**
     * The components nested, at any depth, in those [parents] accepts, by identity: a BOM may lack bom-refs, and a
     * component keeps its identity when a filter moves it to another place.
     */
    fun nestedIn(root: ObjectNode, parents: (ObjectNode) -> Boolean): Set<ObjectNode> =
        components(root).filter(parents).flatMapTo(Collections.newSetFromMap(IdentityHashMap()), ::nested)

    /**
     * The `bom-ref`s of the components [inside] accepts, or nested in one, that a component outside them,
     * `metadata.component` included, depends on directly: the first component on each path into them, where an SBOM
     * of the environment that provides them continues. A dependant inside them does not count, it goes with them.
     */
    fun directlyDependedOn(root: ObjectNode, inside: (ObjectNode) -> Boolean): Set<String> {
        val dependencies = root.get("dependencies") as? ArrayNode ?: return emptySet()
        val insideRefs = (components(root).filter(inside) + nestedIn(root, inside)).mapNotNullTo(hashSetOf(), ::refOf)
        val identified = sortedSetOf<String>()
        for (dependency in dependencies) {
            if (dependency.get("ref")?.asText() in insideRefs) continue
            for (target in dependency.get("dependsOn") as? ArrayNode ?: continue) {
                target.asText().takeIf { it in insideRefs }?.let(identified::add)
            }
        }
        return identified
    }

    /**
     * Changes [root] in place: every component [drop] accepts goes, nested or not, and takes its nested ones along,
     * except those [keep] accepts, which take its place as dependencies of its dependants; `metadata.component` itself
     * stays. A `dependsOn` reference to a dropped component leads to the next ones that stay; one from a `provides`
     * list, a vulnerability, an annotation or a composition goes, as do the last three left empty ([keepComposition]).
     */
    fun dropComponents(root: ObjectNode, drop: (ObjectNode) -> Boolean, keep: (ObjectNode) -> Boolean = { false }) {
        val before = refsBelow(root)
        val movedUp = mutableMapOf<String, MutableList<String>>()
        val standIns = IdentityHashMap<ObjectNode, String>()
        removeComponents(root.get("components"), drop, keep, movedUp, standIns)
        topLevel(root)?.let { top ->
            removeComponents(top.get("components"), drop, keep, movedUp, standIns)
            if ((top.get("components") as? ArrayNode)?.isEmpty == true) top.remove("components")
        }
        val dropped = before - refsBelow(root)
        if (dropped.isEmpty()) return
        containAsDependencies(root, movedUp)
        relinkDependencies(root, dropped)
        dropReferences(root, dropped)
    }

    /** [dropComponents] for every version of [modules], by `group:name`. */
    fun dropModules(root: ObjectNode, modules: Set<String>, keep: (ObjectNode) -> Boolean = { false }) {
        if (modules.isEmpty()) return
        dropComponents(root, { moduleIdOf(it) in modules }, keep)
    }

    /**
     * Removes the components nested, at any depth, in those [parents] accepts, as [dropComponents] would: the host
     * provides those modules whole, so nothing inside them ships with this artifact.
     */
    fun dropNested(root: ObjectNode, parents: (ObjectNode) -> Boolean) {
        val nested = nestedIn(root, parents)
        if (nested.isEmpty()) return
        dropComponents(root, { it in nested })
    }

    /**
     * Gives the components with the given [refs] an empty `dependsOn` list, an entry of their own if they had none,
     * and hands their targets outside [refs] to their dependants, so every other component stays reachable while those
     * depend on nothing here. An edge between two of them goes: each has a dependant of its own, [directlyDependedOn].
     */
    fun detachComponents(root: ObjectNode, refs: Set<String>) {
        if (refs.isEmpty()) return
        val detached = components(root).mapNotNullTo(linkedSetOf(), ::refOf).filter { it in refs }.toSet()
        val dependencies = root.get("dependencies") as? ArrayNode ?: return
        if (detached.isEmpty()) return
        val below = keptBelow(dependencies, detached)
        val entries = mutableSetOf<String>()
        for (dependency in dependencies) {
            val ref = dependency.get("ref")?.asText()
            if (ref in detached) {
                // explicitly nothing, which CycloneDX tells apart from a component without an entry
                (dependency as ObjectNode).putArray("dependsOn")
                ref?.let(entries::add)
                continue
            }
            val dependsOn = dependency.get("dependsOn") as? ArrayNode ?: continue
            if (dependsOn.none { it.asText() in detached }) continue
            val targets = linkedSetOf<JsonNode>()
            for (entry in dependsOn) {
                targets.add(entry)
                below[entry.asText()]?.filterTo(targets) { it.asText() != ref }
            }
            dependsOn.removeAll()
            targets.forEach(dependsOn::add)
        }
        for (ref in detached - entries) {
            addEntry(dependencies, ref).putArray("dependsOn")
        }
    }

    /** Removes the properties whose name starts with [prefix] from the components with the given [refs]. */
    fun removeProperties(root: ObjectNode, refs: Set<String>, prefix: String) {
        for (component in components(root)) {
            if (refOf(component)?.let { it in refs } != true) continue
            val properties = component.get("properties") as? ArrayNode ?: continue
            properties.removeIf { it.get("name")?.asText()?.startsWith(prefix) == true }
            if (properties.isEmpty) component.remove("properties")
        }
    }

    /** Every `bom-ref` in [root], so that a caller can tell, before and after a filter or the enricher, what went. */
    fun refsIn(root: JsonNode): Set<String> = buildSet { collectRefs(root, this) }

    /**
     * Every non-empty text in [root] equal to one of [refs], by its JSON pointer: once something removed them, what
     * still names it sits in a place nothing rewrites, such as a declaration's claim or a formulation workflow. An
     * empty text names nothing, like an empty `bom-ref`.
     */
    fun referencesTo(root: JsonNode, refs: Set<String>): Map<String, String> = buildMap {
        fun walk(node: JsonNode, pointer: String) {
            when {
                node.isTextual -> node.asText().takeIf { it.isNotEmpty() && it in refs }?.let { put(pointer, it) }
                node.isObject -> node.properties().forEach { (name, child) ->
                    walk(child, "$pointer/" + name.replace("~", "~0").replace("/", "~1"))
                }
                node.isArray -> node.forEachIndexed { index, child -> walk(child, "$pointer/$index") }
            }
        }
        walk(root, "")
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

    // every bom-ref below the components, those of their licenses and other nested objects included, so that a
    // reference to anything inside a dropped component is cleaned up as well
    private fun refsBelow(root: ObjectNode): Set<String> = buildSet {
        components(root).forEach { collectRefs(it, this) }
    }

    private fun collectRefs(node: JsonNode, into: MutableSet<String>) {
        if (node.isObject) node.get("bom-ref")?.takeIf { it.isTextual }?.let { into.add(it.asText()) }
        node.forEach { collectRefs(it, into) }
    }

    /**
     * Removes what [drop] accepts from [array] and the nested arrays; those [keep] accepts inside a dropped one take
     * its place, and [movedUp] records their refs by its ref or, for one without, by its stand-in in [standIns].
     */
    private fun removeComponents(
        array: JsonNode?,
        drop: (ObjectNode) -> Boolean,
        keep: (ObjectNode) -> Boolean,
        movedUp: MutableMap<String, MutableList<String>>,
        standIns: IdentityHashMap<ObjectNode, String>,
    ) {
        val components = array as? ArrayNode ?: return
        val kept = mutableListOf<JsonNode>()
        var moved = false
        for (component in components) {
            if (component !is ObjectNode || !drop(component)) {
                kept.add(component)
                continue
            }
            val rescued = mutableListOf<ObjectNode>()
            rescue(component, refOf(component) ?: standIns[component], keep, rescued, movedUp, standIns)
            if (rescued.isEmpty()) continue
            moved = true
            kept.addAll(rescued)
        }
        components.removeAll()
        kept.forEach(components::add)
        // a component that moved up may match as well, so the array is walked again until none does
        if (moved && kept.any { it is ObjectNode && drop(it) }) {
            return removeComponents(components, drop, keep, movedUp, standIns)
        }
        for (component in components) {
            removeComponents(component.get("components"), drop, keep, movedUp, standIns)
            // CycloneDX writes no empty list, so none is left behind either
            if ((component.get("components") as? ArrayNode)?.isEmpty == true) {
                (component as ObjectNode).remove("components")
            }
        }
    }

    /**
     * Collects [into] the components nested in [component] that [keep] accepts, at any depth below ones it does not, in
     * document order. With an [under], the ref or stand-in of the component [removeComponents] dropped, records their
     * refs in [movedUp] under it, and makes it the stand-in in [standIns] of one without a ref, for when that one is
     * dropped in turn.
     */
    private fun rescue(
        component: ObjectNode,
        under: String?,
        keep: (ObjectNode) -> Boolean,
        into: MutableList<ObjectNode>,
        movedUp: MutableMap<String, MutableList<String>>,
        standIns: IdentityHashMap<ObjectNode, String>,
    ) {
        for (child in component.get("components") as? ArrayNode ?: return) {
            if (child !is ObjectNode) continue
            if (!keep(child)) {
                rescue(child, under, keep, into, movedUp, standIns)
                continue
            }
            into.add(child)
            if (under == null) continue
            val ref = refOf(child)
            if (ref != null) movedUp.getOrPut(under) { mutableListOf() }.add(ref) else standIns[child] = under
        }
    }

    // a component that moved up was part of the dropped one, so the dropped one's dependants depend on it from now on
    private fun containAsDependencies(root: ObjectNode, movedUp: Map<String, List<String>>) {
        if (movedUp.isEmpty()) return
        val dependencies = root.get("dependencies") as? ArrayNode ?: return
        for ((parent, children) in movedUp) {
            val entry = dependencies.firstOrNull { it.get("ref")?.asText() == parent } as? ObjectNode
                ?: addEntry(dependencies, parent)
            val dependsOn = entry.get("dependsOn") as? ArrayNode ?: entry.putArray("dependsOn")
            val present = dependsOn.mapTo(hashSetOf()) { it.asText() }
            children.filterNot { it in present }.forEach(dependsOn::add)
        }
    }

    // CycloneDX writes the entries sorted by ref and the normalizer relies on it, so a new entry goes to its place
    private fun addEntry(dependencies: ArrayNode, ref: String): ObjectNode {
        val index = dependencies.indexOfFirst { (it.get("ref")?.asText() ?: "") > ref }
        val entry = if (index < 0) dependencies.addObject() else dependencies.insertObject(index)
        return entry.put("ref", ref)
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
     * through other such refs, in the order they first appear; each ref is walked once, so a cycle ends.
     */
    private fun keptBelow(dependencies: ArrayNode, through: Set<String>): Map<String, Set<JsonNode>> {
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
                    if (ref !in through) reached.add(entry)
                    if (ref in through && walked.add(ref)) queue.add(ref)
                }
            }
            reached
        }
    }

    /** Removes the entries of [composition]'s [field] naming one of [refs], the list when emptied; true if any went. */
    private fun removeRefs(composition: ObjectNode, field: String, refs: Set<String>): Boolean {
        val list = composition.get(field) as? ArrayNode ?: return false
        val size = list.size()
        list.removeIf { it.asText() in refs }
        if (list.size() == size) return false
        if (list.isEmpty) composition.remove(field)
        return true
    }

    // a reference from outside the dependency graph leads nowhere once its component is gone
    private fun dropReferences(root: ObjectNode, dropped: Set<String>) {
        val removedVulnerabilities = mutableSetOf<String>()
        (root.get("vulnerabilities") as? ArrayNode)?.removeIf { vulnerability ->
            val affects = vulnerability.get("affects") as? ArrayNode ?: return@removeIf false
            val affectedSomething = affects.size() > 0
            affects.removeIf { it.get("ref")?.asText() in dropped }
            // a vulnerability of dropped components only has nothing left to affect
            val gone = affectedSomething && affects.isEmpty
            if (gone) vulnerability.get("bom-ref")?.asText()?.let(removedVulnerabilities::add)
            gone
        }
        (root.get("compositions") as? ArrayNode)?.let { compositions ->
            compositions.removeIf { composition ->
                if (composition !is ObjectNode) return@removeIf false
                val pruned = listOf(
                    removeRefs(composition, "assemblies", dropped),
                    removeRefs(composition, "dependencies", dropped),
                    removeRefs(composition, "vulnerabilities", removedVulnerabilities),
                ).any { it }
                // one left naming nothing would state its aggregate about nothing; one the filter did not change stays
                pruned && !keepComposition(composition)
            }
            // pruning can leave two equal
            dropDuplicates(compositions)
        }
        // an annotation may be about a vulnerability or another annotation as well as about a component
        val removedRefs = (dropped + removedVulnerabilities).toMutableSet()
        val annotations = root.get("annotations") as? ArrayNode ?: return
        do {
            val removedAnnotations = mutableSetOf<String>()
            annotations.removeIf { annotation ->
                val subjects = annotation.get("subjects") as? ArrayNode ?: return@removeIf false
                val annotatedSomething = subjects.size() > 0
                subjects.removeIf { it.asText() in removedRefs }
                // an annotation of dropped subjects only has nothing left to be about
                val gone = annotatedSomething && subjects.isEmpty
                if (gone) annotation.get("bom-ref")?.asText()?.let(removedAnnotations::add)
                gone
            }
            // once more when an annotation went, for the ones that were about it
        } while (removedRefs.addAll(removedAnnotations))
    }

    // the fields listing what a composition makes a statement about
    private val compositionLists = listOf("assemblies", "dependencies", "vulnerabilities")
}
