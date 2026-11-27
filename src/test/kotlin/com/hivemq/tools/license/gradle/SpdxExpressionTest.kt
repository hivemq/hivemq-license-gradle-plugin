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

import com.hivemq.tools.license.gradle.SpdxExpression.Node.And
import com.hivemq.tools.license.gradle.SpdxExpression.Node.Leaf
import com.hivemq.tools.license.gradle.SpdxExpression.Node.Or
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The syntax tree of an expression and the canonical form the SBOM writes; approval is tested with the resolver. */
class SpdxExpressionTest {

    private fun tree(expression: String) =
        requireNotNull(SpdxExpression(expression).tree) { "'$expression' has no tree" }

    private fun canonical(expression: String) = tree(expression).canonical()?.render()

    // --- Tree ---

    @Test
    fun `has no tree for a malformed expression`() {
        assertThat(SpdxExpression("MIT OR )").tree).isNull()
    }

    @Test
    fun `has no tree for an expression nested too deep`() {
        val deep = "(".repeat(33) + "MIT" + ")".repeat(33)

        assertThat(SpdxExpression(deep).tree).isNull()
    }

    @Test
    fun `has no tree for an expression with tokens left over`() {
        assertThat(SpdxExpression("MIT ISC").tree).isNull()
    }

    @Test
    fun `has no tree for an expression with too many alternatives`() {
        // eleven pairs of distinct approved licenses have 2048 ways to be satisfied, more than the parser keeps
        val ids = LicenseResolver.SPDX_ID_MAP.keys.filter { ' ' !in it }.take(22)
        check(ids.size == 22) { "needs 22 approved licenses with an SPDX id, found ${ids.size}" }

        val expression = SpdxExpression(ids.chunked(2).joinToString(" AND ") { (a, b) -> "($a OR $b)" })

        assertThat(expression.tree).isNull()
        assertThat(expression.evaluate().problem).isEqualTo("SPDX expression too complex to evaluate")
    }

    @Test
    fun `reads a license with its exception`() {
        assertThat(tree("GPL-2.0-only WITH Classpath-exception-2.0"))
            .isEqualTo(Leaf("GPL-2.0-only", "Classpath-exception-2.0"))
    }

    @Test
    fun `reads a parenthesised group in place`() {
        assertThat(tree("(MIT OR ISC) AND Apache-2.0"))
            .isEqualTo(And(listOf(Or(listOf(Leaf("MIT"), Leaf("ISC"))), Leaf("Apache-2.0"))))
    }

    @Test
    fun `reads an AND operand every alternative already includes`() {
        // evaluation skips such an operand, the tree keeps what was declared
        assertThat(tree("MIT AND MIT")).isEqualTo(And(listOf(Leaf("MIT"), Leaf("MIT"))))
    }

    @Test
    fun `reads an id as written`() {
        assertThat(tree("mit")).isEqualTo(Leaf("mit"))
    }

    @Test
    fun `reads AND as binding tighter than OR`() {
        assertThat(tree("MIT OR ISC AND Apache-2.0"))
            .isEqualTo(Or(listOf(Leaf("MIT"), And(listOf(Leaf("ISC"), Leaf("Apache-2.0"))))))
    }

    @Test
    fun `reads and evaluates the same expression`() {
        val expression = SpdxExpression("MIT OR GPL-3.0-only")

        assertThat(expression.tree).isEqualTo(Or(listOf(Leaf("MIT"), Leaf("GPL-3.0-only"))))
        assertThat(expression.evaluate().licenses).containsExactly(KnownLicense.MIT)
    }

    // --- Canonical form ---

    @Test
    fun `flattens a group that a repeat shrinks to one operand`() {
        assertThat(canonical("(MIT OR ISC) AND (mit OR isc)")).isEqualTo("MIT OR ISC")
        assertThat(canonical("(MIT AND ISC) OR (mit AND isc)")).isEqualTo("MIT AND ISC")
        assertThat(canonical("MIT OR GPL-2.0 AND GPL-2.0-only")).isEqualTo("MIT OR GPL-2.0-only")
        // the AND shrinks to an OR, which then joins the enclosing OR
        assertThat(canonical("MIT OR ((MIT OR ISC) AND (mit OR isc))")).isEqualTo("MIT OR ISC")
    }

