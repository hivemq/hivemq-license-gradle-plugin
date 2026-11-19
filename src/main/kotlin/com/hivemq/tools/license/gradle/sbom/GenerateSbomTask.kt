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
import com.fasterxml.jackson.databind.node.ObjectNode
import com.hivemq.tools.license.gradle.LicenseResolvingTask
import com.hivemq.tools.license.gradle.OwnModules
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
import java.time.format.DateTimeParseException

/**
 * Writes a reproducible CycloneDX SBOM, with the fields [SbomEnricher] adds, from the JSON BOM in [dependencyLicense];
 * see [SbomNormalizer]. Fails like `checkApprovedLicenses` on a license problem, writing no SBOM; leaves out the
 * internal modules ([OwnModules]) and the provided ones but for the first on each path, which stays identified. Every
 * instance gets the `generateSbom` conventions, so a second one sets only what differs, usually [artifactFileName].
 */
@DisableCachingByDefault(because = "Rewriting one file is cheaper than a cache round trip")
abstract class GenerateSbomTask : LicenseResolvingTask() {

    /**
     * An ISO 8601 instant in UTC such as `2026-01-02T03:04:05Z`, anything else fails the task; written as
     * `metadata.timestamp`.
     */
    @get:Input
    abstract val timestamp: Property<String>

    /**
     * Written as the `vcs` and the `source-distribution` reference, canonicalized first by [SbomNormalizer]; without a
     * value the SBOM has neither. A task input, which Gradle keeps in its history, so it must not contain credentials.
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
     * both of which CycloneDX leaves out of the BOM; empty means complete. Setting it leaves
     * [declaredIncompleteReasons] alone.
     */
    @get:Input
    abstract val incompleteReasons: ListProperty<String>

    /**
     * Gaps the build declares because the plugin cannot detect them, such as a frontend the jar bundles from a separate
     * build; `hivemqLicense.sbomIncompleteReasons` by default. Any of them makes the graph incomplete like
     * [incompleteReasons] does, without the warning, and a blank one fails the task.
     */
    @get:Input
    abstract val declaredIncompleteReasons: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    protected fun run() {
        val bomFile = dependencyLicense.get().asFile
        val sbomFile = outputFile.get().asFile
        val reasons = incompleteReasons.get()
        val declaredReasons = declaredIncompleteReasons.get()
        // a blank reason would turn the graph incomplete without saying why
        if (declaredReasons.any { it.isBlank() }) {
            throw GradleException(
                "Task '$path' got a blank declared incomplete reason (hivemqLicense.sbomIncompleteReasons): " +
                    "$declaredReasons"
            )
        }
        val text = timestamp.get()
        val instant = try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            throw GradleException(
                "The timestamp of task '$path' must be an ISO 8601 instant such as 2026-01-02T03:04:05Z, " +
                    "but is '$text'",
                e,
            )
        }
        val provided = providedModules.get()
        val own = OwnModules(ignoredGroupPrefixes.get(), allowedArtifacts.get())
        val normalized = try {
            // before the license resolver, so a file that is not a JSON object gets the reader's message, not the raw
            // one of the resolver; filter, enricher and normalizer then work on this one tree
            val root = BomJson.read(bomFile.readBytes())
            // without the graph nobody can tell which provided components the shipped code depends on directly
            val hasProvided = BomFilter.components(root).any { BomFilter.moduleIdOf(it) in provided }
            val hasGraph = root.get("dependencies")?.let { it.isArray && !it.isEmpty } == true
            if (hasProvided && !hasGraph) {
                throw SbomException("The BOM has no dependency graph, needed to identify the modules the host provides")
            }
            // the shipped artifact contains nothing the host provides at runtime, so neither does its SBOM, except the
            // first provided component on each path: identified, so the host's SBOM continues from it (TR-03183-2 5.1)
            val identified = BomFilter.keepShipped(root, own, provided) { tree, isProvided ->
                val refs = BomFilter.directlyDependedOn(tree, isProvided)
                val isIdentified = { component: ObjectNode -> BomFilter.refOf(component)?.let { it in refs } == true }
                isIdentified
            }
            val identifiedRefs = identified.mapNotNullTo(sortedSetOf(), BomFilter::refOf)
            // identified only: the host's SBOM describes it, here it depends on nothing and ships no file
            BomFilter.detachComponents(root, identifiedRefs)
            BomFilter.removeProperties(root, identifiedRefs, "bsi:component:")
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
                incompleteReasons = reasons + declaredReasons,
                identifiedComponents = identifiedRefs,
            )
            SbomNormalizer.normalize(SbomEnricher.enrich(root, details), instant, vcsUrl.orNull)
        } catch (e: SbomException) {
            throw cannotWrite(bomFile, e)
        } catch (e: JsonProcessingException) {
            // valid JSON the license resolver cannot map, such as a component without a name
            throw cannotWrite(bomFile, e)
        }
        sbomFile.writeBytes(normalized)
        // only the detected gaps: the declared ones are known to the build already
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

    // the task's path starts with its project's path; the configuration cache forbids reading the project here
    private fun projectPath() = path.substringBeforeLast(':').ifEmpty { ":" }
}
