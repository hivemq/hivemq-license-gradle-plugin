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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class LicenseTest {

    // --- KnownLicense enum properties ---

    @ParameterizedTest
    @EnumSource(KnownLicense::class)
    fun `every KnownLicense has non-blank id`(license: KnownLicense) {
        assertThat(license.id).isNotBlank()
    }

    @ParameterizedTest
    @EnumSource(KnownLicense::class)
    fun `every KnownLicense has non-blank fullName`(license: KnownLicense) {
        assertThat(license.fullName).isNotBlank()
    }

    @ParameterizedTest
    @EnumSource(KnownLicense::class)
    fun `every KnownLicense has non-null url`(license: KnownLicense) {
        assertThat(license.url).isNotNull()
    }

    @ParameterizedTest
    @EnumSource(KnownLicense::class)
    fun `every KnownLicense implements License interface`(license: KnownLicense) {
        val asLicense: License = license
        assertThat(asLicense.fullName).isEqualTo(license.fullName)
        assertThat(asLicense.url).isEqualTo(license.url)
    }

    // --- Specific KnownLicense values ---

    @Test
    fun `APACHE_2_0 has correct properties`() {
        assertThat(KnownLicense.APACHE_2_0.id).isEqualTo("Apache-2.0")
        assertThat(KnownLicense.APACHE_2_0.fullName).isEqualTo("Apache License 2.0")
        assertThat(KnownLicense.APACHE_2_0.url).isEqualTo("https://spdx.org/licenses/Apache-2.0.html")
    }

    @Test
    fun `MIT has correct properties`() {
        assertThat(KnownLicense.MIT.id).isEqualTo("MIT")
        assertThat(KnownLicense.MIT.fullName).isEqualTo("MIT License")
        assertThat(KnownLicense.MIT.url).isEqualTo("https://spdx.org/licenses/MIT.html")
    }

    @Test
    fun `MIT_0 has correct properties`() {
        assertThat(KnownLicense.MIT_0.id).isEqualTo("MIT-0")
        assertThat(KnownLicense.MIT_0.fullName).isEqualTo("MIT No Attribution")
    }

    @Test
    fun `BOUNCY_CASTLE has MIT SPDX id`() {
        assertThat(KnownLicense.BOUNCY_CASTLE.id).isEqualTo("MIT")
        assertThat(KnownLicense.BOUNCY_CASTLE.fullName).isEqualTo("Bouncy Castle Licence")
    }

    @Test
    fun `EDL_1_0 has BSD-3-Clause SPDX id`() {
        assertThat(KnownLicense.EDL_1_0.id).isEqualTo("BSD-3-Clause")
        assertThat(KnownLicense.EDL_1_0.fullName).isEqualTo("Eclipse Distribution License - v 1.0")
    }

    @Test
    fun `GO has BSD-3-Clause SPDX id`() {
        assertThat(KnownLicense.GO.id).isEqualTo("BSD-3-Clause")
        assertThat(KnownLicense.GO.fullName).isEqualTo("Go License")
    }

    @Test
    fun `PUBLIC_DOMAIN has empty url`() {
        assertThat(KnownLicense.PUBLIC_DOMAIN.id).isEqualTo("Public Domain")
        assertThat(KnownLicense.PUBLIC_DOMAIN.url).isEmpty()
    }

    @Test
    fun `enum has expected number of values`() {
        assertThat(KnownLicense.entries).hasSize(23)
    }

    // --- UnknownLicense ---

    @Test
    fun `UnknownLicense stores fullName and url`() {
        val license = UnknownLicense("LGPL-3.0", "https://example.com")
        assertThat(license.fullName).isEqualTo("LGPL-3.0")
        assertThat(license.url).isEqualTo("https://example.com")
    }

    @Test
    fun `UnknownLicense with null url`() {
        val license = UnknownLicense("Proprietary", null)
        assertThat(license.fullName).isEqualTo("Proprietary")
        assertThat(license.url).isNull()
    }

    @Test
    fun `UnknownLicense implements License interface`() {
        val license: License = UnknownLicense("Custom", "https://custom.com")
        assertThat(license.fullName).isEqualTo("Custom")
        assertThat(license.url).isEqualTo("https://custom.com")
    }

    @Test
    fun `UnknownLicense data class equality`() {
        val a = UnknownLicense("MIT", "https://a.com")
        val b = UnknownLicense("MIT", "https://a.com")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `UnknownLicense data class copy`() {
        val original = UnknownLicense("MIT", "https://a.com")
        val copy = original.copy(fullName = "BSD")
        assertThat(copy.fullName).isEqualTo("BSD")
        assertThat(copy.url).isEqualTo("https://a.com")
    }
}
