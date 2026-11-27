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
import java.net.URLDecoder

/**
 * Finds what the parts of a product leave open: which components a part only identifies, which of those a host part
 * describes, in the very version or as the one other version of the package that takes its place, and why the product
 * is incomplete otherwise. [SbomMerger] applies what [assess] finds to the merged document.
 */
internal object ProductCompleteness {

    // how a package URL starts
    private const val PURL_PREFIX = "pkg:"

    /** What the parts of a product leave open, as [assess] finds it. */
    class Assessment(
        /**
         * For each part, in the order [assess] got them, the components it identifies, each to the other version of
         * its package that a host part describes and that takes its place.
         */
        val replacements: List<Map<String, String>>,
        /** The identified components no host part describes, which keep the product incomplete for them alone. */
        val undescribed: Set<String>,
        /** Why the whole graph of the product is open, such as a part's gap of its own or a missing part. */
        val reasons: List<String>,
        /** What the merge warns with, the gap the build declares left out. */
        val reported: List<String>,
    )

    /**
     * What keeps one part from being complete: [undescribed], the components it identifies that no host part
     * describes, and [reason], any other gap, which leaves the whole graph of the product open, null when there is
     * none.
     */
    private class Gap(val undescribed: Set<String> = emptySet(), val reason: String? = null)

    /**
     * What the [parts], read and sorted by [SbomMerger], leave open in the product [product]; [purls] gives the purl
     * of each merged component that states one, by bom-ref.
     */
    fun assess(parts: List<ReadPart>, product: ProductDetails, purls: Map<String, String>): Assessment {
        val describedByPart = parts.map(::described)
        val fillers = parts.indices.map { describedByHosts(parts, describedByPart, product.hostParts, it) }
        val replacements = parts.indices.map { replacements(parts[it], fillers[it], purls) }
        val gaps = parts.indices.map { completeness(parts[it], fillers[it] + replacements[it].keys) }
        val missing = (product.expectedParts - parts.map { it.name }.toSet()).sorted().map { "no SBOM for part '$it'" }
        val declared = product.declaredIdentifiedGapReasons.isNotEmpty()
        return Assessment(
            replacements = replacements,
            undescribed = gaps.flatMapTo(sortedSetOf()) { it.undescribed },
            reasons = gaps.mapNotNull { it.reason } + missing,
            reported = reported(parts, gaps, product, purls, declared) + missing,
        )
    }

    /**
     * The refs [part] describes: listed in the `dependencies` of a `complete` composition of its SBOM and in none with
     * another `aggregate`, while the SBOM's `dependencies` have an entry for them, so it states what they depend on.
     */
    private fun described(part: ReadPart): Set<String> {
        val entries = part.entries.mapTo(hashSetOf()) { it.first }
        val (complete, other) = stated(part).partition { it.get("aggregate").asText() == "complete" }
        // a part that also leaves a ref open contradicts itself, so two such parts cannot fill each other's gap
        val open = other.flatMapTo(hashSetOf()) { textRefs(it.get("dependencies")) }
        return complete
            .flatMap { textRefs(it.get("dependencies")) }
            .filterTo(hashSetOf()) { it in entries && it !in open }
    }

    /** The `compositions` of the SBOM of [part] that state an `aggregate` as text; any other says nothing. */
    private fun stated(part: ReadPart): List<JsonNode> =
        (part.root.get("compositions") as? ArrayNode)?.filter { it.get("aggregate")?.isTextual == true }.orEmpty()

    /** The text entries of [list], a composition's `dependencies`; none when it is not a list. */
    private fun textRefs(list: JsonNode?): List<String> =
        (list as? ArrayNode)?.filter { it.isTextual }?.map { it.asText() }.orEmpty()

