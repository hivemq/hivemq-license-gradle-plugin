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

    private fun gradleRunner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args)

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
                outputDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            }
            """.trimIndent()
        )

        val result = gradleRunner("updateFrontendLicenses").build()

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
        // Scoped internal packages are excluded via ignoredGroupPrefixes.
        assertThat(text).doesNotContain("@example/internal-widget")
    }
}
