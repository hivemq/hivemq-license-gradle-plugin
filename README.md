# HiveMQ License Gradle Plugin

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/com.hivemq.tools.license?color=brightgreen&style=for-the-badge)](https://plugins.gradle.org/plugin/com.hivemq.tools.license)
[![GitHub](https://img.shields.io/github/license/hivemq/hivemq-license-gradle-plugin?color=brightgreen&style=for-the-badge)](LICENSE)

This Gradle plugin generates and validates third-party license reports from CycloneDX BOMs and pnpm license output.

## Example

Contents of the `build.gradle(.kts)` file:
```kotlin
plugins {
    id("com.hivemq.tools.license") version "1.3.3"
}

hivemqLicense {
    projectName = "My Application"
    thirdPartyLicenseDirectory = layout.buildDirectory.dir("third-party-licenses")
}
```

## Configuration

The `hivemqLicense` extension provides the following properties:

| Property                     | Type                          | Default                             | Required | Description                                                            |
|------------------------------|-------------------------------|-------------------------------------|----------|------------------------------------------------------------------------|
| `projectName`                | `Property<String>`            | `project.name`                      | No       | Display name used in license report headers                            |
| `thirdPartyLicenseDirectory` | `DirectoryProperty`           | -                                   | Yes      | Directory where license report files are generated                     |
| `ignoredGroupPrefixes`       | `SetProperty<String>`         | `["com.hivemq"]`                    | No       | Group ID prefixes to exclude from reports (internal dependencies)      |
| `allowedArtifacts`           | `SetProperty<String>`         | `["com.hivemq:hivemq-mqtt-client"]` | No       | Specific artifacts to include despite matching an ignored group prefix |
| `additionalConfigurations`   | `SetProperty<String>`         | `[]`                                | No       | Extra Gradle configurations to include in the CycloneDX BOM            |
| `excludedDependencies`       | `SetProperty<String>`         | `[]`                                | No       | Dependencies (`group:artifact`) to exclude from CycloneDX scanning     |
| `overriddenLicenses`         | `MapProperty<String, String>` | `{}`                                | No       | Override license SPDX IDs for specific artifacts                       |

### Advanced Example

```kotlin
hivemqLicense {
    projectName = "My Application"
    thirdPartyLicenseDirectory = layout.buildDirectory.dir("third-party-licenses")
    ignoredGroupPrefixes.set(setOf("com.hivemq", "com.mycompany"))
    allowedArtifacts.set(setOf("com.hivemq:hivemq-mqtt-client", "com.mycompany:public-lib"))
    excludedDependencies.set(setOf("org.jline:jline", "org.jline:jline-picocli"))
    overriddenLicenses.set(mapOf(
        "org.example:some-lib" to "Apache-2.0",
        "org.jline:jline" to "BSD-3-Clause",
        "org.jline:jline-picocli" to "BSD-3-Clause",
    ))
}
```

## Tasks

| Task                       | Description                                                  |
|----------------------------|--------------------------------------------------------------|
| `checkApprovedLicenses`    | Validates that all dependencies have approved licenses       |
| `updateThirdPartyLicenses` | Generates third-party license reports from the CycloneDX BOM |

### checkApprovedLicenses

Reads the CycloneDX BOM and validates that every dependency has a license from the approved list.
The task fails if any dependency has an unapproved or unknown license.

### updateThirdPartyLicenses

Reads the CycloneDX BOM and generates two report files in the configured `thirdPartyLicenseDirectory`:
- `licenses` — plaintext report (or `<prefix>licenses` if `outputFileNamePrefix` is set)
- `licenses.html` — HTML report (or `<prefix>licenses.html` if `outputFileNamePrefix` is set)

Both reports contain a table with the following columns: Module, Version, License ID, and License URL.

The `outputFileNamePrefix` property can be set to prefix the output file names, which is useful when generating multiple reports (e.g. backend and frontend) into the same directory.

### Custom License Tasks

You can register additional `UpdateThirdPartyLicensesTask` instances for non-Gradle dependency sources like pnpm:

```kotlin
tasks.register("updateThirdPartyLicensesFrontend", com.hivemq.tools.license.gradle.UpdateThirdPartyLicensesTask::class.java) {
    group = "hivemq license"
    description = "Generates frontend third-party license reports."
    projectName.set("My Frontend")
    outputFileNamePrefix.set("frontend-")
    dependencyLicense.set(file("path/to/licenses-frontend.json"))
    ignoredGroupPrefixes.set(setOf("@mycompany/"))
    outputDirectory.set(layout.projectDirectory.dir("src/distribution/third-party-licenses"))
}
```

## Input Formats

The plugin supports two input formats:

### CycloneDX BOM (default)

The standard CycloneDX `bom.json` format generated by the CycloneDX Gradle Plugin. This is used automatically for Gradle/Maven dependencies.

### pnpm License JSON

The JSON output from `pnpm licenses list --json --prod`. The plugin auto-detects this format when the input file does not contain a `components` field. This is useful for frontend projects using pnpm as their package manager.

For npm scoped packages (e.g. `@scope/package`), the scope is part of the package name with an empty group. The `ignoredGroupPrefixes` setting also matches against the package name when the group is empty, so `@mycompany/` will correctly ignore all packages under that scope.

Compound SPDX expressions like `MIT AND ISC` or `MIT OR Apache-2.0` are supported.

## Approved Licenses

The following licenses are recognized and approved. When a dependency has multiple licenses, the plugin chooses the one with the highest priority (top of the list).

| Priority | SPDX ID           | Full Name                                                  |
|----------|-------------------|------------------------------------------------------------|
| 1        | Apache-2.0        | Apache License 2.0                                         |
| 2        | MIT               | MIT License                                                |
| 3        | MIT-0             | MIT No Attribution                                         |
| 4        | 0BSD              | BSD Zero Clause License                                    |
| 5        | Unlicense         | The Unlicense                                              |
| 6        | MIT               | Bouncy Castle Licence                                      |
| 7        | BlueOak-1.0.0     | Blue Oak Model License 1.0.0                               |
| 8        | ISC               | ISC License                                                |
| 9        | BSD-3-Clause      | BSD 3-Clause "New" or "Revised" License                    |
| 10       | BSD-2-Clause      | BSD 2-Clause "Simplified" License                          |
| 11       | BSD-3-Clause      | Go License                                                 |
| 12       | CC0-1.0           | Creative Commons Zero v1.0 Universal                       |
| 13       | CC-BY-4.0         | Creative Commons Attribution 4.0 International             |
| 14       | OFL-1.1           | SIL Open Font License 1.1                                  |
| 15       | Public Domain     | Public Domain                                              |
| 16       | W3C               | W3C Software Notice and License (2002-12-31)               |
| 17       | W3C-19980720      | W3C Software Notice and License (1998-07-20)               |
| 18       | BSD-3-Clause      | Eclipse Distribution License - v 1.0                       |
| 19       | EPL-2.0           | Eclipse Public License 2.0                                 |
| 20       | EPL-1.0           | Eclipse Public License 1.0                                 |
| 21       | CDDL-1.1          | Common Development and Distribution License 1.1            |
| 22       | CDDL-1.0          | Common Development and Distribution License 1.0            |
| 23       | Unicode-3.0       | Unicode License v3                                         |
| 24       | Unicode-DFS-2016  | Unicode License Agreement - Data Files and Software (2016) |
| 25       | UPL-1.0           | Universal Permissive License v1.0                          |
| 26       | LGPL-2.1-or-later | GNU Lesser General Public License v2.1 or later            |

## Built-in License Mappings

Some well-known artifacts don't report license metadata in CycloneDX. The plugin includes built-in mappings for these:

| Artifact                                     | License      |
|----------------------------------------------|--------------|
| `javax.activation:javax.activation-api`      | CDDL-1.1     |
| `javax.annotation:javax.annotation-api`      | CDDL-1.1     |
| `javax.servlet:javax.servlet-api`            | CDDL-1.1     |
| `javax.websocket:javax.websocket-api`        | CDDL-1.1     |
| `javax.websocket:javax.websocket-client-api` | CDDL-1.1     |
| `javax.ws.rs:jsr311-api`                     | CDDL-1.1     |
| `org.antlr:ST4`                              | BSD-3-Clause |
| `org.antlr:antlr-runtime`                    | BSD-3-Clause |
| `org.ow2.asm:asm`                            | BSD-3-Clause |
| `org.picocontainer:picocontainer`            | BSD-3-Clause |
| `org.w3c.css:sac`                            | W3C-19980720 |

These can be overridden using the `overriddenLicenses` configuration.

## Excluded Dependencies

Some dependencies are built with Maven 4, which produces POM files that the CycloneDX Gradle Plugin cannot parse ([CycloneDX #793](https://github.com/CycloneDX/cyclonedx-gradle-plugin/issues/793)). To work around this, you can exclude these dependencies from CycloneDX scanning using `excludedDependencies`.

Excluded dependencies are removed from the configuration that CycloneDX scans (including their transitives). Their licenses are still included in the report, resolved from `overriddenLicenses` or `BUILT_IN_LICENSES`. The task fails if an excluded dependency has no license defined in either source.

```kotlin
hivemqLicense {
    projectName = "My Application"
    thirdPartyLicenseDirectory = layout.buildDirectory.dir("third-party-licenses")
    excludedDependencies.set(setOf("org.jline:jline", "org.jline:jline-picocli"))
    overriddenLicenses.set(mapOf(
        "org.jline:jline" to "BSD-3-Clause",
        "org.jline:jline-picocli" to "BSD-3-Clause",
    ))
}
```

## How It Works

1. The plugin applies the [CycloneDX Gradle Plugin](https://github.com/CycloneDX/cyclonedx-gradle-plugin) and configures it to generate a `bom.json` from the `runtimeClasspath` (or a filtered copy if `excludedDependencies` is set).
2. Both tasks parse the generated BOM (or pnpm JSON) and resolve licenses using SPDX IDs, license name patterns, and URL-based matching.
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
