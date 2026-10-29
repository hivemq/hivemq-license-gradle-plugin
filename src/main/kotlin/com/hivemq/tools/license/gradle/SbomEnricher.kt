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
package com.hivemq.tools.license.gradle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigInteger

/**
 * Adds what BSI TR-03183-2 requires and CycloneDX does not write: who created the SBOM, the file behind each
 * component and what kind of file it is, the component creators, the source URI, whether the dependency graph is
 * complete, and our reviewed license next to the declared one. Everything is derived from [SbomDetails] and from
 * what the BOM already holds, so the result is the same for the same input. The BOM has to declare CycloneDX 1.6 or
 * later, the first spec version with the manufacturer fields and the acknowledgement of a license; any other value
 * of an unexpected type, such as a `licenses` that is not an array, is left as it is instead of being replaced or
 * failing.
 */
internal object SbomEnricher {

    private val mapper = ObjectMapper()

    // CycloneDX puts this qualifier on the purl of every Gradle project, and on nothing else. That includes the
    // projects of an included build, so their components get the same creator, which is right for a composite build
    // of our own repositories.
    private val projectPath = Regex("[?&]project_path=")

    private val mailto = Regex("^mailto:", RegexOption.IGNORE_CASE)

    // major and minor, and a patch like the 1.6.1 that the schema gives as an example
    private val specVersion = Regex("""(\d+)\.(\d+)(?:\.\d+)?""")

    /**
     * The CycloneDX specification in which the manufacturer fields, the license acknowledgements and the
     * `source-distribution` reference first appear; a BOM of an older specification cannot carry them.
     */
    const val MINIMUM_SPEC_VERSION = "1.6"

    private val minimumSpecVersion = requireNotNull(majorMinor(MINIMUM_SPEC_VERSION))

    fun enrich(bom: ByteArray, details: SbomDetails): ByteArray {
        val root = BomJson.read(bom)
        requireSpecVersion(root)
        enrichMetadata(root, details)
        (root.get("components") as? ArrayNode)?.forEach { node ->
            val component = node as? ObjectNode ?: return@forEach
            // with the version, because the BOM can hold a module in two versions, each with its own file and license
            val coordinates = coordinates(component).toString()
            details.artifactFileNames[coordinates]?.let { describeFile(component, it) }
            addCreator(component, details)
            addSourceDistribution(component)
            acknowledgeLicenses(component, details.concludedLicenses[coordinates])
        }
        declareComplete(root)
        // compact, because the normalizer writes the final document and a pretty printer would put the system line
        // separator into these bytes
        return mapper.writeValueAsBytes(root)
    }

    // cyclonedxDirectBom can be configured to write an older spec version, whose schema rejects the fields added here
    private fun requireSpecVersion(root: ObjectNode) {
        val declared = root.get("specVersion")
        require(declared != null && declared.isTextual && isAtLeastMinimum(declared.asText())) {
            val version = when {
                declared == null -> "no CycloneDX specVersion"
                declared.isTextual -> "CycloneDX specVersion ${declared.asText()}"
                else -> "a CycloneDX specVersion that is not a string"
            }
            "The BOM declares $version, but the manufacturer, license acknowledgement and source-distribution " +
                "fields need $MINIMUM_SPEC_VERSION or later"
        }
    }

    private fun isAtLeastMinimum(version: String): Boolean {
        val declared = majorMinor(version) ?: return false
        return compareValuesBy(declared, minimumSpecVersion, { it.first }, { it.second }) >= 0
    }

    // as numbers, so that 1.10 is later than 1.6
    private fun majorMinor(version: String): Pair<BigInteger, BigInteger>? =
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

    // a jar holds code a runtime executes and metadata about its contents; a zip holds files, not code
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

    // replaces an existing value, so enriching twice gives the same document
    private fun ArrayNode.putProperty(name: String, value: String) {
        val existing = firstOrNull { it.get("name")?.asText() == name } as? ObjectNode
        if (existing != null) existing.put("value", value) else addObject().put("name", name).put("value", value)
    }

    // A project of this build has no POM, but it is ours, so its creator is the creator of the SBOM. A POM carries no
    // contact for its authors, so for any other component the closest thing to one is the URL of the organization in
    // its POM, which CycloneDX writes as the `website` reference, or else its repository.
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
     * CycloneDX wrote what the POM declares; our reviewed license is added as concluded. A license list is either
     * license objects or one expression, never both. To fit a concluded license next to an expression, the expression
     * becomes the name of a license, kept in one piece because as separate ids it would read as both licenses instead
     * of either one. Without a concluded license the expression stays as it is and only gets its tag.
     */
    private fun acknowledgeLicenses(component: ObjectNode, concluded: String?) {
        val licenses = component.get("licenses") as? ArrayNode
        if (concluded != null) licenses?.let(::nameExpressions)
        licenses?.forEach { entry ->
            val target = (entry.get("license") as? ObjectNode)
                ?: (entry as? ObjectNode)?.takeIf { it.has("expression") }
            if (target != null && !target.has("acknowledgement")) target.put("acknowledgement", "declared")
        }
        if (concluded == null) return
        // only an expression that is not text is left, and license objects cannot join it in the list
        if (licenses != null && licenses.any { it.has("expression") }) return
        val list = component.arrayOrNew("licenses") ?: return
        for (id in concluded.split(" AND ")) {
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

    // a license takes free text as its name, so the declared expression can sit next to license ids in one piece
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

    // Gradle resolved the whole graph, so the dependency list is complete; the guideline wants that said
    private fun declareComplete(root: ObjectNode) {
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
        val composition = compositions.addObject().put("aggregate", "complete")
        val list = composition.putArray("dependencies")
        refs.forEach(list::add)
    }

    private const val FILENAME = "bsi:component:filename"
    private const val EXECUTABLE = "bsi:component:executable"
    private const val ARCHIVE = "bsi:component:archive"
    private const val STRUCTURED = "bsi:component:structured"
}
