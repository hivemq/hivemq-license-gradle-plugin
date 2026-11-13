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

    /** `com.example:no-license-lib:1.0.0`, whose missing license makes checkApprovedLicenses fail for a dependant. */
    fun unlicensedLib(repoDir: File) = module(repoDir, "no-license-lib", "1.0.0")

    /**
     * `<group>:<name>:<version>`, whose POM depends on each `name:version` of [dependencies] in `com.example` and
     * declares [license] by name when given, as the POM of a published library does.
     */
    fun module(
        repoDir: File,
        name: String,
        version: String,
        vararg dependencies: String,
        group: String = "com.example",
        license: String? = null,
    ) {
        val dir = repoDir.resolve("${group.replace('.', '/')}/$name/$version").apply { mkdirs() }
        val declared = dependencies.joinToString("") { dependency ->
            val (artifactId, dependencyVersion) = dependency.split(':')
            "<dependency><groupId>com.example</groupId><artifactId>$artifactId</artifactId>" +
                "<version>$dependencyVersion</version></dependency>"
        }
        val licenses = license?.let { "<licenses><license><name>$it</name></license></licenses>" }.orEmpty()
        dir.resolve("$name-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$name</artifactId>
              <version>$version</version>
              $licenses
              <dependencies>$declared</dependencies>
            </project>
            """.trimIndent()
        )
        ZipOutputStream(dir.resolve("$name-$version.jar").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            zip.closeEntry()
        }
    }
}
