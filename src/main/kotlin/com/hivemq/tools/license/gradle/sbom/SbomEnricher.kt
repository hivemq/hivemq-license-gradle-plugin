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
import com.hivemq.tools.license.gradle.Coordinates
import com.hivemq.tools.license.gradle.OwnModules
import java.math.BigInteger
import java.net.URI
import java.net.URISyntaxException

/**
 * Adds what BSI TR-03183-2 requires and CycloneDX does not write: the SBOM's creator, each component's file, file kind,
 * creator and source URI, the graph's completeness, and our reviewed license next to the declared licenses or, as one
 * expression, in their place (see [ConcludedLicense]). Deterministic; needs CycloneDX [MINIMUM_SPEC_VERSION] or a later
 * 1.x; leaves a value of an unexpected type as it is, except inside a list it rewrites, but fails when the BOM cannot
 * carry a gap the SBOM has to state.
 */
internal object SbomEnricher {

    private val mapper = ObjectMapper()

    private val mailto = Regex("^mailto:", RegexOption.IGNORE_CASE)

    // a label of the local part: letters, marks and digits of any script and the other characters RFC 5322 allows in
    // an atom, without / ? # so that no URL or mailto query passes; quoted local parts are not supported
    private const val LOCAL_LABEL = """[\p{L}\p{M}\p{N}!$%&'*+=^_`{|}~-]+"""

    // a label of the domain: letters, marks and digits of any script, and hyphens except at the ends
    private const val DOMAIN_LABEL = """[\p{L}\p{M}\p{N}](?:[\p{L}\p{M}\p{N}-]*[\p{L}\p{M}\p{N}])?"""

    // a local part and a domain, each made of labels separated by single dots, the domain of at least two, so that no
    // empty label passes
    private val emailAddress = Regex("""^$LOCAL_LABEL(?:\.$LOCAL_LABEL)*@$DOMAIN_LABEL(?:\.$DOMAIN_LABEL)+$""")

    private val webSchemes = setOf("http", "https")

    // major and minor, and a patch like the 1.6.1 that the schema gives as an example
    private val specVersion = Regex("""(\d+)\.(\d+)(?:\.\d+)?""")

    /**
     * The CycloneDX specification in which the manufacturer fields, the license acknowledgements and the
     * `source-distribution` reference first appear; a BOM of an older specification cannot carry them.
     */
    const val MINIMUM_SPEC_VERSION = "1.6"

    private val minimumSpecVersion = requireNotNull(majorMinor(MINIMUM_SPEC_VERSION))

    /**
     * The property written, with the value `true`, on every component the SBOM only identifies: one the host provides
     * at runtime, whose file and dependencies the host's SBOM describes. A product merge recognises identified
     * components by it alone. The name is in a namespace of the plugin's own, as CycloneDX registers only names under
     * `cdx:`.
     */
    const val PROVIDED_BY_HOST = "hivemq:sbom:provided-by-host"

    /** Whether [property] is the [PROVIDED_BY_HOST] label with the value `true`, the one form that counts. */
    internal fun isHostLabel(property: JsonNode): Boolean =
        property.get("name")?.asText() == PROVIDED_BY_HOST && property.get("value")?.asText() == "true"

    /** Whether [component] carries the [PROVIDED_BY_HOST] label, see [isHostLabel]. */
    internal fun carriesHostLabel(component: JsonNode): Boolean =
        (component.get("properties") as? ArrayNode)?.any(::isHostLabel) == true

    /**
     * Adds the fields to [root], as [BomJson.read] returns it, and returns it. Everything is checked first, so [root]
     * is unchanged when this throws.
     */
    fun enrich(root: ObjectNode, details: SbomDetails): ObjectNode {
        requireSpecVersion(root)
        requireCreator(details.creator)
        val compositions = planCompositions(root, details)
        enrichMetadata(root, details)
        for (component in BomFilter.components(root)) {
            // with the version, because the BOM can hold a module in two versions, each with its own file and license
            val coordinates = coordinates(component).toString()
            // an identified component ships no file with this artifact, whatever the build resolved for its coordinates
            if (!isIdentified(component, details)) {
                details.componentFileNames[coordinates]?.let { describeFile(component, it) }
            } else {
                component.arrayOrNew("properties")?.putProperty(PROVIDED_BY_HOST, "true")
            }
            addCreator(component, details)
            addSourceDistribution(component)
            acknowledgeLicenses(component, details.concludedLicenses[coordinates])
        }
        compositions?.let { writeCompositions(root, it) }
        return root
    }

