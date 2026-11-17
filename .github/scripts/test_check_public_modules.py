"""Tests of check_public_modules.py against a fake Maven Central: `python3 -m unittest discover -s .github/scripts`."""
import os
import tempfile
import unittest
import urllib.error
from unittest import mock

import check_public_modules as script

GROUP = "https://repo1.maven.org/maven2/com/hivemq"
APACHE = (
    "<license><name>Apache License, Version 2.0</name>"
    "<url>http://www.apache.org/licenses/LICENSE-2.0.txt</url></license>"
)
COMMONS_CLAUSE = "<license><name>Apache License, Version 2.0 with Commons Clause</name></license>"
SOURCE = """\
internal class OwnModules(
    ownGroupPrefixes: Set<String>,
    publicArtifacts: Set<String>,
) {
    companion object {
        /** HiveMQ's libraries on Maven Central, all Apache-2.0: listed by default, wherever they ship. */
        val PUBLIC_HIVEMQ_MODULES: Set<String> = sortedSetOf(
            "com.hivemq:hivemq-extension-sdk",
            "com.hivemq:hivemq-mqtt-client",
        )

        private val projectPath = Regex("[?&]project_path=")
    }
}
"""


def pom(packaging=None, licenses=(APACHE,)):
    body = f"<packaging>{packaging}</packaging>" if packaging else ""
    body += f"<licenses>{''.join(licenses)}</licenses>" if licenses else ""
    return f'<project xmlns="http://maven.apache.org/POM/4.0.0">{body}</project>'.encode()


def metadata(version, tag="latest"):
    unordered = "<versions><version>1.1</version><version>1.0</version></versions>"
    versioning = f"<{tag}>{version}</{tag}>" if tag else unordered
    return f"<metadata><versioning>{versioning}</versioning></metadata>".encode()


def central(artifacts, extra_directories=(), metadata_tag="latest"):
    """A fake fetch serving [artifacts], artifact id to POM bytes, every one in version 1.0."""
    names = list(artifacts) + list(extra_directories)
    pages = {f"{GROUP}/": "".join(f'<a href="{name}/">{name}/</a>' for name in names).encode()}
    for artifact_id, content in artifacts.items():
        pages[f"{GROUP}/{artifact_id}/maven-metadata.xml"] = metadata("1.0", metadata_tag)
        pages[f"{GROUP}/{artifact_id}/1.0/{artifact_id}-1.0.pom"] = content

    def fetch(url, attempts=3):
        if url not in pages:
            raise urllib.error.HTTPError(url, 404, "Not Found", {}, None)
        return pages[url]

    return fetch


