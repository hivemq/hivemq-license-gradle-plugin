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

import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.Action
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import javax.inject.Inject

class SbomTimestampSourceTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `falls back to the current time when git is not installed`() {
        // TestKit cannot take git away: with PATH set to an empty directory through withEnvironment, the Gradle daemon
        // still started git. So the value source runs here on Gradle's real exec, pointed at a binary that is missing.
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val exec = project.objects.newInstance(ExecHolder::class.java).execOperations
        val parameters = project.objects.newInstance(SbomTimestampSource.Parameters::class.java).apply {
            projectDirectory.set(projectDir)
            projectPath.set(project.path)
        }
        fun obtain(adjust: ExecSpec.() -> Unit) = object : SbomTimestampSource() {
            override val execOperations: ExecOperations = AdjustedExec(exec) {
                setEnvironment(GitTestRepository.isolatedEnv(projectDir))
                adjust()
            }

            override fun getParameters() = parameters
        }.obtain()
        // the same setup finds the commit while git can be started
        assertThat(obtain {}).isEqualTo(Instant.parse("2024-05-06T07:08:09Z"))
        val before = Instant.now()

        val timestamp = obtain { executable = projectDir.resolve("missing/git").absolutePath }

        assertThat(timestamp).isBetween(before, Instant.now())
    }

    abstract class ExecHolder @Inject constructor(val execOperations: ExecOperations)

    /** Gradle's exec with [adjust] applied to every command after the caller configured it. */
    private class AdjustedExec(private val delegate: ExecOperations, private val adjust: ExecSpec.() -> Unit) :
        ExecOperations by delegate {
        override fun exec(action: Action<in ExecSpec>): ExecResult = delegate.exec {
            action.execute(this)
            adjust()
        }
    }
}
