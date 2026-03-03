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

class LicenseResolverChooseLicenseTest {

    @Test
    fun `chooses single known license`() {
        assertThat(LicenseResolver.chooseLicense(listOf(KnownLicense.MIT))).isEqualTo(KnownLicense.MIT)
    }

    @Test
    fun `chooses Apache over EPL when both present`() {
        val licenses = listOf(KnownLicense.EPL_2_0, KnownLicense.APACHE_2_0)
        assertThat(LicenseResolver.chooseLicense(licenses)).isEqualTo(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `chooses MIT over BSD_3_CLAUSE`() {
        val licenses = listOf(KnownLicense.BSD_3_CLAUSE, KnownLicense.MIT)
        assertThat(LicenseResolver.chooseLicense(licenses)).isEqualTo(KnownLicense.MIT)
    }

    @Test
    fun `chooses Apache over all others`() {
        val licenses = listOf(
            KnownLicense.CDDL_1_0,
            KnownLicense.EPL_1_0,
            KnownLicense.MIT,
            KnownLicense.APACHE_2_0,
        )
        assertThat(LicenseResolver.chooseLicense(licenses)).isEqualTo(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `returns null for empty list`() {
        assertThat(LicenseResolver.chooseLicense(emptyList())).isNull()
    }

    @Test
    fun `returns null for only unknown licenses`() {
        val licenses = listOf(
            UnknownLicense("LGPL-3.0", "https://example.com"),
            UnknownLicense("Proprietary", null),
        )
        assertThat(LicenseResolver.chooseLicense(licenses)).isNull()
    }

    @Test
    fun `ignores unknown licenses and picks known one`() {
        val licenses = listOf(
            UnknownLicense("LGPL-3.0", "https://example.com"),
            KnownLicense.EPL_2_0,
        )
        assertThat(LicenseResolver.chooseLicense(licenses)).isEqualTo(KnownLicense.EPL_2_0)
    }

    @Test
    fun `follows LICENSE_ORDER priority`() {
        // verify a few pairs from the order
        assertThat(LicenseResolver.chooseLicense(listOf(KnownLicense.BOUNCY_CASTLE, KnownLicense.MIT)))
            .isEqualTo(KnownLicense.MIT)
        assertThat(LicenseResolver.chooseLicense(listOf(KnownLicense.W3C_19980720, KnownLicense.CC0_1_0)))
            .isEqualTo(KnownLicense.CC0_1_0)
        assertThat(LicenseResolver.chooseLicense(listOf(KnownLicense.UPL_1_0, KnownLicense.CDDL_1_1)))
            .isEqualTo(KnownLicense.CDDL_1_1)
    }
}
