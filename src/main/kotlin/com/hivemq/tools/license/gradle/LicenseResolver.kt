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

/**
 * A dependency and the licenses it is used under, every one of which applies. [declaredExpression] is one SPDX
 * expression in canonical form naming every way the dependency's declared licenses allow it to be used, when they name
 * more than one license; null for a pnpm package, for a single license, for a license not taken from the declared
 * entries (an override, a built-in license, a project module) and for a declared expression that names a license
 * without an SPDX form; a built-in expression counts as declared.
 */
data class ResolvedDependency(
    val coordinates: Coordinates,
    val licenses: List<KnownLicense>,
    val declaredExpression: String? = null,
) {
    init {
        require(licenses.isNotEmpty()) { "'$coordinates' needs at least one license" }
    }
}

object LicenseResolver {

    // written by cyclonedx-gradle-plugin (3.5.0+) on a component whose Maven metadata could not be fully read
    const val METADATA_UNRESOLVED_PROPERTY = "cdx:maven:package:metadata-unresolved"

    // the first CycloneDX 1.x minor with component properties, which carry METADATA_UNRESOLVED_PROPERTY
    private const val FIRST_MINOR_WITH_PROPERTIES = 3

    // a 1.x spec version with its minor, as "1.6" or "1.6.1"; other majors pass here, generateSbom rejects them
    private val SPEC_VERSION_1 = Regex("""1\.(\d+)(?:\.\d+)?""")

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

