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
import org.junit.jupiter.params.provider.ValueSource

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
    fun `converts a known SPDX ID regardless of case`() {
        val entry = DependencyReport.LicenseEntry(id = "mit")
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
    fun `converts known SPDX ID BlueOak-1_0_0`() {
        val entry = DependencyReport.LicenseEntry(id = "BlueOak-1.0.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.BLUE_OAK_1_0_0)
    }

    @Test
    fun `converts known SPDX ID CC-BY-4_0`() {
        val entry = DependencyReport.LicenseEntry(id = "CC-BY-4.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.CC_BY_4_0)
    }

    @Test
    fun `converts known SPDX ID LGPL-2_1-or-later`() {
        val entry = DependencyReport.LicenseEntry(id = "LGPL-2.1-or-later")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.LGPL_2_1_OR_LATER)
    }

    @Test
    fun `converts known SPDX ID MPL-2_0`() {
        val entry = DependencyReport.LicenseEntry(id = "MPL-2.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.MPL_2_0)
    }

    @Test
    fun `MPL-2_0-no-copyleft-exception SPDX ID returns UnknownLicense`() {
        val entry = DependencyReport.LicenseEntry(id = "MPL-2.0-no-copyleft-exception")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isInstanceOf(UnknownLicense::class.java)
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
    fun `matches Blue Oak Model License 1_0_0 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Blue Oak Model License 1.0.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.BLUE_OAK_1_0_0)
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
    fun `matches Creative Commons Attribution 4_0 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Creative Commons Attribution 4.0 International")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.CC_BY_4_0)
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

    @ParameterizedTest
    @ValueSource(
        strings = [
            "MPL 2.0",
            "MPL v2.0",
            "MPL-2.0",
            "Mozilla Public License 2.0",
            "Mozilla Public License Version 2.0",
            "Mozilla Public License version 2.0",
            "Mozilla Public License, Version 2.0",
            "Mozilla Public License, v. 2.0",
        ]
    )
    fun `matches MPL 2_0 by name`(name: String) {
        val entry = DependencyReport.LicenseEntry(name = name)
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.MPL_2_0)
    }

    @Test
    fun `matches MPL next to the MPL 2_0 URL`() {
        val entry = DependencyReport.LicenseEntry(name = "MPL", url = "https://www.mozilla.org/MPL/2.0/")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.MPL_2_0)
    }

    @Suppress("HttpUrlsUsage")
    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://mozilla.org/MPL/2.0/",
            "http://www.mozilla.org/MPL/2.0/index.txt",
            "https://www.mozilla.org/MPL/2.0/",
            "https://www.mozilla.org/en-US/MPL/2.0/",
        ]
    )
    fun `matches Mozilla Public License next to the MPL 2_0 URL`(url: String) {
        val entry = DependencyReport.LicenseEntry(name = "Mozilla Public License", url = url)
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.MPL_2_0)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Apache License",
            "GNU General Public License",
            "MPL 1.0",
            "MPL 1.1",
            "MPL and GPL",
            "MPL/GPL 2.0",
            "Mozilla Public License 1.1",
            "Something",
        ]
    )
    fun `MPL 1_x or another license name next to the MPL 2_0 URL returns UnknownLicense`(name: String) {
        val entry = DependencyReport.LicenseEntry(name = name, url = "https://www.mozilla.org/en-US/MPL/2.0/")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isInstanceOf(UnknownLicense::class.java)
    }

    @Suppress("HttpUrlsUsage")
    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://www.mozilla.org/MPL/MPL-1.0.html",
            "http://www.mozilla.org/MPL/MPL-1.1.html",
            "http://www.opensource.org/licenses/mozilla1.1.php",
            "https://opensource.org/license/mpl-1-1",
            "https://opensource.org/licenses/MPL-1.1",
            "https://spdx.org/licenses/MPL-2.0-no-copyleft-exception.html",
            "https://www.mozilla.org/en-US/MPL/1.1/",
        ]
    )
    fun `MPL 2_0 next to the URL of an unapproved MPL returns UnknownLicense`(url: String) {
        val entry = DependencyReport.LicenseEntry(name = "MPL 2.0", url = url)
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isInstanceOf(UnknownLicense::class.java)
    }

    @Test
    fun `MPL 2_0 or EPL 1_0 matches EPL_1_0`() {
        val entry = DependencyReport.LicenseEntry(name = "MPL 2.0 or EPL 1.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.EPL_1_0)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "MPL 2.0, Incompatible With Secondary Licenses",
            "MPL 2.0\nIncompatible With Secondary Licenses",
            "MPL-2.0-no-copyleft-exception",
            "Mozilla Public License (no copyleft exception)",
            "Mozilla Public License 2.0 (no copyleft exception)",
        ]
    )
    fun `MPL 2_0 without copyleft exception returns UnknownLicense`(name: String) {
        val entry = DependencyReport.LicenseEntry(name = name, url = "https://www.mozilla.org/MPL/2.0/")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isInstanceOf(UnknownLicense::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["MPL", "Mozilla Public License"])
    fun `MPL without a version and without the MPL 2_0 URL returns UnknownLicense`(name: String) {
        val withoutUrl = DependencyReport.LicenseEntry(name = name)
        assertThat(LicenseResolver.convertLicense(withoutUrl, anyCoordinates)).isInstanceOf(UnknownLicense::class.java)
        val nextToGpl = DependencyReport.LicenseEntry(name = name, url = "https://www.gnu.org/licenses/gpl-3.0.txt")
        assertThat(LicenseResolver.convertLicense(nextToGpl, anyCoordinates)).isInstanceOf(UnknownLicense::class.java)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "MPL 2.0 / GPL 2.0",
            "MPL 2.0 and GPL 3.0",
            "MPL 2.0 or Universal Permissive License, Version 1.0",
            "Mozilla Public License / GPL 2.0",
        ]
    )
    fun `MPL combined with another license returns UnknownLicense`(name: String) {
        val entry = DependencyReport.LicenseEntry(name = name)
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isInstanceOf(UnknownLicense::class.java)
    }

    @Test
    fun `SIMPL 2_0 returns UnknownLicense`() {
        val entry = DependencyReport.LicenseEntry(name = "SIMPL 2.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isInstanceOf(UnknownLicense::class.java)
    }

    @Test
    fun `matches Public Domain by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Public Domain")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.PUBLIC_DOMAIN)
    }

    @Test
    fun `matches Unicode ICU License by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Unicode/ICU License")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.UNICODE_3_0)
    }

    @Test
    fun `matches UPL 1_0 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "Universal Permissive License, Version 1.0")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.UPL_1_0)
    }

    @Test
    fun `matches LGPL 2_1 by name`() {
        val entry = DependencyReport.LicenseEntry(name = "GNU Lesser General Public License, Version 2.1")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.LGPL_2_1_OR_LATER)
    }

    @Test
    fun `matches LGPL 2_1 by short name`() {
        val entry = DependencyReport.LicenseEntry(name = "LGPL 2.1")
        assertThat(LicenseResolver.convertLicense(entry, anyCoordinates)).isEqualTo(KnownLicense.LGPL_2_1_OR_LATER)
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
