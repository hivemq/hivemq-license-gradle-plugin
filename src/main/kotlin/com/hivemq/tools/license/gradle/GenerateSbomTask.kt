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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.time.Instant

/**
 * Writes a reproducible CycloneDX SBOM from the JSON BOM of the `cyclonedxDirectBom` task, see [SbomNormalizer].
 */
@DisableCachingByDefault(
    because = "Rewriting one file is cheaper than a cache round trip, and the random serial number in the CycloneDX " +
        "output makes a hit unlikely"
)
abstract class GenerateSbomTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bom: RegularFileProperty

    /** Written as `metadata.timestamp`; a build that passes the same instant writes the same file. */
    @get:Input
    abstract val timestamp: Property<Instant>

    /**
     * Written as the `vcs` and the `source-distribution` reference, canonicalized first by [SbomNormalizer]; without a
     * value the SBOM has neither. In a custom registration the raw value is a task input, which Gradle keeps in its
     * task history, so it must not contain credentials.
     */
    @get:Input
    @get:Optional
    abstract val vcsUrl: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    protected fun run() {
        val bomFile = bom.get().asFile
        val sbomFile = outputFile.get().asFile
        val normalized = try {
            SbomNormalizer.normalize(bomFile.readBytes(), timestamp.get(), vcsUrl.orNull)
        } catch (e: IllegalArgumentException) {
            throw GradleException("Cannot write an SBOM from '$bomFile': ${e.message}", e)
        }
        sbomFile.writeBytes(normalized)
    }
}
