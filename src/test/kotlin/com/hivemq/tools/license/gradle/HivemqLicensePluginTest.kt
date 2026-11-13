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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
                id("com.hivemq.tools.license")
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
    fun `plugin registers generateDependencyBom without a group`() {
        setup()

        val all = gradleRunner("tasks", "--all").build()
        val grouped = gradleRunner("tasks").build()

        assertThat(all.output).contains("generateDependencyBom - Writes the CycloneDX BOM of the resolved dependencies")
        // generateSbom's description names it as well, so only an entry of its own counts
        assertThat(grouped.output).doesNotContain("generateDependencyBom -")
    }

    @Test
    fun `plugin adds no task or configuration of the CycloneDX plugin to the project or its subprojects`() {
        setup(
            """
            gradle.projectsEvaluated {
                for (p in allprojects) {
                    val tasks = p.tasks.names.filter { it.contains("bom", ignoreCase = true) }
                    val configurations = p.configurations.names.filter { it.contains("bom", ignoreCase = true) }
                    println(p.displayName + ": tasks=" + tasks + " configurations=" + configurations)
                }
            }
            """.trimIndent()
        )
        projectDir.resolve("settings.gradle.kts").appendText("\ninclude(\"sub\")")
        projectDir.resolve("sub").mkdirs()
        projectDir.resolve("sub/build.gradle.kts").writeText("plugins { java }")

        val result = gradleRunner("help").build()

        assertThat(result.output)
            .contains(
                "root project 'test-project': tasks=[generateDependencyBom, generateSbom] configurations=[sbomElements]"
            )
            .contains("project ':sub': tasks=[] configurations=[]")
    }

    @ParameterizedTest
    @ValueSource(strings = ["includeConfigs", "skipConfigs"])
    fun `a build script cannot change the configurations generateDependencyBom reads`(property: String) {
        setup(
            """
            tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("generateDependencyBom") {
                $property.set(listOf("compileClasspath"))
            }
            """.trimIndent()
        )

        val result = gradleRunner("generateDependencyBom").buildAndFail()

        assertThat(result.output)
            .contains("The value for task ':generateDependencyBom' property '$property' cannot be changed any further")
    }

    @Test
    fun `generateDependencyBom writes only the JSON BOM into the license build directory`() {
        setup()

        val result = gradleRunner("generateDependencyBom").build()

        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(projectDir.resolve("build/license").list()).containsExactly("bom.json")
        assertThat(projectDir.resolve("build").walk().filter { it.extension == "xml" }.toList()).isEmpty()
        assertThat(projectDir.resolve("build/license/bom.json").readText()).contains(""""bomFormat" : "CycloneDX"""")
    }

    @Test
    fun `extension defaults use project name`() {
        setup(
            """
            tasks.register("printExtension") {
                doLast {
                    val ext = project.extensions.getByType(com.hivemq.tools.license.gradle.HivemqLicenseExtension::class.java)
                    println("projectName=" + ext.projectName.get())
                    println("ignoredGroupPrefixes=" + ext.ignoredGroupPrefixes.get())
                    println("allowedArtifacts=" + ext.allowedArtifacts.get())
                    println("internalArtifacts=" + ext.internalArtifacts.get())
                }
            }
            """.trimIndent()
        )

        val result = gradleRunner("printExtension").build()

        assertThat(result.output)
            .contains("projectName=test-project")
            .contains("ignoredGroupPrefixes=[com.hivemq]")
            .contains("allowedArtifacts=[com.hivemq:hivemq-amazon-kinesis-extension-customization-sdk, ")
            .contains("com.hivemq:hivemq-extension-sdk, ")
            .contains("com.hivemq:hivemq-mqtt-client, ")
            .contains("internalArtifacts=[@hivemq/*, com.hivemq.commons:*, ")
            .contains("com.hivemq:hivemq-platform-sdk, com.hivemq:hivemq-pulse-*]")
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
                    val ext = project.extensions.getByType(com.hivemq.tools.license.gradle.HivemqLicenseExtension::class.java)
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
                id("com.hivemq.tools.license")
            }
            tasks.named<com.hivemq.tools.license.gradle.CheckApprovedLicensesTask>("checkApprovedLicenses") {
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
                id("com.hivemq.tools.license")
            }
            tasks.named<com.hivemq.tools.license.gradle.CheckApprovedLicensesTask>("checkApprovedLicenses") {
                dependencyLicense.set(file("build/reports/cyclonedx/bom.json"))
            }
            """.trimIndent()
        )

        val result = gradleRunner("checkApprovedLicenses").buildAndFail()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains("License not on the approved list")
    }

    @Test
    fun `additionalConfigurations creates transitive wrapper configuration`() {
        setup(
            """
            val myCustomConfig by configurations.creating {
                isTransitive = false
            }
            hivemqLicense {
                additionalConfigurations.add("myCustomConfig")
            }
            tasks.register("printWrapperConfig") {
                doLast {
                    val wrapper = project.configurations.findByName("hivemqLicense_myCustomConfig")
                    println("exists=" + (wrapper != null))
                    println("canBeResolved=" + wrapper?.isCanBeResolved)
                    println("canBeConsumed=" + wrapper?.isCanBeConsumed)
                    println("extendsFrom=" + wrapper?.extendsFrom?.map { it.name })
                }
            }
            """.trimIndent()
        )

        val result = gradleRunner("printWrapperConfig").build()

        assertThat(result.output)
            .contains("exists=true")
            .contains("canBeResolved=true")
            .contains("canBeConsumed=false")
            .contains("extendsFrom=[myCustomConfig]")
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
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            }
            tasks.named<com.hivemq.tools.license.gradle.UpdateThirdPartyLicensesTask>("updateThirdPartyLicenses") {
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
