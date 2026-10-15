plugins {
    `kotlin-dsl`
    signing
    alias(libs.plugins.pluginPublish)
    alias(libs.plugins.defaults)
    alias(libs.plugins.metadata)
    alias(libs.plugins.spotless)
}

group = "com.hivemq.tools"

metadata {
    readableName = "HiveMQ License Gradle Plugin"
    description = "A Gradle plugin to validate third-party licenses and generate license reports from CycloneDX BOMs"
    organization {
        name = "HiveMQ"
        url = "https://www.hivemq.com/"
    }
    license {
        apache2()
    }
    github {
        org = "hivemq"
        issues()
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.compileJava {
    javaCompiler = javaToolchains.compilerFor {
        languageVersion = JavaLanguageVersion.of(11)
    }
}

tasks.compileKotlin {
    kotlinJavaToolchain.toolchain.use(javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(11)
    })
}

repositories {
    gradlePluginPortal()
}

dependencies {
    implementation(libs.cyclonedx)
    implementation(libs.jackson.databind)
    testImplementation(libs.assertj)
}

gradlePlugin {
    plugins {
        create("license") {
            id = "$group.$name"
            implementationClass = "$group.$name.gradle.HivemqLicensePlugin"
            tags = listOf("hivemq", "license")
        }
    }
}

signing {
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)
    isRequired = signingKey != null && signingPassword != null
}

@Suppress("UnstableApiUsage")
testing {
    suites {
        "test"(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit.jupiter)
        }
    }
}

// A Maven repository with this plugin, for TestKit builds that need to resolve it from a plugins block like a real
// consumer. TestKit's injected plugin classpath loads the plugin once for the whole build, which hides problems that
// only show when Gradle gives sibling projects with different plugins blocks their own plugin classloaders.
val testPluginRepository = layout.buildDirectory.dir("test-repo")
publishing {
    repositories {
        maven {
            name = "test"
            url = uri(testPluginRepository)
        }
    }
}

// Pass the catalog-managed versions of the plugins applied inside the TestKit integration builds into the test JVM, so
// Renovate updates to those plugins are exercised by PluginCoexistenceIntegrationTest.
tasks.withType<Test>().configureEach {
    dependsOn("publishAllPublicationsToTestRepository")
    inputs.dir(testPluginRepository).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("test.plugin.repo", testPluginRepository.get().asFile.absolutePath)
    systemProperty("test.plugin.version", version)
    systemProperty("test.plugin.shadow.version", libs.versions.test.plugin.shadow.get())
    systemProperty("test.plugin.oci.version", libs.versions.test.plugin.oci.get())
}

spotless {
    kotlin {
        licenseHeaderFile(rootDir.resolve("HEADER"), "(package |@file:)")
    }
}
