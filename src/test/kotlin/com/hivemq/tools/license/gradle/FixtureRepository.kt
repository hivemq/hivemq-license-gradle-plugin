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

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes modules into a local Maven repository as jar and POM, `com.example` unless a group is given. */
internal object FixtureRepository {

    /** A `<license>` element of a POM: its `<name>` and, when given, its `<url>`. */
    data class PomLicense(val name: String, val url: String? = null)

    /** `com.example:no-license-lib:1.0.0`, whose missing license makes checkApprovedLicenses fail for a dependant. */
    fun unlicensedLib(repoDir: File) = module(repoDir, "no-license-lib", "1.0.0")

    /**
     * `<group>:<name>:<version>`, whose POM depends on each `name:version` of [dependencies] in `com.example`,
     * declares the [licenses], as the POM of a published library does, and names the [parent] POM, given as
     * `group:name:version`, whose licenses it inherits when it declares none.
     */
    fun module(
        repoDir: File,
        name: String,
        version: String,
        vararg dependencies: String,
        group: String = "com.example",
        licenses: List<PomLicense> = emptyList(),
        parent: String? = null,
    ) {
        val dependencyElements = dependencies.joinToString("") { dependency ->
            val (artifactId, dependencyVersion) = dependency.split(':')
            "<dependency><groupId>com.example</groupId><artifactId>$artifactId</artifactId>" +
                "<version>$dependencyVersion</version></dependency>"
        }
        val elements = "${parentElement(parent)}${licensesElement(licenses)}" +
            "<dependencies>$dependencyElements</dependencies>"
        val dir = writePom(repoDir, group, name, version, elements)
        ZipOutputStream(dir.resolve("$name-$version.jar").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            zip.closeEntry()
        }
    }

    /**
     * A parent POM at [coordinates], `group:name:version`, without a jar, declaring the [licenses] it passes on, and
     * naming its own [parent] POM, given the same way, whose licenses it passes on when it declares none.
     */
    fun parent(repoDir: File, coordinates: String, licenses: List<PomLicense> = emptyList(), parent: String? = null) {
        val (group, name, version) = coordinates.split(':')
        val elements = "${parentElement(parent)}<packaging>pom</packaging>${licensesElement(licenses)}"
        writePom(repoDir, group, name, version, elements)
    }

    /** Writes the POM of `<group>:<name>:<version>`, [elements] after its coordinates, and returns its directory. */
    private fun writePom(repoDir: File, group: String, name: String, version: String, elements: String): File {
        val dir = repoDir.resolve("${group.replace('.', '/')}/$name/$version").apply { mkdirs() }
        dir.resolve("$name-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$name</artifactId>
              <version>$version</version>
              $elements
            </project>
            """.trimIndent()
        )
        return dir
    }

    /** The `<parent>` element naming [parent], `group:name:version`, or nothing without one. */
    private fun parentElement(parent: String?): String {
        val (group, name, version) = parent?.split(':') ?: return ""
        // an empty relativePath makes Maven resolve the parent from the repository instead of looking for ../pom.xml
        return "<parent><groupId>$group</groupId><artifactId>$name</artifactId><version>$version</version>" +
            "<relativePath/></parent>"
    }

    /** The `<licenses>` element declaring the [licenses] in their order, or nothing for none. */
    private fun licensesElement(licenses: List<PomLicense>) =
        if (licenses.isEmpty()) "" else licenses.joinToString("", "<licenses>", "</licenses>") { license ->
            val url = license.url?.let { "<url>${escapeXml(it)}</url>" }.orEmpty()
            "<license><name>${escapeXml(license.name)}</name>$url</license>"
        }

    /** [text] with the characters XML gives a meaning escaped, so that any license name or URL can be written. */
    private fun escapeXml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
