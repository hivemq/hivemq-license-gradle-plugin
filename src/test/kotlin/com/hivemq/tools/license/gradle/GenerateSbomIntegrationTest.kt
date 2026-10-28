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

class GenerateSbomIntegrationTest {

    @TempDir
    lateinit var projectDir: File

    private fun gradleRunner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args)
        .withEnvironment(GitTestRepository.isolatedEnv(projectDir))

    /**
     * A project with two real dependencies, or with none for a test that does not need Maven Central;
     * [hivemqLicense] goes into the extension block.
     */
    private fun setup(hivemqLicense: String = "", externalDependencies: Boolean = true) {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "sbom-project"""")
        val buildScript = """
            import java.time.Instant

            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
                $hivemqLicense
            }
            """.trimIndent()
        val dependencies = """
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation("com.google.guava:guava:33.4.0-jre")
                implementation("org.slf4j:slf4j-api:2.0.16")
            }
            """.trimIndent()
        projectDir.resolve("build.gradle.kts")
            .writeText(if (externalDependencies) "$buildScript\n$dependencies" else buildScript)
    }

    private val fixedTimestamp = """sbomTimestamp.set(Instant.parse("2026-01-02T03:04:05Z"))"""

    private fun sbom() = projectDir.resolve("build/sbom/sbom-project.cdx.json")

    private val mapper = ObjectMapper()

    // --- Content ---

    @Test
    fun `describes the dependencies with their files, licenses and creators`() {
        setup(fixedTimestamp)

        gradleRunner("generateSbom").build()

        val sbom = mapper.readTree(sbom())
        val guava = sbom["components"].first { it["name"].asText() == "guava" }
        assertThat(guava["properties"].map { it["name"].asText() to it["value"].asText() })
            .contains(
                "bsi:component:filename" to "guava-33.4.0-jre.jar",
                "bsi:component:executable" to "executable",
                "bsi:component:archive" to "archive",
                "bsi:component:structured" to "structured",
            )
        assertThat(guava["licenses"].map { it["license"]["id"].asText() to it["license"]["acknowledgement"].asText() })
            .containsExactly("Apache-2.0" to "declared", "Apache-2.0" to "concluded")
        // The creator reference comes from the <organization> and <scm> elements of a component's own POM. Guava's POM
        // has neither, only a project <url>, which is not used, so the creator is asserted on checker-qual instead.
        val checkerQual = sbom["components"].first { it["name"].asText() == "checker-qual" }
        assertThat(checkerQual["manufacturer"]["url"][0].asText())
            .startsWith("https://github.com/typetools/checker-framework")
        assertThat(sbom["metadata"]["manufacturer"]["name"].asText()).isEqualTo("HiveMQ GmbH")
        assertThat(sbom["metadata"]["manufacturer"]["contact"][0]["email"].asText()).isEqualTo("security@hivemq.com")
        assertThat(sbom["metadata"]["component"]["properties"].map { it["value"].asText() })
            .contains("sbom-project.jar")
        assertThat(sbom["compositions"][0]["aggregate"].asText()).isEqualTo("complete")
    }

    @Test
    fun `describes the files of the additional configurations`() {
        setup("additionalConfigurations.add(\"extra\")\n$fixedTimestamp", externalDependencies = false)
        // the plugin creates the configuration it resolves, hivemqLicense_extra, only after the build script ran
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            val extra by configurations.creating
            repositories {
                mavenCentral()
            }
            dependencies {
                extra("org.slf4j:slf4j-api:2.0.16")
            }
            """.trimIndent()
        )

        gradleRunner("generateSbom").build()

        val slf4j = mapper.readTree(sbom())["components"].first { it["name"].asText() == "slf4j-api" }
        assertThat(slf4j["properties"].map { it["name"].asText() to it["value"].asText() })
            .contains("bsi:component:filename" to "slf4j-api-2.0.16.jar")
    }

    @Test
    fun `does not build the jar or the jars of project dependencies`() {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "multi-project"
            include("lib", "app")
            """.trimIndent()
        )
        projectDir.resolve("lib").mkdirs()
        projectDir.resolve("lib/build.gradle.kts").writeText(
            """
            plugins {
                java
            }
            """.trimIndent()
        )
        projectDir.resolve("app").mkdirs()
        projectDir.resolve("app/build.gradle.kts").writeText(
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
            dependencies {
                implementation(project(":lib"))
            }
            """.trimIndent()
        )

        val result = gradleRunner(":app:generateSbom").build()

        assertThat(result.task(":app:generateSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":app:jar")).isNull()
        assertThat(result.task(":lib:jar")).isNull()
        val sbom = mapper.readTree(projectDir.resolve("app/build/sbom/app.cdx.json"))
        assertThat(sbom["metadata"]["component"].path("properties").map { it["value"].asText() }).contains("app.jar")
        // a project has no Maven coordinates, so its component gets no file
        val lib = sbom["components"].first { it["name"].asText() == "lib" }
        assertThat(lib.path("properties").map { it["name"].asText() }).doesNotContain("bsi:component:filename")
    }

    @Test
    fun `keeps the file names when the configuration cache is reused`() {
        setup(fixedTimestamp)
        // Compiles the build script first: compiling it in a configuration-cache run records system properties that
        // change between TestKit runs, which would discard the entry.
        gradleRunner("help").build()
        val args = arrayOf("generateSbom", "--configuration-cache")
        gradleRunner(*args).build()
        // without its output the task runs again, with the inputs read from the stored entry
        projectDir.resolve("build/sbom").deleteRecursively()

        val result = gradleRunner(*args).build()

        assertThat(result.output).contains("Reusing configuration cache")
        assertThat(result.task(":generateSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val guava = mapper.readTree(sbom())["components"].first { it["name"].asText() == "guava" }
        assertThat(guava["properties"].map { it["name"].asText() to it["value"].asText() })
            .contains("bsi:component:filename" to "guava-33.4.0-jre.jar")
    }

    @Test
    fun `names no file without the java plugin`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "plain-project"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import java.time.Instant

            plugins {
                base
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
                $fixedTimestamp
            }
            """.trimIndent()
        )

        val result = gradleRunner("generateSbom").build()

        assertThat(result.task(":generateSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val sbom = mapper.readTree(projectDir.resolve("build/sbom/plain-project.cdx.json"))
        assertThat(sbom["metadata"]["component"].path("properties").map { it["name"].asText() })
            .doesNotContain("bsi:component:filename")
    }

    @Test
    fun `overriddenLicenses sets the concluded license of a dependency`() {
        setup("overriddenLicenses.put(\"com.google.guava:guava\", \"MIT\")\n$fixedTimestamp")

        gradleRunner("generateSbom").build()

        val guava = mapper.readTree(sbom())["components"].first { it["name"].asText() == "guava" }
        val concluded = guava["licenses"].filter { it["license"]["acknowledgement"].asText() == "concluded" }
        assertThat(concluded)
            .containsExactly(mapper.readTree("""{ "license": { "id": "MIT", "acknowledgement": "concluded" } }"""))
    }

    @Test
    fun `sbomCreator and sbomArtifactFileName override the defaults`() {
        setup("sbomCreator.set(\"security@example.com\")\nsbomArtifactFileName.set(\"hivemq.jar\")\n$fixedTimestamp")

        gradleRunner("generateSbom").build()

        val sbom = mapper.readTree(sbom())
        assertThat(sbom["metadata"]["manufacturer"]["contact"][0]["email"].asText()).isEqualTo("security@example.com")
        assertThat(sbom["metadata"]["component"]["properties"].map { it["value"].asText() }).contains("hivemq.jar")
    }

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
    fun `replaces the CI references with the project's origin remote`() {
        setup(fixedTimestamp)
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "build.gradle.kts")
        GitTestRepository.setRemote(projectDir, "git@github.com:hivemq/x.git")
        val ci = mapOf("BUILD_URL" to "https://ci.example.com/job/1/", "GIT_URL" to "git@github.com:other/wrong.git")

        gradleRunner("generateSbom").withEnvironment(GitTestRepository.isolatedEnv(projectDir, ci)).build()

        // CycloneDX wrote both references from the environment; on GitHub Actions its run URL wins over BUILD_URL
        assertThat(projectDir.resolve("build/reports/cyclonedx-direct/bom.json").readText())
            .contains("build-system")
            .contains("other/wrong")
        assertThat(sbom().readText())
            .doesNotContain("build-system")
            .doesNotContain("ci.example.com")
            .doesNotContain("other/wrong")
            .contains(""""url" : "https://github.com/hivemq/x"""")
    }

    @Test
    fun `two machines write identical bytes`(@TempDir otherMachine: File) {
        setup()
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "build.gradle.kts")
        GitTestRepository.setRemote(projectDir, "git@github.com:hivemq/x.git")
        for (name in listOf("settings.gradle.kts", "build.gradle.kts", ".git")) {
            projectDir.resolve(name).copyRecursively(otherMachine.resolve(name))
        }
        // the other machine cloned the same commit over https and builds it in another CI job
        GitTestRepository.git(otherMachine, "remote", "set-url", "origin", "https://github.com/hivemq/x.git")
        val ci = mapOf("BUILD_URL" to "https://ci.example.com/job/1/", "GIT_URL" to "git@github.com:hivemq/x.git")
        val otherCi = mapOf("BUILD_URL" to "https://ci2.example.com/job/2/", "GIT_URL" to "https://github.com/hivemq/x")
        gradleRunner("generateSbom").withEnvironment(GitTestRepository.isolatedEnv(projectDir, ci)).build()

        gradleRunner("generateSbom")
            .withProjectDir(otherMachine)
            .withEnvironment(GitTestRepository.isolatedEnv(otherMachine, otherCi))
            .build()

        // CycloneDX wrote a different serial number, timestamp and references, the normalized files are the same
        val bom = "build/reports/cyclonedx-direct/bom.json"
        assertThat(otherMachine.resolve(bom).readBytes()).isNotEqualTo(projectDir.resolve(bom).readBytes())
        assertThat(otherMachine.resolve("build/sbom/sbom-project.cdx.json").readBytes()).isEqualTo(sbom().readBytes())
    }

    // --- Timestamp defaults ---

    @Test
    fun `a malformed SOURCE_DATE_EPOCH fails with a clear message`() {
        setup(externalDependencies = false)

        val result = gradleRunner("generateSbom")
            .withEnvironment(GitTestRepository.isolatedEnv(projectDir, mapOf("SOURCE_DATE_EPOCH" to "yesterday")))
            .buildAndFail()

        assertThat(result.output).contains("SOURCE_DATE_EPOCH must be the number of seconds")
    }

    @Test
    fun `falls back to the current time with a warning outside a git repository`() {
        setup(externalDependencies = false)

        val result = gradleRunner("generateSbom").build()

        assertThat(result.output)
            .contains("project ':', the SBOM timestamp is the current time")
            .contains("exited with 128: fatal: not a git repository")
            // the missing vcs reference is logged at info level here, so there is no second warning
            .doesNotContain("has no vcs reference")
        assertThat(sbom()).exists()
    }

    @Test
    fun `sbomTimestamp beats SOURCE_DATE_EPOCH`() {
        setup(fixedTimestamp, externalDependencies = false)

        gradleRunner("generateSbom")
            .withEnvironment(GitTestRepository.isolatedEnv(projectDir, mapOf("SOURCE_DATE_EPOCH" to "1700000000")))
            .build()

        assertThat(sbom().readText()).contains(""""timestamp" : "2026-01-02T03:04:05Z"""")
    }

    @Test
    fun `SOURCE_DATE_EPOCH beats the commit time`() {
        setup(externalDependencies = false)
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "build.gradle.kts")

        gradleRunner("generateSbom")
            .withEnvironment(GitTestRepository.isolatedEnv(projectDir, mapOf("SOURCE_DATE_EPOCH" to "1700000000")))
            .build()

        assertThat(sbom().readText()).contains(""""timestamp" : "2023-11-14T22:13:20Z"""")
    }

    @Test
    fun `the configuration cache reads the commit time on reuse without a warning`() {
        setup(externalDependencies = false)
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "build.gradle.kts")
        // Compiles the build script first: compiling it during a configuration-cache run records system properties
        // the embedded Kotlin compiler reads, which change between TestKit runs and would discard the entry.
        gradleRunner("help").build()
        val args = arrayOf("generateSbom", "--configuration-cache")
        val first = gradleRunner(*args).build()
        assertThat(first.output).doesNotContain("SBOM timestamp is the current time")
        assertThat(sbom().readText()).contains(""""timestamp" : "2024-05-06T07:08:09Z"""")
        // git runs when the task runs, not when the entry is stored, so a later commit changes the timestamp
        GitTestRepository.commitAt(projectDir, "2024-06-07T08:09:10Z", "second.txt")

        val second = gradleRunner(*args).build()

        assertThat(second.output)
            .contains("Reusing configuration cache")
            .doesNotContain("SBOM timestamp is the current time")
        assertThat(sbom().readText()).contains(""""timestamp" : "2024-06-07T08:09:10Z"""")
    }

    @Test
    fun `the last commit time is the timestamp when SOURCE_DATE_EPOCH is not set`() {
        setup(externalDependencies = false)
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "build.gradle.kts")

        gradleRunner("generateSbom").build()

        assertThat(sbom().readText()).contains(""""timestamp" : "2024-05-06T07:08:09Z"""")
    }

    // --- Variant ---

    @Test
    fun `a producer without the java plugin keeps its default configuration`() {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "plain-project"
            include("plain", "app")
            """.trimIndent()
        )
        projectDir.resolve("plain").mkdirs()
        projectDir.resolve("plain/payload.txt").writeText("payload")
        projectDir.resolve("plain/build.gradle.kts").writeText(
            """
            plugins {
                base
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            }
            tasks.register<Zip>("payload") {
                from("payload.txt")
                archiveFileName.set("payload.zip")
            }
            artifacts {
                add("default", tasks.named("payload"))
            }
            """.trimIndent()
        )
        projectDir.resolve("app").mkdirs()
        projectDir.resolve("app/build.gradle.kts").writeText(
            """
            plugins {
                java
            }
            dependencies {
                runtimeOnly(project(":plain"))
            }
            tasks.register<Copy>("copyRuntime") {
                from(configurations.runtimeClasspath)
                into(layout.buildDirectory.dir("runtime"))
            }
            """.trimIndent()
        )

        val result = gradleRunner(":app:copyRuntime").build()

        assertThat(result.task(":app:copyRuntime")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(projectDir.resolve("app/build/runtime/payload.zip")).exists()
    }

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
    fun `assemble does not run generateSbom`() {
        setup(externalDependencies = false)

        // Gradle 8 builds the artifacts of every visible configuration as part of assemble
        // TestKit downloads the distribution into ~/.gradle/wrapper/dists, which CI caches, so this costs the
        // download once per cache key
        val result = gradleRunner("assemble", "--dry-run").withGradleVersion("8.14").build()

        assertThat(result.output).contains(":jar SKIPPED").doesNotContain(":generateSbom")
    }

    @Test
    fun `assemble does not run generateSbom on the current Gradle`() {
        setup(externalDependencies = false)

        val result = gradleRunner("assemble", "--dry-run").build()

        assertThat(result.output).contains(":jar SKIPPED").doesNotContain(":generateSbom")
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
        assertThat(result.task(":lib:generateSbom")).isNull()
        assertThat(result.task(":lib:cyclonedxDirectBom")).isNull()
    }
}
