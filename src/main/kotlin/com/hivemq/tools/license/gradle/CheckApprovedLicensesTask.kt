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

import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Validates that all dependencies in the CycloneDX BOM have approved licenses.
 * Does not produce any output files.
 */
@DisableCachingByDefault(because = "Validation task that should always run")
abstract class CheckApprovedLicensesTask : LicenseResolvingTask() {

    init {
        // always run - no outputs means Gradle would consider it UP-TO-DATE otherwise
        outputs.upToDateWhen { false }
    }

    @TaskAction
    protected fun run() {
        val entries = resolveLicenses()
        logger.lifecycle("All ${entries.size} dependencies have approved licenses.")
    }
}
