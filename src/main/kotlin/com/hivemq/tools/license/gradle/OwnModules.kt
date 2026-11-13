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
 * Sorts the modules of the build's own groups, the ones [ownGroupPrefixes] name, into public ones, listed like any
 * third-party library, internal ones, left out, and the rest, which fails the build until it is classified.
 */
internal class OwnModules(
    private val ownGroupPrefixes: Set<String>,
    publicArtifacts: Set<String>,
    internalArtifacts: Set<String>,
) {
    enum class Kind { THIRD_PARTY, PUBLIC, INTERNAL, UNCLASSIFIED }

    private val public = publicArtifacts.map(::pattern)
    private val internal = internalArtifacts.map(::pattern)

    /** Whether [coordinates] belong to one of the own groups; a package without a group is matched by its name. */
    fun isOwn(coordinates: Coordinates): Boolean = ownGroupPrefixOf(coordinates) != null

    /** Whether the module `group:name`, or the package name, is an own module one of the public patterns matches. */
    fun isPublic(moduleId: String): Boolean {
        val coordinates = Coordinates(moduleId.substringBefore(':', ""), moduleId.substringAfter(':'), "")
        return isOwn(coordinates) && public.any { it.matches(moduleId) }
    }

    /** [project]: the component is built by this build or an included one, which makes it internal unless public. */
    fun classify(coordinates: Coordinates, project: Boolean = false): Kind = when {
        !isOwn(coordinates) -> Kind.THIRD_PARTY
        isPublic(coordinates.moduleId) -> Kind.PUBLIC
        project || internal.any { it.matches(coordinates.moduleId) } -> Kind.INTERNAL
        else -> Kind.UNCLASSIFIED
    }

    /** The problem an own module that matches neither list adds to the license check. */
    fun describeUnclassified(coordinates: Coordinates): String =
        "Own module '${coordinates.moduleId}' (ignored group prefix '${ownGroupPrefixOf(coordinates)}') is in " +
            "neither allowedArtifacts nor internalArtifacts: add it to allowedArtifacts to list it, or to " +
            "internalArtifacts to leave it out"

    private fun ownGroupPrefixOf(coordinates: Coordinates): String? = ownGroupPrefixes.firstOrNull { prefix ->
        coordinates.group.startsWith(prefix) || (coordinates.group.isEmpty() && coordinates.name.startsWith(prefix))
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
            "com.hivemq:hivemq-swarm-extension-sdk",
        )

        /** HiveMQ's modules from private repositories, and its frontend packages: left out by default. */
        val INTERNAL_HIVEMQ_MODULES: Set<String> = sortedSetOf(
            "@hivemq/*",
            "com.hivemq.commons:*",
            "com.hivemq:hivemq-clustering",
            "com.hivemq:hivemq-data-hub",
            "com.hivemq:hivemq-enterprise",
            "com.hivemq:hivemq-enterprise-extension-sdk",
            "com.hivemq:hivemq-platform-sdk",
            "com.hivemq:hivemq-pulse-*",
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
