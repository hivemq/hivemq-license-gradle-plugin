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

/** The licenses an expression is satisfied by, or null with the reason when that is a fault of the expression. */
internal class ExpressionResult(val licenses: List<KnownLicense>?, val problem: String? = null) {
    fun describe(expression: String) = if (problem == null) expression else "$expression ($problem)"
}

/**
 * Parses an SPDX license expression such as "(CDDL-1.0 OR GPL-2.0-with-classpath-exception)" into every way it can be
 * satisfied, each one the licenses that then apply together, and ranks them once the whole expression is read, so an
 * alternative is never chosen before an enclosing AND adds to it. "A OR B" is satisfied by any approved alternative,
 * and the one with the fewest obligations is reported; "A AND B" needs every operand approved and reports all of them.
 * AND binds tighter than OR, parentheses group, a license WITH an exception is not approved as the license, and a
 * malformed expression is never satisfied. An unknown license satisfies nothing; a syntax error is tracked apart, so
 * "MIT OR )" is not satisfied by its MIT. An alternative that adds licenses to another can never rank better, so it is
 * dropped as soon as it appears. A candidate seen before, and an AND operand every alternative already includes, cost
 * nothing; an expression that produces more candidates than [MAX_CANDIDATES], keeps more alternatives than
 * [MAX_OPTIONS], or nests parentheses deeper than [MAX_DEPTH], is not evaluated.
 */
internal class SpdxExpression(expression: String) {
    private val tokens = TOKEN.findAll(expression).map { it.value }.toList()
    private var position = 0
    private var wellFormed = true
    private var withinBudget = true
    private var candidates = 0
    private var depth = 0

    fun evaluate(): ExpressionResult {
        val options = disjunction()
        return when {
            !withinBudget -> ExpressionResult(null, "SPDX expression too complex to evaluate")
            !wellFormed || position != tokens.size -> ExpressionResult(null, "malformed SPDX expression")
            else -> ExpressionResult(
                LicenseResolver.chooseOption(
                    options.map { option -> option.sortedBy { LicenseResolver.LICENSE_ORDER.indexOf(it) } }
                )
            )
        }
    }

    // disjunction := conjunction ("OR" conjunction)*
    private fun disjunction(): List<Set<KnownLicense>> {
        val first = conjunction()
        // a lone conjunction already holds no option that covers another
        if (!acceptOperator("OR")) return first
        // the alternatives were produced, and charged where they were products, by their conjunctions
        val options = Options()
        for (alternative in first) {
            options.add(alternative, charged = false)
        }
        do {
            for (alternative in conjunction()) {
                options.add(alternative, charged = false)
            }
        } while (acceptOperator("OR"))
        return options.list
    }

    // conjunction := term ("AND" term)*
    private fun conjunction(): List<Set<KnownLicense>> {
        var options = term()
        while (acceptOperator("AND")) {
            val operand = term()
            // an operand every alternative already includes leaves them as they are
            if (operand.isNotEmpty() && options.all { left -> operand.all(left::containsAll) }) continue
            val combined = Options()
            for (left in options) {
                if (!withinBudget) break
                for (right in operand) {
                    combined.add(left + right, charged = true)
                }
            }
            options = combined.list
        }
        return options
    }

    // The alternatives collected so far. A candidate is kept unless an option it covers exists already, and it
    // drops the options that cover it. A candidate seen before costs nothing; a charged one, which is every
    // product of a conjunction, counts against the budget, and the expression is given up once the candidates or
    // the options kept exceed it.
    private inner class Options {
        val list = mutableListOf<Set<KnownLicense>>()
        private val seen = HashSet<Set<KnownLicense>>()

        fun add(candidate: Set<KnownLicense>, charged: Boolean) {
            if (!withinBudget || !seen.add(candidate)) return
            if (charged && ++candidates > MAX_CANDIDATES) {
                withinBudget = false
                return
            }
            if (list.any { candidate.containsAll(it) }) return
            list.removeAll { it.containsAll(candidate) }
            list += candidate
            if (list.size > MAX_OPTIONS) {
                withinBudget = false
            }
        }
    }

    // term := "(" disjunction ")" | license-id ["WITH" exception-id]
    private fun term(): List<Set<KnownLicense>> {
        val token = tokens.getOrNull(position++) ?: return malformed()
        if (token == "(") {
            if (++depth > MAX_DEPTH) {
                withinBudget = false
                return emptyList()
            }
            val inner = disjunction()
            depth--
            return if (tokens.getOrNull(position++) == ")") inner else malformed()
        }
        if (!isLicense(token)) return malformed()
        val license = LicenseResolver.knownLicense(token)
        if (acceptOperator("WITH")) {
            val exception = tokens.getOrNull(position++)
            return if (exception != null && isIdString(exception)) emptyList() else malformed()
        }
        return if (license == null) emptyList() else listOf(setOf(license))
    }

    // a known id, or what the SPDX grammar allows as a simple expression: a license reference, or a license id
    // with an optional "+"; "Public Domain" is known and not SPDX
    private fun isLicense(token: String) = when {
        LicenseResolver.knownLicense(token) != null -> true
        REFERENCE_PREFIX.containsMatchIn(token) -> LICENSE_REFERENCE.matches(token)
        else -> isIdString(token.removeSuffix("+"))
    }

    private fun isIdString(token: String) =
        ID_STRING.matches(token) && OPERATORS.none { it.equals(token, ignoreCase = true) }

    private fun malformed(): List<Set<KnownLicense>> {
        wellFormed = false
        return emptyList()
    }

    private fun acceptOperator(operator: String): Boolean {
        if (!tokens.getOrNull(position).equals(operator, ignoreCase = true)) return false
        position++
        return true
    }

    private companion object {
        // a known id containing whitespace is matched first, so "Public Domain" stays one token, and only up to
        // a token boundary, so "Public DomainOR" is not read as "Public Domain OR"
        val TOKEN = Regex(
            LicenseResolver.SPDX_ID_MAP.keys.filter { it.any(Char::isWhitespace) }
                .joinToString("") { Regex.escape(it) + """(?=[\s()]|$)|""" } +
                """[()]|[^\s()]+""",
            RegexOption.IGNORE_CASE,
        )
        val ID_STRING = Regex("""[A-Za-z0-9.-]+""")
        val REFERENCE_PREFIX = Regex("""^(LicenseRef|DocumentRef)-""", RegexOption.IGNORE_CASE)
        val LICENSE_REFERENCE =
            Regex("""(DocumentRef-[A-Za-z0-9.-]+:)?LicenseRef-[A-Za-z0-9.-]+""", RegexOption.IGNORE_CASE)
        val OPERATORS = listOf("AND", "OR", "WITH")
        const val MAX_OPTIONS = 1024
        const val MAX_CANDIDATES = 16_384
        const val MAX_DEPTH = 32
    }
}
