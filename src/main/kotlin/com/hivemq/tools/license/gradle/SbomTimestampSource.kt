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
import java.io.ByteArrayOutputStream
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
        parameters.sourceDateEpoch.orNull?.let { return parseSourceDateEpoch(it) }
        lastCommitTime()?.let { return it }
        logger.warn(
            "Neither SOURCE_DATE_EPOCH nor a git commit time is available for ${parameters.projectPath.get()}, the " +
                "SBOM timestamp is the current time and the file is not reproducible; set " +
                "hivemqLicense.sbomTimestamp to fix it"
        )
        return Instant.now()
    }

    private fun parseSourceDateEpoch(raw: String): Instant =
        raw.trim().toLongOrNull()?.let(Instant::ofEpochSecond) ?: throw GradleException(
            "SOURCE_DATE_EPOCH must be the number of seconds since 1970-01-01T00:00:00Z, but is '$raw'"
        )

    /** The committer time of HEAD, null when the directory is not in a repository, has no commit or git is missing. */
    private fun lastCommitTime(): Instant? {
        val directory = parameters.projectDirectory.get().asFile
        val output = ByteArrayOutputStream()
        val result = try {
            execOperations.exec {
                workingDir = directory
                commandLine("git", "log", "-1", "--format=%ct")
                standardOutput = output
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
        } catch (e: GradleException) {
            // A process that cannot be started fails with a GradleException. The subclass Gradle 9 throws,
            // ProcessExecutionException, does not exist in Gradle 8, so catching it would break there. A non-zero
            // exit value does not throw because it is ignored above.
            logger.info("git is not available in $directory, falling back: ${e.message}")
            return null
        }
        if (result.exitValue != 0) {
            return null
        }
        return output.toString().trim().toLongOrNull()?.let(Instant::ofEpochSecond)
    }

    private companion object {
        val logger: Logger = Logging.getLogger(SbomTimestampSource::class.java)
    }
}
