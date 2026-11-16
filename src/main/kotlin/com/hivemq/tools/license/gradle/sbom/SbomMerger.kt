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
import java.math.BigInteger
import java.util.TreeMap
import java.util.TreeSet

/** One per-component SBOM to merge; [source] names it in messages, usually the file path. */
internal class SbomPart(val source: String, val bytes: ByteArray)

/** What the build knows about the product the parts ship in. */
internal data class ProductDetails(
    /** Name of the product; with [version] it forms the `bom-ref` of its top-level component, `<name>@<version>`. */
    val name: String,
    /** Version of the product, the second half of its `bom-ref`. */
    val version: String,
    /** File name of the deliverable, null when it has none, such as a Docker image. */
    val artifactFileName: String?,
    /** Name of the organisation that created the SBOM and ships the product, e.g. "HiveMQ GmbH". */
    val creatorName: String,
    /** Email address or http(s) URL of that organisation. */
    val creator: String,
    /** Names of the parts that must contribute an SBOM; a missing one makes the product incomplete. */
    val expectedParts: Set<String>,
)

/** The merged document before [SbomNormalizer] rewrites it, and why the product is incomplete, empty when it is not. */
internal class MergedSbom(val root: ObjectNode, val incompleteReasons: List<String>)

/**
 * Merges the SBOMs of the parts of one deliverable into the SBOM of the product, flat: every component keeps its
 * `bom-ref`, the product's top-level component depends on every part, and a library two parts share appears once.
 */
internal object SbomMerger {

    private val mapper = ObjectMapper()

    // the one value the schema allows for bomFormat
    private const val BOM_FORMAT = "CycloneDX"

    // how a reference into another document starts: urn:cdx:<serial number>/<version>#<bom-ref>
    private const val BOM_LINK_PREFIX = "urn:cdx:"

    // what makes a component the one it is, to tell apart two that share a document-local bom-ref
    private val identityFields = listOf("purl", "group", "name", "version")

    // the bom-ref of the product's top-level component
    private val ProductDetails.ref: String get() = "$name@$version"

    /**
     * A part that [read] accepted: [ref] and [name] belong to its top-level component, and its spec version is kept as
     * text for the output and as [majorMinor] numbers to find the highest.
     */
    private class Part(
        val source: String,
        val root: ObjectNode,
        val primary: ObjectNode,
        val ref: String,
        val name: String,
        val specVersion: String,
        val majorMinor: Pair<BigInteger, BigInteger>,
    )

    /**
     * One entry of a `hashes` list: the [node] to copy, and its digest in lower case, because the same hex digits in
     * another case are the same digest.
     */
    private class Hash(val node: JsonNode, val content: String)

    /**
     * A digest in lower case and the part that listed it first, for the message when another part lists a different
     * one.
     */
    private class Digest(val content: String, val source: String)

    /**
     * A value of one of the [identityFields] and the part that listed it first, for the message when another part lists
     * a different one.
     */
    private class Claim(val value: String, val source: String)

    /**
     * A merged component, the part it came from, the part each field [unite] copied from another entry came from, the
     * digest of each algorithm and the value of each identity field the parts have listed for it.
     */
    private class Owned(val source: String, val node: ObjectNode) {
        val fieldSources = mutableMapOf<String, String>()
        val digests = mutableMapOf<String, Digest>()
        val identity = mutableMapOf<String, Claim>()
    }

    /**
     * The values of `scope`, the strongest first, so that the smaller of two is the one that stands. A consumer assumes
     * [REQUIRED] for a component that states none.
     */
    private enum class Scope(val text: String) {
        REQUIRED("required"),
        OPTIONAL("optional"),
        EXCLUDED("excluded"),
    }

