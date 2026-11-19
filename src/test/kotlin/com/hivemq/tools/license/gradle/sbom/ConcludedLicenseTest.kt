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

import com.hivemq.tools.license.gradle.Coordinates
import com.hivemq.tools.license.gradle.KnownLicense
import com.hivemq.tools.license.gradle.ResolvedDependency
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConcludedLicenseTest {

    private val coordinates = Coordinates("org.example", "lib", "1.0")

    @Test
    fun `is the declared expression when the declared licenses name more than one`() {
        val expression = "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0"
        val dependency = ResolvedDependency(coordinates, listOf(KnownLicense.EPL_2_0), expression)

        assertThat(ConcludedLicense.of(dependency)).isEqualTo(ConcludedLicense.Expression(expression))
    }

    @Test
    fun `is the elected licenses by id without a declared expression`() {
        val dependency = ResolvedDependency(coordinates, listOf(KnownLicense.MIT, KnownLicense.ISC))

        assertThat(ConcludedLicense.of(dependency)).isEqualTo(ConcludedLicense.Elected(listOf("MIT", "ISC")))
    }

    @Test
    fun `keeps an id that is not SPDX for the enricher to write by name`() {
        val dependency = ResolvedDependency(coordinates, listOf(KnownLicense.PUBLIC_DOMAIN))

        assertThat(ConcludedLicense.of(dependency)).isEqualTo(ConcludedLicense.Elected(listOf("Public Domain")))
    }
}
