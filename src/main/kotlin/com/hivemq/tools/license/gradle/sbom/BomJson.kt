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

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** Reads the JSON BOM of the CycloneDX Gradle plugin, so that everything that reads it rejects a bad file alike. */
internal object BomJson {

    // Jackson reads one value and ignores whatever follows, so a second document would go unnoticed
    private val mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    /**
     * Throws [SbomException] when [bom] is not JSON, has anything but white space after its value, its root is not
     * an object or its `metadata` is not an object, which the enricher and the normalizer both write to.
     */
    fun read(bom: ByteArray): ObjectNode {
        val root = try {
            mapper.readTree(bom)
        } catch (e: JsonProcessingException) {
            val location = e.location?.let { " at line ${it.lineNr}, column ${it.columnNr}" }.orEmpty()
            // a second value after a complete one is Jackson's "trailing token", said in the file's terms
            val problem = when (e) {
                is MismatchedInputException -> "has content after its JSON value"
                else -> "is not valid JSON: ${e.originalMessage}"
            }
            throw SbomException("The BOM $problem$location")
        }
        if (root !is ObjectNode) throw SbomException("The BOM is not a JSON object")
        val metadata = root.get("metadata")
        if (metadata != null && metadata !is ObjectNode) throw SbomException("The BOM's metadata is not a JSON object")
        return root
    }
}