    /**
     * Merges [parts] into the SBOM of [product]. Throws [SbomException] on a creator that is neither an email address
     * nor an http(s) URL, a part that cannot be read or that uses the `bom-ref` of the product, a `bom-ref` defined for
     * two nodes, or parts that contradict each other.
     */
    fun merge(parts: List<SbomPart>, product: ProductDetails): MergedSbom {
        SbomEnricher.requireCreator(product.creator)
        // by source, so that of several unreadable parts the same one is reported whatever the order of the files
        val readParts = parts.sortedBy { it.source }.map(::read)
        // by name, then bom-ref, so that the merge does not depend on the order either; source settles a tie
        val sorted = readParts.sortedWith(compareBy({ it.name }, { it.ref }, { it.source }))

        val components = TreeMap<String, Owned>()
        val dependencies = TreeMap<String, TreeSet<String>>()
        // every top-level component first, so that a library of another part with the same bom-ref cannot replace it
        for (part in sorted) {
            components[part.ref]?.let { other ->
                throw SbomException("'${other.source}' and '${part.source}' describe the same part '${part.ref}'")
            }
            add(components, part.source, part.primary.deepCopy().put("type", "application"))
        }
        for (part in sorted) {
            (part.root.get("components") as? ArrayNode)?.forEach { component ->
                add(components, part.source, (component as? ObjectNode ?: return@forEach).deepCopy())
            }
            for ((ref, targets) in dependencyEntries(part.root)) {
                dependencies.getOrPut(ref) { TreeSet() }.addAll(targets)
            }
        }
        // after the union, so that the entry of another part for the same component cannot bring one back
        sorted.forEach { dropBuildSystemReference(components.getValue(it.ref).node) }
        // two entries would share the bom-ref of the product, and a part could make the product depend on itself;
        // read lets a graph name that bom-ref only through such a component, so no dependency check is needed
        components[product.ref]?.let { owner -> throw usesProductRef(owner.source, product) }
        requireUniqueNestedRefs(components, product)
        dependencies.getOrPut(product.ref) { TreeSet() }.addAll(sorted.map { it.ref })

        val missing = product.expectedParts - sorted.map { it.name }.toSet()
        val reasons = sorted.mapNotNull(::completeness) + missing.sorted().map { "no SBOM for part '$it'" }
        val root = assemble(product, sorted, components.values.map { it.node }, dependencies, reasons)
        return MergedSbom(root, reasons)
    }

    /**
     * Removes the `build-system` references of [component], a promoted top-level component, and the list when none is
     * left: they point at the CI run that built the part, which changes without the part changing.
     */
    private fun dropBuildSystemReference(component: ObjectNode) {
        val references = component.get("externalReferences") as? ArrayNode ?: return
        references.removeIf { it.get("type")?.asText() == "build-system" }
        if (references.isEmpty) component.remove("externalReferences")
    }

    /**
     * Throws [SbomException] when a bom-ref defined below one of the [components], by a license, an organization or an
     * occurrence for example, is the bom-ref of [product], of a component or of another such node: a bom-ref is unique
     * within a document, so two parts that use one for different nodes cannot share a product.
     */
    private fun requireUniqueNestedRefs(components: Map<String, Owned>, product: ProductDetails) {
        val nested = mutableMapOf<String, String>()
        for (owned in components.values) {
            for ((field, value) in owned.node.properties()) {
                val source = owned.fieldSources[field] ?: owned.source
                for (ref in definedRefs(value)) {
                    if (ref == product.ref) throw usesProductRef(source, product)
                    val previous = components[ref]?.source ?: nested[ref]
                    if (previous == null) {
                        nested[ref] = source
                        continue
                    }
                    throw SbomException(
                        if (previous == source) "'$previous' defines the bom-ref '$ref' twice"
                        else "'$previous' and '$source' define the bom-ref '$ref' for different nodes"
                    )
                }
            }
        }
    }

    /** The bom-refs that [node] and the nodes below it define; a text or an array defines none of its own. */
    private fun definedRefs(node: JsonNode): List<String> =
        listOfNotNull(node.get("bom-ref")?.takeIf { it.isTextual }?.asText()) + node.flatMap(::definedRefs)

    /** The failure for the part [source], which uses the `bom-ref` of [product] for a node of its own. */
    private fun usesProductRef(source: String, product: ProductDetails) =
        SbomException("'$source' uses the bom-ref '${product.ref}' of the product")

    /**
     * The entries of the `dependencies` of [root] as the merge reads them: those with a text `ref`, each with the text
     * targets of its `dependsOn`. Anything else in the list is ignored.
     */
    private fun dependencyEntries(root: ObjectNode): List<Pair<String, List<String>>> =
        (root.get("dependencies") as? ArrayNode)
            ?.mapNotNull { entry ->
                val ref = entry.get("ref")?.takeIf { it.isTextual }?.asText() ?: return@mapNotNull null
                val targets = (entry.get("dependsOn") as? ArrayNode)
                    ?.mapNotNull { it.takeIf { target -> target.isTextual }?.asText() }
                    .orEmpty()
                ref to targets
            }
            .orEmpty()

