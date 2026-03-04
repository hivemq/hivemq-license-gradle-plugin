# HiveMQ License Gradle Plugin

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/com.hivemq.tools.license?color=brightgreen&style=for-the-badge)](https://plugins.gradle.org/plugin/com.hivemq.tools.license)
[![GitHub](https://img.shields.io/github/license/hivemq/hivemq-license-gradle-plugin?color=brightgreen&style=for-the-badge)](LICENSE)

This Gradle plugin generates and validates third-party license reports from CycloneDX BOMs.

## Example

Contents of the `build.gradle(.kts)` file:
```kotlin
plugins {
    id("com.hivemq.tools.license") version "1.0.0"
}

hivemqLicense {
    projectName = "My Application"
    thirdPartyLicenseDirectory = layout.buildDirectory.dir("third-party-licenses")
}
```

## Configuration

The `hivemqLicense` extension provides the following properties:

| Property                    | Type                    | Default                             | Required | Description                                                            |
|-----------------------------|-------------------------|-------------------------------------|----------|------------------------------------------------------------------------|
| `projectName`               | `Property<String>`      | `project.name`                      | No       | Display name used in license report headers                            |
| `thirdPartyLicenseDirectory`| `DirectoryProperty`     | -                                   | Yes      | Directory where license report files are generated                     |
| `ignoredGroupPrefixes`      | `SetProperty<String>`   | `["com.hivemq"]`                    | No       | Group ID prefixes to exclude from reports (internal dependencies)      |
| `allowedArtifacts`          | `SetProperty<String>`   | `["com.hivemq:hivemq-mqtt-client"]` | No       | Specific artifacts to include despite matching an ignored group prefix |
| `overriddenLicenses`        | `MapProperty<String, String>` | `{}`                          | No       | Override license SPDX IDs for specific artifacts                       |

### Advanced Example

```kotlin
hivemqLicense {
    projectName = "My Application"
    thirdPartyLicenseDirectory = layout.buildDirectory.dir("third-party-licenses")
    ignoredGroupPrefixes.set(setOf("com.hivemq", "com.mycompany"))
    allowedArtifacts.set(setOf("com.hivemq:hivemq-mqtt-client", "com.mycompany:public-lib"))
    overriddenLicenses.set(mapOf("org.example:some-lib" to "Apache-2.0"))
}
```

## Tasks

| Task                       | Description                                                |
|----------------------------|------------------------------------------------------------|
| `checkApprovedLicenses`    | Validates that all dependencies have approved licenses     |
| `updateThirdPartyLicenses` | Generates third-party license reports from the CycloneDX BOM |

### checkApprovedLicenses

Reads the CycloneDX BOM and validates that every dependency has a license from the approved list.
The task fails if any dependency has an unapproved or unknown license.

### updateThirdPartyLicenses

Reads the CycloneDX BOM and generates two report files in the configured `thirdPartyLicenseDirectory`:
- `licenses` — plaintext report
- `licenses.html` — HTML report

Both reports contain a table with the following columns: Module, Version, License ID, and License URL.

## How It Works

1. The plugin applies the [CycloneDX Gradle Plugin](https://github.com/CycloneDX/cyclonedx-gradle-plugin) and configures it to generate a `bom.json` from the `runtimeClasspath`.
2. Both tasks parse the generated BOM and resolve licenses using SPDX IDs, license name patterns, and URL-based matching.
3. Dependencies matching the configured `ignoredGroupPrefixes` are excluded (unless listed in `allowedArtifacts`).

## Requirements

- Gradle 8.3 or higher is required
- JDK 11 or higher is required

## Build

Execute the `updateThirdPartyLicenses` task to generate license reports:
```shell
./gradlew updateThirdPartyLicenses
```

Execute the `checkApprovedLicenses` task to validate licenses:
```shell
./gradlew checkApprovedLicenses
```
