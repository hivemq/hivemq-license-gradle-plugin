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

import com.fasterxml.jackson.databind.ObjectMapper
import com.hivemq.tools.license.gradle.FixtureRepository.PomLicense
import com.hivemq.tools.license.gradle.sbom.GitTestRepository
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Runs the license tasks over modules that declare the license lists of the published POMs of the modules in
 * [LicenseResolver.BUILT_IN_EXPRESSIONS], so that an update of the CycloneDX Gradle plugin that writes one of these
 * lists differently fails here, before a release, instead of in a consumer's build.
 */
class BuiltInExpressionsIntegrationTest {

    @TempDir
    lateinit var projectDir: File

    /**
     * A parent POM at [coordinates], `group:name:version`, declaring the [licenses] its modules inherit, or none and
     * inheriting them from its own [parent] in turn.
     */
    private data class ParentPom(
        val coordinates: String,
        val licenses: List<PomLicense> = emptyList(),
        val parent: ParentPom? = null,
    )

    /**
     * A module as published at [coordinates], `group:name:version`, with the [licenses] its POM declares, or none and
     * the [parent] POM it inherits them from, and the [expression] of BUILT_IN_EXPRESSIONS that list resolves to.
     */
    private data class PublishedModule(
        val coordinates: String,
        val expression: String,
        val licenses: List<PomLicense>,
        val parent: ParentPom? = null,
    ) {
        // group:name, as BUILT_IN_EXPRESSIONS names the module
        val moduleId = coordinates.substringBeforeLast(':')
    }

    /** Each of [modules], `group:name:version`, declaring the [licenses] that resolve to [expression] in its POM. */
    private fun declaring(expression: String, licenses: List<PomLicense>, vararg modules: String) =
        modules.map { PublishedModule(it, expression, licenses) }

    /** Each of [modules], `group:name:version`, inheriting from [parent] the licenses that resolve to [expression]. */
    private fun inheriting(expression: String, parent: ParentPom, vararg modules: String) =
        modules.map { PublishedModule(it, expression, emptyList(), parent) }

    // the choice of the Jakarta EE APIs, HK2, Jersey and RESTEasy
    private val eplOrGpl = "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0"

    // the list of the HK2 parents, jakarta.servlet-api and javax.ws.rs-api
    private val hk2Licenses = listOf(
        PomLicense("EPL 2.0", "http://www.eclipse.org/legal/epl-2.0"),
        PomLicense("GPL2 w/ CPE", "https://www.gnu.org/software/classpath/license.html"),
    )

    // the list of the Eclipse Collections parents
    private val eclipseCollectionsLicenses = listOf(
        PomLicense("Eclipse Public License - v 1.0", "https://www.eclipse.org/legal/epl-v10.html"),
        PomLicense("Eclipse Distribution License - v 1.0", "https://www.eclipse.org/licenses/edl-v10.html"),
    )

    // the list of jetty-project, the grandparent of the Jetty modules, and of jetty-bom
    private val jettyLicenses = listOf(
        PomLicense("EPL-2.0", "https://www.eclipse.org/legal/epl-2.0/"),
        PomLicense("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt"),
    )

    // the list of the JNA modules at the version the consumers use
    private val jnaLicenses = listOf(
        PomLicense("LGPL-2.1-or-later", "https://www.gnu.org/licenses/old-licenses/lgpl-2.1"),
        PomLicense("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt"),
    )

    // the list of the java-json-tools modules
    private val javaJsonToolsLicenses = listOf(
        PomLicense("Lesser General Public License, version 3 or greater", "http://www.gnu.org/licenses/lgpl.html"),
        PomLicense("Apache Software License, version 2.0", "http://www.apache.org/licenses/LICENSE-2.0"),
    )

    // the first three entries of the Jersey modules that declare a list of their own
    private val jerseyModuleLicenses = listOf(
        PomLicense("EPL 2.0", "http://www.eclipse.org/legal/epl-2.0"),
        PomLicense(
            "The GNU General Public License (GPL), Version 2, With Classpath Exception",
            "https://www.gnu.org/software/classpath/license.html",
        ),
        PomLicense("Apache License, 2.0", "http://www.apache.org/licenses/LICENSE-2.0.html"),
    )

