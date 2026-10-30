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

    // --- Unresolved metadata in the failure message ---

    @Test
    fun `failure says why the metadata of a dependency without a POM is incomplete`() {
        // a repository that serves the jar alone, so the CycloneDX plugin finds no POM to read licenses from
        val artifactDir = projectDir.resolve("repo/com/example/pomless-lib/1.0.0")
        artifactDir.mkdirs()
        ZipOutputStream(artifactDir.resolve("pomless-lib-1.0.0.jar").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            zip.closeEntry()
        }
        projectDir.resolve("settings.gradle.kts").writeText(settingsWithLocalBuildCache())
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            repositories {
                maven {
                    url = uri("repo")
                    metadataSources { artifact() }
                }
            }
            dependencies {
                implementation("com.example:pomless-lib:1.0.0")
            }
            """.trimIndent()
        )

        val result = cachedRunner("checkApprovedLicenses").buildAndFail()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output)
            .contains("No license found for 'com.example:pomless-lib:1.0.0' (its Maven metadata is incomplete: pom-unresolved)")
    }

    // --- BOM origin in the failure message ---

    /**
     * Writes a local Maven repository with one artifact whose POM declares no license, so the real cyclonedxDirectBom
     * task produces a BOM that makes checkApprovedLicenses fail.
     */
    private fun writeUnlicensedRepository(repoDir: File) {
        val artifactDir = repoDir.resolve("com/example/no-license-lib/1.0.0")
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
    }

    /** Build script of a project depending on the unlicensed artifact, [repoPath] relative to the project. */
    private fun unlicensedBuildFile(
        repoPath: String,
        extraPlugins: String = "",
        licensePlugin: String = """id("com.hivemq.tools.license")""",
        extra: String = "",
    ) =
        """
        plugins {
            java
            $extraPlugins
            $licensePlugin
        }
        repositories {
            maven { url = uri("$repoPath") }
        }
        dependencies {
            implementation("com.example:no-license-lib:1.0.0")
        }
        $extra
        """.trimIndent()

    private fun settingsWithLocalBuildCache(extra: String = "") =
        """
        rootProject.name = "test-project"
        buildCache {
            local {
                directory = file("build-cache")
            }
        }
        $extra
        """.trimIndent()

    /**
     * Runs with the local build cache and without file system watching, since the daemon's watched view of the
     * project can otherwise outlive a build directory deleted between two runs.
     */
    private fun cachedRunner(vararg args: String) = gradleRunner(*args, "--build-cache", "--no-watch-fs")

    private fun setupWithUnlicensedDependency(extra: String = "") {
        writeUnlicensedRepository(projectDir.resolve("repo"))
        projectDir.resolve("settings.gradle.kts").writeText(settingsWithLocalBuildCache())
        projectDir.resolve("build.gradle.kts").writeText(unlicensedBuildFile("repo", extra = extra))
    }

    // Gradle canonicalizes the project directory, and the JUnit temp dir sits behind a symlink on macOS
    private fun bomPath(projectDir: File) = projectDir.resolve("build/reports/cyclonedx-direct/bom.json").canonicalPath

    // Supplied by the build (see build.gradle.kts).
    private fun testProperty(name: String) = requireNotNull(System.getProperty(name)) {
        "System property '$name' is not set; run the tests through Gradle."
    }

    @Test
    fun `failure names no origin when the BOM task is excluded from the build`() {
        setupWithUnlicensedDependency()
        cachedRunner("checkApprovedLicenses").buildAndFail()

        val result = cachedRunner("checkApprovedLicenses", "-x", "cyclonedxDirectBom").buildAndFail()

        assertThat(result.task(":cyclonedxDirectBom")).isNull()
        assertThat(result.output).contains("'${bomPath(projectDir)}':")
        assertThat(result.output).doesNotContain("'${bomPath(projectDir)}' (")
    }

    @Test
    fun `failure says the BOM was produced by this build`() {
        setupWithUnlicensedDependency()

        val result = cachedRunner("checkApprovedLicenses").buildAndFail()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("1 license problem found in '")
        assertThat(result.output).contains("' (produced by this build):")
        assertThat(result.output).contains("No license found for 'com.example:no-license-lib:1.0.0'")
    }

    @Test
    fun `failure says the BOM was produced by this build with the configuration cache`() {
        setupWithUnlicensedDependency()

        val result = cachedRunner("checkApprovedLicenses", "--configuration-cache").buildAndFail()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("' (produced by this build):")
    }

    @Test
    fun `failure says the BOM is unchanged when the configuration cache is reused`() {
        setupWithUnlicensedDependency()
        cachedRunner("checkApprovedLicenses", "--configuration-cache").buildAndFail()

        val result = cachedRunner("checkApprovedLicenses", "--configuration-cache").buildAndFail()

        assertThat(result.output).contains("Reusing configuration cache.")
        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(result.output).contains("' (unchanged since an earlier build):")
    }

    @Test
    fun `generateSbom failure says where the BOM came from`() {
        setupWithUnlicensedDependency()

        val result = cachedRunner("generateSbom").buildAndFail()

        assertThat(result.task(":generateSbom")?.outcome).isEqualTo(TaskOutcome.FAILED)
        // word for word what checkApprovedLicenses reports, so a build that packs the SBOM needs no separate check
        assertThat(result.output)
            .contains("1 license problem found in '${bomPath(projectDir)}' (produced by this build):")
            .contains("No license found for 'com.example:no-license-lib:1.0.0'")
            .doesNotContain("Cannot write an SBOM")
        assertThat(projectDir.resolve("build/sbom/test-project.cdx.json")).doesNotExist()
    }

    @Test
    fun `updateThirdPartyLicenses is up to date and restored from the build cache`() {
        // the task's inputs live in a base class, so this checks they still take part in its up-to-date check and key
        setupWithUnlicensedDependency(
            extra = """
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
                overriddenLicenses.put("com.example:no-license-lib", "MIT")
            }
            """.trimIndent()
        )
        assertThat(cachedRunner("updateThirdPartyLicenses").build().task(":updateThirdPartyLicenses")?.outcome)
            .isEqualTo(TaskOutcome.SUCCESS)

        val unchanged = cachedRunner("updateThirdPartyLicenses").build()
        assertThat(unchanged.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

        assertThat(projectDir.resolve("build/tmp/third-party-licenses").deleteRecursively()).isTrue()
        val restored = cachedRunner("updateThirdPartyLicenses").build()
        assertThat(restored.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
        assertThat(projectDir.resolve("build/tmp/third-party-licenses/licenses").readText())
            .contains("com.example:no-license-lib")
            .contains("MIT")
    }

    @Test
    fun `updateThirdPartyLicenses failure says where the BOM came from`() {
        setupWithUnlicensedDependency(
            extra = """
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            }
            """.trimIndent()
        )

        val result = cachedRunner("updateThirdPartyLicenses").buildAndFail()

        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains("'${bomPath(projectDir)}' (produced by this build):")
    }

    @Test
    fun `failure says the BOM was restored from the build cache`() {
        setupWithUnlicensedDependency()
        cachedRunner("checkApprovedLicenses").buildAndFail()
        assertThat(projectDir.resolve("build").deleteRecursively()).isTrue()

        val result = cachedRunner("checkApprovedLicenses").buildAndFail()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
        assertThat(result.output).contains("' (restored from the build cache):")
    }

    @Test
    fun `failure says the BOM is unchanged since an earlier build`() {
        setupWithUnlicensedDependency()
        cachedRunner("checkApprovedLicenses").buildAndFail()

        val result = cachedRunner("checkApprovedLicenses").buildAndFail()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(result.output).contains("' (unchanged since an earlier build):")
    }

    @Test
    fun `origin is reported per project when sibling projects load the plugin in different classloaders`() {
        // Gradle gives a project whose plugins block differs from its sibling's a plugin classloader of its own, so
        // the plugin classes, and with them the build service type, exist once per such project. The plugin is
        // resolved from a repository here because TestKit's injected classpath loads it once for the whole build.
        val pluginVersion = testProperty("test.plugin.version")
        val shadowVersion = testProperty("test.plugin.shadow.version")
        writeUnlicensedRepository(projectDir.resolve("repo"))
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven { url = uri("${testProperty("test.plugin.repo").replace("\\", "/")}") }
                    gradlePluginPortal()
                }
            }
            ${settingsWithLocalBuildCache("""include("a", "b")""")}
            """.trimIndent()
        )
        projectDir.resolve("a").mkdirs()
        projectDir.resolve("a/build.gradle.kts").writeText(
            unlicensedBuildFile("../repo", licensePlugin = """id("com.hivemq.tools.license") version "$pluginVersion"""")
        )
        projectDir.resolve("b").mkdirs()
        projectDir.resolve("b/build.gradle.kts").writeText(
            unlicensedBuildFile(
                "../repo",
                extraPlugins = """id("com.gradleup.shadow") version "$shadowVersion"""",
                licensePlugin = """id("com.hivemq.tools.license") version "$pluginVersion"""",
            )
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("checkApprovedLicenses", "--build-cache", "--no-watch-fs", "--continue")
            .buildAndFail()

        assertThat(result.task(":a:checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.task(":b:checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains("'${bomPath(projectDir.resolve("a"))}' (produced by this build):")
        assertThat(result.output).contains("'${bomPath(projectDir.resolve("b"))}' (produced by this build):")
    }

    @Test
    fun `origin is reported per build in a composite build`() {
        // the root build and the included build both have a ":cyclonedxDirectBom" task, so outcomes must be keyed by
        // the task's path in the whole build tree
        writeUnlicensedRepository(projectDir.resolve("repo"))
        projectDir.resolve("settings.gradle.kts").writeText(settingsWithLocalBuildCache("""includeBuild("inc")"""))
        projectDir.resolve("build.gradle.kts").writeText(unlicensedBuildFile("repo"))
        val includedDir = projectDir.resolve("inc")
        includedDir.mkdirs()
        includedDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "inc"""")
        includedDir.resolve("build.gradle.kts").writeText(unlicensedBuildFile("../repo"))
        cachedRunner("checkApprovedLicenses", ":inc:checkApprovedLicenses", "--continue").buildAndFail()
        // the root build's BOM is restored from the cache, the included build's BOM is left untouched
        assertThat(projectDir.resolve("build").deleteRecursively()).isTrue()

        val result =
            cachedRunner("checkApprovedLicenses", ":inc:checkApprovedLicenses", "--continue").buildAndFail()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
        assertThat(result.task(":inc:cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(result.output).contains("'${bomPath(projectDir)}' (restored from the build cache):")
        assertThat(result.output).contains("'${bomPath(includedDir)}' (unchanged since an earlier build):")
    }

    @Test
    fun `no origin is reported for a file that is not the BOM task output`() {
        projectDir.resolve("frontend.json").writeText(
            """
            {
              "Unknown": [
                { "name": "mystery-pkg", "versions": ["2.0.0"], "license": "Unknown" }
              ]
            }
            """.trimIndent()
        )
        setupWithUnlicensedDependency(
            extra = """
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            }
            tasks.named<com.hivemq.tools.license.gradle.UpdateThirdPartyLicensesTask>("updateThirdPartyLicenses") {
                dependencyLicense.set(file("frontend.json"))
            }
            """.trimIndent()
        )

        val result =
            cachedRunner("cyclonedxDirectBom", "updateThirdPartyLicenses", "--continue").buildAndFail()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.FAILED)
        val frontendReport = projectDir.resolve("frontend.json").canonicalPath
        assertThat(result.output).contains("1 license problem found in '$frontendReport':")
        assertThat(result.output).doesNotContain("'$frontendReport' (")
    }
}
