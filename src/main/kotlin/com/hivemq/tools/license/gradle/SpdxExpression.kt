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
 * An SPDX license expression such as "(CDDL-1.0 OR GPL-2.0-with-classpath-exception)", read once into its syntax
 * [tree] and into every way it can be satisfied, each one the licenses that then apply together, which [evaluate]
 * ranks once the whole expression is read, so an alternative is never chosen before an enclosing AND adds to it.
 * "A OR B" is satisfied by any approved alternative, and the one with the fewest obligations is reported; "A AND B"
 * needs every operand approved and reports all of them. AND binds tighter than OR, parentheses group, a license WITH
 * an exception is not approved as the license, and a malformed expression is never satisfied. An unknown license
 * satisfies nothing; a syntax error is tracked apart, so "MIT OR )" is not satisfied by its MIT. An alternative that
 * adds licenses to another can never rank better, so it is dropped as soon as it appears. A candidate seen before,
 * and an AND operand every alternative already includes, cost nothing; an expression that produces more candidates
 * than [MAX_CANDIDATES], keeps more alternatives than [MAX_OPTIONS], or nests parentheses deeper than [MAX_DEPTH], is
 * not evaluated and has no tree.
 */
internal class SpdxExpression(expression: String) {
    private val tokens = TOKEN.findAll(expression).map { it.value }.toList()
    private var position = 0
    private var wellFormed = true
    private var withinBudget = true
    private var candidates = 0
    private var depth = 0

    /** The whole expression, read after the state above because properties are initialised in declaration order. */
    private val parsed = disjunction()

    /** Why the expression cannot be evaluated, or null when it can. */
    private val problem: String? = when {
        !withinBudget -> "SPDX expression too complex to evaluate"
        !wellFormed || position != tokens.size -> "malformed SPDX expression"
        else -> null
    }

    /** The syntax tree, or null when the expression is malformed or too complex to evaluate. */
    val tree: Node? = if (problem == null) parsed.node else null

    /** The licenses of the alternative with the fewest obligations, or why the expression cannot be evaluated. */
    fun evaluate(): ExpressionResult {
        if (problem != null) return ExpressionResult(null, problem)
        val options = parsed.options.map { option -> option.sortedBy { LicenseResolver.LICENSE_ORDER.indexOf(it) } }
        return ExpressionResult(LicenseResolver.chooseOption(options))
    }

    /** A node of the syntax tree: a leaf, which is a license with the exception of its WITH clause, or AND or OR. */
    sealed interface Node {
        /** A license id or reference as written, with the exception id when a WITH clause names one. */
        data class Leaf(val id: String, val exception: String? = null) : Node

        data class And(val operands: List<Node>) : Node

        data class Or(val operands: List<Node>) : Node

        /**
         * The same expression in canonical form: known ids in the case of the approved list, deprecated license and
         * exception ids in the form SPDX names for them, nested groups of one operator flattened, a repeated operand
         * written once, its ids compared regardless of case as SPDX matches them, and a group of one operand replaced
         * by it. Null when a license is not an SPDX id, such as `Public Domain`.
         */
        fun canonical(): Node? = when (this) {
            is Leaf -> canonicalLeaf(this)
            is And -> canonicalGroup(operands, { (it as? And)?.operands }, { And(it) })
            is Or -> canonicalGroup(operands, { (it as? Or)?.operands }, { Or(it) })
        }

        /**
         * The canonical form as text, every group's operands sorted and ids in lower case, as SPDX matches them, so
         * that expressions differing only in order and case get the same key; null without a canonical form.
         */
        fun key(): String? = canonical()?.orderFree()

        /** As text; AND binds tighter than OR, so only an OR under AND and an AND under OR are parenthesised. */
        fun render(): String = when (this) {
            is Leaf -> if (exception == null) id else "$id WITH $exception"
            is And -> operands.joinToString(" AND ") { if (it is Or) "(${it.render()})" else it.render() }
            is Or -> operands.joinToString(" OR ") { if (it is And) "(${it.render()})" else it.render() }
        }
    }

