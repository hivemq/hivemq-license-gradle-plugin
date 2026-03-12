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
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Validates that all dependencies in the CycloneDX BOM have approved licenses.
 * Does not produce any output files.
 */
@DisableCachingByDefault(because = "Validation task that should always run")
abstract class CheckApprovedLicensesTask : DefaultTask() {

    @get:Input
    abstract val projectName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyLicense: RegularFileProperty

    @get:Input
    abstract val ignoredGroupPrefixes: SetProperty<String>

    @get:Input
    abstract val allowedArtifacts: SetProperty<String>

    @get:Input
    abstract val overriddenLicenses: MapProperty<String, String>

    @get:Input
    abstract val excludedDependencies: MapProperty<String, String>

    init {
        // always run - no outputs means Gradle would consider it UP-TO-DATE otherwise
        outputs.upToDateWhen { false }
    }

    @TaskAction
    protected fun run() {
        val entries = LicenseResolver.resolveLicenses(
            dependencyLicenseFile = dependencyLicense.get().asFile.absoluteFile,
            ignoredGroupPrefixes = ignoredGroupPrefixes.get(),
            allowedArtifacts = allowedArtifacts.get(),
            overriddenLicenses = overriddenLicenses.get(),
            excludedDependencies = excludedDependencies.get(),
        )
        logger.lifecycle("All ${entries.size} dependencies have approved licenses.")
    }
}
