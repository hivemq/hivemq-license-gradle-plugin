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
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File

/** Runs git for the value sources that read the project's repository. */
internal object GitCommand {

    private val logger: Logger = Logging.getLogger(GitCommand::class.java)

    /** Runs `git [args]` in [dir]; null when git could not be started, for example because it is not installed. */
    fun run(exec: ExecOperations, dir: File, vararg args: String): GitResult? {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = try {
            exec.exec {
                workingDir = dir
                commandLine("git", *args)
                standardOutput = stdout
                errorOutput = stderr
                isIgnoreExitValue = true
            }
        } catch (e: GradleException) {
            // A process that cannot be started fails with a GradleException. The subclass Gradle 9 throws,
            // ProcessExecutionException, does not exist in Gradle 8, so catching it would break there. A non-zero
            // exit value does not throw because it is ignored above.
            logger.info("Cannot start git in $dir: ${e.message}")
            return null
        }
        return GitResult(result.exitValue, stdout.toString().trim(), stderr.toString().trim())
    }
}

/** What a git command printed, trimmed. */
internal data class GitResult(val exitCode: Int, val stdout: String, val stderr: String)
