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
import org.junit.jupiter.api.Test

class LicenseResolverShouldIgnoreTest {

    private val defaultIgnoredPrefixes = setOf("com.hivemq")
    private val defaultAllowedArtifacts = setOf("com.hivemq:hivemq-mqtt-client")

    @Test
    fun `ignores com_hivemq artifact`() {
        val coordinates = Coordinates("com.hivemq", "hivemq-extension-sdk", "4.0.0")
        assertThat(LicenseResolver.shouldIgnore(coordinates, defaultIgnoredPrefixes, defaultAllowedArtifacts)).isTrue()
    }

    @Test
    fun `ignores com_hivemq sub-group artifact`() {
        val coordinates = Coordinates("com.hivemq.internal", "some-module", "1.0.0")
        assertThat(LicenseResolver.shouldIgnore(coordinates, defaultIgnoredPrefixes, defaultAllowedArtifacts)).isTrue()
    }

    @Test
    fun `does not ignore allowed artifact`() {
        val coordinates = Coordinates("com.hivemq", "hivemq-mqtt-client", "1.3.0")
        assertThat(LicenseResolver.shouldIgnore(coordinates, defaultIgnoredPrefixes, defaultAllowedArtifacts)).isFalse()
    }

    @Test
    fun `does not ignore third-party artifact`() {
        val coordinates = Coordinates("com.fasterxml.jackson.core", "jackson-databind", "2.15.0")
        assertThat(LicenseResolver.shouldIgnore(coordinates, defaultIgnoredPrefixes, defaultAllowedArtifacts)).isFalse()
    }

    @Test
    fun `ignores nothing with empty prefixes`() {
        val coordinates = Coordinates("com.hivemq", "hivemq-extension-sdk", "4.0.0")
        assertThat(LicenseResolver.shouldIgnore(coordinates, emptySet(), emptySet())).isFalse()
    }

    @Test
    fun `uses custom ignored prefixes`() {
        val coordinates = Coordinates("org.internal", "my-lib", "1.0.0")
        assertThat(LicenseResolver.shouldIgnore(coordinates, setOf("org.internal"), emptySet())).isTrue()
    }

    @Test
    fun `allowed artifact overrides ignored prefix`() {
        val coordinates = Coordinates("org.internal", "public-api", "1.0.0")
        assertThat(
            LicenseResolver.shouldIgnore(
                coordinates, setOf("org.internal"), setOf("org.internal:public-api")
            )
        ).isFalse()
    }
}
