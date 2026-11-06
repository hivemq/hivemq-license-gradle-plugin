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
package com.hivemq.tools.license.gradle.sbom

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * The `origin` remote of the repository the project lives in, as configured there (the first URL when it has several)
 * and in the form [SbomNormalizer.canonicalVcsUrl] gives it; absent when there is none.
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
        // the configured URL, because git remote get-url applies the machine's url.<base>.insteadOf rewrites, which
        // would make the SBOM differ between machines that clone the same repository; --local keeps other config out
        val git = GitCommand.run(execOperations, directory, "config", "--local", "--includes", "--get-all", URL_KEY)
            ?: return failed(project, "git could not be started")
        if (git.exitCode != 0 && git.exitCode != NOT_SET) {
            // --local fails the same way outside a repository, which is expected, and in one git refuses to open
            val failure = repositoryFailure(directory, git) ?: return absent(project, "not in a git repository")
            return failed(project, failure)
        }
        // the first URL is the one git fetches from
        val url = git.stdout.substringBefore('\n')
        val reason = when {
            git.exitCode == NOT_SET -> "no origin remote"
            url.isEmpty() -> "the origin remote has an empty URL"
            // the remote may carry a token, so it is neither returned as it is nor logged
            else -> SbomNormalizer.canonicalVcsUrl(url)?.let { return it }
                ?: "the origin remote is not an http(s) or ssh URL"
        }
        return absent(project, reason)
    }

    /** Logs [reason] at info level, as a project without a remote is expected, and gives no vcs reference. */
    private fun absent(project: String, reason: String): String? {
        logger.info("The SBOM of project '$project' has no vcs reference: $reason")
        return null
    }

    /** Warns about [failure], because the SBOM ships without a vcs reference and nothing else would show that. */
    private fun failed(project: String, failure: String): String? {
        logger.warn("The SBOM of project '$project' has no vcs reference: $failure")
        return null
    }

    /**
     * Git's complaint, from `git rev-parse --git-dir`, when it will not open the repository in [directory], such as
     * another user owning it; [config]'s own failure when it opens; null when [directory] is not in a repository.
     */
    private fun repositoryFailure(directory: File, config: GitResult): String? {
        val probe = GitCommand.run(execOperations, directory, "rev-parse", "--git-dir")
            ?: return "git could not be started"
        return when {
            probe.exitCode == 0 -> "git config --local exited with ${config.exitCode}: ${config.stderr}"
            // the search up the directories found nothing; a .git file that points nowhere reads differently
            probe.stderr.contains("not a git repository (or any") -> null
            else -> "git rev-parse --git-dir exited with ${probe.exitCode}: ${probe.stderr}"
        }
    }

    private companion object {
        val logger: Logger = Logging.getLogger(SbomVcsUrlSource::class.java)

        const val URL_KEY = "remote.origin.url"

        // what git config --get-all exits with when the key is not set
        const val NOT_SET = 1
    }
}
