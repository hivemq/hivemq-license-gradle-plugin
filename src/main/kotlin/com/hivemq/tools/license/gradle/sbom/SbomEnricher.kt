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
import java.math.BigInteger
import java.net.URI
import java.net.URISyntaxException

/**
 * Adds what BSI TR-03183-2 requires and CycloneDX does not write: the SBOM's creator, each component's file, file kind,
 * creator and source URI, the graph's completeness, and our reviewed license next to the declared one. Deterministic;
 * needs CycloneDX [MINIMUM_SPEC_VERSION] or a later 1.x and leaves a value of an unexpected type as it is.
 */
internal object SbomEnricher {

    private val mapper = ObjectMapper()

    // only on the purl of a Gradle project, included builds too, so a composite of our repositories gets our creator
    private val projectPath = Regex("[?&]project_path=")

    private val mailto = Regex("^mailto:", RegexOption.IGNORE_CASE)

    // a local part and a domain, neither with whitespace or a second at sign
    private val emailAddress = Regex("""^[^\s@]+@[^\s@]+$""")

    private val webSchemes = setOf("http", "https")

    // major and minor, and a patch like the 1.6.1 that the schema gives as an example
    private val specVersion = Regex("""(\d+)\.(\d+)(?:\.\d+)?""")

    // the CycloneDX specification in which the manufacturer fields, the license acknowledgements and the
    // `source-distribution` reference first appear; a BOM of an older specification cannot carry them
    const val MINIMUM_SPEC_VERSION = "1.6"

    private val minimumSpecVersion = requireNotNull(majorMinor(MINIMUM_SPEC_VERSION))

    /** [enrich] for a BOM that is still bytes; the result is compact JSON. */
    fun enrich(bom: ByteArray, details: SbomDetails): ByteArray =
        // compact, because the normalizer writes the final document and a pretty printer would put the system line
        // separator into these bytes
        mapper.writeValueAsBytes(enrich(BomJson.read(bom), details))

    /**
     * Adds the fields to [root], as [BomJson.read] returns it, and returns it. Everything is checked first, so [root]
     * is unchanged when this throws.
     */
    fun enrich(root: ObjectNode, details: SbomDetails): ObjectNode {
        requireSpecVersion(root)
        requireCreator(details.creator)
        enrichMetadata(root, details)
        (root.get("components") as? ArrayNode)?.forEach { node ->
            val component = node as? ObjectNode ?: return@forEach
            // with the version, because the BOM can hold a module in two versions, each with its own file and license
            val coordinates = coordinates(component).toString()
            details.componentFileNames[coordinates]?.let { describeFile(component, it) }
            addCreator(component, details)
            addSourceDistribution(component)
            acknowledgeLicenses(component, details.concludedLicenses[coordinates].orEmpty())
        }
        declareCompleteness(root, details.incompleteReasons)
        return root
    }

    /**
     * The `cyclonedxDirectBom` task can write an older spec that rejects these fields; a new major may rename or drop
     * them.
     */
    private fun requireSpecVersion(root: ObjectNode) {
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
     * A non-email value is written as a URL, where a name silently invalidates the SBOM and only http(s) leads
     * anywhere.
     */
    private fun requireCreator(creator: String) {
        if (emailAddress.matches(creator.replace(mailto, "")) || isWebUrl(creator)) return
        throw SbomException("hivemqLicense.sbomCreator must be an email address or an http(s) URL, but is '$creator'")
    }

    private fun isWebUrl(value: String) = try {
        // a host too, because https:foo is a valid URI without one
        URI(value).let { it.scheme?.lowercase() in webSchemes && it.host != null }
    } catch (_: URISyntaxException) {
        false
    }

    /** As numbers, so that 1.10 is later than 1.6. */
    private fun majorMinor(version: String): Pair<BigInteger, BigInteger>? =
        specVersion.matchEntire(version)?.destructured?.let { (major, minor) ->
            major.toBigInteger() to minor.toBigInteger()
        }

    /**
     * A value of another type is not ours to replace: these return null for it and the caller leaves that part alone.
     */
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
        metadata.set<JsonNode>("manufacturer", manufacturer(details))
        val component = metadata.get("component") as? ObjectNode ?: return
        component.set<JsonNode>("manufacturer", manufacturer(details))
        details.artifactFileName?.let { describeFile(component, it) }
    }

    private fun manufacturer(details: SbomDetails): ObjectNode = mapper.createObjectNode().apply {
        put("name", details.creatorName)
        // the guideline wants an email address and accepts a URL when there is none; a mailto link holds an address,
        // and a URL can contain an at sign too
        val address = details.creator.replace(mailto, "")
        if ("://" !in address && "@" in address) {
            putArray("contact").addObject().put("email", address)
        } else {
            putArray("url").add(details.creator)
        }
    }

