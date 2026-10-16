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

class UpdateThirdPartyLicensesTaskTest {

    @TempDir
    lateinit var projectDir: File

    private fun setup(buildExtra: String = "") {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "test-project"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            $buildExtra
            """.trimIndent()
        )
    }

    private fun writeBom(json: String) {
        val bomDir = projectDir.resolve("build/reports/cyclonedx")
        bomDir.mkdirs()
        bomDir.resolve("bom.json").writeText(json)
    }

    private fun overrideDependencyLicense(reportDirectory: String = """layout.buildDirectory.dir("tmp/third-party-licenses")""") {
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set($reportDirectory)
            }
            tasks.named<com.hivemq.tools.license.gradle.UpdateThirdPartyLicensesTask>("updateThirdPartyLicenses") {
                dependencyLicense.set(file("build/reports/cyclonedx/bom.json"))
            }
            """.trimIndent()
        )
    }

    private fun gradleRunner() = GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("updateThirdPartyLicenses")
        .withPluginClasspath()

    // --- Plaintext output format ---

    @Test
    fun `plaintext output contains header with project name`() {
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
        overrideDependencyLicense()

        gradleRunner().build()

        val plaintext = projectDir.resolve("build/tmp/third-party-licenses/licenses").readText()
        assertThat(plaintext)
            .startsWith("Third Party Licenses")
            .contains("==============================")
            .contains("test-project uses the following third party libraries")
            .contains("Module")
            .contains("Version")
            .contains("License ID")
            .contains("License URL")
    }

    @Test
    fun `plaintext output contains formatted dependency line`() {
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
        overrideDependencyLicense()

        gradleRunner().build()

        val plaintext = projectDir.resolve("build/tmp/third-party-licenses/licenses").readText()
        assertThat(plaintext).contains(
            " com.google.guava:guava                                                     | 32.1.3-jre                                | Apache-2.0    | https://spdx.org/licenses/Apache-2.0.html\n"
        )
    }

    @Test
    fun `plaintext output lists every license of a dependency under several licenses at once`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "dual-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "expression": "MIT AND ISC" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        overrideDependencyLicense()

        gradleRunner().build()

        val plaintext = projectDir.resolve("build/tmp/third-party-licenses/licenses").readText()
        assertThat(plaintext).contains(
            " com.example:dual-lib                                                       | 1.0.0                                     | MIT AND ISC   | https://spdx.org/licenses/MIT.html, https://spdx.org/licenses/ISC.html\n"
        )
    }

    @Test
    fun `plaintext output leaves out the URL of a license that has none`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "free-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "expression": "MIT AND Public Domain" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        overrideDependencyLicense()

        gradleRunner().build()

        val plaintext = projectDir.resolve("build/tmp/third-party-licenses/licenses").readText()
        assertThat(plaintext).contains(
            " com.example:free-lib                                                       | 1.0.0                                     | MIT AND Public Domain | https://spdx.org/licenses/MIT.html\n"
        )
    }

    @Test
    fun `plaintext output contains footer with email`() {
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
        overrideDependencyLicense()

        gradleRunner().build()

        val plaintext = projectDir.resolve("build/tmp/third-party-licenses/licenses").readText()
        assertThat(plaintext).contains("legal@hivemq.com")
    }

    @Test
    fun `plaintext output sorts dependencies alphabetically`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "org.slf4j",
                  "name": "slf4j-api",
                  "version": "2.0.9",
                  "licenses": [
                    { "license": { "id": "MIT" } }
                  ]
                },
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
        overrideDependencyLicense()

        gradleRunner().build()

        val plaintext = projectDir.resolve("build/tmp/third-party-licenses/licenses").readText()
        val guavaIndex = plaintext.indexOf("com.google.guava:guava")
        val slf4jIndex = plaintext.indexOf("org.slf4j:slf4j-api")
        assertThat(guavaIndex).isLessThan(slf4jIndex)
    }

    // --- HTML output format ---

    @Test
    fun `html output contains table structure`() {
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
        overrideDependencyLicense()

        gradleRunner().build()

        val html = projectDir.resolve("build/tmp/third-party-licenses/licenses.html").readText()
        assertThat(html)
            .contains("<head>")
            .contains("<title>Third Party Licences</title>")
            .contains("<table>")
            .contains("<th>Module</th>")
            .contains("<th>Version</th>")
            .contains("<th>License ID</th>")
            .contains("<th>License URL</th>")
            .contains("</table>")
            .contains("</body>")
    }

    @Test
    fun `html output contains dependency row with link`() {
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
        overrideDependencyLicense()

        gradleRunner().build()

        val html = projectDir.resolve("build/tmp/third-party-licenses/licenses.html").readText()
        assertThat(html).contains(
            """
            |    <tr>
            |        <td>com.google.guava:guava</td>
            |        <td>32.1.3-jre</td>
            |        <td>Apache-2.0</td>
            |        <td>
            |            <a href="https://spdx.org/licenses/Apache-2.0.html">https://spdx.org/licenses/Apache-2.0.html</a>
            |        </td>
            |    </tr>
            |
            """.trimMargin()
        )
    }

    @Test
    fun `html output lists every license of a dependency under several licenses at once`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "dual-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "expression": "MIT AND ISC" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        overrideDependencyLicense()

        gradleRunner().build()

        val html = projectDir.resolve("build/tmp/third-party-licenses/licenses.html").readText()
        assertThat(html).contains(
            """
            |    <tr>
            |        <td>com.example:dual-lib</td>
            |        <td>1.0.0</td>
            |        <td>MIT AND ISC</td>
            |        <td>
            |            <a href="https://spdx.org/licenses/MIT.html">https://spdx.org/licenses/MIT.html</a><br>
            |            <a href="https://spdx.org/licenses/ISC.html">https://spdx.org/licenses/ISC.html</a>
            |        </td>
            |    </tr>
            |
            """.trimMargin()
        )
    }

    @Test
    fun `html output leaves out the link of a license that has none`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "free-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "expression": "MIT AND Public Domain" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        overrideDependencyLicense()

        gradleRunner().build()

        val html = projectDir.resolve("build/tmp/third-party-licenses/licenses.html").readText()
        assertThat(html).contains(
            """
            |        <td>MIT AND Public Domain</td>
            |        <td>
            |            <a href="https://spdx.org/licenses/MIT.html">https://spdx.org/licenses/MIT.html</a>
            |        </td>
            """.trimMargin()
        )
        assertThat(html).doesNotContain("""href=""""")
    }

    @Test
    fun `html output has an empty link cell for a license that has no URL`() {
        setup()
        writeBom(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "free-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "Public Domain" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        overrideDependencyLicense()

        gradleRunner().build()

        val html = projectDir.resolve("build/tmp/third-party-licenses/licenses.html").readText()
        assertThat(html).contains(
            """
            |    <tr>
            |        <td>com.example:free-lib</td>
            |        <td>1.0.0</td>
            |        <td>Public Domain</td>
            |        <td>
            |        </td>
            |    </tr>
            |
            """.trimMargin()
        )
    }

    @Test
    fun `html output contains project name`() {
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
        overrideDependencyLicense()

        gradleRunner().build()

        val html = projectDir.resolve("build/tmp/third-party-licenses/licenses.html").readText()
        assertThat(html).contains("test-project uses the following third party libraries")
    }

    @Test
    fun `html output contains email footer`() {
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
        overrideDependencyLicense()

        gradleRunner().build()

        val html = projectDir.resolve("build/tmp/third-party-licenses/licenses.html").readText()
        assertThat(html).contains("""<a href="mailto:legal@hivemq.com">legal@hivemq.com</a>""")
    }

    // --- Custom project name ---

    @Test
    fun `uses custom project name in output`() {
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
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                projectName.set("My Custom Product")
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            }
            tasks.named<com.hivemq.tools.license.gradle.UpdateThirdPartyLicensesTask>("updateThirdPartyLicenses") {
                dependencyLicense.set(file("build/reports/cyclonedx/bom.json"))
            }
            """.trimIndent()
        )

        gradleRunner().build()

        val plaintext = projectDir.resolve("build/tmp/third-party-licenses/licenses").readText()
        assertThat(plaintext).contains("My Custom Product uses the following third party libraries")
        val html = projectDir.resolve("build/tmp/third-party-licenses/licenses.html").readText()
        assertThat(html).contains("My Custom Product uses the following third party libraries")
    }

    // --- Empty BOM ---

    @Test
    fun `generates reports with header and footer for BOM with no dependencies`() {
        setup()
        writeBom("""{ "components": [] }""")
        overrideDependencyLicense()

        val result = gradleRunner().build()

        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val plaintext = projectDir.resolve("build/tmp/third-party-licenses/licenses").readText()
        assertThat(plaintext)
            .contains("Third Party Licenses")
            .contains("legal@hivemq.com")
            .doesNotContain("com.google.guava")
    }

    // --- Re-run overwrites previous output ---

    @Test
    fun `re-running task replaces previous output files`() {
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
        overrideDependencyLicense()

        gradleRunner().build()

        // Update BOM with a different dependency
        writeBom(
            """
            {
              "components": [
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

        gradleRunner().build()

        val plaintext = projectDir.resolve("build/tmp/third-party-licenses/licenses").readText()
        assertThat(plaintext)
            .doesNotContain("com.google.guava:guava")
            .contains("org.slf4j:slf4j-api")
    }

    // --- Multiple license types ---

    @Test
    fun `generates correct output for multiple license types`() {
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
                },
                {
                  "group": "com.example",
                  "name": "dual-license-lib",
                  "version": "1.0.0",
                  "licenses": [
                    { "license": { "id": "EPL-2.0" } },
                    { "license": { "id": "Apache-2.0" } }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        overrideDependencyLicense()

        gradleRunner().build()

        val plaintext = projectDir.resolve("build/tmp/third-party-licenses/licenses").readText()
        assertThat(plaintext)
            .contains("com.google.guava:guava")
            .contains("org.slf4j:slf4j-api")
            .contains("com.example:dual-license-lib")
        // dual-license should prefer Apache-2.0
        val lines = plaintext.lines()
        val dualLicenseLine = lines.find { it.contains("com.example:dual-license-lib") }!!
        assertThat(dualLicenseLine).contains("Apache-2.0")
    }

    // --- Task fails with unapproved license ---

    @Test
    fun `task fails when dependency has unapproved license`() {
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
        overrideDependencyLicense()

        val result = gradleRunner().buildAndFail()

        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.FAILED)
    }

    @Test
    fun `keeps the existing reports when a license cannot be resolved`() {
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
        // checked-in reports live outside the build directory, where Gradle does not clean stale outputs
        overrideDependencyLicense(reportDirectory = """layout.projectDirectory.dir("third-party-licenses")""")
        val outputDir = projectDir.resolve("third-party-licenses")
        outputDir.mkdirs()
        outputDir.resolve("licenses").writeText("previous plaintext report")
        outputDir.resolve("licenses.html").writeText("previous html report")

        val result = gradleRunner().buildAndFail()

        assertThat(result.task(":updateThirdPartyLicenses")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(outputDir.resolve("licenses")).hasContent("previous plaintext report")
        assertThat(outputDir.resolve("licenses.html")).hasContent("previous html report")
    }

    // --- Custom ignored prefixes ---

    @Test
    fun `respects custom ignoredGroupPrefixes`() {
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
                },
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
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                ignoredGroupPrefixes.set(setOf("org.internal"))
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            }
            tasks.named<com.hivemq.tools.license.gradle.UpdateThirdPartyLicensesTask>("updateThirdPartyLicenses") {
                dependencyLicense.set(file("build/reports/cyclonedx/bom.json"))
            }
            """.trimIndent()
        )

        gradleRunner().build()

        val plaintext = projectDir.resolve("build/tmp/third-party-licenses/licenses").readText()
        assertThat(plaintext)
            .doesNotContain("org.internal:internal-lib")
            .contains("com.google.guava:guava")
    }
}
