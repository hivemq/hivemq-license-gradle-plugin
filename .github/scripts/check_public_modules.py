#!/usr/bin/env python3
"""Brings OwnModules.PUBLIC_HIVEMQ_MODULES in line with the artifacts of the group on Maven Central.

Usage: check_public_modules.py <OwnModules.kt> <body.md> [group]

Adds every artifact that can be a dependency and whose latest POM declares Apache-2.0 and nothing else, the built-in
license of the listed modules; an entry Maven Central does not have is kept and reported, as its releases may still come
from a mirror or a composite build. Writes a pull request body, prints it, and writes `changed=true|false` (the file was
edited) and `unresolved=true|false` (an artifact needs a look) to $GITHUB_OUTPUT when set. Exits non-zero, editing
nothing, when Maven Central or the file cannot be read or when the set is not written the way this script reads it.
"""
import os
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ElementTree

REPOSITORY = "https://repo1.maven.org/maven2"
# archetypes and Maven plugins are never dependencies, so they never appear in a BOM
NOT_A_DEPENDENCY = {"maven-archetype", "maven-plugin"}
# artifacts somebody looked at, by artifact id: an unlisted one stays unlisted whatever its POM declares, a listed one
# that left the index stays listed; a listed one whose POM contradicts Apache-2.0 is always reported
ACKNOWLEDGED = set()
# the names and URLs HiveMQ's POMs have used for Apache-2.0 over the years, compared after normalizing the spelling
APACHE_2_NAMES = {
    "apache-2.0",
    "apache 2.0",
    "apache license 2.0",
    "apache license version 2.0",
    "the apache software license version 2.0",
    "asl 2.0",
}
APACHE_2_URLS = {
    "apache.org/licenses/license-2.0",
    "spdx.org/licenses/apache-2.0",
    "opensource.org/licenses/apache-2.0",
}
# one entry per line, quoted, with a trailing comma, as the set is written
ENTRY = re.compile(r'^( *)"([^"]+)",$')


def fetch(url, attempts=3):
    """The content at [url]; a transient failure is retried, a 404 is raised at once."""
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404 or attempt == attempts:
                raise
        except urllib.error.URLError:
            if attempt == attempts:
                raise
        time.sleep(5 * attempt)


def local_name(element):
    return element.tag.rsplit("}", 1)[-1]


def children(element, name):
    # the POM namespace varies between producers, so elements are matched by their local name
    return [child for child in element if local_name(child) == name]


def group_path(group):
    return f"{REPOSITORY}/{group.replace('.', '/')}"


def artifact_ids(group):
    # the repository's directory index is complete, unlike the search index, which skips some artifacts
    index = fetch(f"{group_path(group)}/").decode()
    ids = sorted(set(re.findall(r'href="([^"/]+)/"', index)) - {".."})
    if not ids:
        sys.exit(f"the index of {group} on Maven Central lists no artifacts; not touching the list")
    return ids


def latest_version(group, artifact_id):
    """The latest version of the artifact, or None for a directory without metadata, such as a subgroup."""
    try:
        data = fetch(f"{group_path(group)}/{artifact_id}/maven-metadata.xml")
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise
    metadata = ElementTree.fromstring(data)
    # the versions list is in no particular order, so only an explicit latest or release counts
    latest = metadata.findtext("versioning/latest") or metadata.findtext("versioning/release")
    if not latest:
        sys.exit(f"{artifact_id}: its maven-metadata.xml names neither a latest nor a release version")
    return latest


def describe(group, artifact_id, version):
    """The packaging and the licenses, as (name, url) pairs, the POM of that version declares."""
    pom = ElementTree.fromstring(fetch(f"{group_path(group)}/{artifact_id}/{version}/{artifact_id}-{version}.pom"))
    packaging = (next((child.text for child in children(pom, "packaging")), None) or "jar").strip()
    licenses = [
        (
            next((field.text or "" for field in children(license, "name")), ""),
            next((field.text or "" for field in children(license, "url")), ""),
        )
        for licenses in children(pom, "licenses")
        for license in children(licenses, "license")
    ]
    return packaging, licenses


def is_apache_2(name, url):
    normalized = re.sub(r"\s+", " ", name.lower().replace(",", "")).strip()
    address = re.sub(r"^https?://(www\.)?", "", url.lower().strip()).removesuffix("/")
    by_name = normalized in APACHE_2_NAMES
    by_url = address.removesuffix(".txt").removesuffix(".html") in APACHE_2_URLS
    # a name and a URL have to agree: a clause added to the name, or the URL of another license, needs a look
    return by_name and by_url if normalized and address else by_name or by_url