    private fun isIdentified(component: ObjectNode, details: SbomDetails) =
        BomFilter.refOf(component)?.let { it in details.identifiedComponents } == true

    /**
     * Throws [SbomException] unless [root] declares CycloneDX [MINIMUM_SPEC_VERSION] or a later 1.x version: an older
     * spec rejects the added fields, and a new major may rename or drop them.
     */
    internal fun requireSpecVersion(root: ObjectNode) {
        val declared = root.get("specVersion")
        val text = declared?.takeIf { it.isTextual }?.asText()
        val version = text?.let(::majorMinor)
        if (version != null && version.first != BigInteger.ONE) {
            throw SbomException(
                "The BOM declares CycloneDX specVersion $text, but this task only handles specification " +
                    "$MINIMUM_SPEC_VERSION and later 1.x versions"
            )
        }
        if (version == null || compareValuesBy(version, minimumSpecVersion, { it.first }, { it.second }) < 0) {
            val description = when {
                declared == null -> "no CycloneDX specVersion"
                text != null -> "CycloneDX specVersion $text"
                else -> "a CycloneDX specVersion that is not a string"
            }
            throw SbomException(
                "The BOM declares $description, but the manufacturer, license acknowledgement and " +
                    "source-distribution fields need $MINIMUM_SPEC_VERSION or later"
            )
        }
    }

    /**
     * Throws [SbomException] unless [creator] is an email address or an http(s) URL: a non-email value is written as a
     * URL, where a name silently invalidates the SBOM and only http(s) leads anywhere.
     */
    internal fun requireCreator(creator: String) {
        if (emailAddressOf(creator) != null || isWebUrl(creator)) return
        throw SbomException(
            "The creator, hivemqLicense.sbomCreator or the creator of the task, must be an email address or an " +
                "http(s) URL, but is '$creator'"
        )
    }

    // the address in an email or mailto creator, else null; the check and the manufacturer share this one rule, so
    // that what passes as an address is also written as one
    private fun emailAddressOf(creator: String): String? = creator.replace(mailto, "").takeIf(emailAddress::matches)

    private fun isWebUrl(value: String) = try {
        // a host too, because https:foo is a valid URI without one
        URI(value).let { it.scheme?.lowercase() in webSchemes && it.host != null }
    } catch (_: URISyntaxException) {
        false
    }

    /** The major and minor number of [version], such as 1.6 or 1.6.1; null when it is not such a version. */
    internal fun majorMinor(version: String): Pair<BigInteger, BigInteger>? =
        specVersion.matchEntire(version)?.destructured?.let { (major, minor) ->
            major.toBigInteger() to minor.toBigInteger()
        }

    // A value of another type is not ours to replace: these return null for it and the caller leaves that part alone.
    private fun ObjectNode.objectOrNew(name: String): ObjectNode? = when (val existing = get(name)) {
        null -> putObject(name)
        is ObjectNode -> existing
        else -> null
    }

    private fun ObjectNode.arrayOrNew(name: String): ArrayNode? = when (val existing = get(name)) {
        null -> putArray(name)
        is ArrayNode -> existing
        else -> null
    }

    private fun enrichMetadata(root: ObjectNode, details: SbomDetails) {
        val metadata = root.objectOrNew("metadata") ?: return
        metadata.set<JsonNode>("manufacturer", manufacturer(details.creatorName, details.creator))
        val component = metadata.get("component") as? ObjectNode ?: return
        component.set<JsonNode>("manufacturer", manufacturer(details.creatorName, details.creator))
        details.artifactFileName?.let { describeFile(component, it) }
    }

    /** The manufacturer object: the email as a contact, or the URL as a url, the one rule [requireCreator] applies. */
    internal fun manufacturer(creatorName: String, creator: String): ObjectNode = mapper.createObjectNode().apply {
        put("name", creatorName)
        // the guideline wants an email address and accepts a URL when there is none; any value that is not an address
        // has passed requireCreator as an http(s) URL
        val address = emailAddressOf(creator)
        if (address != null) {
            putArray("contact").addObject().put("email", address)
        } else {
            putArray("url").add(creator)
        }
    }

