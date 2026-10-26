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
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.build.event.BuildEventsListenerRegistry
import javax.inject.Inject

abstract class HivemqLicensePlugin @Inject constructor(
    private val buildEventsListenerRegistry: BuildEventsListenerRegistry,
) : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("hivemqLicense", HivemqLicenseExtensionImpl::class.java)
        extension.projectName.convention(project.name)

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
                project.configurations.create("hivemqLicense_$configName") {
                    isCanBeConsumed = false
                    isCanBeResolved = true
                    extendsFrom(project.configurations.getByName(configName))
                }
            }
        }

        val cyclonedxDirectBom = project.tasks.named(BOM_TASK_NAME, CyclonedxDirectTask::class.java)
        cyclonedxDirectBom.configure {
            projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
            includeConfigs.set(
                extension.additionalConfigurations.map { additionalConfigs ->
                    listOf("runtimeClasspath") + additionalConfigs.map { "hivemqLicense_$it" }
                }
            )
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
    }

    companion object {
        /** The CycloneDX task whose BOM the license tasks read. */
        const val BOM_TASK_NAME = "cyclonedxDirectBom"

        private const val TASK_GROUP = "hivemq license"
    }
}
