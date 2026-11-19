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
import com.fasterxml.jackson.databind.node.ObjectNode
import com.hivemq.tools.license.gradle.sbom.BomFilter
import java.io.File
import java.util.*

/** A dependency and the licenses it is used under, every one of which applies. */
data class ResolvedDependency(val coordinates: Coordinates, val licenses: List<KnownLicense>) {
    init {
        require(licenses.isNotEmpty()) { "'$coordinates' needs at least one license" }
    }
}

object LicenseResolver {

    // written by cyclonedx-gradle-plugin (3.5.0+) on a component whose Maven metadata could not be fully read
    const val METADATA_UNRESOLVED_PROPERTY = "cdx:maven:package:metadata-unresolved"

    // ranks the approved licenses, most permissive first: it decides which of several alternatives to choose, which of
    // several alternatives has the fewest obligations, and the order in which licenses that apply together are listed
    val LICENSE_ORDER = listOf(
        KnownLicense.APACHE_2_0,
        KnownLicense.MIT,
        KnownLicense.MIT_0,
        KnownLicense.ZERO_BSD,
        KnownLicense.UNLICENSE,
        KnownLicense.BOUNCY_CASTLE,
        KnownLicense.BLUE_OAK_1_0_0,
        KnownLicense.ISC,
        KnownLicense.BSD_3_CLAUSE,
        KnownLicense.BSD_2_CLAUSE,
        KnownLicense.GO,
        KnownLicense.CC0_1_0,
        KnownLicense.CC_BY_4_0,
        KnownLicense.OFL_1_1,
        KnownLicense.PUBLIC_DOMAIN,
        KnownLicense.W3C,
        KnownLicense.W3C_19980720,
        KnownLicense.EDL_1_0,
        KnownLicense.EPL_2_0,
        KnownLicense.EPL_1_0,
        KnownLicense.CDDL_1_1,
        KnownLicense.CDDL_1_0,
        KnownLicense.UNICODE_3_0,
        KnownLicense.UNICODE_DFS_2016,
        KnownLicense.UPL_1_0,
        KnownLicense.LGPL_2_1_OR_LATER,
    )

    // well-known artifacts whose POM declares no license, or one the BOM maps to another id or expression; like the
    // overrides, these win over the BOM
    val BUILT_IN_LICENSES = mapOf(
        "javax.activation:javax.activation-api" to KnownLicense.CDDL_1_1,
        "javax.annotation:javax.annotation-api" to KnownLicense.CDDL_1_1,
        "javax.servlet:javax.servlet-api" to KnownLicense.CDDL_1_1,
        "javax.websocket:javax.websocket-api" to KnownLicense.CDDL_1_1,
        "javax.websocket:javax.websocket-client-api" to KnownLicense.CDDL_1_1,
        "javax.ws.rs:jsr311-api" to KnownLicense.CDDL_1_1,
        "org.antlr:ST4" to KnownLicense.BSD_3_CLAUSE,
        "org.antlr:antlr-runtime" to KnownLicense.BSD_3_CLAUSE,
        "org.ow2.asm:asm" to KnownLicense.BSD_3_CLAUSE,
        "org.picocontainer:picocontainer" to KnownLicense.BSD_3_CLAUSE,
        "org.w3c.css:sac" to KnownLicense.W3C_19980720,
    )

    // maps SPDX license IDs from CycloneDX to known licenses (first match in LICENSE_ORDER wins)
    val SPDX_ID_MAP = buildMap {
        for (license in LICENSE_ORDER) {
            putIfAbsent(license.id, license)
        }
    }

    private val SPDX_IDS_IGNORING_CASE = buildMap {
        for ((id, license) in SPDX_ID_MAP) {
            putIfAbsent(id.lowercase(), license)
        }
    }

    /** The approved license with this SPDX ID, matched regardless of case, or null. */
    internal fun knownLicense(spdxId: String): KnownLicense? =
        SPDX_ID_MAP[spdxId] ?: SPDX_IDS_IGNORING_CASE[spdxId.lowercase()]