    /**
     * The document of [product]: its top-level component, then the [components] and [dependencies] the [parts]
     * contributed, at the highest spec version of the parts and complete unless [reasons] says otherwise.
     */
    private fun assemble(
        product: ProductDetails,
        parts: List<Part>,
        components: List<ObjectNode>,
        dependencies: Map<String, Set<String>>,
        reasons: List<String>,
    ): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("bomFormat", BOM_FORMAT)
        // the highest, because a part written for a later specification may carry fields an earlier one lacks
        val latest = parts.maxWithOrNull { a, b ->
            compareValuesBy(a.majorMinor, b.majorMinor, { it.first }, { it.second })
        }
        root.put("specVersion", latest?.specVersion ?: SbomEnricher.MINIMUM_SPEC_VERSION)
        root.put("version", 1)
        val metadata = root.putObject("metadata")
        // the normalizer replaces the value in place, so the timestamp keeps the first position as in CycloneDX
        metadata.put("timestamp", "")
        val primary = metadata.putObject("component")
        primary.put("type", "application")
        primary.put("bom-ref", product.ref)
        primary.put("name", product.name)
        primary.put("version", product.version)
        primary.set<JsonNode>("manufacturer", SbomEnricher.manufacturer(product.creatorName, product.creator))
        product.artifactFileName?.let { SbomEnricher.describeFile(primary, it) }
        // after the component, the order the enricher leaves in the metadata of one project
        metadata.set<JsonNode>("manufacturer", SbomEnricher.manufacturer(product.creatorName, product.creator))
        val componentList = root.putArray("components")
        components.forEach { componentList.add(it) }
        val dependencyList = root.putArray("dependencies")
        for ((ref, targets) in dependencies) {
            val entry = dependencyList.addObject().put("ref", ref)
            val list = entry.putArray("dependsOn")
            targets.forEach(list::add)
        }
        SbomEnricher.declareCompleteness(root, reasons)
        return root
    }

    /** Reads and checks [part]; every failure names its source. */
    private fun read(part: SbomPart): Part {
        val root = try {
            BomJson.read(part.bytes).also(::requireBomFormat).also(SbomEnricher::requireSpecVersion)
        } catch (e: SbomException) {
            throw SbomException("'${part.source}': ${e.message}")
        }
        val primary = (root.get("metadata") as? ObjectNode)?.get("component") as? ObjectNode
        val ref = primary?.get("bom-ref")?.takeIf { it.isTextual }?.asText()
        val name = primary?.get("name")?.takeIf { it.isTextual }?.asText()
        if (primary == null || ref == null || name == null) {
            throw SbomException("'${part.source}' has no top-level component with a bom-ref and a name")
        }
        // the merge would drop these lists, so a part that fills them would lose content without a word
        for (list in listOf("services", "formulation")) {
            val entries = root.get(list) as? ArrayNode
            if (entries != null && !entries.isEmpty) {
                throw SbomException("'${part.source}' has $list, which the merge does not carry")
            }
        }
        // the provides of an entry states a relation that the merge does not read, and so would drop
        val dependencies = root.get("dependencies") as? ArrayNode
        if (dependencies != null && dependencies.any { (it.get("provides") as? ArrayNode)?.isEmpty == false }) {
            throw SbomException(
                "'${part.source}' has a dependency entry with provides, which the merge does not carry"
            )
        }
        val listed = (root.get("components") as? ArrayNode)?.filterIsInstance<ObjectNode>().orEmpty()
        val carried = listOf(primary) + listed
        // a component nested in another would skip the union by bom-ref, so the merge takes none
        if (carried.any { (it.get("components") as? ArrayNode)?.isEmpty == false }) {
            throw SbomException(
                "'${part.source}' has a component with nested components, which the merge does not flatten"
            )
        }
        // the components are the only nodes of a part the merge keeps, so a reference to any other node, a tool
        // defined in metadata.tools for one, would lead nowhere
        val refs = carried.mapNotNull { it.get("bom-ref")?.takeIf { bomRef -> bomRef.isTextual }?.asText() }.toSet()
        carried.flatMap(::evidenceTools).firstOrNull { it !in refs }?.let { tool ->
            throw SbomException(
                "'${part.source}' has evidence that references the tool '$tool', which the merge does not carry"
            )
        }
        val referenced = dependencyEntries(root).flatMap { (entryRef, targets) -> listOf(entryRef) + targets }
        referenced.firstOrNull { it !in refs }?.let { target ->
            throw SbomException(
                "'${part.source}' has a dependency entry that references '$target', which the merge does not carry"
            )
        }
        // requireSpecVersion passed, so the text is a 1.x version
        val text = root.get("specVersion").asText()
        return Part(part.source, root, primary, ref, name, text, requireNotNull(SbomEnricher.majorMinor(text)))
    }

    /**
     * The bom-refs the `evidence` of [node] and of the components below it, its pedigree for example, name as tools,
     * from a list of identity entries as well as from the single entry of the deprecated form; a BOM-Link to another
     * document is left out.
     */
    private fun evidenceTools(node: JsonNode): List<String> {
        val identity = (node.get("evidence") as? ObjectNode)?.get("identity")
        val entries = when {
            identity == null -> emptyList()
            identity.isArray -> identity.toList()
            else -> listOf(identity)
        }
        val own = entries.flatMap { entry -> (entry.get("tools") as? ArrayNode)?.toList().orEmpty() }
            .mapNotNull { it.takeIf { tool -> tool.isTextual }?.asText() }
            .filterNot { it.startsWith(BOM_LINK_PREFIX) }
        return own + node.flatMap(::evidenceTools)
    }

    /** Throws [SbomException] unless [root] declares the `bomFormat` CycloneDX, which [BomJson.read] does not check. */
    private fun requireBomFormat(root: ObjectNode) {
        val format = root.get("bomFormat")?.takeIf { it.isTextual }?.asText()
            ?: throw SbomException("The BOM declares no bomFormat")
        if (format != BOM_FORMAT) throw SbomException("The BOM declares bomFormat '$format', not $BOM_FORMAT")
    }

    /**
     * Adds [component] from the part [source], or unites it with the entry of the same bom-ref added before, which
     * stays and gains what only [component] has; see [unite]. Fails when the two name different components (their
     * `purl`, `group`, `name` or `version` differ) or list different digests for one hash algorithm, or one entry does.
     */
    private fun add(components: TreeMap<String, Owned>, source: String, component: ObjectNode) {
        val ref = component.get("bom-ref")?.takeIf { it.isTextual }?.asText()
            ?: throw SbomException("'$source' has a component without a bom-ref")
        val owned = components.getOrPut(ref) { Owned(source, component) }
        for (field in identityFields) {
            val value = component.get(field)?.takeIf { it.isTextual }?.asText() ?: continue
            val seen = owned.identity.getOrPut(field) { Claim(value, source) }
            if (seen.value != value) {
                throw SbomException("'$ref' names different components in '${seen.source}' and '$source'")
            }
        }
        val incoming = hashes(component)
        // an entry that lists one algorithm with different digests is broken on its own, whatever other parts list
        for ((algorithm, listed) in incoming) {
            if (listed.map { it.content }.distinct().size > 1) {
                throw SbomException("'$source' lists different $algorithm digests for '$ref'")
            }
        }
        // each algorithm has one digest in the entry now, so its last entry speaks for it and is the one to copy
        val perAlgorithm = incoming.mapValues { it.value.last() }
        for ((algorithm, hash) in perAlgorithm) {
            val seen = owned.digests[algorithm]
            if (seen != null && seen.content != hash.content) {
                throw SbomException("'$ref' has different hashes in '${seen.source}' and '$source'")
            }
        }
        val added = perAlgorithm.filterKeys { it !in owned.digests }
        for ((algorithm, hash) in added) owned.digests[algorithm] = Digest(hash.content, source)
        if (owned.node !== component) unite(owned, source, component, added.values)
    }

    /**
     * Gives the node of [owned] the fields only [other] has, noting the part [source] they came from, and the `hashes`
     * entries of the [added] algorithms. Where both have `properties`, [addPropertiesByName] unites them, and
     * [strongestScope] picks the `scope`, left out when `required`. Any other field both have stays as it is.
     */
    private fun unite(owned: Owned, source: String, other: ObjectNode, added: Collection<Hash>) {
        val kept = owned.node
        val scope = strongestScope(kept, other)
        for ((name, value) in other.properties()) {
            when {
                // the hashes go by algorithm below, because both entries may list some
                name == "hashes" -> Unit
                !kept.has(name) -> {
                    kept.set<JsonNode>(name, value.deepCopy())
                    owned.fieldSources[name] = source
                }
                name == "properties" -> addPropertiesByName(kept.get(name), value)
            }
        }
        // after the loop, which copies the scope that only [other] states, so that the strongest one has the last word
        when (scope) {
            null -> Unit
            Scope.REQUIRED -> kept.remove("scope")
            else -> kept.put("scope", scope.text)
        }
        if (added.isEmpty()) return
        val list = when (val existing = kept.get("hashes")) {
            null -> kept.putArray("hashes")
            is ArrayNode -> existing
            else -> return
        }
        added.forEach { list.add(it.node.deepCopy()) }
    }

    /**
     * The scope that stands when [kept] and [other] are united: the strongest of the two. Null when either states a
     * `scope` that is none of the three texts, which is left to the rule for any other field.
     */
    private fun strongestScope(kept: ObjectNode, other: ObjectNode): Scope? {
        val first = scopeOf(kept) ?: return null
        val second = scopeOf(other) ?: return null
        return minOf(first, second)
    }

    /** The scope [component] states, required when it states none, null when it is none of the three texts. */
    private fun scopeOf(component: ObjectNode): Scope? {
        val stated = component.get("scope") ?: return Scope.REQUIRED
        return Scope.entries.firstOrNull { stated.isTextual && stated.asText() == it.text }
    }

    /**
     * Appends to the `properties` list [kept] the entries of [other] whose name [kept] lists no entry for, in the order
     * of [other]. A name [other] lists twice is appended twice, because a property list may repeat a name. A value
     * that is not a list stays as it is.
     */
    private fun addPropertiesByName(kept: JsonNode, other: JsonNode) {
        if (kept !is ArrayNode || other !is ArrayNode) return
        val names = kept.mapNotNull(::propertyName).toSet()
        other.filter { property -> propertyName(property).let { it != null && it !in names } }
            .forEach { kept.add(it.deepCopy()) }
    }

    /** The name of [property], null when it has none as text. */
    private fun propertyName(property: JsonNode): String? = property.get("name")?.takeIf { it.isTextual }?.asText()

    /** The entries of each algorithm [component] lists a digest for, in the order of the list. */
    private fun hashes(component: ObjectNode): Map<String, List<Hash>> =
        (component.get("hashes") as? ArrayNode)
            ?.mapNotNull { hash ->
                val algorithm = hash.get("alg")?.takeIf { it.isTextual }?.asText()
                val content = hash.get("content")?.takeIf { it.isTextual }?.asText()
                if (algorithm != null && content != null) algorithm to Hash(hash, content.lowercase()) else null
            }
            ?.groupBy({ it.first }, { it.second })
            .orEmpty()

    /**
     * Why [part] keeps the product from being complete, null when the `compositions` of its SBOM that state an
     * `aggregate` all say `complete` and their `dependencies` together list the top-level component and every `ref` and
     * `dependsOn` target of the part's `dependencies`.
     */
    private fun completeness(part: Part): String? {
        val compositions = part.root.get("compositions") as? ArrayNode ?: return noClaim(part)
        val stated = compositions.filter { it.get("aggregate")?.isTextual == true }
        if (stated.isEmpty()) return noClaim(part)
        if (stated.any { it.get("aggregate").asText() != "complete" }) {
            return "the SBOM of '${part.name}' declares its dependency graph incomplete"
        }
        val lists = stated.mapNotNull { it.get("dependencies") as? ArrayNode }
        if (lists.isEmpty()) return noClaim(part)
        val covered = lists.flatMap { list -> list.filter { it.isTextual }.map { it.asText() } }.toSet()
        // the top-level component must be covered even when the part lists no entry for it, and so must a target
        // that has none either
        val refs = dependencyEntries(part.root).flatMap { (ref, targets) -> listOf(ref) + targets } + part.ref
        if (!covered.containsAll(refs)) {
            return "the SBOM of '${part.name}' declares only part of its dependency graph complete"
        }
        return null
    }

    /**
     * Why [part] keeps the product from being complete when its SBOM states no aggregate, or its `complete`
     * compositions list no dependencies.
     */
    private fun noClaim(part: Part) =
        "the SBOM of '${part.name}' does not declare whether its dependency graph is complete"
}
