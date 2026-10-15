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
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.OperationDescriptor
import org.gradle.tooling.events.PluginIdentifier
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskOperationDescriptor
import org.gradle.tooling.events.task.TaskOperationResult
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class BomTaskOutcomeServiceTest {

    private val service = object : BomTaskOutcomeService() {
        override fun getParameters(): BuildServiceParameters.None = throw UnsupportedOperationException()
    }

    private fun taskFinished(taskPath: String, result: TaskOperationResult) = object : TaskFinishEvent {
        override fun getEventTime() = 0L
        override fun getDisplayName() = "Task $taskPath finished"
        override fun getDescriptor() = object : TaskOperationDescriptor {
            override fun getName() = taskPath
            override fun getDisplayName() = "Task $taskPath"
            override fun getParent(): OperationDescriptor? = null
            override fun getTaskPath() = taskPath
            override fun getDependencies() = emptySet<OperationDescriptor>()
            override fun getOriginPlugin(): PluginIdentifier? = null
        }
        override fun getResult() = result
    }

    private fun success(fromCache: Boolean, upToDate: Boolean) = object : TaskSuccessResult {
        override fun getStartTime() = 0L
        override fun getEndTime() = 0L
        override fun isUpToDate() = upToDate
        override fun isFromCache() = fromCache
        override fun isIncremental() = false
        override fun getExecutionReasons() = emptyList<String>()
    }

    private fun skipped() = object : TaskSkippedResult {
        override fun getStartTime() = 0L
        override fun getEndTime() = 0L
        override fun getSkipMessage() = "SKIPPED"
    }

    private fun failure() = object : TaskFailureResult {
        override fun getStartTime() = 0L
        override fun getEndTime() = 0L
        override fun getFailures() = emptyList<org.gradle.tooling.Failure>()
        override fun isIncremental() = false
        override fun getExecutionReasons() = emptyList<String>()
    }

    private fun originWithin(limit: Duration, taskPath: String): String? {
        val start = Instant.now()
        val origin = service.originOf(taskPath)
        assertThat(Duration.between(start, Instant.now())).isLessThan(limit)
        return origin
    }

    @Test
    fun `an executed task is recorded before its finish event arrives`() {
        service.recordExecuted(":cyclonedxDirectBom")

        assertThat(originWithin(Duration.ofMillis(500), ":cyclonedxDirectBom")).isEqualTo("produced by this build")
    }

    @Test
    fun `a task restored from the cache is known from its finish event`() {
        service.onFinish(taskFinished(":cyclonedxDirectBom", success(fromCache = true, upToDate = false)))

        assertThat(originWithin(Duration.ofMillis(500), ":cyclonedxDirectBom")).isEqualTo("restored from the build cache")
    }

    @Test
    fun `an up-to-date task is known from its finish event`() {
        service.onFinish(taskFinished(":cyclonedxDirectBom", success(fromCache = false, upToDate = true)))

        assertThat(originWithin(Duration.ofMillis(500), ":cyclonedxDirectBom")).isEqualTo("unchanged since an earlier build")
    }

    @Test
    fun `a skipped task has no origin and does not make the caller wait`() {
        service.onFinish(taskFinished(":cyclonedxDirectBom", skipped()))

        assertThat(originWithin(Duration.ofMillis(500), ":cyclonedxDirectBom")).isNull()
    }

    @Test
    fun `a failed task has no origin and does not make the caller wait`() {
        service.onFinish(taskFinished(":cyclonedxDirectBom", failure()))

        assertThat(originWithin(Duration.ofMillis(500), ":cyclonedxDirectBom")).isNull()
    }

    @Test
    fun `a task that never finishes has no origin after the bounded wait`() {
        val start = Instant.now()

        assertThat(service.originOf(":cyclonedxDirectBom")).isNull()

        assertThat(Duration.between(start, Instant.now())).isGreaterThanOrEqualTo(Duration.ofSeconds(2))
    }

    @Test
    fun `outcomes are kept per task path`() {
        service.recordExecuted(":cyclonedxDirectBom")
        service.onFinish(taskFinished(":inc:cyclonedxDirectBom", success(fromCache = true, upToDate = false)))

        assertThat(service.originOf(":cyclonedxDirectBom")).isEqualTo("produced by this build")
        assertThat(service.originOf(":inc:cyclonedxDirectBom")).isEqualTo("restored from the build cache")
    }
}