    @Test
    fun `flattens nested groups of one operator`() {
        assertThat(canonical("(MIT OR (ISC OR Apache-2.0))")).isEqualTo("MIT OR ISC OR Apache-2.0")
        assertThat(canonical("(MIT AND ISC) AND Apache-2.0")).isEqualTo("MIT AND ISC AND Apache-2.0")
    }

    @Test
    fun `has no canonical form for a license that is not an SPDX id`() {
        assertThat(canonical("MIT OR Public Domain")).isNull()
    }

    @Test
    fun `keeps a deprecated id SPDX names no replacement for as written`() {
        assertThat(canonical("MIT OR BSD-2-Clause-FreeBSD")).isEqualTo("MIT OR BSD-2-Clause-FreeBSD")
    }

    @Test
    fun `keeps a deprecated id with an exception of its own as written`() {
        assertThat(canonical("GPL-2.0-with-classpath-exception WITH Foo"))
            .isEqualTo("GPL-2.0-with-classpath-exception WITH Foo")
    }

    @Test
    fun `keeps a license reference as written`() {
        assertThat(canonical("MIT OR LicenseRef-foo")).isEqualTo("MIT OR LicenseRef-foo")
        assertThat(canonical("MIT OR DocumentRef-spdx-doc:LicenseRef-foo"))
            .isEqualTo("MIT OR DocumentRef-spdx-doc:LicenseRef-foo")
    }

    @Test
    fun `keeps a plus suffix outside the table as written`() {
        assertThat(canonical("MIT OR Foo-1.0+")).isEqualTo("MIT OR Foo-1.0+")
    }

    @Test
    fun `keeps a WITH clause`() {
        assertThat(canonical("MIT OR GPL-2.0-only WITH Classpath-exception-2.0"))
            .isEqualTo("MIT OR GPL-2.0-only WITH Classpath-exception-2.0")
    }

    @Test
    fun `keeps an unknown id as written`() {
        assertThat(canonical("MIT OR gpl-3.0-only")).isEqualTo("MIT OR gpl-3.0-only")
    }

    @Test
    fun `parenthesises AND under OR and OR under AND`() {
        assertThat(canonical("MIT OR ISC AND Apache-2.0")).isEqualTo("MIT OR (ISC AND Apache-2.0)")
        assertThat(canonical("(MIT OR ISC) AND Apache-2.0")).isEqualTo("(MIT OR ISC) AND Apache-2.0")
    }

    @Test
    fun `replaces a group of one operand by it`() {
        assertThat(canonical("(MIT)")).isEqualTo("MIT")
        assertThat(canonical("MIT OR MIT")).isEqualTo("MIT")
    }

    @Test
    fun `rewrites a deprecated exception id`() {
        assertThat(canonical("LGPL-2.1-only WITH Nokia-Qt-exception-1.1"))
            .isEqualTo("LGPL-2.1-only WITH Qt-LGPL-exception-1.1")
        assertThat(canonical("LGPL-2.1-only WITH nokia-qt-EXCEPTION-1.1"))
            .isEqualTo("LGPL-2.1-only WITH Qt-LGPL-exception-1.1")
        // how Qt was declared before both ids were deprecated
        assertThat(canonical("LGPL-2.1 WITH Nokia-Qt-exception-1.1"))
            .isEqualTo("LGPL-2.1-only WITH Qt-LGPL-exception-1.1")
    }

    @Test
    fun `rewrites a deprecated id that carries a WITH clause`() {
        assertThat(canonical("GPL-2.0 WITH Classpath-exception-2.0"))
            .isEqualTo("GPL-2.0-only WITH Classpath-exception-2.0")
        assertThat(canonical("MIT OR GPL-2.0+ WITH Classpath-exception-2.0"))
            .isEqualTo("MIT OR GPL-2.0-or-later WITH Classpath-exception-2.0")
    }