    private fun coordinates(component: ObjectNode) = Coordinates(
        group = component.get("group")?.asText().orEmpty(),
        name = component.get("name")?.asText().orEmpty(),
        version = component.get("version")?.asText().orEmpty(),
    )

    /**
     * Writes [fileName] and, for a jar or a zip, its executable, archive and structured flags to the `properties` of
     * [component]. Does nothing when `properties` is not an array.
     */
    internal fun describeFile(component: ObjectNode, fileName: String) {
        val properties = component.arrayOrNew("properties") ?: return
        properties.putProperty(FILENAME, fileName)
        when {
            fileName.endsWith(".jar") -> {
                properties.putProperty(EXECUTABLE, "executable")
                properties.putProperty(ARCHIVE, "archive")
                properties.putProperty(STRUCTURED, "structured")
            }
            fileName.endsWith(".zip") -> {
                properties.putProperty(EXECUTABLE, "non-executable")
                properties.putProperty(ARCHIVE, "archive")
                properties.putProperty(STRUCTURED, "structured")
            }
        }
    }

    // replaces an existing value, so enriching twice gives the same document
    private fun ArrayNode.putProperty(name: String, value: String) {
        val existing = firstOrNull { it.get("name")?.asText() == name } as? ObjectNode
        if (existing != null) existing.put("value", value) else addObject().put("name", name).put("value", value)
    }

    // a project of this build is ours; another, whose POM has no contact, gets its organization URL (`website`) or vcs
    private fun addCreator(component: ObjectNode, details: SbomDetails) {
        if (component.has("manufacturer")) return
        if (isProjectOfThisBuild(component)) {
            component.set<JsonNode>("manufacturer", manufacturer(details.creatorName, details.creator))
            return
        }
        val references = component.get("externalReferences") as? ArrayNode ?: return
        val url = referenceUrl(references, "website") ?: referenceUrl(references, "vcs") ?: return
        component.putObject("manufacturer").putArray("url").add(url)
    }

    // a project of this build or of an included one, so a composite of our repositories gets our creator
    private fun isProjectOfThisBuild(component: ObjectNode) =
        OwnModules.isProjectComponent(component.get("purl")?.asText(), component.get("bom-ref")?.asText())

    private fun addSourceDistribution(component: ObjectNode) {
        val references = component.get("externalReferences") as? ArrayNode ?: return
        val vcs = referenceUrl(references, "vcs") ?: return
        // by type alone, so a reference without a usable url does not make every run add another one
        if (references.any { it.get("type")?.asText() == "source-distribution" }) return
        references.addObject().put("type", "source-distribution").put("url", vcs)
    }

    private fun referenceUrl(references: ArrayNode, type: String): String? =
        references.firstOrNull { it.get("type")?.asText() == type }
            ?.get("url")?.takeIf { it.isTextual }?.asText()

    /**
     * Writes [concluded], our reviewed license, into the component: elected licenses next to the declared entries, see
     * [addElected], an expression in their place, see [concludeExpression]. Without [concluded] the declared entries
     * are only tagged.
     */
    private fun acknowledgeLicenses(component: ObjectNode, concluded: ConcludedLicense?) {
        when (concluded) {
            is ConcludedLicense.Expression -> concludeExpression(component, concluded.text)
            is ConcludedLicense.Elected -> {
                val licenses = component.get("licenses") as? ArrayNode
                licenses?.let(::nameExpressions)
                tagDeclared(licenses)
                addElected(component, licenses, concluded.ids)
            }
            null -> tagDeclared(component.get("licenses") as? ArrayNode)
        }
    }

    /** Tags the licenses CycloneDX wrote from the POM as `declared`, keeping a tag an entry already carries. */
    private fun tagDeclared(licenses: ArrayNode?) {
        licenses?.forEach { entry ->
            val target = (entry.get("license") as? ObjectNode)
                ?: (entry as? ObjectNode)?.takeIf { it.has("expression") }
            if (target != null && !target.has("acknowledgement")) target.put("acknowledgement", "declared")
        }
    }

