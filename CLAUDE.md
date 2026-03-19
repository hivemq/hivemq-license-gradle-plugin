# CLAUDE.md

## Project Overview

Gradle plugin that generates and validates third-party license reports from CycloneDX BOMs and pnpm license output.

## Tech Stack

- Kotlin, Gradle plugin development
- Jackson for JSON parsing
- JUnit 5 + AssertJ for testing

## Key Files

- `src/main/kotlin/com/hivemq/tools/license/gradle/License.kt` — `KnownLicense` enum (alphabetically ordered) and `UnknownLicense` data class
- `src/main/kotlin/com/hivemq/tools/license/gradle/LicenseResolver.kt` — `LICENSE_ORDER` (priority-ordered), `BUILT_IN_LICENSES`, `SPDX_ID_MAP`, license resolution logic
- `src/main/kotlin/com/hivemq/tools/license/gradle/HivemqLicenseExtension.kt` — Plugin extension configuration
- `src/main/kotlin/com/hivemq/tools/license/gradle/HivemqLicensePlugin.kt` — Plugin entry point

## Conventions

- `KnownLicense` enum entries are sorted alphabetically
- `LICENSE_ORDER` defines priority (not alphabetical) — higher priority = earlier in list
- Tests are organized by section with comment headers (e.g., `// --- Section ---`) and sorted alphabetically within sections
- Test classes: `LicenseTest`, `LicenseResolverMetadataTest`, `LicenseResolverConvertLicenseTest`, `LicenseResolverChooseLicenseTest`

## Adding a New License

1. Add enum entry to `KnownLicense` in `License.kt` (alphabetical position)
2. Add to `LICENSE_ORDER` in `LicenseResolver.kt` (desired priority position)
3. Add name-based matching rule in `convertLicense()` if needed
4. Update `LicenseTest` enum count
5. Update `LicenseResolverMetadataTest` — SPDX_ID_MAP assertion, LICENSE_ORDER position if last
6. Add SPDX ID and name-based matching tests in `LicenseResolverConvertLicenseTest`
7. Update README approved licenses table

## Build & Test

```shell
./gradlew test        # Run all tests
./gradlew build       # Full build
```
