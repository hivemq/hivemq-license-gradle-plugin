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
    // CI passes the key through environment variables, which arrive empty when the secret is absent
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword.orEmpty())
    }
    // A release must be signed, so publishing to the Plugin Portal fails without a key. Every other publication,
    // the test repository the tests depend on included, is left unsigned when there is no key.
    isRequired = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "publishPlugins" }
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

// Hands the repository to the test JVM. The published files are the input, not the directory, because every publish
// rewrites maven-metadata.xml and a signing run rewrites the signatures, and the absolute path stays out of the cache
// key.
abstract class TestPluginRepository : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val files: ConfigurableFileCollection

    @get:Internal
    abstract val directory: DirectoryProperty

    override fun asArguments() = listOf("-Dtest.plugin.repo=${directory.get().asFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    dependsOn("publishAllPublicationsToTestRepository")
    jvmArgumentProviders.add(
        objects.newInstance<TestPluginRepository>().apply {
            directory.set(testPluginRepository)
            files.from(fileTree(testPluginRepository) { exclude("**/maven-metadata.xml*", "**/*.asc*") })
        }
    )
    systemProperty("test.plugin.version", version)
    // Pass the catalog-managed versions of the plugins applied inside the TestKit integration builds into the test
    // JVM, so Renovate updates to those plugins are exercised by PluginCoexistenceIntegrationTest.
    systemProperty("test.plugin.shadow.version", libs.versions.test.plugin.shadow.get())
    systemProperty("test.plugin.oci.version", libs.versions.test.plugin.oci.get())
}

spotless {
    kotlin {
        licenseHeaderFile(rootDir.resolve("HEADER"), "(package |@file:)")
    }
}
