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

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end coverage for the `hivemqLicense` extension option paths that are otherwise only exercised by resolver unit
 * tests: `overriddenLicenses` and `excludedDependencies`. These paths depend on runtime dependency resolution and on the
 * CycloneDX `includeConfigs` wiring, so they are the option paths most exposed to a CycloneDX version bump.
 */
class LicenseOptionsIntegrationTest {

    @TempDir
    lateinit var projectDir: File

    @Suppress("SameParameterValue")
    private fun gradleRunner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args)

    @Test
    fun `overriddenLicenses replaces the license resolved from the BOM`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "override-project"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
                // org.slf4j:slf4j-api is published under MIT; force it to Apache-2.0 to prove the override wins.
                overriddenLicenses.put("org.slf4j:slf4j-api", "Apache-2.0")
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation("org.slf4j:slf4j-api:2.0.16")
            }
            """.trimIndent()
        )

        val result = gradleRunner("updateThirdPartyLicenses").build()

        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val slf4jLine = projectDir.resolve("build/tmp/third-party-licenses/licenses").readLines()
            .single { it.contains("org.slf4j:slf4j-api") }
        assertThat(slf4jLine).contains("Apache-2.0")
        assertThat(slf4jLine).doesNotContain("MIT")
    }

    @Test
    fun `excludedDependencies drops the dependency from the BOM and re-adds it with an overridden license`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "exclude-project"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
                // Exclude slf4j-api from the CycloneDX BOM, then provide its license manually.
                excludedDependencies.add("org.slf4j:slf4j-api")
                overriddenLicenses.put("org.slf4j:slf4j-api", "MIT")
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation("com.google.guava:guava:33.4.0-jre")
                implementation("org.slf4j:slf4j-api:2.0.16")
            }
            """.trimIndent()
        )

        val result = gradleRunner("updateThirdPartyLicenses").build()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val slf4jLine = projectDir.resolve("build/tmp/third-party-licenses/licenses").readLines()
            .single { it.contains("org.slf4j:slf4j-api") }
        // Version comes from resolving the runtime classpath; license comes from overriddenLicenses.
        assertThat(slf4jLine).contains("2.0.16")
        assertThat(slf4jLine).contains("MIT")
        // The non-excluded dependency is still resolved from the BOM as usual.
        assertThat(projectDir.resolve("build/tmp/third-party-licenses/licenses").readText())
            .contains("com.google.guava:guava")
    }

    @Test
    fun `allowedArtifacts re-includes a single artifact excluded by ignoredGroupPrefixes`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "allowed-project"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
                // Ignore the whole com.google.guava group, then re-include only guava itself.
                ignoredGroupPrefixes.add("com.google.guava")
                allowedArtifacts.add("com.google.guava:guava")
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                // guava 33.4.0-jre pulls com.google.guava:failureaccess transitively (same ignored group).
                implementation("com.google.guava:guava:33.4.0-jre")
            }
            """.trimIndent()
        )

        val result = gradleRunner("updateThirdPartyLicenses").build()

        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val plaintext = projectDir.resolve("build/tmp/third-party-licenses/licenses").readText()
        // guava is re-included by allowedArtifacts despite matching the ignored prefix.
        assertThat(plaintext).contains("com.google.guava:guava")
        // failureaccess shares the ignored group and is not on the allow-list, so it stays excluded.
        assertThat(plaintext).doesNotContain("com.google.guava:failureaccess")
    }
}
