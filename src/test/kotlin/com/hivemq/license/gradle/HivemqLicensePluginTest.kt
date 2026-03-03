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
package com.hivemq.license.gradle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HivemqLicensePluginTest {

    @TempDir
    lateinit var projectDir: File

    private fun setup(buildExtra: String = "") {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "test-project"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.license")
            }
            $buildExtra
            """.trimIndent()
        )
    }

    private fun gradleRunner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(*args)
        .withPluginClasspath()

    @Test
    fun `plugin applies successfully`() {
        setup()

        val result = gradleRunner("tasks", "--group=hivemq license").build()

        assertThat(result.output).contains("checkApprovedLicenses")
        assertThat(result.output).contains("updateThirdPartyLicenses")
    }

    @Test
    fun `plugin registers cyclonedxDirectBom task`() {
        setup()

        val result = gradleRunner("tasks", "--all").build()

        assertThat(result.output).contains("cyclonedxDirectBom")
    }

    @Test
    fun `extension defaults use project name`() {
        setup(
            """
            tasks.register("printExtension") {
                doLast {
                    val ext = project.extensions.getByType(com.hivemq.license.gradle.HivemqLicenseExtension::class.java)
                    println("projectName=" + ext.projectName.get())
                    println("ignoredGroupPrefixes=" + ext.ignoredGroupPrefixes.get())
                    println("allowedArtifacts=" + ext.allowedArtifacts.get())
                }
            }
            """.trimIndent()
        )

        val result = gradleRunner("printExtension").build()

        assertThat(result.output)
            .contains("projectName=test-project")
            .contains("ignoredGroupPrefixes=[com.hivemq]")
            .contains("allowedArtifacts=[com.hivemq:hivemq-mqtt-client]")
    }

    @Test
    fun `extension values can be customized`() {
        setup(
            """
            hivemqLicense {
                projectName.set("My Custom Project")
                ignoredGroupPrefixes.set(setOf("org.internal"))
                allowedArtifacts.set(setOf("org.internal:public-api"))
            }
            tasks.register("printExtension") {
                doLast {
                    val ext = project.extensions.getByType(com.hivemq.license.gradle.HivemqLicenseExtension::class.java)
                    println("projectName=" + ext.projectName.get())
                    println("ignoredGroupPrefixes=" + ext.ignoredGroupPrefixes.get())
                    println("allowedArtifacts=" + ext.allowedArtifacts.get())
                }
            }
            """.trimIndent()
        )

        val result = gradleRunner("printExtension").build()

        assertThat(result.output)
            .contains("projectName=My Custom Project")
            .contains("ignoredGroupPrefixes=[org.internal]")
            .contains("allowedArtifacts=[org.internal:public-api]")
    }

    @Test
    fun `checkApprovedLicenses succeeds with valid BOM`() {
        setup()

        // Create a fake BOM file in the expected CycloneDX output location
        val bomDir = projectDir.resolve("build/reports/cyclonedx")
        bomDir.mkdirs()
        val bomFile = bomDir.resolve("bom.json")
        bomFile.writeText(
            """
            {
              "components": [
                {
                  "group": "com.google.guava",
                  "name": "guava",
                  "version": "32.1.3-jre",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        // Override the dependencyLicense to use our fake BOM
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.license")
            }
            tasks.named<com.hivemq.license.gradle.CheckApprovedLicensesTask>("checkApprovedLicenses") {
                dependencyLicense.set(file("build/reports/cyclonedx/bom.json"))
            }
            """.trimIndent()
        )

        val result = gradleRunner("checkApprovedLicenses").build()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("1 dependencies have approved licenses")
    }

    @Test
    fun `checkApprovedLicenses fails with unapproved license`() {
        setup()

        val bomDir = projectDir.resolve("build/reports/cyclonedx")
        bomDir.mkdirs()
        bomDir.resolve("bom.json").writeText(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "proprietary-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "name": "Some Proprietary License" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.license")
            }
            tasks.named<com.hivemq.license.gradle.CheckApprovedLicensesTask>("checkApprovedLicenses") {
                dependencyLicense.set(file("build/reports/cyclonedx/bom.json"))
            }
            """.trimIndent()
        )

        val result = gradleRunner("checkApprovedLicenses").buildAndFail()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains("License not on the approved list")
    }

    @Test
    fun `updateThirdPartyLicenses generates output files`() {
        setup()

        val bomDir = projectDir.resolve("build/reports/cyclonedx")
        bomDir.mkdirs()
        bomDir.resolve("bom.json").writeText(
            """
            {
              "components": [
                {
                  "group": "com.google.guava",
                  "name": "guava",
                  "version": "32.1.3-jre",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                },
                {
                  "group": "org.slf4j",
                  "name": "slf4j-api",
                  "version": "2.0.9",
                  "licenses": [
                    { "license": { "id": "MIT" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            }
            tasks.named<com.hivemq.license.gradle.UpdateThirdPartyLicensesTask>("updateThirdPartyLicenses") {
                dependencyLicense.set(file("build/reports/cyclonedx/bom.json"))
            }
            """.trimIndent()
        )

        val result = gradleRunner("updateThirdPartyLicenses").build()

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
            .contains("Apache-2.0")
            .contains("<table>")
    }
}