    /** What a rule read: the ways it can be satisfied, and its syntax tree, null when malformed or over budget. */
    private class Parsed(val options: List<Set<KnownLicense>>, val node: Node?)

    /** disjunction := conjunction ("OR" conjunction)* */
    private fun disjunction(): Parsed {
        val first = conjunction()
        // a lone conjunction already holds no option that covers another
        if (!acceptOperator("OR")) return first
        // the alternatives were produced, and charged where they were products, by their conjunctions
        val options = Options()
        val nodes = mutableListOf(first.node)
        for (alternative in first.options) {
            options.add(alternative, charged = false)
        }
        do {
            val next = conjunction()
            nodes += next.node
            for (alternative in next.options) {
                options.add(alternative, charged = false)
            }
        } while (acceptOperator("OR"))
        return Parsed(options.list, nodes.grouped { Node.Or(it) })
    }

    /** conjunction := term ("AND" term)* */
    private fun conjunction(): Parsed {
        val first = term()
        var options = first.options
        val nodes = mutableListOf(first.node)
        while (acceptOperator("AND")) {
            val right = term()
            nodes += right.node
            val operand = right.options
            // an operand every alternative already includes leaves them as they are
            if (operand.isNotEmpty() && options.all { left -> operand.all(left::containsAll) }) continue
            val combined = Options()
            for (left in options) {
                if (!withinBudget) break
                for (licenses in operand) {
                    combined.add(left + licenses, charged = true)
                }
            }
            options = combined.list
        }
        return Parsed(options, nodes.grouped { Node.And(it) })
    }

    /** The operands as one node, a group of them when there are several, or null when one of them is null. */
    private fun List<Node?>.grouped(group: (List<Node>) -> Node): Node? {
        val nodes = map { it ?: return null }
        return nodes.singleOrNull() ?: group(nodes)
    }

    /**
     * The alternatives collected so far: a candidate covering a kept option is not kept, and one that is kept drops
     * the options covering it. A charged candidate, every product of a conjunction, counts against the budget.
     */
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

    /** term := "(" disjunction ")" | license-id ["WITH" exception-id] */
    private fun term(): Parsed {
        val token = tokens.getOrNull(position++) ?: return malformed()
        if (token == "(") {
            if (++depth > MAX_DEPTH) {
                withinBudget = false
                return Parsed(emptyList(), null)
            }
            val inner = disjunction()
            depth--
            return if (tokens.getOrNull(position++) == ")") inner else malformed()
        }
        if (!isLicense(token)) return malformed()
        if (acceptOperator("WITH")) {
            val exception = tokens.getOrNull(position++)
            if (exception == null || !isIdString(exception)) return malformed()
            // not approved as the license, but part of what was declared
            return Parsed(emptyList(), Node.Leaf(token, exception))
        }
        val license = LicenseResolver.knownLicense(token)
        return Parsed(if (license == null) emptyList() else listOf(setOf(license)), Node.Leaf(token))
    }

    /** A known id, or what the SPDX grammar allows as a simple expression; "Public Domain" is known and not SPDX. */
    private fun isLicense(token: String) = LicenseResolver.knownLicense(token) != null || isLicenseId(token)

    private fun malformed(): Parsed {
        wellFormed = false
        return Parsed(emptyList(), null)
    }

    private fun acceptOperator(operator: String): Boolean {
        if (!tokens.getOrNull(position).equals(operator, ignoreCase = true)) return false
        position++
        return true
    }

