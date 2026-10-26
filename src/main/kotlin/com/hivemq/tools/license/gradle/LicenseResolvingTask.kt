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
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault
import java.util.TreeMap

/**
 * What the tasks that read a CycloneDX BOM (or a pnpm license report) and resolve its licenses have in common.
 */
@DisableCachingByDefault(because = "Base class, the concrete tasks decide")
abstract class LicenseResolvingTask : DefaultTask() {

    @get:Input
    abstract val projectName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyLicense: RegularFileProperty

    /** Path in the build tree of the task that produces the BOM, used to tell where [dependencyLicense] came from. */
    @get:Internal
    abstract val dependencyLicenseTaskPath: Property<String>

    /** Absolute path of that task's output, so the origin is only reported when [dependencyLicense] is that file. */
    @get:Internal
    abstract val dependencyLicenseTaskOutput: Property<String>

    /** Records how the BOM task finished, so a failure can say where [dependencyLicense] came from. */
    @get:Internal
    abstract val taskOutcomes: Property<BomTaskOutcomeService>

    @get:Input
    abstract val ignoredGroupPrefixes: SetProperty<String>

    @get:Input
    abstract val allowedArtifacts: SetProperty<String>

    @get:Input
    abstract val overriddenLicenses: MapProperty<String, String>

    protected fun resolveLicenses(): TreeMap<String, ResolvedDependency> = LicenseResolver.resolveLicenses(
        dependencyLicenseFile = dependencyLicense.get().asFile.absoluteFile,
        ignoredGroupPrefixes = ignoredGroupPrefixes.get(),
        allowedArtifacts = allowedArtifacts.get(),
        overriddenLicenses = overriddenLicenses.get(),
        dependencyLicenseOrigin = ::dependencyLicenseOrigin,
    )

    private fun dependencyLicenseOrigin(): String? {
        val taskPath = dependencyLicenseTaskPath.orNull ?: return null
        val taskOutput = dependencyLicenseTaskOutput.orNull ?: return null
        if (dependencyLicense.get().asFile.absolutePath != taskOutput) return null
        return taskOutcomes.orNull?.originOf(taskPath)
    }
}
