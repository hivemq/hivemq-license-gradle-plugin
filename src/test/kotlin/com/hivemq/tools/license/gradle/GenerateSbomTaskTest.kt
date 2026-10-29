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

class GenerateSbomTaskTest {

    @TempDir
    lateinit var projectDir: File

    private fun gradleRunner() = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments("sbomFromFile")

    private fun setup() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "task-project"""")
        projectDir.resolve("bom.json").writeText(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "serialNumber": "urn:uuid:fcc72669-6094-47f1-a9fe-81e7c08e2d54",
              "version": 1,
              "metadata": { "timestamp": "2026-10-16T10:01:10Z", "component": { "name": "app" } },
              "components": [
                {
                  "group": "org.example",
                  "name": "lib",
                  "version": "1.0",
                  "purl": "pkg:maven/org.example/lib@1.0?type=jar",
                  "licenses": [ { "license": { "id": "Apache-2.0" } } ]
                }
              ],
              "dependencies": [ { "ref": "pkg:maven/org.example/lib@1.0?type=jar", "dependsOn": [] } ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import com.hivemq.tools.license.gradle.GenerateSbomTask
            import java.time.Instant

            plugins {
                id("com.hivemq.tools.license")
            }
            tasks.register<GenerateSbomTask>("sbomFromFile") {
                bom.set(layout.projectDirectory.file("bom.json"))
                timestamp.set(Instant.parse("2026-01-02T03:04:05Z"))
                creatorName.set("HiveMQ GmbH")
                creator.set("https://www.hivemq.com/")
                artifactFileName.set("app-1.0.0.jar")
                artifactFileNames.set(mapOf("org.example:lib:1.0" to "lib-1.0.jar"))
                ignoredGroupPrefixes.set(setOf("com.hivemq"))
                allowedArtifacts.set(emptySet())
                overriddenLicenses.set(mapOf("org.example:lib" to "MIT"))
                outputFile.set(layout.buildDirectory.file("sbom/app.cdx.json"))
            }
            """.trimIndent()
        )
    }

    // --- Output ---

    @Test
    fun `does not fail on a license that is not approved`() {
        setup()
        projectDir.resolve("bom.json").writeText(
            projectDir.resolve("bom.json").readText().replace("Apache-2.0", "Proprietary-9.9")
        )
        projectDir.resolve("build.gradle.kts").writeText(
            projectDir.resolve("build.gradle.kts").readText()
                .replace("""mapOf("org.example:lib" to "MIT")""", "emptyMap()")
        )

        val result = gradleRunner().build()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(projectDir.resolve("build/sbom/app.cdx.json").readText()).doesNotContain("concluded")
    }

    @Test
    fun `fails naming the BOM file when a component cannot be read`() {
        setup()
        // valid JSON, but a component without a name and a version is not one the license resolver can map
        projectDir.resolve("bom.json").writeText("""{ "components": [ { "group": "org.example" } ] }""")

        val result = gradleRunner().buildAndFail()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output)
            .contains("Cannot write an SBOM from '")
            .contains("bom.json': ")
    }

    @Test
    fun `fails naming the BOM file when the BOM declares a spec version before the minimum`() {
        setup()
        projectDir.resolve("bom.json").writeText(
            projectDir.resolve("bom.json").readText().replace(""""specVersion": "1.6"""", """"specVersion": "1.5"""")
        )

        val result = gradleRunner().buildAndFail()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output)
            .contains("Cannot write an SBOM from '")
            .contains(
                "bom.json': The BOM declares CycloneDX specVersion 1.5, but the manufacturer, license " +
                    "acknowledgement and source-distribution fields need ${SbomEnricher.MINIMUM_SPEC_VERSION} or later"
            )
    }

    @Test
    fun `fails naming the BOM file when the input is not a BOM`() {
        setup()
        projectDir.resolve("bom.json").writeText("[]")

        val result = gradleRunner().buildAndFail()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output)
            .contains("Cannot write an SBOM from '")
            .contains("bom.json': The BOM is not a JSON object")
    }

    @Test
    fun `fails naming the BOM file when the input is not valid JSON`() {
        setup()
        projectDir.resolve("bom.json").writeText("{")

        val result = gradleRunner().buildAndFail()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output)
            .contains("Cannot write an SBOM from '")
            .contains("bom.json': The BOM is not valid JSON: ")
    }

    @Test
    fun `writes the creator, the file description and the concluded license`() {
        setup()

        gradleRunner().build()

        val output = projectDir.resolve("build/sbom/app.cdx.json").readText()
        assertThat(output)
            .contains(""""name" : "HiveMQ GmbH"""")
            .contains(""""bsi:component:filename"""")
            .contains(""""value" : "lib-1.0.jar"""")
            .contains(""""acknowledgement" : "declared"""")
            .contains(""""id" : "MIT"""")
            .contains(""""aggregate" : "complete"""")
    }

    @Test
    fun `writes the normalized BOM to the output file`() {
        setup()

        val result = gradleRunner().build()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val output = projectDir.resolve("build/sbom/app.cdx.json").readText()
        assertThat(output)
            .contains(""""timestamp" : "2026-01-02T03:04:05Z"""")
            .doesNotContain("fcc72669-6094-47f1-a9fe-81e7c08e2d54")
    }

    // --- Up to date ---

    @Test
    fun `is up to date when nothing changed`() {
        setup()
        gradleRunner().build()

        val result = gradleRunner().build()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun `reruns when the BOM changes`() {
        setup()
        gradleRunner().build()
        projectDir.resolve("bom.json").writeText(
            projectDir.resolve("bom.json").readText().replace(""""name": "app"""", """"name": "renamed-app"""")
        )

        val result = gradleRunner().build()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(projectDir.resolve("build/sbom/app.cdx.json").readText()).contains("renamed-app")
    }

    @Test
    fun `reruns when the timestamp changes`() {
        setup()
        gradleRunner().build()
        projectDir.resolve("build.gradle.kts").writeText(
            projectDir.resolve("build.gradle.kts").readText().replace("2026-01-02T03:04:05Z", "2026-01-02T03:04:06Z")
        )

        val result = gradleRunner().build()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(projectDir.resolve("build/sbom/app.cdx.json").readText()).contains("2026-01-02T03:04:06Z")
    }
}
