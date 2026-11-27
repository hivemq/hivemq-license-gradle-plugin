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

    /**
     * A part SBOM as `generateSbom` writes it, reduced to what the merger reads, concluding [license] for `lib`. With
     * [identifiesSdk], the part also depends on `sdk`, which it only identifies, as an extension identifies the SDK of
     * its host.
     */
    private fun partSbom(
        name: String,
        hash: String = "aa",
        license: String = "Apache-2.0",
        identifiesSdk: Boolean = false,
    ): String {
        val primary = "pkg:maven/org.example/$name@1.0?project_path=%3A"
        val lib = "pkg:maven/org.example/lib@1.0?type=jar"
        val sdk = "pkg:maven/org.example/sdk@1.0?type=jar"
        // each piece is empty for a part that does not identify the SDK
        fun withSdk(text: String) = if (identifiesSdk) text else ""
        val label = """{ "name": "${SbomEnricher.PROVIDED_BY_HOST}", "value": "true" }"""
        val sdkComponent = withSdk(
            """, { "type": "library", "bom-ref": "$sdk", "group": "org.example", "name": "sdk", "version": "1.0", """ +
                """"properties": [ $label ] }"""
        )
        val sdkTarget = withSdk(""", "$sdk"""")
        val sdkEntry = withSdk(""", { "ref": "$sdk", "dependsOn": [] }""")
        val sdkGap = withSdk(""", { "aggregate": "incomplete", "dependencies": [ "$sdk" ] }""")
        return """
            {
              "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
              "metadata": {
                "component": {
                  "type": "application", "bom-ref": "$primary",
                  "group": "org.example", "name": "$name", "version": "1.0"
                }
              },
              "components": [
                {
                  "type": "library", "bom-ref": "$lib", "group": "org.example", "name": "lib", "version": "1.0",
                  "hashes": [ { "alg": "SHA-256", "content": "$hash" } ],
                  "licenses": [ { "license": { "id": "$license", "acknowledgement": "concluded" } } ]
                }$sdkComponent
              ],
              "dependencies": [
                { "ref": "$primary", "dependsOn": [ "$lib"$sdkTarget ] },
                { "ref": "$lib", "dependsOn": [] }$sdkEntry
              ],
              "compositions": [
                { "aggregate": "complete", "dependencies": [ "$primary", "$lib" ] }$sdkGap
              ]
            }
        """.trimIndent()
    }

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

    private fun output() = projectDir.resolve("build/sbom/mergeProduct/product.cdx.json")

    /**
     * The start of a merge failure. Gradle names canonical paths, and the temporary directory may be reached through
     * a symbolic link.
     */
    private fun failurePrefix() = "Cannot merge the SBOMs into '${output().canonicalPath}': "

    // --- Output ---

    @Test
    fun `accepts a product name that is no file name when the output file is set`() {
        for (name in listOf("a/b", " ")) {
            setup(
                """
                productName.set("$name")
                outputFile.set(layout.buildDirectory.file("sbom/set.cdx.json"))
                """.trimIndent()
            )

            val result = gradleRunner().build()

            assertThat(result.task(":mergeProduct")?.outcome).describedAs(name).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(projectDir.resolve("build/sbom/set.cdx.json")).describedAs(name).exists()
        }
    }

    @Test
    fun `fails naming hostParts that are neither parts of the merge nor expected`() {
        setup("""hostParts.set(setOf("alpha", "gamma"))""")

        val result = gradleRunner().buildAndFail()

        assertThat(result.output).contains(
            failurePrefix() + "hostParts names parts that are neither in the merge nor in expectedParts: 'gamma'; " +
                "correct or remove the names"
        )
        assertThat(output()).doesNotExist()
    }

    @Test
    fun `fails naming licenseConflicts when two parts conclude different licenses`() {
        setup()
        projectDir.resolve("parts/beta.cdx.json").writeText(partSbom("beta", license = "MIT"))

        val result = gradleRunner().buildAndFail()

        assertThat(result.output)
            .contains(failurePrefix())
            .contains("has different concluded licenses in '")
            .contains("into licenseConflicts under 'org.example:lib'")
        assertThat(output()).doesNotExist()
    }

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
    fun `fails on a blank declared identified gap reason`() {
        setup("""declaredIdentifiedGapReasons.add(" ")""")

        val result = gradleRunner().buildAndFail()

        assertThat(result.output)
            .contains("Task ':mergeProduct' got a blank reason in declaredIdentifiedGapReasons: [ ]")
        assertThat(output()).doesNotExist()
    }

    @Test
    fun `fails on a declared identified gap that the product does not have`() {
        setup("""declaredIdentifiedGapReasons.add("the extensions run on the broker of another image")""")

        val result = gradleRunner().buildAndFail()

        assertThat(result.output).contains(
            failurePrefix() + "declaredIdentifiedGapReasons declares a gap of identified components that no host " +
                "part describes, but the product has none; remove the declaration"
        )
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
    fun `rejects a product name that is not a file name`() {
        setup("""productName.set("../x")""")

        val result = gradleRunner().buildAndFail()

        assertThat(result.output).contains(
            "The productName of task ':mergeProduct' must be a non-blank file name without '/' or '\\', " +
                "but is '../x'"
        )
        assertThat(projectDir.resolve("build/sbom/x.cdx.json")).doesNotExist()
    }

    @Test
    fun `warns about an expected host part that has no SBOM`() {
        setup(
            """
            hostParts.set(setOf("gamma"))
            expectedParts.set(setOf("alpha", "beta", "gamma"))
            """.trimIndent()
        )

        val result = gradleRunner().build()

        assertThat(result.output).contains(
            "The product SBOM 'product' of task ':mergeProduct' declares its dependency graph incomplete: " +
                "no SBOM for part 'gamma'"
        )
        assertThat(mapper.readTree(output())["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
    }

    @Test
    fun `warns about the gaps other than the identified one the build declares`() {
        setup(
            """
            declaredIdentifiedGapReasons.add("the extensions run on the broker of another image")
            expectedParts.set(setOf("alpha", "beta", "gamma"))
            """.trimIndent()
        )
        projectDir.resolve("parts/alpha.cdx.json").writeText(partSbom("alpha", identifiesSdk = true))

        val result = gradleRunner().build()

        assertThat(result.output).contains(
            "The product SBOM 'product' of task ':mergeProduct' declares its dependency graph incomplete: " +
                "no SBOM for part 'gamma'"
        )
        assertThat(result.output).doesNotContain("no host part describes")
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
    fun `writes no warning about an identified gap the build declares`() {
        setup("""declaredIdentifiedGapReasons.add("the extensions run on the broker of another image")""")
        projectDir.resolve("parts/alpha.cdx.json").writeText(partSbom("alpha", identifiesSdk = true))

        val result = gradleRunner().build()

        assertThat(result.output).doesNotContain("declares its dependency graph incomplete")
        val compositions = mapper.readTree(output())["compositions"]
        assertThat(compositions.map { it["aggregate"].asText() }).containsExactly("complete", "incomplete")
        assertThat(compositions[1]["dependencies"].map { it.asText() })
            .containsExactly("pkg:maven/org.example/sdk@1.0?type=jar")
    }

    @Test
    fun `writes the creator of hivemqLicense as manufacturer and the origin remote as vcs reference`() {
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
    fun `writes the license that licenseConflicts gives where two parts conclude different ones`() {
        setup("""licenseConflicts.put("org.example:lib", "MIT")""")
        projectDir.resolve("parts/beta.cdx.json").writeText(partSbom("beta", license = "MIT"))

        gradleRunner().build()

        val lib = mapper.readTree(output())["components"].first { it["name"].asText() == "lib" }
        assertThat(lib["licenses"].map { it["license"]["id"].asText() }).containsExactly("MIT")
    }

    @Test
    fun `writes the product SBOM with the conventions of hivemqLicense`() {
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
    fun `writes two products of the same name into the directories of their tasks`() {
        setup()
        projectDir.resolve("build.gradle.kts").appendText(
            """

            tasks.register<MergeSbomsTask>("mergeImage") {
                productName.set("product")
                productVersion.set("1.0.0")
                sboms.from(fileTree("parts"))
            }
            """.trimIndent()
        )

        val result = gradleRunner().withArguments("mergeProduct", "mergeImage").build()

        assertThat(result.task(":mergeImage")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(output()).exists()
        assertThat(projectDir.resolve("build/sbom/mergeImage/product.cdx.json")).exists()
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