    /**
     * Adds each of [ids] as a `concluded` license object unless it is there already. A declared expression has become a
     * license name before, see [nameExpressions], since next to separate ids it would read as both licenses instead of
     * either.
     */
    private fun addElected(component: ObjectNode, licenses: ArrayNode?, ids: List<String>) {
        // only an expression that is not text is left, and license objects cannot join it in the list
        if (licenses != null && licenses.any { it.has("expression") }) return
        val list = component.arrayOrNew("licenses") ?: return
        for (id in ids) {
            // an SPDX id has no space, so a value like "Public Domain" can only be a name, and CycloneDX accepts
            // nothing but SPDX ids as `id`
            val key = if (' ' in id) "name" else "id"
            val present = list.any { entry ->
                val license = entry.get("license")
                license?.get(key)?.asText() == id && license.get("acknowledgement")?.asText() == "concluded"
            }
            if (!present) list.addObject().putObject("license").put(key, id).put("acknowledgement", "concluded")
        }
    }

    /**
     * Makes [expression] the component's whole license list: it names every declared alternative, and CycloneDX 1.6
     * cannot hold the declared objects next to it. Every entry goes, of whatever kind, together with the URL or text
     * CycloneDX wrote for it; rebuilt from scratch, so enriching twice gives the same document.
     */
    private fun concludeExpression(component: ObjectNode, expression: String) {
        val licenses = component.arrayOrNew("licenses") ?: return
        licenses.removeAll()
        licenses.addObject().put("expression", expression).put("acknowledgement", "concluded")
    }

    // a license takes free text as its name, so the declared expression can sit next to license ids in one piece
    private fun nameExpressions(licenses: ArrayNode) {
        for (index in 0 until licenses.size()) {
            val entry = licenses.get(index) as? ObjectNode ?: continue
            val expression = entry.get("expression")?.takeIf { it.isTextual }?.asText() ?: continue
            val named = mapper.createObjectNode()
            val license = named.putObject("license").put("name", expression)
            // a tag it already carries stays, a missing one is added together with the other licenses; so does a
            // bom-ref, which something else may refer to
            entry.get("acknowledgement")?.let { license.set<JsonNode>("acknowledgement", it) }
            entry.get("bom-ref")?.let { license.set<JsonNode>("bom-ref", it) }
            licenses.set(index, named)
        }
    }

    /**
     * The compositions to write, or null to leave the BOM's own untouched. BSI TR-03183-2 wants completeness stated
     * truthfully: every gap gets an incomplete composition, or this throws before anything changes.
     */
    private fun planCompositions(root: ObjectNode, details: SbomDetails): List<Composition>? {
        val graph = graphRefs(root)
        // a component without an entry may have unknown dependencies, so only one with an entry can be called complete
        val stated = entryRefs(root)
        // an identified component, whose dependencies the host's SBOM describes, is incomplete here in any case
        val identifiedGap = IDENTIFIED_GAP.takeIf { graph.any { it in details.identifiedComponents } }
        val gaps = details.incompleteReasons + listOfNotNull(identifiedGap)
        val writable = canWriteCompositions(root)
        // with no gap there is nothing to hold against a BOM without a graph or with compositions of another type
        if (gaps.isEmpty()) {
            val described = graph.filter { it in stated }
            return if (described.isEmpty() || !writable) null else listOf(Composition("complete", described))
        }
        val why = gaps.joinToString("; ")
        if (!writable) {
            throw SbomException(
                "The BOM's compositions are not a JSON array, so the SBOM cannot state that its dependency graph is " +
                    "incomplete: $why"
            )
        }
        if (details.incompleteReasons.isEmpty()) return splitCompositions(graph, stated, details.identifiedComponents)
        // a reported gap is the artifact's, so it is stated for the top-level component, whether or not the graph has
        // an entry for it, and for every component in the graph
        val topLevel = topLevelRef(root) ?: throw SbomException(
            "The BOM has no bom-ref on metadata.component, so the SBOM cannot state that its dependency graph is " +
                "incomplete: $why"
        )
        return listOf(Composition("incomplete", (graph + topLevel).distinct().sorted()))
    }

    /**
     * `incomplete` over the refs of [graph] in [open], and `complete` over the others that [stated] has an entry for;
     * either is left out when it would list nothing.
     */
    private fun splitCompositions(graph: List<String>, stated: Set<String>, open: Set<String>): List<Composition> {
        val (gap, others) = graph.partition { it in open }
        val described = others.filter { it in stated }
        return listOfNotNull(
            Composition("complete", described).takeIf { described.isNotEmpty() },
            Composition("incomplete", gap).takeIf { gap.isNotEmpty() },
        )
    }

