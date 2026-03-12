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

class HivemqLicensePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("hivemqLicense", HivemqLicenseExtensionImpl::class.java)
        extension.projectName.convention(project.name)

        project.plugins.apply(CyclonedxPlugin::class.java)

        val filteredConfigName = "hivemqLicenseRuntimeClasspath"
        project.afterEvaluate {
            val excludedDependencies = extension.excludedDependencies.get()
            if (excludedDependencies.isNotEmpty()) {
                project.configurations.create(filteredConfigName) {
                    isCanBeConsumed = false
                    isCanBeResolved = true
                    extendsFrom(project.configurations.getByName("runtimeClasspath"))
                    for (dep in excludedDependencies) {
                        val parts = dep.split(":")
                        exclude(mapOf("group" to parts[0], "module" to parts[1]))
                    }
                }
            }
        }

        val resolvedExcludedDependencies = extension.excludedDependencies.map { excludedDeps ->
            if (excludedDeps.isEmpty()) return@map emptyMap<String, String>()
            val runtimeClasspath = project.configurations.getByName("runtimeClasspath")
            val resolvedVersions = mutableMapOf<String, String>()
            runtimeClasspath.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                val moduleId = "${artifact.moduleVersion.id.group}:${artifact.moduleVersion.id.name}"
                if (moduleId in excludedDeps) {
                    resolvedVersions[moduleId] = artifact.moduleVersion.id.version
                }
            }
            resolvedVersions.toMap()
        }

        val cyclonedxDirectBom = project.tasks.named("cyclonedxDirectBom", CyclonedxDirectTask::class.java)
        cyclonedxDirectBom.configure {
            projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
            includeConfigs.set(extension.excludedDependencies.map { excludedDeps ->
                if (excludedDeps.isNotEmpty()) listOf(filteredConfigName) else listOf("runtimeClasspath")
            })
        }

        val cyclonedxJsonOutput = cyclonedxDirectBom.flatMap { it.jsonOutput }

        project.tasks.register("checkApprovedLicenses", CheckApprovedLicensesTask::class.java).configure {
            group = "hivemq license"
            description = "Validates that all dependencies have approved licenses."
            projectName.set(extension.projectName)
            dependencyLicense.set(cyclonedxJsonOutput)
            ignoredGroupPrefixes.set(extension.ignoredGroupPrefixes)
            allowedArtifacts.set(extension.allowedArtifacts)
            overriddenLicenses.set(extension.overriddenLicenses)
            excludedDependencies.set(resolvedExcludedDependencies)
        }

        project.tasks.register("updateThirdPartyLicenses", UpdateThirdPartyLicensesTask::class.java).configure {
            group = "hivemq license"
            description = "Generates third-party license reports from the CycloneDX BOM."
            projectName.set(extension.projectName)
            outputFileNamePrefix.convention("")
            dependencyLicense.set(cyclonedxJsonOutput)
            ignoredGroupPrefixes.set(extension.ignoredGroupPrefixes)
            allowedArtifacts.set(extension.allowedArtifacts)
            overriddenLicenses.set(extension.overriddenLicenses)
            excludedDependencies.set(resolvedExcludedDependencies)
            outputDirectory.set(extension.thirdPartyLicenseDirectory)
        }
    }
}
