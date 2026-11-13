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

    /** The plugin's defaults: HiveMQ's groups, its Maven Central libraries public, its private modules internal. */
    private val defaults = OwnModules(
        setOf("com.hivemq"),
        OwnModules.PUBLIC_HIVEMQ_MODULES,
        OwnModules.INTERNAL_HIVEMQ_MODULES,
    )

    private fun hivemq(name: String) = Coordinates("com.hivemq", name, "1.0.0")

    // --- Classification ---

    @Test
    fun `a module of a group below an own prefix is own`() {
        assertThat(defaults.isOwn(Coordinates("com.hivemq.commons", "hivemq-result", "1.0.2"))).isTrue()
        assertThat(defaults.isOwn(Coordinates("com.hivemqx", "lib", "1.0"))).isTrue()
        assertThat(defaults.isOwn(Coordinates("org.com.hivemq", "lib", "1.0"))).isFalse()
    }

    @Test
    fun `a module of another group is third-party`() {
        val coordinates = Coordinates("com.fasterxml.jackson.core", "jackson-databind", "2.15.0")

        assertThat(defaults.classify(coordinates)).isEqualTo(Kind.THIRD_PARTY)
    }

    @Test
    fun `a module on neither list is unclassified`() {
        assertThat(defaults.classify(hivemq("hivemq-brand-new"))).isEqualTo(Kind.UNCLASSIFIED)
    }

    @Test
    fun `a module on the internal list is internal`() {
        assertThat(defaults.classify(hivemq("hivemq-platform-sdk"))).isEqualTo(Kind.INTERNAL)
    }

    @Test
    fun `a module on the public list is public`() {
        assertThat(defaults.classify(hivemq("hivemq-extension-sdk"))).isEqualTo(Kind.PUBLIC)
    }

    @Test
    fun `a project of another group is third-party`() {
        val coordinates = Coordinates("com.example", "lib", "1.0.0")

        assertThat(defaults.classify(coordinates, project = true)).isEqualTo(Kind.THIRD_PARTY)
    }

    @Test
    fun `a project of the build is internal unless public`() {
        assertThat(defaults.classify(hivemq("hivemq-brand-new"), project = true)).isEqualTo(Kind.INTERNAL)
        assertThat(defaults.classify(hivemq("hivemq-extension-sdk"), project = true)).isEqualTo(Kind.PUBLIC)
    }

    @Test
    fun `nothing is own without prefixes`() {
        val own = OwnModules(emptySet(), emptySet(), emptySet())

        assertThat(own.classify(hivemq("hivemq-brand-new"))).isEqualTo(Kind.THIRD_PARTY)
    }

    @Test
    fun `the public list wins over the internal list`() {
        val own = OwnModules(setOf("com.hivemq"), setOf("com.hivemq:lib"), setOf("com.hivemq:*"))

        assertThat(own.classify(hivemq("lib"))).isEqualTo(Kind.PUBLIC)
    }

    // --- Defaults ---

    @Test
    fun `every default public module has a built-in license`() {
        assertThat(LicenseResolver.BUILT_IN_LICENSES).containsKeys(*OwnModules.PUBLIC_HIVEMQ_MODULES.toTypedArray())
        assertThat(OwnModules.PUBLIC_HIVEMQ_MODULES.map { LicenseResolver.BUILT_IN_LICENSES[it] })
            .containsOnly(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `the default internal list covers the frontend scope`() {
        val own = OwnModules(setOf("@hivemq/"), OwnModules.PUBLIC_HIVEMQ_MODULES, OwnModules.INTERNAL_HIVEMQ_MODULES)

        assertThat(own.classify(Coordinates("", "@hivemq/ui-library", "1.0.0"))).isEqualTo(Kind.INTERNAL)
    }

    // --- Packages without a group ---

    @Test
    fun `a package is matched by its name when it has no group`() {
        val own = OwnModules(setOf("@hivemq/"), emptySet(), setOf("@hivemq/*"))

        assertThat(own.classify(Coordinates("", "@hivemq/ui-library", "1.0.0"))).isEqualTo(Kind.INTERNAL)
        assertThat(own.classify(Coordinates("", "@fontsource/raleway", "5.2.8"))).isEqualTo(Kind.THIRD_PARTY)
    }

    @Test
    fun `a public package is listed`() {
        val own = OwnModules(setOf("@hivemq/"), setOf("@hivemq/public-lib"), setOf("@hivemq/*"))

        assertThat(own.classify(Coordinates("", "@hivemq/public-lib", "1.0.0"))).isEqualTo(Kind.PUBLIC)
    }

    // --- Patterns ---

    @Test
    fun `a dot in a pattern is literal`() {
        val own = OwnModules(setOf("com.hivemq"), emptySet(), setOf("com.hivemq.commons:*"))

        assertThat(own.classify(Coordinates("com.hivemqXcommons", "lib", "1.0"))).isEqualTo(Kind.UNCLASSIFIED)
    }

    @Test
    fun `a pattern matches any run of characters at the star`() {
        val own = OwnModules(setOf("com.hivemq"), setOf("com.hivemq:hivemq-mqtt-client*"), emptySet())

        assertThat(own.isPublic("com.hivemq:hivemq-mqtt-client")).isTrue()
        assertThat(own.isPublic("com.hivemq:hivemq-mqtt-client-websocket")).isTrue()
        assertThat(own.isPublic("com.hivemq:hivemq-mqtt-clientele")).isTrue()
        assertThat(own.isPublic("com.hivemq:hivemq-mqtt")).isFalse()
        assertThat(defaults.classify(hivemq("hivemq-pulse-agent-history"))).isEqualTo(Kind.INTERNAL)
    }

    @Test
    fun `a pattern without a star matches the whole module id only`() {
        assertThat(defaults.isPublic("com.hivemq:hivemq-extension-sdk-2")).isFalse()
        assertThat(defaults.isPublic("org.com.hivemq:hivemq-extension-sdk")).isFalse()
    }

    @Test
    fun `a public pattern covers own modules only`() {
        val own = OwnModules(setOf("com.hivemq"), setOf("*"), emptySet())

        assertThat(own.isPublic("com.hivemq:anything")).isTrue()
        assertThat(own.isPublic("org.example:lib")).isFalse()
        assertThat(own.classify(Coordinates("org.example", "lib", "1.0"))).isEqualTo(Kind.THIRD_PARTY)
    }

    @Test
    fun `a star alone matches everything`() {
        val own = OwnModules(setOf("com.hivemq"), emptySet(), setOf("*"))

        assertThat(own.classify(hivemq("anything"))).isEqualTo(Kind.INTERNAL)
    }

    // --- Problem text ---

    @Test
    fun `describes an unclassified module with its prefix and both lists`() {
        assertThat(defaults.describeUnclassified(hivemq("hivemq-brand-new"))).isEqualTo(
            "Own module 'com.hivemq:hivemq-brand-new' (ignored group prefix 'com.hivemq') is in neither " +
                "allowedArtifacts nor internalArtifacts: add it to allowedArtifacts to list it, or to " +
                "internalArtifacts to leave it out"
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
