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
    /**
     * Names of the parts that host the others, such as the broker in an image of the platform: only what such a part
     * describes fills what a part that is no host identifies. Each has to be the name of a part or of an expected part.
     */
    val hostParts: Set<String> = emptySet(),
    /**
     * Module id (`group:name`) to the conclusion the product states for a component whose parts conclude differently,
     * for every version of the module: the text of the expression one part concludes, or the ids of the license objects
     * it concludes, separated by commas in any order, so a license name that holds a comma cannot be given.
     */
    val licenseConflicts: Map<String, String> = emptyMap(),
    /**
     * Why components the parts identify stay undescribed, such as extensions that run on a broker another deliverable
     * ships; empty when the build declares no such gap. Only whether it is empty counts: one reason covers every
     * identified component no host part describes.
     */
    val declaredIdentifiedGapReasons: List<String> = emptyList(),
)

/**
 * The merged document before [SbomNormalizer] rewrites it, and why the product is incomplete: empty when it is
 * complete, or when its only gap is the one [ProductDetails.declaredIdentifiedGapReasons] declares.
 */
internal class MergedSbom(val root: ObjectNode, val incompleteReasons: List<String>)

/**
 * A part that [SbomMerger] read and accepted, its top-level component named [ref] and [name], its spec version kept as
 * text for the output and as [majorMinor] numbers to find the highest.
 */
internal class ReadPart(
    /** Names the part in messages, usually the file path. */
    val source: String,
    /** The document of the part. */
    val root: ObjectNode,
    /** Its top-level component, `metadata.component`. */
    val primary: ObjectNode,
    /** The non-empty `bom-ref` of [primary]. */
    val ref: String,
    /** The name of [primary], by which `expectedParts` and `hostParts` name the part. */
    val name: String,
    /** The `specVersion` of the document, a 1.x version from 1.6 on. */
    val specVersion: String,
    /** Major and minor number of [specVersion]. */
    val majorMinor: Pair<BigInteger, BigInteger>,
    /** The `dependencies` entries as the merge reads them: each text `ref` with the text targets of its `dependsOn`. */
    val entries: List<Pair<String, List<String>>>,
)

/**
 * Merges the SBOMs of the parts of one deliverable into the SBOM of the product, flat: every component keeps its
 * `bom-ref`, the product's top-level component depends on every part, and a library two parts share appears once. A
 * component a part only identifies gives way to the one other version of it that a host part describes; what the parts
 * leave open, and so what the merge changes for it, is the business of [ProductCompleteness].
 */
internal object SbomMerger {

    private val mapper = ObjectMapper()

    // the one value the schema allows for bomFormat
    private const val BOM_FORMAT = "CycloneDX"

    // how a reference into another document starts: urn:cdx:<serial number>/<version>#<bom-ref>
    private const val BOM_LINK_PREFIX = "urn:cdx:"

    // how a licenseConflicts value gives a conclusion, for the messages that ask for one
    private const val CONCLUSION_FORMS =
        "an expression as its text, license objects as their ids separated by commas in any order"

    // what makes a component the one it is, to tell apart two that share a document-local bom-ref
    private val identityFields = listOf("purl", "group", "name", "version")

    // the bom-ref of the product's top-level component
    private val ProductDetails.ref: String get() = "$name@$version"

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
     * What one [entry] for a component, from the part [source], concludes: the [ids] of its license objects, the `id`
     * or else the `name` of each, and its [expressions]. The product takes the whole `licenses` list of [entry] when
     * that conclusion stands.
     */
    private class Conclusion(
        val ids: Set<String>,
        val expressions: Set<String>,
        val source: String,
        val entry: ObjectNode,
    ) {
        /** The conclusion as the messages write it, which tells license objects from an expression of the same text. */
        val text: String = listOfNotNull(
            ids.singleOrNull()?.let { "the license $it" },
            ids.takeIf { it.size > 1 }?.let { "the licenses ${it.joinToString(", ")}" },
        ).plus(expressions.map { "the expression $it" }).joinToString(" and ")

        /** Whether [other] concludes the same license objects and expressions, in whatever order it lists them. */
        fun sameAs(other: Conclusion): Boolean = ids == other.ids && expressions == other.expressions

        /**
         * Whether [value], from `licenseConflicts`, gives this conclusion: the text of its one expression, or the ids
         * of its license objects separated by commas, in any order.
         */
        fun matches(value: String): Boolean = when {
            expressions.isEmpty() -> value.split(',').mapTo(sortedSetOf()) { it.trim() } == ids
            ids.isEmpty() -> value.trim() == expressions.singleOrNull()
            else -> false
        }
    }