    /**
     * The licenses of the components in [dependencyLicenseFile], or a failure that lists every license problem found.
     * Before the licenses are read, the BOM is filtered ([BomFilter]): the internal own modules go, the modules in
     * [providedModules] go unless public, and an unlisted own module that may be public is a problem.
     */
    fun resolveLicenses(
        dependencyLicenseFile: File,
        ignoredGroupPrefixes: Set<String>,
        allowedArtifacts: Set<String>,
        overriddenLicenses: Map<String, String> = emptyMap(),
        providedModules: Set<String> = emptySet(),
        dependencyLicenseOrigin: () -> String? = { null },
    ): TreeMap<String, ResolvedDependency> {
        val objectMapper = ObjectMapper()
        val tree = objectMapper.readTree(dependencyLicenseFile)
        val source = LicenseSource(dependencyLicenseFile, dependencyLicenseOrigin)
        val own = OwnModules(ignoredGroupPrefixes, allowedArtifacts)
        // an override naming an unknown license is a problem of its own, and its module is not resolved any further
        val invalidOverrides = overriddenLicenses.filterValues { knownLicense(it) == null }
        val problems = invalidOverrides.map { (moduleId, spdxId) ->
            "Overridden license '$spdxId' for '$moduleId' is not a known SPDX license ID"
        }.toMutableList()
        // a CycloneDX BOM may leave out an empty components array; a pnpm report, keyed by license name, has neither
        val root = tree as? ObjectNode
        if (root == null || (root.get("bomFormat")?.textValue() != "CycloneDX" && !root.has("components"))) {
            return resolvePnpmLicenses(source, tree, own, overriddenLicenses, invalidOverrides.keys, problems)
        }
        // own modules are classified on the whole tree first, so a new one cannot hide behind hivemqProvided; the
        // filter runs on the tree, so a left-out component never reaches the model
        val unlisted = linkedMapOf<String, Pair<Coordinates, String?>>()
        for (component in BomFilter.components(root)) {
            val coordinates = BomFilter.coordinatesOf(component) ?: continue
            val kind = own.classify(coordinates, BomFilter.isProject(component), BomFilter.mayBePublic(component))
            if (kind != OwnModules.Kind.UNLISTED) continue
            // once per module, whatever the number of versions; a declared license, in any version, is the stronger
            // sign of a public module, so its message wins over incomplete metadata
            val unresolved = BomFilter.unresolvedMetadata(component).takeIf { !BomFilter.declaresLicense(component) }
            if (coordinates.moduleId !in unlisted || unresolved == null) {
                unlisted[coordinates.moduleId] = coordinates to unresolved
            }
        }
        for ((coordinates, unresolved) in unlisted.values) {
            problems += own.describeUnlisted(coordinates, unresolved)
        }
        // the same filter as the SBOM's, but the provided components that stay are the public ones, listed with nothing
        // nested in them, as none of that ships
        val publicProvided = BomFilter.keepShipped(root, own, providedModules) { _, _ ->
            { component: ObjectNode -> own.isPublic(BomFilter.moduleIdOf(component)) }
        }
        BomFilter.dropNested(root) { it in publicProvided }
        // reported above; gone before binding, which a version-less one would otherwise fail with another message;
        // after the filter, so that what a provided unlisted module carried has gone with it
        BomFilter.dropModules(root, unlisted.keys) { true }
        val bom = objectMapper.treeToValue(root, DependencyReport.Bom::class.java)
        val entries = TreeMap<String, ResolvedDependency>()
        fun resolveOne(component: DependencyReport.Component) {
            val group = component.group ?: return
            val coordinates = Coordinates(group, component.name, component.version)
            if (coordinates.moduleId in invalidOverrides) return
            val resolvedLicense = resolveOverriddenOrBuiltIn(coordinates.moduleId, overriddenLicenses)
            if (resolvedLicense != null) {
                entries[coordinates.moduleId] = ResolvedDependency(coordinates, listOf(resolvedLicense))
                return
            }
            // every entry is one way the component may be used
            val options = (component.licenses ?: emptyList()).mapNotNull { choice ->
                val license = choice.license
                val expression = choice.expression
                when {
                    license != null -> convertLicense(license, coordinates).let {
                        LicenseOption((it as? KnownLicense)?.let(::listOf), it.fullName)
                    }

                    expression != null -> evaluateExpression(expression).let { result ->
                        LicenseOption(result.licenses, result.describe(expression))
                    }
                    else -> null
                }
            }
            if (options.isEmpty()) {
                // Apache-2.0 for a public HiveMQ module built as a project, which has no POM and so no license in the
                // BOM; a published version of the same module declares its license
                val project = OwnModules.isProjectComponent(component.purl, component.bomRef)
                if (project && coordinates.moduleId in OwnModules.PUBLIC_HIVEMQ_MODULES) {
                    entries[coordinates.moduleId] = ResolvedDependency(coordinates, listOf(KnownLicense.APACHE_2_0))
                    return
                }
                problems += "No license found for '$coordinates'" + describeUnresolvedMetadata(component)
                return
            }
            val chosenLicenses = chooseOption(options.map { it.licenses })
            if (chosenLicenses == null) {
                problems += "License not on the approved list for '$coordinates'" +
                    "${describeUnresolvedMetadata(component)}: ${options.joinToString { it.name }}"
                return
            }
            entries[coordinates.moduleId] = ResolvedDependency(coordinates, chosenLicenses)
        }
        // nested components are resolved on their own, in document order; the filter above already decided which stay
        fun resolve(components: List<DependencyReport.Component>) {
            for (component in components) {
                resolveOne(component)
                resolve(component.components.orEmpty())
            }
        }
        resolve(bom.components)
        resolve(bom.metadata?.component?.components.orEmpty())
        failOnProblems(source, problems)
        return entries
    }

