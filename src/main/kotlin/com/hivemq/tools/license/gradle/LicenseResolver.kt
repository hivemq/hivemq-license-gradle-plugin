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
import java.util.TreeMap

data class ResolvedDependency(val coordinates: Coordinates, val license: KnownLicense)

object LicenseResolver {

    // defines the license to choose, if multiple licenses are available for an artifact
    val LICENSE_ORDER = listOf(
        KnownLicense.APACHE_2_0,
        KnownLicense.MIT,
        KnownLicense.MIT_0,
        KnownLicense.ZERO_BSD,
        KnownLicense.UNLICENSE,
        KnownLicense.BOUNCY_CASTLE,
        KnownLicense.ISC,
        KnownLicense.BSD_3_CLAUSE,
        KnownLicense.BSD_2_CLAUSE,
        KnownLicense.GO,
        KnownLicense.CC0_1_0,
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
    )

    // built-in license mappings for well-known artifacts that don't report license metadata in CycloneDX
    val BUILT_IN_LICENSES = mapOf(
        "javax.activation:javax.activation-api" to KnownLicense.CDDL_1_1,
        "javax.annotation:javax.annotation-api" to KnownLicense.CDDL_1_1,
        "javax.servlet:javax.servlet-api" to KnownLicense.CDDL_1_1,
        "javax.websocket:javax.websocket-api" to KnownLicense.CDDL_1_1,
        "javax.websocket:javax.websocket-client-api" to KnownLicense.CDDL_1_1,
        "org.antlr:ST4" to KnownLicense.BSD_3_CLAUSE,
        "org.antlr:antlr-runtime" to KnownLicense.BSD_3_CLAUSE,
        "org.ow2.asm:asm" to KnownLicense.BSD_3_CLAUSE,
        "org.picocontainer:picocontainer" to KnownLicense.BSD_3_CLAUSE,
    )

    // maps SPDX license IDs from CycloneDX to known licenses (first match in LICENSE_ORDER wins)
    val SPDX_ID_MAP = buildMap {
        for (license in LICENSE_ORDER) {
            putIfAbsent(license.id, license)
        }
    }

    fun shouldIgnore(coordinates: Coordinates, ignoredGroupPrefixes: Set<String>, allowedArtifacts: Set<String>): Boolean {
        if (coordinates.moduleId in allowedArtifacts) return false
        return ignoredGroupPrefixes.any { coordinates.group.startsWith(it) || (coordinates.group.isEmpty() && coordinates.name.startsWith(it)) }
    }

    fun convertLicense(license: DependencyReport.LicenseEntry, coordinates: Coordinates): License {
        // CycloneDX provides SPDX IDs directly when available
        val spdxId = license.id
        if (spdxId != null) {
            val known = SPDX_ID_MAP[spdxId]
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
            // from here license name and url are not enough to determine the exact license, so we checked the specific dependency manually
            (name == "BSD") && (((coordinates.group == "dk.brics.automaton") && (coordinates.name == "automaton")) || ((coordinates.group == "org.picocontainer") && (coordinates.name == "picocontainer")) || ((coordinates.group == "org.ow2.asm") && (coordinates.name == "asm"))) -> KnownLicense.BSD_3_CLAUSE
            else -> UnknownLicense(name, url)
        }
    }

    fun chooseLicense(licenses: List<License>): KnownLicense? {
        var chosenLicense: KnownLicense? = null
        var indexOfChosenLicense = Int.MAX_VALUE
        for (license in licenses) {
            if (license is KnownLicense) {
                val indexOfLicense = LICENSE_ORDER.indexOf(license)
                if ((indexOfLicense != -1) && (indexOfLicense < indexOfChosenLicense)) {
                    chosenLicense = license
                    indexOfChosenLicense = indexOfLicense
                }
            }
        }
        return chosenLicense
    }

