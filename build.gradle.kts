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
            vcsUrl = "https://github.com/hivemq/hivemq-license-gradle-plugin"
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

spotless {
    kotlin {
        licenseHeaderFile(rootDir.resolve("HEADER"), "(package |@file:)")
    }
}
