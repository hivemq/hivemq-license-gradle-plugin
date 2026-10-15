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

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSuccessResult
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Records how each task of the build obtained its outputs, so the license tasks can say whether the BOM they read was
 * produced by this build, restored from the build cache, or left over from an earlier build.
 *
 * A task cannot see another task's outcome through its inputs, so [HivemqLicensePlugin] feeds this service from two
 * sides. A `doFirst` action on the BOM task calls [recordExecuted] synchronously, which only happens when the task
 * actually runs. For a task Gradle skipped, only the task finish event tells whether it was restored from the cache or
 * up to date, and Gradle delivers those events asynchronously, "some time soon" after the task completed. [originOf]
 * therefore waits a bounded moment for the event and gives up without an origin if it does not arrive.
 */
abstract class BomTaskOutcomeService : BuildService<BuildServiceParameters.None>, OperationCompletionListener {

    private val origins = ConcurrentHashMap<String, CompletableFuture<String>>()

    private fun originFuture(taskPath: String) = origins.computeIfAbsent(taskPath) { CompletableFuture() }

    /** Called from the task's own action, so it is known before any dependent task starts. */
    fun recordExecuted(taskPath: String) {
        originFuture(taskPath).complete(PRODUCED_BY_THIS_BUILD)
    }

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val result = event.result as? TaskSuccessResult ?: return
        originFuture(event.descriptor.taskPath).complete(
            when {
                result.isFromCache -> "restored from the build cache"
                result.isUpToDate -> "unchanged since an earlier build"
                else -> PRODUCED_BY_THIS_BUILD
            }
        )
    }

    /** How the task at [taskPath] obtained its outputs, or null if that is not known within [EVENT_WAIT]. */
    fun originOf(taskPath: String): String? = try {
        originFuture(taskPath).get(EVENT_WAIT.toMillis(), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    }

    companion object {
        const val NAME = "hivemqLicenseBomTaskOutcomes"
        private const val PRODUCED_BY_THIS_BUILD = "produced by this build"
        private val EVENT_WAIT: Duration = Duration.ofSeconds(2)
    }
}