    /**
     * The refs that may fill what the part at [index] identifies, as the very ref or as another version of it: those
     * [describedByPart] holds for the [parts] that [hostParts] names, and none when that part is a host itself. What a
     * part describes runs in that part's own classloader, so it is no component another part can use, whatever its
     * version, unless that part hosts the other, and no part hosts a host.
     */
    private fun describedByHosts(
        parts: List<ReadPart>,
        describedByPart: List<Set<String>>,
        hostParts: Set<String>,
        index: Int,
    ): Set<String> {
        if (parts[index].name in hostParts) return emptySet()
        return parts.indices.filter { parts[it].name in hostParts }.flatMapTo(hashSetOf()) { describedByPart[it] }
    }

    /**
     * The components [part] identifies and [described] does not hold, each to the component that takes its place: the
     * one ref of [described] with the same package (see [packageOf]), another version of it for example. A component
     * with no such ref, or with several, keeps its gap, and so does one the part states dependencies of. [purls]
     * gives the purl of each merged component that states one.
     */
    private fun replacements(part: ReadPart, described: Set<String>, purls: Map<String, String>): Map<String, String> {
        // the dependencies of a component the part ships, or names with a graph of its own, would lose their edge
        val withTargets = targetedRefs(part)
        val open = identified(part).filterNot { it in described || it in withTargets }
        if (open.isEmpty()) return emptyMap()
        val byPackage = described.groupBy { packageOf(it, purls) }
        return open.mapNotNull { ref ->
            val name = packageOf(ref, purls) ?: return@mapNotNull null
            // of several versions, the merge cannot tell which one the product runs the part on
            byPackage[name]?.singleOrNull()?.let { ref to it }
        }.toMap()
    }

    /** The refs of the dependency entries of [part] that have targets: the components it states dependencies of. */
    private fun targetedRefs(part: ReadPart): Set<String> =
        part.entries.filter { (_, targets) -> targets.isNotEmpty() }.mapTo(hashSetOf()) { it.first }

    /**
     * The refs the `incomplete` compositions of [part] list when the part's open compositions are all gaps of
     * identified components: shaped as one (see [shapedAsIdentifiedGaps]) and over labelled components only (see
     * [labelled]). None otherwise, as the part's own graph is open then.
     */
    private fun identified(part: ReadPart): Set<String> {
        val stated = stated(part)
        if (!shapedAsIdentifiedGaps(part, stated)) return emptySet()
        val listed = openRefs(stated)
        return if (listed.all { it in labelled(part) }) listed else emptySet()
    }

    /**
     * Whether every composition of [stated], those of [part], that is not `complete` has the shape `generateSbom`
     * writes for identified components: `incomplete` over a non-empty list of refs of the part's dependency graph,
     * without the part's top-level component, whose gap no other part could fill, and without a ref a `complete` one
     * lists, which contradicts it. Any other open composition, one over something outside the graph included, leaves
     * the part's whole graph open; so does one over a component without the label, see [identified].
     */
    private fun shapedAsIdentifiedGaps(part: ReadPart, stated: List<JsonNode>): Boolean {
        val (complete, open) = stated.partition { it.get("aggregate").asText() == "complete" }
        val completeRefs = complete.flatMapTo(hashSetOf()) { textRefs(it.get("dependencies")) }
        val graph = part.entries.flatMapTo(hashSetOf()) { (ref, targets) -> targets + ref }
        return open.all { composition ->
            val listed = textRefs(composition.get("dependencies"))
            composition.get("aggregate").asText() == "incomplete" && listed.isNotEmpty() && part.ref !in listed &&
                listed.none { it in completeRefs } && listed.all { it in graph }
        }
    }

    /** The refs the `incomplete` compositions of [stated] list, sorted. */
    private fun openRefs(stated: List<JsonNode>): Set<String> =
        stated.filter { it.get("aggregate").asText() == "incomplete" }
            .flatMapTo(sortedSetOf()) { textRefs(it.get("dependencies")) }

