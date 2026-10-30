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

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Removes the components of the given `group:name` modules from a BOM tree, and every reference to them. */
internal object BomFilter {

    /** Changes [root] in place: every version of [modules] goes, `metadata.component` stays even when it matches. */
    fun dropModules(root: ObjectNode, modules: Set<String>) {
        if (modules.isEmpty()) return
        val components = root.get("components") as? ArrayNode ?: return
        val (kept, removed) = components.partition { component ->
            "${component.get("group")?.asText().orEmpty()}:${component.get("name")?.asText().orEmpty()}" !in modules
        }
        val dropped = removed.mapNotNull { it.get("bom-ref")?.asText() }.toSet()
        components.removeAll()
        kept.forEach(components::add)
        val dependencies = root.get("dependencies") as? ArrayNode ?: return
        // a dependency entry of a dropped component goes, a reference to one goes from every list
        val keptDependencies = dependencies.filter { it.get("ref")?.asText() !in dropped }
        dependencies.removeAll()
        keptDependencies.forEach(dependencies::add)
        for (dependency in dependencies) {
            val dependsOn = dependency.get("dependsOn") as? ArrayNode ?: continue
            val keptRefs = dependsOn.filter { it.asText() !in dropped }
            dependsOn.removeAll()
            keptRefs.forEach(dependsOn::add)
        }
    }
}
