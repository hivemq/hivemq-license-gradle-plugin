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
    fun `LICENSE_ORDER ends with LGPL_2_1_OR_LATER`() {
        assertThat(LicenseResolver.LICENSE_ORDER.last()).isEqualTo(KnownLicense.LGPL_2_1_OR_LATER)
    }

    @Test
    fun `LICENSE_ORDER has BLUE_OAK_1_0_0 after BOUNCY_CASTLE`() {
        val order = LicenseResolver.LICENSE_ORDER
        assertThat(order.indexOf(KnownLicense.BLUE_OAK_1_0_0))
            .isGreaterThan(order.indexOf(KnownLicense.BOUNCY_CASTLE))
            .isLessThan(order.indexOf(KnownLicense.ISC))
    }

    @Test
    fun `LICENSE_ORDER has CC_BY_4_0 after CC0_1_0`() {
        val order = LicenseResolver.LICENSE_ORDER
        assertThat(order.indexOf(KnownLicense.CC_BY_4_0))
            .isGreaterThan(order.indexOf(KnownLicense.CC0_1_0))
            .isLessThan(order.indexOf(KnownLicense.OFL_1_1))
    }

    @Test
    fun `BUILT_IN_EXPRESSIONS are canonical expressions with an approved alternative among several licenses`() {
        val builtIns = LicenseResolver.BUILT_IN_EXPRESSIONS.flatMap { (moduleId, all) -> all.map { moduleId to it } }
        for ((moduleId, builtIn) in builtIns) {
            val parsed = SpdxExpression(builtIn.expression)
            assertThat(parsed.evaluate().licenses).describedAs(moduleId).isNotNull()
            // null for a single license, so equality also says that the expression names several
            val canonical = SpdxExpression.expressionOf(listOf(parsed.tree))
            assertThat(canonical).describedAs(moduleId).isEqualTo(builtIn.expression)
        }
    }

    @Test
    fun `BUILT_IN_EXPRESSIONS maps jersey-server to the Jersey choice AND Apache-2_0 AND BSD-3-Clause`() {
        val builtIns = LicenseResolver.BUILT_IN_EXPRESSIONS["org.glassfish.jersey.core:jersey-server"]

        assertThat(builtIns?.map { it.expression })
            .containsExactly("(EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0) AND Apache-2.0 AND BSD-3-Clause")
    }

    @Test
    fun `BUILT_IN_EXPRESSIONS maps jna to both license lists its versions declare`() {
        assertThat(LicenseResolver.BUILT_IN_EXPRESSIONS["net.java.dev.jna:jna"]).containsExactly(
            LicenseResolver.BuiltInExpression("LGPL-2.1-only OR Apache-2.0"),
            LicenseResolver.BuiltInExpression("LGPL-2.1-or-later OR Apache-2.0"),
        )
    }

    @Test
    fun `BUILT_IN_EXPRESSIONS maps logstash-logback-encoder to Apache-2_0 AND MIT`() {
        assertThat(LicenseResolver.BUILT_IN_EXPRESSIONS["net.logstash.logback:logstash-logback-encoder"])
            .containsExactly(LicenseResolver.BuiltInExpression("Apache-2.0 AND MIT", listOf("Apache-2.0", "MIT")))
    }

    @Test
    fun `BUILT_IN_EXPRESSIONS maps resteasy-tracing-api to EPL-2_0 OR GPL-2_0-only WITH Classpath-exception-2_0`() {
        assertThat(LicenseResolver.BUILT_IN_EXPRESSIONS["org.jboss.resteasy:resteasy-tracing-api"]).containsExactly(
            LicenseResolver.BuiltInExpression(
                "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0",
                listOf("EPL-2.0", "GNU General Public License, Version 2 with the Classpath Exception"),
            )
        )
    }

    @Test
    fun `BUILT_IN_EXPRESSIONS maps rocksdbjni to Apache-2_0 OR GPL-2_0-only`() {
        assertThat(LicenseResolver.BUILT_IN_EXPRESSIONS["org.rocksdb:rocksdbjni"]).containsExactly(
            LicenseResolver.BuiltInExpression(
                "Apache-2.0 OR GPL-2.0-only",
                listOf("Apache-2.0", "GNU General Public License, version 2"),
            )
        )
    }

    @Test
    fun `BUILT_IN_EXPRESSIONS maps unboundid-ldapsdk to its four licenses, the Free Use License as a reference`() {
        val builtIns = LicenseResolver.BUILT_IN_EXPRESSIONS["com.unboundid:unboundid-ldapsdk"]

        assertThat(builtIns?.map { it.expression })
            .containsExactly("Apache-2.0 OR GPL-2.0-only OR LGPL-2.1-only OR LicenseRef-scancode-ldap-sdk-free-use")
    }

    @Test
    fun `BUILT_IN_EXPRESSIONS name no module of BUILT_IN_LICENSES`() {
        // a built-in license is checked first and would win silently
        assertThat(LicenseResolver.BUILT_IN_EXPRESSIONS.keys)
            .doesNotContainAnyElementsOf(LicenseResolver.BUILT_IN_LICENSES.keys)
    }

    @Test
    fun `BUILT_IN_EXPRESSIONS record a declared list only where the list does not declare the expression itself`() {
        val recorded = LicenseResolver.BUILT_IN_EXPRESSIONS
            .flatMap { (moduleId, all) -> all.filter { it.declared.isNotEmpty() }.map { moduleId to it } }
        for ((moduleId, builtIn) in recorded) {
            assertThat(builtIn.declared).describedAs(moduleId).hasSizeGreaterThan(1)
            // a name has no tree, so the list declares no expression; ids that do must declare another one
            val trees = builtIn.declared.map { SpdxExpression(it).tree }
            if (null in trees) continue
            val expression = SpdxExpression(builtIn.expression).tree
            assertThat(SpdxExpression.sameExpression(SpdxExpression.Node.Or(trees.filterNotNull()), expression))
                .describedAs(moduleId)
                .isFalse()
        }
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
    fun `BUILT_IN_LICENSES maps jsr311-api to CDDL_1_1`() {
        assertThat(LicenseResolver.BUILT_IN_LICENSES["javax.ws.rs:jsr311-api"])
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
    fun `BUILT_IN_LICENSES maps sac to W3C_19980720`() {
        assertThat(LicenseResolver.BUILT_IN_LICENSES["org.w3c.css:sac"])
            .isEqualTo(KnownLicense.W3C_19980720)
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
        assertThat(LicenseResolver.SPDX_ID_MAP["LGPL-2.1-or-later"]).isEqualTo(KnownLicense.LGPL_2_1_OR_LATER)
        assertThat(LicenseResolver.SPDX_ID_MAP["BlueOak-1.0.0"]).isEqualTo(KnownLicense.BLUE_OAK_1_0_0)
        assertThat(LicenseResolver.SPDX_ID_MAP["CC-BY-4.0"]).isEqualTo(KnownLicense.CC_BY_4_0)
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
