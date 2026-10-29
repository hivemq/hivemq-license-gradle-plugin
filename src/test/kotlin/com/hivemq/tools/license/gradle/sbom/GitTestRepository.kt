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

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Runs git inside temporary test directories.
 *
 * The environment is isolated, as the machine's global git config, hooks, inherited `GIT_*` variables and an
 * enclosing repository would otherwise leak into the tests and change their outcome.
 */
internal object GitTestRepository {

    /**
     * This JVM's environment without the machine's git settings and `SOURCE_DATE_EPOCH`, plus [extra]. The ceiling
     * keeps git from treating a temporary directory inside some repository as part of it.
     */
    fun isolatedEnv(projectDir: File, extra: Map<String, String> = emptyMap()): Map<String, String> {
        // an empty home hides the global config from any git version, GIT_CONFIG_GLOBAL needs git 2.32 or later
        val home = projectDir.resolve(".home").apply { mkdirs() }
        return System.getenv().filterKeys { !it.startsWith("GIT_") && it != "SOURCE_DATE_EPOCH" } + mapOf(
            "HOME" to home.absolutePath,
            "XDG_CONFIG_HOME" to home.resolve(".config").absolutePath,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CEILING_DIRECTORIES" to projectDir.parent,
            "GIT_AUTHOR_NAME" to "test",
            "GIT_AUTHOR_EMAIL" to "test@example.com",
            "GIT_COMMITTER_NAME" to "test",
            "GIT_COMMITTER_EMAIL" to "test@example.com",
            // untranslated git messages, which the tests assert on
            "LC_ALL" to "C",
        ) + extra
    }

    /** Runs `git [args]` in [dir] with the isolated environment plus [env]; fails with the output on error. */
    fun git(dir: File, vararg args: String, env: Map<String, String> = emptyMap()) {
        val process = ProcessBuilder("git", *args)
            .directory(dir)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(isolatedEnv(dir, env))
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(1, TimeUnit.MINUTES) && process.exitValue() == 0) {
            "git ${args.joinToString(" ")} failed: $output"
        }
    }

    /**
     * Turns [dir] into a repository (if needed) with one commit of [fileName], authored and committed at [timestamp].
     * The file is written with placeholder content when it does not exist yet.
     */
    fun commitAt(dir: File, timestamp: String, fileName: String) {
        val file = dir.resolve(fileName)
        if (!file.exists()) {
            file.writeText("content")
        }
        git(dir, "init", "-q")
        git(dir, "add", fileName)
        git(
            dir, "commit", "-q", "-m", "init",
            env = mapOf("GIT_AUTHOR_DATE" to timestamp, "GIT_COMMITTER_DATE" to timestamp),
        )
    }

    /** Adds [url] as the `origin` remote of the repository in [dir]. */
    fun setRemote(dir: File, url: String) {
        git(dir, "remote", "add", "origin", url)
    }

    /**
     * Gradle's real exec from [project], running every command in the isolated environment of [dir] and then applying
     * [adjust]. Value sources start git through it, and a test that calls them without a build passes this one in.
     */
    fun isolatedExec(project: Project, dir: File, adjust: ExecSpec.() -> Unit = {}): ExecOperations {
        val delegate = project.objects.newInstance(ExecHolder::class.java).execOperations
        return object : ExecOperations by delegate {
            override fun exec(action: Action<in ExecSpec>): ExecResult = delegate.exec {
                action.execute(this)
                setEnvironment(isolatedEnv(dir))
                adjust()
            }
        }
    }

    abstract class ExecHolder @Inject constructor(val execOperations: ExecOperations)
}
