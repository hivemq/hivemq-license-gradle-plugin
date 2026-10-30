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

import com.hivemq.tools.license.gradle.sbom.SbomWiring
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Provider
import org.gradle.build.event.BuildEventsListenerRegistry
import javax.inject.Inject

abstract class HivemqLicensePlugin @Inject constructor(
    private val buildEventsListenerRegistry: BuildEventsListenerRegistry,
) : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("hivemqLicense", HivemqLicenseExtensionImpl::class.java)
        extension.projectName.convention(project.name)

        project.afterEvaluate {
            for (configName in extension.additionalConfigurations.get()) {
                project.configurations.create("$WRAPPER_CONFIGURATION_PREFIX$configName") {
                    isCanBeConsumed = false
                    isCanBeResolved = true
                    extendsFrom(project.configurations.getByName(configName))
                }
            }
        }

        val bomConfigurationNames = bomConfigurationNames(extension)
        val bomTask = project.tasks.register(BOM_TASK_NAME, CyclonedxDirectTask::class.java) {
            description =
                "Writes the CycloneDX BOM of the resolved dependencies that the license tasks and generateSbom read."
            jsonOutput.convention(project.layout.buildDirectory.file("license/bom.json"))
            projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
            // CycloneDX matches includeConfigs as regular expressions, so escape the names to read them literally
            includeConfigs.set(bomConfigurationNames.map { names -> names.map { Regex.escape(it) } })
            // generateSbom describes the files of exactly these configurations, so a build script cannot pick others
            includeConfigs.disallowChanges()
            skipConfigs.disallowChanges()
        }

        val bomJsonOutput = bomTask.flatMap { it.jsonOutput }
        // The location alone carries no dependency on the task, so it can be read even when a consumer pointed
        // dependencyLicense elsewhere and the BOM task does not run.
        val bomJsonOutputPath = bomTask.flatMap { it.jsonOutput.locationOnly }.map { it.asFile.absolutePath }
        // the task's path in the whole build tree, which is what task finish events carry
        val bomTaskPath = "${project.buildTreePath.trimEnd(':')}:${bomTask.name}"

        // The plugin is applied per project; Gradle registers the service and the listener once per provider.
        val bomTaskOutcomes = project.gradle.sharedServices
            .registerIfAbsent(BomTaskOutcomeService.NAME, BomTaskOutcomeService::class.java) {}
        buildEventsListenerRegistry.onTaskCompletion(bomTaskOutcomes)
        bomTask.configure {
            usesService(bomTaskOutcomes)
            doFirst { bomTaskOutcomes.get().recordExecuted(bomTaskPath) }
        }

        val provided = providedModules(project)

        fun LicenseResolvingTask.readLicensesFromBom() {
            projectName.set(extension.projectName)
            dependencyLicense.set(bomJsonOutput)
            dependencyLicenseTaskPath.set(bomTaskPath)
            dependencyLicenseTaskOutput.set(bomJsonOutputPath)
            taskOutcomes.set(bomTaskOutcomes)
            usesService(bomTaskOutcomes)
            ignoredGroupPrefixes.set(extension.ignoredGroupPrefixes)
            allowedArtifacts.set(extension.allowedArtifacts)
            overriddenLicenses.set(extension.overriddenLicenses)
            providedModules.set(provided)
        }

        project.tasks.register("checkApprovedLicenses", CheckApprovedLicensesTask::class.java).configure {
            group = TASK_GROUP
            description = "Validates that all dependencies have approved licenses."
            readLicensesFromBom()
        }

        project.tasks.register("updateThirdPartyLicenses", UpdateThirdPartyLicensesTask::class.java).configure {
            group = TASK_GROUP
            description = "Generates third-party license reports from the CycloneDX BOM."
            readLicensesFromBom()
            outputFileNamePrefix.convention("")
            outputDirectory.set(extension.thirdPartyLicenseDirectory)
        }

        // the SBOM resolves the licenses like the other license tasks, so a license problem fails it the same way
        SbomWiring.register(project, extension, bomConfigurationNames).configure {
            readLicensesFromBom()
        }
    }

    /**
     * Names of the configurations the BOM is built from: `runtimeClasspath`, then the wrapper of each additional
     * configuration. The wrappers are only created after evaluation, so this is a provider of names, not of
     * configurations.
     */
    private fun bomConfigurationNames(extension: HivemqLicenseExtension): Provider<List<String>> =
        extension.additionalConfigurations.map { additionalConfigs ->
            listOf("runtimeClasspath") + additionalConfigs.map { "$WRAPPER_CONFIGURATION_PREFIX$it" }
        }

    /**
     * The modules a `hivemqProvided` configuration resolves, by `group:name`; empty when there is no such
     * configuration.
     */
    private fun providedModules(project: Project): Provider<Set<String>> {
        val configurations = project.configurations
        return project.provider { configurations.findByName(PROVIDED_CONFIGURATION_NAME) }
            .flatMap { it.incoming.resolutionResult.rootComponent }
            .map { root -> modulesBelow(root) }
            .orElse(emptySet())
    }

    companion object {
        /** The CycloneDX task that writes the BOM the license tasks and the SBOM read, registered without a group. */
        const val BOM_TASK_NAME = "generateDependencyBom"

        /** The task that writes the reproducible SBOM and the variant that carries it. */
        const val SBOM_TASK_NAME = "generateSbom"
        const val SBOM_CONFIGURATION_NAME = "sbomElements"
        const val SBOM_CATEGORY = "sbom"

        /** The configuration in which an extension build declares what the HiveMQ broker provides at runtime. */
        internal const val PROVIDED_CONFIGURATION_NAME = "hivemqProvided"

        internal const val TASK_GROUP = "hivemq license"

        /** Prefix of the resolvable wrapper the plugin creates for each additional configuration. */
        internal const val WRAPPER_CONFIGURATION_PREFIX = "hivemqLicense_"

        /** Every component below the root, the root itself excluded: the extension's shadow jar drops the same set. */
        private fun modulesBelow(root: ResolvedComponentResult): Set<String> {
            val seen = linkedSetOf(root)
            val queue = ArrayDeque(root.dependencies)
            while (queue.isNotEmpty()) {
                val selected = (queue.removeFirst() as? ResolvedDependencyResult)?.selected ?: continue
                if (seen.add(selected)) queue.addAll(selected.dependencies)
            }
            return (seen - root).mapNotNull { it.moduleVersion?.let { id -> "${id.group}:${id.name}" } }.toSortedSet()
        }
    }
}
