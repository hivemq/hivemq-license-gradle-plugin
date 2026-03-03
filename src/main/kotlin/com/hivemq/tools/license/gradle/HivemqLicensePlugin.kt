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

        val cyclonedxDirectBom = project.tasks.named("cyclonedxDirectBom", CyclonedxDirectTask::class.java)
        cyclonedxDirectBom.configure {
            projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
            includeConfigs.set(listOf("runtimeClasspath"))
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
        }

        project.tasks.register("updateThirdPartyLicenses", UpdateThirdPartyLicensesTask::class.java).configure {
            group = "hivemq license"
            description = "Generates third-party license reports from the CycloneDX BOM."
            projectName.set(extension.projectName)
            dependencyLicense.set(cyclonedxJsonOutput)
            ignoredGroupPrefixes.set(extension.ignoredGroupPrefixes)
            allowedArtifacts.set(extension.allowedArtifacts)
            overriddenLicenses.set(extension.overriddenLicenses)
            outputDirectory.set(extension.thirdPartyLicenseDirectory)
        }
    }
}
