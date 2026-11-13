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
 * Covers generating a separate license report for a frontend from a pnpm license report: a second
 * `UpdateThirdPartyLicensesTask` is registered manually, its `dependencyLicense` input is a pnpm license report (not a
 * CycloneDX BOM), and it writes its own report via `outputFileNamePrefix`.
 *
 * This exercises the plugin surfaces that the CycloneDX-driven tests do not: the resolver's pnpm branch (a report
 * without a `components` array), the `outputFileNamePrefix` option, and manual task registration with a hand-provided
 * `dependencyLicense`.
 */
class PnpmFrontendLicensesIntegrationTest {

    @TempDir
    lateinit var projectDir: File

    /** Runs the frontend task, the only one these tests register. */
    private fun gradleRunner() = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments("updateFrontendLicenses")

    @Test
    fun `manually registered task generates a prefixed report from a pnpm license file`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "frontend-project"""")
        projectDir.resolve("frontend-licenses.json").writeText(
            """
            {
              "MIT": [
                { "name": "react", "versions": ["18.2.0"], "license": "MIT" }
              ],
              "ISC": [
                { "name": "lru-cache", "versions": ["10.2.0"], "license": "ISC" }
              ],
              "internal": [
                { "name": "@example/internal-widget", "versions": ["1.0.0"], "license": "MIT" }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import com.hivemq.tools.license.gradle.UpdateThirdPartyLicensesTask

            plugins {
                java
                id("com.hivemq.tools.license")
            }

            tasks.register("updateFrontendLicenses", UpdateThirdPartyLicensesTask::class.java) {
                projectName.set("Frontend")
                outputFileNamePrefix.set("frontend-")
                dependencyLicense.set(layout.projectDirectory.file("frontend-licenses.json"))
                ignoredGroupPrefixes.set(setOf("@example/"))
                internalArtifacts.add("@example/*")
                outputDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            }
            """.trimIndent()
        )

        val result = gradleRunner().build()

        assertThat(result.task(":updateFrontendLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val outputDir = projectDir.resolve("build/tmp/third-party-licenses")
        val plaintext = outputDir.resolve("frontend-licenses")
        val html = outputDir.resolve("frontend-licenses.html")
        assertThat(plaintext).exists()
        assertThat(html).exists()

        val text = plaintext.readText()
        assertThat(text)
            .contains("Frontend uses the following third party libraries")
            .contains("react")
            .contains("18.2.0")
            .contains("lru-cache")
            .contains("ISC")
        // a scoped package of the own prefix is left out when internalArtifacts names it
        assertThat(text).doesNotContain("@example/internal-widget")
    }

    @Test
    fun `manually registered task leaves the default internal scope out without a list of its own`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "frontend-project"""")
        projectDir.resolve("frontend-licenses.json").writeText(
            """
            {
              "MIT": [
                { "name": "react", "versions": ["18.2.0"], "license": "MIT" },
                { "name": "@hivemq/ui-library", "versions": ["1.0.0"], "license": "MIT" }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import com.hivemq.tools.license.gradle.UpdateThirdPartyLicensesTask

            plugins {
                java
                id("com.hivemq.tools.license")
            }

            tasks.register("updateFrontendLicenses", UpdateThirdPartyLicensesTask::class.java) {
                projectName.set("Frontend")
                outputFileNamePrefix.set("frontend-")
                dependencyLicense.set(layout.projectDirectory.file("frontend-licenses.json"))
                ignoredGroupPrefixes.set(setOf("@hivemq/"))
                outputDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            }
            """.trimIndent()
        )

        val result = gradleRunner().build()

        assertThat(result.task(":updateFrontendLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        // the extension's internalArtifacts, @hivemq/* among them, reach a task the build registers
        val text = projectDir.resolve("build/tmp/third-party-licenses/frontend-licenses").readText()
        assertThat(text).contains("react").doesNotContain("@hivemq/ui-library")
    }

    @Test
    fun `manually registered task replaces a default list with set`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "frontend-project"""")
        projectDir.resolve("frontend-licenses.json").writeText(
            """
            {
              "MIT": [
                { "name": "@hivemq/ui-library", "versions": ["1.0.0"], "license": "MIT" }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import com.hivemq.tools.license.gradle.UpdateThirdPartyLicensesTask

            plugins {
                java
                id("com.hivemq.tools.license")
            }

            tasks.register("updateFrontendLicenses", UpdateThirdPartyLicensesTask::class.java) {
                projectName.set("Frontend")
                outputFileNamePrefix.set("frontend-")
                dependencyLicense.set(layout.projectDirectory.file("frontend-licenses.json"))
                ignoredGroupPrefixes.set(setOf("@hivemq/"))
                internalArtifacts.set(setOf("@other/*"))
                outputDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            }
            """.trimIndent()
        )

        val result = gradleRunner().buildAndFail()

        // set discards the extension's lists, so the package the defaults would have left out is unclassified
        assertThat(result.output).contains(
            "Own module '@hivemq/ui-library' (ignored group prefix '@hivemq/') is in neither allowedArtifacts nor " +
                "internalArtifacts"
        )
    }
}
