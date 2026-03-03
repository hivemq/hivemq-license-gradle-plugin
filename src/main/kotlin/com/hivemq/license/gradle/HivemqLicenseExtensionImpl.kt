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
package com.hivemq.license.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

abstract class HivemqLicenseExtensionImpl @Inject constructor(
    objectFactory: ObjectFactory,
) : HivemqLicenseExtension {
    override val projectName: Property<String> = objectFactory.property(String::class.java)
    override val ignoredGroupPrefixes: SetProperty<String> = objectFactory.setProperty(String::class.java)
        .convention(setOf("com.hivemq"))
    override val allowedArtifacts: SetProperty<String> = objectFactory.setProperty(String::class.java)
        .convention(setOf("com.hivemq:hivemq-mqtt-client"))
    override val overriddenLicenses: MapProperty<String, String> =
        objectFactory.mapProperty(String::class.java, String::class.java).convention(emptyMap())
    override val thirdPartyLicenseDirectory: DirectoryProperty = objectFactory.directoryProperty()
}
