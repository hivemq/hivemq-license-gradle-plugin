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
            import com.hivemq.tools.license.gradle.sbom.GenerateSbomTask

            plugins {
                id("com.hivemq.tools.license")
            }
            tasks.register<GenerateSbomTask>("sbomFromFile") {
                // an input of every license task, unused by the SBOM
                projectName.set("app")
                dependencyLicense.set(layout.projectDirectory.file("bom.json"))
                timestamp.set("2026-01-02T03:04:05Z")
                creatorName.set("HiveMQ GmbH")
                creator.set("https://www.hivemq.com/")
                artifactFileName.set("app-1.0.0.jar")
                componentFileNames.set(mapOf("org.example:lib:1.0" to "lib-1.0.jar"))
                ignoredGroupPrefixes.set(setOf("com.hivemq"))
                allowedArtifacts.set(emptySet())
                overriddenLicenses.set(mapOf("org.example:lib" to "MIT"))
                outputFile.set(layout.buildDirectory.file("sbom/app.cdx.json"))
            }
            """.trimIndent()
        )
    }

    /** Gives the BOM's component a license that is not approved, and drops the override that would replace it. */
    private fun useUnapprovedLicense() {
        projectDir.resolve("bom.json").writeText(
            projectDir.resolve("bom.json").readText().replace("Apache-2.0", "Proprietary-9.9")
        )
        projectDir.resolve("build.gradle.kts").writeText(
            projectDir.resolve("build.gradle.kts").readText()
                .replace("""mapOf("org.example:lib" to "MIT")""", "emptyMap()")
        )
    }

    /**
     * Adds `com.hivemq:own`, an unlisted own component, to the BOM: with a license nobody approved, with CycloneDX's
     * marker for a POM it could not read, or with neither.
     */
    private fun addOwnComponent(licensed: Boolean, unresolved: Boolean = false) {
        val licenses = if (licensed) """, "licenses": [ { "license": { "id": "Proprietary-9.9" } } ]""" else ""
        val properties = if (unresolved) {
            """, "properties": [ { "name": "cdx:maven:package:metadata-unresolved", "value": "pom-unresolved" } ]"""
        } else {
            ""
        }
        val own = """{ "group": "com.hivemq", "name": "own", "version": "1.0", """ +
            """"purl": "pkg:maven/com.hivemq/own@1.0?type=jar"$licenses$properties },"""
        projectDir.resolve("bom.json").writeText(
            projectDir.resolve("bom.json").readText().replace(""""components": [""", """"components": [ $own""")
        )
    }

    // --- Output ---

    @Test
    fun `drops the other version of an identified module that only a provided module leads to`() {
        setup()
        val app = "pkg:maven/org.example/app@1.0"
        val lib = "pkg:maven/org.example/lib@1.0?type=jar"
        val provided1 = "pkg:maven/org.example/provided@1.0?type=jar"
        val provided2 = "pkg:maven/org.example/provided@2.0?type=jar"
        val other = "pkg:maven/org.example/other@1.0?type=jar"
        projectDir.resolve("bom.json").writeText(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": { "component": { "bom-ref": "$app", "name": "app" } },
              "components": [
                { "bom-ref": "$lib", "group": "org.example", "name": "lib", "version": "1.0" },
                { "bom-ref": "$provided1", "group": "org.example", "name": "provided", "version": "1.0" },
                { "bom-ref": "$provided2", "group": "org.example", "name": "provided", "version": "2.0" },
                { "bom-ref": "$other", "group": "org.example", "name": "other", "version": "1.0" }
              ],
              "dependencies": [
                { "ref": "$app", "dependsOn": [ "$lib", "$provided1", "$other" ] },
                { "ref": "$other", "dependsOn": [ "$provided2" ] },
                { "ref": "$provided1", "dependsOn": [] },
                { "ref": "$provided2", "dependsOn": [] }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("org.example:provided", "org.example:other"))
            }
            """.trimIndent()
        )

        gradleRunner().build()

        // identification is per component, so version 2.0, reached only through the other provided module, goes
        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        assertThat(sbom["components"].map { it["bom-ref"].asText() }).containsExactlyInAnyOrder(lib, other, provided1)
        assertThat(sbom["dependencies"].first { it["ref"].asText() == other }["dependsOn"]).isEmpty()
    }

    @Test
    fun `fails for an own component that declares a license without being listed, writing no SBOM`() {
        setup()
        addOwnComponent(licensed = true)

        val result = gradleRunner().buildAndFail()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains(
            "Own module 'com.hivemq:own' (ignored group prefix 'com.hivemq') declares a license but is not in " +
                "allowedArtifacts: a public module has to be listed there, an internal one declares no license, and " +
                "a third-party group does not belong in ignoredGroupPrefixes"
        )
        assertThat(projectDir.resolve("build/sbom/app.cdx.json")).doesNotExist()
    }

    @Test
    fun `fails for an unlisted own component with incomplete Maven metadata, writing no SBOM`() {
        setup()
        addOwnComponent(licensed = false, unresolved = true)

        val result = gradleRunner().buildAndFail()

        // without the POM nobody knows whether the module declares a license, so it cannot pass as internal
        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains(
            "Own module 'com.hivemq:own' (ignored group prefix 'com.hivemq') is not in allowedArtifacts and its " +
                "Maven metadata is incomplete (pom-unresolved), so it may be a public module: list it there, or fix " +
                "its POM or the repository it comes from"
        )
        assertThat(projectDir.resolve("build/sbom/app.cdx.json")).doesNotExist()
    }

    @Test
    fun `fails like checkApprovedLicenses on a license that is not approved`() {
        setup()
        useUnapprovedLicense()

        val result = gradleRunner().buildAndFail()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.FAILED)
        // the message of checkApprovedLicenses, not wrapped like the message of a BOM the SBOM cannot be written from
        assertThat(result.output)
            .contains("1 license problem found in '${projectDir.resolve("bom.json").canonicalPath}':")
            .contains("License not on the approved list for 'org.example:lib:1.0': Proprietary-9.9")
            .doesNotContain("Cannot write an SBOM")
        assertThat(projectDir.resolve("build/sbom/app.cdx.json")).doesNotExist()
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
    fun `fails naming the BOM file when it has no dependency graph and modules are provided`() {
        setup()
        val provided = """{ "group": "org.example", "name": "provided", "version": "1.0", """ +
            """"purl": "pkg:maven/org.example/provided@1.0?type=jar" },"""
        projectDir.resolve("bom.json").writeText(
            projectDir.resolve("bom.json").readText()
                .replace(""""components": [""", """"components": [ $provided""")
                .replace(Regex(""",\s*"dependencies": \[.*]"""), "")
        )
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("org.example:provided"))
            }
            """.trimIndent()
        )

        val result = gradleRunner().buildAndFail()

        // without the graph the provided components could only be dropped or kept wholesale, both wrong
        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output)
            .contains("Cannot write an SBOM from '")
            .contains("bom.json': The BOM has no dependency graph, needed to identify the modules the host provides")
    }

    @Test
    fun `fails naming the BOM file when its dependency graph is empty and modules are provided`() {
        setup()
        val provided = """{ "group": "org.example", "name": "provided", "version": "1.0", """ +
            """"purl": "pkg:maven/org.example/provided@1.0?type=jar" },"""
        projectDir.resolve("bom.json").writeText(
            projectDir.resolve("bom.json").readText()
                .replace(""""components": [""", """"components": [ $provided""")
                .replace(Regex(""""dependencies": \[.*]"""), """"dependencies": []""")
        )
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("org.example:provided"))
            }
            """.trimIndent()
        )

        val result = gradleRunner().buildAndFail()

        // an empty graph says as little as a missing one, and would silently drop every provided component
        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output)
            .contains("Cannot write an SBOM from '")
            .contains("bom.json': The BOM has no dependency graph, needed to identify the modules the host provides")
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
    fun `fails naming the BOM file when the creator is neither an email address nor an http URL`() {
        setup()
        projectDir.resolve("build.gradle.kts").writeText(
            projectDir.resolve("build.gradle.kts").readText()
                .replace("""creator.set("https://www.hivemq.com/")""", """creator.set("HiveMQ Security Team")""")
        )

        val result = gradleRunner().buildAndFail()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output)
            .contains("Cannot write an SBOM from '")
            .contains(
                "bom.json': hivemqLicense.sbomCreator must be an email address or an http(s) URL, but is " +
                    "'HiveMQ Security Team'"
            )
        assertThat(projectDir.resolve("build/sbom/app.cdx.json")).doesNotExist()
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
    fun `fails naming the BOM file when the metadata is not an object`() {
        setup()
        projectDir.resolve("bom.json").writeText("""{ "specVersion": "1.6", "metadata": [] }""")

        val result = gradleRunner().buildAndFail()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output)
            .contains("Cannot write an SBOM from '")
            .contains("bom.json': The BOM's metadata is not a JSON object")
    }

    @Test
    fun `fails naming the task when the timestamp is not an instant`() {
        setup()
        projectDir.resolve("build.gradle.kts").writeText(
            projectDir.resolve("build.gradle.kts").readText().replace("2026-01-02T03:04:05Z", "yesterday")
        )

        val result = gradleRunner().buildAndFail()

        assertThat(result.output).contains(
            "The timestamp of task ':sbomFromFile' must be an ISO 8601 instant such as 2026-01-02T03:04:05Z, but is " +
                "'yesterday'"
        )
    }

    @Test
    fun `gives an identified component nested in a provided one no file name`() {
        setup()
        val app = "pkg:maven/org.example/app@1.0"
        val lib = "pkg:maven/org.example/lib@1.0?type=jar"
        val bundle = "pkg:maven/org.example/bundle@1.0?type=jar"
        val inner = "pkg:maven/org.example/inner@1.0?type=jar"
        // inner is not a provided module itself, only part of one, so the module filter alone would not catch its file
        projectDir.resolve("bom.json").writeText(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": { "component": { "bom-ref": "$app", "name": "app" } },
              "components": [
                { "bom-ref": "$lib", "group": "org.example", "name": "lib", "version": "1.0" },
                { "bom-ref": "$bundle", "group": "org.example", "name": "bundle", "version": "1.0", "components": [
                  { "bom-ref": "$inner", "group": "org.example", "name": "inner", "version": "1.0" }
                ] }
              ],
              "dependencies": [
                { "ref": "$app", "dependsOn": [ "$lib" ] },
                { "ref": "$lib", "dependsOn": [ "$inner" ] },
                { "ref": "$inner", "dependsOn": [] }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            projectDir.resolve("build.gradle.kts").readText().replace(
                """componentFileNames.set(mapOf("org.example:lib:1.0" to "lib-1.0.jar"))""",
                """componentFileNames.set(mapOf("org.example:lib:1.0" to "lib-1.0.jar", """ +
                    """"org.example:inner:1.0" to "inner-1.0.jar"))""" + "\n    " +
                    """providedModules.set(setOf("org.example:bundle"))""",
            )
        )

        gradleRunner().build()

        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        assertThat(sbom["components"].map { it["bom-ref"].asText() }).containsExactly(lib, inner)
        val properties = { ref: String ->
            sbom["components"].first { it["bom-ref"].asText() == ref }.path("properties").map { it["name"].asText() }
        }
        assertThat(properties(inner)).doesNotContain("bsi:component:filename")
        assertThat(properties(lib)).contains("bsi:component:filename")
    }

    @Test
    fun `identifies a provided component nested in the top-level component when it depends on it`() {
        setup()
        val app = "pkg:maven/org.example/app@1.0"
        val lib = "pkg:maven/org.example/lib@1.0?type=jar"
        val api = "pkg:maven/org.example/provided-api@1.0?type=jar"
        projectDir.resolve("bom.json").writeText(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": { "component": { "bom-ref": "$app", "name": "app", "components": [
                { "bom-ref": "$api", "group": "org.example", "name": "provided-api", "version": "1.0" }
              ] } },
              "components": [
                { "bom-ref": "$lib", "group": "org.example", "name": "lib", "version": "1.0" }
              ],
              "dependencies": [
                { "ref": "$app", "dependsOn": [ "$lib", "$api" ] },
                { "ref": "$api", "dependsOn": [] }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("org.example:provided-api"))
            }
            """.trimIndent()
        )

        gradleRunner().build()

        // identified in its place: the top-level component depends on it directly
        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        assertThat(sbom["metadata"]["component"]["components"].map { it["bom-ref"].asText() }).containsExactly(api)
        assertThat(sbom["dependencies"].first { it["ref"].asText() == api }["dependsOn"]).isEmpty()
        assertThat(sbom["compositions"][1]["dependencies"].map { it.asText() }).containsExactly(api)
    }

    @Test
    fun `identifies what a hidden provided module depends on directly`() {
        setup()
        val app = "pkg:maven/org.example/app@1.0"
        val lib = "pkg:maven/org.example/lib@1.0?type=jar"
        val platform = "pkg:maven/com.hivemq/platform@1.0?type=jar"
        val otel = "pkg:maven/org.example/otel@1.0?type=jar"
        val context = "pkg:maven/org.example/otel-context@1.0?type=jar"
        // the platform module is own and declares no license, so it is hidden before the provided modules are filtered
        projectDir.resolve("bom.json").writeText(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": { "component": { "bom-ref": "$app", "name": "app" } },
              "components": [
                { "bom-ref": "$lib", "group": "org.example", "name": "lib", "version": "1.0" },
                { "bom-ref": "$platform", "group": "com.hivemq", "name": "platform", "version": "1.0" },
                { "bom-ref": "$otel", "group": "org.example", "name": "otel", "version": "1.0" },
                { "bom-ref": "$context", "group": "org.example", "name": "otel-context", "version": "1.0" }
              ],
              "dependencies": [
                { "ref": "$app", "dependsOn": [ "$lib", "$platform" ] },
                { "ref": "$platform", "dependsOn": [ "$otel" ] },
                { "ref": "$otel", "dependsOn": [ "$context" ] },
                { "ref": "$context", "dependsOn": [] }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("com.hivemq:platform", "org.example:otel", "org.example:otel-context"))
            }
            """.trimIndent()
        )

        gradleRunner().build()

        // the module directly below the hidden one takes its place as the identified component
        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        assertThat(sbom["components"].map { it["name"].asText() }).containsExactly("lib", "otel")
        assertThat(sbom["dependencies"].first { it["ref"].asText() == app }["dependsOn"].map { it.asText() })
            .containsExactly(lib, otel)
        assertThat(sbom["dependencies"].first { it["ref"].asText() == otel }["dependsOn"]).isEmpty()
    }

    @Test
    fun `identifies what is nested in a hidden provided module, in its place`() {
        setup()
        val app = "pkg:maven/org.example/app@1.0"
        val lib = "pkg:maven/org.example/lib@1.0?type=jar"
        val platform = "pkg:maven/com.hivemq/platform@1.0?type=jar"
        val inner = "pkg:maven/org.example/inner@1.0?type=jar"
        val api = "pkg:maven/org.example/api@1.0?type=jar"
        // the platform module is hidden and provided: what the BOM nests in it takes its place as the components the
        // top-level component depends on, and stays provided, so both are identified and ship nothing
        projectDir.resolve("bom.json").writeText(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": { "component": { "bom-ref": "$app", "name": "app" } },
              "components": [
                { "bom-ref": "$lib", "group": "org.example", "name": "lib", "version": "1.0" },
                { "bom-ref": "$platform", "group": "com.hivemq", "name": "platform", "version": "1.0", "components": [
                  { "bom-ref": "$inner", "group": "org.example", "name": "inner", "version": "1.0" },
                  { "bom-ref": "$api", "group": "org.example", "name": "api", "version": "1.0" }
                ] }
              ],
              "dependencies": [
                { "ref": "$app", "dependsOn": [ "$lib", "$platform" ] },
                { "ref": "$lib", "dependsOn": [ "$api" ] },
                { "ref": "$platform", "dependsOn": [] },
                { "ref": "$inner", "dependsOn": [] },
                { "ref": "$api", "dependsOn": [] }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("com.hivemq:platform"))
            }
            """.trimIndent()
        )

        gradleRunner().build()

        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        assertThat(sbom["components"].map { it["bom-ref"].asText() }).containsExactlyInAnyOrder(lib, inner, api)
        assertThat(sbom["dependencies"].first { it["ref"].asText() == app }["dependsOn"].map { it.asText() })
            .containsExactlyInAnyOrder(lib, inner, api)
        for (ref in listOf(inner, api)) {
            assertThat(sbom["dependencies"].first { it["ref"].asText() == ref }["dependsOn"]).isEmpty()
        }
        // the graph is complete for what ships; the identified components' dependencies are the host's to describe
        assertThat(sbom["compositions"].map { it["aggregate"].asText() }).containsExactly("complete", "incomplete")
        assertThat(sbom["compositions"][0]["dependencies"].map { it.asText() }).containsExactly(app, lib)
        assertThat(sbom["compositions"][1]["dependencies"].map { it.asText() }).containsExactly(api, inner)
    }

    @Test
    fun `keeps a provided component a shipped one depends on, without dependencies or file description`() {
        setup()
        // a BOM supplied by hand may already describe the provided module's file and dependencies
        val api = "pkg:maven/org.example/provided-api@1.0?type=jar"
        val lib = "pkg:maven/org.example/lib@1.0?type=jar"
        val provided = """{ "group": "org.example", "name": "provided-api", "version": "1.0", """ +
            """"bom-ref": "$api", "purl": "$api", "licenses": [ { "license": { "id": "MIT" } } ], """ +
            """"properties": [ { "name": "bsi:component:filename", "value": "provided-api-1.0.jar" } ] },"""
        val providedDependency = """{ "ref": "$api", "dependsOn": [ "$lib" ] },"""
        projectDir.resolve("bom.json").writeText(
            projectDir.resolve("bom.json").readText()
                .replace(""""components": [""", """"components": [ $provided""")
                .replace(""""dependencies": [""", """"dependencies": [ $providedDependency""")
                .replace(""""dependsOn": [] }""", """"dependsOn": [ "$api" ] }""")
        )
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("org.example:provided-api"))
            }
            """.trimIndent()
        )

        gradleRunner().build()

        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        val component = sbom["components"].first { it["name"].asText() == "provided-api" }
        assertThat(component.path("properties").map { it["name"].asText() }).doesNotContain("bsi:component:filename")
        assertThat(sbom["dependencies"].first { it["ref"].asText() == api }["dependsOn"]).isEmpty()
        // the shipped library keeps depending on it, which is what makes it the identified component
        assertThat(sbom["dependencies"].first { it["ref"].asText() == lib }["dependsOn"].map { it.asText() })
            .containsExactly(api)
        assertThat(sbom["components"].map { it["name"].asText() }).containsExactlyInAnyOrder("provided-api", "lib")
    }

    @Test
    fun `keeps an identified component nested in a left-out provided one, in the place of its parent`() {
        setup()
        val app = "pkg:maven/org.example/app@1.0"
        val lib = "pkg:maven/org.example/lib@1.0?type=jar"
        val bundle = "pkg:maven/org.example/bundle@1.0?type=jar"
        val api = "pkg:maven/org.example/api@1.0?type=jar"
        // the provided bundle carries the api inside it; nothing depends on the bundle, the shipped library on the api
        projectDir.resolve("bom.json").writeText(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": { "component": { "bom-ref": "$app", "name": "app" } },
              "components": [
                { "bom-ref": "$lib", "group": "org.example", "name": "lib", "version": "1.0" },
                { "bom-ref": "$bundle", "group": "org.example", "name": "bundle", "version": "1.0", "components": [
                  { "bom-ref": "$api", "group": "org.example", "name": "api", "version": "1.0" }
                ] }
              ],
              "dependencies": [
                { "ref": "$app", "dependsOn": [ "$lib" ] },
                { "ref": "$lib", "dependsOn": [ "$api" ] },
                { "ref": "$api", "dependsOn": [] }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("org.example:bundle", "org.example:api"))
            }
            """.trimIndent()
        )

        gradleRunner().build()

        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        assertThat(sbom["components"].map { it["bom-ref"].asText() }).containsExactly(lib, api)
        assertThat(sbom["dependencies"].first { it["ref"].asText() == lib }["dependsOn"].map { it.asText() })
            .containsExactly(api)
        assertThat(sbom["dependencies"].first { it["ref"].asText() == api }["dependsOn"]).isEmpty()
    }

    @Test
    fun `keeps an identified component nested in another identified one in its place`() {
        setup()
        val app = "pkg:maven/org.example/app@1.0"
        val lib = "pkg:maven/org.example/lib@1.0?type=jar"
        val bundle = "pkg:maven/org.example/bundle@1.0?type=jar"
        val api = "pkg:maven/org.example/api@1.0?type=jar"
        // the top-level component depends on the bundle, the shipped library on the api inside it
        projectDir.resolve("bom.json").writeText(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": { "component": { "bom-ref": "$app", "name": "app" } },
              "components": [
                { "bom-ref": "$lib", "group": "org.example", "name": "lib", "version": "1.0" },
                { "bom-ref": "$bundle", "group": "org.example", "name": "bundle", "version": "1.0", "components": [
                  { "bom-ref": "$api", "group": "org.example", "name": "api", "version": "1.0" }
                ] }
              ],
              "dependencies": [
                { "ref": "$app", "dependsOn": [ "$lib", "$bundle" ] },
                { "ref": "$lib", "dependsOn": [ "$api" ] },
                { "ref": "$bundle", "dependsOn": [] },
                { "ref": "$api", "dependsOn": [] }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("org.example:bundle", "org.example:api"))
            }
            """.trimIndent()
        )

        gradleRunner().build()

        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        assertThat(sbom["components"].map { it["bom-ref"].asText() }).containsExactly(lib, bundle)
        val nested = sbom["components"].first { it["bom-ref"].asText() == bundle }["components"]
        assertThat(nested.map { it["bom-ref"].asText() }).containsExactly(api)
        assertThat(sbom["dependencies"].first { it["ref"].asText() == lib }["dependsOn"].map { it.asText() })
            .containsExactly(api)
    }

    @Test
    fun `leaves an own component without a license out`() {
        setup()
        addOwnComponent(licensed = false)

        val result = gradleRunner().build()

        // internal, so neither checked nor written: the component is not in the SBOM
        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val components = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))["components"]
        assertThat(components.map { it["name"].asText() }).containsExactly("lib")
    }

    @Test
    fun `leaves out a hidden provided module that only a provided module depends on, with what is below it`() {
        setup()
        val app = "pkg:maven/org.example/app@1.0"
        val lib = "pkg:maven/org.example/lib@1.0?type=jar"
        val outer = "pkg:maven/org.example/outer@1.0?type=jar"
        val platform = "pkg:maven/com.hivemq/platform@1.0?type=jar"
        val otel = "pkg:maven/org.example/otel@1.0?type=jar"
        projectDir.resolve("bom.json").writeText(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": { "component": { "bom-ref": "$app", "name": "app" } },
              "components": [
                { "bom-ref": "$lib", "group": "org.example", "name": "lib", "version": "1.0" },
                { "bom-ref": "$outer", "group": "org.example", "name": "outer", "version": "1.0" },
                { "bom-ref": "$platform", "group": "com.hivemq", "name": "platform", "version": "1.0" },
                { "bom-ref": "$otel", "group": "org.example", "name": "otel", "version": "1.0" }
              ],
              "dependencies": [
                { "ref": "$app", "dependsOn": [ "$lib", "$outer" ] },
                { "ref": "$outer", "dependsOn": [ "$platform" ] },
                { "ref": "$platform", "dependsOn": [ "$otel" ] },
                { "ref": "$otel", "dependsOn": [] }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("org.example:outer", "com.hivemq:platform", "org.example:otel"))
            }
            """.trimIndent()
        )

        gradleRunner().build()

        // the hidden module hands otel to outer, which is identified and depends on nothing here, so otel goes too
        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        assertThat(sbom["components"].map { it["bom-ref"].asText() }).containsExactly(lib, outer)
        assertThat(sbom["dependencies"].first { it["ref"].asText() == outer }["dependsOn"]).isEmpty()
    }

    @Test
    fun `leaves out a provided component nested in an identified one, and its references`() {
        setup()
        val app = "pkg:maven/org.example/app@1.0"
        val lib = "pkg:maven/org.example/lib@1.0?type=jar"
        val bundle = "pkg:maven/org.example/bundle@1.0?type=jar"
        val inner = "pkg:maven/org.example/inner@1.0?type=jar"
        // the bundle is identified; what it carries is provided too, and nothing that stays depends on it
        projectDir.resolve("bom.json").writeText(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": { "component": { "bom-ref": "$app", "name": "app" } },
              "components": [
                { "bom-ref": "$lib", "group": "org.example", "name": "lib", "version": "1.0" },
                { "bom-ref": "$bundle", "group": "org.example", "name": "bundle", "version": "1.0", "components": [
                  { "bom-ref": "$inner", "group": "org.example", "name": "inner", "version": "1.0" }
                ] }
              ],
              "dependencies": [
                { "ref": "$app", "dependsOn": [ "$lib", "$bundle" ] },
                { "ref": "$lib", "dependsOn": [] },
                { "ref": "$bundle", "dependsOn": [ "$inner" ] },
                { "ref": "$inner", "dependsOn": [] }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("org.example:bundle"))
            }
            """.trimIndent()
        )

        gradleRunner().build()

        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        assertThat(sbom["components"].map { it["bom-ref"].asText() }).containsExactly(lib, bundle)
        assertThat(sbom["components"].first { it["bom-ref"].asText() == bundle }.has("components")).isFalse()
        assertThat(sbom["dependencies"].map { it["ref"].asText() }).containsExactly(app, lib, bundle)
        assertThat(sbom["dependencies"].first { it["ref"].asText() == bundle }["dependsOn"]).isEmpty()
    }

    @Test
    fun `leaves out a provided component nested in the top-level component when nothing depends on it`() {
        setup()
        val app = "pkg:maven/org.example/app@1.0"
        val lib = "pkg:maven/org.example/lib@1.0?type=jar"
        val api = "pkg:maven/org.example/provided-api@1.0?type=jar"
        projectDir.resolve("bom.json").writeText(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": { "component": { "bom-ref": "$app", "name": "app", "components": [
                { "bom-ref": "$api", "group": "org.example", "name": "provided-api", "version": "1.0" }
              ] } },
              "components": [
                { "bom-ref": "$lib", "group": "org.example", "name": "lib", "version": "1.0" }
              ],
              "dependencies": [
                { "ref": "$app", "dependsOn": [ "$lib" ] },
                { "ref": "$lib", "dependsOn": [] },
                { "ref": "$api", "dependsOn": [] }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("org.example:provided-api"))
            }
            """.trimIndent()
        )

        gradleRunner().build()

        // gone with its entry, and no empty list is left on the top-level component
        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        assertThat(sbom["metadata"]["component"].has("components")).isFalse()
        assertThat(sbom["dependencies"].map { it["ref"].asText() }).containsExactly(app, lib)
        assertThat(sbom["compositions"]).hasSize(1)
    }

    @Test
    fun `leaves out what lies below the first provided module on each path, and every reference to it`() {
        setup()
        val app = "pkg:maven/org.example/app@1.0"
        val lib = "pkg:maven/org.example/lib@1.0?type=jar"
        val provided = "pkg:maven/org.example/provided@1.0?type=jar"
        val core = "pkg:maven/org.example/provided-core@1.0?type=jar"
        projectDir.resolve("bom.json").writeText(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": { "component": { "bom-ref": "$app", "name": "app" } },
              "components": [
                { "bom-ref": "$lib", "group": "org.example", "name": "lib", "version": "1.0" },
                { "bom-ref": "$provided", "group": "org.example", "name": "provided", "version": "1.0" },
                { "bom-ref": "$core", "group": "org.example", "name": "provided-core", "version": "1.0" }
              ],
              "dependencies": [
                { "ref": "$app", "dependsOn": [ "$lib" ] },
                { "ref": "$lib", "dependsOn": [ "$provided" ] },
                { "ref": "$provided", "dependsOn": [ "$core" ] },
                { "ref": "$core", "dependsOn": [] }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("org.example:provided", "org.example:provided-core"))
            }
            """.trimIndent()
        )

        gradleRunner().build()

        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        assertThat(sbom["components"].map { it["name"].asText() }).containsExactly("lib", "provided")
        assertThat(sbom["dependencies"].map { it["ref"].asText() }).containsExactly(app, lib, provided)
        assertThat(sbom["dependencies"].flatMap { it["dependsOn"] }.map { it.asText() }).containsExactly(lib, provided)
    }

    @Test
    fun `writes an SBOM from a BOM without a dependency graph when none of the provided modules is in it`() {
        setup()
        projectDir.resolve("bom.json").writeText(
            projectDir.resolve("bom.json").readText().replace(Regex(""",\s*"dependencies": \[.*]"""), "")
        )
        // the project provides a module this BOM does not contain, so there is nothing to identify
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """
            tasks.named<GenerateSbomTask>("sbomFromFile") {
                providedModules.set(setOf("org.example:provided"))
            }
            """.trimIndent()
        )

        val result = gradleRunner().build()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        assertThat(sbom["components"].map { it["name"].asText() }).containsExactly("lib")
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
    fun `reruns when a declared incomplete reason changes`() {
        setup()
        gradleRunner().build()
        projectDir.resolve("build.gradle.kts").appendText(
            "\n" + """hivemqLicense { sbomIncompleteReasons.add("bundles a frontend") }"""
        )

        val result = gradleRunner().build()

        assertThat(result.task(":sbomFromFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val sbom = ObjectMapper().readTree(projectDir.resolve("build/sbom/app.cdx.json"))
        assertThat(sbom["compositions"][0]["aggregate"].asText()).isEqualTo("incomplete")
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
