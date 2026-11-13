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

import com.hivemq.tools.license.gradle.OwnModules.Kind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OwnModulesTest {

    /** The plugin's defaults: HiveMQ's groups, its Maven Central libraries public, every other module internal. */
    private val defaults = OwnModules(setOf("com.hivemq"), OwnModules.PUBLIC_HIVEMQ_MODULES)

    private fun hivemq(name: String) = Coordinates("com.hivemq", name, "1.0.0")

    // --- Classification ---

    @Test
    fun `a module of a group below an own prefix is own`() {
        assertThat(defaults.isOwn(Coordinates("com.hivemq", "hivemq-extension-sdk", "4.0.0"))).isTrue()
        assertThat(defaults.isOwn(Coordinates("com.hivemq.internal", "internal-lib", "1.0.2"))).isTrue()
        // a prefix names a group, not the start of one, so a foreign group that merely begins alike is not own
        assertThat(defaults.isOwn(Coordinates("com.hivemqx", "lib", "1.0"))).isFalse()
        assertThat(defaults.isOwn(Coordinates("org.com.hivemq", "lib", "1.0"))).isFalse()
    }

    @Test
    fun `a module of another group is third-party, license or not`() {
        val coordinates = Coordinates("com.fasterxml.jackson.core", "jackson-databind", "2.15.0")

        assertThat(defaults.classify(coordinates)).isEqualTo(Kind.THIRD_PARTY)
        assertThat(defaults.classify(coordinates, mayBePublic = true)).isEqualTo(Kind.THIRD_PARTY)
    }

    @Test
    fun `a module on the public list is public, license or not`() {
        assertThat(defaults.classify(hivemq("hivemq-extension-sdk"))).isEqualTo(Kind.PUBLIC)
        assertThat(defaults.classify(hivemq("hivemq-extension-sdk"), mayBePublic = true)).isEqualTo(Kind.PUBLIC)
    }

    @Test
    fun `a blank prefix names nothing`() {
        val own = OwnModules(setOf("", " "), emptySet())

        assertThat(own.isOwn(Coordinates("com.hivemq", "lib", "1.0"))).isFalse()
        assertThat(own.isOwn(Coordinates("", "@hivemq/ui-library", "1.0.0"))).isFalse()
    }

    @Test
    fun `a prefix with a trailing dot names the same group`() {
        val own = OwnModules(setOf("com.example."), emptySet())

        assertThat(own.isOwn(Coordinates("com.example", "lib", "1.0"))).isTrue()
        assertThat(own.isOwn(Coordinates("com.example.internal", "lib", "1.0"))).isTrue()
        assertThat(own.isOwn(Coordinates("com.examplex", "lib", "1.0"))).isFalse()
    }

    @Test
    fun `a project of another group is third-party`() {
        val coordinates = Coordinates("com.example", "lib", "1.0.0")

        assertThat(defaults.classify(coordinates, project = true)).isEqualTo(Kind.THIRD_PARTY)
    }

    @Test
    fun `a project of the build is internal unless public, license or not`() {
        assertThat(defaults.classify(hivemq("hivemq-brand-new"), project = true)).isEqualTo(Kind.INTERNAL)
        assertThat(defaults.classify(hivemq("hivemq-brand-new"), project = true, mayBePublic = true))
            .isEqualTo(Kind.INTERNAL)
        assertThat(defaults.classify(hivemq("hivemq-extension-sdk"), project = true)).isEqualTo(Kind.PUBLIC)
    }

    @Test
    fun `an own module that may be public without being listed is unlisted`() {
        assertThat(defaults.classify(hivemq("hivemq-brand-new"), mayBePublic = true)).isEqualTo(Kind.UNLISTED)
    }

    @Test
    fun `an own module without a license is internal`() {
        assertThat(defaults.classify(hivemq("hivemq-internal-sdk"))).isEqualTo(Kind.INTERNAL)
        assertThat(defaults.classify(Coordinates("com.hivemq.internal", "internal-lib", "1.0.2")))
            .isEqualTo(Kind.INTERNAL)
    }

    @Test
    fun `nothing is own without prefixes`() {
        val own = OwnModules(emptySet(), emptySet())

        assertThat(own.classify(hivemq("hivemq-brand-new"), mayBePublic = true)).isEqualTo(Kind.THIRD_PARTY)
    }

    // --- Packages without a group ---

    @Test
    fun `a package is matched by its name when it has no group`() {
        val own = OwnModules(setOf("@hivemq/"), emptySet())

        assertThat(own.classify(Coordinates("", "@hivemq/ui-library", "1.0.0"))).isEqualTo(Kind.INTERNAL)
        assertThat(own.classify(Coordinates("", "@fontsource/raleway", "5.2.8"))).isEqualTo(Kind.THIRD_PARTY)
    }

    @Test
    fun `a public package is listed`() {
        val own = OwnModules(setOf("@hivemq/"), setOf("@hivemq/public-lib"))

        assertThat(own.classify(Coordinates("", "@hivemq/public-lib", "1.0.0"))).isEqualTo(Kind.PUBLIC)
    }

    // --- Patterns ---

    @Test
    fun `a dot in a pattern is literal`() {
        // the prefix covers both groups, so only the pattern tells them apart
        val own = OwnModules(setOf("com"), setOf("com.hivemq.internal:*"))

        assertThat(own.isPublic("com.hivemq.internal:lib")).isTrue()
        assertThat(own.classify(Coordinates("com.hivemqXcommons", "lib", "1.0"), mayBePublic = true))
            .isEqualTo(Kind.UNLISTED)
    }

    @Test
    fun `a pattern matches any run of characters at the star`() {
        val own = OwnModules(setOf("com.hivemq"), setOf("com.hivemq:hivemq-mqtt-client*"))

        assertThat(own.isPublic("com.hivemq:hivemq-mqtt-client")).isTrue()
        assertThat(own.isPublic("com.hivemq:hivemq-mqtt-client-websocket")).isTrue()
        assertThat(own.isPublic("com.hivemq:hivemq-mqtt-clientele")).isTrue()
        assertThat(own.isPublic("com.hivemq:hivemq-mqtt")).isFalse()
    }

    @Test
    fun `a pattern without a star matches the whole module id only`() {
        assertThat(defaults.isPublic("com.hivemq:hivemq-extension-sdk-2")).isFalse()
        assertThat(defaults.isPublic("org.com.hivemq:hivemq-extension-sdk")).isFalse()
    }

    @Test
    fun `a public pattern covers own modules only`() {
        val own = OwnModules(setOf("com.hivemq"), setOf("*"))

        assertThat(own.isPublic("com.hivemq:anything")).isTrue()
        assertThat(own.isPublic("org.example:lib")).isFalse()
        assertThat(own.classify(hivemq("anything"), mayBePublic = true)).isEqualTo(Kind.PUBLIC)
        assertThat(own.classify(Coordinates("org.example", "lib", "1.0"))).isEqualTo(Kind.THIRD_PARTY)
    }

    // --- Problem text ---

    @Test
    fun `describes an unlisted module that declares a license with its prefix and the remedies`() {
        assertThat(defaults.describeUnlisted(hivemq("hivemq-brand-new"))).isEqualTo(
            "Own module 'com.hivemq:hivemq-brand-new' (ignored group prefix 'com.hivemq') declares a license but is " +
                "not in allowedArtifacts: a public module has to be listed there, an internal one declares no " +
                "license, and a third-party group does not belong in ignoredGroupPrefixes"
        )
    }

    @Test
    fun `describes an unlisted module with incomplete Maven metadata, naming the reason`() {
        assertThat(defaults.describeUnlisted(hivemq("hivemq-brand-new"), unresolvedMetadata = "parent-unresolved"))
            .isEqualTo(
                "Own module 'com.hivemq:hivemq-brand-new' (ignored group prefix 'com.hivemq') is not in " +
                    "allowedArtifacts and its Maven metadata is incomplete (parent-unresolved), so it may be a " +
                    "public module: list it there, or fix its POM or the repository it comes from"
            )
    }

    // --- Projects of the build ---

    @Test
    fun `a project is recognised by its purl or by its bom-ref`() {
        val project = "pkg:maven/com.hivemq/app@1.0.0?project_path=%3A"
        val module = "pkg:maven/org.example/lib@1.0?type=jar"

        assertThat(OwnModules.isProjectComponent(project, null)).isTrue()
        assertThat(OwnModules.isProjectComponent(null, project)).isTrue()
        assertThat(OwnModules.isProjectComponent(module, module)).isFalse()
        assertThat(OwnModules.isProjectComponent(null, null)).isFalse()
    }

    @Test
    fun `a project purl carries the project path`() {
        assertThat(OwnModules.isProjectPurl("pkg:maven/com.hivemq/app@1.0.0?project_path=%3A")).isTrue()
        assertThat(OwnModules.isProjectPurl("pkg:maven/com.hivemq/app@1.0.0?type=jar&project_path=%3Aapp")).isTrue()
        assertThat(OwnModules.isProjectPurl("pkg:maven/org.example/lib@1.0?type=jar")).isFalse()
        assertThat(OwnModules.isProjectPurl("pkg:maven/org.example/project_path@1.0?type=jar")).isFalse()
        assertThat(OwnModules.isProjectPurl(null)).isFalse()
    }
}
