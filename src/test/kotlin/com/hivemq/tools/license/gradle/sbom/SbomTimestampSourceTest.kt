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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class SbomTimestampSourceTest {

    @TempDir
    lateinit var projectDir: File

    private val project by lazy { ProjectBuilder.builder().withProjectDir(projectDir).build() }

    /** Runs the value source on Gradle's real exec, outside a build, with [adjust] applied to every git command. */
    private fun obtain(sourceDateEpoch: String? = null, adjust: ExecSpec.() -> Unit = {}): Instant {
        val sourceParameters = project.objects.newInstance(SbomTimestampSource.Parameters::class.java).apply {
            this.sourceDateEpoch.set(sourceDateEpoch)
            projectDirectory.set(projectDir)
            projectPath.set(project.path)
        }
        return object : SbomTimestampSource() {
            override val execOperations: ExecOperations = GitTestRepository.isolatedExec(project, projectDir, adjust)

            override fun getParameters() = sourceParameters
        }.obtain()
    }

    // --- Git ---

    @Test
    fun `falls back to the current time when git is not installed`() {
        // TestKit cannot take git away: with PATH set to an empty directory through withEnvironment, the Gradle daemon
        // still started git. So the value source runs here on Gradle's real exec, pointed at a binary that is missing.
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        // the same setup finds the commit while git can be started
        assertThat(obtain()).isEqualTo(Instant.parse("2024-05-06T07:08:09Z"))
        val before = Instant.now()

        val timestamp = obtain { executable = projectDir.resolve("missing/git").absolutePath }

        assertThat(timestamp).isBetween(before, Instant.now())
    }

    // --- SOURCE_DATE_EPOCH ---

    @Test
    fun `a blank SOURCE_DATE_EPOCH counts as unset`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")

        val timestamp = obtain(sourceDateEpoch = " ")

        assertThat(timestamp).isEqualTo(Instant.parse("2024-05-06T07:08:09Z"))
    }

    @Test
    fun `a SOURCE_DATE_EPOCH in nanoseconds fails`() {
        // fits in a long, but as seconds it lies beyond the last instant Java can represent
        assertThatThrownBy { obtain(sourceDateEpoch = "1700000000000000000") }
            .isInstanceOf(GradleException::class.java)
            .hasMessage(
                "SOURCE_DATE_EPOCH must be the number of seconds since 1970-01-01T00:00:00Z, but is " +
                    "'1700000000000000000'"
            )
    }

    @Test
    fun `a SOURCE_DATE_EPOCH that is not a number fails`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")

        assertThatThrownBy { obtain(sourceDateEpoch = "yesterday") }
            .isInstanceOf(GradleException::class.java)
            .hasMessage(
                "SOURCE_DATE_EPOCH must be the number of seconds since 1970-01-01T00:00:00Z, but is 'yesterday'"
            )
    }
}
