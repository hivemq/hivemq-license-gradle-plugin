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
package com.hivemq.license.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Reads the CycloneDX `bom.json` file created by the `cyclonedxDirectBom` task and creates `licenses` and
 * `licenses.html` files in the configured [outputDirectory].
 */
abstract class UpdateThirdPartyLicensesTask : DefaultTask() {

    @get:Input
    abstract val projectName: Property<String>

    @get:InputFile
    abstract val dependencyLicense: RegularFileProperty

    @get:Input
    abstract val ignoredGroupPrefixes: SetProperty<String>

    @get:Input
    abstract val allowedArtifacts: SetProperty<String>

    @get:Input
    abstract val overriddenLicenses: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    protected fun run() {
        val productName = projectName.get()
        val resultPlaintextFile = outputDirectory.get().asFile.resolve("licenses")
        val resultHtmlFile = outputDirectory.get().asFile.resolve("licenses.html")

        check(productName.isNotBlank()) { "Project name is blank" }
        if (resultPlaintextFile.exists()) {
            check(resultPlaintextFile.delete()) { "Could not delete file '$resultPlaintextFile'" }
        }
        if (resultHtmlFile.exists()) {
            check(resultHtmlFile.delete()) { "Could not delete file '$resultHtmlFile'" }
        }

        val entries = LicenseResolver.resolveLicenses(
            dependencyLicenseFile = dependencyLicense.get().asFile.absoluteFile,
            ignoredGroupPrefixes = ignoredGroupPrefixes.get(),
            allowedArtifacts = allowedArtifacts.get(),
            overriddenLicenses = overriddenLicenses.get(),
        )

        val licensePlaintext = StringBuilder()
        val licenseHtml = StringBuilder()
        licensePlaintext.addHeaderPlaintext(productName)
        licenseHtml.addHeaderHtml(productName)
        for ((coordinates, chosenLicense) in entries.values) {
            licensePlaintext.addLinePlaintext(coordinates, chosenLicense)
            licenseHtml.addLineHtml(coordinates, chosenLicense)
        }
        licensePlaintext.addFooterPlaintext()
        licenseHtml.addFooterHtml()

        resultPlaintextFile.writeText(licensePlaintext.toString())
        resultHtmlFile.writeText(licenseHtml.toString())
    }

    private fun StringBuilder.addHeaderPlaintext(productName: String) = append(
        """
        Third Party Licenses
        ==============================

        $productName uses the following third party libraries:

         Module                                                                     | Version                                   | License ID    | License URL
        --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

        """.trimIndent()
    )

    private fun StringBuilder.addHeaderHtml(productName: String) = append(
        """
        <head>
            <title>Third Party Licences</title>
            <style>
                table, th, td {
                    border: 1px solid black;
                    border-collapse: collapse;
                    border-spacing: 0;
                }

                th, td {
                    padding: 5px;
                }
            </style>
        </head>

        <body>

        <h2>Third Party Licenses</h2>
        <p>$productName uses the following third party libraries</p>

        <table>
            <tbody>
            <tr>
                <th>Module</th>
                <th>Version</th>
                <th>License ID</th>
                <th>License URL</th>
            </tr>

        """.trimIndent()
    )

    private fun StringBuilder.addLinePlaintext(coordinates: Coordinates, license: KnownLicense) =
        //@formatter:off
        append(" ${"%-74s".format(coordinates.moduleId)} | ${"%-41s".format(coordinates.version)} | ${"%-13s".format(license.id)} | ${license.url}\n")
        //@formatter:on

    private fun StringBuilder.addLineHtml(coordinates: Coordinates, license: KnownLicense) = append(
        """
        |    <tr>
        |        <td>${coordinates.moduleId}</td>
        |        <td>${coordinates.version}</td>
        |        <td>${license.id}</td>
        |        <td>
        |            <a href="${license.url}">${license.url}</a>
        |        </td>
        |    </tr>
        |
        """.trimMargin()
    )

    private fun StringBuilder.addFooterPlaintext() = append(
        """
        --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

        The open source code of the libraries can be obtained by sending an email to legal@hivemq.com.

        """.trimIndent()
    )

    private fun StringBuilder.addFooterHtml() = append(
        """
            </tbody>
        </table>
        <p>The open source code of the libraries can be obtained by sending an email to <a href="mailto:legal@hivemq.com">legal@hivemq.com</a>.
        </p>
        </body>

        """.trimIndent()
    )
}
