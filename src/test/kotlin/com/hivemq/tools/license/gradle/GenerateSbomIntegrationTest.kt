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

class GenerateSbomIntegrationTest {

    @TempDir
    lateinit var projectDir: File

    private fun gradleRunner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args)
        .withEnvironment(GitTestRepository.isolatedEnv(projectDir))

    /** A project with two real dependencies; [hivemqLicense] goes into the extension block. */
    private fun setup(hivemqLicense: String = "", settings: String = """rootProject.name = "sbom-project"""") {
        projectDir.resolve("settings.gradle.kts").writeText(settings)
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import java.time.Instant

            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
                $hivemqLicense
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

    private val fixedTimestamp = """sbomTimestamp.set(Instant.parse("2026-01-02T03:04:05Z"))"""

    private fun sbom() = projectDir.resolve("build/sbom/sbom-project.cdx.json")

    // --- Output ---

    @Test
    fun `generateSbom writes the project name as file name`() {
        setup(fixedTimestamp)

        val result = gradleRunner("generateSbom").build()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":generateSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(sbom().readText())
            .contains(""""timestamp" : "2026-01-02T03:04:05Z"""")
            .contains("pkg:maven/com.google.guava/guava@33.4.0-jre")
            .contains("pkg:maven/org.slf4j/slf4j-api@2.0.16")
            .doesNotContain("build-system")
    }

    @Test
    fun `sbomName changes the file name`() {
        setup("sbomName.set(\"hivemq-broker\")\n$fixedTimestamp")

        gradleRunner("generateSbom").build()

        assertThat(projectDir.resolve("build/sbom/hivemq-broker.cdx.json")).exists()
    }

    // --- Reproducibility ---

    @Test
    fun `generateSbom is restored from the build cache`() {
        setup(
            fixedTimestamp,
            settings = """
                rootProject.name = "sbom-project"
                buildCache {
                    local {
                        directory = file("build-cache")
                    }
                }
            """.trimIndent()
        )
        gradleRunner("generateSbom", "--build-cache").build()
        check(projectDir.resolve("build").deleteRecursively())

        val result = gradleRunner("generateSbom", "--build-cache").build()

        assertThat(result.task(":generateSbom")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
    }

    @Test
    fun `two clean builds write identical bytes`() {
        setup(fixedTimestamp)
        gradleRunner("generateSbom").build()
        val first = sbom().readBytes()
        check(projectDir.resolve("build").deleteRecursively())

        val result = gradleRunner("generateSbom").build()

        // the CycloneDX task ran again and wrote a new serial number and timestamp, the normalized file did not change
        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(sbom().readBytes()).isEqualTo(first)
    }

    // --- Timestamp defaults ---

    @Test
    fun `a malformed SOURCE_DATE_EPOCH fails with a clear message`() {
        setup()

        val result = gradleRunner("generateSbom")
            .withEnvironment(GitTestRepository.isolatedEnv(projectDir, mapOf("SOURCE_DATE_EPOCH" to "yesterday")))
            .buildAndFail()

        assertThat(result.output).contains("SOURCE_DATE_EPOCH must be the number of seconds")
    }

    @Test
    fun `falls back to the current time with a warning outside a git repository`() {
        setup()

        val result = gradleRunner("generateSbom").build()

        assertThat(result.output).contains("SBOM timestamp is the current time")
        assertThat(sbom()).exists()
    }

    @Test
    fun `sbomTimestamp beats SOURCE_DATE_EPOCH`() {
        setup(fixedTimestamp)

        gradleRunner("generateSbom")
            .withEnvironment(GitTestRepository.isolatedEnv(projectDir, mapOf("SOURCE_DATE_EPOCH" to "1700000000")))
            .build()

        assertThat(sbom().readText()).contains(""""timestamp" : "2026-01-02T03:04:05Z"""")
    }

    @Test
    fun `SOURCE_DATE_EPOCH beats the commit time`() {
        setup()
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "build.gradle.kts")

        gradleRunner("generateSbom")
            .withEnvironment(GitTestRepository.isolatedEnv(projectDir, mapOf("SOURCE_DATE_EPOCH" to "1700000000")))
            .build()

        assertThat(sbom().readText()).contains(""""timestamp" : "2023-11-14T22:13:20Z"""")
    }

    @Test
    fun `SOURCE_DATE_EPOCH sets the timestamp`() {
        setup()

        gradleRunner("generateSbom")
            .withEnvironment(GitTestRepository.isolatedEnv(projectDir, mapOf("SOURCE_DATE_EPOCH" to "1700000000")))
            .build()

        assertThat(sbom().readText()).contains(""""timestamp" : "2023-11-14T22:13:20Z"""")
    }

    @Test
    fun `the configuration cache stores the commit time without a warning`() {
        setup()
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "build.gradle.kts")
        // Compiles the build script first: compiling it during a configuration-cache run records system properties
        // the embedded Kotlin compiler reads, which change between TestKit runs and would discard the entry.
        gradleRunner("help").build()
        val args = arrayOf("generateSbom", "--configuration-cache")
        val first = gradleRunner(*args).build()
        assertThat(first.output).doesNotContain("SBOM timestamp is the current time")
        assertThat(sbom().readText()).contains(""""timestamp" : "2024-05-06T07:08:09Z"""")
        // the second run writes the file again from the stored entry
        check(projectDir.resolve("build").deleteRecursively())

        val second = gradleRunner(*args).build()

        assertThat(second.output)
            .contains("Reusing configuration cache")
            .doesNotContain("SBOM timestamp is the current time")
        assertThat(sbom().readText()).contains(""""timestamp" : "2024-05-06T07:08:09Z"""")
    }

    @Test
    fun `the last commit time is the timestamp when SOURCE_DATE_EPOCH is not set`() {
        setup()
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "build.gradle.kts")

        gradleRunner("generateSbom").build()

        assertThat(sbom().readText()).contains(""""timestamp" : "2024-05-06T07:08:09Z"""")
    }

    // --- Variant ---

    @Test
    fun `another project resolves the sbom variant`() {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "variant-project"
            include("lib", "collector")
            """.trimIndent()
        )
        projectDir.resolve("lib").mkdirs()
        projectDir.resolve("lib/build.gradle.kts").writeText(
            """
            import java.time.Instant

            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
                $fixedTimestamp
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation("org.slf4j:slf4j-api:2.0.16")
            }
            """.trimIndent()
        )
        projectDir.resolve("collector").mkdirs()
        projectDir.resolve("collector/build.gradle.kts").writeText(
            """
            val sboms by configurations.creating {
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named("sbom"))
                }
            }
            dependencies {
                sboms(project(":lib"))
            }
            tasks.register<Copy>("collectSboms") {
                from(sboms)
                into(layout.buildDirectory.dir("sboms"))
            }
            """.trimIndent()
        )

        val result = gradleRunner(":collector:collectSboms").build()

        assertThat(result.task(":lib:generateSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(projectDir.resolve("collector/build/sboms/lib.cdx.json")).exists()
    }

    @Test
    fun `the variant does not disturb a java project dependency`() {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "java-project"
            include("lib", "app")
            """.trimIndent()
        )
        projectDir.resolve("lib/src/main/java/lib").mkdirs()
        projectDir.resolve("lib/src/main/java/lib/Lib.java").writeText("package lib; public class Lib {}")
        projectDir.resolve("lib/build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            }
            """.trimIndent()
        )
        projectDir.resolve("app/src/main/java/app").mkdirs()
        projectDir.resolve("app/src/main/java/app/App.java").writeText("package app; public class App { lib.Lib lib; }")
        projectDir.resolve("app/build.gradle.kts").writeText(
            """
            plugins {
                java
            }
            dependencies {
                implementation(project(":lib"))
            }
            """.trimIndent()
        )

        val result = gradleRunner(":app:compileJava").build()

        assertThat(result.task(":app:compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }
}
