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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException

/**
 * Merges the per-component SBOMs of one deliverable, such as a ZIP or a Docker image, into one flat product SBOM; see
 * [SbomMerger]. A build registers one per deliverable and sets [sboms], [productName] and [productVersion]; the plugin
 * registers none, but applies the `hivemqLicense` conventions to every instance.
 */
@DisableCachingByDefault(because = "Rewriting one file is cheaper than a cache round trip")
abstract class MergeSbomsTask : DefaultTask() {

    /**
     * The SBOM files of the parts, one per part, each with a top-level component. The task fails without a file, and on
     * a directory: pass the files, such as those of a `fileTree`.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sboms: ConfigurableFileCollection

    /**
     * Name of the product. Unless [outputFile] is set, it also names the output file and then fails the task when
     * blank, with a path separator, or equal to the project's `hivemqLicense.sbomName` in any letter case.
     */
    @get:Input
    abstract val productName: Property<String>

    /** Version of the product; with [productName] it forms the `bom-ref` of its top-level component. */
    @get:Input
    abstract val productVersion: Property<String>

    /** File name of the deliverable, such as `hivemq-4.47.0.zip`; left out when it has none, such as an image. */
    @get:Input
    @get:Optional
    abstract val artifactFileName: Property<String>

    /**
     * Names of the parts that must contribute an SBOM; the product is incomplete, with a warning, when one is
     * missing. The name of a part is the name of its top-level component, by default the name of its project.
     */
    @get:Input
    abstract val expectedParts: SetProperty<String>

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

    /** Name of the organisation that creates the SBOM and ships the product, written as its manufacturer. */
    @get:Input
    abstract val creatorName: Property<String>

    /** Email address or http(s) URL of that organisation, anything else fails the task. */
    @get:Input
    abstract val creator: Property<String>

    /** The product SBOM; `build/sbom/<productName>.cdx.json` by default. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    protected fun run() {
        val output = outputFile.get().asFile
        val instant = SbomTimestamp.parse(path, timestamp.get())
        // by path, so a message names the same file for the same input whatever order the collection has
        val files = sboms.files.sortedBy { it.path }
        // a zip tree that matches nothing would otherwise ship a product that claims to be complete
        if (files.isEmpty()) throw cannotMerge(output, "the sboms input holds no file")
        val parts = files.map { file ->
            try {
                SbomPart(file.path, file.readBytes())
            } catch (e: IOException) {
                throw cannotMerge(output, "cannot read '${file.path}': ${e.message}", e)
            }
        }
        val product = ProductDetails(
            name = productName.get(),
            version = productVersion.get(),
            artifactFileName = artifactFileName.orNull,
            creatorName = creatorName.get(),
            creator = creator.get(),
            expectedParts = expectedParts.get(),
        )
        val (bytes, incompleteReasons) = try {
            val merged = SbomMerger.merge(parts, product)
            // the normalizer fails too, on a timestamp outside the four-digit years
            SbomNormalizer.normalize(merged.root, instant, vcsUrl.orNull) to merged.incompleteReasons
        } catch (e: SbomException) {
            throw cannotMerge(output, e.message.orEmpty(), e)
        }
        output.writeBytes(bytes)
        if (incompleteReasons.isNotEmpty()) {
            // the SBOM says so too, but nobody reads it during the build
            logger.warn(
                "The product SBOM '${product.name}' of task '$path' declares its dependency graph incomplete: " +
                    incompleteReasons.joinToString("; ")
            )
        }
    }

    /** The failure of this task for [reason], with the output file in front, as every failure of the merge has it. */
    private fun cannotMerge(output: File, reason: String, cause: Throwable? = null) =
        GradleException("Cannot merge the SBOMs into '$output': $reason", cause)
}
