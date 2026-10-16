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

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Reads the CycloneDX `bom.json` file created by the `cyclonedxDirectBom` task and creates `licenses` and
 * `licenses.html` files in the configured [outputDirectory].
 */
@CacheableTask
abstract class UpdateThirdPartyLicensesTask : LicenseResolvingTask() {

    @get:Input
    abstract val outputFileNamePrefix: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    protected fun run() {
        val productName = projectName.get()
        val prefix = outputFileNamePrefix.get()
        val resultPlaintextFile = outputDirectory.get().asFile.resolve("${prefix}licenses")
        val resultHtmlFile = outputDirectory.get().asFile.resolve("${prefix}licenses.html")

        check(productName.isNotBlank()) { "Project name is blank" }
        // resolved before the previous reports go, so a failure leaves them in place
        val entries = resolveLicenses()
        if (resultPlaintextFile.exists()) {
            check(resultPlaintextFile.delete()) { "Could not delete file '$resultPlaintextFile'" }
        }
        if (resultHtmlFile.exists()) {
            check(resultHtmlFile.delete()) { "Could not delete file '$resultHtmlFile'" }
        }

        val licensePlaintext = StringBuilder()
        val licenseHtml = StringBuilder()
        licensePlaintext.addHeaderPlaintext(productName)
        licenseHtml.addHeaderHtml(productName)
        for ((coordinates, licenses) in entries.values) {
            licensePlaintext.addLinePlaintext(coordinates, licenses)
            licenseHtml.addLineHtml(coordinates, licenses)
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

    // a dependency under several licenses at once is listed as "MIT AND ISC", with one URL per license that has one
    private fun StringBuilder.addLinePlaintext(coordinates: Coordinates, licenses: List<KnownLicense>) =
        //@formatter:off
        append(" ${"%-74s".format(coordinates.moduleId)} | ${"%-41s".format(coordinates.version)} | ${"%-13s".format(licenses.joinToString(" AND ") { it.id })} | ${licenses.urls().joinToString(", ")}\n")
        //@formatter:on

    private fun List<KnownLicense>.urls() = map { it.url }.filter { it.isNotBlank() }

    // every link on its own line, and a license without a URL leaves the cell empty
    private fun StringBuilder.addLineHtml(coordinates: Coordinates, licenses: List<KnownLicense>) {
        val links = licenses.urls().map { """<a href="$it">$it</a>""" }
        val lines = listOf(
            "    <tr>",
            "        <td>${coordinates.moduleId}</td>",
            "        <td>${coordinates.version}</td>",
            "        <td>${licenses.joinToString(" AND ") { it.id }}</td>",
            "        <td>",
        ) + links.mapIndexed { index, link ->
            "            $link" + if (index < links.lastIndex) "<br>" else ""
        } + listOf(
            "        </td>",
            "    </tr>",
        )
        append(lines.joinToString("\n", postfix = "\n"))
    }

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
