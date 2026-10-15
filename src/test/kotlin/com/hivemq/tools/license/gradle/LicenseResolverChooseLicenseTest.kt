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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * How a license is chosen when a component may be used under one of several: the alternative with the fewest
 * obligations wins, which for single licenses is the one that comes first in [LicenseResolver.LICENSE_ORDER].
 */
class LicenseResolverChooseLicenseTest {

    @TempDir
    lateinit var tempDir: File

    /** A BOM with one component, `com.example:lib:1.0.0`, offering each of [licenseEntries] as an alternative. */
    private fun resolve(vararg licenseEntries: String): List<KnownLicense> {
        val bom = tempDir.resolve("bom.json")
        bom.writeText(
            """
            {
              "components": [
                {
                  "group": "com.example",
                  "name": "lib",
                  "version": "1.0.0",
                  "licenses": [
                    ${licenseEntries.joinToString(",\n                    ")}
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        return LicenseResolver.resolveLicenses(bom, emptySet(), emptySet()).getValue("com.example:lib").licenses
    }

    private fun id(spdxId: String) = """{ "license": { "id": "$spdxId" } }"""

    private fun name(name: String) = """{ "license": { "name": "$name" } }"""

    @Test
    fun `chooses single known license`() {
        assertThat(resolve(id("MIT"))).containsExactly(KnownLicense.MIT)
    }

    @Test
    fun `chooses Apache over EPL when both present`() {
        assertThat(resolve(id("EPL-2.0"), id("Apache-2.0"))).containsExactly(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `chooses MIT over BSD_3_CLAUSE`() {
        assertThat(resolve(id("BSD-3-Clause"), id("MIT"))).containsExactly(KnownLicense.MIT)
    }

    @Test
    fun `chooses Apache over all others`() {
        assertThat(resolve(id("CDDL-1.0"), id("EPL-1.0"), id("MIT"), id("Apache-2.0")))
            .containsExactly(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `fails for empty list`() {
        assertThatThrownBy { resolve() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No license found for 'com.example:lib:1.0.0'")
    }

    @Test
    fun `fails for only unknown licenses`() {
        assertThatThrownBy { resolve(name("LGPL-3.0"), name("Proprietary")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("License not on the approved list for 'com.example:lib:1.0.0': LGPL-3.0, Proprietary")
    }

    @Test
    fun `ignores unknown licenses and picks known one`() {
        assertThat(resolve(name("LGPL-3.0"), id("EPL-2.0"))).containsExactly(KnownLicense.EPL_2_0)
    }

    @Test
    fun `follows LICENSE_ORDER priority`() {
        // verify a few pairs from the order; Bouncy Castle shares the MIT id, so it is reached by name
        assertThat(resolve(name("Bouncy Castle Licence"), id("MIT"))).containsExactly(KnownLicense.MIT)
        assertThat(resolve(id("W3C-19980720"), id("CC0-1.0"))).containsExactly(KnownLicense.CC0_1_0)
        assertThat(resolve(id("UPL-1.0"), id("CDDL-1.1"))).containsExactly(KnownLicense.CDDL_1_1)
    }
}