class CheckPublicModulesTest(unittest.TestCase):

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.source = os.path.join(self.directory.name, "OwnModules.kt")
        self.body = os.path.join(self.directory.name, "body.md")
        self.write(SOURCE)

    def tearDown(self):
        self.directory.cleanup()

    def write(self, text):
        with open(self.source, "w", encoding="utf-8") as file:
            file.write(text)

    def read(self):
        with open(self.source, encoding="utf-8") as file:
            return file.read()

    def run_against(self, artifacts, extra_directories=(), metadata_tag="latest"):
        with mock.patch.object(script, "fetch", central(artifacts, extra_directories, metadata_tag)):
            with mock.patch("builtins.print"):
                return script.run(self.source, self.body)

    def entries(self):
        return [entry for _, entry in script.parse_set(self.read(), self.source)[1]]

    # --- Comparison ---

    def test_a_matching_list_is_left_alone(self):
        changed, unresolved = self.run_against({"hivemq-extension-sdk": pom(), "hivemq-mqtt-client": pom()})

        self.assertEqual((changed, unresolved), (False, False))
        self.assertEqual(self.read(), SOURCE)
        with open(self.body, encoding="utf-8") as file:
            self.assertIn("matches Maven Central", file.read())

    def test_adds_a_new_apache_library_keeping_the_order(self):
        artifacts = {"hivemq-extension-sdk": pom(), "hivemq-mqtt-client": pom(), "hivemq-aaa-new": pom()}

        changed, _ = self.run_against(artifacts)

        self.assertTrue(changed)
        self.assertIn(
            '            "com.hivemq:hivemq-aaa-new",\n            "com.hivemq:hivemq-extension-sdk",\n'
            '            "com.hivemq:hivemq-mqtt-client",\n        )',
            self.read(),
        )

    def test_keeps_a_listed_artifact_missing_on_central_and_reports_it(self):
        changed, unresolved = self.run_against({"hivemq-extension-sdk": pom()})

        # Maven Central does not forget artifacts, so a missing one is a typo or an index hiccup, for a human to judge
        self.assertEqual((changed, unresolved), (False, True))
        self.assertEqual(self.read(), SOURCE)
        with open(self.body, encoding="utf-8") as file:
            body = file.read()
        self.assertIn("not on Maven Central", body)
        self.assertIn("`com.hivemq:hivemq-mqtt-client`", body)

    def test_keeps_quiet_about_an_acknowledged_entry_missing_on_central(self):
        with mock.patch.object(script, "ACKNOWLEDGED", {"hivemq-mqtt-client"}):
            changed, unresolved = self.run_against({"hivemq-extension-sdk": pom()})

        self.assertEqual((changed, unresolved), (False, False))
        self.assertEqual(self.read(), SOURCE)

    def test_keeps_a_listed_artifact_whose_latest_release_is_no_library(self):
        changed, unresolved = self.run_against({
            "hivemq-extension-sdk": pom(),
            "hivemq-mqtt-client": pom("maven-plugin"),
        })

        # older releases may still be libraries in use
        self.assertEqual((changed, unresolved), (False, False))
        self.assertEqual(self.read(), SOURCE)

    def test_keeps_entries_of_other_groups_and_patterns(self):
        self.write(SOURCE.replace('            "com.hivemq:hivemq-mqtt-client",\n',
                                  '            "com.hivemq:hivemq-mqtt-client",\n'
                                  '            "com.hivemq:hivemq-mqtt-client-*",\n'
                                  '            "com.example:other",\n'))

        self.run_against({"hivemq-extension-sdk": pom(), "hivemq-mqtt-client": pom(), "hivemq-new": pom()})

        self.assertEqual(self.entries(), [
            "com.example:other",
            "com.hivemq:hivemq-extension-sdk",
            "com.hivemq:hivemq-mqtt-client",
            "com.hivemq:hivemq-mqtt-client-*",
            "com.hivemq:hivemq-new",
        ])

    def test_skips_archetypes_plugins_and_directories_without_metadata(self):
        artifacts = {
            "hivemq-extension-sdk": pom(),
            "hivemq-mqtt-client": pom(),
            "hivemq-extension-archetype": pom("maven-archetype"),
            "hivemq-maven-plugin": pom("maven-plugin"),
        }

        changed, unresolved = self.run_against(artifacts, extra_directories=["extensions"])

        self.assertEqual((changed, unresolved), (False, False))
        with open(self.body, encoding="utf-8") as file:
            self.assertIn("3 skipped", file.read())

    # --- Licenses ---

    def test_does_not_add_a_library_under_another_license_or_with_two_licenses(self):
        other = "<license><name>Commercial</name></license>"
        artifacts = {
            "hivemq-extension-sdk": pom(),
            "hivemq-mqtt-client": pom(),
            "hivemq-closed": pom(licenses=(other,)),
            "hivemq-dual": pom(licenses=(APACHE, other)),
            "hivemq-clause": pom(licenses=(COMMONS_CLAUSE,)),
            "hivemq-silent": pom(licenses=()),
        }

        changed, unresolved = self.run_against(artifacts)

        self.assertEqual((changed, unresolved), (False, True))
        self.assertEqual(self.read(), SOURCE)
        with open(self.body, encoding="utf-8") as file:
            body = file.read()
        for artifact_id in ("hivemq-closed", "hivemq-dual", "hivemq-clause", "hivemq-silent"):
            self.assertIn(f"`com.hivemq:{artifact_id}`", body)

    def test_reports_a_listed_library_whose_license_changed_and_keeps_it_listed(self):
        changed, unresolved = self.run_against({
            "hivemq-extension-sdk": pom(),
            "hivemq-mqtt-client": pom(licenses=("<license><name>Proprietary</name></license>",)),
        })

        self.assertEqual((changed, unresolved), (False, True))
        self.assertEqual(self.read(), SOURCE)

    def test_keeps_an_acknowledged_unlisted_artifact_unlisted_whatever_its_pom_declares(self):
        artifacts = {
            "hivemq-extension-sdk": pom(),
            "hivemq-mqtt-client": pom(),
            "hivemq-closed": pom(licenses=("<license><name>Commercial</name></license>",)),
            "hivemq-relicensed": pom(),
        }

        with mock.patch.object(script, "ACKNOWLEDGED", {"hivemq-closed", "hivemq-relicensed"}):
            changed, unresolved = self.run_against(artifacts)

        self.assertEqual((changed, unresolved), (False, False))
        self.assertEqual(self.read(), SOURCE)

    def test_reports_a_listed_artifact_whose_license_changed_although_acknowledged(self):
        # a listed module stands for Apache-2.0 in composite builds, so a contradicting POM stays loud until removed
        artifacts = {
            "hivemq-extension-sdk": pom(),
            "hivemq-mqtt-client": pom(licenses=("<license><name>Commercial</name></license>",)),
        }

        with mock.patch.object(script, "ACKNOWLEDGED", {"hivemq-mqtt-client"}):
            changed, unresolved = self.run_against(artifacts)

        self.assertEqual((changed, unresolved), (False, True))
        self.assertEqual(self.read(), SOURCE)

    def test_recognises_the_apache_spellings_by_name_or_url(self):
        for name, url in [
            ("Apache-2.0", "https://spdx.org/licenses/Apache-2.0.html"),
            ("The Apache Software License, Version 2.0", "http://www.apache.org/licenses/LICENSE-2.0.txt"),
            ("Apache License 2.0", ""),
            ("", "https://www.apache.org/licenses/LICENSE-2.0"),
        ]:
            self.assertTrue(script.is_apache_2(name, url), (name, url))
        for name, url in [
            ("Apache License, Version 2.0 with Commons Clause", ""),
            ("Apache License, Version 2.0 with Commons Clause", "https://www.apache.org/licenses/LICENSE-2.0"),
            ("Apache-2.0", "https://opensource.org/licenses/MIT"),
            ("MIT", "https://opensource.org/licenses/MIT"),
            ("MIT", "https://www.apache.org/licenses/LICENSE-2.0"),
        ]:
            self.assertFalse(script.is_apache_2(name, url), (name, url))

    # --- Reading the set ---

    def test_finds_the_set_although_its_name_appears_earlier(self):
        mention = "/** Defaults: [PUBLIC_HIVEMQ_MODULES]. */\n"
        self.write(mention + SOURCE)

        changed, _ = self.run_against({"hivemq-extension-sdk": pom(), "hivemq-mqtt-client": pom(), "hivemq-new": pom()})

        self.assertTrue(changed)
        self.assertIn("com.hivemq:hivemq-new", self.entries())
        # the constructor, the first parenthesis after the mention, is untouched
        self.assertTrue(self.read().startswith(mention + SOURCE.split("companion", 1)[0]))

    def test_refuses_a_set_with_a_line_it_does_not_understand(self):
        for line in ('            // the SDKs\n', '            "com.hivemq:hivemq-last"\n'):
            self.write(SOURCE.replace('            "com.hivemq:hivemq-mqtt-client",\n', line))

            with self.assertRaises(SystemExit):
                self.run_against({"hivemq-extension-sdk": pom()})
            self.assertNotIn("hivemq-aaa", self.read())

    # --- Versions ---

    def test_refuses_metadata_naming_neither_latest_nor_release(self):
        with self.assertRaises(SystemExit):
            self.run_against({"hivemq-extension-sdk": pom(), "hivemq-mqtt-client": pom()}, metadata_tag=None)
        self.assertEqual(self.read(), SOURCE)

    def test_uses_the_release_version_without_a_latest_one(self):
        changed, unresolved = self.run_against(
            {"hivemq-extension-sdk": pom(), "hivemq-mqtt-client": pom()}, metadata_tag="release"
        )

        self.assertEqual((changed, unresolved), (False, False))

    # --- Refusing to wipe the list ---

    def test_refuses_an_empty_index(self):
        with self.assertRaises(SystemExit):
            self.run_against({})
        self.assertEqual(self.read(), SOURCE)

    def test_refuses_when_a_listed_artifact_has_no_metadata(self):
        with self.assertRaises(SystemExit):
            self.run_against({"hivemq-extension-sdk": pom()}, extra_directories=["hivemq-mqtt-client"])
        self.assertEqual(self.read(), SOURCE)


if __name__ == "__main__":
    unittest.main()
