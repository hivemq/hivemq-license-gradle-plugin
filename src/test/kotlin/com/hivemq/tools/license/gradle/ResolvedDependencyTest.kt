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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResolvedDependencyTest {

    @Test
    fun `stores coordinates and license`() {
        val coordinates = Coordinates("com.google.guava", "guava", "32.1.3-jre")
        val dep = ResolvedDependency(coordinates, KnownLicense.APACHE_2_0)
        assertThat(dep.coordinates).isEqualTo(coordinates)
        assertThat(dep.license).isEqualTo(KnownLicense.APACHE_2_0)
    }

    @Test
    fun `data class equality`() {
        val a = ResolvedDependency(Coordinates("g", "n", "v"), KnownLicense.MIT)
        val b = ResolvedDependency(Coordinates("g", "n", "v"), KnownLicense.MIT)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `data class inequality on different license`() {
        val a = ResolvedDependency(Coordinates("g", "n", "v"), KnownLicense.MIT)
        val b = ResolvedDependency(Coordinates("g", "n", "v"), KnownLicense.APACHE_2_0)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `data class inequality on different coordinates`() {
        val a = ResolvedDependency(Coordinates("g", "n", "1.0"), KnownLicense.MIT)
        val b = ResolvedDependency(Coordinates("g", "n", "2.0"), KnownLicense.MIT)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun destructuring() {
        val dep = ResolvedDependency(Coordinates("com.example", "lib", "1.0"), KnownLicense.EPL_2_0)
        val (coordinates, license) = dep
        assertThat(coordinates.group).isEqualTo("com.example")
        assertThat(coordinates.name).isEqualTo("lib")
        assertThat(coordinates.version).isEqualTo("1.0")
        assertThat(license).isEqualTo(KnownLicense.EPL_2_0)
    }

    @Test
    fun `copy with different license`() {
        val original = ResolvedDependency(Coordinates("g", "n", "v"), KnownLicense.MIT)
        val copy = original.copy(license = KnownLicense.APACHE_2_0)
        assertThat(copy.license).isEqualTo(KnownLicense.APACHE_2_0)
        assertThat(copy.coordinates).isEqualTo(original.coordinates)
    }
}
