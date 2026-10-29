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

import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.gradle.CyclonedxPlugin
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.attributes.Category
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.util.GradleVersion
import javax.inject.Inject

abstract class HivemqLicensePlugin @Inject constructor(
    private val buildEventsListenerRegistry: BuildEventsListenerRegistry,
) : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("hivemqLicense", HivemqLicenseExtensionImpl::class.java)
        extension.projectName.convention(project.name)
        extension.sbomName.convention(project.name)
        extension.sbomTimestamp.convention(
            project.providers.of(SbomTimestampSource::class.java) {
                parameters.sourceDateEpoch.set(project.providers.environmentVariable("SOURCE_DATE_EPOCH"))
                parameters.projectDirectory.set(project.layout.projectDirectory)
                parameters.projectPath.set(project.path)
            }
        )
        extension.sbomCreatorName.convention("HiveMQ GmbH")
        extension.sbomCreator.convention("security@hivemq.com")
        project.pluginManager.withPlugin("java") {
            extension.sbomArtifactFileName.convention(
                project.tasks.named("jar", Jar::class.java).flatMap { it.archiveFileName }
            )
        }

        project.plugins.apply(CyclonedxPlugin::class.java)

        // The CycloneDX plugin creates a consumable "cyclonedxDirectBom" configuration for aggregate BOM collection.
        // We don't use the aggregate BOM, and leaving it consumable causes issues: other plugins (e.g. Shadow) and
        // the Gradle Tooling API (used by Buildship / Eclipse JDT.LS) can trigger variant observation that consumes
        // this configuration before CycloneDX's deferred artifact wiring runs, resulting in
        // "Cannot mutate the artifacts of configuration" errors.
        //
        // CycloneDX 3.x auto-propagates to subprojects via a per-project plugin class that differs from the root
        // CyclonedxPlugin, so a plugins.withType(CyclonedxPlugin) hook only catches the root. Watch the
        // configurations container in every project instead — configureEach fires synchronously when the
        // configuration is added, before any model builder or variant resolution can observe it.
        project.allprojects {
            configurations.configureEach {
                if (name == "cyclonedxDirectBom") {
                    isCanBeConsumed = false
                }
            }
        }

        project.afterEvaluate {
            for (configName in extension.additionalConfigurations.get()) {
                project.configurations.create("$WRAPPER_CONFIGURATION_PREFIX$configName") {
                    isCanBeConsumed = false
                    isCanBeResolved = true
                    extendsFrom(project.configurations.getByName(configName))
                }
            }
        }

        val cyclonedxDirectBom = project.tasks.named(BOM_TASK_NAME, CyclonedxDirectTask::class.java)
        cyclonedxDirectBom.configure {
            projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
            includeConfigs.set(bomConfigurationNames(extension))
        }

        val cyclonedxJsonOutput = cyclonedxDirectBom.flatMap { it.jsonOutput }
        // The location alone carries no dependency on the task, so it can be read even when a consumer pointed
        // dependencyLicense elsewhere and the BOM task does not run.
        val cyclonedxJsonOutputPath =
            cyclonedxDirectBom.flatMap { it.jsonOutput.locationOnly }.map { it.asFile.absolutePath }
        // the task's path in the whole build tree, which is what task finish events carry
        val bomTaskPath = "${project.buildTreePath.trimEnd(':')}:${cyclonedxDirectBom.name}"

        // The plugin is applied per project; Gradle registers the service and the listener once per provider.
        val bomTaskOutcomes = project.gradle.sharedServices
            .registerIfAbsent(BomTaskOutcomeService.NAME, BomTaskOutcomeService::class.java) {}
        buildEventsListenerRegistry.onTaskCompletion(bomTaskOutcomes)
        cyclonedxDirectBom.configure {
            usesService(bomTaskOutcomes)
            doFirst { bomTaskOutcomes.get().recordExecuted(bomTaskPath) }
        }

        fun LicenseResolvingTask.readLicensesFromBom() {
            projectName.set(extension.projectName)
            dependencyLicense.set(cyclonedxJsonOutput)
            dependencyLicenseTaskPath.set(bomTaskPath)
            dependencyLicenseTaskOutput.set(cyclonedxJsonOutputPath)
            taskOutcomes.set(bomTaskOutcomes)
            usesService(bomTaskOutcomes)
            ignoredGroupPrefixes.set(extension.ignoredGroupPrefixes)
            allowedArtifacts.set(extension.allowedArtifacts)
            overriddenLicenses.set(extension.overriddenLicenses)
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

        val generateSbom = project.tasks.register(SBOM_TASK_NAME, GenerateSbomTask::class.java) {
            group = TASK_GROUP
            description =
                "Writes a reproducible CycloneDX SBOM (<sbomName>.cdx.json) from the cyclonedxDirectBom output."
            bom.set(cyclonedxJsonOutput)
            timestamp.set(extension.sbomTimestamp)
            vcsUrl.set(
                project.providers.of(SbomVcsUrlSource::class.java) {
                    parameters.projectDirectory.set(project.layout.projectDirectory)
                    parameters.projectPath.set(project.path)
                }
            )
            creatorName.set(extension.sbomCreatorName)
            creator.set(extension.sbomCreator)
            artifactFileName.set(extension.sbomArtifactFileName)
            componentFileNames.set(resolvedComponentFileNames(project, extension))
            incompleteReasons.set(incompleteGraphReasons(project, extension))
            ignoredGroupPrefixes.set(extension.ignoredGroupPrefixes)
            allowedArtifacts.set(extension.allowedArtifacts)
            overriddenLicenses.set(extension.overriddenLicenses)
            outputFile.set(
                project.layout.buildDirectory.file(extension.sbomName.map { "sbom/${checkSbomName(it)}.cdx.json" })
            )
        }

        // Without the java plugin, an attributed consumable configuration would switch the project's consumers from
        // the default configuration to attribute matching, which then finds no library.
        project.pluginManager.withPlugin("java") {
            // other projects, or included builds, collect the SBOM through this variant, by its Category attribute
            project.configurations.create(SBOM_CONFIGURATION_NAME) {
                isCanBeConsumed = true
                isCanBeResolved = false
                // Gradle 8 lets assemble build the artifacts of every visible configuration, Gradle 9 does not
                if (GradleVersion.current().baseVersion < GradleVersion.version("9.0")) {
                    @Suppress("DEPRECATION")
                    isVisible = false
                }
                attributes.attribute(
                    Category.CATEGORY_ATTRIBUTE,
                    project.objects.named(Category::class.java, SBOM_CATEGORY)
                )
                outgoing.artifact(generateSbom.flatMap { it.outputFile })
            }
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
     * `group:name:version` to file name of the module artifacts of the configurations the BOM is built from. The
     * configurations are looked up when Gradle queries the value, after evaluation, because the wrappers are only
     * created then.
     */
    private fun resolvedComponentFileNames(
        project: Project,
        extension: HivemqLicenseExtension,
    ): Provider<Map<String, String>> {
        val configurations = project.configurations
        val noArtifacts = project.provider { emptyList<ResolvedArtifactResult>() }
        return bomConfigurationNames(extension).flatMap { names ->
            // in name order, keeping the first file of a component: that is how CycloneDX picks the file it hashes
            names.sorted()
                .mapNotNull { configurations.findByName(it) }
                .map { configuration ->
                    configuration.incoming.artifactView {
                        // modules only, like CycloneDX: the artifact of a project or a file may have to be built
                        componentFilter { it is ModuleComponentIdentifier }
                        // like CycloneDX too, which leaves out what fails to resolve instead of failing
                        lenient(true)
                    }.artifacts.resolvedArtifacts
                }
                .fold(noArtifacts) { all, artifacts -> all.zip(artifacts) { left, right -> left + right } }
                .map { artifacts ->
                    buildMap {
                        for (artifact in artifacts) {
                            val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: continue
                            putIfAbsent(Coordinates(id.group, id.module, id.version).toString(), artifact.file.name)
                        }
                    }
                }
        }
    }

    /**
     * Why the BOM's dependency graph is not complete, one short reason per finding in configuration name order, empty
     * when it is complete. CycloneDX leaves out file dependencies and modules that fail to resolve, so the build looks
     * for both itself; like [resolvedComponentFileNames], it finds the configurations when Gradle queries the value.
     */
    private fun incompleteGraphReasons(project: Project, extension: HivemqLicenseExtension): Provider<List<String>> {
        val configurations = project.configurations
        val noReasons = project.provider { emptyList<String>() }
        return bomConfigurationNames(extension).flatMap { names ->
            names.sorted()
                .mapNotNull { configurations.findByName(it) }
                .map { configuration ->
                    // the name the build script gave it, not the one of the wrapper the plugin resolves in its place
                    val name = configuration.name.removePrefix(WRAPPER_CONFIGURATION_PREFIX)
                    // the file dependencies, also those other projects and included builds bring in, are the artifacts
                    // of neither a module nor a project; leaving out projects also keeps their jars from being built
                    val files = configuration.incoming.artifactView {
                        componentFilter { it !is ModuleComponentIdentifier && it !is ProjectComponentIdentifier }
                        // a module that fails to resolve would fail the view, and it gets its own reason below
                        lenient(true)
                    }.artifacts.resolvedArtifacts
                    configuration.incoming.resolutionResult.rootComponent.zip(files) { root, artifacts ->
                        fileDependencyReasons(name, artifacts) + unresolvedDependencyReasons(name, root)
                    }
                }
                .fold(noReasons) { all, reasons -> all.zip(reasons) { left, right -> left + right } }
        }
    }

    companion object {
        /** The CycloneDX task whose BOM the license tasks read. */
        const val BOM_TASK_NAME = "cyclonedxDirectBom"

        /** The task that writes the reproducible SBOM and the variant that carries it. */
        const val SBOM_TASK_NAME = "generateSbom"
        const val SBOM_CONFIGURATION_NAME = "sbomElements"
        const val SBOM_CATEGORY = "sbom"

        private const val TASK_GROUP = "hivemq license"

        /** Prefix of the resolvable wrapper the plugin creates for each additional configuration. */
        private const val WRAPPER_CONFIGURATION_PREFIX = "hivemqLicense_"

        // The helpers below run inside providers, which the configuration cache may store. They live here, so that a
        // lambda calling them does not capture the plugin, which the cache would then have to store as well.

        // a separator in the name would write the SBOM elsewhere, and a blank name would give a file without a name
        private fun checkSbomName(name: String): String {
            if (name.isBlank() || '/' in name || '\\' in name) {
                throw GradleException(
                    "hivemqLicense.sbomName must be a non-blank file name without '/' or '\\', but is '$name'"
                )
            }
            return name
        }

        // file names, sorted and without paths, so the task input depends on neither the machine nor the artifact order
        private fun fileDependencyReasons(
            configurationName: String,
            artifacts: Set<ResolvedArtifactResult>,
        ): List<String> {
            val fileNames = artifacts.map { it.file.name }.toSortedSet()
            if (fileNames.isEmpty()) return emptyList()
            return listOf("$configurationName has file dependencies: ${fileNames.joinToString(", ")}")
        }

        // an unresolved module has no component, only the edge that requested it; sorted, whatever the walk order
        private fun unresolvedDependencyReasons(
            configurationName: String,
            root: ResolvedComponentResult,
        ): List<String> {
            val visited = mutableSetOf<ComponentIdentifier>()
            val queue = ArrayDeque(listOf(root))
            val reasons = sortedSetOf<String>()
            while (queue.isNotEmpty()) {
                val component = queue.removeFirst()
                if (!visited.add(component.id)) continue
                for (dependency in component.dependencies) {
                    // a constraint adds no component, CycloneDX skips it as well
                    if (dependency.isConstraint) continue
                    when (dependency) {
                        is ResolvedDependencyResult -> queue.add(dependency.selected)
                        is UnresolvedDependencyResult ->
                            reasons.add("$configurationName could not resolve ${dependency.requested.displayName}")
                    }
                }
            }
            return reasons.toList()
        }
    }
}