    /**
     * A merged component, the part it came from, the part each field [unite] copied from another entry came from, the
     * digest of each algorithm and the value of each identity field the parts have listed for it, the conclusion of
     * every entry that states one, the first disagreement about a digest, which [requireAgreeingHashes] reports, and
     * whether an entry lacks the host label, which [removeDisputedLabels] then takes off.
     */
    private class Owned(val source: String, val node: ObjectNode) {
        val fieldSources = mutableMapOf<String, String>()
        val digests = mutableMapOf<String, Digest>()
        val identity = mutableMapOf<String, Claim>()
        val conclusions = mutableListOf<Conclusion>()
        var hashConflict: String? = null
        var listedWithoutLabel = false
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
     * two nodes, parts that contradict each other, a [ProductDetails.hostParts] name that is neither a part nor
     * expected, a [ProductDetails.licenseConflicts] entry that settles no conflict, and a
     * [ProductDetails.declaredIdentifiedGapReasons] without the gap it declares.
     */
    fun merge(parts: List<SbomPart>, product: ProductDetails): MergedSbom {
        SbomEnricher.requireCreator(product.creator)
        // by source, so that of several unreadable parts the same one is reported whatever the order of the files
        val readParts = parts.sortedBy { it.source }.map(::read)
        // by name, then bom-ref, so that the merge does not depend on the order either; source settles a tie
        val sorted = readParts.sortedWith(compareBy({ it.name }, { it.ref }, { it.source }))
        requireHostParts(sorted, product)

        val components = TreeMap<String, Owned>()
        val promoted = sorted.mapTo(hashSetOf()) { it.ref }
        // every top-level component first, so that a library of another part with the same bom-ref cannot replace it
        for (part in sorted) {
            components[part.ref]?.let { other ->
                throw SbomException(
                    "'${other.source}' and '${part.source}' describe the same part '${part.ref}'; pass one SBOM " +
                        "per part"
                )
            }
            val application = part.primary.deepCopy().put("type", "application")
            dropBuildSystemReference(application)
            add(components, part.source, application)
        }
        for (part in sorted) {
            (part.root.get("components") as? ArrayNode)?.forEach { component ->
                val copy = (component as? ObjectNode ?: return@forEach).deepCopy()
                if (copy.get("bom-ref")?.asText() in promoted) dropBuildSystemReference(copy)
                add(components, part.source, copy)
            }
        }
        // two entries would share the bom-ref of the product, and a part could make the product depend on itself;
        // read lets a graph name that bom-ref only through such a component, so no dependency check is needed
        components[product.ref]?.let { owner -> throw usesProductRef(owner.source, product) }

        val purls = components.mapNotNull { (ref, owned) -> owned.identity["purl"]?.let { ref to it.value } }.toMap()
        val gaps = ProductCompleteness.assess(sorted, product, purls)
        val dependencies = mergeDependencies(sorted, gaps.replacements)
        dependencies.getOrPut(product.ref) { TreeSet() }.addAll(sorted.map { it.ref })
        val replaced = gaps.replacements.flatMapTo(sortedSetOf()) { it.keys }
        val keptOutside = dropUnreferenced(components, dependencies, replaced)
        // after the drop, so that a component the product leaves out neither needs licenseConflicts nor clashes
        requireAgreeingHashes(components)
        concludeLicenses(components, product.licenseConflicts)
        requireUniqueNestedRefs(components, product)

        val declared = product.declaredIdentifiedGapReasons.isNotEmpty()
        // another gap may hide identified components, such as one over a part's whole graph or a missing part
        if (declared && gaps.undescribed.isEmpty() && keptOutside.isEmpty() && gaps.reasons.isEmpty()) {
            throw SbomException(
                "declaredIdentifiedGapReasons declares a gap of identified components that no host part describes, " +
                    "but the product has none; remove the declaration"
            )
        }
        // the build knows the gap it declared, the replaced components the product keeps included
        val kept = if (declared) emptyList() else keptOutside.map { keptReason(it, sorted, gaps.replacements) }
        removeDisputedLabels(components)
        val identified = gaps.undescribed + keptOutside
        val root = assemble(product, sorted, components.values.map { it.node }, dependencies, gaps.reasons, identified)
        return MergedSbom(root, gaps.reported + kept)
    }