    /** The text `ref`s of the dependency entries: the components whose dependencies the graph states. */
    private fun entryRefs(root: ObjectNode): Set<String> =
        (root.get("dependencies") as? ArrayNode)
            ?.mapNotNullTo(hashSetOf()) { it.get("ref")?.takeIf { ref -> ref.isTextual }?.asText() }
            .orEmpty()

    /**
     * The text refs of the dependency graph, each entry's `ref` and `dependsOn` targets, since a BOM from another tool
     * may target a component without an entry; each once as the schema wants, sorted so the entries' order is moot.
     */
    private fun graphRefs(root: ObjectNode): List<String> {
        val dependencies = root.get("dependencies") as? ArrayNode ?: return emptyList()
        return dependencies
            .flatMap { entry ->
                val targets = (entry.get("dependsOn") as? ArrayNode)?.filter { it.isTextual }?.map { it.asText() }
                listOfNotNull(entry.get("ref")?.takeIf { ref -> ref.isTextual }?.asText()) + targets.orEmpty()
            }
            .distinct()
            .sorted()
    }

    // compositions of another type cannot take the plugin's statement; a missing list is written
    private fun canWriteCompositions(root: ObjectNode): Boolean =
        root.get("compositions").let { it == null || it is ArrayNode }

    private fun topLevelRef(root: ObjectNode): String? =
        BomFilter.nonEmptyRefOf(root.path("metadata").path("component"))

    /**
     * Writes [compositions], then the BOM's own ones without the dependency refs [compositions] list. Expects the
     * BOM's own ones to be absent or an array, which the callers check with [canWriteCompositions].
     */
    private fun writeCompositions(root: ObjectNode, compositions: List<Composition>) {
        val covered = compositions.flatMapTo(hashSetOf()) { it.refs }
        val own = (root.get("compositions") as? ArrayNode)?.filterIsInstance<ObjectNode>().orEmpty()
        val array = root.putArray("compositions")
        for (composition in compositions) {
            val refs = array.addObject().put("aggregate", composition.aggregate).putArray("dependencies")
            composition.refs.forEach(refs::add)
        }
        for (composition in own) {
            dropCovered(composition, covered)
            if (BomFilter.keepComposition(composition)) array.add(composition)
        }
        // two can be equal once their covered refs are gone
        BomFilter.dropDuplicates(array)
    }

    /** Drops [covered] from a [composition]'s dependencies, and the list when that empties it. */
    private fun dropCovered(composition: ObjectNode, covered: Set<String>) {
        val dependencies = composition.get("dependencies") as? ArrayNode ?: return
        val rest = dependencies.filterNot { it.isTextual && it.asText() in covered }
        if (rest.size == dependencies.size()) return
        if (rest.isEmpty()) composition.remove("dependencies") else dependencies.removeAll().addAll(rest)
    }

    /**
     * Writes the compositions over every `ref` of the `dependencies` of [root] and every `dependsOn` target, and over
     * [undescribed], in the graph or not: one `incomplete` over all of them when [incompleteReasons] is not empty, else
     * `incomplete` over [undescribed] and `complete` over the others that have an entry, as [enrich] states identified
     * components. The BOM's own compositions stay as [writeCompositions] keeps them. Does nothing without a dependency
     * ref, or when `compositions` is not an array.
     */
    internal fun declareCompleteness(
        root: ObjectNode,
        incompleteReasons: List<String>,
        undescribed: Set<String> = emptySet(),
    ) {
        val graph = graphRefs(root)
        if (graph.isEmpty() || !canWriteCompositions(root)) return
        val refs = (graph + undescribed).distinct().sorted()
        if (incompleteReasons.isNotEmpty()) {
            writeCompositions(root, listOf(Composition("incomplete", refs)))
            return
        }
        writeCompositions(root, splitCompositions(refs, entryRefs(root), undescribed))
    }

    private class Composition(val aggregate: String, val refs: List<String>)

    private const val IDENTIFIED_GAP =
        "identified components (the first module on each path that hivemqProvided resolves), whose dependencies the " +
            "host's SBOM describes"

    private const val FILENAME = "bsi:component:filename"
    private const val EXECUTABLE = "bsi:component:executable"
    private const val ARCHIVE = "bsi:component:archive"
    private const val STRUCTURED = "bsi:component:structured"
}