    fun resolveLicenses(
        dependencyLicenseFile: File,
        ignoredGroupPrefixes: Set<String>,
        allowedArtifacts: Set<String>,
        overriddenLicenses: Map<String, String> = emptyMap(),
    ): TreeMap<String, ResolvedDependency> {
        val objectMapper = ObjectMapper()
        val tree = objectMapper.readTree(dependencyLicenseFile)
        if (!tree.has("components")) {
            return resolvePnpmLicenses(tree, ignoredGroupPrefixes, allowedArtifacts, overriddenLicenses)
        }
        val bom = objectMapper.treeToValue(tree, DependencyReport.Bom::class.java)
        val entries = TreeMap<String, ResolvedDependency>()
        for (component in bom.components) {
            val group = component.group ?: continue
            val coordinates = Coordinates(group, component.name, component.version)
            if (shouldIgnore(coordinates, ignoredGroupPrefixes, allowedArtifacts)) continue
            val overriddenSpdxId = overriddenLicenses[coordinates.moduleId]
            if (overriddenSpdxId != null) {
                val knownLicense = checkNotNull(SPDX_ID_MAP[overriddenSpdxId]) {
                    "Overridden license '$overriddenSpdxId' for '$coordinates' is not a known SPDX license ID"
                }
                entries[coordinates.moduleId] = ResolvedDependency(coordinates, knownLicense)
                continue
            }
            val builtInLicense = BUILT_IN_LICENSES[coordinates.moduleId]
            if (builtInLicense != null) {
                entries[coordinates.moduleId] = ResolvedDependency(coordinates, builtInLicense)
                continue
            }
            val licenses = (component.licenses ?: emptyList())
                .mapNotNull { it.license }
                .map { convertLicense(it, coordinates) }
            check(licenses.isNotEmpty()) { "No license found for '$coordinates'" }
            val chosenLicense = checkNotNull(chooseLicense(licenses)) {
                "License not on the approved list for '$coordinates': ${licenses.joinToString { it.fullName }}"
            }
            entries[coordinates.moduleId] = ResolvedDependency(coordinates, chosenLicense)
        }
        return entries
    }

    private fun resolvePnpmLicenses(
        tree: JsonNode,
        ignoredGroupPrefixes: Set<String>,
        allowedArtifacts: Set<String>,
        overriddenLicenses: Map<String, String>,
    ): TreeMap<String, ResolvedDependency> {
        val entries = TreeMap<String, ResolvedDependency>()
        for ((_, packages) in tree.properties()) {
            for (pkg in packages) {
                val name = pkg.get("name")?.asText() ?: continue
                val version = pkg.get("versions")?.firstOrNull()?.asText() ?: continue
                val spdxId = pkg.get("license")?.asText()
                val coordinates = Coordinates("", name, version)
                if (shouldIgnore(coordinates, ignoredGroupPrefixes, allowedArtifacts)) continue
                val overriddenSpdxId = overriddenLicenses[coordinates.moduleId]
                if (overriddenSpdxId != null) {
                    val knownLicense = checkNotNull(SPDX_ID_MAP[overriddenSpdxId]) {
                        "Overridden license '$overriddenSpdxId' for '$coordinates' is not a known SPDX license ID"
                    }
                    entries[coordinates.moduleId] = ResolvedDependency(coordinates, knownLicense)
                    continue
                }
                val builtInLicense = BUILT_IN_LICENSES[coordinates.moduleId]
                if (builtInLicense != null) {
                    entries[coordinates.moduleId] = ResolvedDependency(coordinates, builtInLicense)
                    continue
                }
                check(spdxId != null && spdxId != "Unknown") { "No license found for '$coordinates'" }
                // handle compound SPDX expressions like "MIT AND ISC"
                val spdxIds = spdxId.split(" AND ", " OR ").map { it.trim() }
                val licenses = spdxIds.mapNotNull { SPDX_ID_MAP[it] }
                val knownLicense = checkNotNull(chooseLicense(licenses)) {
                    "License not on the approved list for '$coordinates': $spdxId"
                }
                entries[coordinates.moduleId] = ResolvedDependency(coordinates, knownLicense)
            }
        }
        return entries
    }
}