def parse_set(text, source):
    """The match of the set's lines and its (indent, entry) pairs; exits when the set is not written as expected."""
    block = re.search(r"(val PUBLIC_HIVEMQ_MODULES\b[^(\n]*\(\n)(.*?)(\n *\))", text, re.S)
    if block is None:
        sys.exit(f"{source}: no PUBLIC_HIVEMQ_MODULES set")
    entries = []
    for line in block.group(2).split("\n"):
        if not line.strip():
            continue
        match = ENTRY.match(line)
        if match is None:
            sys.exit(f"{source}: one quoted entry with a trailing comma per line is all this script reads, not: {line}")
        entries.append(match.groups())
    if not entries:
        sys.exit(f"{source}: the PUBLIC_HIVEMQ_MODULES set is empty")
    return block, entries


def run(source, body_path, group="com.hivemq"):
    """Updates [source] from Maven Central and writes the pull request body; returns (changed, unresolved)."""
    with open(source, encoding="utf-8") as file:
        text = file.read()
    block, entries = parse_set(text, source)
    indent = entries[0][0]
    prefix = f"{group}:"
    # a pattern cannot be compared with an artifact and stays as it is
    listed = {entry[len(prefix):] for _, entry in entries if entry.startswith(prefix) and "*" not in entry}

    published, skipped, unresolved = {}, {}, {}
    for artifact_id in artifact_ids(group):
        version = latest_version(group, artifact_id)
        if version is None:
            # a listed module has metadata; without it nobody knows whether it is gone, so nothing is removed
            if artifact_id in listed:
                sys.exit(f"{artifact_id} is listed but its maven-metadata.xml cannot be read; not touching the list")
            skipped[artifact_id] = "no metadata, a subgroup perhaps, not checked"
            continue
        packaging, licenses = describe(group, artifact_id, version)
        if packaging in NOT_A_DEPENDENCY:
            skipped[artifact_id] = packaging
        elif artifact_id in ACKNOWLEDGED and artifact_id not in listed:
            # before the license, so that a decision taken stands whatever a later POM declares
            skipped[artifact_id] = "acknowledged, unlisted on purpose"
        elif licenses and all(is_apache_2(name, url) for name, url in licenses):
            published[artifact_id] = version
        else:
            # a module under another license would get the wrong built-in license; somebody has to look at it
            unresolved[artifact_id] = (version, "; ".join(name or url for name, url in licenses) or "no license")

    added = sorted(set(published) - listed)
    # nothing is removed: a listed module that changed its license, became no library or left the index is reported
    missing = sorted(listed - set(published) - set(unresolved) - set(skipped) - ACKNOWLEDGED)
    changed = bool(added)
    if changed:
        kept = {entry for _, entry in entries} | {f"{prefix}{a}" for a in added}
        text = text[: block.start(2)] + "\n".join(f'{indent}"{entry}",' for entry in sorted(kept)) + text[block.end(2):]
        with open(source, "w", encoding="utf-8") as file:
            file.write(text)

    body = [
        f"`OwnModules.PUBLIC_HIVEMQ_MODULES` lists the `{group}` libraries on Maven Central that every build using the "
        "plugin reports as public modules, with Apache-2.0 as their built-in license. This compares it with the "
        f"repository index: {len(published) + len(unresolved)} artifacts that can be dependencies, {len(skipped)} "
        f"skipped ({', '.join(f'`{a}`: {why}' for a, why in sorted(skipped.items())) or 'none'}).",
        "",
    ]
    if added:
        body += ["Added, the latest POM declares Apache-2.0 only:", ""]
        body += [f"- `{prefix}{a}` (latest {published[a]})" for a in added]
        body.append("")
    if missing:
        body += [
            "In the list but not on Maven Central, kept: releases may still come from a mirror or a composite build. "
            "Remove the entry, or acknowledge it in `ACKNOWLEDGED` of `.github/scripts/check_public_modules.py`:",
            "",
        ]
        body += [f"- `{prefix}{a}`" for a in missing]
        body.append("")
    if unresolved:
        body += [
            "Not added, or kept as listed: the latest POM declares a license other than Apache-2.0, or none, so the "
            "built-in license would be wrong. A module that is in fact under an approved license, which its POM states "
            "wrongly or not at all, gets the POM fixed or an `overriddenLicenses` entry next to `allowedArtifacts` in "
            "the consuming builds; one under a license that is not approved needs a decision first. Once decided, an "
            "unlisted module is acknowledged in `ACKNOWLEDGED` of `.github/scripts/check_public_modules.py`, a listed "
            "one is removed from the set by hand:",
            "",
        ]
        body += [f"- `{prefix}{a}` (latest {v}): {licenses}" for a, (v, licenses) in sorted(unresolved.items())]
        body.append("")
    if not (added or missing or unresolved):
        body += ["The list matches Maven Central.", ""]
    report = "\n".join(body)
    with open(body_path, "w", encoding="utf-8") as file:
        file.write(report)
    print(report)
    return changed, bool(unresolved or missing)


def main():
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    changed, unresolved = run(*sys.argv[1:])
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as file:
            file.write(f"changed={'true' if changed else 'false'}\nunresolved={'true' if unresolved else 'false'}\n")


if __name__ == "__main__":
    main()
