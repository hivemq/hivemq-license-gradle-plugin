# HiveMQ License Gradle Plugin

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/com.hivemq.tools.license?color=brightgreen&style=for-the-badge)](https://plugins.gradle.org/plugin/com.hivemq.tools.license)
[![GitHub](https://img.shields.io/github/license/hivemq/hivemq-license-gradle-plugin?color=brightgreen&style=for-the-badge)](LICENSE)

This Gradle plugin generates and validates third-party license reports from CycloneDX BOMs and pnpm license output.

## Example

Contents of the `build.gradle(.kts)` file:
```kotlin
plugins {
    id("com.hivemq.tools.license") version "1.5.0"
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
| `overriddenLicenses`         | `MapProperty<String, String>` | `{}`                                | No       | Override license SPDX IDs for specific artifacts                       |
| `sbomName`                   | `Property<String>`            | `project.name`                      | No       | SBOM file name without `.cdx.json`; blank, `/` or `\` fails the build  |
| `sbomTimestamp`              | `Property<Instant>`           | see `generateSbom`                  | No       | `metadata.timestamp` of that SBOM                                      |
| `sbomCreatorName`            | `Property<String>`            | `HiveMQ GmbH`                       | No       | Name of the organisation that creates the SBOM and ships the component |
| `sbomCreator`                | `Property<String>`            | `security@hivemq.com`               | No       | Email address or http(s) URL of that organisation, or the build fails  |
| `sbomArtifactFileName`       | `Property<String>`            | `jar` archive file name             | No       | Top-level component's file name; the default needs the `java` plugin   |

In a project with a `hivemqProvided` configuration (the HiveMQ extension Gradle plugin creates it for the dependencies
the broker supplies at runtime), everything that configuration resolves is left out of the reports, the check and the
SBOM, the same rule the extension jar applies when it leaves those libraries out; the broker's own report covers them,
and its SBOM will once it has one. There is no extension property for it. The CycloneDX BOM,
`build/reports/cyclonedx-direct/bom.json`, still lists these modules, and `checkApprovedLicenses`,
`updateThirdPartyLicenses` and `generateSbom` skip them when they read it: CycloneDX cannot drop part of
`runtimeClasspath`, which contains them because `implementation` extends `hivemqProvided`.

### Advanced Example

```kotlin
hivemqLicense {
    projectName = "My Application"
    thirdPartyLicenseDirectory = layout.buildDirectory.dir("third-party-licenses")
    ignoredGroupPrefixes.set(setOf("com.hivemq", "com.mycompany"))
    allowedArtifacts.set(setOf("com.hivemq:hivemq-mqtt-client", "com.mycompany:public-lib"))
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
| `generateSbom`             | Writes a reproducible CycloneDX SBOM from the CycloneDX BOM  |

### checkApprovedLicenses

Reads the CycloneDX BOM and validates that every dependency has a license from the approved list.
The task fails if any dependency has an unapproved or unknown license. One failure names the BOM file that was read,
says when known whether this build produced it, restored it from the build cache or found it unchanged from an earlier
build, and lists every offending dependency, so a BOM with several problems is fixed in one round. When the CycloneDX
plugin recorded that a dependency's Maven metadata is incomplete (`cdx:maven:package:metadata-unresolved`, CycloneDX
Gradle plugin 3.5.0 or later), the entry says so and names the reason, to tell a POM that could not be read or completed
apart from a POM that declares no license.

### updateThirdPartyLicenses

Reads the CycloneDX BOM and generates two report files in the configured `thirdPartyLicenseDirectory`.
It validates licenses the same way as `checkApprovedLicenses` and fails with the same message when any dependency has
an unapproved or unknown license.
- `licenses` — plaintext report (or `<prefix>licenses` if `outputFileNamePrefix` is set)
- `licenses.html` — HTML report (or `<prefix>licenses.html` if `outputFileNamePrefix` is set)

Both reports contain a table with the following columns: Module, Version, License ID, and License URL.

The `outputFileNamePrefix` property can be set to prefix the output file names, which is useful when generating multiple reports (e.g. backend and frontend) into the same directory.

### generateSbom

Writes `build/sbom/<sbomName>.cdx.json`, a CycloneDX JSON SBOM derived from the `cyclonedxDirectBom` output.
Nothing ships it by itself: add it to a distribution or image where the product is assembled, for example
`from(tasks.named("generateSbom")) { into("sbom") }` in a `Zip` task. It validates licenses the same way as
`checkApprovedLicenses` and fails with the same message when any dependency has an unapproved or unknown license, so
a build that ships the SBOM needs no separate license check.

The CycloneDX Gradle plugin writes a file that differs on every run (current time, random serial number, the CI run
URL, a `vcs` reference taken from the `GIT_URL` variable or from the directory Gradle was started in), which changes
every archive that contains it and defeats the build cache. `generateSbom` writes the same bytes for the same
dependencies and timestamp:

- `metadata.timestamp` is `sbomTimestamp`, truncated to whole seconds. Without a value the plugin uses
  `SOURCE_DATE_EPOCH`, then the time of the repository's HEAD commit (`git log -1` in the project directory), then the
  current time with a warning. A missing `git` binary also falls back to the current time; a blank `SOURCE_DATE_EPOCH`
  counts as unset, and one that is not a number of seconds within the range of Java's `Instant` fails the build.
- `serialNumber` is a UUID computed from the content.
- `dependencies` and their `dependsOn` lists are sorted.
- The `build-system` external reference is dropped, and the `vcs` reference is the project's own `origin` remote, as
  `https://github.com/<org>/<repo>` for GitHub, with credentials removed; without a remote, or with one that is not
  an http(s) or ssh URL, it is left out. A URL set on the task property `generateSbom.vcsUrl` replaces the remote's,
  whether there is a remote or not; it is a task input, so it must not contain credentials. Only a GitHub remote gives
  the same URL whatever protocol the repository was cloned with; for other hosts an ssh and an https clone write
  different SBOMs. `vcs` and `build-system` references configured through `cyclonedxDirectBom.externalReferences` are
  dropped as well, and a configured `source-distribution` reference is replaced too: the top-level component's
  `source-distribution` reference carries the same URL as `vcs`, or is left out with it.

In a Kotlin DSL build script, setting `sbomTimestamp` needs the import, because `java.time.Instant` written out in
full does not resolve once the `java` plugin is applied:

```kotlin
import java.time.Instant

hivemqLicense {
    sbomTimestamp.set(Instant.parse("2026-01-02T03:04:05Z"))
}
```

The file also carries what [BSI TR-03183-2](https://www.bsi.bund.de/SharedDocs/Downloads/EN/BSI/Publications/TechGuidelines/TR03183/BSI-TR-03183-2_v2_1_0.pdf?__blob=publicationFile)
requires beyond CycloneDX's defaults:

- The creator of the SBOM and of the component, as `metadata.manufacturer` and `metadata.component.manufacturer`,
  from `sbomCreatorName` and `sbomCreator` (an email address becomes a contact, a URL stays a URL); a manufacturer
  configured through `cyclonedxDirectBom.organizationalEntity` is replaced.
- Per component: the file name and whether it is executable, an archive and structured
  (`bsi:component:filename`, `bsi:component:executable`, `bsi:component:archive`, `bsi:component:structured`),
  from the resolved artifact; a JAR counts as an executable, structured archive, a ZIP as a non-executable one, and a
  file of any other type gets only its name. Components without a file, such as Maven BOMs, get none. Projects of
  this build and of included builds get none either: only modules from a repository are resolved to files, so that no
  jar has to be built for the SBOM. The top-level component's file name comes from `sbomArtifactFileName`, by default
  the archive file name of the `jar` task; that default exists only when the `java` plugin is applied, and without a
  value the top-level component gets no file description.
- The component creator as a URL, and the repository as a `source-distribution` reference. The creator is the URL of
  the component's `website` reference, or else of its `vcs` reference; CycloneDX writes those from the `<organization>`
  and `<scm>` elements of the component's own POM, so a library whose POM inherits them from a parent POM gets no
  creator. A project of this build or of an included build has no POM and gets the creator of the SBOM.
- A `compositions` entry declaring the dependency graph `complete`, or `incomplete` when the configurations the BOM is
  built from resolve a file dependency, such as a jar checked into the repository, or fail to resolve a module:
  CycloneDX leaves both out of the BOM, and `generateSbom` warns with the reasons. A file dependency counts no matter
  which project declares it: this one, or one it depends on, in this build or in an included build. `complete` refers to
  the dependency graph of the shipped artifact, without the modules a `hivemqProvided` configuration resolves.
- Licenses: the entries CycloneDX wrote from the POM are tagged `declared`; the license this plugin resolves for the
  reports (overrides included) is added as `concluded`, one entry per license when several apply. When a component's
  declared license is a single SPDX expression and a concluded license is added, the expression is kept whole as the
  `name` of the declared entry, because a CycloneDX license list holds either single licenses or one expression. A
  component the reports leave out (a module matched by `ignoredGroupPrefixes` and not listed in `allowedArtifacts`),
  or in a version the reports do not list, keeps only its declared entries.

Known gaps: first-party components (the top-level component and the projects of this build and of included builds) carry
no license, so a project dependency fails `generateSbom` like it fails `checkApprovedLicenses` unless
`ignoredGroupPrefixes` covers its group or an override names it; concluded licenses are written as one license object
per license instead of the single SPDX expression the guideline shows, because a CycloneDX 1.6 license list cannot mix
an expression with the declared license objects; files other than `.jar` and `.zip` get a file name but no executable,
archive or structured property; and a component whose POM inherits `<organization>` and `<scm>` from a parent POM gets
no creator, as described above.

Deliberate deviations: the serial number is derived from the content, while CycloneDX recommends a new serial number
for every generated BOM even when the content is unchanged; and the timestamp is `sbomTimestamp`, by default
`SOURCE_DATE_EPOCH` or the commit time, instead of the time the SBOM data was compiled, which is what BSI TR-03183-2
names. Both keep the file reproducible, so that archives containing it stay cacheable. BSI TR-03183-2 section 5.1 also
requires identifying, on each path, the first component outside the scope of delivery, so that the SBOM can be chained
with the SBOM of the environment the software runs in; the SBOM of a HiveMQ extension stops at the delivered artifact
instead and does not name the modules the broker provides.

Every version of the modules a `hivemqProvided` configuration resolves is left out, with its dependency entries (see
Configuration). A reference to one of them is replaced by references to the components that stay and that it leads to,
directly or through other left-out modules, so the graph still reaches every component that stays, such as a module that
only another version of a provided module brings in.

The manufacturer fields, the license acknowledgements and the `source-distribution` reference exist from CycloneDX
specification 1.6 on, which is what `cyclonedxDirectBom` writes by default; `generateSbom` fails on a BOM that
declares an older `specVersion`, or a major version other than 1.

The guideline also asks for a SHA-512 hash of each component's file. CycloneDX writes the hashes of every resolved
artifact, SHA-512 among them, to `components[].hashes`, and they stay there; projects of this build and of included
builds, the top-level component among them, have no resolved artifact and so no hashes. The guideline's example
places the hashes on a `distribution` reference with a download URL, which Gradle does not know.

The file is also available to other projects, or included builds, as the `sbomElements` variant (attribute
`Category=sbom`):

```kotlin
val sboms by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named("sbom"))
    }
}
dependencies {
    sboms(project(":some-component"))
}
```

The variant exists only in projects with the `java` plugin. In other projects it would be their only variant with
attributes, so Gradle would match their consumers against it instead of handing out the `default` configuration.

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

A component may declare its licenses as single entries (`{ "license": { "id": "Apache-2.0" } }`) or as an SPDX
expression (`{ "expression": "(CDDL-1.0 OR GPL-2.0-with-classpath-exception)" }`). Other BOM producers write
expressions today; the CycloneDX Gradle Plugin, up to and including version 3.5.1, drops a POM license that maps to
one, so a component with only such a license has no license entry at all
([fixed upstream](https://github.com/CycloneDX/cyclonedx-gradle-plugin/commit/27bfea69e) after 3.5.1).

Expressions follow these rules:

- An `OR` is satisfied by any approved alternative, and the one with the fewest obligations is reported: the
  alternative whose lowest-priority license ranks highest in the approved list, and of those the one with fewer
  licenses.
- An `AND` needs every operand approved, and the report lists all of them in priority order, as `MIT AND ISC` with
  one URL per license that has one.
- License ids match regardless of case, in expressions as in single license entries and overrides. An id that is
  not on the approved list, a license reference (`LicenseRef-`), an id with a `+` suffix and a license `WITH` an
  exception are not approved, so a dependency under `Apache-2.0 WITH LLVM-exception` needs an entry in
  `overriddenLicenses`.
- A malformed expression, or one with more alternatives or deeper nesting than the plugin evaluates, satisfies
  nothing, and the message says so when no other entry of the component is approved.

### pnpm License JSON

The JSON output from `pnpm licenses list --json --prod`. The plugin auto-detects this format when the input file does not contain a `components` field. This is useful for frontend projects using pnpm as their package manager.

For npm scoped packages (e.g. `@scope/package`), the scope is part of the package name with an empty group. The `ignoredGroupPrefixes` setting also matches against the package name when the group is empty, so `@mycompany/` will correctly ignore all packages under that scope.

Compound SPDX expressions like `MIT AND ISC` or `MIT OR Apache-2.0` are supported, with the same rules as for the
CycloneDX BOM.

## Approved Licenses

The following licenses are recognized and approved. When a dependency may be used under one of several licenses, the plugin chooses the one with the highest priority (top of the list). When several licenses apply at once (an SPDX `AND`), every one of them must be approved and all are reported.

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

These can be overridden using the `overriddenLicenses` configuration. An override must name an SPDX ID from the
approved table, matched regardless of case, and every override is checked, whether or not its module turns up in the
BOM.

## How It Works

1. The plugin applies the [CycloneDX Gradle Plugin](https://github.com/CycloneDX/cyclonedx-gradle-plugin) and configures it to generate a `bom.json` from the `runtimeClasspath`.
2. The three tasks, `checkApprovedLicenses`, `updateThirdPartyLicenses` and `generateSbom`, parse the generated BOM and resolve licenses using SPDX IDs, license name patterns, and URL-based matching; the first two also read pnpm JSON.
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