    // one way a component may be used: the licenses that then apply together, or null when one of them is not approved
    private class LicenseOption(val licenses: List<KnownLicense>?, val name: String)

    // the file the licenses were read from and, when known, how this build obtained it; finding out the origin may
    // wait for a build event, so it is only asked for when a message needs it
    private class LicenseSource(val file: File, val origin: () -> String?) {
        fun describe(): String {
            val origin = origin()
            return if (origin == null) "'${file.absolutePath}'" else "'${file.absolutePath}' ($origin)"
        }
    }

    private fun describeUnresolvedMetadata(component: DependencyReport.Component): String {
        val reason = component.properties
            ?.firstOrNull { it.name == METADATA_UNRESOLVED_PROPERTY }
            ?.value
            ?: return ""
        return " (its Maven metadata is incomplete: $reason)"
    }

    private fun failOnProblems(source: LicenseSource, problems: List<String>) {
        check(problems.isEmpty()) {
            val noun = if (problems.size == 1) "problem" else "problems"
            "${problems.size} license $noun found in ${source.describe()}:\n" +
                    problems.joinToString("\n") { "  $it" }
        }
    }

    private fun resolvePnpmLicenses(
        source: LicenseSource,
        tree: JsonNode,
        own: OwnModules,
        overriddenLicenses: Map<String, String>,
        invalidOverrides: Set<String>,
        problems: MutableList<String>,
    ): TreeMap<String, ResolvedDependency> {
        val entries = TreeMap<String, ResolvedDependency>()
        for ((_, packages) in tree.properties()) {
            for (pkg in packages) {
                val name = pkg.get("name")?.asText() ?: continue
                val version = pkg.get("versions")?.firstOrNull()?.asText() ?: continue
                val spdxId = pkg.get("license")?.asText()
                val coordinates = Coordinates("", name, version)
                // an own package is hidden unless listed, whatever it declares: internal frontend packages carry
                // licenses, so a license tells nothing about being public here
                if (own.classify(coordinates) == OwnModules.Kind.INTERNAL) continue
                if (coordinates.moduleId in invalidOverrides) continue
                val resolvedLicense = resolveOverriddenOrBuiltIn(coordinates.moduleId, overriddenLicenses)
                if (resolvedLicense != null) {
                    entries[coordinates.moduleId] = ResolvedDependency(coordinates, listOf(resolvedLicense))
                    continue
                }
                if (spdxId == null || spdxId == "Unknown") {
                    problems += "No license found for '$coordinates'"
                    continue
                }
                val result = evaluateExpression(spdxId)
                if (result.licenses == null) {
                    problems += "License not on the approved list for '$coordinates': ${result.describe(spdxId)}"
                    continue
                }
                entries[coordinates.moduleId] = ResolvedDependency(coordinates, result.licenses)
            }
        }
        failOnProblems(source, problems)
        return entries
    }

    // the caller has already set aside modules whose override names an unknown license, so one here is a bug
    private fun resolveOverriddenOrBuiltIn(moduleId: String, overriddenLicenses: Map<String, String>): KnownLicense? =
        overriddenLicenses[moduleId]?.let { spdxId ->
            checkNotNull(knownLicense(spdxId)) { "Overridden license '$spdxId' for '$moduleId' was not set aside" }
        } ?: BUILT_IN_LICENSES[moduleId]

    private fun evaluateExpression(expression: String): ExpressionResult = SpdxExpression(expression).evaluate()