    /** Reads and checks [part]; every failure names its source. */
    private fun read(part: SbomPart): ReadPart {
        val root = try {
            BomJson.read(part.bytes).also(::requireBomFormat).also(SbomEnricher::requireSpecVersion)
        } catch (e: SbomException) {
            throw SbomException("'${part.source}': ${e.message}")
        }
        val primary = (root.get("metadata") as? ObjectNode)?.get("component") as? ObjectNode
        // an empty bom-ref refers to nothing, and the schema rejects it
        val ref = primary?.let(BomFilter::nonEmptyRefOf)
        val name = primary?.get("name")?.takeIf { it.isTextual }?.asText()
        if (primary == null || ref == null || name == null) {
            throw SbomException(
                "'${part.source}' has no top-level component with a bom-ref and a name; give its metadata.component " +
                    "both, as the merge names a part by that component"
            )
        }
        // the merge would drop these lists, so a part that fills them would lose content without a word
        for (list in listOf("services", "formulation")) {
            val entries = root.get(list) as? ArrayNode
            if (entries != null && !entries.isEmpty) {
                throw SbomException(
                    "'${part.source}' has $list, which the merge does not carry; leave them out of the part's SBOM"
                )
            }
        }
        // the provides of an entry states a relation that the merge does not read, and so would drop
        val dependencies = root.get("dependencies") as? ArrayNode
        if (dependencies != null && dependencies.any { (it.get("provides") as? ArrayNode)?.isEmpty == false }) {
            throw SbomException(
                "'${part.source}' has a dependency entry with provides, which the merge does not carry; leave " +
                    "provides out of the part's SBOM"
            )
        }
        val listed = (root.get("components") as? ArrayNode)?.filterIsInstance<ObjectNode>().orEmpty()
        val carried = buildList {
            add(primary)
            addAll(listed)
        }
        // a component nested in another would skip the union by bom-ref, so the merge takes none
        if (carried.any { (it.get("components") as? ArrayNode)?.isEmpty == false }) {
            throw SbomException(
                "'${part.source}' has a component with nested components, which the merge does not flatten; list " +
                    "every component at the top level of the part's SBOM"
            )
        }
        // the components are the only nodes of a part the merge keeps, so a reference to any other node, a tool
        // defined in metadata.tools for one, would lead nowhere
        val refs = carried.mapNotNullTo(hashSetOf(), BomFilter::nonEmptyRefOf)
        carried.flatMap(::evidenceTools).firstOrNull { it !in refs }?.let { tool ->
            throw SbomException(
                "'${part.source}' has evidence that references the tool '$tool', which the merge does not carry; " +
                    "list the tool among the components, or leave the reference out"
            )
        }
        val referenced = dependencyEntries(root).flatMap { (entryRef, targets) ->
            buildList {
                add(entryRef)
                addAll(targets)
            }
        }
        referenced.firstOrNull { it !in refs }?.let { target ->
            throw SbomException(
                "'${part.source}' has a dependency entry that references '$target', which the merge does not carry; " +
                    "list '$target' among the components"
            )
        }
        // requireSpecVersion passed, so the text is a 1.x version
        val text = root.get("specVersion").asText()
        val majorMinor = requireNotNull(SbomEnricher.majorMinor(text))
        return ReadPart(part.source, root, primary, ref, name, text, majorMinor, dependencyEntries(root))
    }