    /**
     * The bom-refs of the components of [part] that carry [SbomEnricher.PROVIDED_BY_HOST] with the value `true`: the
     * ones `generateSbom` only identifies. A part from another tool carries none, so the merge identifies nothing in
     * it.
     */
    private fun labelled(part: ReadPart): Set<String> =
        (part.root.get("components") as? ArrayNode)
            ?.filter(SbomEnricher::carriesHostLabel)
            ?.mapNotNullTo(hashSetOf(), BomFilter::nonEmptyRefOf)
            .orEmpty()

    /**
     * The package of the component [ref], from its purl in [purls], else from [ref] itself: the type in lower case, the
     * namespace and the name percent-decoded, as in `pkg:maven/com.hivemq/hivemq-extension-sdk`, without version,
     * qualifiers and subpath. Null without a purl.
     */
    private fun packageOf(ref: String, purls: Map<String, String>): String? {
        val purl = purls[ref] ?: ref
        if (!purl.startsWith(PURL_PREFIX)) return null
        // the subpath follows the first #, the qualifiers the first ?, neither of which a version or name may hold
        val path = purl.removePrefix(PURL_PREFIX).substringBefore('#').substringBefore('?').trimStart('/')
        // the version follows the last @ after the last /, where an unencoded npm scope such as @types cannot be
        val at = path.lastIndexOf('@')
        val unversioned = if (at > path.lastIndexOf('/')) path.substring(0, at) else path
        val type = unversioned.substringBefore('/', missingDelimiterValue = "")
        val name = unversioned.substringAfter('/', missingDelimiterValue = "")
        if (type.isEmpty() || name.isEmpty()) return null
        return "$PURL_PREFIX${type.lowercase()}/${percentDecoded(name)}"
    }

    /** [text] with its percent-encoded octets decoded as UTF-8; as it is when an escape is malformed. */
    private fun percentDecoded(text: String): String {
        if ('%' !in text) return text
        return try {
            // a plus stands for itself in a purl, where the decoder would read a space
            URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            text
        }
    }

    /**
     * What keeps [part] from being complete. The `compositions` of its SBOM that state an `aggregate` have to say
     * `complete`, or `incomplete` over identified components (see [identified]), and together list its
     * top-level component and every `ref` and `dependsOn` target of its `dependencies`; a `complete` entry counts only
     * for components that have an entry there. An identified component outside [filled] is [Gap.undescribed]; any other
     * shortcoming is the [Gap.reason].
     */
    private fun completeness(part: ReadPart, filled: Set<String>): Gap {
        val stated = stated(part)
        if (stated.isEmpty()) return Gap(reason = noClaim(part))
        // a gap of the part itself names its top-level component, which no other part can fill, or contradicts itself
        if (!shapedAsIdentifiedGaps(part, stated)) {
            return Gap(reason = "the SBOM of '${part.name}' declares its dependency graph incomplete")
        }
        val unlabelled = openRefs(stated) - labelled(part)
        if (unlabelled.isNotEmpty()) {
            // in parentheses, as the warning joins the reasons with semicolons
            return Gap(
                reason = "the SBOM of '${part.name}' declares " + unlabelled.joinToString(", ") { "'$it'" } +
                    " incomplete without the ${SbomEnricher.PROVIDED_BY_HOST} label, so the merge cannot tell a " +
                    "component its host provides from one it ships (a part built before the plugin wrote that " +
                    "label needs a rebuild; a merged product loses the label where one of its parts ships the " +
                    "component)"
            )
        }
        // an identified component a host part describes, such as the SDK an extension is built against and the
        // broker ships, is complete at the product level, where the host fills the gap
        val undescribed = identified(part).filterTo(sortedSetOf()) { it !in filled }
        val lists = stated.mapNotNull { it.get("dependencies") as? ArrayNode }
        if (lists.isEmpty()) return Gap(undescribed, noClaim(part))
        // a complete entry can only speak for components the part states the dependencies of, as the enricher writes it
        val entryList = part.entries
        val entries = entryList.mapTo(hashSetOf()) { it.first }
        val covered = stated.flatMap { composition ->
            val listed = textRefs(composition.get("dependencies"))
            if (composition.get("aggregate").asText() == "complete") listed.filter { it in entries } else listed
        }.toSet()
        // the top-level component and every target count even without an entry: such a target needs another part to
        // describe it, and such a top-level component keeps the part incomplete
        val graph = buildList {
            for ((ref, targets) in entryList) {
                add(ref)
                addAll(targets)
            }
            add(part.ref)
        }
        if (!covered.containsAll(graph)) {
            return Gap(undescribed, "the SBOM of '${part.name}' declares only part of its dependency graph complete")
        }
        return Gap(undescribed)
    }

