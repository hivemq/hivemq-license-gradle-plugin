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

import org.gradle.api.GradleException
import java.time.Instant
import java.time.format.DateTimeParseException

/** The timestamp input of the SBOM tasks: text, because Gradle 8's configuration cache cannot store an `Instant`. */
internal object SbomTimestamp {

    /** [text] as an instant; anything else fails the task at [taskPath] with the property's requirement. */
    fun parse(taskPath: String, text: String): Instant = try {
        Instant.parse(text)
    } catch (e: DateTimeParseException) {
        throw GradleException(
            "The timestamp of task '$taskPath' must be an ISO 8601 instant such as 2026-01-02T03:04:05Z, " +
                "but is '$text'",
            e,
        )
    }
}
