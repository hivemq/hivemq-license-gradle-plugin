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
- `src/main/kotlin/com/hivemq/tools/license/gradle/LicenseResolvingTask.kt`, shared inputs of `checkApprovedLicenses`, `updateThirdPartyLicenses` and `generateSbom`, and the license resolution call that fails on any license problem
- `src/main/kotlin/com/hivemq/tools/license/gradle/BomTaskOutcomeService.kt`, build service recording how the BOM task finished, so a failure can say where the BOM came from
- `src/main/kotlin/com/hivemq/tools/license/gradle/sbom/SbomNormalizer.kt`, rewrites the CycloneDX JSON so identical inputs give identical bytes (timestamp, serial number, ordering, CI references)
- `src/main/kotlin/com/hivemq/tools/license/gradle/sbom/GenerateSbomTask.kt`, the task around it, a `LicenseResolvingTask`, so a license problem fails it like `checkApprovedLicenses`; the plugin registers it as `generateSbom` and exposes the file as the `sbomElements` variant
- `src/main/kotlin/com/hivemq/tools/license/gradle/sbom/SbomWiring.kt`, what the plugin's `apply` does for the SBOM: the `sbom*` conventions, the `generateSbom` registration with its SBOM inputs (the plugin sets the BOM and the license inputs, as for the other license tasks), and the `sbomElements` variant when the `java` plugin is applied
- `src/main/kotlin/com/hivemq/tools/license/gradle/sbom/BomFilter.kt`, removes the components a `hivemqProvided` configuration resolves, and every reference to them, from the BOM tree before the enricher sees it
- `src/main/kotlin/com/hivemq/tools/license/gradle/sbom/SbomTimestampSource.kt`, the value source behind the default `sbomTimestamp` (`SOURCE_DATE_EPOCH`, then the HEAD commit time, then now with a warning)
- `src/main/kotlin/com/hivemq/tools/license/gradle/sbom/SbomVcsUrlSource.kt`, the value source behind the SBOM's `vcs` reference (the project's own `origin` remote)
- `src/main/kotlin/com/hivemq/tools/license/gradle/sbom/GitCommand.kt`, runs git for both value sources with untranslated messages, and gives null when git cannot be started
- `src/main/kotlin/com/hivemq/tools/license/gradle/sbom/SbomEnricher.kt`, adds the BSI TR-03183-2 fields and the concluded licenses to the BOM before `SbomNormalizer` rewrites it
- `src/main/kotlin/com/hivemq/tools/license/gradle/sbom/SbomDetails.kt`, what the build tells the enricher: the creator, the file names of the resolved components, the concluded licenses and why the dependency graph is incomplete
- `src/main/kotlin/com/hivemq/tools/license/gradle/sbom/BomJson.kt`, reads the BOM so that the enricher, the normalizer and the task reject a bad file the same way

## Conventions

- `KnownLicense` enum entries are sorted alphabetically
- `LICENSE_ORDER` defines priority (not alphabetical) — higher priority = earlier in list
- Tests are organized by section with comment headers (e.g., `// --- Section ---`) and sorted alphabetically within sections
- Test classes touched when adding a license: `LicenseTest`, `LicenseResolverMetadataTest`, `LicenseResolverConvertLicenseTest`, `LicenseResolverChooseLicenseTest`; the resolver, the build service and the plugin wiring have their own (`LicenseResolverResolveLicensesTest`, `BomTaskOutcomeServiceTest`, `HivemqLicensePluginIntegrationTest`); the SBOM has `SbomNormalizerTest`, `SbomEnricherTest`, `GenerateSbomTaskTest`, `GenerateSbomIntegrationTest`, `SbomTimestampSourceTest` and `SbomVcsUrlSourceTest`

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
