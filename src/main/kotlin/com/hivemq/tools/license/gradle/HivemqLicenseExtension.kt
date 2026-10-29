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

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import java.time.Instant

interface HivemqLicenseExtension {
    val projectName: Property<String>
    val thirdPartyLicenseDirectory: DirectoryProperty
    val ignoredGroupPrefixes: SetProperty<String>
    val allowedArtifacts: SetProperty<String>
    val additionalConfigurations: SetProperty<String>
    val overriddenLicenses: MapProperty<String, String>

    /**
     * File name of the SBOM written by `generateSbom`, without the `.cdx.json` suffix; the project name by default. A
     * blank name, or one with `/` or `\`, fails the build.
     */
    val sbomName: Property<String>

    /**
     * `metadata.timestamp` of that SBOM. Defaults to `SOURCE_DATE_EPOCH`, else the time of the repository's HEAD
     * commit (`git log -1`), else the current time (with a warning, because the file is then not reproducible).
     */
    val sbomTimestamp: Property<Instant>

    /** Who creates the SBOM and ships the component, written as the manufacturer; "HiveMQ GmbH" by default. */
    val sbomCreatorName: Property<String>

    /**
     * Email address or http(s) URL of that organisation, anything else fails `generateSbom`; `security@hivemq.com` by
     * default.
     */
    val sbomCreator: Property<String>

    /**
     * File name of the shipped top-level artifact. The default is the archive file name of the `jar` task, and it
     * exists only when the `java` plugin is applied; without that, the SBOM names no file unless this is set.
     */
    val sbomArtifactFileName: Property<String>
}