    /**
     * Picks, among the ways a dependency may be used, the one with the fewest obligations: the option whose least
     * permissive license comes first in [LICENSE_ORDER], and of equally ranked options the one with fewer licenses.
     * The first of equal options wins.
     */
    internal fun chooseOption(options: List<List<KnownLicense>?>): List<KnownLicense>? = options
        .filterNotNull()
        .minWithOrNull(compareBy({ option -> option.maxOf { LICENSE_ORDER.indexOf(it) } }, { it.size }))

    fun convertLicense(license: DependencyReport.LicenseEntry, coordinates: Coordinates): License {
        // CycloneDX provides SPDX IDs directly when available
        val spdxId = license.id
        if (spdxId != null) {
            val known = knownLicense(spdxId)
            if (known != null) return known
            return UnknownLicense(spdxId, license.url)
        }

        // fall back to name/url-based matching
        val name = license.name ?: return UnknownLicense("Unknown", license.url)
        val url = license.url
        return when {
            name.matches(".*Apache.*[\\s\\-v](2\\.0.*|2(\\s.*|$))".toRegex()) -> KnownLicense.APACHE_2_0
            name == "Bouncy Castle Licence" -> KnownLicense.BOUNCY_CASTLE
            name.matches("(.*BSD.*2.*[Cc]lause.*)|(.*2.*[Cc]lause.*BSD.*)".toRegex()) -> KnownLicense.BSD_2_CLAUSE
            name.matches("(.*BSD.*3.*[Cc]lause.*)|(.*3.*[Cc]lause.*BSD.*)|(.*[Nn]ew.*BSD.*)|(.*BSD.*[Nn]ew.*)".toRegex()) || (url == "https://opensource.org/licenses/BSD-3-Clause") -> KnownLicense.BSD_3_CLAUSE
            name.matches(".*Blue.*Oak.*1\\.0.*".toRegex()) -> KnownLicense.BLUE_OAK_1_0_0
            name.matches(".*Creative.*Commons.*Attribution.*4\\.0.*".toRegex()) -> KnownLicense.CC_BY_4_0
            name == "CC0" -> KnownLicense.CC0_1_0
            name == "Public Domain, per Creative Commons CC0" -> KnownLicense.CC0_1_0
            url == "https://glassfish.dev.java.net/public/CDDLv1.0.html" -> KnownLicense.CDDL_1_0
            (url == "https://oss.oracle.com/licenses/CDDL+GPL-1.1") || (url == "https://github.com/javaee/javax.annotation/blob/master/LICENSE") || (url == "https://glassfish.java.net/public/CDDL+GPL_1_1.html") -> KnownLicense.CDDL_1_1
            name.matches(".*(EDL|Eclipse.*Distribution.*License).*1\\.0.*".toRegex()) -> KnownLicense.EDL_1_0
            name.matches(".*(EPL|Eclipse.*Public.*License).*1\\.0.*".toRegex()) -> KnownLicense.EPL_1_0
            name.matches(".*(EPL|Eclipse.*Public.*License).*2\\.0.*".toRegex()) -> KnownLicense.EPL_2_0
            name == "Go License" -> KnownLicense.GO
            name.matches(".*MIT(\\s.*|$)".toRegex()) -> KnownLicense.MIT
            name.matches(".*MIT-0.*".toRegex()) -> KnownLicense.MIT_0
            name == "Public Domain" -> KnownLicense.PUBLIC_DOMAIN
            name == "Unicode/ICU License" -> KnownLicense.UNICODE_3_0
            name == "Universal Permissive License, Version 1.0" -> KnownLicense.UPL_1_0
            url == "http://www.w3.org/Consortium/Legal/copyright-software-19980720" -> KnownLicense.W3C_19980720
            name.matches(".*(LGPL|GNU.*Lesser).*2\\.1.*".toRegex()) -> KnownLicense.LGPL_2_1_OR_LATER
            // from here license name and url are not enough to determine the exact license, so we checked the specific dependency manually
            (name == "BSD") && (((coordinates.group == "dk.brics.automaton") && (coordinates.name == "automaton")) || ((coordinates.group == "org.picocontainer") && (coordinates.name == "picocontainer")) || ((coordinates.group == "org.ow2.asm") && (coordinates.name == "asm"))) -> KnownLicense.BSD_3_CLAUSE
            else -> UnknownLicense(name, url)
        }
    }
}
