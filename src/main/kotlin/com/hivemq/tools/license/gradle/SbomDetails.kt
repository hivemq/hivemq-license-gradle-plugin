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

/** What the build knows about the SBOM's component and its dependencies beyond what CycloneDX writes. */
internal data class SbomDetails(
    /** Name of the organisation that created the SBOM and ships the component, e.g. "HiveMQ GmbH". */
    val creatorName: String,
    /** Email address or URL of that organisation. */
    val creator: String,
    /** File name of the shipped top-level artifact, null when unknown. */
    val artifactFileName: String?,
    /**
     * File name of every resolved component, by the `group:name:version` that [Coordinates.toString] writes, for
     * example `org.slf4j:slf4j-api:2.0.16` to `slf4j-api-2.0.16.jar`.
     */
    val componentFileNames: Map<String, String>,
    /**
     * The reviewed licenses of the components that resolve, keyed like [componentFileNames]; the license tasks resolve
     * one version of each module, so another version in the BOM has no entry. Every license of a list applies; one with
     * a space, such as `Public Domain`, is not an SPDX id and is written as a `name`.
     */
    val concludedLicenses: Map<String, List<String>>,
    /**
     * Why the dependency graph is not complete, one reason per finding, such as a file dependency or a module that
     * could not be resolved; empty when it is complete.
     */
    val incompleteReasons: List<String>,
)
