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

    // built-in license mappings for well-known artifacts that don't report license metadata in CycloneDX
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

    // the approved license with this SPDX ID, matched regardless of case, or null
    private fun knownLicense(spdxId: String): KnownLicense? =
        SPDX_ID_MAP[spdxId] ?: SPDX_IDS_IGNORING_CASE[spdxId.lowercase()]

    fun resolveLicenses(
        dependencyLicenseFile: File,
        ignoredGroupPrefixes: Set<String>,
        allowedArtifacts: Set<String>,
        overriddenLicenses: Map<String, String> = emptyMap(),
        dependencyLicenseOrigin: () -> String? = { null },
    ): TreeMap<String, ResolvedDependency> {
        val (entries, problems) =
            resolve(dependencyLicenseFile, ignoredGroupPrefixes, allowedArtifacts, overriddenLicenses)
        failOnProblems(LicenseSource(dependencyLicenseFile, dependencyLicenseOrigin), problems)
        return entries
    }

    /**
     * The licenses that resolve, nothing else: a component with no license, an unapproved one or an invalid override
     * is left out instead of failing, so a license problem stays a matter for [resolveLicenses] and the report tasks.
     * A file that cannot be read or parsed still fails.
     */
    internal fun resolveKnownLicenses(
        dependencyLicenseFile: File,
        ignoredGroupPrefixes: Set<String>,
        allowedArtifacts: Set<String>,
        overriddenLicenses: Map<String, String> = emptyMap(),
    ): TreeMap<String, ResolvedDependency> =
        resolve(dependencyLicenseFile, ignoredGroupPrefixes, allowedArtifacts, overriddenLicenses).first

    // what resolves and a message for each component that does not, so the caller decides whether that is a failure
    private fun resolve(
        dependencyLicenseFile: File,
        ignoredGroupPrefixes: Set<String>,
        allowedArtifacts: Set<String>,
        overriddenLicenses: Map<String, String>,
    ): Pair<TreeMap<String, ResolvedDependency>, List<String>> {
        val objectMapper = ObjectMapper()
        val tree = objectMapper.readTree(dependencyLicenseFile)
        // an override naming an unknown license is a problem of its own, and its module is not resolved any further
        val invalidOverrides = overriddenLicenses.filterValues { knownLicense(it) == null }
        val problems = invalidOverrides.map { (moduleId, spdxId) ->
            "Overridden license '$spdxId' for '$moduleId' is not a known SPDX license ID"
        }.toMutableList()
        if (!tree.has("components")) {
            return resolvePnpmLicenses(
                tree,
                ignoredGroupPrefixes,
                allowedArtifacts,
                overriddenLicenses,
                invalidOverrides.keys,
                problems,
            )
        }
        val bom = objectMapper.treeToValue(tree, DependencyReport.Bom::class.java)
        val entries = TreeMap<String, ResolvedDependency>()
        for (component in bom.components) {
            val group = component.group ?: continue
            val coordinates = Coordinates(group, component.name, component.version)
            if (shouldIgnore(coordinates, ignoredGroupPrefixes, allowedArtifacts)) continue
            if (coordinates.moduleId in invalidOverrides) continue
            val resolvedLicense = resolveOverriddenOrBuiltIn(coordinates.moduleId, overriddenLicenses)
            if (resolvedLicense != null) {
                entries[coordinates.moduleId] = ResolvedDependency(coordinates, listOf(resolvedLicense))
                continue
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
                problems += "No license found for '$coordinates'" + describeUnresolvedMetadata(component)
                continue
            }
            val chosenLicenses = chooseOption(options.map { it.licenses })
            if (chosenLicenses == null) {
                problems += "License not on the approved list for '$coordinates'${describeUnresolvedMetadata(component)}: " +
                        options.joinToString { it.name }
                continue
            }
            entries[coordinates.moduleId] = ResolvedDependency(coordinates, chosenLicenses)
        }
        return Pair(entries, problems)
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
        tree: JsonNode,
        ignoredGroupPrefixes: Set<String>,
        allowedArtifacts: Set<String>,
        overriddenLicenses: Map<String, String>,
        invalidOverrides: Set<String>,
        problems: MutableList<String>,
    ): Pair<TreeMap<String, ResolvedDependency>, List<String>> {
        val entries = TreeMap<String, ResolvedDependency>()
        for ((_, packages) in tree.properties()) {
            for (pkg in packages) {
                val name = pkg.get("name")?.asText() ?: continue
                val version = pkg.get("versions")?.firstOrNull()?.asText() ?: continue
                val spdxId = pkg.get("license")?.asText()
                val coordinates = Coordinates("", name, version)
                if (shouldIgnore(coordinates, ignoredGroupPrefixes, allowedArtifacts)) continue
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
        return Pair(entries, problems)
    }

    fun shouldIgnore(
        coordinates: Coordinates,
        ignoredGroupPrefixes: Set<String>,
        allowedArtifacts: Set<String>
    ): Boolean {
        return coordinates.moduleId !in allowedArtifacts && ignoredGroupPrefixes.any {
            coordinates.group.startsWith(it) || (coordinates.group.isEmpty() && coordinates.name.startsWith(
                it
            ))
        }
    }

    // the caller has already set aside modules whose override names an unknown license, so one here is a bug
    private fun resolveOverriddenOrBuiltIn(moduleId: String, overriddenLicenses: Map<String, String>): KnownLicense? =
        overriddenLicenses[moduleId]?.let { spdxId ->
            checkNotNull(knownLicense(spdxId)) { "Overridden license '$spdxId' for '$moduleId' was not set aside" }
        } ?: BUILT_IN_LICENSES[moduleId]

    // Evaluates an SPDX license expression such as "(CDDL-1.0 OR GPL-2.0-with-classpath-exception)" to the licenses
    // that apply when it is satisfied, or to the reason when the expression itself is at fault. "A OR B" is satisfied
    // by any approved alternative, and the one with the fewest obligations is reported. "A AND B" needs every operand
    // approved and reports all of them. AND binds tighter than OR, parentheses group, a license with a WITH exception
    // is not approved as the license, and a malformed expression is never satisfied.
    private fun evaluateExpression(expression: String): ExpressionResult = SpdxExpression(expression).evaluate()

    // the licenses an expression is satisfied by, or null with the reason when that is a fault of the expression
    private class ExpressionResult(val licenses: List<KnownLicense>?, val problem: String? = null) {
        fun describe(expression: String) = if (problem == null) expression else "$expression ($problem)"
    }

    // Parses the expression into every way it can be satisfied, each one the licenses that then apply together, and
    // ranks them once the whole expression is read, so an alternative is never chosen before an enclosing AND adds
    // to it. An unknown license satisfies nothing; a syntax error is tracked apart, so "MIT OR )" is not satisfied
    // by its MIT. An alternative that adds licenses to another can never rank better, so it is dropped as soon as it
    // appears. A candidate seen before, and an AND operand every alternative already includes, cost nothing; an
    // expression that produces more candidates than MAX_CANDIDATES, keeps more alternatives than MAX_OPTIONS, or
    // nests parentheses deeper than MAX_DEPTH, is not evaluated.
    private class SpdxExpression(expression: String) {
        private val tokens = TOKEN.findAll(expression).map { it.value }.toList()
        private var position = 0
        private var wellFormed = true
        private var withinBudget = true
        private var candidates = 0
        private var depth = 0

        fun evaluate(): ExpressionResult {
            val options = disjunction()
            return when {
                !withinBudget -> ExpressionResult(null, "SPDX expression too complex to evaluate")
                !wellFormed || position != tokens.size -> ExpressionResult(null, "malformed SPDX expression")
                else -> ExpressionResult(
                    chooseOption(options.map { option -> option.sortedBy { LICENSE_ORDER.indexOf(it) } })
                )
            }
        }

        // disjunction := conjunction ("OR" conjunction)*
        private fun disjunction(): List<Set<KnownLicense>> {
            val first = conjunction()
            // a lone conjunction already holds no option that covers another
            if (!acceptOperator("OR")) return first
            // the alternatives were produced, and charged where they were products, by their conjunctions
            val options = Options()
            for (alternative in first) {
                options.add(alternative, charged = false)
            }
            do {
                for (alternative in conjunction()) {
                    options.add(alternative, charged = false)
                }
            } while (acceptOperator("OR"))
            return options.list
        }

        // conjunction := term ("AND" term)*
        private fun conjunction(): List<Set<KnownLicense>> {
            var options = term()
            while (acceptOperator("AND")) {
                val operand = term()
                // an operand every alternative already includes leaves them as they are
                if (operand.isNotEmpty() && options.all { left -> operand.all(left::containsAll) }) continue
                val combined = Options()
                for (left in options) {
                    if (!withinBudget) break
                    for (right in operand) {
                        combined.add(left + right, charged = true)
                    }
                }
                options = combined.list
            }
            return options
        }

        // The alternatives collected so far. A candidate is kept unless an option it covers exists already, and it
        // drops the options that cover it. A candidate seen before costs nothing; a charged one, which is every
        // product of a conjunction, counts against the budget, and the expression is given up once the candidates or
        // the options kept exceed it.
        private inner class Options {
            val list = mutableListOf<Set<KnownLicense>>()
            private val seen = HashSet<Set<KnownLicense>>()

            fun add(candidate: Set<KnownLicense>, charged: Boolean) {
                if (!withinBudget || !seen.add(candidate)) return
                if (charged && ++candidates > MAX_CANDIDATES) {
                    withinBudget = false
                    return
                }
                if (list.any { candidate.containsAll(it) }) return
                list.removeAll { it.containsAll(candidate) }
                list += candidate
                if (list.size > MAX_OPTIONS) {
                    withinBudget = false
                }
            }
        }

        // term := "(" disjunction ")" | license-id ["WITH" exception-id]
        private fun term(): List<Set<KnownLicense>> {
            val token = tokens.getOrNull(position++) ?: return malformed()
            if (token == "(") {
                if (++depth > MAX_DEPTH) {
                    withinBudget = false
                    return emptyList()
                }
                val inner = disjunction()
                depth--
                return if (tokens.getOrNull(position++) == ")") inner else malformed()
            }
            if (!isLicense(token)) return malformed()
            val license = knownLicense(token)
            if (acceptOperator("WITH")) {
                val exception = tokens.getOrNull(position++)
                return if (exception != null && isIdString(exception)) emptyList() else malformed()
            }
            return if (license == null) emptyList() else listOf(setOf(license))
        }

        // a known id, or what the SPDX grammar allows as a simple expression: a license reference, or a license id
        // with an optional "+"; "Public Domain" is known and not SPDX
        private fun isLicense(token: String) = when {
            knownLicense(token) != null -> true
            REFERENCE_PREFIX.containsMatchIn(token) -> LICENSE_REFERENCE.matches(token)
            else -> isIdString(token.removeSuffix("+"))
        }

        private fun isIdString(token: String) =
            ID_STRING.matches(token) && OPERATORS.none { it.equals(token, ignoreCase = true) }

        private fun malformed(): List<Set<KnownLicense>> {
            wellFormed = false
            return emptyList()
        }

        private fun acceptOperator(operator: String): Boolean {
            if (!tokens.getOrNull(position).equals(operator, ignoreCase = true)) return false
            position++
            return true
        }

        private companion object {
            // a known id containing whitespace is matched first, so "Public Domain" stays one token, and only up to
            // a token boundary, so "Public DomainOR" is not read as "Public Domain OR"
            val TOKEN = Regex(
                SPDX_ID_MAP.keys.filter { it.any(Char::isWhitespace) }
                    .joinToString("") { Regex.escape(it) + """(?=[\s()]|$)|""" } +
                    """[()]|[^\s()]+""",
                RegexOption.IGNORE_CASE,
            )
            val ID_STRING = Regex("""[A-Za-z0-9.-]+""")
            val REFERENCE_PREFIX = Regex("""^(LicenseRef|DocumentRef)-""", RegexOption.IGNORE_CASE)
            val LICENSE_REFERENCE =
                Regex("""(DocumentRef-[A-Za-z0-9.-]+:)?LicenseRef-[A-Za-z0-9.-]+""", RegexOption.IGNORE_CASE)
            val OPERATORS = listOf("AND", "OR", "WITH")
            const val MAX_OPTIONS = 1024
            const val MAX_CANDIDATES = 16_384
            const val MAX_DEPTH = 32
        }
    }

    // Picks, among the ways a dependency may be used, the one with the fewest obligations: the option whose least
    // permissive license comes first in LICENSE_ORDER, and of equally ranked options the one with fewer licenses. The
    // first of equal options wins.
    private fun chooseOption(options: List<List<KnownLicense>?>): List<KnownLicense>? = options
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
