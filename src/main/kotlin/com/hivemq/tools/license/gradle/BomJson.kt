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

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** Reads the JSON BOM of the CycloneDX Gradle plugin, so that everything that reads it rejects a bad file alike. */
internal object BomJson {

    private val mapper = ObjectMapper()

    /**
     * Throws [SbomException] when [bom] is not JSON, its root is not an object or its `metadata` is not an object,
     * which the enricher and the normalizer both write to.
     */
    fun read(bom: ByteArray): ObjectNode {
        val root = try {
            mapper.readTree(bom)
        } catch (e: JsonProcessingException) {
            val location = e.location?.let { " at line ${it.lineNr}, column ${it.columnNr}" }.orEmpty()
            throw SbomException("The BOM is not valid JSON: ${e.originalMessage}$location")
        }
        if (root !is ObjectNode) throw SbomException("The BOM is not a JSON object")
        val metadata = root.get("metadata")
        if (metadata != null && metadata !is ObjectNode) throw SbomException("The BOM's metadata is not a JSON object")
        return root
    }
}
