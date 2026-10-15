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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    fun `additionalConfigurations resolves transitive dependencies`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "test-project"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
                additionalConfigurations.add("internalJdbcDriver")
            }
            repositories {
                mavenCentral()
            }
            val internalJdbcDriver by configurations.creating {
                isTransitive = false
            }
            dependencies {
                internalJdbcDriver("org.postgresql:postgresql:42.7.4")
            }
            """.trimIndent()
        )

        val result = gradleRunner("updateThirdPartyLicenses").build()

        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val plaintext = projectDir.resolve("build/tmp/third-party-licenses/licenses").readText()
        // Direct dependency
        assertThat(plaintext).contains("org.postgresql:postgresql")
        // Transitive dependency (pulled in via the wrapper's transitive resolution)
        assertThat(plaintext).contains("org.checkerframework:checker-qual")
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

    // --- BOM origin in the failure message ---

    /**
     * A local Maven repository with one artifact whose POM declares no license, so the real cyclonedxDirectBom task
     * produces a BOM that makes checkApprovedLicenses fail.
     */
    private fun setupWithUnlicensedDependency() {
        val artifactDir = projectDir.resolve("repo/com/example/no-license-lib/1.0.0")
        artifactDir.mkdirs()
        artifactDir.resolve("no-license-lib-1.0.0.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>no-license-lib</artifactId>
              <version>1.0.0</version>
            </project>
            """.trimIndent()
        )
        ZipOutputStream(artifactDir.resolve("no-license-lib-1.0.0.jar").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            zip.closeEntry()
        }
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "test-project"
            buildCache {
                local {
                    directory = file("build-cache")
                }
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            repositories {
                maven { url = uri("repo") }
            }
            dependencies {
                implementation("com.example:no-license-lib:1.0.0")
            }
            """.trimIndent()
        )
    }

    @Test
    fun `failure says the BOM was produced by this build`() {
        setupWithUnlicensedDependency()

        val result = gradleRunner("checkApprovedLicenses", "--build-cache").buildAndFail()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("1 license problem found in '")
        assertThat(result.output).contains("' (produced by this build):")
        assertThat(result.output).contains("No license found for 'com.example:no-license-lib:1.0.0'")
    }

    @Test
    fun `failure says the BOM was produced by this build with the configuration cache`() {
        setupWithUnlicensedDependency()

        val result = gradleRunner("checkApprovedLicenses", "--build-cache", "--configuration-cache").buildAndFail()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("' (produced by this build):")
    }

    @Test
    fun `failure says the BOM was restored from the build cache`() {
        setupWithUnlicensedDependency()
        gradleRunner("checkApprovedLicenses", "--build-cache").buildAndFail()
        assertThat(projectDir.resolve("build").deleteRecursively()).isTrue()

        val result = gradleRunner("checkApprovedLicenses", "--build-cache").buildAndFail()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
        assertThat(result.output).contains("' (restored from the build cache):")
    }

    @Test
    fun `failure says the BOM is unchanged since an earlier build`() {
        setupWithUnlicensedDependency()
        gradleRunner("checkApprovedLicenses", "--build-cache").buildAndFail()

        val result = gradleRunner("checkApprovedLicenses", "--build-cache").buildAndFail()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(result.output).contains("' (unchanged since an earlier build):")
    }
}