    @ParameterizedTest
    // the obsoletedBy entries of the SPDX license list, with the deprecated GPL-x.0 they name written as GPL-x.0-only
    // and the stray "+" of the font exception's entry left out
    @CsvSource(
        "AGPL-1.0, AGPL-1.0-only",
        "AGPL-1.0+, AGPL-1.0-or-later",
        "AGPL-3.0, AGPL-3.0-only",
        "AGPL-3.0+, AGPL-3.0-or-later",
        "GFDL-1.1, GFDL-1.1-only",
        "GFDL-1.1+, GFDL-1.1-or-later",
        "GFDL-1.2, GFDL-1.2-only",
        "GFDL-1.2+, GFDL-1.2-or-later",
        "GFDL-1.3, GFDL-1.3-only",
        "GFDL-1.3+, GFDL-1.3-or-later",
        "GPL-1.0, GPL-1.0-only",
        "GPL-1.0+, GPL-1.0-or-later",
        "GPL-2.0, GPL-2.0-only",
        "GPL-2.0+, GPL-2.0-or-later",
        "GPL-3.0, GPL-3.0-only",
        "GPL-3.0+, GPL-3.0-or-later",
        "LGPL-2.0, LGPL-2.0-only",
        "LGPL-2.0+, LGPL-2.0-or-later",
        "LGPL-2.1, LGPL-2.1-only",
        "LGPL-2.1+, LGPL-2.1-or-later",
        "LGPL-3.0, LGPL-3.0-only",
        "LGPL-3.0+, LGPL-3.0-or-later",
        "GPL-2.0-with-autoconf-exception, GPL-2.0-only WITH Autoconf-exception-2.0",
        "GPL-2.0-with-autoconf-exception+, GPL-2.0-or-later WITH Autoconf-exception-2.0",
        "GPL-2.0-with-bison-exception, GPL-2.0-only WITH Bison-exception-2.2",
        "GPL-2.0-with-bison-exception+, GPL-2.0-or-later WITH Bison-exception-2.2",
        "GPL-2.0-with-classpath-exception, GPL-2.0-only WITH Classpath-exception-2.0",
        "GPL-2.0-with-classpath-exception+, GPL-2.0-or-later WITH Classpath-exception-2.0",
        "GPL-2.0-with-font-exception, GPL-2.0-only WITH Font-exception-2.0",
        "GPL-2.0-with-font-exception+, GPL-2.0-or-later WITH Font-exception-2.0",
        "GPL-2.0-with-GCC-exception, GPL-2.0-only WITH GCC-exception-2.0",
        "GPL-2.0-with-GCC-exception+, GPL-2.0-or-later WITH GCC-exception-2.0",
        "GPL-3.0-with-autoconf-exception, GPL-3.0-only WITH Autoconf-exception-3.0",
        "GPL-3.0-with-autoconf-exception+, GPL-3.0-or-later WITH Autoconf-exception-3.0",
        "GPL-3.0-with-GCC-exception, GPL-3.0-only WITH GCC-exception-3.1",
        "GPL-3.0-with-GCC-exception+, GPL-3.0-or-later WITH GCC-exception-3.1",
        "BSD-2-Clause-NetBSD, BSD-2-Clause",
        "eCos-2.0, GPL-2.0-or-later WITH eCos-exception-2.0",
        "Nunit, zlib-acknowledgement",
        "StandardML-NJ, SMLNJ",
        "wxWindows, GPL-2.0-or-later WITH WxWindows-exception-3.1",
    )
    fun `writes a deprecated id in its current form regardless of case`(deprecated: String, current: String) {
        for (written in listOf(deprecated, deprecated.uppercase(), deprecated.lowercase())) {
            assertThat(canonical("MIT OR $written")).describedAs(written).isEqualTo("MIT OR $current")
        }
    }

    @Test
    fun `writes a known id in the case of the approved list`() {
        assertThat(canonical("mit OR apache-2.0")).isEqualTo("MIT OR Apache-2.0")
    }

    @Test
    fun `writes a known id with a plus suffix in the case of the approved list`() {
        assertThat(canonical("MIT OR apache-2.0+")).isEqualTo("MIT OR Apache-2.0+")
    }

    @Test
    fun `writes a repeated operand once`() {
        assertThat(canonical("MIT OR Apache-2.0 OR mit")).isEqualTo("MIT OR Apache-2.0")
        // flattened first, so a repeat inside a nested group of the same operator is found too
        assertThat(canonical("MIT OR (Apache-2.0 OR mit)")).isEqualTo("MIT OR Apache-2.0")
    }

