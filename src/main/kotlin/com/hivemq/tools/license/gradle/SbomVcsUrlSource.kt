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

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * The `origin` remote of the repository the project lives in, in the form [SbomNormalizer.canonicalVcsUrl] gives it,
 * absent when there is none. CycloneDX reads `GIT_URL` or the remote of the Gradle daemon's working directory
 * instead, which in a composite build is another repository.
 */
abstract class SbomVcsUrlSource : ValueSource<String, SbomVcsUrlSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val projectDirectory: DirectoryProperty
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String? {
        val directory = parameters.projectDirectory.get().asFile
        val git = GitCommand.run(execOperations, directory, "remote", "get-url", "origin")
        val reason = when {
            git == null -> "git could not be started"
            git.exitCode != 0 -> "git remote get-url origin exited with ${git.exitCode}: ${git.stderr}"
            git.stdout.isEmpty() -> "git remote get-url origin printed nothing"
            // the remote may carry a token, so it is neither returned as it is nor logged
            else -> SbomNormalizer.canonicalVcsUrl(git.stdout)?.let { return it }
                ?: "the origin remote is not an http(s) or ssh URL"
        }
        logger.info("The SBOM of $directory has no vcs reference: $reason")
        return null
    }

    private companion object {
        val logger: Logger = Logging.getLogger(SbomVcsUrlSource::class.java)
    }
}