    // the parent chains as published, up to the POM that declares the list, so inheritance across levels is covered
    private val jettyProject = ParentPom("org.eclipse.jetty:jetty-project:12.1.15", jettyLicenses)
    private val hk2Project = ParentPom("org.glassfish.hk2:hk2-project:4.0.3", hk2Licenses)
    private val hk2Parent = ParentPom("org.glassfish.hk2:hk2-parent:4.0.3", parent = hk2Project)
    private val jerseyProject = ParentPom(
        "org.glassfish.jersey:project:4.0.3",
        listOf(
            PomLicense("EPL 2.0", "http://www.eclipse.org/legal/epl-2.0"),
            PomLicense("GPL2 w/ CPE", "https://www.gnu.org/software/classpath/license.html"),
            PomLicense("EDL 1.0", "http://www.eclipse.org/org/documents/edl-v10.php"),
            PomLicense("BSD 2-Clause", "https://opensource.org/licenses/BSD-2-Clause"),
            PomLicense("Apache License, 2.0", "http://www.apache.org/licenses/LICENSE-2.0.html"),
            PomLicense("Public Domain", "https://creativecommons.org/publicdomain/zero/1.0/"),
            PomLicense("Modified BSD", "https://asm.ow2.io/license.html"),
            PomLicense("jQuery license", "jquery.org/license"),
            PomLicense("MIT license", "http://www.opensource.org/licenses/mit-license.php"),
            PomLicense("W3C license", "https://www.w3.org/Consortium/Legal/copyright-documents-19990405"),
        ),
    )

