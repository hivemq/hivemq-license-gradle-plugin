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

class HivemqLicensePluginIntegrationTest {

    @TempDir
    lateinit var projectDir: File

    private fun setup() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "test-project"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
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
    }

    private fun gradleRunner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args)

    @Test
    fun `checkApprovedLicenses succeeds with real dependencies`() {
        setup()

        val result = gradleRunner("checkApprovedLicenses").build()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("dependencies have approved licenses")
    }

    @Test
    fun `updateThirdPartyLicenses generates reports from real dependencies`() {
        setup()

        val result = gradleRunner("updateThirdPartyLicenses").build()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val outputDir = projectDir.resolve("build/tmp/third-party-licenses")
        val plaintextFile = outputDir.resolve("licenses")
        val htmlFile = outputDir.resolve("licenses.html")

        assertThat(plaintextFile).exists()
        assertThat(htmlFile).exists()

        val plaintext = plaintextFile.readText()
        assertThat(plaintext)
            .contains("com.google.guava:guava")
            .contains("org.slf4j:slf4j-api")
            .contains("Apache-2.0")
            .contains("MIT")
            .contains("test-project uses the following third party libraries")

        val html = htmlFile.readText()
        assertThat(html)
            .contains("com.google.guava:guava")
            .contains("org.slf4j:slf4j-api")
    }
}