    private fun coordinates(component: ObjectNode) = Coordinates(
        group = component.get("group")?.asText().orEmpty(),
        name = component.get("name")?.asText().orEmpty(),
        version = component.get("version")?.asText().orEmpty(),
    )

    /** A jar holds code a runtime executes and metadata about its contents; a zip holds files, not code. */
    private fun describeFile(component: ObjectNode, fileName: String) {
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

    /** Replaces an existing value, so enriching twice gives the same document. */
    private fun ArrayNode.putProperty(name: String, value: String) {
        val existing = firstOrNull { it.get("name")?.asText() == name } as? ObjectNode
        if (existing != null) existing.put("value", value) else addObject().put("name", name).put("value", value)
    }

    /**
     * A project of this build is ours; another, whose POM has no contact, gets its organization URL (`website`) or vcs.
     */
    private fun addCreator(component: ObjectNode, details: SbomDetails) {
        if (component.has("manufacturer")) return
        if (isProjectOfThisBuild(component)) {
            component.set<JsonNode>("manufacturer", manufacturer(details))
            return
        }
        val references = component.get("externalReferences") as? ArrayNode ?: return
        val url = referenceUrl(references, "website") ?: referenceUrl(references, "vcs") ?: return
        component.putObject("manufacturer").putArray("url").add(url)
    }

    private fun isProjectOfThisBuild(component: ObjectNode) = listOf("purl", "bom-ref").any { name ->
        component.get(name)?.asText()?.let(projectPath::containsMatchIn) == true
    }

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
     * CycloneDX wrote what the POM declares; our reviewed license is added as concluded. A license list holds license
     * objects or one expression, never both, so next to a concluded license an expression becomes a license name, since
     * separate ids would read as both licenses instead of either; without a concluded license it only gets its tag.
     */
    private fun acknowledgeLicenses(component: ObjectNode, concluded: List<String>) {
        val licenses = component.get("licenses") as? ArrayNode
        if (concluded.isNotEmpty()) licenses?.let(::nameExpressions)
        licenses?.forEach { entry ->
            val target = (entry.get("license") as? ObjectNode)
                ?: (entry as? ObjectNode)?.takeIf { it.has("expression") }
            if (target != null && !target.has("acknowledgement")) target.put("acknowledgement", "declared")
        }
        if (concluded.isEmpty()) return
        // only an expression that is not text is left, and license objects cannot join it in the list
        if (licenses != null && licenses.any { it.has("expression") }) return
        val list = component.arrayOrNew("licenses") ?: return
        for (id in concluded) {
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

    /** A license takes free text as its name, so the declared expression can sit next to license ids in one piece. */
    private fun nameExpressions(licenses: ArrayNode) {
        for (index in 0 until licenses.size()) {
            val entry = licenses.get(index) as? ObjectNode ?: continue
            val expression = entry.get("expression")?.takeIf { it.isTextual }?.asText() ?: continue
            val named = mapper.createObjectNode()
            val license = named.putObject("license").put("name", expression)
            // a tag it already carries stays, a missing one is added together with the other licenses
            entry.get("acknowledgement")?.let { license.set<JsonNode>("acknowledgement", it) }
            licenses.set(index, named)
        }
    }

    /**
     * BSI TR-03183-2 wants completeness stated truthfully, so any reason the build reported makes the graph incomplete.
     */
    private fun declareCompleteness(root: ObjectNode, incompleteReasons: List<String>) {
        val dependencies = root.get("dependencies") as? ArrayNode ?: return
        // only text, each once, because the schema wants unique strings; sorted like the normalizer sorts the
        // dependencies, so the order CycloneDX wrote does not reach the output
        val refs = dependencies
            .mapNotNull { it.get("ref")?.takeIf { ref -> ref.isTextual }?.asText() }
            .distinct()
            .sorted()
        if (refs.isEmpty()) return
        val compositions = root.arrayOrNew("compositions") ?: return
        compositions.removeAll()
        val aggregate = if (incompleteReasons.isEmpty()) "complete" else "incomplete"
        val composition = compositions.addObject().put("aggregate", aggregate)
        val list = composition.putArray("dependencies")
        refs.forEach(list::add)
    }

    private const val FILENAME = "bsi:component:filename"
    private const val EXECUTABLE = "bsi:component:executable"
    private const val ARCHIVE = "bsi:component:archive"
    private const val STRUCTURED = "bsi:component:structured"
}
