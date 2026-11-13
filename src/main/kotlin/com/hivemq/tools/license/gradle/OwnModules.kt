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

/**
 * Sorts the modules of the build's own groups, the ones [ownGroupPrefixes] name with their subgroups, into public
 * ones, listed like any third-party library, and internal ones, left out. The plugin names no internal module: every
 * own module that [publicArtifacts] does not match is internal, unless it declares a license, which internal modules
 * never do, or its metadata is incomplete: such a one is a public module nobody listed and fails the build until it is.
 */
internal class OwnModules(
    ownGroupPrefixes: Set<String>,
    publicArtifacts: Set<String>,
) {
    enum class Kind { THIRD_PARTY, PUBLIC, INTERNAL, UNLISTED }

    // a blank prefix would name every group, or every package, which nobody means
    private val ownGroupPrefixes = ownGroupPrefixes.filter { it.isNotBlank() }
    private val public = publicArtifacts.map(::pattern)

    /** Whether [coordinates] belong to one of the own groups; a package without a group is matched by its name. */
    fun isOwn(coordinates: Coordinates): Boolean = ownGroupPrefixOf(coordinates) != null

    /** Whether the module `group:name`, or the package name, is an own module one of the public patterns matches. */
    fun isPublic(moduleId: String): Boolean {
        val coordinates = Coordinates(moduleId.substringBefore(':', ""), moduleId.substringAfter(':'), "")
        return isOwn(coordinates) && public.any { it.matches(moduleId) }
    }

    /**
     * [project]: built by this build or an included one, internal unless public whatever it declares. [mayBePublic]:
     * the component declares a license, or its license is unknown, which makes an unlisted own module [Kind.UNLISTED]
     * instead of internal.
     */
    fun classify(coordinates: Coordinates, project: Boolean = false, mayBePublic: Boolean = false): Kind = when {
        !isOwn(coordinates) -> Kind.THIRD_PARTY
        isPublic(coordinates.moduleId) -> Kind.PUBLIC
        project || !mayBePublic -> Kind.INTERNAL
        else -> Kind.UNLISTED
    }

    /**
     * The problem an unlisted own module adds to the license check: it declares a license, as only a public module
     * does, or its Maven metadata is incomplete, which [unresolvedMetadata] says how, so nobody knows what it declares.
     */
    fun describeUnlisted(coordinates: Coordinates, unresolvedMetadata: String? = null): String {
        val module = "Own module '${coordinates.moduleId}' (ignored group prefix '${ownGroupPrefixOf(coordinates)}')"
        return if (unresolvedMetadata == null) {
            "$module declares a license but is not in allowedArtifacts: a public module has to be listed there, an " +
                "internal one declares no license, and a third-party group does not belong in ignoredGroupPrefixes"
        } else {
            "$module is not in allowedArtifacts and its Maven metadata is incomplete ($unresolvedMetadata), so it " +
                "may be a public module: list it there, or fix its POM or the repository it comes from"
        }
    }

    // a prefix names a group and its subgroups, with or without a trailing dot: com.hivemq covers com.hivemq.tools
    // but not com.hivemqx
    private fun ownGroupPrefixOf(coordinates: Coordinates): String? = ownGroupPrefixes.firstOrNull { prefix ->
        val group = coordinates.group
        if (group.isEmpty()) return@firstOrNull coordinates.name.startsWith(prefix)
        val named = prefix.removeSuffix(".")
        group == named || group.startsWith("$named.")
    }

    companion object {
        /** HiveMQ's libraries on Maven Central, all Apache-2.0: listed by default, wherever they ship. */
        val PUBLIC_HIVEMQ_MODULES: Set<String> = sortedSetOf(
            "com.hivemq:hivemq-amazon-kinesis-extension-customization-sdk",
            "com.hivemq:hivemq-community-edition",
            "com.hivemq:hivemq-community-edition-embedded",
            "com.hivemq:hivemq-edge-adapter-sdk",
            "com.hivemq:hivemq-edge-extension-sdk",
            "com.hivemq:hivemq-enterprise-security-extension-customization-sdk",
            "com.hivemq:hivemq-extension-sdk",
            "com.hivemq:hivemq-gcp-pubsub-extension-customization-sdk",
            "com.hivemq:hivemq-google-cloud-pubsub-extension-customization-sdk",
            "com.hivemq:hivemq-kafka-extension-customization-sdk",
            "com.hivemq:hivemq-mqtt-client",
            "com.hivemq:hivemq-mqtt-client-epoll",
            "com.hivemq:hivemq-mqtt-client-proxy",
            "com.hivemq:hivemq-mqtt-client-reactor",
            "com.hivemq:hivemq-mqtt-client-shaded",
            "com.hivemq:hivemq-mqtt-client-websocket",
            "com.hivemq:hivemq-mtconnect-protocol",
            "com.hivemq:hivemq-spi",
            "com.hivemq:hivemq-swarm-extension-sdk",
            "com.hivemq:hivemq-testcontainer-core",
            "com.hivemq:hivemq-testcontainer-junit4",
            "com.hivemq:hivemq-testcontainer-junit5",
        )

        // the qualifier CycloneDX adds to the purl of a Gradle project, included builds too
        private val projectPath = Regex("[?&]project_path=")

        /** Whether a package URL, or a bom-ref built from one, marks a project of this build or of an included one. */
        fun isProjectPurl(purl: String?): Boolean = purl != null && projectPath.containsMatchIn(purl)

        /** Whether a component is such a project, by its purl or by its bom-ref, as a BOM may carry no purl. */
        fun isProjectComponent(purl: String?, bomRef: String?): Boolean = isProjectPurl(purl) || isProjectPurl(bomRef)

        // `*` matches any run of characters, everything else is literal
        private fun pattern(text: String) = Regex(text.split('*').joinToString(".*") { Regex.escape(it) })
    }
}
