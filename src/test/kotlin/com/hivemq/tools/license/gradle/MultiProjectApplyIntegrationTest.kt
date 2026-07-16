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
 * Covers a multi-project build where the plugin is applied independently in several subprojects rather than once at
 * the root.
 *
 * On apply the plugin runs a `project.allprojects { configurations.configureEach { ... } }` sweep. With per-subproject
 * application every applying subproject reaches across the whole build, so this test verifies that multiple
 * applications coexist and every `updateThirdPartyLicenses` task runs without cross-project configuration errors.
 */
class MultiProjectApplyIntegrationTest {

    @TempDir
    lateinit var projectDir: File

    @Suppress("SameParameterValue")
    private fun gradleRunner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args)

    private fun subprojectBuildFile() =
        """
        plugins {
            java
            id("com.hivemq.tools.license")
        }
        hivemqLicense {
            thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("reports/third-party-licenses"))
            ignoredGroupPrefixes.add("com.hivemq")
        }
        repositories {
            mavenCentral()
        }
        dependencies {
            implementation("org.slf4j:slf4j-api:2.0.16")
        }
        """.trimIndent()

    @Test
    fun `updateThirdPartyLicenses runs in every subproject when the plugin is applied per subproject`() {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "multi-project"
            include("module-a", "module-b")
            """.trimIndent()
        )
        // Root project intentionally does not apply the plugin; application happens only in the subprojects.
        projectDir.resolve("build.gradle.kts").writeText("")

        val moduleA = projectDir.resolve("module-a").apply { mkdirs() }
        val moduleB = projectDir.resolve("module-b").apply { mkdirs() }
        moduleA.resolve("build.gradle.kts").writeText(subprojectBuildFile())
        moduleB.resolve("build.gradle.kts").writeText(subprojectBuildFile())

        val result = gradleRunner("updateThirdPartyLicenses").build()

        assertThat(result.task(":module-a:cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":module-b:cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":module-a:updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":module-b:updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).doesNotContain("Cannot mutate the artifacts of configuration")

        for (module in listOf(moduleA, moduleB)) {
            val plaintext = module.resolve("build/reports/third-party-licenses/licenses").readText()
            assertThat(plaintext).contains("org.slf4j:slf4j-api")
        }
    }
}
