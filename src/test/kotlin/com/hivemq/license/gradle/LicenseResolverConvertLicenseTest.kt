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
package com.hivemq.license.gradle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LicenseResolverConvertLicenseTest {

    private val anyCoordinates = Coordinates("com.example", "lib", "1.0.0")

    // --- SPDX ID resolution ---

    @Test
    fun `converts known SPDX ID Apache-2_0`() {
        val entry = DependencyReport.LicenseEntry(id = "Apache-2.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `converts known SPDX ID MIT`() {
        val entry = DependencyReport.LicenseEntry(id = "MIT")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.MIT)
    }

    @Test
    fun `converts known SPDX ID EPL-2_0`() {
        val entry = DependencyReport.LicenseEntry(id = "EPL-2.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.EPL_2_0)
    }

    @Test
    fun `converts known SPDX ID MIT-0`() {
        val entry = DependencyReport.LicenseEntry(id = "MIT-0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.MIT_0)
    }

    @Test
    fun `converts known SPDX ID BSD-2-Clause`() {
        val entry = DependencyReport.LicenseEntry(id = "BSD-2-Clause")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.BSD_2_CLAUSE)
    }

    @Test
    fun `BSD-3-Clause SPDX ID maps to BSD_3_CLAUSE (first in LICENSE_ORDER)`() {
        val entry = DependencyReport.LicenseEntry(id = "BSD-3-Clause")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.BSD_3_CLAUSE)
    }

    @Test
    fun `unknown SPDX ID returns UnknownLicense`() {
        val entry = DependencyReport.LicenseEntry(id = "LGPL-3.0", url = "https://example.com")
        val result = LicenseResolver.convertLicense(entry, anyCoordinates)
        assertThat(result).isInstanceOf(UnknownLicense::class.java)
        assertThat(result.fullName).isEqualTo("LGPL-3.0")
        assertThat(result.url).isEqualTo("https://example.com")
    }

    // --- Name-based matching ---

    @Test
    fun `matches Apache License 2_0 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Apache License 2.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `matches Apache License Version 2_0 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Apache License, Version 2.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `matches The Apache Software License Version 2_0 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "The Apache Software License, Version 2.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `matches Apache-2 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Apache-2")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `matches Bouncy Castle Licence by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Bouncy Castle Licence")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.BOUNCY_CASTLE)
    }

    @Test
    fun `matches BSD 2-Clause by name`() {
        val entry = DependencyReport.LicenseEntry(name = "BSD 2-Clause License")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.BSD_2_CLAUSE)
    }

    @Test
    fun `matches BSD 3-Clause by name`() {
        val entry = DependencyReport.LicenseEntry(name = "BSD 3-Clause License")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.BSD_3_CLAUSE)
    }

    @Test
    fun `matches New BSD License by name`() {
        val entry = DependencyReport.LicenseEntry(name = "New BSD License")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.BSD_3_CLAUSE)
    }

    @Test
    fun `matches BSD 3-Clause by URL`() {
        val entry = DependencyReport.LicenseEntry(
            name = "Some License",
            url = "https://opensource.org/licenses/BSD-3-Clause",
        )
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.BSD_3_CLAUSE)
    }

    @Test
    fun `matches CC0 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "CC0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.CC0_1_0)
    }

    @Test
    fun `matches Public Domain per Creative Commons CC0 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Public Domain, per Creative Commons CC0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.CC0_1_0)
    }

    @Test
    fun `matches CDDL 1_0 by URL`() {
        val entry = DependencyReport.LicenseEntry(
            name = "Something",
            url = "https://glassfish.dev.java.net/public/CDDLv1.0.html",
        )
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.CDDL_1_0)
    }

    @Test
    fun `matches CDDL 1_1 by CDDL+GPL URL`() {
        val entry = DependencyReport.LicenseEntry(
            name = "Something",
            url = "https://oss.oracle.com/licenses/CDDL+GPL-1.1",
        )
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.CDDL_1_1)
    }

    @Test
    fun `matches EDL 1_0 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Eclipse Distribution License - v 1.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.EDL_1_0)
    }

    @Test
    fun `matches EPL 1_0 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Eclipse Public License 1.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.EPL_1_0)
    }

    @Test
    fun `matches EPL 2_0 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Eclipse Public License 2.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.EPL_2_0)
    }

    @Test
    fun `matches Go License by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Go License")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.GO)
    }

    @Test
    fun `matches MIT License by name`() {
        val entry = DependencyReport.LicenseEntry(name = "MIT License")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.MIT)
    }

    @Test
    fun `matches MIT-0 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "MIT-0 License")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.MIT_0)
    }

    @Test
    fun `matches Public Domain by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Public Domain")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.PUBLIC_DOMAIN)
    }

    @Test
    fun `matches UPL 1_0 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Universal Permissive License, Version 1.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.UPL_1_0)
    }

    @Test
    fun `matches W3C 19980720 by URL`() {
        val entry = DependencyReport.LicenseEntry(
            name = "Something",
            url = "http://www.w3.org/Consortium/Legal/copyright-software-19980720",
        )
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.W3C_19980720)
    }

    // --- Specific dependency overrides ---

    @Test
    fun `matches BSD for dk_brics_automaton as BSD_3_CLAUSE`() {
        val coordinates = Coordinates("dk.brics.automaton", "automaton", "1.0.0")
        val entry = DependencyReport.LicenseEntry(name = "BSD")
        assertThat(LicenseResolver.convertLicense(entry, coordinates)).isEqualTo(KnownLicense.BSD_3_CLAUSE)
    }

    @Test
    fun `matches BSD for org_ow2_asm as BSD_3_CLAUSE`() {
        val coordinates = Coordinates("org.ow2.asm", "asm", "9.0")
        val entry = DependencyReport.LicenseEntry(name = "BSD")
        assertThat(LicenseResolver.convertLicense(entry, coordinates)).isEqualTo(KnownLicense.BSD_3_CLAUSE)
    }

    @Test
    fun `matches BSD for org_picocontainer as BSD_3_CLAUSE`() {
        val coordinates = Coordinates("org.picocontainer", "picocontainer", "2.15")
        val entry = DependencyReport.LicenseEntry(name = "BSD")
        assertThat(LicenseResolver.convertLicense(entry, coordinates)).isEqualTo(KnownLicense.BSD_3_CLAUSE)
    }

    @Test
    fun `BSD for unknown artifact returns UnknownLicense`() {
        val coordinates = Coordinates("com.example", "some-lib", "1.0.0")
        val entry = DependencyReport.LicenseEntry(name = "BSD")
        val result = LicenseResolver.convertLicense(entry, coordinates)
        assertThat(result).isInstanceOf(UnknownLicense::class.java)
    }

    // --- Edge cases ---

    @Test
    fun `no id and no name returns UnknownLicense`() {
        val entry = DependencyReport.LicenseEntry(url = "https://example.com")
        val result = LicenseResolver.convertLicense(entry, anyCoordinates)
        assertThat(result).isInstanceOf(UnknownLicense::class.java)
        assertThat(result.fullName).isEqualTo("Unknown")
        assertThat(result.url).isEqualTo("https://example.com")
    }

    @Test
    fun `unrecognized name returns UnknownLicense`() {
        val entry = DependencyReport.LicenseEntry(name = "Some Proprietary License", url = "https://example.com")
        val result = LicenseResolver.convertLicense(entry, anyCoordinates)
        assertThat(result).isInstanceOf(UnknownLicense::class.java)
        assertThat(result.fullName).isEqualTo("Some Proprietary License")
    }
}