    @Test
    fun `writes an unknown id or an exception repeated in another case once`() {
        // SPDX matches ids regardless of case, so both name one license; the first spelling stays
        assertThat(canonical("GPL-3.0-only OR gpl-3.0-only")).isEqualTo("GPL-3.0-only")
        assertThat(canonical("GPL-2.0-only WITH Classpath-exception-2.0 OR GPL-2.0-only WITH classpath-exception-2.0"))
            .isEqualTo("GPL-2.0-only WITH Classpath-exception-2.0")
    }

    // --- Expression of the alternatives ---

    @Test
    fun `is null for a single license`() {
        assertThat(SpdxExpression.expressionOf(listOf(Leaf("MIT")))).isNull()
        assertThat(SpdxExpression.expressionOf(listOf(Leaf("MIT"), Leaf("mit")))).isNull()
    }

    @Test
    fun `is null when an alternative has no SPDX form`() {
        assertThat(SpdxExpression.expressionOf(listOf(Leaf("MIT"), Leaf("Public Domain")))).isNull()
    }

    @Test
    fun `is null when an alternative is null`() {
        assertThat(SpdxExpression.expressionOf(listOf(Leaf("EPL-2.0"), Leaf("MIT"), null))).isNull()
    }

    @Test
    fun `is null without alternatives`() {
        assertThat(SpdxExpression.expressionOf(emptyList())).isNull()
    }

    @Test
    fun `joins the alternatives with OR`() {
        val alternatives = listOf(Leaf("EPL-2.0"), Leaf("GPL-2.0-with-classpath-exception"))

        assertThat(SpdxExpression.expressionOf(alternatives))
            .isEqualTo("EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0")
    }

    @Test
    fun `parenthesises an alternative that is a conjunction`() {
        assertThat(SpdxExpression.expressionOf(listOf(Leaf("EPL-2.0"), tree("MIT AND ISC"))))
            .isEqualTo("EPL-2.0 OR (MIT AND ISC)")
    }

    @Test
    fun `writes a single conjunction without parentheses`() {
        assertThat(SpdxExpression.expressionOf(listOf(tree("MIT AND ISC")))).isEqualTo("MIT AND ISC")
    }

    // --- Same expression ---

    @Test
    fun `is not the same expression for a license that is not an SPDX id`() {
        assertThat(SpdxExpression.sameExpression(Leaf("Public Domain"), Leaf("Public Domain"))).isFalse()
    }

    @Test
    fun `is not the same expression for another operator`() {
        assertThat(SpdxExpression.sameExpression(tree("Apache-2.0 AND MIT"), tree("Apache-2.0 OR MIT"))).isFalse()
    }

    @Test
    fun `is not the same expression for null`() {
        assertThat(SpdxExpression.sameExpression(null, tree("MIT"))).isFalse()
        assertThat(SpdxExpression.sameExpression(tree("MIT"), null)).isFalse()
    }

    @Test
    fun `is the same expression for a deprecated id and its current form`() {
        val deprecated = tree("EPL-2.0 OR GPL-2.0-with-classpath-exception")
        val current = tree("EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0")

        assertThat(SpdxExpression.sameExpression(deprecated, current)).isTrue()
    }

    @Test
    fun `is the same expression for a group repeated in another order`() {
        val repeated = tree("(MIT AND ISC) OR (ISC AND MIT)")

        assertThat(SpdxExpression.sameExpression(repeated, tree("MIT AND ISC"))).isTrue()
    }

    @Test
    fun `is the same expression for a group repeated in another order inside a group of the parent's operator`() {
        // the repeated OR collapses to one operand of the AND, which then stands in the outer OR
        val repeated = tree("Apache-2.0 OR ((MPL-1.1 OR LGPL-2.1-only) AND (LGPL-2.1-only OR MPL-1.1))")

        assertThat(SpdxExpression.sameExpression(repeated, tree("MPL-1.1 OR LGPL-2.1-only OR Apache-2.0"))).isTrue()
    }

    @Test
    fun `is the same expression regardless of the case of ids`() {
        assertThat(SpdxExpression.sameExpression(tree("wtfpl OR mit"), tree("WTFPL OR MIT"))).isTrue()
    }

    @Test
    fun `is the same expression whatever the order of the operands of a group`() {
        val first = tree("Apache-2.0 OR (MIT AND ISC)")

        assertThat(SpdxExpression.sameExpression(first, tree("(ISC AND MIT) OR Apache-2.0"))).isTrue()
    }
}