    companion object {
        /**
         * The current form of each deprecated license id, keyed by that id in lower case, as the `obsoletedBy` entries
         * of the SPDX license list (3.29.0) name it: `GPL-2.0` is `GPL-2.0-only` and `GPL-2.0+` is `GPL-2.0-or-later`;
         * `GPL-2.0-with-classpath-exception` is `GPL-2.0 WITH Classpath-exception-2.0`, written with `GPL-2.0-only`
         * because SPDX deprecates that `GPL-2.0` too, and its `+` form is `GPL-2.0-or-later WITH` the same exception
         * (SPDX's entry for the font exception has a stray `+` after `-or-later`, left out here). A deprecated id SPDX
         * names no replacement for stays as written, a deprecated id being a valid one.
         */
        private val DEPRECATED_IDS: Map<String, Node.Leaf> = buildMap {
            val licenses = listOf(
                "AGPL-1.0", "AGPL-3.0", "GFDL-1.1", "GFDL-1.2", "GFDL-1.3", "GPL-1.0", "GPL-2.0", "GPL-3.0", "LGPL-2.0",
                "LGPL-2.1", "LGPL-3.0",
            )
            for (license in licenses) {
                put(license.lowercase(), Node.Leaf("$license-only"))
                put("$license+".lowercase(), Node.Leaf("$license-or-later"))
            }
            val exceptions = mapOf(
                "GPL-2.0-with-autoconf-exception" to "Autoconf-exception-2.0",
                "GPL-2.0-with-bison-exception" to "Bison-exception-2.2",
                "GPL-2.0-with-classpath-exception" to "Classpath-exception-2.0",
                "GPL-2.0-with-font-exception" to "Font-exception-2.0",
                "GPL-2.0-with-GCC-exception" to "GCC-exception-2.0",
                "GPL-3.0-with-autoconf-exception" to "Autoconf-exception-3.0",
                "GPL-3.0-with-GCC-exception" to "GCC-exception-3.1",
            )
            for ((id, exception) in exceptions) {
                val license = id.substringBefore("-with-")
                put(id.lowercase(), Node.Leaf("$license-only", exception))
                put("$id+".lowercase(), Node.Leaf("$license-or-later", exception))
            }
            put("bsd-2-clause-netbsd", Node.Leaf("BSD-2-Clause"))
            put("ecos-2.0", Node.Leaf("GPL-2.0-or-later", "eCos-exception-2.0"))
            put("nunit", Node.Leaf("zlib-acknowledgement"))
            put("standardml-nj", Node.Leaf("SMLNJ"))
            put("wxwindows", Node.Leaf("GPL-2.0-or-later", "WxWindows-exception-3.1"))
        }

        /** The current form of each deprecated exception id, keyed by that id in lower case, as SPDX names it. */
        private val DEPRECATED_EXCEPTIONS = mapOf("nokia-qt-exception-1.1" to "Qt-LGPL-exception-1.1")

        /**
         * One canonical expression naming every way the [alternatives] allow use, when they name more than one
         * license; null for a single license, for no alternative, and when an alternative is null or has no SPDX form.
         */
        fun expressionOf(alternatives: List<Node?>): String? {
            if (alternatives.isEmpty()) return null
            val nodes = alternatives.map { it ?: return null }
            val canonical = Node.Or(nodes).canonical() ?: return null
            return if (canonical is Node.Leaf) null else canonical.render()
        }

        /**
         * Whether [first] and [second] combine the same licenses in the same way: equal in canonical form, whatever the
         * order of the operands of a group. False when either is null or has no canonical form; forms that group the
         * same licenses differently, such as a distributed AND, are not recognised as equivalent.
         */
        fun sameExpression(first: Node?, second: Node?): Boolean {
            val one = first?.key() ?: return false
            return one == second?.key()
        }

        /** As text, every group's operands sorted and written once and ids in lower case; the base of [Node.key]. */
        private fun Node.orderFree(): String = orderFreeNode().text()

        /**
         * The tree with the operands of every group in order-free form, flattened into a parent of the same operator,
         * written once and sorted; a group left with one operand stands for it, which its parent may then flatten.
         */
        private fun Node.orderFreeNode(): Node = when (this) {
            is Node.Leaf -> this
            is Node.And -> orderFreeGroup(operands, { (it as? Node.And)?.operands }, { Node.And(it) })
            is Node.Or -> orderFreeGroup(operands, { (it as? Node.Or)?.operands }, { Node.Or(it) })
        }

        /** The [operands] of one group in order-free form, those of the [sameOperator] flattened in, as one [group]. */
        private fun orderFreeGroup(
            operands: List<Node>,
            sameOperator: (Node) -> List<Node>?,
            group: (List<Node>) -> Node,
        ): Node {
            val members = operands.map { it.orderFreeNode() }.flatMap { sameOperator(it) ?: listOf(it) }
                .distinctBy { it.text() }
                .sortedBy { it.text() }
            return members.singleOrNull() ?: group(members)
        }

        /** An order-free tree as text, ids in lower case and every group in parentheses. */
        private fun Node.text(): String = when (this) {
            is Node.Leaf -> render().lowercase()
            is Node.And -> operands.joinToString(" AND ", "(", ")") { it.text() }
            is Node.Or -> operands.joinToString(" OR ", "(", ")") { it.text() }
        }

        /** What the SPDX grammar allows as a simple expression: a license reference, or an id with an optional "+". */
        private fun isLicenseId(token: String) = when {
            REFERENCE_PREFIX.containsMatchIn(token) -> LICENSE_REFERENCE.matches(token)
            else -> isIdString(token.removeSuffix("+"))
        }

        /**
         * A known id in the case of the approved list, also in front of a `+`, and a deprecated license or exception
         * id in its current form; null for a license that is not an SPDX id, such as `Public Domain`. A deprecated id
         * whose current form names an exception, such as a `-with-` id, carries one itself, so with a WITH clause of
         * its own its id is left as written; any other deprecated id keeps its WITH clause.
         */
        private fun canonicalLeaf(leaf: Node.Leaf): Node.Leaf? {
            val bare = leaf.id.removeSuffix("+")
            val id = (LicenseResolver.knownLicense(bare)?.id ?: bare) + leaf.id.substring(bare.length)
            if (!isLicenseId(id)) return null
            val exception = leaf.exception?.let { DEPRECATED_EXCEPTIONS[it.lowercase()] ?: it }
            val current = DEPRECATED_IDS[id.lowercase()]
            return when {
                current == null -> Node.Leaf(id, exception)
                exception == null -> current
                current.exception != null -> Node.Leaf(id, exception)
                else -> Node.Leaf(current.id, exception)
            }
        }

        /**
         * The [operands] in canonical form, those of the same operator flattened in and repeats dropped, as one
         * [group]; one operand stands alone. Null when an operand has no canonical form.
         */
        private fun canonicalGroup(
            operands: List<Node>,
            sameOperator: (Node) -> List<Node>?,
            group: (List<Node>) -> Node,
        ): Node? {
            val members = operands.flatMap { operand ->
                val canonical = operand.canonical() ?: return null
                sameOperator(canonical) ?: listOf(canonical)
            }
            // SPDX matches ids regardless of case; the first spelling stays
            val distinct = members.distinctBy { it.render().lowercase() }
            return distinct.singleOrNull() ?: group(distinct)
        }

        private fun isIdString(token: String) =
            ID_STRING.matches(token) && OPERATORS.none { it.equals(token, ignoreCase = true) }

        /**
         * A known id containing whitespace is matched first, so "Public Domain" stays one token, and only up to a token
         * boundary, so "Public DomainOR" is not read as "Public Domain OR".
         */
        private val TOKEN = Regex(
            LicenseResolver.SPDX_ID_MAP.keys.filter { it.any(Char::isWhitespace) }
                .joinToString("") { Regex.escape(it) + """(?=[\s()]|$)|""" } +
                """[()]|[^\s()]+""",
            RegexOption.IGNORE_CASE,
        )
        private val ID_STRING = Regex("""[A-Za-z0-9.-]+""")
        private val REFERENCE_PREFIX = Regex("""^(LicenseRef|DocumentRef)-""", RegexOption.IGNORE_CASE)
        private val LICENSE_REFERENCE =
            Regex("""(DocumentRef-[A-Za-z0-9.-]+:)?LicenseRef-[A-Za-z0-9.-]+""", RegexOption.IGNORE_CASE)
        private val OPERATORS = listOf("AND", "OR", "WITH")
        private const val MAX_OPTIONS = 1024
        private const val MAX_CANDIDATES = 16_384
        private const val MAX_DEPTH = 32
    }
}
