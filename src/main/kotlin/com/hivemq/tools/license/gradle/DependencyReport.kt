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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

class DependencyReport {

    @JsonIgnoreProperties(ignoreUnknown = true)
    class Bom(
        @JsonProperty("components")
        components: List<Component>? = null,

        @param:JsonProperty("metadata")
        val metadata: Metadata? = null,
    ) {
        /** Empty when the BOM has no component, since CycloneDX leaves an empty list out. */
        val components: List<Component> = components.orEmpty()
    }

    /** The part of the metadata the resolver reads: the top-level component, for the components nested in it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    class Metadata(
        @param:JsonProperty("component")
        val component: TopLevelComponent? = null,
    )

    /** Only the nested components: the top-level component itself is never resolved, and may lack a version. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    class TopLevelComponent(
        @param:JsonProperty("components")
        val components: List<Component>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    class Component(
        @param:JsonProperty("group")
        val group: String?,

        @param:JsonProperty("name")
        val name: String,

        @param:JsonProperty("version")
        val version: String,

        @param:JsonProperty("licenses")
        val licenses: List<LicenseChoice>? = null,

        @param:JsonProperty("properties")
        val properties: List<Property>? = null,

        /** Components nested in this one, which CycloneDX allows and cyclonedx-gradle-plugin never writes. */
        @param:JsonProperty("components")
        val components: List<Component>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    class Property(
        @param:JsonProperty("name")
        val name: String? = null,

        @param:JsonProperty("value")
        val value: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    class LicenseChoice(
        @param:JsonProperty("license")
        val license: LicenseEntry? = null,

        @param:JsonProperty("expression")
        val expression: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    class LicenseEntry(
        @param:JsonProperty("id")
        val id: String? = null,

        @param:JsonProperty("name")
        val name: String? = null,

        @param:JsonProperty("url")
        val url: String? = null,
    )
}