    // every module of BUILT_IN_EXPRESSIONS once, at the newest version the consumers use, with the list of its POM
    private val current = listOf(
        inheriting(
            "CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0",
            ParentPom(
                "javax.xml.bind:jaxb-api-parent:2.3.1",
                listOf(
                    PomLicense("CDDL 1.1", "https://oss.oracle.com/licenses/CDDL+GPL-1.1"),
                    PomLicense("GPL2 w/ CPE", "https://oss.oracle.com/licenses/CDDL+GPL-1.1"),
                ),
            ),
            "javax.xml.bind:jaxb-api:2.3.1",
        ),
        inheriting(
            "EPL-1.0 OR BSD-3-Clause",
            ParentPom("org.eclipse.collections:eclipse-collections-parent:13.0.0", eclipseCollectionsLicenses),
            "org.eclipse.collections:eclipse-collections:13.0.0",
            "org.eclipse.collections:eclipse-collections-api:13.0.0",
        ),
        inheriting(
            "EPL-1.0 OR BSD-3-Clause",
            ParentPom("org.eclipse.collections:eclipse-collections-parent:10.4.0", eclipseCollectionsLicenses),
            "org.eclipse.collections:eclipse-collections-forkjoin:10.4.0",
        ),
        inheriting(
            "EPL-2.0 OR Apache-2.0",
            ParentPom("org.eclipse.jetty.ee11:jetty-ee11:12.1.15", parent = jettyProject),
            "org.eclipse.jetty.ee11:jetty-ee11-servlet:12.1.15",
        ),
        inheriting(
            "EPL-2.0 OR Apache-2.0",
            ParentPom("org.eclipse.jetty:jetty-core:12.1.15", parent = jettyProject),
            "org.eclipse.jetty:jetty-http:12.1.15",
            "org.eclipse.jetty:jetty-io:12.1.15",
            "org.eclipse.jetty:jetty-security:12.1.15",
            "org.eclipse.jetty:jetty-server:12.1.15",
            "org.eclipse.jetty:jetty-session:12.1.15",
            "org.eclipse.jetty:jetty-util:12.1.15",
        ),
        declaring("EPL-2.0 OR Apache-2.0", jettyLicenses, "org.eclipse.jetty:jetty-bom:12.1.15"),
        inheriting(
            "EPL-2.0 OR BSD-3-Clause",
            ParentPom(
                "org.locationtech.jts:jts-modules:1.20.0",
                parent = ParentPom(
                    "org.locationtech.jts:jts:1.20.0",
                    listOf(
                        PomLicense(
                            "Eclipse Public License, Version 2.0",
                            "https://github.com/locationtech/jts/blob/master/LICENSE_EPLv2.txt",
                        ),
                        PomLicense(
                            "Eclipse Distribution License - v 1.0",
                            "https://github.com/locationtech/jts/blob/master/LICENSE_EDLv1.txt",
                        ),
                    ),
                ),
            ),
            "org.locationtech.jts:jts-core:1.20.0",
        ),
        declaring(
            eplOrGpl,
            listOf(
                PomLicense("EPL 2.0", "https://www.eclipse.org/legal/epl-2.0"),
                PomLicense("GPL2 w/ CPE", "https://www.gnu.org/software/classpath/license.html"),
            ),
            "jakarta.annotation:jakarta.annotation-api:3.0.0",
        ),
        declaring(
            eplOrGpl,
            hk2Licenses,
            "jakarta.servlet:jakarta.servlet-api:6.1.0",
            "javax.ws.rs:javax.ws.rs-api:2.1.1",
        ),
        inheriting(
            eplOrGpl,
            ParentPom(
                "jakarta.ws.rs:all:4.0.0",
                listOf(
                    PomLicense("EPL-2.0", "https://www.eclipse.org/legal/epl-2.0"),
                    PomLicense(
                        "GPL-2.0-with-classpath-exception",
                        "https://www.gnu.org/software/classpath/license.html",
                    ),
                ),
            ),
            "jakarta.ws.rs:jakarta.ws.rs-api:4.0.0",
        ),
        inheriting(
            eplOrGpl,
            ParentPom("org.glassfish.hk2:external:4.0.3", parent = hk2Parent),
            "org.glassfish.hk2.external:aopalliance-repackaged:4.0.3",
        ),
        inheriting(
            eplOrGpl,
            hk2Parent,
            "org.glassfish.hk2:hk2-api:4.0.3",
            "org.glassfish.hk2:hk2-locator:4.0.3",
            "org.glassfish.hk2:hk2-utils:4.0.3",
        ),
        inheriting(
            eplOrGpl,
            ParentPom("org.glassfish.hk2:hk2-extra-parent:3.0.0", hk2Licenses),
            "org.glassfish.hk2:osgi-resource-locator:3.0.0",
        ),
        inheriting(
            "EPL-2.0 OR LGPL-2.1-only",
            ParentPom(
                "ch.qos.logback:logback-parent:1.6.8",
                listOf(
                    PomLicense("EPL-2.0", "https://www.eclipse.org/legal/epl-v20.html"),
                    PomLicense("LGPL-2.1-only", "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html"),
                ),
            ),
            "ch.qos.logback:logback-classic:1.6.8",
            "ch.qos.logback:logback-core:1.6.8",
        ),
        declaring(
            "LGPL-2.1-or-later OR Apache-2.0",
            jnaLicenses,
            "net.java.dev.jna:jna:5.19.1",
            "net.java.dev.jna:jna-jpms:5.19.1",
            "net.java.dev.jna:jna-platform:5.19.1",
            "net.java.dev.jna:jna-platform-jpms:5.19.1",
        ),
        declaring(
            "LGPL-3.0-or-later OR Apache-2.0",
            javaJsonToolsLicenses,
            "com.github.java-json-tools:btf:1.3",
            "com.github.java-json-tools:jackson-coreutils:2.0",
            "com.github.java-json-tools:json-patch:1.13",
            "com.github.java-json-tools:msg-simple:1.2",
        ),
        declaring(
            "MPL-1.1 OR LGPL-2.1-only OR Apache-2.0",
            listOf(
                PomLicense("MPL 1.1", "https://www.mozilla.org/en-US/MPL/1.1/"),
                PomLicense("LGPL 2.1", "https://www.gnu.org/licenses/lgpl-2.1.html"),
                PomLicense("Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
            ),
            "org.javassist:javassist:3.33.0-GA",
        ),
        declaring(
            "WTFPL OR Apache-2.0",
            listOf(
                PomLicense("WTFPL", "http://www.wtfpl.net/"),
                PomLicense(
                    "The Apache Software License, Version 2.0",
                    "http://www.apache.org/licenses/LICENSE-2.0.txt",
                ),
            ),
            "org.reflections:reflections:0.10.2",
        ),
        declaring(
            "Apache-2.0 OR GPL-2.0-only OR LGPL-2.1-only OR LicenseRef-scancode-ldap-sdk-free-use",
            listOf(
                PomLicense("Apache License, Version 2", "http://www.apache.org/licenses/LICENSE-2.0"),
                PomLicense("GNU General Public License version 2 (GPLv2)", "http://www.gnu.org/licenses/gpl-2.0.html"),
                PomLicense(
                    "GNU Lesser General Public License version 2.1 (LGPLv2.1)",
                    "http://www.gnu.org/licenses/lgpl-2.1.html",
                ),
                PomLicense(
                    "UnboundID LDAP SDK Free Use License",
                    "https://docs.ldap.com/ldap-sdk/docs/LICENSE-UnboundID-LDAPSDK.txt",
                ),
            ),
            "com.unboundid:unboundid-ldapsdk:7.0.6",
        ),
        declaring(
            "Apache-2.0 AND MIT",
            listOf(
                PomLicense("Apache License, Version 2.0", "http://www.apache.org/licenses/LICENSE-2.0"),
                PomLicense("MIT License", "http://www.slf4j.org/license.html"),
            ),
            "net.logstash.logback:logstash-logback-encoder:9.0",
        ),
        inheriting(eplOrGpl, jerseyProject, "org.glassfish.jersey.core:jersey-client:4.0.3"),
        inheriting(
            eplOrGpl,
            ParentPom("org.glassfish.jersey.containers:project:4.0.3", parent = jerseyProject),
            "org.glassfish.jersey.containers:jersey-container-jdk-http:4.0.3",
        ),
        inheriting(
            eplOrGpl,
            ParentPom("org.glassfish.jersey.ext:project:4.0.3", parent = jerseyProject),
            "org.glassfish.jersey.ext:jersey-entity-filtering:4.0.3",
        ),
        inheriting(
            eplOrGpl,
            ParentPom("org.glassfish.jersey.inject:project:4.0.3", parent = jerseyProject),
            "org.glassfish.jersey.inject:jersey-hk2:4.0.3",
        ),
        inheriting(
            eplOrGpl,
            ParentPom("org.glassfish.jersey.media:project:4.0.3", parent = jerseyProject),
            "org.glassfish.jersey.media:jersey-media-multipart:4.0.3",
        ),
        declaring(
            "($eplOrGpl) AND Apache-2.0 AND CC0-1.0",
            jerseyModuleLicenses + PomLicense("Public Domain", "https://creativecommons.org/publicdomain/zero/1.0/"),
            "org.glassfish.jersey.core:jersey-common:4.0.3",
        ),
        declaring(
            "($eplOrGpl) AND Apache-2.0 AND BSD-3-Clause",
            jerseyModuleLicenses + PomLicense("Modified BSD", "https://asm.ow2.io/license.html"),
            "org.glassfish.jersey.core:jersey-server:4.0.3",
        ),
        declaring(
            "($eplOrGpl) AND Apache-2.0",
            jerseyModuleLicenses,
            "org.glassfish.jersey.media:jersey-media-json-jackson:4.0.3",
        ),
        inheriting(
            eplOrGpl,
            ParentPom(
                "org.jboss.resteasy:resteasy-extensions:2.0.2.Final",
                listOf(
                    PomLicense("Eclipse Public License 2.0", "https://www.eclipse.org/legal/epl-v20.html"),
                    PomLicense(
                        "GNU General Public License, Version 2 with the Classpath Exception",
                        "https://repository.jboss.org/licenses/gpl-2.0-ce.txt",
                    ),
                ),
            ),
            "org.jboss.resteasy:resteasy-tracing-api:2.0.2.Final",
        ),
        declaring(
            "Apache-2.0 OR GPL-2.0-only",
            listOf(
                PomLicense("Apache License 2.0", "http://www.apache.org/licenses/LICENSE-2.0.html"),
                PomLicense("GNU General Public License, version 2", "http://www.gnu.org/licenses/gpl-2.0.html"),
            ),
            "org.rocksdb:rocksdbjni:10.2.1",
        ),
    ).flatten()

    // earlier versions the consumers use, whose lists differ from the current ones; a project of their own, as Gradle
    // resolves one version of a module per project
    private val older = listOf(
        inheriting(
            "EPL-2.0 OR LGPL-2.1-only",
            ParentPom(
                "ch.qos.logback:logback-parent:1.5.32",
                listOf(
                    PomLicense("Eclipse Public License - v 2.0", "https://www.eclipse.org/legal/epl-v20.html"),
                    PomLicense(
                        "GNU Lesser General Public License",
                        "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html",
                    ),
                ),
            ),
            "ch.qos.logback:logback-classic:1.5.32",
            "ch.qos.logback:logback-core:1.5.32",
        ),
        declaring(
            "CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0",
            listOf(
                PomLicense("CDDL 1.1", "https://glassfish.java.net/public/CDDL+GPL_1_1.html"),
                PomLicense("GPL2 w/ CPE", "https://glassfish.java.net/public/CDDL+GPL_1_1.html"),
            ),
            "javax.xml.bind:jaxb-api:2.2.12",
        ),
        // the LGPL entry before it became "or later"
        declaring(
            "LGPL-2.1-only OR Apache-2.0",
            listOf(
                PomLicense("LGPL, version 2.1", "http://www.gnu.org/licenses/licenses.html"),
                PomLicense("Apache License v2.0", "http://www.apache.org/licenses/LICENSE-2.0.txt"),
            ),
            "net.java.dev.jna:jna:5.7.0",
            "net.java.dev.jna:jna-platform:5.7.0",
        ),
        declaring(
            "MPL-1.1 OR LGPL-2.1-only OR Apache-2.0",
            listOf(
                PomLicense("MPL 1.1", "http://www.mozilla.org/MPL/MPL-1.1.html"),
                PomLicense("LGPL 2.1", "http://www.gnu.org/licenses/lgpl-2.1.html"),
                PomLicense("Apache License 2.0", "http://www.apache.org/licenses/"),
            ),
            "org.javassist:javassist:3.28.0-GA",
        ),
    ).flatten()

    private val mapper = ObjectMapper()

    private fun gradleRunner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args)
        // generateSbom runs git, which must see neither the machine's git settings nor a repository around the build;
        // without a commit to take the SBOM timestamp from, a fixed one keeps the fallback warning out of the output
        .withEnvironment(GitTestRepository.isolatedEnv(projectDir, mapOf("SOURCE_DATE_EPOCH" to "1767323045")))

