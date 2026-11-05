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
package com.hivemq.tools.license.gradle.sbom

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.cyclonedx.Version
import org.cyclonedx.parsers.JsonParser
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MergeSbomsIntegrationTest {

    @TempDir
    lateinit var projectDir: File

    private val mapper = ObjectMapper()

    private fun gradleRunner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args)
        .withEnvironment(GitTestRepository.isolatedEnv(projectDir))

    private val fixedTimestamp = """sbomTimestamp.set(Instant.parse("2026-01-02T03:04:05Z"))"""

    // an SBOM as another build ships it in a zip, with a library as top-level component to show the conversion
    private val toolSbom = """
        {
          "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
          "metadata": {
            "component": { "type": "library", "bom-ref": "pkg:generic/tool@2.0", "name": "tool", "version": "2.0" }
          },
          "components": [
            { "type": "library", "bom-ref": "pkg:maven/org.slf4j/slf4j-api@2.0.16?type=jar",
              "group": "org.slf4j", "name": "slf4j-api", "version": "2.0.16" }
          ],
          "dependencies": [
            { "ref": "pkg:generic/tool@2.0", "dependsOn": [ "pkg:maven/org.slf4j/slf4j-api@2.0.16?type=jar" ] },
            { "ref": "pkg:maven/org.slf4j/slf4j-api@2.0.16?type=jar", "dependsOn": [] }
          ],
          "compositions": [
            {
              "aggregate": "complete",
              "dependencies": [ "pkg:generic/tool@2.0", "pkg:maven/org.slf4j/slf4j-api@2.0.16?type=jar" ]
            }
          ]
        }
    """.trimIndent()

    /**
     * The build script of the part [name], a Java project that applies the plugin as [licensePlugin]. The extension
     * has a library of its own next to the one every part has.
     */
    private fun partBuildScript(name: String, licensePlugin: String) = """
        import java.time.Instant

        plugins {
            java
            $licensePlugin
        }
        group = "org.example"
        version = "1.0"
        hivemqLicense {
            thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            $fixedTimestamp
        }
        repositories {
            mavenCentral()
        }
        dependencies {
            implementation("org.slf4j:slf4j-api:2.0.16")
            ${if (name == "extension") """implementation("com.google.guava:guava:33.4.0-jre")""" else ""}
        }
    """.trimIndent()

    /**
     * Two subprojects with the plugin, a zip with a third part's SBOM, and the root merging the three; the root applies
     * the plugin for its conventions. [extra] goes into the merge task.
     */
    private fun setup(extra: String = "") {
        // not named like the product: the merge would write the file that generateSbom of the root writes
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "deliverables"
            include("broker", "extension")
            """.trimIndent()
        )
        for (name in listOf("broker", "extension")) {
            projectDir.resolve(name).mkdirs()
            projectDir.resolve("$name/build.gradle.kts").writeText(
                partBuildScript(name, """id("com.hivemq.tools.license")""")
            )
        }
        projectDir.resolve("tool/sbom").mkdirs()
        projectDir.resolve("tool/sbom/tool.cdx.json").writeText(toolSbom)
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import com.hivemq.tools.license.gradle.sbom.MergeSbomsTask
            import java.time.Instant

            plugins {
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
                $fixedTimestamp
            }
            val partSboms by configurations.creating {
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named("sbom"))
                }
            }
            dependencies {
                partSboms(project(":broker"))
                partSboms(project(":extension"))
            }
            val toolZip by tasks.registering(Zip::class) {
                from("tool")
                archiveFileName.set("tool-2.0.zip")
                destinationDirectory.set(layout.buildDirectory.dir("zips"))
            }
            tasks.register<MergeSbomsTask>("mergePlatformSbom") {
                productName.set("platform")
                productVersion.set("4.0.0")
                artifactFileName.set("platform-4.0.0.zip")
                // matching drops the task dependency that zipTree infers from the zip task
                dependsOn(toolZip)
                sboms.from(
                    partSboms,
                    zipTree(toolZip.flatMap { it.archiveFile }).matching { include("**/sbom/*.cdx.json") }
                )
                expectedParts.set(setOf("broker", "extension", "tool"))
                $extra
            }
            """.trimIndent()
        )
    }

    /** The system property the build supplies to the tests (see build.gradle.kts). */
    private fun testProperty(name: String) = requireNotNull(System.getProperty(name)) {
        "System property '$name' is not set; run the tests through Gradle."
    }

    /** The plugin management of a build that gets the plugin from the test repository and its libraries elsewhere. */
    private fun pluginRepositories(): String {
        val repository = testProperty("test.plugin.repo").replace("\\", "/")
        return """pluginManagement { repositories { maven { url = uri("$repository") }; gradlePluginPortal() } }"""
    }

    /**
     * Two parts that are included builds, each applying the plugin from the test repository, and a root build that
     * applies it the same way and merges their SBOMs, resolving the parts by their coordinates through the composite.
     */
    private fun setupComposite() {
        val pluginVersion = testProperty("test.plugin.version")
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            ${pluginRepositories()}
            rootProject.name = "deliverables"
            includeBuild("broker")
            includeBuild("extension")
            """.trimIndent()
        )
        for (name in listOf("broker", "extension")) {
            projectDir.resolve(name).mkdirs()
            // an included build reads its own settings, so it names its plugin repositories itself
            projectDir.resolve("$name/settings.gradle.kts").writeText(
                """
                ${pluginRepositories()}
                rootProject.name = "$name"
                """.trimIndent()
            )
            projectDir.resolve("$name/build.gradle.kts").writeText(
                partBuildScript(name, """id("com.hivemq.tools.license") version "$pluginVersion"""")
            )
        }
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import com.hivemq.tools.license.gradle.sbom.MergeSbomsTask
            import java.time.Instant

            plugins {
                id("com.hivemq.tools.license") version "$pluginVersion"
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
                $fixedTimestamp
            }
            val partSboms by configurations.creating {
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named("sbom"))
                }
            }
            dependencies {
                partSboms("org.example:broker")
                partSboms("org.example:extension")
            }
            tasks.register<MergeSbomsTask>("mergePlatformSbom") {
                productName.set("platform")
                productVersion.set("4.0.0")
                artifactFileName.set("platform-4.0.0.zip")
                sboms.from(partSboms)
                expectedParts.set(setOf("broker", "extension"))
            }
            """.trimIndent()
        )
    }

    /** A runner without the injected plugin classpath, so that every build of the composite loads the plugin itself. */
    private fun repositoryRunner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(*args)
        .withEnvironment(GitTestRepository.isolatedEnv(projectDir))

    private fun output() = projectDir.resolve("build/sbom/platform.cdx.json")

    // --- Merge ---

    @Test
    fun `merges the variants of the parts it builds and the SBOM inside a zip`() {
        setup()

        val result = gradleRunner("mergePlatformSbom").build()

        assertThat(result.task(":broker:generateSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":extension:generateSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":mergePlatformSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val sbom = mapper.readTree(output())
        val names = sbom["components"].map { it["name"].asText() }
        val refs = sbom["components"].map { it["bom-ref"].asText() }
        // slf4j-api, which all three parts list, appears once, and no bom-ref repeats, guava's modules included
        assertThat(names).contains("broker", "extension", "guava", "tool").containsOnlyOnce("slf4j-api")
        assertThat(refs).doesNotHaveDuplicates().isSorted()
        val tool = sbom["components"].first { it["name"].asText() == "tool" }
        assertThat(tool["type"].asText()).isEqualTo("application")
        val product = sbom["dependencies"].first { it["ref"].asText() == "platform@4.0.0" }
        assertThat(product["dependsOn"].map { it.asText() }).containsExactly(
            "pkg:generic/tool@2.0",
            "pkg:maven/org.example/broker@1.0?project_path=%3Abroker",
            "pkg:maven/org.example/extension@1.0?project_path=%3Aextension",
        )
        assertThat(sbom["compositions"][0]["aggregate"].asText()).isEqualTo("complete")
        assertThat(result.output).doesNotContain("declares its dependency graph incomplete")
    }

    @Test
    fun `merges the variants of the parts of included builds`() {
        setupComposite()

        val result = repositoryRunner("mergePlatformSbom").build()

        assertThat(result.task(":broker:generateSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":extension:generateSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":mergePlatformSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val sbom = mapper.readTree(output())
        val names = sbom["components"].map { it["name"].asText() }
        // slf4j-api, which both parts list, appears once, and no bom-ref repeats
        assertThat(names).contains("broker", "extension", "guava").containsOnlyOnce("slf4j-api")
        assertThat(sbom["components"].map { it["bom-ref"].asText() }).doesNotHaveDuplicates().isSorted()
        val product = sbom["dependencies"].first { it["ref"].asText() == "platform@4.0.0" }
        assertThat(product["dependsOn"].map { it.asText() }).containsExactly(
            "pkg:maven/org.example/broker@1.0?project_path=%3A",
            "pkg:maven/org.example/extension@1.0?project_path=%3A",
        )
        assertThat(sbom["compositions"][0]["aggregate"].asText()).isEqualTo("complete")
        assertThat(result.output).doesNotContain("declares its dependency graph incomplete")
    }

    @Test
    fun `warns and declares the product incomplete when an expected part is missing`() {
        setup("""expectedParts.set(setOf("broker", "extension", "tool", "swarm"))""")

        val result = gradleRunner("mergePlatformSbom").build()

        assertThat(result.output).contains("declares its dependency graph incomplete: no SBOM for part 'swarm'")
        assertThat(mapper.readTree(output())["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    // --- Output ---

    @Test
    fun `reuses the configuration cache`() {
        setup()
        // compiles the build scripts first: compiling them during a configuration-cache run records system properties
        // the embedded Kotlin compiler reads, which change between TestKit runs and would discard the entry
        gradleRunner("help").build()
        val args = arrayOf("mergePlatformSbom", "--configuration-cache")
        val first = gradleRunner(*args).build()
        assertThat(first.output).contains("Configuration cache entry stored")
        val firstBytes = output().readBytes()
        projectDir.resolve("build/sbom").deleteRecursively()

        val result = gradleRunner(*args).build()

        assertThat(result.output).contains("Reusing configuration cache")
        assertThat(result.task(":mergePlatformSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(output().readBytes()).isEqualTo(firstBytes)
    }

    @Test
    fun `writes a document that is valid for the spec version it declares`() {
        setup()

        gradleRunner("mergePlatformSbom").build()

        val bytes = output().readBytes()
        val schema = Version.fromVersionString(mapper.readTree(bytes)["specVersion"].asText())
        assertThat(JsonParser().validate(bytes, schema).map { it.message }).isEmpty()
    }

    @Test
    fun `writes identical bytes on a rerun`() {
        setup()
        gradleRunner("mergePlatformSbom").build()
        val first = output().readBytes()

        gradleRunner("mergePlatformSbom", "--rerun-tasks").build()

        assertThat(output().readBytes()).isEqualTo(first)
    }

    @Test
    fun `writes the product SBOM on Gradle 8`() {
        setup()

        // the other tests run on the Gradle that builds the plugin, while the plugin supports Gradle 8 as well
        val result = gradleRunner("mergePlatformSbom").withGradleVersion("8.14").build()

        assertThat(result.task(":mergePlatformSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(output()).exists()
        assertThat(mapper.readTree(output())["components"].map { it["name"].asText() })
            .contains("broker", "extension", "tool")
    }
}
