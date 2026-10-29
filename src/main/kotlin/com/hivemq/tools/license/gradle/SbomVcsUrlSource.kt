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
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * The `origin` remote of the repository the project lives in, in the form [SbomNormalizer.canonicalVcsUrl] gives it,
 * absent when there is none. CycloneDX reads `GIT_URL` or the remote of the Gradle daemon's working directory
 * instead, which in a composite build is another repository.
 */
internal abstract class SbomVcsUrlSource : ValueSource<String, SbomVcsUrlSource.Parameters> {

    internal interface Parameters : ValueSourceParameters {
        val projectDirectory: DirectoryProperty

        /** Only named in the log messages. */
        val projectPath: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String? {
        val directory = parameters.projectDirectory.get().asFile
        val project = parameters.projectPath.get()
        val git = GitCommand.run(execOperations, directory, "remote", "get-url", "origin")
        // a source tree outside any repository is expected, the timestamp source already warns about it
        val outsideRepository = git != null && git.exitCode != 0 && git.stderr.contains("not a git repository")
        if (git == null || (git.exitCode != 0 && git.exitCode != NO_SUCH_REMOTE && !outsideRepository)) {
            val failure = git?.let { "git remote get-url origin exited with ${it.exitCode}: ${it.stderr}" }
                ?: "git could not be started"
            // the SBOM ships without a vcs reference, and nothing else would show that
            logger.warn("The SBOM of project '$project' has no vcs reference: $failure")
            return null
        }
        val reason = when {
            outsideRepository -> "not in a git repository"
            git.exitCode == NO_SUCH_REMOTE -> "no origin remote"
            git.stdout.isEmpty() -> "git remote get-url origin printed nothing"
            // the remote may carry a token, so it is neither returned as it is nor logged
            else -> SbomNormalizer.canonicalVcsUrl(git.stdout)?.let { return it }
                ?: "the origin remote is not an http(s) or ssh URL"
        }
        logger.info("The SBOM of project '$project' has no vcs reference: $reason")
        return null
    }

    private companion object {
        val logger: Logger = Logging.getLogger(SbomVcsUrlSource::class.java)

        // what git remote get-url exits with when the remote does not exist
        const val NO_SUCH_REMOTE = 2
    }
}
