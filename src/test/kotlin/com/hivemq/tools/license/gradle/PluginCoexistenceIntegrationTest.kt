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

/**
 * Verifies that this plugin coexists with other Gradle plugins commonly applied to the same project, and that a
 * command-line build still produces the license report without error. The combinations exercised are the Shadow
 * plugin, the OCI plugin, and `maven-publish`, alone and combined.
 *
 * These are command-line coexistence checks. They do not cover the Gradle Tooling API import race that the
 * `cyclonedxDirectBom` non-consumable workaround guards against: that failure ("Cannot mutate the artifacts of
 * configuration ... after the configuration was consumed as a variant") only surfaces during IDE project imports
 * (Buildship, Eclipse JDT.LS, IntelliJ) and is not reproducible from a headless command-line or single Tooling API
 * model fetch.
 */
class PluginCoexistenceIntegrationTest {

    @TempDir
    lateinit var projectDir: File

    // Supplied by the build from the version catalog (see build.gradle.kts) so Renovate updates are exercised here.
    private val shadowVersion =
        requireNotNull(System.getProperty("test.plugin.shadow.version")) {
            "System property 'test.plugin.shadow.version' is not set; run the tests through Gradle."
        }
    private val ociVersion =
        requireNotNull(System.getProperty("test.plugin.oci.version")) {
            "System property 'test.plugin.oci.version' is not set; run the tests through Gradle."
        }

    private fun gradleRunner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args)

    @Test
    fun `coexists with the Shadow plugin when the output is published as an artifact`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "shadow-project"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import com.hivemq.tools.license.gradle.UpdateThirdPartyLicensesTask

            plugins {
                java
                id("com.gradleup.shadow") version "$shadowVersion"
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("reports/third-party-licenses"))
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation("com.google.guava:guava:33.4.0-jre")
                implementation("org.slf4j:slf4j-api:2.0.16")
            }

            val releaseBinary by configurations.creating {
                isCanBeConsumed = true
                isCanBeResolved = false
            }
            val thirdPartyLicenses by configurations.creating {
                isCanBeConsumed = true
                isCanBeResolved = false
            }
            artifacts {
                add(releaseBinary.name, tasks.named("shadowJar"))
                add(
                    thirdPartyLicenses.name,
                    tasks.named<UpdateThirdPartyLicensesTask>("updateThirdPartyLicenses").flatMap { it.outputDirectory }
                )
            }
            """.trimIndent()
        )

        val result = gradleRunner("updateThirdPartyLicenses", "shadowJar").build()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":shadowJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).doesNotContain("Cannot mutate the artifacts of configuration")

        val plaintext = projectDir.resolve("build/reports/third-party-licenses/licenses").readText()
        assertThat(plaintext)
            .contains("com.google.guava:guava")
            .contains("org.slf4j:slf4j-api")
    }

    @Test
    fun `coexists with the oci plugin`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "oci-project"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("io.github.sgtsilvio.gradle.oci") version "$ociVersion"
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("reports/third-party-licenses"))
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation("org.slf4j:slf4j-api:2.0.16")
            }
            """.trimIndent()
        )

        val result = gradleRunner("updateThirdPartyLicenses").build()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).doesNotContain("Cannot mutate the artifacts of configuration")

        val plaintext = projectDir.resolve("build/reports/third-party-licenses/licenses").readText()
        assertThat(plaintext).contains("org.slf4j:slf4j-api")
    }

    @Test
    fun `coexists with the maven-publish plugin when publishing`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "publish-project"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                `maven-publish`
                id("com.hivemq.tools.license")
            }
            group = "com.example"
            version = "1.0.0"
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("reports/third-party-licenses"))
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation("org.slf4j:slf4j-api:2.0.16")
            }
            publishing {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["java"])
                    }
                }
                repositories {
                    maven { url = uri(layout.buildDirectory.dir("repo")) }
                }
            }
            """.trimIndent()
        )

        val result = gradleRunner(
            "generateMetadataFileForMavenPublication",
            "publishMavenPublicationToMavenRepository",
            "updateThirdPartyLicenses",
        ).build()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":publishMavenPublicationToMavenRepository")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).doesNotContain("Cannot mutate the artifacts of configuration")

        val plaintext = projectDir.resolve("build/reports/third-party-licenses/licenses").readText()
        assertThat(plaintext).contains("org.slf4j:slf4j-api")
    }

    @Test
    fun `coexists with the Shadow and maven-publish plugins together`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "shadow-publish-project"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                `maven-publish`
                id("com.gradleup.shadow") version "$shadowVersion"
                id("com.hivemq.tools.license")
            }
            group = "com.example"
            version = "1.0.0"
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("reports/third-party-licenses"))
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation("org.slf4j:slf4j-api:2.0.16")
            }
            publishing {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["java"])
                    }
                }
                repositories {
                    maven { url = uri(layout.buildDirectory.dir("repo")) }
                }
            }
            """.trimIndent()
        )

        val result = gradleRunner(
            "publishMavenPublicationToMavenRepository",
            "shadowJar",
            "updateThirdPartyLicenses",
        ).build()

        assertThat(result.task(":cyclonedxDirectBom")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":shadowJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":publishMavenPublicationToMavenRepository")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).doesNotContain("Cannot mutate the artifacts of configuration")

        val plaintext = projectDir.resolve("build/reports/third-party-licenses/licenses").readText()
        assertThat(plaintext).contains("org.slf4j:slf4j-api")
    }
}
