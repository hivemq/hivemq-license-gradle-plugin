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

class CheckApprovedLicensesTaskTest {

    @TempDir
    lateinit var projectDir: File

    private fun setup() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "test-project"""")
    }

    private fun writeBom(json: String) {
        val bomDir = projectDir.resolve("build/reports/cyclonedx")
        bomDir.mkdirs()
        bomDir.resolve("bom.json").writeText(json)
    }

    private fun writeBuildFile(extra: String = "") {
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            tasks.named<com.hivemq.tools.license.gradle.CheckApprovedLicensesTask>("checkApprovedLicenses") {
                dependencyLicense.set(file("build/reports/cyclonedx/bom.json"))
            }
            $extra
            """.trimIndent()
        )
    }

    private fun gradleRunner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(*args)
        .withPluginClasspath()

    @Test
    fun `succeeds with single approved dependency`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "com.google.guava",
                  "name": "guava",
                  "version": "32.1.3-jre",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        writeBuildFile()

        val result = gradleRunner("checkApprovedLicenses").build()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("1 dependencies have approved licenses")
    }

    @Test
    fun `succeeds with multiple approved dependencies`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "com.google.guava",
                  "name": "guava",
                  "version": "32.1.3-jre",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                },
                {
                  "group": "org.slf4j",
                  "name": "slf4j-api",
                  "version": "2.0.9",
                  "licenses": [
                    { "license": { "id": "MIT" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        writeBuildFile()

        val result = gradleRunner("checkApprovedLicenses").build()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("2 dependencies have approved licenses")
    }

    @Test
    fun `succeeds with empty BOM`() {
        setup()
        writeBom("""{ "components": [] }""")
        writeBuildFile()

        val result = gradleRunner("checkApprovedLicenses").build()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("0 dependencies have approved licenses")
    }

    @Test
    fun `fails with unapproved license`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "proprietary-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "name": "Some Proprietary License" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        writeBuildFile()

        val result = gradleRunner("checkApprovedLicenses").buildAndFail()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains("License not on the approved list")
    }

    @Test
    fun `fails with no licenses`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "no-license-lib",
                  "version": "1.0.0",
                  "licenses": []
                }
              ]
            }
            """.trimIndent()
        )
        writeBuildFile()

        val result = gradleRunner("checkApprovedLicenses").buildAndFail()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains("No license found")
    }

    @Test
    fun `ignores com_hivemq dependencies by default`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-extension-sdk",
                  "version": "4.0.0",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        writeBuildFile()

        val result = gradleRunner("checkApprovedLicenses").build()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("0 dependencies have approved licenses")
    }

    @Test
    fun `includes allowed artifact from ignored group`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "com.hivemq",
                  "name": "hivemq-mqtt-client",
                  "version": "1.3.0",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        writeBuildFile()

        val result = gradleRunner("checkApprovedLicenses").build()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("1 dependencies have approved licenses")
    }

    @Test
    fun `uses custom ignored group prefixes`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "org.internal",
                  "name": "internal-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                ignoredGroupPrefixes.set(setOf("org.internal"))
            }
            tasks.named<com.hivemq.tools.license.gradle.CheckApprovedLicensesTask>("checkApprovedLicenses") {
                dependencyLicense.set(file("build/reports/cyclonedx/bom.json"))
            }
            """.trimIndent()
        )

        val result = gradleRunner("checkApprovedLicenses").build()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("0 dependencies have approved licenses")
    }

    @Test
    fun `uses custom allowed artifacts`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "org.internal",
                  "name": "public-api",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                ignoredGroupPrefixes.set(setOf("org.internal"))
                allowedArtifacts.set(setOf("org.internal:public-api"))
            }
            tasks.named<com.hivemq.tools.license.gradle.CheckApprovedLicensesTask>("checkApprovedLicenses") {
                dependencyLicense.set(file("build/reports/cyclonedx/bom.json"))
            }
            """.trimIndent()
        )

        val result = gradleRunner("checkApprovedLicenses").build()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("1 dependencies have approved licenses")
    }

    @Test
    fun `succeeds with dual-licensed dependency`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "javax.annotation",
                  "name": "javax.annotation-api",
                  "version": "1.3.2",
                  "licenses": [
                    { "license": { "id": "EPL-2.0" } },
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        writeBuildFile()

        val result = gradleRunner("checkApprovedLicenses").build()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("1 dependencies have approved licenses")
    }

    @Test
    fun `succeeds with name-based license matching`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "some-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "name": "The Apache Software License, Version 2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        writeBuildFile()

        val result = gradleRunner("checkApprovedLicenses").build()

        assertThat(result.task(":checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun `does not produce output files`() {
        setup()
        writeBom("""{ "components": [] }""")
        writeBuildFile()

        gradleRunner("checkApprovedLicenses").build()

        val outputDir = projectDir.resolve("build/tmp/third-party-licenses")
        if (outputDir.exists()) {
            assertThat(outputDir.listFiles()).isEmpty()
        }
    }
}
