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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.hivemq.tools.license.gradle.sbom.GitTestRepository
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProvidedDependenciesIntegrationTest {

    @TempDir
    lateinit var projectDir: File

    private fun gradleRunner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args)
        // generateSbom runs git, which must see neither the machine's git settings nor a repository around the build
        .withEnvironment(GitTestRepository.isolatedEnv(projectDir))

    // the configuration as the HiveMQ extension plugin creates it: resolvable, and extended by implementation
    private val providedConfiguration = """
        val hivemqProvided by configurations.creating {
            isCanBeResolved = true
            isCanBeConsumed = false
        }
        configurations.named("implementation") { extendsFrom(hivemqProvided) }
        """.trimIndent()

    private val guavaWithProvidedSlf4j = """
        dependencies {
            implementation("com.google.guava:guava:33.4.0-jre")
            hivemqProvided("org.slf4j:slf4j-api:2.0.16")
        }
        """.trimIndent()

    // guava brings failureaccess along, so the shadow jar of an extension leaves it out as well; slf4j-api stays
    private val providedGuavaWithDeclaredFailureaccess = """
        dependencies {
            hivemqProvided("com.google.guava:guava:33.4.0-jre")
            implementation("com.google.guava:failureaccess:1.0.2")
            implementation("org.slf4j:slf4j-api:2.0.16")
        }
        """.trimIndent()

    // prints what the plugin hands to the license tasks as provided modules
    private val printProvidedModules = """
        tasks.register("printProvidedModules") {
            val provided = tasks.named<com.hivemq.tools.license.gradle.LicenseResolvingTask>("updateThirdPartyLicenses")
                .flatMap { it.providedModules }
            doLast { println("provided modules: " + provided.get().sorted()) }
        }
        """.trimIndent()

    private val head = """
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
        """.trimIndent()

    /** Each block goes below the plugins, the extension and Maven Central, so a test adds only what it needs. */
    private fun buildScript(vararg blocks: String) = listOf(head, *blocks).joinToString("\n")

    private fun setup(vararg blocks: String) {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "provided-project"""")
        projectDir.resolve("build.gradle.kts").writeText(buildScript(*blocks))
    }

    /** A subproject of [group] with the hivemqProvided configuration, below which go the given blocks. */
    private fun subproject(name: String, vararg blocks: String, group: String = "com.example") {
        val dir = projectDir.resolve(name).apply { mkdirs() }
        val script = buildScript("""group = "$group"""", providedConfiguration, *blocks)
        dir.resolve("build.gradle.kts").writeText(script)
    }

    /** `<group>:<name>:<version>` in the project's `repo` directory, depending on each com.example `name:version`. */
    private fun module(name: String, version: String, vararg dependencies: String, group: String = "com.example") =
        FixtureRepository.module(projectDir.resolve("repo"), name, version, *dependencies, group = group)

    private fun licenses(dir: File = projectDir) =
        dir.resolve("build/tmp/third-party-licenses/licenses").readText()

    private val mapper = ObjectMapper()

    /** The BOM of the generateDependencyBom task, from which generateSbom writes the SBOM. */
    private fun bom() = mapper.readTree(projectDir.resolve("build/license/bom.json"))

    private fun sbom() = mapper.readTree(projectDir.resolve("build/sbom/provided-project.cdx.json"))

    /** The `bom-ref` of the component with the given name in this BOM or SBOM. */
    private fun JsonNode.refOf(name: String) =
        this["components"].first { it["name"].asText() == name }["bom-ref"].asText()

    /** The refs of the dependency entries in this BOM or SBOM that depend on [ref]. */
    private fun JsonNode.dependantsOf(ref: String) = this["dependencies"]
        .filter { entry -> entry["dependsOn"].any { it.asText() == ref } }
        .map { it["ref"].asText() }

    // --- Reports ---

    @Test
    fun `checkApprovedLicenses ignores provided modules`() {
        FixtureRepository.unlicensedLib(projectDir.resolve("repo"))
        setup(
            providedConfiguration,
            """
            repositories {
                maven { url = uri("repo") }
            }
            dependencies {
                implementation("com.google.guava:guava:33.4.0-jre")
                hivemqProvided("com.example:no-license-lib:1.0.0")
            }
            """.trimIndent(),
        )

        val result = gradleRunner("checkApprovedLicenses").build()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        // the BOM lists the module, so the check passes by leaving it out, not by never seeing it
        assertThat(projectDir.resolve("build/license/bom.json").readText())
            .contains("no-license-lib")
    }

    @Test
    fun `drops a module reached from a provided one even when declared directly`() {
        setup(providedConfiguration, providedGuavaWithDeclaredFailureaccess)

        gradleRunner("updateThirdPartyLicenses").build()

        assertThat(licenses()).contains("org.slf4j:slf4j-api").doesNotContain("com.google.guava:failureaccess")
    }

    @Test
    fun `drops a provided project and what it brings in, not what the project declares itself`() {
        // a project in the provided graph counts like a module, and so does everything it pulls in
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "provided-project"
            include("a", "b")
            """.trimIndent()
        )
        subproject(
            "a",
            """
            dependencies {
                implementation("com.google.guava:guava:33.4.0-jre")
                hivemqProvided(project(":b"))
            }
            """.trimIndent(),
            printProvidedModules,
        )
        subproject(
            "b",
            """
            dependencies {
                implementation("org.slf4j:slf4j-api:2.0.16")
            }
            """.trimIndent(),
        )

        val result = gradleRunner(":a:printProvidedModules", ":a:updateThirdPartyLicenses").build()

        // what the project declares itself stays, what :b declares goes
        assertThat(result.output).contains("provided modules: [com.example:b, org.slf4j:slf4j-api]")
        assertThat(licenses(projectDir.resolve("a")))
            .contains("com.google.guava:guava")
            .doesNotContain("org.slf4j:slf4j-api")
    }

    @Test
    fun `reports stay the same without a hivemqProvided configuration`() {
        setup(
            """
            dependencies {
                implementation("com.google.guava:guava:33.4.0-jre")
                implementation("org.slf4j:slf4j-api:2.0.16")
            }
            """.trimIndent(),
        )

        gradleRunner("updateThirdPartyLicenses").build()

        assertThat(licenses()).contains("org.slf4j:slf4j-api")
    }

    @Test
    fun `updateThirdPartyLicenses leaves provided modules out`() {
        setup(providedConfiguration, guavaWithProvidedSlf4j)

        val result = gradleRunner("updateThirdPartyLicenses").build()

        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(licenses()).contains("com.google.guava:guava").doesNotContain("org.slf4j:slf4j-api")
    }

    @Test
    fun `updateThirdPartyLicenses leaves provided modules out when the configuration cache is reused`() {
        setup(providedConfiguration, guavaWithProvidedSlf4j)
        // Compiles the build script first: compiling it in a configuration-cache run records system properties that
        // change between TestKit runs, which would discard the entry.
        gradleRunner("help").build()
        val args = arrayOf("updateThirdPartyLicenses", "--configuration-cache")
        assertThat(gradleRunner(*args).build().output).contains("Configuration cache entry stored")
        // without its output the task runs again, with the provided modules read from the stored entry
        projectDir.resolve("build/tmp/third-party-licenses").deleteRecursively()

        val result = gradleRunner(*args).build()

        assertThat(result.output).contains("Reusing configuration cache")
        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(licenses()).contains("com.google.guava:guava").doesNotContain("org.slf4j:slf4j-api")
    }

    // --- SBOM ---

    @Test
    fun `generateSbom drops a module reached from a provided one even when declared directly`() {
        setup(providedConfiguration, providedGuavaWithDeclaredFailureaccess)

        gradleRunner("generateSbom").build()

        assertThat(sbom()["components"].map { it["name"].asText() })
            .contains("slf4j-api")
            .doesNotContain("guava", "failureaccess")
    }

    @Test
    fun `generateSbom leaves provided modules out`() {
        setup(providedConfiguration, guavaWithProvidedSlf4j)

        gradleRunner("generateSbom").build()

        // the BOM lists slf4j-api, so the SBOM lacks it because of the filter, not because CycloneDX never saw it
        val slf4jApi = bom()["components"].first { it["name"].asText() == "slf4j-api" }["bom-ref"].asText()
        val sbom = sbom()
        assertThat(sbom["components"].map { it["name"].asText() }).contains("guava").doesNotContain("slf4j-api")
        val guava = sbom["components"].first { it["name"].asText() == "guava" }["bom-ref"].asText()
        // nothing refers to slf4j-api any more, neither a dependsOn list nor the composition stating completeness
        val refs = sbom["dependencies"].flatMap { it["dependsOn"] } + sbom["compositions"][0]["dependencies"].toList()
        assertThat(refs.map { it.asText() }).contains(guava).doesNotContain(slf4jApi)
    }

    @Test
    fun `generateSbom leaves provided modules out when the configuration cache is reused`() {
        setup(providedConfiguration, guavaWithProvidedSlf4j)
        // Compiles the build script first: compiling it in a configuration-cache run records system properties that
        // change between TestKit runs, which would discard the entry.
        gradleRunner("help").build()
        val args = arrayOf("generateSbom", "--configuration-cache")
        assertThat(gradleRunner(*args).build().output).contains("Configuration cache entry stored")
        val stored = sbom()
        // without its output the task runs again, with the provided modules read from the stored entry
        projectDir.resolve("build/sbom").deleteRecursively()

        val result = gradleRunner(*args).build()

        assertThat(result.output).contains("Reusing configuration cache")
        assertThat(result.task(":generateSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        // outside a git repository the timestamp is the current time, but what the filter shapes is the same
        val sbom = sbom()
        assertThat(sbom["components"]).isEqualTo(stored["components"])
        assertThat(sbom["dependencies"]).isEqualTo(stored["dependencies"])
        assertThat(sbom["components"].map { it["name"].asText() }).contains("guava").doesNotContain("slf4j-api")
    }

    @Test
    fun `generateSbom links a module that only a newer version of a provided one brings in`() {
        // runtimeClasspath takes provided 2.0 from app-lib, which brings in extra; hivemqProvided alone resolves 1.0
        module("provided", "1.0")
        module("provided", "2.0", "extra:1.0")
        module("extra", "1.0")
        module("app-lib", "1.0", "provided:2.0")
        // the fixture POMs declare no license, so the modules that stay get one, or generateSbom fails on them
        setup(
            providedConfiguration,
            """
            repositories {
                maven { url = uri("repo") }
            }
            dependencies {
                hivemqProvided("com.example:provided:1.0")
                implementation("com.example:app-lib:1.0")
            }
            hivemqLicense {
                overriddenLicenses.put("com.example:app-lib", "Apache-2.0")
                overriddenLicenses.put("com.example:extra", "Apache-2.0")
            }
            """.trimIndent(),
        )

        gradleRunner("generateSbom").build()

        // in the BOM only the provided module depends on extra, which is not provided and so stays
        val bom = bom()
        assertThat(bom.dependantsOf(bom.refOf("extra"))).containsExactly(bom.refOf("provided"))
        val sbom = sbom()
        assertThat(sbom["components"].map { it["name"].asText() })
            .contains("app-lib", "extra")
            .doesNotContain("provided")
        val top = sbom["metadata"]["component"]["bom-ref"].asText()
        assertThat(sbom.dependantsOf(sbom.refOf("extra"))).containsExactlyInAnyOrder(top, sbom.refOf("app-lib"))
        // every reference names a component of the SBOM or its top-level component
        val known = sbom["components"].map { it["bom-ref"].asText() } + top
        val references = sbom["dependencies"].flatMap { listOf(it["ref"]) + it["dependsOn"].toList() } +
            sbom["compositions"][0]["dependencies"].toList()
        assertThat(references.map { it.asText() }).isSubsetOf(known)
    }

    // --- Own modules ---

    @Test
    fun `a build adds to the lists instead of replacing them`() {
        module("hivemq-brand-new", "1.0.0", group = "com.hivemq")
        module("hivemq-platform-sdk", "1.0.0", group = "com.hivemq")
        setup(
            """
            repositories {
                maven { url = uri("repo") }
            }
            dependencies {
                implementation("com.hivemq:hivemq-brand-new:1.0.0")
                implementation("com.hivemq:hivemq-platform-sdk:1.0.0")
                implementation("com.hivemq:hivemq-mqtt-client:1.3.0")
            }
            hivemqLicense {
                internalArtifacts.add("com.hivemq:hivemq-brand-*")
            }
            """.trimIndent(),
        )

        gradleRunner("updateThirdPartyLicenses").build()

        // the added pattern leaves the new module out; the defaults still leave out the platform SDK, which has no
        // license and would fail the build otherwise, and still list the client
        assertThat(licenses())
            .contains("com.hivemq:hivemq-mqtt-client")
            .doesNotContain("hivemq-brand-new", "hivemq-platform-sdk")
    }

    @Test
    fun `fails for a provided own module on neither list`() {
        module("hivemq-brand-new-sdk", "1.0.0", group = "com.hivemq")
        setup(
            providedConfiguration,
            """
            repositories {
                maven { url = uri("repo") }
            }
            dependencies {
                implementation("com.google.guava:guava:33.4.0-jre")
                hivemqProvided("com.hivemq:hivemq-brand-new-sdk:1.0.0")
            }
            """.trimIndent(),
        )

        val result = gradleRunner("generateSbom").buildAndFail()

        // a new public SDK must be listed as the connecting component, so it cannot vanish with the provided modules
        assertThat(result.task(":generateSbom")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains(
            "Own module 'com.hivemq:hivemq-brand-new-sdk' (ignored group prefix 'com.hivemq') is in neither " +
                "allowedArtifacts nor internalArtifacts"
        )
        assertThat(projectDir.resolve("build/sbom/provided-project.cdx.json")).doesNotExist()
    }

    @Test
    fun `fails for an own module on neither list`() {
        module("hivemq-brand-new", "1.0.0", group = "com.hivemq")
        setup(
            """
            repositories {
                maven { url = uri("repo") }
            }
            dependencies {
                implementation("com.hivemq:hivemq-brand-new:1.0.0")
            }
            """.trimIndent(),
        )

        val result = gradleRunner("generateSbom").buildAndFail()

        assertThat(result.task(":generateSbom")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains(
            "Own module 'com.hivemq:hivemq-brand-new' (ignored group prefix 'com.hivemq') is in neither " +
                "allowedArtifacts nor internalArtifacts: add it to allowedArtifacts to list it, or to " +
                "internalArtifacts to leave it out"
        )
        assertThat(projectDir.resolve("build/sbom/provided-project.cdx.json")).doesNotExist()
    }

    @Test
    fun `hides a module an internal pattern matches, license or not, until allowedArtifacts names it`() {
        // a commons module made public later still matches the internal pattern of the defaults
        module("hivemq-newly-public", "1.0.0", group = "com.hivemq.commons")
        setup(
            """
            repositories {
                maven { url = uri("repo") }
            }
            dependencies {
                implementation("com.hivemq.commons:hivemq-newly-public:1.0.0")
            }
            """.trimIndent(),
        )

        gradleRunner("updateThirdPartyLicenses", "generateSbom").build()

        assertThat(licenses()).doesNotContain("hivemq-newly-public")
        assertThat(sbom()["components"]).isEmpty()
    }

    @Test
    fun `keeps a public provided module as a component without dependencies of its own`() {
        setup(
            providedConfiguration,
            """
            dependencies {
                implementation("com.google.guava:guava:33.4.0-jre")
                hivemqProvided("com.hivemq:hivemq-extension-sdk:4.57.0")
            }
            """.trimIndent(),
        )

        gradleRunner("updateThirdPartyLicenses", "generateSbom").build()

        // the SDK is public, so the report lists it, and not what it brings in, which the host provides
        assertThat(licenses())
            .contains("com.hivemq:hivemq-extension-sdk", "com.google.guava:guava")
            .doesNotContain("org.slf4j:slf4j-api", "io.dropwizard.metrics:metrics-core")
        val sbom = sbom()
        assertThat(sbom["components"].map { it["name"].asText() })
            .contains("hivemq-extension-sdk", "guava")
            .doesNotContain("slf4j-api", "metrics-core")
        // the component where the host's SBOM continues: the top-level component depends on it, it on nothing
        val sdk = sbom.refOf("hivemq-extension-sdk")
        assertThat(sbom.dependantsOf(sdk)).containsExactly(sbom["metadata"]["component"]["bom-ref"].asText())
        assertThat(sbom["dependencies"].first { it["ref"].asText() == sdk }["dependsOn"]).isEmpty()
        // the artifact does not contain the SDK's file, so the component carries no file description
        val component = sbom["components"].first { it["name"].asText() == "hivemq-extension-sdk" }
        assertThat(component.path("properties").map { it["name"].asText() }).doesNotContain("bsi:component:filename")
    }

    @Test
    fun `keeps a public provided project of the build as a component without file or dependencies`() {
        // the platform composite builds the public SDK itself and provides it to the extensions
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "provided-project"
            include("a", "hivemq-extension-sdk")
            """.trimIndent()
        )
        subproject(
            "a",
            """
            dependencies {
                implementation("com.google.guava:guava:33.4.0-jre")
                hivemqProvided(project(":hivemq-extension-sdk"))
            }
            """.trimIndent(),
        )
        subproject(
            "hivemq-extension-sdk",
            """
            dependencies {
                implementation("org.slf4j:slf4j-api:2.0.16")
            }
            """.trimIndent(),
            group = "com.hivemq",
        )

        gradleRunner(":a:updateThirdPartyLicenses", ":a:generateSbom").build()

        // the project has no POM, so its built-in license lists it; what it brings in is provided and left out
        assertThat(licenses(projectDir.resolve("a")))
            .contains("com.hivemq:hivemq-extension-sdk")
            .doesNotContain("org.slf4j:slf4j-api")
        val sbom = mapper.readTree(projectDir.resolve("a/build/sbom/a.cdx.json"))
        assertThat(sbom["components"].map { it["name"].asText() })
            .contains("hivemq-extension-sdk", "guava")
            .doesNotContain("slf4j-api")
        val sdk = sbom["components"].first { it["name"].asText() == "hivemq-extension-sdk" }
        val sdkRef = sdk["bom-ref"].asText()
        assertThat(sbom["dependencies"].first { it["ref"].asText() == sdkRef }["dependsOn"]).isEmpty()
        assertThat(sdk.path("properties").map { it["name"].asText() }).doesNotContain("bsi:component:filename")
    }

    @Test
    fun `leaves a project of the build in an own group out`() {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "provided-project"
            include("a", "b")
            """.trimIndent()
        )
        subproject(
            "a",
            """
            dependencies {
                implementation(project(":b"))
                implementation("com.google.guava:guava:33.4.0-jre")
            }
            """.trimIndent(),
        )
        // a project has no license and is on no list; as one of our own, it is left out instead of failing the build
        subproject(
            "b",
            """
            dependencies {
                implementation("org.slf4j:slf4j-api:2.0.16")
            }
            """.trimIndent(),
            group = "com.hivemq",
        )

        val result = gradleRunner(":a:generateSbom").build()

        assertThat(result.task(":a:generateSbom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val sbom = mapper.readTree(projectDir.resolve("a/build/sbom/a.cdx.json"))
        assertThat(sbom["components"].map { it["name"].asText() }).contains("guava", "slf4j-api").doesNotContain("b")
        // what the hidden project brings in attaches to the component that depended on the project
        assertThat(sbom.dependantsOf(sbom.refOf("slf4j-api")))
            .containsExactly(sbom["metadata"]["component"]["bom-ref"].asText())
    }

    @Test
    fun `leaves an internal module out and links what it brings in to the dependant`() {
        module("hivemq-platform-sdk", "1.0.0", "extra:1.0", group = "com.hivemq")
        module("extra", "1.0")
        setup(
            """
            repositories {
                maven { url = uri("repo") }
            }
            dependencies {
                implementation("com.hivemq:hivemq-platform-sdk:1.0.0")
            }
            hivemqLicense {
                overriddenLicenses.put("com.example:extra", "Apache-2.0")
            }
            """.trimIndent(),
        )

        gradleRunner("updateThirdPartyLicenses", "generateSbom").build()

        // the internal module has no license and is not listed; extra, which only it brings in, is
        assertThat(licenses()).contains("com.example:extra").doesNotContain("com.hivemq:hivemq-platform-sdk")
        val sbom = sbom()
        assertThat(sbom["components"].map { it["name"].asText() }).containsExactly("extra")
        assertThat(sbom.dependantsOf(sbom.refOf("extra")))
            .containsExactly(sbom["metadata"]["component"]["bom-ref"].asText())
    }

    @Test
    fun `lists a module an internal pattern matches once allowedArtifacts names it`() {
        module("hivemq-newly-public", "1.0.0", group = "com.hivemq.commons")
        setup(
            """
            repositories {
                maven { url = uri("repo") }
            }
            dependencies {
                implementation("com.hivemq.commons:hivemq-newly-public:1.0.0")
            }
            hivemqLicense {
                allowedArtifacts.add("com.hivemq.commons:hivemq-newly-public")
                overriddenLicenses.put("com.hivemq.commons:hivemq-newly-public", "Apache-2.0")
            }
            """.trimIndent(),
        )

        gradleRunner("updateThirdPartyLicenses", "generateSbom").build()

        // the public list wins over the internal pattern, so the module is listed and checked like any library
        assertThat(licenses()).contains("com.hivemq.commons:hivemq-newly-public")
        assertThat(sbom()["components"].map { it["name"].asText() }).containsExactly("hivemq-newly-public")
    }
}
