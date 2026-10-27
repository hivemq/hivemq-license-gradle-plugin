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

import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.time.DateTimeException
import java.time.Instant
import javax.inject.Inject

/**
 * Decides the timestamp of the SBOM when `hivemqLicense.sbomTimestamp` is not set: `SOURCE_DATE_EPOCH`, else the
 * time of the repository's HEAD commit, else the current time. A value source keeps git and the environment out
 * of the configuration-cache entry and only warns when the fallback to the current time really happens.
 */
abstract class SbomTimestampSource : ValueSource<Instant, SbomTimestampSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        /** `SOURCE_DATE_EPOCH`, absent when the variable is not set. */
        val sourceDateEpoch: Property<String>
        val projectDirectory: DirectoryProperty

        /** Only named in the warning. */
        val projectPath: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): Instant {
        // a CI template that passes on an unset variable exports it empty
        parameters.sourceDateEpoch.orNull?.takeIf { it.isNotBlank() }?.let { return parseSourceDateEpoch(it) }
        val directory = parameters.projectDirectory.get().asFile
        // log.showSignature=true would print the signature check before the time
        val git = GitCommand.run(execOperations, directory, "log", "-1", "--no-show-signature", "--format=%ct")
        val reason = when {
            git == null -> "git could not be started in $directory"
            git.exitCode != 0 -> "git log in $directory exited with ${git.exitCode}: ${git.stderr}"
            else -> git.stdout.toLongOrNull()?.let { return Instant.ofEpochSecond(it) }
                ?: "git log in $directory printed '${git.stdout}' instead of a commit time"
        }
        val project = parameters.projectPath.get()
        logger.warn(
            "Neither SOURCE_DATE_EPOCH nor a git commit time is available for project '$project', the SBOM " +
                "timestamp is the current time and the file is not reproducible ($reason); set " +
                "hivemqLicense.sbomTimestamp to fix it"
        )
        return Instant.now()
    }

    private fun parseSourceDateEpoch(raw: String): Instant {
        val instant = try {
            raw.trim().toLongOrNull()?.let(Instant::ofEpochSecond)
        } catch (_: DateTimeException) {
            // a value in nanoseconds lies beyond the last instant Java can represent
            null
        }
        return instant ?: throw GradleException(
            "SOURCE_DATE_EPOCH must be the number of seconds since 1970-01-01T00:00:00Z, but is '$raw'"
        )
    }

    private companion object {
        val logger: Logger = Logging.getLogger(SbomTimestampSource::class.java)
    }
}
