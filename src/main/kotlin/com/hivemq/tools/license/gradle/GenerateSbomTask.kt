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

import com.fasterxml.jackson.core.JsonProcessingException
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.time.Instant

/**
 * Writes a reproducible CycloneDX SBOM from the JSON BOM of the `cyclonedxDirectBom` task, with the fields
 * [SbomEnricher] adds, see [SbomNormalizer].
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

    /** Name of the organisation that creates the SBOM and ships the component, written as its manufacturer. */
    @get:Input
    abstract val creatorName: Property<String>

    /** Email address or URL of that organisation; BSI TR-03183-2 wants a contact for the SBOM's creator. */
    @get:Input
    abstract val creator: Property<String>

    /** File name of the shipped top-level artifact, left out of the SBOM when unknown. */
    @get:Input
    @get:Optional
    abstract val artifactFileName: Property<String>

    /** `group:name:version` to file name of the resolved artifacts, for the per-component file description. */
    @get:Input
    abstract val artifactFileNames: MapProperty<String, String>

    /** Same as `hivemqLicense.ignoredGroupPrefixes`; here it only shapes which concluded licenses are written. */
    @get:Input
    abstract val ignoredGroupPrefixes: SetProperty<String>

    /** Same as `hivemqLicense.allowedArtifacts`; here it only shapes which concluded licenses are written. */
    @get:Input
    abstract val allowedArtifacts: SetProperty<String>

    /** Same as `hivemqLicense.overriddenLicenses`; here it only shapes which concluded licenses are written. */
    @get:Input
    abstract val overriddenLicenses: MapProperty<String, String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    protected fun run() {
        val bomFile = bom.get().asFile
        val sbomFile = outputFile.get().asFile
        val normalized = try {
            val bomBytes = bomFile.readBytes()
            // a file that is not a JSON object gets the reader's message, not the raw one of the license resolver
            BomJson.read(bomBytes)
            // a license problem is for checkApprovedLicenses to report, the SBOM takes what resolves
            val concluded = LicenseResolver.resolveKnownLicenses(
                bomFile, ignoredGroupPrefixes.get(), allowedArtifacts.get(), overriddenLicenses.get()
            ).values.associate { dependency ->
                // by the version the license was resolved for, which another version of the module may not share
                dependency.coordinates.toString() to dependency.licenses.joinToString(" AND ") { it.id }
            }
            val details = SbomDetails(
                creatorName = creatorName.get(),
                creator = creator.get(),
                artifactFileName = artifactFileName.orNull,
                artifactFileNames = artifactFileNames.get(),
                concludedLicenses = concluded,
            )
            SbomNormalizer.normalize(SbomEnricher.enrich(bomBytes, details), timestamp.get(), vcsUrl.orNull)
        } catch (e: IllegalArgumentException) {
            throw cannotWrite(bomFile, e)
        } catch (e: JsonProcessingException) {
            // valid JSON the license resolver cannot map, such as a component without a name
            throw cannotWrite(bomFile, e)
        }
        sbomFile.writeBytes(normalized)
    }

    private fun cannotWrite(bomFile: File, cause: Exception) =
        GradleException("Cannot write an SBOM from '$bomFile': ${cause.message}", cause)
}
