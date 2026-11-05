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
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class MergeSbomsTaskTest {

    @TempDir
    lateinit var projectDir: File

    private val mapper = ObjectMapper()

    private fun gradleRunner() = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments("mergeProduct")
        .withEnvironment(GitTestRepository.isolatedEnv(projectDir))

    /** A part SBOM as `generateSbom` writes it, reduced to what the merger reads. */
    private fun partSbom(name: String, hash: String = "aa") = """
        {
          "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
          "metadata": {
            "component": {
              "type": "application", "bom-ref": "pkg:maven/org.example/$name@1.0?project_path=%3A",
              "group": "org.example", "name": "$name", "version": "1.0"
            }
          },
          "components": [
            {
              "type": "library", "bom-ref": "pkg:maven/org.example/lib@1.0?type=jar",
              "group": "org.example", "name": "lib", "version": "1.0",
              "hashes": [ { "alg": "SHA-256", "content": "$hash" } ]
            }
          ],
          "dependencies": [
            {
              "ref": "pkg:maven/org.example/$name@1.0?project_path=%3A",
              "dependsOn": [ "pkg:maven/org.example/lib@1.0?type=jar" ]
            },
            { "ref": "pkg:maven/org.example/lib@1.0?type=jar", "dependsOn": [] }
          ],
          "compositions": [
            {
              "aggregate": "complete",
              "dependencies": [
                "pkg:maven/org.example/$name@1.0?project_path=%3A",
                "pkg:maven/org.example/lib@1.0?type=jar"
              ]
            }
          ]
        }
    """.trimIndent()

    /**
     * The plugin applied for its conventions and a hand-registered merge of two part files; [extra] goes into the
     * task and [extension] into the `hivemqLicense` block.
     */
    private fun setup(extra: String = "", extension: String = "") {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "merge-project"""")
        projectDir.resolve("parts").mkdirs()
        projectDir.resolve("parts/alpha.cdx.json").writeText(partSbom("alpha"))
        projectDir.resolve("parts/beta.cdx.json").writeText(partSbom("beta"))
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import com.hivemq.tools.license.gradle.sbom.MergeSbomsTask
            import java.time.Instant

            plugins {
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
                sbomTimestamp.set(Instant.parse("2026-01-02T03:04:05Z"))
                $extension
            }
            tasks.register<MergeSbomsTask>("mergeProduct") {
                productName.set("product")
                productVersion.set("1.0.0")
                artifactFileName.set("product-1.0.0.zip")
                sboms.from(fileTree("parts"))
                $extra
            }
            """.trimIndent()
        )
    }

    private fun output() = projectDir.resolve("build/sbom/product.cdx.json")

    /**
     * The start of a merge failure. Gradle names canonical paths, and the temporary directory may be reached through
     * a symbolic link.
     */
    private fun failurePrefix() = "Cannot merge the SBOMs into '${output().canonicalPath}': "

    // --- Output ---

    @Test
    fun `fails naming the output file on a timestamp outside the four-digit years`() {
        setup("""timestamp.set("+10000-01-01T00:00:00Z")""")

        val result = gradleRunner().buildAndFail()

        assertThat(result.output).contains(failurePrefix()).contains("is not a valid date")
        assertThat(output()).doesNotExist()
    }

    @Test
    fun `fails naming the output file when a part cannot be merged`() {
        setup()
        projectDir.resolve("parts/beta.cdx.json").writeText(partSbom("beta", hash = "bb"))

        val result = gradleRunner().buildAndFail()

        assertThat(result.output)
            .contains(failurePrefix())
            .contains("'pkg:maven/org.example/lib@1.0?type=jar' has different hashes in '")
        assertThat(output()).doesNotExist()
    }

    @Test
    fun `fails naming the output file when a part cannot be read`() {
        setup("""sboms.from(file("parts"))""")

        val result = gradleRunner().buildAndFail()

        assertThat(result.output)
            .contains(failurePrefix() + "cannot read '${projectDir.resolve("parts").canonicalPath}': ")
        assertThat(output()).doesNotExist()
    }

    @Test
    fun `fails naming the output file when there is no part to merge`() {
        setup()
        projectDir.resolve("parts/alpha.cdx.json").delete()
        projectDir.resolve("parts/beta.cdx.json").delete()

        val result = gradleRunner().buildAndFail()

        assertThat(result.output).contains(failurePrefix() + "the sboms input holds no file")
        assertThat(output()).doesNotExist()
    }

    @Test
    fun `fails on a timestamp that is not an instant`() {
        setup("""timestamp.set("yesterday")""")

        val result = gradleRunner().buildAndFail()

        assertThat(result.output).contains(
            "The timestamp of task ':mergeProduct' must be an ISO 8601 instant such as 2026-01-02T03:04:05Z, " +
                "but is 'yesterday'"
        )
    }

    @Test
    fun `rejects a product name that differs from the sbomName of the project only in case`() {
        setup("""productName.set("Merge-Project")""")

        val result = gradleRunner().buildAndFail()

        assertThat(result.output).contains(
            "The productName of task ':mergeProduct' must differ from hivemqLicense.sbomName, " +
                "which is 'merge-project'"
        )
        assertThat(projectDir.resolve("build/sbom/Merge-Project.cdx.json")).doesNotExist()
    }

    @Test
    fun `rejects a product name that is not a file name`() {
        setup("""productName.set("../x")""")

        val result = gradleRunner().buildAndFail()

        assertThat(result.output).contains(
            "The productName of task ':mergeProduct' must be a non-blank file name without '/' or '\\', " +
                "but is '../x'"
        )
        assertThat(projectDir.resolve("build/x.cdx.json")).doesNotExist()
    }

    @Test
    fun `rejects a product name that is the project name, the default sbomName`() {
        setup("""productName.set("merge-project")""")

        val result = gradleRunner().buildAndFail()

        assertThat(result.output).contains(
            "The productName of task ':mergeProduct' must differ from hivemqLicense.sbomName, " +
                "which is 'merge-project'"
        )
        assertThat(projectDir.resolve("build/sbom/merge-project.cdx.json")).doesNotExist()
    }

    @Test
    fun `rejects a product name that is the sbomName of the project`() {
        setup("""productName.set("same")""", extension = """sbomName.set("same")""")

        val result = gradleRunner().buildAndFail()

        assertThat(result.output).contains(
            "The productName of task ':mergeProduct' must differ from hivemqLicense.sbomName, which is 'same'"
        )
        assertThat(projectDir.resolve("build/sbom/same.cdx.json")).doesNotExist()
    }

    @Test
    fun `warns when the product is incomplete`() {
        setup("""expectedParts.set(setOf("alpha", "beta", "gamma"))""")

        val result = gradleRunner().build()

        assertThat(result.output).contains(
            "The product SBOM 'product' of task ':mergeProduct' declares its dependency graph incomplete: " +
                "no SBOM for part 'gamma'"
        )
        assertThat(mapper.readTree(output())["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `writes the creator of the extension as manufacturer and the origin remote as vcs reference`() {
        setup(extension = """sbomCreatorName.set("Example Corp"); sbomCreator.set("https://security.example.com/")""")
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "build.gradle.kts")
        GitTestRepository.setRemote(projectDir, "https://github.com/example/product.git")

        gradleRunner().build()

        val component = mapper.readTree(output())["metadata"]["component"]
        assertThat(component["manufacturer"]["name"].asText()).isEqualTo("Example Corp")
        assertThat(component["manufacturer"]["url"].map { it.asText() })
            .containsExactly("https://security.example.com/")
        assertThat(component["externalReferences"].filter { it["type"].asText() == "vcs" }.map { it["url"].asText() })
            .containsExactly("https://github.com/example/product")
    }

    @Test
    fun `writes the product SBOM with the conventions of the extension`() {
        setup()

        val result = gradleRunner().build()

        assertThat(result.task(":mergeProduct")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val sbom = mapper.readTree(output())
        assertThat(sbom["metadata"]["timestamp"].asText()).isEqualTo("2026-01-02T03:04:05Z")
        assertThat(sbom["metadata"]["manufacturer"]["name"].asText()).isEqualTo("HiveMQ GmbH")
        assertThat(sbom["metadata"]["manufacturer"]["contact"][0]["email"].asText()).isEqualTo("security@hivemq.com")
        assertThat(sbom["metadata"]["component"]["bom-ref"].asText()).isEqualTo("product@1.0.0")
        assertThat(sbom["components"].map { it["name"].asText() }).containsExactly("alpha", "beta", "lib")
        assertThat(sbom["serialNumber"].asText()).startsWith("urn:uuid:")
        assertThat(result.output).doesNotContain("declares its dependency graph incomplete")
    }

    @Test
    fun `writes to the output file that is set for a product named like the project`() {
        setup(
            """
            productName.set("merge-project")
            outputFile.set(layout.buildDirectory.file("sbom/other.cdx.json"))
            """.trimIndent()
        )

        gradleRunner().build()

        assertThat(projectDir.resolve("build/sbom/other.cdx.json")).exists()
    }

    // --- Up to date ---

    @Test
    fun `is up to date when a part file is renamed`() {
        setup()
        gradleRunner().build()
        val parts = projectDir.resolve("parts")
        Files.move(parts.resolve("alpha.cdx.json").toPath(), parts.resolve("alpha-renamed.cdx.json").toPath())

        val result = gradleRunner().build()

        assertThat(result.task(":mergeProduct")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun `is up to date when nothing changed`() {
        setup()
        gradleRunner().build()

        val result = gradleRunner().build()

        assertThat(result.task(":mergeProduct")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun `reruns when a part is added`() {
        setup()
        gradleRunner().build()
        projectDir.resolve("parts/gamma.cdx.json").writeText(partSbom("gamma"))

        val result = gradleRunner().build()

        assertThat(result.task(":mergeProduct")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(mapper.readTree(output())["components"].map { it["name"].asText() }).contains("gamma")
    }
}
