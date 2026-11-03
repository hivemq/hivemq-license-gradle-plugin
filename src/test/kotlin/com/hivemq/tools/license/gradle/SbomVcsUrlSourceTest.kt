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
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SbomVcsUrlSourceTest {

    @TempDir
    lateinit var projectDir: File

    private val project by lazy { ProjectBuilder.builder().withProjectDir(projectDir).build() }

    /** Runs the value source on Gradle's real exec, outside a build, with [adjust] applied to every git command. */
    private fun obtain(adjust: ExecSpec.() -> Unit = {}): String? {
        val sourceParameters = project.objects.newInstance(SbomVcsUrlSource.Parameters::class.java).apply {
            projectDirectory.set(projectDir)
        }
        return object : SbomVcsUrlSource() {
            override val execOperations: ExecOperations = GitTestRepository.isolatedExec(project, projectDir, adjust)

            override fun getParameters() = sourceParameters
        }.obtain()
    }

    // --- Remote ---

    @Test
    fun `returns the origin remote in its canonical form`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        GitTestRepository.setRemote(projectDir, "https://github.com/hivemq/x.git")

        assertThat(obtain()).isEqualTo("https://github.com/hivemq/x")
    }

    @Test
    fun `strips a credential from the origin remote`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        GitTestRepository.setRemote(projectDir, "https://x-access-token:SECRET@github.com/hivemq/x.git")

        assertThat(obtain()).isEqualTo("https://github.com/hivemq/x")
    }

    // --- Fallback ---

    @Test
    fun `is absent for a local remote`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        GitTestRepository.setRemote(projectDir, projectDir.resolve("mirror").absolutePath)

        assertThat(obtain()).isNull()
    }

    @Test
    fun `is absent outside a repository`() {
        assertThat(obtain()).isNull()
    }

    @Test
    fun `is absent when git is not installed`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        GitTestRepository.setRemote(projectDir, "git@github.com:hivemq/x.git")
        // the same setup finds the remote while git can be started
        assertThat(obtain()).isEqualTo("https://github.com/hivemq/x")

        val url = obtain { executable = projectDir.resolve("missing/git").absolutePath }

        assertThat(url).isNull()
    }

    @Test
    fun `is absent without a remote`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")

        assertThat(obtain()).isNull()
    }
}