    // the reviewed meaning of each multi-license POM list, one per list a module's versions declare; a module whose
    // BOM lists several licenses fails without an entry here (README, Built-in expressions)
    internal val BUILT_IN_EXPRESSIONS: Map<String, List<BuiltInExpression>> =
        buildMap<String, MutableList<BuiltInExpression>> {
            fun add(module: String, builtIn: BuiltInExpression) = getOrPut(module) { mutableListOf() }.let { list ->
                check(builtIn !in list) { "'$module' lists $builtIn twice" }
                val recorded = builtIn.declared.normalized()
                check(recorded.isEmpty() || list.none { it.declared.normalized() == recorded }) {
                    "'$module' records the list ${builtIn.declared} twice"
                }
                list += builtIn
            }

            fun choice(expression: String, vararg modules: String) =
                modules.forEach { add(it, BuiltInExpression(expression)) }

            fun replacement(module: String, expression: String, vararg declared: String) =
                add(module, BuiltInExpression(expression, declared.toList()))

            choice("CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0", "javax.xml.bind:jaxb-api")
            choice(
                "EPL-1.0 OR BSD-3-Clause",
                "org.eclipse.collections:eclipse-collections",
                "org.eclipse.collections:eclipse-collections-api",
                "org.eclipse.collections:eclipse-collections-forkjoin",
            )
            choice(
                "EPL-2.0 OR Apache-2.0",
                "org.eclipse.jetty.ee11:jetty-ee11-servlet",
                "org.eclipse.jetty:jetty-bom",
                "org.eclipse.jetty:jetty-http",
                "org.eclipse.jetty:jetty-io",
                "org.eclipse.jetty:jetty-security",
                "org.eclipse.jetty:jetty-server",
                "org.eclipse.jetty:jetty-session",
                "org.eclipse.jetty:jetty-util",
            )
            choice("EPL-2.0 OR BSD-3-Clause", "org.locationtech.jts:jts-core")
            choice(
                "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0",
                "jakarta.annotation:jakarta.annotation-api",
                "jakarta.servlet:jakarta.servlet-api",
                "jakarta.ws.rs:jakarta.ws.rs-api",
                "javax.ws.rs:javax.ws.rs-api",
                "org.glassfish.hk2.external:aopalliance-repackaged",
                "org.glassfish.hk2:hk2-api",
                "org.glassfish.hk2:hk2-locator",
                "org.glassfish.hk2:hk2-utils",
                "org.glassfish.hk2:osgi-resource-locator",
            )
            choice("EPL-2.0 OR LGPL-2.1-only", "ch.qos.logback:logback-classic", "ch.qos.logback:logback-core")
            choice("LGPL-2.1-only OR Apache-2.0", "net.java.dev.jna:jna", "net.java.dev.jna:jna-platform")
            choice(
                "LGPL-2.1-or-later OR Apache-2.0",
                "net.java.dev.jna:jna",
                "net.java.dev.jna:jna-jpms",
                "net.java.dev.jna:jna-platform",
                "net.java.dev.jna:jna-platform-jpms",
            )
            choice(
                "LGPL-3.0-or-later OR Apache-2.0",
                "com.github.java-json-tools:btf",
                "com.github.java-json-tools:jackson-coreutils",
                "com.github.java-json-tools:json-patch",
                "com.github.java-json-tools:msg-simple",
            )
            choice("MPL-1.1 OR LGPL-2.1-only OR Apache-2.0", "org.javassist:javassist")
            choice("WTFPL OR Apache-2.0", "org.reflections:reflections")
            // a choice of four, three given by name; the one with no SPDX id has ScanCode's LicenseDB key
            replacement(
                "com.unboundid:unboundid-ldapsdk",
                "Apache-2.0 OR GPL-2.0-only OR LGPL-2.1-only OR LicenseRef-scancode-ldap-sdk-free-use",
                "Apache-2.0",
                "GNU General Public License version 2 (GPLv2)",
                "GNU Lesser General Public License version 2.1 (LGPLv2.1)",
                "UnboundID LDAP SDK Free Use License",
            )
            replacement("net.logstash.logback:logstash-logback-encoder", "Apache-2.0 AND MIT", "Apache-2.0", "MIT")
            // five Jersey modules inherit the project's list, which also names what other Jersey jars bundle and the
            // examples; the other three declare their own, whose comments give each extra license to bundled code
            val jersey = "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0"
            val jerseyProject = arrayOf(
                "EPL-2.0", "GPL-2.0-with-classpath-exception", "BSD-3-Clause", "BSD-2-Clause", "Apache License, 2.0",
                "Public Domain", "Modified BSD", "jQuery license", "MIT", "W3C license",
            )
            val jerseyGpl = "The GNU General Public License (GPL), Version 2, With Classpath Exception"
            for (module in listOf(
                "org.glassfish.jersey.containers:jersey-container-jdk-http",
                "org.glassfish.jersey.core:jersey-client",
                "org.glassfish.jersey.ext:jersey-entity-filtering",
                "org.glassfish.jersey.inject:jersey-hk2",
                "org.glassfish.jersey.media:jersey-media-multipart",
            )) {
                replacement(module, jersey, *jerseyProject)
            }
            // a repackaged Guava, and the public domain (CC0) JSR-166 classes that 2.x and 3.x jars bundle as well
            replacement(
                "org.glassfish.jersey.core:jersey-common",
                "($jersey) AND Apache-2.0 AND CC0-1.0",
                "EPL-2.0", jerseyGpl, "Apache License, 2.0", "Public Domain",
            )
            // ASM, and classes derived from Dropwizard
            replacement(
                "org.glassfish.jersey.core:jersey-server",
                "($jersey) AND Apache-2.0 AND BSD-3-Clause",
                "EPL-2.0", jerseyGpl, "Apache License, 2.0", "Modified BSD",
            )
            // the Jackson JAX-RS providers
            replacement(
                "org.glassfish.jersey.media:jersey-media-json-jackson",
                "($jersey) AND Apache-2.0",
                "EPL-2.0", jerseyGpl, "Apache License, 2.0",
            )
            replacement(
                "org.jboss.resteasy:resteasy-tracing-api",
                "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0",
                "EPL-2.0",
                "GNU General Public License, Version 2 with the Classpath Exception",
            )
            replacement(
                "org.rocksdb:rocksdbjni",
                "Apache-2.0 OR GPL-2.0-only",
                "Apache-2.0",
                "GNU General Public License, version 2",
            )
        }