    /** Writes the [modules] and every POM of their parent chains into the build's `repo` directory. */
    private fun publish(modules: List<PublishedModule>) {
        val repoDir = projectDir.resolve("repo")
        for (parent in modules.flatMap { generateSequence(it.parent, ParentPom::parent) }.distinct()) {
            FixtureRepository.parent(repoDir, parent.coordinates, parent.licenses, parent.parent?.coordinates)
        }
        for (module in modules) {
            val (group, name, version) = module.coordinates.split(':')
            FixtureRepository.module(
                repoDir, name, version, group = group, licenses = module.licenses, parent = module.parent?.coordinates,
            )
        }
    }

    /** The subproject [name], which applies the plugin and depends on each of [modules], from `repo` alone. */
    private fun consumer(name: String, modules: List<PublishedModule>) {
        val dependencies = modules.joinToString("\n", "dependencies {\n", "\n}") {
            """    implementation("${it.coordinates}")"""
        }
        val buildScript = """
            plugins {
                java
                id("com.hivemq.tools.license")
            }
            hivemqLicense {
                thirdPartyLicenseDirectory.set(layout.buildDirectory.dir("tmp/third-party-licenses"))
            }
            repositories {
                maven { url = uri(rootDir.resolve("repo")) }
            }
            """.trimIndent()
        projectDir.resolve(name).apply { mkdirs() }.resolve("build.gradle.kts").writeText("$buildScript\n$dependencies")
    }