    /**
     * Why [part] keeps the product from being complete when its SBOM states no aggregate, or its `complete`
     * compositions list no dependencies.
     */
    private fun noClaim(part: ReadPart) =
        "the SBOM of '${part.name}' does not declare whether its dependency graph is complete"

    /**
     * Why the [parts] keep the product from being complete, part by part as [gaps] holds it: the identified components
     * no host part describes, with what to do about them (see [remedy]) for [product], unless the build [declared]
     * that gap and so knows it, then any other gap; [purls] as for [replacements].
     */
    private fun reported(
        parts: List<ReadPart>,
        gaps: List<Gap>,
        product: ProductDetails,
        purls: Map<String, String>,
        declared: Boolean,
    ): List<String> {
        val present = parts.mapTo(hashSetOf()) { it.name }
        return parts.zip(gaps).flatMap { (part, gap) ->
            val identifiedGap = gap.undescribed.takeIf { it.isNotEmpty() && !declared }?.let { refs ->
                // in parentheses, as the warning joins the reasons with semicolons
                val remedy = remedy(part, refs, product.hostParts, present, purls)
                "the SBOM of '${part.name}' declares its dependency graph incomplete, and no host part describes " +
                    refs.joinToString(", ") { "'$it'" } + " ($remedy)"
            }
            listOfNotNull(identifiedGap, gap.reason)
        }
    }

    /**
     * What the build can do about the [refs] that [part] identifies and no host part describes, by why they stay so:
     * no part fills the gap of a host part; without [hostParts], naming the host fills it; a named host part may have
     * no SBOM among the [present] parts; and a ref may have no package URL in [purls], dependencies of its own in
     * [part], or no single version among what the host parts describe. Declaring the gap accepts it in every case.
     */
    private fun remedy(
        part: ReadPart,
        refs: Set<String>,
        hostParts: Set<String>,
        present: Set<String>,
        purls: Map<String, String>,
    ): String {
        val accept = "to accept the gap, declare it in declaredIdentifiedGapReasons"
        if (part.name in hostParts) {
            return "no part fills the gap of a host part; to accept it, declare it in declaredIdentifiedGapReasons"
        }
        if (hostParts.isEmpty()) {
            return "to fill the gap, name the part that hosts '${part.name}' in hostParts; to accept it, declare it " +
                "in declaredIdentifiedGapReasons"
        }
        val absent = (hostParts - present).sorted()
        if (absent.isNotEmpty()) {
            val names = absent.joinToString(", ") { "'$it'" }
            val has = if (absent.size == 1) "the host part $names has" else "the host parts $names have"
            return "$has no SBOM in this merge; $accept"
        }
        val withTargets = targetedRefs(part)
        val causes = refs.map { ref ->
            when {
                packageOf(ref, purls) == null -> "'$ref' has no package URL to match another version by"
                ref in withTargets -> "'$ref' has dependencies of its own, which another version cannot take over"
                else -> "the host parts describe the package of '$ref' in no version or in several"
            }
        }
        return causes.joinToString(", ") + "; $accept"
    }
}
