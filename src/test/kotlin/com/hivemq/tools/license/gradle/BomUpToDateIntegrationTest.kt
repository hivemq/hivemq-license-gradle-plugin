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

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Covers that `generateDependencyBom` and the reports run again when the dependency graph changes in a way that
 * changes none of the module files the BOM task reads: a project dependency or an included build.
 */
class BomUpToDateIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    // the build under test, in a directory of its own so a test can put a build that includes it next to it
    private val appDir by lazy { tempDir.resolve("app").apply { mkdirs() } }

    private val mapper = ObjectMapper()

    private val includeLib = """include("lib")"""
    private val libDependency = """implementation(project(":lib"))"""

    /**
     * Runs without file system watching, since the daemon's watched view of the project can otherwise outlive a build
     * directory deleted between two runs.
     */
    private fun gradleRunner(vararg args: String, projectDir: File = appDir) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--no-watch-fs")

    /**
     * Writes the root project `app`, which applies the plugin and has [dependencies]; [settings] goes into its
     * settings script and [extra] into its build script.
     */
    private fun writeApp(dependencies: String, settings: String = "", extra: String = "") {
        appDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "app"
            buildCache {
                local {
                    directory = file("build-cache")
                }
            }
            $settings
            """.trimIndent()
        )
        appDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            group = "org.example"
            version = "1.0"
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("third-party-licenses"))
                overriddenLicenses.put("org.example:lib", "MIT")
            }
            $extra
            dependencies {
                $dependencies
            }
            """.trimIndent()
        )
    }

    /** Writes a library project into [dir], without dependencies of its own unless [dependencies] names some. */
    private fun writeLibrary(dir: String, version: String = "1.0", dependencies: String = "") {
        val libraryDir = appDir.resolve(dir).apply { mkdirs() }
        libraryDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
            }
            group = "org.example"
            version = "$version"
            dependencies {
                $dependencies
            }
            """.trimIndent()
        )
    }

    /** `app` depending on its subproject `lib`. */
    private fun setupWithProjectDependency() {
        writeApp(libDependency, settings = includeLib)
        writeLibrary("lib")
    }

    private fun bom() = mapper.readTree(appDir.resolve("build/license/bom.json"))

    /** Name to version of every component in the BOM. */
    private fun bomComponents() = bom().path("components").associate { it["name"].asText() to it["version"].asText() }

    /** What the BOM lists as the dependencies of `lib`. */
    private fun bomDependenciesOfLib() = bom().path("dependencies")
        .single { "/lib@" in it["ref"].asText() }
        .path("dependsOn").map { it.asText() }

    private fun report() = appDir.resolve("build/third-party-licenses/licenses").readText()

    // --- Changes in the graph ---

    @Test
    fun `a bumped included build re-runs generateDependencyBom`() {
        writeApp("""implementation("org.example:inc-lib:1.0")""", settings = """includeBuild("inc")""")
        writeLibrary("inc")
        appDir.resolve("inc/settings.gradle.kts").writeText("""rootProject.name = "inc-lib"""")
        gradleRunner(":generateDependencyBom").build()
        assertThat(bomComponents()).containsEntry("inc-lib", "1.0")
        writeLibrary("inc", version = "2.0")

        val result = gradleRunner(":generateDependencyBom").build()

        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(bomComponents()).containsEntry("inc-lib", "2.0")
    }

    @Test
    fun `a bumped project dependency is not restored from the build cache`() {
        // a clean checkout, as on CI, finds the BOM of the earlier version in the cache
        setupWithProjectDependency()
        gradleRunner(":generateDependencyBom", "--build-cache").build()
        writeLibrary("lib", version = "2.0")
        assertThat(appDir.resolve("build").deleteRecursively()).isTrue()

        val result = gradleRunner(":generateDependencyBom", "--build-cache").build()

        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(bomComponents()).containsEntry("lib", "2.0")
    }

    @Test
    fun `a bumped project dependency of an additional configuration re-runs generateDependencyBom`() {
        writeApp(
            """"bundled"(project(":lib"))""",
            settings = includeLib,
            extra = """
            val bundled by configurations.creating
            hivemqLicense {
                additionalConfigurations.add("bundled")
            }
            """.trimIndent(),
        )
        writeLibrary("lib")
        gradleRunner(":generateDependencyBom").build()
        assertThat(bomComponents()).containsEntry("lib", "1.0")
        writeLibrary("lib", version = "2.0")

        val result = gradleRunner(":generateDependencyBom").build()

        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(bomComponents()).containsEntry("lib", "2.0")
    }

    @Test
    fun `a bumped project dependency re-runs generateDependencyBom with the configuration cache`() {
        setupWithProjectDependency()
        val stored = gradleRunner(":generateDependencyBom", "--configuration-cache").build()
        assertThat(stored.output).contains("Configuration cache entry stored.")
        writeLibrary("lib", version = "2.0")

        val result = gradleRunner(":generateDependencyBom", "--configuration-cache").build()

        assertThat(result.output).contains("Configuration cache entry stored.")
        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(bomComponents()).containsEntry("lib", "2.0")
    }

    @Test
    fun `a bumped project dependency shows its new version in the BOM and the report`() {
        setupWithProjectDependency()
        gradleRunner("updateThirdPartyLicenses").build()
        assertThat(bomComponents()).containsEntry("lib", "1.0")
        writeLibrary("lib", version = "2.0")

        val result = gradleRunner("updateThirdPartyLicenses").build()

        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(bomComponents()).containsEntry("lib", "2.0")
        assertThat(report()).containsPattern("""org\.example:lib +\| 2\.0 """)
    }

    @Test
    fun `a bumped transitive project dependency re-runs generateDependencyBom`() {
        writeApp(libDependency, settings = """include("lib", "util")""")
        writeLibrary("lib", dependencies = """implementation(project(":util"))""")
        writeLibrary("util")
        gradleRunner(":generateDependencyBom").build()
        assertThat(bomComponents()).containsEntry("util", "1.0")
        writeLibrary("util", version = "2.0")

        val result = gradleRunner(":generateDependencyBom").build()

        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(bomComponents()).containsEntry("util", "2.0")
    }

    @Test
    fun `a project dependency that gains a dependency already in the graph re-runs generateDependencyBom`() {
        // the components stay the same, only the dependencies the BOM lists for lib change
        writeApp(
            """
            implementation(project(":lib"))
            implementation(project(":util"))
            """.trimIndent(),
            settings = """include("lib", "util")""",
        )
        writeLibrary("lib")
        writeLibrary("util")
        gradleRunner(":generateDependencyBom").build()
        assertThat(bomDependenciesOfLib()).isEmpty()
        writeLibrary("lib", dependencies = """implementation(project(":util"))""")

        val result = gradleRunner(":generateDependencyBom").build()

        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(bomDependenciesOfLib()).singleElement().asString().contains("/util@1.0")
    }

    @Test
    fun `a removed project dependency is gone from the BOM and the report`() {
        // lib brings no module of its own, so removing it changes none of the files the BOM task reads
        setupWithProjectDependency()
        gradleRunner("updateThirdPartyLicenses").build()
        assertThat(bomComponents()).containsKey("lib")
        writeApp(dependencies = "", settings = includeLib)

        val result = gradleRunner("updateThirdPartyLicenses").build()

        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(bomComponents()).doesNotContainKey("lib")
        assertThat(report()).doesNotContain("org.example:lib")
    }

    // --- No change in the graph ---

    @Test
    fun `a bumped project dependency the BOM does not read leaves generateDependencyBom up to date`() {
        writeApp("""testImplementation(project(":lib"))""", settings = includeLib)
        writeLibrary("lib")
        gradleRunner(":generateDependencyBom").build()
        assertThat(bomComponents()).doesNotContainKey("lib")
        writeLibrary("lib", version = "2.0")

        val result = gradleRunner(":generateDependencyBom").build()

        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun `an unchanged included build leaves generateDependencyBom up to date`() {
        writeApp("""implementation("org.example:inc-lib:1.0")""", settings = """includeBuild("inc")""")
        writeLibrary("inc")
        appDir.resolve("inc/settings.gradle.kts").writeText("""rootProject.name = "inc-lib"""")
        gradleRunner(":generateDependencyBom").build()

        val result = gradleRunner(":generateDependencyBom").build()

        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun `generateDependencyBom is restored from the build cache when nothing changed`() {
        setupWithProjectDependency()
        gradleRunner(":generateDependencyBom", "--build-cache").build()
        assertThat(appDir.resolve("build").deleteRecursively()).isTrue()

        val result = gradleRunner(":generateDependencyBom", "--build-cache").build()

        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
        assertThat(bomComponents()).containsEntry("lib", "1.0")
    }

    @Test
    fun `generateDependencyBom is restored from the build cache when the build becomes an included build`() {
        // the BOM names a project by its path in its own build, so where that build sits in a composite changes nothing
        setupWithProjectDependency()
        gradleRunner(":generateDependencyBom", "--build-cache").build()
        assertThat(appDir.resolve("build").deleteRecursively()).isTrue()
        val outerDir = tempDir.resolve("outer").apply { mkdirs() }
        outerDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "outer"
            includeBuild("../app")
            buildCache {
                local {
                    directory = file("../app/build-cache")
                }
            }
            """.trimIndent()
        )

        val result = gradleRunner(":app:generateDependencyBom", "--build-cache", projectDir = outerDir).build()

        assertThat(result.task(":app:generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
    }

    @Test
    fun `nothing re-runs when nothing changed`() {
        setupWithProjectDependency()
        gradleRunner("updateThirdPartyLicenses").build()

        val result = gradleRunner("updateThirdPartyLicenses").build()

        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun `the configuration cache is reused when nothing changed`() {
        setupWithProjectDependency()
        val stored = gradleRunner("updateThirdPartyLicenses", "--configuration-cache").build()
        assertThat(stored.output).contains("Configuration cache entry stored.")

        val result = gradleRunner("updateThirdPartyLicenses", "--configuration-cache").build()

        assertThat(result.output).contains("Reusing configuration cache.")
        assertThat(result.task(":generateDependencyBom")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }
}
