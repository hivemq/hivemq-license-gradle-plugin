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

class LicenseResolverMetadataTest {

    @Test
    fun `LICENSE_ORDER contains all KnownLicense values`() {
        assertThat(LicenseResolver.LICENSE_ORDER).containsAll(KnownLicense.entries)
    }

    @Test
    fun `LICENSE_ORDER has no duplicates`() {
        assertThat(LicenseResolver.LICENSE_ORDER).doesNotHaveDuplicates()
    }

    @Test
    fun `LICENSE_ORDER has same size as KnownLicense enum`() {
        assertThat(LicenseResolver.LICENSE_ORDER).hasSameSizeAs(KnownLicense.entries)
    }

    @Test
    fun `LICENSE_ORDER starts with APACHE_2_0`() {
        assertThat(LicenseResolver.LICENSE_ORDER.first()).isEqualTo(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `LICENSE_ORDER ends with UPL_1_0`() {
        assertThat(LicenseResolver.LICENSE_ORDER.last()).isEqualTo(KnownLicense.UPL_1_0)
    }

    @Test
    fun `BUILT_IN_LICENSES maps javax_activation to CDDL_1_1`() {
        assertThat(LicenseResolver.BUILT_IN_LICENSES["javax.activation:javax.activation-api"])
            .isEqualTo(KnownLicense.CDDL_1_1)
    }

    @Test
    fun `BUILT_IN_LICENSES maps javax_annotation to CDDL_1_1`() {
        assertThat(LicenseResolver.BUILT_IN_LICENSES["javax.annotation:javax.annotation-api"])
            .isEqualTo(KnownLicense.CDDL_1_1)
    }

    @Test
    fun `BUILT_IN_LICENSES maps asm to BSD_3_CLAUSE`() {
        assertThat(LicenseResolver.BUILT_IN_LICENSES["org.ow2.asm:asm"])
            .isEqualTo(KnownLicense.BSD_3_CLAUSE)
    }

    @Test
    fun `BUILT_IN_LICENSES maps picocontainer to BSD_3_CLAUSE`() {
        assertThat(LicenseResolver.BUILT_IN_LICENSES["org.picocontainer:picocontainer"])
            .isEqualTo(KnownLicense.BSD_3_CLAUSE)
    }

    @Test
    fun `SPDX_ID_MAP maps known SPDX IDs`() {
        assertThat(LicenseResolver.SPDX_ID_MAP["Apache-2.0"]).isEqualTo(KnownLicense.APACHE_2_0)
        assertThat(LicenseResolver.SPDX_ID_MAP["MIT"]).isEqualTo(KnownLicense.MIT)
        assertThat(LicenseResolver.SPDX_ID_MAP["EPL-2.0"]).isEqualTo(KnownLicense.EPL_2_0)
        assertThat(LicenseResolver.SPDX_ID_MAP["EPL-1.0"]).isEqualTo(KnownLicense.EPL_1_0)
        assertThat(LicenseResolver.SPDX_ID_MAP["CC0-1.0"]).isEqualTo(KnownLicense.CC0_1_0)
        assertThat(LicenseResolver.SPDX_ID_MAP["CDDL-1.0"]).isEqualTo(KnownLicense.CDDL_1_0)
        assertThat(LicenseResolver.SPDX_ID_MAP["CDDL-1.1"]).isEqualTo(KnownLicense.CDDL_1_1)
        assertThat(LicenseResolver.SPDX_ID_MAP["BSD-2-Clause"]).isEqualTo(KnownLicense.BSD_2_CLAUSE)
        assertThat(LicenseResolver.SPDX_ID_MAP["UPL-1.0"]).isEqualTo(KnownLicense.UPL_1_0)
        assertThat(LicenseResolver.SPDX_ID_MAP["MIT-0"]).isEqualTo(KnownLicense.MIT_0)
        assertThat(LicenseResolver.SPDX_ID_MAP["W3C"]).isEqualTo(KnownLicense.W3C)
        assertThat(LicenseResolver.SPDX_ID_MAP["W3C-19980720"]).isEqualTo(KnownLicense.W3C_19980720)
        assertThat(LicenseResolver.SPDX_ID_MAP["Unicode-3.0"]).isEqualTo(KnownLicense.UNICODE_3_0)
        assertThat(LicenseResolver.SPDX_ID_MAP["Unicode-DFS-2016"]).isEqualTo(KnownLicense.UNICODE_DFS_2016)
        assertThat(LicenseResolver.SPDX_ID_MAP["0BSD"]).isEqualTo(KnownLicense.ZERO_BSD)
        assertThat(LicenseResolver.SPDX_ID_MAP["ISC"]).isEqualTo(KnownLicense.ISC)
        assertThat(LicenseResolver.SPDX_ID_MAP["OFL-1.1"]).isEqualTo(KnownLicense.OFL_1_1)
        assertThat(LicenseResolver.SPDX_ID_MAP["Unlicense"]).isEqualTo(KnownLicense.UNLICENSE)
    }

    @Test
    fun `SPDX_ID_MAP uses first match from LICENSE_ORDER for shared SPDX IDs`() {
        // BSD-3-Clause is shared by BSD_3_CLAUSE, EDL_1_0, and GO
        // BSD_3_CLAUSE comes first in LICENSE_ORDER, so it should win
        assertThat(LicenseResolver.SPDX_ID_MAP["BSD-3-Clause"]).isEqualTo(KnownLicense.BSD_3_CLAUSE)
    }

    @Test
    fun `SPDX_ID_MAP uses MIT not BOUNCY_CASTLE for MIT SPDX ID`() {
        // MIT is shared by MIT and BOUNCY_CASTLE
        // MIT comes before BOUNCY_CASTLE in LICENSE_ORDER
        assertThat(LicenseResolver.SPDX_ID_MAP["MIT"]).isEqualTo(KnownLicense.MIT)
    }

    @ParameterizedTest
    @EnumSource(KnownLicense::class)
    fun `every KnownLicense SPDX ID is present in SPDX_ID_MAP`(license: KnownLicense) {
        assertThat(LicenseResolver.SPDX_ID_MAP[license.id])
            .describedAs("SPDX ID '${license.id}' for $license is not in SPDX_ID_MAP")
            .isNotNull()
    }

    @Test
    fun `SPDX_ID_MAP has fewer entries than KnownLicense due to shared IDs`() {
        val uniqueIds = KnownLicense.entries.map { it.id }.toSet()
        assertThat(LicenseResolver.SPDX_ID_MAP).hasSameSizeAs(uniqueIds)
    }
}
