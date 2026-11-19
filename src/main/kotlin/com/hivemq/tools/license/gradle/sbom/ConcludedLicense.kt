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
package com.hivemq.tools.license.gradle.sbom

import com.hivemq.tools.license.gradle.ResolvedDependency

/** Our reviewed license of a component, written as `concluded` next to the declared entries or in their place. */
internal sealed interface ConcludedLicense {

    /**
     * The elected licenses, every one of which applies, each written as a license object next to the declared
     * entries; one with a space, such as `Public Domain`, by name.
     */
    data class Elected(val ids: List<String>) : ConcludedLicense {
        init {
            require(ids.isNotEmpty()) { "needs at least one elected license" }
        }
    }

    /**
     * The [ResolvedDependency.declaredExpression], written as the one SPDX expression that replaces the declared
     * entries, since a CycloneDX 1.6 license list holds either license objects or one expression.
     */
    data class Expression(val text: String) : ConcludedLicense

    companion object {
        /** The [ResolvedDependency.declaredExpression] when there is one, else the [Elected] licenses. */
        fun of(dependency: ResolvedDependency): ConcludedLicense =
            dependency.declaredExpression?.let(::Expression) ?: Elected(dependency.licenses.map { it.id })
    }
}