    /**
     * The SPDX [expression] a module's license list means, and the [declared] entries, each as CycloneDX writes it, of
     * a list that does not declare the expression itself; empty for a list that does.
     */
    internal data class BuiltInExpression(val expression: String, val declared: List<String> = emptyList())

    // what to do about a list of several licenses the plugin has not reviewed in this form
    private const val LIST_REMEDY = "check the POM's <comments>, then add the list to the plugin's built-in " +
        "expressions, or name the license in overriddenLicenses until a release has it"

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
     * The licenses of the components in [dependencyLicenseFile], or a failure that lists every license problem found;
     * a CycloneDX BOM older than 1.3 fails at once, as it cannot carry [METADATA_UNRESOLVED_PROPERTY]. Before the
     * licenses are read, the BOM is filtered ([BomFilter]): the internal own modules go, the modules in
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
        val specVersion = root.get("specVersion")?.takeIf { it.isTextual }?.asText()
        val minor = specVersion?.let { SPEC_VERSION_1.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        check(minor == null || minor >= FIRST_MINOR_WITH_PROPERTIES) {
            "The BOM ${source.describe()} declares CycloneDX specVersion $specVersion, which has no component " +
                "properties, so an own module whose Maven metadata is incomplete cannot be told from an internal " +
                "one; set the schemaVersion of generateDependencyBom, or of the task that writes it, to " +
                "1.$FIRST_MINOR_WITH_PROPERTIES or later"
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
            // an entry that offers nothing is no part of the list
            val listed = component.licenses.orEmpty().filterNot(::offersNothing)
            // every entry is one way the component may be used
            val declaredOptions = listed.mapNotNull { licenseOption(it, coordinates) }
            val written = listed.map(::describeChoice)
            // the table covers lists of several licenses; one license, or none, resolves as for any other module
            val builtIns = if (namesSeveralLicenses(declaredOptions)) {
                BUILT_IN_EXPRESSIONS[coordinates.moduleId].orEmpty()
            } else {
                emptyList()
            }
            val builtIn = builtIns.firstOrNull { it.covers(written, declaredOptions) }
            if (builtIns.isNotEmpty() && builtIn == null) {
                problems += "Declared licenses of '$coordinates'${describeUnresolvedMetadata(component)} are " +
                    "${quote(written)}, but ${describeBuiltIns(builtIns)}: $LIST_REMEDY"
                return
            }
            // a built-in expression replaces the list as the only way
            val options = builtIn?.let { listOf(expressionOption(it.expression)) } ?: declaredOptions
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
            // a list of several licenses passes only as reviewed, as its POM may say in a comment that they all apply
            if (builtIn == null && options.distinctBy(::licenseKey).size > 1) {
                problems += "Declared licenses of '$coordinates'${describeUnresolvedMetadata(component)} are " +
                    "${quote(written)}, a list the plugin reads as a choice but has no built-in expression for: " +
                    LIST_REMEDY
                return
            }
            val declared = SpdxExpression.expressionOf(options.map { it.declared })
            entries[coordinates.moduleId] = ResolvedDependency(coordinates, chosenLicenses, declared)
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

    /**
     * One way a component may be used: the [licenses] that then apply together, or null when one of them is not
     * approved, and what was [declared] as a syntax tree, null for a license entry without an id (given only by a name
     * or URL) and for an expression that is malformed or too complex to evaluate.
     */
    private class LicenseOption(val licenses: List<KnownLicense>?, val name: String, val declared: SpdxExpression.Node?)

    /**
     * An id as written; a name or URL gets none, as the patterns of [convertLicense] approve a license without
     * describing it exactly (every LGPL name with 2.1 passes as LGPL-2.1-or-later) and the expression replaces the
     * declared entries.
     */
    private fun declaredLicense(entry: DependencyReport.LicenseEntry): SpdxExpression.Node? =
        entry.id?.let { SpdxExpression.Node.Leaf(it) }

    /** Whether a license entry offers nothing: no expression and no license id, name or URL that is not blank. */
    private fun offersNothing(choice: DependencyReport.LicenseChoice): Boolean = choice.expression.isNullOrBlank() &&
        choice.license?.run { id.isNullOrBlank() && name.isNullOrBlank() && url.isNullOrBlank() } != false

    /**
     * The entries of a license list in a fixed order and in lower case, as SPDX matches ids regardless of case and a
     * name differing only in case names the same license.
     */
    private fun List<String>.normalized() = map { it.lowercase() }.sorted()

    /** The way one entry of a license list allows use; null for an entry with neither a license nor an expression. */
    private fun licenseOption(choice: DependencyReport.LicenseChoice, coordinates: Coordinates): LicenseOption? {
        val license = choice.license
        val expression = choice.expression
        return when {
            license != null -> convertLicense(license, coordinates).let {
                LicenseOption((it as? KnownLicense)?.let(::listOf), it.fullName, declaredLicense(license))
            }

            expression != null -> expressionOption(expression)
            else -> null
        }
    }

    /**
     * What tells two entries of a list apart: what was declared, as its order-free canonical key or, without a
     * canonical form, as written; else the approved licenses, else the name, so that one license given by id and by a
     * name the plugin maps to it counts once.
     */
    private fun licenseKey(option: LicenseOption): String =
        option.declared?.let { it.key() ?: it.render().lowercase() }
            ?: option.licenses?.joinToString(" AND ") { it.id.lowercase() }
            ?: option.name.lowercase()

    /**
     * Whether the entries of a list allowing the [options] name several licenses: different ones, or one declared
     * expression that combines several, also one with a license without an SPDX form.
     */
    private fun namesSeveralLicenses(options: List<LicenseOption>): Boolean =
        options.distinctBy(::licenseKey).size > 1 || options.any { option ->
            val declared = option.declared
            // a tree with no canonical form still combines several, while "MIT OR mit" names one
            declared != null && declared !is SpdxExpression.Node.Leaf &&
                declared.canonical() !is SpdxExpression.Node.Leaf
        }

    /**
     * Whether this built-in expression stands in for a list [written] as CycloneDX wrote it, whose entries allow the
     * [options]: the entries it was written for in any order, or entries that, read as a choice, declare the expression
     * themselves, as SPDX ids or as one expression.
     */
    private fun BuiltInExpression.covers(written: List<String>, options: List<LicenseOption>): Boolean {
        if (declared.isNotEmpty() && written.normalized() == declared.normalized()) return true
        val alternatives = options.map { it.declared ?: return false }
        return SpdxExpression.sameExpression(SpdxExpression.Node.Or(alternatives), SpdxExpression(expression).tree)
    }

    /** What the [builtIns] of a module were written for, the lists they record and any list that declares one. */
    private fun describeBuiltIns(builtIns: List<BuiltInExpression>): String {
        val expressions = builtIns.joinToString(" or ") { "'${it.expression}'" }
        val recorded = builtIns.filter { it.declared.isNotEmpty() }
            .joinToString("") { "the list ${quote(it.declared)} or " }
        val (subject, target) = when (builtIns.size) {
            1 -> "expression $expressions was" to "it"
            else -> "expressions $expressions were" to "one of them"
        }
        return "the plugin's built-in $subject written for ${recorded}a list that declares $target"
    }

    /** The way a declared SPDX [expression] allows use: its licenses when approved, how it reads, and its tree. */
    private fun expressionOption(expression: String): LicenseOption = SpdxExpression(expression).let { parsed ->
        val result = parsed.evaluate()
        LicenseOption(result.licenses, result.describe(expression), parsed.tree)
    }

    /** A license entry as CycloneDX wrote it: its expression, else the first of its id, name and URL. */
    private fun describeChoice(choice: DependencyReport.LicenseChoice): String =
        choice.expression ?: choice.license?.run { id ?: name ?: url } ?: ""

    /** The [items], each in single quotes, separated by commas. */
    private fun quote(items: List<String>) = items.joinToString { "'$it'" }

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
                val result = SpdxExpression(spdxId).evaluate()
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
