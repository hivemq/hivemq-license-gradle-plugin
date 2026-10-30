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

import com.fasterxml.jackson.core.JsonProcessingException
import com.hivemq.tools.license.gradle.LicenseResolvingTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.time.Instant

/**
 * Writes a reproducible CycloneDX SBOM, with the fields [SbomEnricher] adds, from the JSON BOM in [dependencyLicense],
 * which the plugin sets to the output of `cyclonedxDirectBom`; see [SbomNormalizer]. It fails on the license
 * problems `checkApprovedLicenses` fails on, with the same message, and then writes no SBOM. A registration by hand
 * must set [projectName] too, although the SBOM does not use it.
 */
@DisableCachingByDefault(because = "Rewriting one file is cheaper than a cache round trip")
abstract class GenerateSbomTask : LicenseResolvingTask() {

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

    /**
     * Email address or http(s) URL of that organisation, anything else fails the task; BSI TR-03183-2 wants a contact
     * for the SBOM's creator.
     */
    @get:Input
    abstract val creator: Property<String>

    /** File name of the shipped top-level artifact, left out of the SBOM when unknown. */
    @get:Input
    @get:Optional
    abstract val artifactFileName: Property<String>

    /** `group:name:version` to file name of the resolved components, for the per-component file description. */
    @get:Input
    abstract val componentFileNames: MapProperty<String, String>

    /**
     * Why the dependency graph is not complete, such as a file dependency or a module that could not be resolved,
     * both of which CycloneDX leaves out of the BOM; empty means complete.
     */
    @get:Input
    abstract val incompleteReasons: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    protected fun run() {
        val bomFile = dependencyLicense.get().asFile
        val sbomFile = outputFile.get().asFile
        val reasons = incompleteReasons.get()
        val normalized = try {
            // before the license resolver, so a file that is not a JSON object gets the reader's message, not the raw
            // one of the resolver; enricher and normalizer then work on this one tree
            val root = BomJson.read(bomFile.readBytes())
            // fails on a license problem with the message of checkApprovedLicenses, which is not wrapped below
            val concluded = resolveLicenses().values.associate { dependency ->
                // by the version the license was resolved for, which another version of the module may not share
                dependency.coordinates.toString() to dependency.licenses.map { it.id }
            }
            val details = SbomDetails(
                creatorName = creatorName.get(),
                creator = creator.get(),
                artifactFileName = artifactFileName.orNull,
                componentFileNames = componentFileNames.get(),
                concludedLicenses = concluded,
                incompleteReasons = reasons,
            )
            SbomNormalizer.normalize(SbomEnricher.enrich(root, details), timestamp.get(), vcsUrl.orNull)
        } catch (e: SbomException) {
            throw cannotWrite(bomFile, e)
        } catch (e: JsonProcessingException) {
            // valid JSON the license resolver cannot map, such as a component without a name
            throw cannotWrite(bomFile, e)
        }
        sbomFile.writeBytes(normalized)
        if (reasons.isNotEmpty()) {
            // the SBOM says so too, but nobody reads it during the build
            logger.warn(
                "The SBOM of project '${projectPath()}' declares its dependency graph incomplete: " +
                    reasons.joinToString("; ")
            )
        }
    }

    private fun cannotWrite(bomFile: File, cause: Exception) =
        GradleException("Cannot write an SBOM from '$bomFile': ${cause.message}", cause)

    /** The task's path starts with its project's path; the configuration cache forbids reading the project here. */
    private fun projectPath() = path.substringBeforeLast(':').ifEmpty { ":" }
}
