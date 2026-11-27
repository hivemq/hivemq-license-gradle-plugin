# HiveMQ License Gradle Plugin

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/com.hivemq.tools.license?color=brightgreen&style=for-the-badge)](https://plugins.gradle.org/plugin/com.hivemq.tools.license)
[![GitHub](https://img.shields.io/github/license/hivemq/hivemq-license-gradle-plugin?color=brightgreen&style=for-the-badge)](LICENSE)

This Gradle plugin generates and validates third-party license reports from CycloneDX BOMs and pnpm license output.

## Example

Contents of the `build.gradle(.kts)` file:
```kotlin
plugins {
    id("com.hivemq.tools.license") version "2.0.0"
}

hivemqLicense {
    projectName = "My Application"
    thirdPartyLicenseDirectory = layout.buildDirectory.dir("third-party-licenses")
}
```

## Migrating to 2.0.0

The plugin no longer applies the CycloneDX Gradle plugin. It registers its own BOM task, `generateDependencyBom`,
which writes `build/license/bom.json` and no XML file. The `cyclonedxDirectBom` and `cyclonedxBom` tasks and the
`cyclonedxDirectBom` configuration no longer exist:

- A build that copied `bom.json` or `bom.xml` from `cyclonedxDirectBom` into a distribution or an image ships the
  output of `generateSbom` instead, which fails on a license problem like `checkApprovedLicenses` and carries the
  BSI TR-03183-2 fields: `into("sbom") { from(tasks.generateSbom) }`. The task writes `<sbomName>.cdx.json`, the
  project name by default, not `bom.json`, so a copy filter such as `include("bom.json", "bom.xml")` must go, since it
  would copy nothing without failing.
- A build that set `includeConfigs` of `cyclonedxDirectBom` lists those configurations in
  `hivemqLicense.additionalConfigurations` instead. It takes exact configuration names, not patterns, and
  `runtimeClasspath` is always read.
- Other settings of `cyclonedxDirectBom`, such as `projectType`, go to `generateDependencyBom`. Its `includeConfigs`,
  `skipConfigs` and `includeMetadataResolution` belong to the plugin, and a build script cannot change them.
- `ignoredGroupPrefixes` no longer hides a whole group by itself: a module under a prefix is listed when
  `allowedArtifacts` matches it and left out otherwise, and one that declares a license (or whose Maven metadata is
  incomplete) without being listed fails the build, since internal modules declare none. HiveMQ builds get the public
  list as a default, so their reports now list HiveMQ's public libraries (the extension SDK, every MQTT client module,
  the customization SDKs) and their SBOMs leave the internal modules out like the reports always did. A build that
  called `allowedArtifacts.set(...)` lost the defaults and now fails, naming every public HiveMQ module it meets; it
  calls `add` instead. A prefix names a group and its subgroups: `com.hivemq` or `com.hivemq.` covers
  `com.hivemq.tools`, not `com.hivemqx`. The rule relies on internal modules declaring no license, as HiveMQ's do: a
  build whose internal POMs carry one, inherited from a parent POM for example, has nothing left to hide them with and
  drops the license there, or lists them. A build that named a third-party group there to hide its modules removes the
  prefix: they are listed and checked like any other library, with `overriddenLicenses` where their metadata needs it.
- A build whose jar bundles content that Gradle does not resolve, such as an npm frontend, adds a reason to
  `hivemqLicense.sbomIncompleteReasons`; otherwise its SBOM declares the dependency graph complete.

## Configuration

The `hivemqLicense` extension provides the following properties:

| Property                     | Type                          | Default                             | Required | Description                                                            |
|------------------------------|-------------------------------|-------------------------------------|----------|------------------------------------------------------------------------|
| `projectName`                | `Property<String>`            | `project.name`                      | No       | Display name used in license report headers                            |
| `thirdPartyLicenseDirectory` | `DirectoryProperty`           | -                                   | Yes      | Directory where license report files are generated                     |
| `ignoredGroupPrefixes`       | `SetProperty<String>`         | `["com.hivemq"]`                    | No       | Group prefixes of the build's own modules, see Own modules below       |
| `allowedArtifacts`           | `SetProperty<String>`         | HiveMQ's Maven Central libraries    | No       | Own modules listed like third-party ones; `group:name`, `*` wildcard   |
| `additionalConfigurations`   | `SetProperty<String>`         | `[]`                                | No       | Extra Gradle configurations to include in the CycloneDX BOM            |
| `overriddenLicenses`         | `MapProperty<String, String>` | `{}`                                | No       | Override license SPDX IDs for specific artifacts                       |
| `sbomName`                   | `Property<String>`            | `project.name`                      | No       | SBOM file name without `.cdx.json`; blank, `/` or `\` fails the build  |
| `sbomTimestamp`              | `Property<Instant>`           | see `generateSbom`                  | No       | `metadata.timestamp` of that SBOM                                      |
| `sbomCreatorName`            | `Property<String>`            | `HiveMQ GmbH`                       | No       | Name of the organisation that creates the SBOM and ships the component |
| `sbomCreator`                | `Property<String>`            | `security@hivemq.com`               | No       | Email address or http(s) URL of that organisation, or the build fails  |
| `sbomArtifactFileName`       | `Property<String>`            | `jar` or `shadowJar` file name      | No       | Top-level component's file name; the default needs the `java` plugin   |
| `sbomIncompleteReasons`      | `ListProperty<String>`        | `[]`                                | No       | Gaps the plugin cannot detect, e.g. a bundled frontend                 |

`sbomTimestamp`, `sbomCreatorName` and `sbomCreator` also apply to every `MergeSbomsTask`, see
[Product-level SBOMs](#product-level-sboms).

### Own modules

Every module in a group that `ignoredGroupPrefixes` names, or in one of its subgroups (`com.hivemq` covers
`com.hivemq.tools`, not `com.hivemqx`), is one of the build's own (a package without a group, from a pnpm report, is
matched by its name, `@hivemq/` for example); a third-party group does not belong there, since naming it no longer hides
its modules, see below. A module matching `allowedArtifacts` is public: listed in
the reports, checked and kept in the SBOM like any third-party library. Every other own module is internal: left out of
the reports, the check and the SBOM, which attaches what it brings in to the component that depended on it, so a
library that only an internal module brings in stays listed; a project of this build or of an included build is
internal unless listed. The plugin names no internal module, so nothing private leaks through it. It relies on internal
modules declaring no license instead: an own module that declares one without being listed fails
`checkApprovedLicenses`, `updateThirdPartyLicenses` and `generateSbom`, also when `hivemqProvided` resolves it, because
it is a public module nobody listed yet, or an internal one that gained a license, which has to lose it again (listing
it would make it public); nothing else hides a licensed own module. An own module whose Maven metadata CycloneDX could
not complete fails the same way, since nobody knows what it declares. CycloneDX records that in a component property,
which exists from CycloneDX 1.3 on, so a BOM declaring an older `specVersion` fails the three tasks too, and the lookup
that writes the licenses and that property, `includeMetadataResolution` of `generateDependencyBom`, cannot be turned
off.
Packages from a pnpm report never fail: internal frontend packages do declare licenses, so they are hidden unless
listed.
`allowedArtifacts` takes `group:name`, or a package name, where `*` matches any run of characters. Add to it, as `set`
replaces the defaults: HiveMQ's libraries on Maven Central (the extension SDK, the MQTT client modules, the
customization SDKs, the Testcontainers modules and others). A build whose own group is not HiveMQ's names it in
`ignoredGroupPrefixes` and its public modules in `allowedArtifacts`.

In a project with a `hivemqProvided` configuration (the HiveMQ extension Gradle plugin creates it for the dependencies
the broker supplies at runtime), everything that configuration resolves is left out of the reports and the check, the
same rule the extension jar applies when it leaves those libraries out; the public modules among them stay listed, and
the broker's own report covers the rest. The SBOM keeps, on each path, the first provided module that a shipped
component depends on as an identified component (BSI TR-03183-2 section 3.2.4: name, version, purl and creator, with
no dependencies of its own and no file description, since the artifact does not contain its file; the version is the one
`runtimeClasspath` resolved, the broker's SBOM describes the one it ships), the point from which the broker's SBOM
continues, and leaves out everything below it. Internal own modules are hidden first, so where an
internal module is provided, the modules directly below it are identified in its place. A public provided module that
only another provided module leads to is therefore in the reports but not in the SBOM. There is no extension property
for it. The CycloneDX BOM, `build/license/bom.json`, still lists these modules; `checkApprovedLicenses` and
`updateThirdPartyLicenses` skip them when they read it, and `generateSbom` keeps the identified ones only: CycloneDX
cannot drop part of `runtimeClasspath`, which contains them because `implementation` extends `hivemqProvided`.

### Advanced Example

```kotlin
hivemqLicense {
    projectName = "My Application"
    thirdPartyLicenseDirectory = layout.buildDirectory.dir("third-party-licenses")
    ignoredGroupPrefixes.add("com.mycompany")
    allowedArtifacts.add("com.mycompany:public-lib")
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
| `generateDependencyBom`    | Writes the CycloneDX BOM the other tasks read (no group)     |

### checkApprovedLicenses

Reads the CycloneDX BOM and validates that every dependency has a license from the approved list.
The task fails if any dependency has an unapproved or unknown license, or lists several licenses the plugin has not
reviewed (see Built-in expressions). One failure names the BOM file that was read, says when known whether this build
produced it, restored it from the build cache or found it unchanged from an earlier build, and lists every offending
dependency, so a BOM with several problems is fixed in one round. When the BOM records that a dependency's Maven
metadata is incomplete (`cdx:maven:package:metadata-unresolved`; for a BOM supplied by hand through `dependencyLicense`,
one written by CycloneDX Gradle plugin 3.5.0 or later), the entry says so and names the reason, to tell a POM that could
not be read or completed apart from a POM that declares no license.

### updateThirdPartyLicenses

Reads the CycloneDX BOM and generates two report files in the configured `thirdPartyLicenseDirectory`.
It validates licenses the same way as `checkApprovedLicenses` and fails on the same problems with the same message.
- `licenses`: plaintext report (or `<prefix>licenses` if `outputFileNamePrefix` is set)
- `licenses.html`: HTML report (or `<prefix>licenses.html` if `outputFileNamePrefix` is set)

Both reports contain a table with the following columns: Module, Version, License ID, and License URL.

The `outputFileNamePrefix` property can be set to prefix the output file names, which is useful when generating multiple reports (e.g. backend and frontend) into the same directory.

### generateSbom

Writes `build/sbom/<sbomName>.cdx.json`, a CycloneDX JSON SBOM derived from the `generateDependencyBom` output.
Nothing ships it by itself: add it to a distribution or image where the product is assembled, for example
`from(tasks.named("generateSbom")) { into("sbom") }` in a `Zip` task. It validates licenses the same way as
`checkApprovedLicenses` and fails on the same problems with the same message, so a build that ships the SBOM needs no
separate license check.

The CycloneDX Gradle plugin writes a file that differs on every run (current time, random serial number, the CI run
URL, a `vcs` reference taken from the `GIT_URL` variable or from the directory Gradle was started in), which changes
every archive that contains it and defeats the build cache. `generateSbom` writes the same bytes for the same
dependencies and timestamp:

- `metadata.timestamp` is `sbomTimestamp`, truncated to whole seconds. Without a value the plugin uses
  `SOURCE_DATE_EPOCH`, then the time of the repository's HEAD commit (`git log -1` in the project directory), then the
  current time with a warning. A missing `git` binary also falls back to the current time; a blank `SOURCE_DATE_EPOCH`
  counts as unset, and one that is not a number of seconds fails the build; so does a timestamp from either source
  that does not fit a four-digit year, such as a `SOURCE_DATE_EPOCH` given in milliseconds.
- `serialNumber` is a UUID computed from the content.
- The `dependsOn` lists of the `dependencies` are sorted; CycloneDX already writes the entries sorted by `ref`.
- The `build-system` external reference is dropped, and the `vcs` reference is the project's own `origin` remote, as
  `https://github.com/<org>/<repo>` for GitHub, with credentials removed; without a remote, or with one that is not an
  http(s) or ssh URL, it is left out. An scp-style remote `git@host:path` becomes `ssh://git@host/path`, the form
  hosting services such as GitLab use themselves, and an explicit `~` home is kept; a path that a plain ssh server takes
  relative to the login's home is written absolute too, so write it as `git@host:~/path` or set the task property
  `generateSbom.vcsUrl` for such a remote. The remote is read as configured in the repository, so a machine's
  `url.<base>.insteadOf` rewrites do not reach the SBOM; a remote entered as a shorthand that such a rule expands is
  read as written too, so `gh:org/repo.git` gives `ssh://gh/org/repo.git`, and only a clone with the full URL gives a
  reference that matches other machines. A URL set on the task property `generateSbom.vcsUrl` replaces the remote's,
  whether there is a remote or not; it is a task input, so it must not contain credentials. Only a GitHub remote gives
  the same URL whatever protocol the repository was cloned with; for other hosts an ssh and an https clone write
  different SBOMs. `vcs` and `build-system` references configured through
  `generateDependencyBom.externalReferences` are dropped as well, and a configured `source-distribution` reference is
  replaced too: the top-level component's `source-distribution` reference carries the same URL as `vcs`, or is left
  out with it.

In a Kotlin DSL build script, setting `sbomTimestamp` needs the import, because `java.time.Instant` written out in
full does not resolve once the `java` plugin is applied:

```kotlin
import java.time.Instant

hivemqLicense {
    sbomTimestamp.set(Instant.parse("2026-01-02T03:04:05Z"))
}
```

Every `signature` the BOM carries, on the document or on any part of it, is removed: the new serial number and
timestamp break the document's, and a signature its producer made does not vouch for what the plugin writes. A signed
signatory of the BOM's declarations without both an organization and an external reference, which the schema requires
of one without a signature, is removed with its signature; compositions or annotations that differed only in their
signatures are written once.

The file also carries what [BSI TR-03183-2](https://www.bsi.bund.de/SharedDocs/Downloads/EN/BSI/Publications/TechGuidelines/TR03183/BSI-TR-03183-2_v2_1_0.pdf?__blob=publicationFile)
requires beyond CycloneDX's defaults:

- The creator of the SBOM and of the component, as `metadata.manufacturer` and `metadata.component.manufacturer`,
  from `sbomCreatorName` and `sbomCreator` (an email address becomes a contact, a URL stays a URL); a manufacturer
  configured through `generateDependencyBom.organizationalEntity` is replaced.
- Per component: the file name and whether it is executable, an archive and structured
  (`bsi:component:filename`, `bsi:component:executable`, `bsi:component:archive`, `bsi:component:structured`),
  from the resolved artifact; a JAR counts as an executable, structured archive, a ZIP as a non-executable one, and a
  file of any other type gets only its name. Components without a file, such as Maven BOMs, get none. Projects of
  this build and of included builds get none either: only modules from a repository are resolved to files, so that no
  jar has to be built for the SBOM. The top-level component's file name comes from `sbomArtifactFileName`. By default
  it is the archive file name of the `jar` task, or of `shadowJar` when the Shadow plugin (`com.gradleup.shadow`, or
  its legacy id `com.github.johnrengelman.shadow`) is applied, because the plain jar does not ship then; a build that
  ships the plain jar next to a shadow jar sets `sbomArtifactFileName.set(tasks.jar.flatMap { it.archiveFileName })`.
  The default needs the `java` plugin; without a value the top-level component gets no file description.
- The component creator as a URL, and the repository as a `source-distribution` reference. The creator is the URL of
  the component's `website` reference, or else of its `vcs` reference; CycloneDX writes those from the `<organization>`
  and `<scm>` elements of the component's own POM, so a library whose POM inherits them from a parent POM gets no
  creator. A project of this build or of an included build has no POM and gets the creator of the SBOM.
- A `compositions` entry declaring the dependency graph `complete`, or `incomplete` when the configurations the BOM is
  built from resolve a file dependency, such as a jar checked into the repository, or fail to resolve a module:
  CycloneDX leaves both out of the BOM, and `generateSbom` warns with the reasons. A build declares what the plugin
  cannot see, such as a frontend that a separate build produces and the jar bundles, by adding a reason to
  `sbomIncompleteReasons`: the graph is then `incomplete` as well, without the warning. A file dependency counts no
  matter which project declares it: this one, or one it depends on, in this build or in an included build. `complete`
  refers to the dependency graph of the shipped artifact, without the modules a `hivemqProvided` configuration resolves.
  The identified components among them (see below) are listed in a second composition, `incomplete`, because their
  dependencies are left to the SBOM of the host, not because they have none; when the build reports a gap, the one
  `incomplete` composition covers every component in the graph, the top-level component included. A BOM from another
  tool may target a component that has no entry of its own in the graph, or have none for the top-level component; the
  plugin's own compositions never declare such a component complete, since the BOM states nothing about its
  dependencies. In a BOM without a dependency graph, such as one from another tool supplied through `dependencyLicense`,
  a gap is stated for the top-level component. Next to the plugin's compositions, which are about the dependency graph
  only, a composition the BOM already has loses the `dependencies` entries the plugin's compositions list and keeps the
  rest, its assemblies and vulnerabilities included; one that then names nothing is dropped, or kept with the aggregate
  `not_specified` when it has a `bom-ref`, which something may refer to. `generateSbom` fails when there is a gap to
  state and the BOM's `compositions` is not an array, or when the build reports a gap and the top-level component has no
  `bom-ref` to state it for.
- Licenses: the entries CycloneDX wrote from the POM are the declared licenses. A component in a version the reports
  do not list, or an identified component the reports leave out, keeps only those, tagged `declared`; any other gets
  what this plugin concludes, tagged `concluded`, in one of two shapes:
  - One SPDX expression naming every declared license, approved or not, when no override or built-in license covers the
    component and its declared licenses name more than one license: a declared expression that does, each license with
    an SPDX id, or a list of several licenses, which the plugin accepts only for a module with a built-in expression and
    reads as if its POM declared that expression alone (see Built-in expressions). A list of license entries is read as
    a choice, as the Maven model defines it; only an `AND`, in a declared expression or a built-in one, means that
    several apply together. The concluded expression is written in canonical form (see the expression rules under
    CycloneDX BOM), for example `EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0` for `jakarta.annotation-api`, and
    is the component's only license entry: the declared entries are dropped, with the license URL or text CycloneDX
    wrote for them, because a CycloneDX 1.6 license list holds either license objects or one expression, and the
    expression names every declared license anyway. A `bom-ref` on or inside a dropped entry goes with it, and
    `generateSbom` fails when any value, an annotation's subject included, still equals it, naming where it sits. The
    reports keep the alternative this plugin elected; the SBOM names every declared license, so that a reader can make
    their own choice.
  - Otherwise the declared entries stay, tagged `declared`, and one license object tagged `concluded` is added per
    license the reports list; a declared expression is then kept whole as the `name` of its entry, its `bom-ref`
    included, because the list cannot mix it with license objects. That is the case for a component with one license,
    also one that names it several times, for example once by id and once by a name the plugin maps to the same
    license, for one that an override or a built-in license covers, and for a declared expression that names a license
    without an SPDX id, such as `Public Domain OR MIT`.

The file names and the completeness follow the configurations the BOM is built from: `runtimeClasspath` and the
`additionalConfigurations`, which are the only ones `generateDependencyBom` reads; a gap declared in
`sbomIncompleteReasons` makes the graph incomplete as well.

Known gaps: first-party components (the top-level component and the projects of this build and of included builds) carry
no license, so a project dependency of a group that is not the build's own fails `generateSbom` like it fails
`checkApprovedLicenses` unless an override names it (a project of an own group is internal and left out, a public HiveMQ
one is listed as Apache-2.0, any other public one needs the override); an override or a built-in license gives a single
license, so a component it covers gets that license alone as concluded, even when the library offers a choice; a
declared expression that names a license without an SPDX id, such as `Public Domain OR MIT`, gets one concluded object
per elected license instead of the single SPDX expression BSI TR-03183-2 shows; a module whose BOM lists several
licenses needs a built-in expression, which takes a plugin release, or an override until then, because CycloneDX drops
the POM's `<comments>` that may give the list a meaning other than a choice, while a POM list that CycloneDX writes as
one expression passes as a declared expression, unreviewed (see Built-in expressions); files other than `.jar` and
`.zip` get a file name but no executable, archive or structured property; and a component whose POM inherits
`<organization>` and `<scm>` from a parent POM gets no creator, as described above.

Deliberate deviations: the serial number is derived from the content, while CycloneDX recommends a new serial number
for every generated BOM even when the content is unchanged; and the timestamp is `sbomTimestamp`, by default
`SOURCE_DATE_EPOCH` or the commit time, instead of the time the SBOM data was compiled, which is what BSI TR-03183-2
names. Both keep the file reproducible, so that archives containing it stay cacheable. BSI TR-03183-2 section 5.1
requires identifying, on each path, the first component outside the scope of delivery, so that the SBOM can be chained
with the SBOM of the environment the software runs in; the SBOM of a HiveMQ extension keeps the first module the broker
provides on each path as that component, except that an internal module is never named, and the modules directly below
it take its place. No SBOM names the internal modules it leaves out (see Own modules), only what they bring in. A
component whose concluded license is one SPDX expression carries no `declared` entries, although BSI TR-03183-2
section 5.2.4 requires its original licenses, mapped to `declared`, when they exist: a CycloneDX 1.6 license list holds
either license objects or a single expression, and the concluded expression already names every license the component
declares.

The internal own modules are left out first, with their dependency entries, nested in another component or not; each
leaves the components nested in it in its place, and its dependants depend on them from then on; what was nested in a
provided one stays provided, wherever it ends up. Then every version of the modules a `hivemqProvided` configuration
resolves is left out, together with everything nested in them, except the first provided component on each path, the one
a component that stays depends on directly: it stays identified, with an empty `dependsOn` list (an entry of its own if
it had none), its targets outside the provided modules handed to its dependants, and without a file description,
whatever the BOM carried, since the host provides it whole, and with the property `hivemq:sbom:provided-by-host` set to
`true`, which marks it as a component the host provides and the host's SBOM describes. A product merge (see
Product-level SBOMs) recognises identified components by this property alone. Its name is in a namespace of the plugin's
own: CycloneDX registers property names under `cdx:` only, so it is not part of the CycloneDX property taxonomy. An
identified component nested in another one stays in its place, one nested in a left-out component takes that component's
place. A reference to a left-out module is replaced by references to the components that stay and that it leads to,
directly or through other left-out modules, so the graph still reaches every component that stays, such as a module that
only another version of a provided module brings in; a reference to it, or to a license or another object inside it with
a `bom-ref` of its own, from a `provides` list, a vulnerability, an annotation or a composition is removed, and a
vulnerability or an annotation left with nothing to refer to goes with it; so does a composition, unless it has a
`bom-ref`, which keeps it with the aggregate `not_specified`. A reference from anywhere else, such as a declaration's
claim or a formulation workflow in a BOM another tool supplied, is not rewritten: `generateSbom` fails when any value
still equals the `bom-ref` of something it left out, naming where it sits.

The manufacturer fields, the license acknowledgements and the `source-distribution` reference exist from CycloneDX
specification 1.6 on, which is what `generateDependencyBom` writes by default; `generateSbom` fails on a BOM that
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

#### A second SBOM of the same project

A project that ships its jar under a second file name, for example because an image renames it, needs a second SBOM,
because BSI TR-03183-2 records one file name per component. The plugin configures every `GenerateSbomTask` of the
project like `generateSbom`: the license options (`ignoredGroupPrefixes`, `allowedArtifacts`, `overriddenLicenses`),
`providedModules`, `componentFileNames`, `incompleteReasons` and `declaredIncompleteReasons` as values that a task's
`add` or `put` extends and its `set(...)` replaces, everything else as a convention that `set(...)` replaces. A second
task therefore sets only what differs: for the renamed jar, the file name alone; a task for an artifact without the
bundled content that `sbomIncompleteReasons` declares also calls `declaredIncompleteReasons.set(emptyList())`. A task's
`add`, `put` or `set(...)` on a value must run after the plugin's own `set`, as it does in a task registered once the
plugin is applied, like the one below; one that runs earlier is discarded.

```kotlin
import com.hivemq.tools.license.gradle.sbom.GenerateSbomTask

val generateInitImageSbom by tasks.registering(GenerateSbomTask::class) {
    artifactFileName.set("hivemq-platform-operator-init-app.jar")
}
```

It writes `build/sbom/<task name>/<sbomName>.cdx.json`: the file keeps the component's name, and the directory keeps
two tasks from overwriting each other. `sbomElements` carries only the file of `generateSbom`, so a build that hands the
second SBOM to other projects adds a consumable configuration with a `Category` attribute of its own:

```kotlin
val initImageSbomElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named("init-image-sbom"))
    }
    outgoing.artifact(generateInitImageSbom.flatMap { it.outputFile })
}
```

On Gradle 8, which builds the artifacts of every visible configuration as part of `assemble`, the configuration also
needs `isVisible = false`, or `assemble` writes that SBOM too.

### Product-level SBOMs

A ZIP or a Docker image ships several components, each with its own `<component>.cdx.json`. The product as placed on
the market needs one SBOM of its own, which the plugin builds from those files: register a
`com.hivemq.tools.license.gradle.sbom.MergeSbomsTask` per deliverable and feed it the SBOMs of the parts, from the
`sbomElements` variant of every part built in the same composite and from the `sbom/` folder of a zip that comes from
a repository. In the example, `buildPlatformZip` is the `Zip` task that builds the deliverable and `dnsZip` is a
resolvable configuration that holds the published zip of an extension:

```kotlin
import com.hivemq.tools.license.gradle.sbom.MergeSbomsTask

val platformSboms by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named("sbom"))
    }
}
dependencies {
    platformSboms("com.hivemq:hivemq-enterprise")
    platformSboms("com.hivemq.extensions:hivemq-kafka-extension")
}
val mergePlatformZipSbom by tasks.registering(MergeSbomsTask::class) {
    productName.set("hivemq-platform")
    productVersion.set(project.version.toString())
    artifactFileName.set(buildPlatformZip.flatMap { it.archiveFileName })
    sboms.from(
        platformSboms,
        zipTree(dnsZip.elements.map { it.single().asFile }).matching { include("**/sbom/*.cdx.json") },
    )
    expectedParts.set(listOf("hivemq-enterprise", "hivemq-kafka-extension", "hivemq-dns-cluster-discovery"))
    hostParts.set(listOf("hivemq-enterprise"))
}
```

The zip goes in as a provider, `dnsZip.elements.map { it.single().asFile }`, so that the configuration resolves when
the task runs; `dnsZip.singleFile` would resolve it while the build is configured. A zip that a task of the same build
produces needs `dependsOn(thatTask)` on the merge task, or `sboms.builtBy(thatTask)`, because
`zipTree(...).matching { ... }` does not carry the task dependency the plain `zipTree(...)` would; a zip resolved from a
repository needs nothing.

The plugin applies the `sbomTimestamp`, `sbomCreatorName` and `sbomCreator` conventions, the project's `origin` remote
and the output `build/sbom/<task name>/<productName>.cdx.json` to every instance, so the registration sets the files,
the product name and its version; `artifactFileName`, `expectedParts`, `hostParts`, `licenseConflicts` and
`declaredIdentifiedGapReasons` are optional, although a deliverable that ships a host with what it hosts needs
`hostParts` (see below). The conventions come from the plugin, so the project that registers the task must apply it
(`id("com.hivemq.tools.license")`); that also registers the plugin's other tasks in that project, which stay unused
there.

The directory named after the task, the rule a second `GenerateSbomTask` follows as well, keeps two merges of the same
product from writing one file, such as those of a ZIP and of an image that both ship `hivemq-platform.cdx.json`, and
keeps every merge away from the file of `generateSbom`. A `productName` that is blank or contains `/` or `\` fails the
task unless `outputFile` is set.

Scanners read the `components` list as the inventory and do not reliably read components nested under another one, so
the merge is flat: every component keeps its `bom-ref`, the top-level component of each part becomes an `application`
component (without its `build-system` reference, which points at the CI run that built it), the product's top-level
component (`bom-ref` `<productName>@<productVersion>`) depends on every part, and a library two parts share appears
once, with the `dependsOn` lists of both. Components are sorted by `bom-ref` and parts by name, so the order of the
files does not change the output; the timestamp, serial number, `vcs` and `source-distribution` references follow the
same rules as `generateSbom`, so a rebuild from the same commit writes identical bytes. The output `specVersion` is the
highest one among the parts.

The task fails when `sboms` holds no file, on a file that is not a JSON object with `"bomFormat": "CycloneDX"`, a
`specVersion` of 1.6 or a later 1.x and a top-level component that has a `bom-ref` and a name, on any other component
without a `bom-ref` (an empty one counts as none, since it refers to nothing and the schema rejects it), on a component
with nested components, which the merge does not flatten, on a file with a non-empty `services` or `formulation` list or
a dependency entry with a non-empty `provides` list, which the merge does not carry, on a `dependencies` entry, a
`dependsOn` target or an `evidence` tool (of a component or of a pedigree component below it) that names anything but
the top-level component or an entry of `components`, such as a tool in `metadata.tools`, because the merge keeps no
other element of a file, on two parts whose top-level components have the same `bom-ref`, on a `bom-ref` defined below a
component that another node of the product defines too, such as a license `bom-ref` two parts use below different
components, on a file that gives a component, its top-level component or a node below one the `bom-ref` of the product,
on two entries for one `bom-ref` that name different components, on one hash algorithm with different digests, within
one entry or between two entries for the same `bom-ref`, and on two entries for one `bom-ref` that conclude different
licenses, unless `licenseConflicts` settles it (see below). Every message names the part and says how to fix it.
Different digests and different conclusions between entries are settled once the merge has left out the identified
components another version takes the place of (see below), so a disagreement about a component the product does not list
fails nothing.

Entries for the same `bom-ref` must name the same component, because a `bom-ref` is only unique within its own document
and two parts can reuse one: a `purl`, `group`, `name` or `version` that both carry must be equal. They are united field
by field: a field only one of them carries is kept, and any other field several carry, a description for example, keeps
the value of a part's top-level component, and otherwise of the part that sorts first by name, the entry that wins. The
`scope` is the exception: it is the strongest one any of the entries states, `required` over `optional` over `excluded`,
and the united entry carries it only when it is `optional` or `excluded`, because no `scope` means `required`. Two lists
are united by their entries instead: the `properties` by name, where the properties of the winning entry stay and those
of the others are appended when their name is new, and the `hashes` by algorithm, where every digest of an algorithm,
within one entry and between entries, must agree, compared without regard to case. Every other list comes from the
winning entry alone, except `licenses`: the product takes the whole list of the first entry that concludes a license,
one whose license object or expression carries `acknowledgement` `concluded`, so that it states the conclusion even
where the winning entry only declares licenses; without such an entry the list of the winning entry stays. Declared
entries that differ are no conflict, since the parts merely repeat what each library's metadata says.

Entries for the same `bom-ref` whose concluded entries differ, compared as sets so that their order does not matter,
fail the task like different digests do, naming the component, both parts and both conclusions: such parts were built
with different plugin releases or license settings, and the product would otherwise state one part's conclusion for
both. The remedy is to build the parts with the same settings. A prebuilt part that cannot be rebuilt without a release
gets an entry in `licenseConflicts` instead, the module id `group:name` to the conclusion the product states, for every
version of the module that has such a conflict, in one of two forms: the text of the expression a part concludes, or the
ids of the license objects a part concludes (the name for one without an id), separated by commas in any order, so a
license name that holds a comma cannot be given. The failure message tells the two apart, as in "the expression
Apache-2.0 AND MIT" and "the licenses Apache-2.0, MIT"; an expression value matches only a part that concludes that
expression, and a list of ids only a part that concludes exactly those license objects:

```kotlin
licenseConflicts.put("org.example:lib", "Apache-2.0, MIT")
licenseConflicts.put("org.example:other", "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0")
```

The product then keeps the whole `licenses` list of the part whose conclusion matches. The task fails when no part's
conclusion matches the entry, and on an entry for a module whose parts conclude no different licenses in this merge,
so that an entry does not outlive the conflict it settles.

The `compositions` entry of the product is `complete` over its whole graph when every part counts as complete and every
name in `expectedParts` contributed a file. A part counts as complete when every `compositions` entry of its SBOM that
has an `aggregate` says `complete`, or says `incomplete` over components the part identifies, each of which a host part
describes (see below), and those entries list, together in their `dependencies`, the top-level component, every entry of
the part's `dependencies` and every target of their `dependsOn` lists; a `complete` entry counts only for components
that have an entry in the part's `dependencies`, since the part states nothing about the others. A part whose entries
list only some of those is only partly complete, and one with no entry that lists `dependencies`, such as one that lists
`assemblies` alone, says nothing about its dependency graph; neither makes the product complete, so the product never
claims more than its parts do.

An `incomplete` entry is a gap of identified components when its `dependencies` list at least one ref and every ref it
lists is a component of the part that carries `hivemq:sbom:provided-by-host` with the value `true`, the property
`generateSbom` writes on the provided modules an extension only identifies (see Own modules), is in the part's
dependency graph, is not the part's own top-level component and is listed by no `complete` entry of the same SBOM; and
every other entry of that SBOM that is not `complete` has to be such a gap too. Any other `incomplete` entry, like one
over `assemblies` alone, says the part is incomplete in a way no host can fill, and the merge then changes nothing in
the part. A part from another tool, which writes no such property, is therefore never filled by a host part or given
another version: its `incomplete` entries leave its whole graph open. When such an entry is a gap of identified
components but for the property, the warning names the components without it, and says that a part built before the
plugin wrote the property needs a rebuild, and that a merged product loses the property where one of its parts ships the
component. A part describes a component when a `complete` entry of its SBOM lists the component's `bom-ref` in its
`dependencies`, the SBOM's `dependencies` have an entry for it and no entry of that SBOM with another `aggregate` lists
it there.

Only a host part fills the gap of another: a part that `hostParts` names, by the name of its top-level component as in
`expectedParts`, such as the broker `hivemq-enterprise`, which supplies the SDK and the other modules an extension
identifies. What an extension bundles runs in the extension's own classloader, so another extension's copy of a module
fills nothing, not even in the same version, and for the same reason no part fills the gap of a host part, another host
included: what a host identifies comes from outside the deliverable, and stays a gap. A platform ZIP or an image that
ships the broker with extensions therefore sets `hostParts` to the broker, and is complete while an extension alone is
not. A deliverable without its host, such as the init image of the operator, which ships extensions that run on the
broker of the platform image, sets no `hostParts`: its identified components stay a gap, which it declares with
`declaredIdentifiedGapReasons` (see below). In the product, `hivemq:sbom:provided-by-host` stays on a component while
every part that lists it labels it: a part that ships the component, and a host part that describes it, list it without
the property, and the product then drops it, so that it does not claim a host provides what a part ships. Every name in
`hostParts` must be the name of a part of the merge or one that `expectedParts` lists: a host part without an SBOM that
`expectedParts` lists keeps the product incomplete with a warning, like any missing part, while any other name, misspelt
or stale, fails the task, so that the setting cannot outlive the part it names.

An extension can only be built against a released extension SDK, so it usually identifies an older SDK, and older
versions of the other modules its host supplies, than the broker it ships with describes. When no host part describes
the exact `bom-ref` a part identifies, but exactly one `bom-ref` a host part describes has the same package, that is the
same purl type, namespace and name whatever the version, qualifiers and subpath, the identified component gives way to
it: the part's dependencies on the identified component point at the described one, the part's own dependency entry for
the identified component goes, and the gap counts as filled. That needs the part to state no dependencies of the
identified component, as `generateSbom` writes it; a labelled component with dependencies of its own in the part, as a
part from another tool may state them, keeps its gap, since the other version would take over neither its dependencies
nor its file. A library that a part of an already merged product ships never gets that far: the merged product lists it
without the property, so its `incomplete` entry keeps that part open as a whole. The identified component itself is left
out of the product unless a remaining dependency entry names it, as its `ref` or as a target, or another component
refers to it, by an evidence tool for example; one kept only for such a reference is outside the graph, and the product
lists it in its `incomplete` composition and warns about it, unless `declaredIdentifiedGapReasons` declares the gap,
which such a component then counts towards. The package comes from the component's `purl`, or from its `bom-ref` without
one, with the type compared in lower case and the namespace and name percent-decoded, so the SDK as an extension
identifies it, `pkg:maven/com.hivemq/hivemq-extension-sdk@4.1.0?type=jar`, gives way to the SDK as a broker built in one
composite with the SDK describes it, `pkg:maven/com.hivemq/hivemq-extension-sdk@<broker version>?project_path=%3A`.
Several matches, such as two host parts that describe the SDK in different versions, leave the gap open, because the
merge cannot tell which one the part runs on.

Where identified components stay undescribed and the parts have no other gap, such as in an image that ships extensions
without the broker they run on, the product states what `generateSbom` states for a single extension: `complete` over
everything else in its graph, and `incomplete` over those identified components only. Every other gap makes the
product's one `compositions` entry `incomplete` over the whole graph: an `incomplete` entry that names the part's own
top-level component, which `generateSbom` writes for a reported or declared gap (a file dependency, an unresolved
module, an `sbomIncompleteReasons` entry), because no other part can fill a gap of the part itself; an `incomplete`
entry over a component a `complete` entry of the same SBOM lists, which contradicts itself and may well be about a
component the part ships; an `incomplete` entry without `dependencies` and an entry whose `aggregate` is neither
`complete` nor `incomplete`; a part that is only partly complete or says nothing about its graph; and a name in
`expectedParts` without a file. The task warns with the reasons, naming the identified components no host part describes
and why: without `hostParts`, it suggests naming the host there, which fills the gap; for a host part, it says that no
part fills that gap; with host parts named, it names a host part whose SBOM the merge lacks, an identified component
without a package URL to match another version by, one the part states dependencies of, or else that the host parts
describe its package in no version or in several. Declaring the gap in `declaredIdentifiedGapReasons` accepts it in
every case.

A registration that knows why identified components stay undescribed adds the reason to `declaredIdentifiedGapReasons`:

```kotlin
declaredIdentifiedGapReasons.add("the extensions run on the broker of the platform image, whose SBOM describes it")
```

That gap is still written as `incomplete`, but no longer warned about; any other gap still is. The declaration covers
every identified component that no host part describes, whichever extension identifies it, and its text only documents
the build script: the task neither writes it into the SBOM, whose compositions have no place for it, nor logs it. A
blank reason fails the task, and so does a reason when the product has no such gap and no other gap that could hide one,
such as a part's gap over its whole graph or a missing part, because a declaration without its gap would silence the
warning about the next one.

A name in `expectedParts` is the name of a part's top-level component, which for a project built with this plugin is by
default the project name; `sbomName` only names the file. A complete product has an entry for every component in its
graph, and its composition lists them all, so it passes the same rule when it is merged again as a part of a larger
deliverable; so does a product that is complete but for undescribed identified components, whose gap the larger merge
fills when a part it names in `hostParts` describes those components.

The file name of a `.zip` deliverable gets the archive flags BSI TR-03183-2 asks for; an image has no file name, so
leave `artifactFileName` unset there.

Known gaps: the top-level component of the product carries no license and no hash; the same library listed under
different `bom-ref`s by different tools appears twice, because components are matched by `bom-ref` alone, except an
identified component that gives way to another version as described above; a part from another tool never has its
identified components filled, since only `generateSbom` writes the property the merge recognises them by; a merged
product named in `hostParts` of a larger merge counts as describing everything it lists, the libraries its extensions
bundle included, because the product SBOM does not record which part described what; a directory or an unreadable file
in `sboms` fails the task, so pass the files, for example those of a `fileTree`; and a patent assertion (CycloneDX 1.7)
on a component is copied as it is, although the patents it references live in `definitions` and an asserter it names by
`bom-ref` can live in the metadata, neither of which the merge carries.

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

Such a task starts with the extension's `allowedArtifacts`, so `add` extends it and `set` replaces it; the packages of
the own scope are left out unless listed, and never fail the build, whatever license they declare.

## Input Formats

The plugin supports two input formats:

### CycloneDX BOM (default)

The standard CycloneDX `bom.json` format generated by the CycloneDX Gradle Plugin. This is used automatically for Gradle/Maven dependencies.

A component may declare its licenses as single entries (`{ "license": { "id": "Apache-2.0" } }`) or as an SPDX
expression (`{ "expression": "(CDDL-1.0 OR GPL-2.0-with-classpath-exception)" }`), which is what the CycloneDX Gradle
Plugin writes for a POM license that maps to one.

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
  nothing, and the message says so when no entry of the component is approved; next to an approved entry it counts as
  a license of its own, so the list needs a built-in expression (see Built-in expressions).
- Declared entries the SBOM keeps stay as written, but its concluded expression is in canonical form:
  - A deprecated id, from a declared license entry or expression, takes the replacement the SPDX license list names
    for it: `GPL-2.0` becomes `GPL-2.0-only` and `GPL-2.0+` becomes `GPL-2.0-or-later`, likewise for the other GPL,
    LGPL, AGPL and GFDL versions. `GPL-2.0-with-classpath-exception` becomes
    `GPL-2.0-only WITH Classpath-exception-2.0` (SPDX names `GPL-2.0 WITH Classpath-exception-2.0`, whose `GPL-2.0` is
    rewritten too), likewise for the other GPL-2.0 and GPL-3.0 exception ids, with `-or-later` for their `+` forms.
    The few other deprecated ids SPDX names a replacement for are rewritten as well, such as `BSD-2-Clause-NetBSD` to
    `BSD-2-Clause`, and so is the deprecated exception `Nokia-Qt-exception-1.1`, to `Qt-LGPL-exception-1.1`. An id
    SPDX names no replacement for stays as written, since a deprecated id is still valid.
  - Ids on the approved list take its letter case.
  - Nested groups of the same operator are merged, the declared entries being the alternatives of one `OR`, and an
    operand repeated within one `OR` or one `AND`, even in different letter case or under a deprecated id, is written
    once: `MIT OR (Apache-2.0 OR mit)` becomes `MIT OR Apache-2.0`.
  - None of this changes approval: the id `LGPL-2.1+` is not approved although `LGPL-2.1-or-later` is.

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

Some well-known artifacts declare no license in their POM, or one that the CycloneDX Gradle Plugin maps to another
SPDX id or expression. The plugin includes built-in mappings for these, which take precedence over the BOM like
`overriddenLicenses` do:

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

HiveMQ's public libraries, the default `allowedArtifacts`, are not mapped: a published version declares its license in
its POM, which is checked like any other. Only one that a composite build includes as a project, which has no POM to
read a license from, is listed as Apache-2.0, the license of all of them.

These can be overridden using the `overriddenLicenses` configuration. An override must name an SPDX ID from the
approved table, matched regardless of case, and every override is checked, whether or not its module turns up in the
BOM.

### Built-in expressions

A list of several `<license>` elements in a POM is a choice: the Maven model says that "the user can select any of them,
not that they must accept all". A POM can say otherwise only in a `<comments>` element, which CycloneDX drops: the POM
of `logstash-logback-encoder` says there that its MIT entry covers code copied from SLF4J, so both licenses apply. The
plugin therefore accepts a module whose BOM lists several licenses only with a built-in expression, the SPDX expression
the list means as reviewed against the module's POM, and resolves the module as if its POM declared that expression, for
the reports and the SBOM alike. Any other module that lists several licenses fails the three tasks, naming its list,
until a plugin release adds it; an override can stand in until then. A list that names one license several times, for
example once by id and once by a name the plugin maps to the same license, names one license, and a single declared
expression of a module outside the table needs no built-in expression, as it states its meaning itself. The table speaks
for lists of several licenses only: a module in it whose version declares a single license resolves as that license,
like any other module.

For most modules the list declares the expression itself, a choice of its licenses; they are listed in
`BUILT_IN_EXPRESSIONS` in `LicenseResolver.kt` (the Jakarta, JAX-RS, JAXB and HK2 APIs, Eclipse Collections, Jetty, JNA,
JTS, Logback, java-json-tools, Javassist and Reflections). A module whose versions declare different lists, as JNA's
LGPL entry became "or later", has one expression per list. Twelve modules need an expression their list does not
declare, so their entries record the list as CycloneDX writes it. Of the Jersey modules (groups
`org.glassfish.jersey.*`), five inherit the project's list, which also names what other Jersey jars bundle and the
examples; the other three declare their own, whose comments give each extra license to bundled code:

| Artifact                                                                                                        | Expression                                                                               | Why the list does not declare it                                                                            |
|-----------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `net.logstash.logback:logstash-logback-encoder`                                                                 | `Apache-2.0 AND MIT`                                                                     | the POM's comments say both licenses apply                                                                  |
| `org.jboss.resteasy:resteasy-tracing-api`                                                                       | `EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0`                                   | the GPL license is given by a name CycloneDX maps to no SPDX id                                             |
| `org.rocksdb:rocksdbjni`                                                                                        | `Apache-2.0 OR GPL-2.0-only`                                                             | the GPL license is given by a name CycloneDX maps to no SPDX id                                             |
| `com.unboundid:unboundid-ldapsdk`                                                                               | `Apache-2.0 OR GPL-2.0-only OR LGPL-2.1-only OR LicenseRef-scancode-ldap-sdk-free-use`   | three licenses are given by name; the Free Use License has no SPDX id, so ScanCode's LicenseDB key names it |
| `jersey-client`, `jersey-container-jdk-http`, `jersey-entity-filtering`, `jersey-hk2`, `jersey-media-multipart` | `EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0`                                   | the project's list, though their jars bundle no other code                                                  |
| `jersey-common`                                                                                                 | `(EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0) AND Apache-2.0 AND CC0-1.0`      | Jersey's choice, a repackaged Guava, and JSR-166 classes in the public domain                               |
| `jersey-media-json-jackson`                                                                                     | `(EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0) AND Apache-2.0`                  | Jersey's choice and the Jackson JAX-RS providers                                                            |
| `jersey-server`                                                                                                 | `(EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0) AND Apache-2.0 AND BSD-3-Clause` | Jersey's choice, ASM and classes derived from Dropwizard                                                    |

A module passes while its list is the recorded one, in any order, or declares one of its expressions itself, for example
with the GPL license of `rocksdbjni` or `resteasy-tracing-api` given by an SPDX id; any other list or expression naming
several licenses fails the build, naming both, so the expression gets a review instead of going stale. An override wins
over a built-in expression, as it does over a built-in license. An entry is matched by the declared list, not by the
jar, so a version that bundles other code under the same list keeps the entry's expression; jersey-common's names the
JSR-166 classes that its 2.x and 3.x jars bundle and 4.x ones no longer do.

## How It Works

1. The plugin registers `generateDependencyBom`, the BOM task of the
   [CycloneDX Gradle Plugin](https://github.com/CycloneDX/cyclonedx-gradle-plugin), without applying that plugin; the
   task writes `build/license/bom.json` from `runtimeClasspath` and the `additionalConfigurations`.
2. The three tasks, `checkApprovedLicenses`, `updateThirdPartyLicenses` and `generateSbom`, parse the generated BOM and resolve licenses using SPDX IDs, license name patterns, and URL-based matching; the first two also read pnpm JSON.
   `generateSbom` writes the resolved licenses as the concluded ones; when no override or built-in license covers the
   component and its declared licenses name more than one license, each with an SPDX form, it writes a single SPDX
   expression naming every one instead. A module whose BOM lists several licenses fails all three unless the plugin
   has a built-in expression for it or an override names its license; with a built-in expression it is resolved as if
   its POM declared that expression alone.
3. Own modules, the ones under `ignoredGroupPrefixes`, are listed when `allowedArtifacts` names them and left out
   otherwise; a Maven module that declares a license, or whose metadata is incomplete, fails the build unless listed,
   since internal modules declare none; projects of the build and pnpm packages never fail.

## Requirements

- Gradle 8.4 or higher is required
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