    /**
     * The concluded license of each of [modules] in the SBOM of the subproject [name]: its expression when it is the
     * component's only license entry, else every entry it has, so that a failure shows what the SBOM holds.
     */
    private fun concludedLicenses(name: String, modules: List<PublishedModule>): Map<String, String> {
        val sbom = mapper.readTree(projectDir.resolve("$name/build/sbom/$name.cdx.json"))
        val components = sbom["components"].associateBy { component ->
            listOf("group", "name", "version").joinToString(":") { component.path(it).asText() }
        }
        return modules.associate { module ->
            val licenses = components[module.coordinates]?.get("licenses")
            val concluded = licenses?.singleOrNull()?.takeIf { it.path("acknowledgement").asText() == "concluded" }
            module.coordinates to (concluded?.get("expression")?.asText() ?: licenses.toString())
        }
    }

    // --- Coverage ---

    @Test
    fun `the published modules cover every module and every expression of BUILT_IN_EXPRESSIONS`() {
        // distinct, as a module may record two lists for one expression
        val builtIns = LicenseResolver.BUILT_IN_EXPRESSIONS
            .flatMap { (moduleId, all) -> all.map { "$moduleId: ${it.expression}" } }
            .distinct()

        // a table entry without a published module would go untested by a CycloneDX update
        assertThat(current.map { it.moduleId })
            .containsExactlyInAnyOrderElementsOf(LicenseResolver.BUILT_IN_EXPRESSIONS.keys)
        assertThat((current + older).map { "${it.moduleId}: ${it.expression}" }.distinct())
            .containsExactlyInAnyOrderElementsOf(builtIns)
    }

    // --- Resolution ---

    @Test
    fun `the license list of every published module resolves to its built-in expression`() {
        publish(current + older)
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "built-in-expressions"
            include("current", "older")
            """.trimIndent()
        )
        consumer("current", current)
        consumer("older", older)

        // one run reports every list that CycloneDX now writes differently, in both projects
        val result = gradleRunner("checkApprovedLicenses", "generateSbom", "--continue").build()

        assertThat(result.task(":current:checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":older:checkApprovedLicenses")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        // a list that passes but through another entry of its module, such as the other JNA list, fails here
        val concluded = concludedLicenses("current", current) + concludedLicenses("older", older)
        assertThat(concluded)
            .containsExactlyInAnyOrderEntriesOf((current + older).associate { it.coordinates to it.expression })
    }
}
