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

/** A local Maven repository with `com.example:no-license-lib:1.0.0`, an artifact whose POM declares no license. */
internal object UnlicensedRepository {

    /**
     * Writes the repository into [repoDir], so the real cyclonedxDirectBom task produces a BOM that makes
     * checkApprovedLicenses fail for a project that depends on the artifact.
     */
    fun write(repoDir: File) {
        val artifactDir = repoDir.resolve("com/example/no-license-lib/1.0.0")
        artifactDir.mkdirs()
        artifactDir.resolve("no-license-lib-1.0.0.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>no-license-lib</artifactId>
              <version>1.0.0</version>
            </project>
            """.trimIndent()
        )
        ZipOutputStream(artifactDir.resolve("no-license-lib-1.0.0.jar").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            zip.closeEntry()
        }
    }
}
