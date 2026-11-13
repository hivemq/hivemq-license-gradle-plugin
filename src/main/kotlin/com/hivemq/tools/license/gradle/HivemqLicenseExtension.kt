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
    /**
     * Group prefixes of the build's own modules, `com.hivemq` by default; a package without a group, from a pnpm
     * report, is matched by its name, `@hivemq/` for example. Every module under them is in [allowedArtifacts], in
     * [internalArtifacts] or built by this build, or the build fails.
     */
    val ignoredGroupPrefixes: SetProperty<String>

    /**
     * Own modules listed like third-party libraries, as `group:name` or a package name, where `*` matches anything;
     * HiveMQ's libraries on Maven Central by default. Add to the set, `set` replaces the defaults.
     */
    val allowedArtifacts: SetProperty<String>

    /**
     * Own modules left out of the reports, the check and the SBOM, which attaches their dependencies to the dependant
     * instead; same syntax, HiveMQ's internal modules by default. A project of this build or of an included build is
     * internal unless it is in [allowedArtifacts].
     */
    val internalArtifacts: SetProperty<String>

    val additionalConfigurations: SetProperty<String>
    val overriddenLicenses: MapProperty<String, String>

    /**
     * File name of the SBOM written by `generateSbom`, without the `.cdx.json` suffix; the project name by default. A
     * blank name, or one with `/` or `\`, fails the build.
     */
    val sbomName: Property<String>

    /**
     * `metadata.timestamp` of that SBOM. Defaults to `SOURCE_DATE_EPOCH`, else the time of the repository's HEAD
     * commit (`git log -1`), else the current time (with a warning, because the file is then not reproducible). It
     * needs a four-digit year.
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
     * File name of the shipped top-level artifact, left out of the SBOM without a value; the default, only with the
     * `java` plugin, is the archive file name of the `jar` task, or of `shadowJar` when the Shadow plugin is applied.
     */
    val sbomArtifactFileName: Property<String>
}