    /** Throws [SbomException] unless [root] declares the `bomFormat` CycloneDX, which [BomJson.read] does not check. */
    private fun requireBomFormat(root: ObjectNode) {
        val declared = root.get("bomFormat") ?: throw SbomException("The BOM declares no bomFormat")
        val format = declared.takeIf { it.isTextual }?.asText()
            ?: throw SbomException("The BOM's bomFormat is not text, but has to be $BOM_FORMAT")
        if (format != BOM_FORMAT) throw SbomException("The BOM declares bomFormat '$format', not $BOM_FORMAT")
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

    /**
     * The entries of the `dependencies` of [root] as the merge reads them: those with a text `ref`, each with the text
     * targets of its `dependsOn`. Anything else in the list is ignored. Kept as [ReadPart.entries].
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
     * Throws [SbomException] when the [ProductDetails.hostParts] of [product] name a part that is none of [parts] and
     * not among its expected parts either, a misspelt or stale name, so that the setting cannot outlive the part it
     * names. A missing expected part keeps the product incomplete with a warning instead, host or not.
     */
    private fun requireHostParts(parts: List<ReadPart>, product: ProductDetails) {
        val partNames = parts.mapTo(hashSetOf()) { it.name }
        val unknown = product.hostParts.filter { it !in partNames && it !in product.expectedParts }
        if (unknown.isEmpty()) return
        throw SbomException(
            "hostParts names parts that are neither in the merge nor in expectedParts: " +
                unknown.sorted().joinToString(", ") { "'$it'" } + "; correct or remove the names"
        )
    }

    /**
     * Removes the `build-system` references of [component], a promoted top-level component or another part's entry for
     * it, and the list when none is left: they point at the CI run that built the part, which changes without the part
     * changing. Called before [unite], so that a CI reference alone cannot win the field over what another part lists.
     */
    private fun dropBuildSystemReference(component: ObjectNode) {
        val references = component.get("externalReferences") as? ArrayNode ?: return
        references.removeIf { it.get("type")?.asText() == "build-system" }
        if (references.isEmpty) component.remove("externalReferences")
    }

    /**
     * Adds [component] from the part [source], or unites it with the entry of the same bom-ref added before, which
     * stays and gains what only [component] has; see [unite]. Fails when the two name different components (their
     * `purl`, `group`, `name` or `version` differ), or [component] lists different digests for one hash algorithm.
     * Notes a digest that differs from the one of an earlier entry for [requireAgreeingHashes], what [component]
     * concludes for [concludeLicenses], and whether it lacks the host label for [removeDisputedLabels].
     */
    private fun add(components: TreeMap<String, Owned>, source: String, component: ObjectNode) {
        val ref = BomFilter.nonEmptyRefOf(component)
            ?: throw SbomException("'$source' has a component without a bom-ref; give every component one")
        val owned = components.getOrPut(ref) { Owned(source, component) }
        for (field in identityFields) {
            val value = component.get(field)?.takeIf { it.isTextual }?.asText() ?: continue
            val seen = owned.identity.getOrPut(field) { Claim(value, source) }
            if (seen.value != value) {
                throw SbomException(
                    "'$ref' names different components in '${seen.source}' and '$source'; give the components " +
                        "bom-refs of their own, such as their purls"
                )
            }
        }
        val incoming = hashes(component)
        // an entry that lists one algorithm with different digests is broken on its own, whatever other parts list
        for ((algorithm, listed) in incoming) {
            if (listed.map { it.content }.distinct().size > 1) {
                throw SbomException(
                    "'$source' lists different $algorithm digests for '$ref'; list one digest per algorithm"
                )
            }
        }
        // each algorithm has one digest in the entry now, so its last entry speaks for it and is the one to copy
        val perAlgorithm = incoming.mapValues { it.value.last() }
        for ((algorithm, hash) in perAlgorithm) {
            val seen = owned.digests[algorithm]
            if (seen != null && seen.content != hash.content && owned.hashConflict == null) {
                owned.hashConflict = "'$ref' has different hashes in '${seen.source}' and '$source'; the parts ship " +
                    "different files under one bom-ref, so build them from the same artifact"
            }
        }
        val added = perAlgorithm.filterKeys { it !in owned.digests }
        for ((algorithm, hash) in added) owned.digests[algorithm] = Digest(hash.content, source)
        // before the union, which may give the entry that stays the licenses of this one
        conclusionOf(component, source)?.let(owned.conclusions::add)
        // a part that ships the component, or describes it as a host, lists it without the label
        if (!SbomEnricher.carriesHostLabel(component)) owned.listedWithoutLabel = true
        if (owned.node !== component) unite(owned, source, component, added.values)
    }

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
     * What [component], from the part [source], concludes: the license objects and the expressions that its `licenses`
     * tag as `concluded`; null when it concludes nothing.
     */
    private fun conclusionOf(component: ObjectNode, source: String): Conclusion? {
        val ids = sortedSetOf<String>()
        val expressions = sortedSetOf<String>()
        for (entry in component.get("licenses") as? ArrayNode ?: return null) {
            val license = entry.get("license") as? ObjectNode
            // a license object carries its acknowledgement inside, an expression next to its text
            if ((license ?: entry).get("acknowledgement")?.asText() != "concluded") continue
            val text = if (license != null) license.get("id") ?: license.get("name") else entry.get("expression")
            val value = text?.takeIf { it.isTextual }?.asText() ?: continue
            if (license != null) ids.add(value) else expressions.add(value)
        }
        if (ids.isEmpty() && expressions.isEmpty()) return null
        return Conclusion(ids, expressions, source, component)
    }

    /**
     * Gives the node of [owned] the fields only [other] has, noting the part [source] they came from, and the `hashes`
     * entries of the [added] algorithms. Where both have `properties`, [addPropertiesByName] unites them, and
     * [strongestScope] picks the `scope`, left out when `required`. Any other field both have stays as it is, but for
     * the `licenses`, which [concludeLicenses] settles once every entry is in.
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

    /** The failure for the part [source], which uses the `bom-ref` of [product] for a node of its own. */
    private fun usesProductRef(source: String, product: ProductDetails) =
        SbomException(
            "'$source' uses the bom-ref '${product.ref}' of the product; give the product another name or version"
        )

    /**
     * The `dependencies` entries of the [parts], merged by `ref`. A target that the part's map in [replacements] names
     * points at the component taking its place, and the entry of the replaced component is left out.
     */
    private fun mergeDependencies(
        parts: List<ReadPart>,
        replacements: List<Map<String, String>>,
    ): TreeMap<String, TreeSet<String>> {
        val dependencies = TreeMap<String, TreeSet<String>>()
        for ((index, part) in parts.withIndex()) {
            val replaced = replacements[index]
            for ((ref, targets) in part.entries) {
                // the component taking the place of a replaced one has its entry from the part that describes it
                if (ref in replaced) continue
                val pointed = targets.mapNotNull { target ->
                    val taking = replaced[target] ?: return@mapNotNull target
                    // a component the part lists next to the one it identifies would otherwise depend on itself
                    taking.takeIf { it != ref }
                }
                dependencies.getOrPut(ref) { TreeSet() }.addAll(pointed)
            }
        }
        return dependencies
    }

    /**
     * Removes those of [replaced] from [components] that no entry of [dependencies] names, as its `ref` or a target,
     * and no component that stays refers to, so that the product does not list a version it does not ship. A replaced
     * component stays for a reference only from one that stays itself, so a chain or a cycle of replaced components
     * that refer only to each other goes as a whole. Returns those it keeps only for such a reference: outside the
     * graph, they need a composition of their own.
     */
    private fun dropUnreferenced(
        components: TreeMap<String, Owned>,
        dependencies: Map<String, Set<String>>,
        replaced: Set<String>,
    ): Set<String> {
        val graph = dependencies.flatMapTo(hashSetOf()) { (ref, targets) -> targets + ref }
        val candidates = replaced.filterTo(sortedSetOf()) { it in components && it !in graph }
        val staying = components.keys.filterTo(hashSetOf()) { it !in candidates }
        val kept = sortedSetOf<String>()
        // until nothing changes, as a component kept in one pass may name another left for the next
        do {
            // any text equal to the ref, such as an evidence tool, would name what the product left out
            val named = (candidates - kept).filter { ref ->
                staying.any { BomFilter.referencesTo(components.getValue(it).node, setOf(ref)).isNotEmpty() }
            }
            kept.addAll(named)
            staying.addAll(named)
        } while (named.isNotEmpty())
        (candidates - kept).forEach(components::remove)
        return kept
    }

    /** Throws [SbomException] on the first component of [components] whose entries list different digests. */
    private fun requireAgreeingHashes(components: Map<String, Owned>) {
        components.values.firstNotNullOfOrNull { it.hashConflict }?.let { throw SbomException(it) }
    }

    /**
     * Gives every component of [components] the `licenses` of its first entry that concludes a license, so that the
     * product states the conclusion whichever entry won the other fields. Where entries conclude differently, the
     * entry whose conclusion [licenseConflicts] gives for the module wins (see [Conclusion.matches]); the merge fails
     * without such an entry, when no entry's conclusion matches it, and on an entry for a module whose parts do not
     * conclude differently.
     */
    private fun concludeLicenses(components: Map<String, Owned>, licenseConflicts: Map<String, String>) {
        val settled = mutableSetOf<String>()
        for ((ref, owned) in components) {
            val first = owned.conclusions.firstOrNull() ?: continue
            val differing = owned.conclusions.firstOrNull { !it.sameAs(first) }
            val standing = if (differing == null) {
                first
            } else {
                val module = moduleId(owned)
                val stated = licenseConflicts[module] ?: throw SbomException(
                    "'$ref' has different concluded licenses in '${first.source}' (${first.text}) and " +
                        "'${differing.source}' (${differing.text}); build the parts with the same license settings, " +
                        "or put the conclusion the product states into licenseConflicts under '$module': " +
                        CONCLUSION_FORMS
                )
                settled.add(module)
                owned.conclusions.firstOrNull { it.matches(stated) } ?: throw SbomException(
                    "licenseConflicts gives '$stated' for '$module', but the parts conclude " +
                        owned.conclusions.joinToString(", ") { "${it.text} in '${it.source}'" } +
                        " for '$ref'; give one of those: $CONCLUSION_FORMS"
                )
            }
            if (standing.entry === owned.node) continue
            owned.node.set<JsonNode>("licenses", standing.entry.get("licenses").deepCopy())
            owned.fieldSources["licenses"] = standing.source
        }
        // an entry that settles nothing could hide the next conflict of its module
        val stale = licenseConflicts.keys - settled
        if (stale.isNotEmpty()) {
            throw SbomException(
                "licenseConflicts names modules whose parts do not conclude different licenses: " +
                    stale.sorted().joinToString(", ") { "'$it'" } + "; remove those entries"
            )
        }
    }

    /** The module id of [owned], `group:name` as the license options write it, or the name alone without a group. */
    private fun moduleId(owned: Owned): String {
        val name = owned.identity["name"]?.value.orEmpty()
        val group = owned.identity["group"]?.value
        return if (group.isNullOrEmpty()) name else "$group:$name"
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
                        if (previous == source) "'$previous' defines the bom-ref '$ref' twice; give each node its own"
                        else "'$previous' and '$source' define the bom-ref '$ref' for different nodes; give each " +
                            "node its own"
                    )
                }
            }
        }
    }

    /** The bom-refs that [node] and the nodes below it define; a text or an array defines none of its own. */
    private fun definedRefs(node: JsonNode): List<String> =
        listOfNotNull(node.get("bom-ref")?.takeIf { it.isTextual }?.asText()) + node.flatMap(::definedRefs)

    /**
     * Why the product lists the replaced component [ref] as incomplete: another component refers to it, so it stays,
     * although another version takes its place for the [parts] whose [replacements] name it.
     */
    private fun keptReason(ref: String, parts: List<ReadPart>, replacements: List<Map<String, String>>): String {
        val replacing = parts.indices.filter { ref in replacements[it] }.joinToString(", ") { "'${parts[it].name}'" }
        return "the product keeps '$ref', in whose place another version comes for $replacing, only because another " +
            "component refers to it, and states nothing about its dependencies (to accept it, declare it in " +
            "declaredIdentifiedGapReasons)"
    }

    /**
     * Removes the host label (see [SbomEnricher.isHostLabel]) from every component of [components] that an entry
     * lists without it: the label stays while every part that lists the component labels it, as a part that ships the
     * component or a host part that describes it does not.
     */
    private fun removeDisputedLabels(components: Map<String, Owned>) {
        for (owned in components.values) {
            if (!owned.listedWithoutLabel) continue
            val properties = owned.node.get("properties") as? ArrayNode ?: continue
            if (properties.none(SbomEnricher::isHostLabel)) continue
            properties.removeIf(SbomEnricher::isHostLabel)
            if (properties.isEmpty) owned.node.remove("properties")
        }
    }

    /**
     * The document of [product]: its top-level component, then the [components] and [dependencies] the [parts]
     * contributed, at the highest spec version of the parts. The graph is complete but for the [undescribed]
     * identified components, or incomplete as a whole when there are [reasons].
     */
    private fun assemble(
        product: ProductDetails,
        parts: List<ReadPart>,
        components: List<ObjectNode>,
        dependencies: Map<String, Set<String>>,
        reasons: List<String>,
        undescribed: Set<String>,
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
        SbomEnricher.declareCompleteness(root, reasons, undescribed)
        return root
    }
}
