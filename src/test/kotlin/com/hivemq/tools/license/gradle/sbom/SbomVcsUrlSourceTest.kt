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
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

class SbomVcsUrlSourceTest {

    @TempDir
    lateinit var projectDir: File

    private val project by lazy { ProjectBuilder.builder().withProjectDir(projectDir).build() }

    /**
     * Runs the value source on Gradle's real exec, outside a build, with [environment] added to the environment of
     * every git command and then [adjust] applied to it.
     */
    private fun obtain(environment: Map<String, String> = emptyMap(), adjust: ExecSpec.() -> Unit = {}): String? {
        val sourceParameters = project.objects.newInstance(SbomVcsUrlSource.Parameters::class.java).apply {
            projectDirectory.set(projectDir)
            projectPath.set(project.path)
        }
        return object : SbomVcsUrlSource() {
            override val execOperations: ExecOperations =
                GitTestRepository.isolatedExec(project, projectDir, environment, adjust)

            override fun getParameters() = sourceParameters
        }.obtain()
    }

    // --- Remote ---

    @Test
    fun `reads a remote from a file the repository configuration includes`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        projectDir.resolve("remote.cfg").writeText("[remote \"origin\"]\n\turl = https://github.com/hivemq/x.git\n")
        GitTestRepository.git(projectDir, "config", "include.path", "../remote.cfg")

        assertThat(obtain()).isEqualTo("https://github.com/hivemq/x")
    }

    @Test
    fun `reads the first URL of a remote that has several`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        GitTestRepository.setRemote(projectDir, "https://github.com/hivemq/x.git")
        GitTestRepository.git(projectDir, "remote", "set-url", "--add", "origin", "https://github.com/hivemq/y.git")

        assertThat(obtain()).isEqualTo("https://github.com/hivemq/x")
    }

    @Test
    fun `reads the remote as configured, not as the machine's insteadOf rules rewrite it`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        GitTestRepository.setRemote(projectDir, "https://github.com/hivemq/x.git")
        val rewrite = "url.ssh://git@mirror.example.com/.insteadOf"
        GitTestRepository.git(projectDir, "config", "--global", rewrite, "https://github.com/")
        // the rule is in force: git itself would fetch from the mirror
        assertThat(GitTestRepository.git(projectDir, "remote", "get-url", "origin"))
            .isEqualTo("ssh://git@mirror.example.com/hivemq/x.git")

        assertThat(obtain()).isEqualTo("https://github.com/hivemq/x")
    }

    @ParameterizedTest
    @CsvSource("GIT_COMMON_DIR, .git", "GIT_CONFIG, .git/config", "GIT_DIR, .git")
    fun `reads the remote of the project's repository when the environment names another one`(
        variable: String,
        path: String,
        @TempDir otherRepository: File,
    ) {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        GitTestRepository.setRemote(projectDir, "https://github.com/hivemq/x.git")
        GitTestRepository.commitAt(otherRepository, "2023-01-02T03:04:05Z", "file.txt")
        GitTestRepository.setRemote(otherRepository, "https://github.com/other/y.git")

        val url = obtain(environment = mapOf(variable to otherRepository.resolve(path).absolutePath))

        assertThat(url).isEqualTo("https://github.com/hivemq/x")
    }

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
    fun `ignores a remote URL from the global config`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        GitTestRepository.git(projectDir, "config", "--global", "remote.origin.url", "https://github.com/hivemq/x.git")
        // git itself sees the value from inside the repository
        assertThat(GitTestRepository.git(projectDir, "config", "--get", "remote.origin.url"))
            .isEqualTo("https://github.com/hivemq/x.git")

        assertThat(obtain()).isNull()
    }

    @Test
    fun `is absent for a local remote`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        GitTestRepository.setRemote(projectDir, projectDir.resolve("mirror").absolutePath)

        assertThat(obtain()).isNull()
    }

    @Test
    fun `is absent for an empty remote URL`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        GitTestRepository.git(projectDir, "config", "remote.origin.url", "")

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
    fun `is absent when the repository cannot be read`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")
        GitTestRepository.setRemote(projectDir, "https://github.com/hivemq/x.git")
        projectDir.resolve(".git/config").appendText("[remote \"origin\"\n")

        assertThat(obtain()).isNull()
    }

    @Test
    fun `is absent without a remote`() {
        GitTestRepository.commitAt(projectDir, "2024-05-06T07:08:09Z", "file.txt")

        assertThat(obtain()).isNull()
    }
}
