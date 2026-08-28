#!/usr/bin/env python3
"""Synthetic temporary fixtures test the gate; they are never project release evidence."""

from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid
import warnings
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZipInfo
import zlib

import verify_evidence as gate


def png_chunk(kind: bytes, content: bytes) -> bytes:
    return struct.pack(">I", len(content)) + kind + content + struct.pack(">I", zlib.crc32(kind + content))


def png() -> bytes:
    return (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0))
            + png_chunk(b"IDAT", zlib.compress((b"\0" + b"\x55\x88\xaa" * 2) * 2)) + png_chunk(b"IEND", b""))


def zip_bytes(entries: dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with ZipFile(buffer, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return buffer.getvalue()


class EvidenceGateTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.output = self.root / "build/verification"
        self.client = self.root / ".codex-local/client-evidence"
        replacement = patch.multiple(gate, ROOT=self.root, OUTPUT=self.output, CLIENT=self.client)
        replacement.start()
        self.addCleanup(replacement.stop)
        self.started = time.time() - 10
        self.fresh = self.started + 1
        self.run_id = "66c3cced-711e-4cbe-9d32-2b754ab67b17"
        self.multiplayer_id = "9f2b4d93-1d7c-44bc-94c3-8d2410f4e116"
        for name in ("build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat", "LICENSE", ".gitattributes", ".gitignore"):
            self.write(self.root / name, b"synthetic gate-test fixture\n")
        self.unit_source = self.root / "src/test/java/example/RuleTest.java"
        self.write(self.unit_source, b'package example; class RuleTest { @Test void invariant() {} }')
        self.server_source = self.root / "src/gametest/java/com/kadamitas/fabricatedbackpacks/gametest/BackpackGameTests.java"
        self.write(self.server_source, b'public class BackpackGameTests { @GameTest(template="platform") public void roundTrip() {} @GameTest(maxTicks=40) public void transactionRollback() {} }')
        self.unit = self.root / "build/test-results/test/TEST-example.RuleTest.xml"
        unit = ET.Element("testsuite", name="example.RuleTest", tests="1", failures="0", errors="0", skipped="0", timestamp=self.timestamp(self.fresh))
        ET.SubElement(unit, "testcase", classname="example.RuleTest", name="invariant()")
        self.xml(self.unit, unit)
        self.execution = self.root / "build/test-results/test/unit-execution.json"
        self.unit_execution([("example.RuleTest", "invariant", "invariant()")])
        self.server = self.root / "build/gametest-results.xml"
        server = ET.Element("testsuite")
        for name in sorted(gate.expected_server_ids()) + ["minecraft:always_pass"]:
            ET.SubElement(server, "testcase", classname="platform", name=name)
        self.xml(self.server, server)
        self.jar = self.root / "build/libs/fabricated-backpacks-0.5.0-alpha.jar"
        self.metadata = {"id": "fabricated_backpacks", "version": "0.5.0-alpha", "license": "MIT", "depends": {"minecraft": "26.2"},
                         "entrypoints": {"main": ["example.Main"], "client": ["example.Client"]}, "jars": [{"file": "META-INF/jars/energy-5.0.0.jar"}]}
        self.clean_jar = zip_bytes({"fabric.mod.json": json.dumps(self.metadata).encode(),
                                   "com/kadamitas/fabricatedbackpacks/FabricatedBackpacks.class": b"\xca\xfe\xba\xbe synthetic",
                                   "META-INF/jars/energy-5.0.0.jar": zip_bytes({"fabric.mod.json": b'{"id":"team_reborn_energy"}'})})
        self.write(self.jar, self.clean_jar)
        self.make_client_evidence()
        self.start_record()

    @staticmethod
    def timestamp(value: float) -> str:
        return datetime.fromtimestamp(value, timezone.utc).isoformat()

    def write(self, path: Path, data: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        os.utime(path, (self.fresh, self.fresh))

    def document(self, path: Path, value: dict) -> None:
        self.write(path, json.dumps(value).encode())

    def xml(self, path: Path, root: ET.Element) -> None:
        self.write(path, ET.tostring(root))

    def unit_execution(self, leaves: list[tuple[str, str, str]], methods: list[tuple[str, str]] | None = None) -> None:
        declared = methods if methods is not None else sorted({(name, method) for name, method, display in leaves})
        self.document(self.execution, {
            "schema": 1, "complete": True, "pid": 1301,
            "started_at": int(self.fresh * 1000), "finished_at": int(self.fresh * 1000),
            "methods": [{"class_name": name, "method_name": method, "parameter_types": ""} for name, method in declared],
            "tests": [{"class_name": name, "method_name": method, "parameter_types": "",
                       "id": f"[engine:junit-jupiter]/[class:{name}]/[method:{method}]/[case:{index}]",
                       "display_name": display, "legacy_name": display, "status": "SUCCESSFUL"}
                      for index, (name, method, display) in enumerate(leaves)],
            "problems": [],
        })

    def unit_report(self, leaves: list[tuple[str, str, str]]) -> None:
        root = ET.Element("testsuite", tests=str(len(leaves)), failures="0", errors="0", skipped="0",
                          timestamp=self.timestamp(self.fresh))
        for name, method, display in leaves:
            ET.SubElement(root, "testcase", classname=name, name=display)
        self.xml(self.unit, root)
        self.unit_execution(leaves)

    def mutate(self, path: Path, update) -> None:
        value = json.loads(path.read_bytes())
        update(value)
        self.document(path, value)

    def start_record(self) -> None:
        self.document(self.output / "start.json", {"schema": 1, "run_id": self.run_id, "started": self.started, "inputs": gate.inputs()})

    def make_client_evidence(self) -> None:
        self.document(self.client / "full-pass.json", {"passed": True, "pid": 1101, "checks": ["Synthetic verifier fixture only"]})
        self.document(self.client / "restart-pass.json", {"passed": True, "writer_pid": 1101, "reader_pid": 1102})
        for directory in ("full-screenshots", "restart-screenshots"):
            self.write(self.client / directory / "fixture.png", png())
        self.multiplayer = self.client / ("multiplayer-" + self.multiplayer_id)
        self.document(self.output / "multiplayer.json", {"passed": True, "run_id": self.multiplayer_id,
                      "host_pid": 1201, "guest_pid": 1202, "host_exit": 0, "guest_exit": 0, "evidence_dir": str(self.multiplayer)})
        for phase, role, pid, extra in (
            ("ready", "host", 1201, {"host_uuid": "fd9c2744-5f67-46aa-aebb-59ef1efb38a6", "port": 25432}),
            ("host-pass", "host", 1201, {"guest_pid": 1202, "stored_emeralds": 19, "channels": "Sounds: 0/247 + 0/8"}),
            ("guest-pass", "guest", 1202, {"host_pid": 1201, "guest_uuid": "f174a742-27ef-4b4d-81c9-fd9ee90ad902", "channels": "Sounds: 0/247 + 0/8"}),
        ):
            self.document(self.multiplayer / f"{phase}.json", {"run_id": self.multiplayer_id, "phase": phase,
                          "role": role, "pid": pid, "recorded_at": int(self.fresh * 1000), **extra})
        for name in ("host-sees-shared-19.png", "guest-inserts-19.png", "guest-sharing-revoked.png"):
            self.write(self.multiplayer / name, png())
        self.manual_image = self.root / "build/manual/screenshot.png"
        self.write(self.manual_image, png())
        self.document(self.output / "manual.json", {"passed": True, "artifact_sha256": gate.sha256(self.jar),
                      "observations": ["Temporary synthetic fixture; not manual gameplay evidence"],
                      "screenshots": ["build/manual/screenshot.png"]})

    def check_cli(self, arguments: list[str]) -> tuple[int, str]:
        output = io.StringIO()
        with redirect_stdout(output), redirect_stderr(output):
            code = gate.main(arguments)
        return code, output.getvalue()

    def test_complete_automated_and_release_fixtures_are_accepted(self) -> None:
        automated = gate.verify(False)
        self.assertEqual((1, 3, 2), (automated["unit_tests"], automated["server_tests"], automated["mod_server_tests"]))
        self.assertEqual("unit-and-server", automated["scope"])
        self.assertNotIn("client", automated)
        released = gate.verify(True)
        self.assertEqual(gate.sha256(self.jar), released["artifact"]["sha256"])
        self.assertEqual(self.multiplayer_id, released["client"]["multiplayer"]["run_id"])
        self.assertEqual(gate.sha256(self.manual_image), released["client"]["manual"]["verified_screenshots"][0]["sha256"])

    def test_each_required_report_and_jar_is_mandatory(self) -> None:
        for path in (self.unit, self.execution, self.server, self.jar, self.client / "full-pass.json", self.client / "restart-pass.json",
                     self.output / "multiplayer.json", self.multiplayer / "host-pass.json", self.output / "manual.json"):
            with self.subTest(path=path.relative_to(self.root)):
                original = path.read_bytes()
                path.unlink()
                try:
                    with self.assertRaises(ValueError):
                        gate.verify(True)
                finally:
                    self.write(path, original)

    def test_empty_and_one_millisecond_stale_files_are_rejected(self) -> None:
        original = self.unit.read_bytes()
        self.write(self.unit, b"")
        with self.assertRaisesRegex(ValueError, "Empty"):
            gate.verify(False)
        self.write(self.unit, original)
        os.utime(self.unit, (self.started - .001, self.started - .001))
        with self.assertRaisesRegex(ValueError, "predates"):
            gate.verify(False)

    def test_touching_an_old_suite_does_not_make_its_execution_fresh(self) -> None:
        root = ET.fromstring(self.unit.read_bytes())
        root.set("timestamp", self.timestamp(self.started - 60))
        self.xml(self.unit, root)
        with self.assertRaisesRegex(ValueError, "Suite execution predates"):
            gate.verify(False)

    def test_suite_counts_errors_and_skips_fail_even_without_case_children(self) -> None:
        original = self.unit.read_bytes()
        for attribute, value in (("errors", "1"), ("failures", "1"), ("skipped", "1"), ("disabled", "1"),
                                 ("tests", "0"), ("tests", "2"), ("errors", "-1"), ("tests", "NaN")):
            with self.subTest(attribute=attribute, value=value):
                root = ET.fromstring(original)
                root.set(attribute, value)
                self.xml(self.unit, root)
                with self.assertRaises(ValueError):
                    gate.verify(False)

    def test_failed_error_and_skipped_cases_are_rejected(self) -> None:
        original = self.unit.read_bytes()
        for status in ("failure", "error", "skipped"):
            with self.subTest(status=status):
                root = ET.fromstring(original)
                ET.SubElement(root.find("testcase"), status, message="synthetic failure")
                self.xml(self.unit, root)
                with self.assertRaisesRegex(ValueError, "Unsuccessful or skipped"):
                    gate.verify(False)

    def test_empty_wrong_root_and_entity_reports_are_rejected(self) -> None:
        for data in (b"<testsuite tests='0'/>", b"<unrelated><testcase name='fake'/></unrelated>",
                     b"<!DOCTYPE testsuite [<!ENTITY x 'fake'>]><testsuite><testcase name='&x;'/></testsuite>",
                     b"<testsuite><testcase/></testsuite>"):
            with self.subTest(data=data):
                self.write(self.unit, data)
                with self.assertRaises(ValueError):
                    gate.verify(False)

    def test_missing_unit_class_is_not_hidden_by_a_passing_report(self) -> None:
        self.write(self.root / "src/test/java/example/AnotherTest.java", b"package example; class AnotherTest { @TestFactory void cases() {} }")
        self.start_record()
        with self.assertRaisesRegex(ValueError, "JUnit discovery mismatch.*AnotherTest"):
            gate.verify(False)

    def test_comment_annotations_do_not_add_phantom_tests(self) -> None:
        self.write(self.root / "src/test/java/example/Helper.java", b'package example; /* @Test */ class Helper { String text = "@Test void phantom()"; }')
        self.write(self.server_source, self.server_source.read_bytes() + b' // @GameTest() public void phantom() {}')
        self.start_record()
        self.assertEqual({"example.RuleTest"}, gate.expected_unit_classes())
        self.assertEqual(2, len(gate.expected_server_ids()))
        self.assertEqual(3, gate.verify(False)["server_tests"])

    def test_repeated_parameterized_display_names_are_not_duplicate_ids(self) -> None:
        self.write(self.unit_source, b"package example; class RuleTest { @ParameterizedTest void first(String value) {} @ParameterizedTest void second(String value) {} }")
        root = ET.fromstring(self.unit.read_bytes())
        root.set("tests", "2")
        root.find("testcase").set("name", '[1] "gold"')
        ET.SubElement(root, "testcase", classname="example.RuleTest", name='[1] "gold"')
        self.xml(self.unit, root)
        self.unit_execution([("example.RuleTest", "first", '[1] "gold"'), ("example.RuleTest", "second", '[1] "gold"')])
        self.start_record()
        self.assertEqual(2, gate.verify(False)["unit_tests"])

    def test_a_passing_class_cannot_hide_an_omitted_test_method(self) -> None:
        self.write(self.unit_source, b"package example; class RuleTest { @Test void invariant() {} @Test void second() {} }")
        self.start_record()
        with self.assertRaisesRegex(ValueError, "JUnit method discovery mismatch.*second"):
            gate.verify(False)

    def test_extra_parameter_invocations_cannot_replace_a_missing_method(self) -> None:
        self.write(self.unit_source, b"package example; class RuleTest { @ParameterizedTest void first(String value) {} @Test void second() {} }")
        self.unit_report([("example.RuleTest", "first", "[1] gold"), ("example.RuleTest", "first", "[2] silver")])
        self.start_record()
        with self.assertRaisesRegex(ValueError, "JUnit method discovery mismatch.*second"):
            gate.verify(False)

    def test_nested_parameterized_and_dynamic_methods_keep_exact_source_identities(self) -> None:
        self.write(self.unit_source, b'''package example;
            class RuleTest {
                @org.junit.jupiter.api.Test void invariant() {}
                @Nested class Inner {
                    @ParameterizedTest(name="shared") @ValueSource(strings={"a"})
                    void variants(String value) {}
                    @TestFactory java.util.stream.Stream<DynamicTest> scenarios() { return null; }
                }
            }''')
        self.unit_report([("example.RuleTest", "invariant", "invariant()"),
                          ("example.RuleTest$Inner", "variants", "shared"),
                          ("example.RuleTest$Inner", "scenarios", "shared"),
                          ("example.RuleTest$Inner", "scenarios", "other dynamic child")])
        self.start_record()
        result = gate.verify(False)
        self.assertEqual((4, 3, 2), (result["unit_tests"], result["unit_test_methods"], result["unit_test_classes"]))

    def test_discovered_factory_with_no_executed_cases_is_rejected(self) -> None:
        self.write(self.unit_source, b"package example; class RuleTest { @Test void invariant() {} @TestFactory Stream<DynamicTest> empty() {} }")
        self.unit_execution([("example.RuleTest", "invariant", "invariant()")],
                            [("example.RuleTest", "invariant"), ("example.RuleTest", "empty")])
        self.start_record()
        with self.assertRaisesRegex(ValueError, "without successful test cases.*empty"):
            gate.verify(False)

    def test_unit_execution_metadata_must_be_complete_successful_and_unambiguous(self) -> None:
        original = self.execution.read_bytes()
        changes = (
            lambda r: r.update(complete=False),
            lambda r: r.update(pid=True),
            lambda r: r.update(started_at=int((self.started - 1) * 1000)),
            lambda r: r.update(finished_at=0),
            lambda r: r.update(problems=[{"status": "MISSING"}]),
            lambda r: r.update(methods=[]),
            lambda r: r.update(tests=[]),
            lambda r: r["methods"].append(dict(r["methods"][0])),
            lambda r: r["tests"].append(dict(r["tests"][0])),
            lambda r: r["tests"][0].update(status="SKIPPED"),
            lambda r: r["tests"][0].update(method_name="anotherMethod"),
            lambda r: r["tests"][0].update(parameter_types="int"),
        )
        for change in changes:
            with self.subTest(change=change):
                self.write(self.execution, original)
                self.mutate(self.execution, change)
                with self.assertRaises(ValueError):
                    gate.verify(False)
        self.write(self.execution, original)
        os.utime(self.execution, (self.started - .001, self.started - .001))
        with self.assertRaisesRegex(ValueError, "predates"):
            gate.verify(False)

    def test_xml_cannot_drop_or_rename_leaf_cases_while_execution_metadata_passes(self) -> None:
        self.write(self.unit_source, b"package example; class RuleTest { @ParameterizedTest void invariant(String value) {} }")
        self.unit_report([("example.RuleTest", "invariant", "[1] gold"), ("example.RuleTest", "invariant", "[2] silver")])
        self.start_record()
        original = self.unit.read_bytes()
        for mutation in ("remove", "rename"):
            with self.subTest(mutation=mutation):
                root = ET.fromstring(original)
                if mutation == "remove":
                    root.remove(root.findall("testcase")[1])
                    root.set("tests", "1")
                else:
                    root.find("testcase").set("name", "[1] fabricated")
                self.xml(self.unit, root)
                with self.assertRaisesRegex(ValueError, "XML and execution identities disagree"):
                    gate.verify(False)

    def test_server_missing_extra_and_duplicate_discovery_are_rejected(self) -> None:
        original = self.server.read_bytes()
        for mutation in ("missing", "extra", "duplicate", "external"):
            with self.subTest(mutation=mutation):
                root = ET.fromstring(original)
                if mutation == "missing":
                    root.remove(root.find("testcase"))
                else:
                    name = {"extra": "fabricated_backpacks_tests:undeclared", "duplicate": root.find("testcase").get("name"),
                            "external": "unrelated:unexpected"}[mutation]
                    ET.SubElement(root, "testcase", name=name)
                self.xml(self.server, root)
                with self.assertRaisesRegex(ValueError, "discovery mismatch|Duplicate server|Unexpected non-mod"):
                    gate.verify(False)

    def test_edited_added_and_removed_inputs_invalidate_the_snapshot(self) -> None:
        original = self.unit_source.read_bytes()
        added = self.root / "tools/new_build_hook.py"
        for change in ("edit", "add", "remove"):
            with self.subTest(change=change):
                if change == "edit":
                    self.write(self.unit_source, original + b"\n// changed source")
                elif change == "add":
                    self.write(added, b"print('changed build input')")
                else:
                    self.unit_source.unlink()
                with self.assertRaisesRegex(ValueError, "inputs changed"):
                    gate.verify(False)
                self.write(self.unit_source, original)
                added.unlink(missing_ok=True)

    def test_private_test_and_unsafe_entries_are_rejected(self) -> None:
        for name in ("com/example/gametest/Foo.class", "data/fabricated_backpacks_tests/fixture.json",
                     ".codex-local/private.txt", "tokens/secret.json", "AGENTS.md", ".env", "../escape", "C:/escape", "folder\\escape"):
            with self.subTest(name=name):
                buffer = io.BytesIO(self.clean_jar)
                with ZipFile(buffer, "a") as archive:
                    # ZipInfo's constructor normalizes Windows separators. Override the
                    # stored name to exercise an actual malformed foreign ZIP entry.
                    entry = ZipInfo("placeholder")
                    entry.filename = entry.orig_filename = name
                    archive.writestr(entry, b"not production content")
                self.write(self.jar, buffer.getvalue())
                with self.assertRaisesRegex(ValueError, "Private or test|Unsafe JAR"):
                    gate.verify(False)

    def test_duplicate_and_nested_test_entries_are_rejected(self) -> None:
        buffer = io.BytesIO(self.clean_jar)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with ZipFile(buffer, "a") as archive:
                archive.writestr("fabric.mod.json", json.dumps(self.metadata))
        self.write(self.jar, buffer.getvalue())
        with self.assertRaisesRegex(ValueError, "duplicate JAR"):
            gate.verify(False)
        buffer = io.BytesIO(self.clean_jar)
        with ZipFile(buffer, "a") as archive:
            archive.writestr("META-INF/jars/innocent.jar", zip_bytes({"gametest/Hidden.class": b"bad"}))
        self.write(self.jar, buffer.getvalue())
        with self.assertRaisesRegex(ValueError, "Private or test"):
            gate.verify(False)

    def test_unit_classes_nested_classes_sources_and_nonstandard_test_helpers_are_excluded(self) -> None:
        self.write(self.root / "src/test/java/example/Support.java", b"package example; class Support {}")
        self.start_record()
        for name in ("com/kadamitas/fabricatedbackpacks/browser/BrowserProtocolTest.class",
                     "com/kadamitas/fabricatedbackpacks/browser/BrowserProtocolTest$Nested.class",
                     "com/kadamitas/fabricatedbackpacks/browser/BrowserProtocolTest.java",
                     "com/example/ValidationTests.class", "example/Support.class", "example/Support$Inner.class"):
            with self.subTest(entry=name):
                buffer = io.BytesIO(self.clean_jar)
                with ZipFile(buffer, "a") as archive:
                    archive.writestr(name, b"synthetic prohibited unit content")
                self.write(self.jar, buffer.getvalue())
                with self.assertRaisesRegex(ValueError, "Private or test class"):
                    gate.verify(False)
        buffer = io.BytesIO(self.clean_jar)
        with ZipFile(buffer, "a") as archive:
            archive.writestr("META-INF/jars/other.jar", zip_bytes({"com/example/ParserTest.class": b"unit"}))
        self.write(self.jar, buffer.getvalue())
        with self.assertRaisesRegex(ValueError, "Private or test class"):
            gate.verify(False)

    def test_non_test_class_names_are_not_rejected_by_the_suffix_rule(self) -> None:
        buffer = io.BytesIO(self.clean_jar)
        with ZipFile(buffer, "a") as archive:
            archive.writestr("com/example/Contest.class", b"synthetic ordinary class")
            archive.writestr("com/example/TestResult.class", b"synthetic ordinary class")
        self.write(self.jar, buffer.getvalue())
        self.assertTrue(gate.verify(False)["passed"])

    def test_release_coordinates_and_energy_loading_are_checked(self) -> None:
        for field, value in (("version", "9.0"), ("id", "another_mod"), ("license", "unknown"), ("jars", [])):
            with self.subTest(field=field):
                with ZipFile(io.BytesIO(self.clean_jar)) as archive:
                    entries = {name: archive.read(name) for name in archive.namelist()}
                metadata = {**self.metadata, field: value}
                entries["fabric.mod.json"] = json.dumps(metadata).encode()
                self.write(self.jar, zip_bytes(entries))
                with self.assertRaises(ValueError):
                    gate.verify(False)

    def test_multiplayer_rejects_mixed_processes_runs_roles_and_profiles(self) -> None:
        mutations = (
            (self.output / "multiplayer.json", "guest_pid", 1201),
            (self.output / "multiplayer.json", "host_exit", 1),
            (self.output / "multiplayer.json", "host_exit", False),
            (self.multiplayer / "host-pass.json", "pid", 9999),
            (self.multiplayer / "guest-pass.json", "host_pid", 9999),
            (self.multiplayer / "guest-pass.json", "run_id", str(uuid.uuid4())),
            (self.multiplayer / "guest-pass.json", "role", "host"),
            (self.multiplayer / "host-pass.json", "phase", "ready"),
            (self.multiplayer / "ready.json", "port", 0),
            (self.multiplayer / "guest-pass.json", "guest_uuid", "fd9c2744-5f67-46aa-aebb-59ef1efb38a6"),
            (self.multiplayer / "host-pass.json", "stored_emeralds", 18),
            (self.multiplayer / "host-pass.json", "recorded_at", int((self.started - 1) * 1000)),
        )
        for path, key, value in mutations:
            with self.subTest(path=path.name, key=key, value=value):
                original = path.read_bytes()
                self.mutate(path, lambda document: document.update({key: value}))
                try:
                    with self.assertRaises(ValueError):
                        gate.verify(True)
                finally:
                    self.write(path, original)

    def test_multiplayer_evidence_cannot_point_to_another_directory(self) -> None:
        self.mutate(self.output / "multiplayer.json", lambda document: document.update(evidence_dir=str(self.client)))
        with self.assertRaisesRegex(ValueError, "exact run directory"):
            gate.verify(True)

    def test_restart_requires_the_tested_writer_and_a_distinct_reader(self) -> None:
        path = self.client / "restart-pass.json"
        original = path.read_bytes()
        for key, value in (("writer_pid", 9999), ("reader_pid", 1101), ("reader_pid", True), ("reader_pid", 0)):
            with self.subTest(key=key, value=value):
                self.write(path, original)
                self.mutate(path, lambda document: document.update({key: value}))
                with self.assertRaises(ValueError):
                    gate.verify(True)

    def test_manual_observations_are_for_the_exact_artifact(self) -> None:
        path = self.output / "manual.json"
        original = path.read_bytes()
        for key, value in (("artifact_sha256", "0" * 64), ("passed", "true"), ("observations", []),
                           ("observations", "not a list"), ("observations", [" "]), ("screenshots", [])):
            with self.subTest(key=key, value=value):
                self.write(path, original)
                self.mutate(path, lambda document: document.update({key: value}))
                with self.assertRaises(ValueError):
                    gate.verify(True)

    def test_absolute_and_traversing_screenshot_paths_are_rejected_on_all_platforms(self) -> None:
        for value in ("/tmp/screenshot.png", "C:/Windows/screenshot.png", r"C:\Windows\screenshot.png",
                      r"C:relative.png", r"\\server\share\screenshot.png", "../screenshot.png",
                      "build/../../screenshot.png", r"build\..\..\screenshot.png", "build//manual/image.png"):
            with self.subTest(path=value):
                self.mutate(self.output / "manual.json", lambda document: document.update(screenshots=[value]))
                with self.assertRaisesRegex(ValueError, "Unsafe screenshot"):
                    gate.verify(True)

    def test_relative_windows_screenshot_paths_are_contained_and_hashed(self) -> None:
        self.mutate(self.output / "manual.json", lambda document: document.update(screenshots=[r"build\manual\screenshot.png"]))
        report = gate.verify(True)
        self.assertEqual("build/manual/screenshot.png", report["client"]["manual"]["verified_screenshots"][0]["path"])

    def test_manual_screenshots_must_exist_be_fresh_and_be_images(self) -> None:
        for invalid in (b"", b"this is not a screenshot"):
            with self.subTest(invalid=invalid):
                self.write(self.manual_image, invalid)
                with self.assertRaises(ValueError):
                    gate.verify(True)
        self.write(self.manual_image, png())
        os.utime(self.manual_image, (self.started - 10, self.started - 10))
        with self.assertRaisesRegex(ValueError, "predates"):
            gate.verify(True)

    def test_screenshot_headers_truncation_and_corrupt_chunks_do_not_count_as_images(self) -> None:
        valid = png()
        header, ending = valid[:33], png_chunk(b"IEND", b"")
        bad_crc = bytearray(valid)
        bad_crc[29] ^= 1
        malformed = (
            valid[:33], header + ending, valid[:-1], valid[:-12],
            b"\xff\xd8\xff\xff\xd9", b"RIFF\x08\0\0\0WEBPVP8 ",
            bytes(bad_crc),
            header + png_chunk(b"IDAT", b"not deflate") + ending,
            valid + b"trailing bytes",
            header + header[8:] + valid[33:],
            header + png_chunk(b"ABCD", b"unknown critical chunk") + valid[33:],
        )
        for data in malformed:
            with self.subTest(length=len(data), tail=data[-12:]):
                self.write(self.manual_image, data)
                with self.assertRaisesRegex(ValueError, "PNG"):
                    gate.verify(True)

    def test_screenshot_inflation_scanline_filters_and_resource_limits_are_checked(self) -> None:
        header, ending = png()[:33], png_chunk(b"IEND", b"")
        for raw in (b"", b"\0" * 13, b"\0" * 15, b"\5" + b"\0" * 13, b"\0" * 1_000_000):
            with self.subTest(raw_length=len(raw)):
                data = header + png_chunk(b"IDAT", zlib.compress(raw)) + ending
                self.write(self.manual_image, data)
                with self.assertRaisesRegex(ValueError, "PNG"):
                    gate.verify(True)
        huge_header = b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 16_384, 16_384, 8, 6, 0, 0, 0))
        self.write(self.manual_image, huge_header + png_chunk(b"IDAT", zlib.compress(b"\0")) + ending)
        with self.assertRaisesRegex(ValueError, "decompression exceeds"):
            gate.verify(True)
        compressed = zlib.compress((b"\0" + b"\x55\x88\xaa" * 2) * 2)
        for payload in (compressed[:-1], compressed + b"extra stream", compressed + zlib.compress(b"\0")):
            with self.subTest(payload=payload):
                self.write(self.manual_image, header + png_chunk(b"IDAT", payload) + ending)
                with self.assertRaisesRegex(ValueError, "PNG"):
                    gate.verify(True)

    def test_complete_split_data_rgba_and_interlaced_pngs_are_accepted(self) -> None:
        signature, ending = b"\x89PNG\r\n\x1a\n", png_chunk(b"IEND", b"")
        rgba = (b"\0" + b"\x55\x88\xaa\xff" * 2) * 2
        compressed = zlib.compress(rgba)
        header = signature + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0))
        data = header + png_chunk(b"IDAT", compressed[:5]) + png_chunk(b"IDAT", compressed[5:]) + ending
        self.assertEqual((2, 2), gate.png_dimensions(data, "complete split RGBA fixture"))
        separated = header + png_chunk(b"IDAT", compressed[:5]) + png_chunk(b"tEXt", b"note\0fixture") + png_chunk(b"IDAT", compressed[5:]) + ending
        with self.assertRaisesRegex(ValueError, "Nonconsecutive"):
            gate.png_dimensions(separated, "invalid separated IDAT fixture")
        # A 2x2 RGB Adam7 image has one pixel in pass1, one in pass6 and two in pass7.
        interlaced = b"\0\x55\x88\xaa" * 2 + b"\0" + b"\x55\x88\xaa" * 2
        header = signature + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 1))
        self.assertEqual((2, 2), gate.png_dimensions(header + png_chunk(b"IDAT", zlib.compress(interlaced)) + ending,
                                                   "complete interlaced fixture"))

    def test_json_duplicates_nonfinite_numbers_and_nonobjects_fail_closed(self) -> None:
        for data in (b'{"passed": false, "passed": true}', b'{"started": NaN}', b'[]'):
            with self.subTest(data=data):
                self.write(self.output / "manual.json", data)
                with self.assertRaises(ValueError):
                    gate.verify(True)

    def test_cli_writes_atomic_receipts_only_for_success(self) -> None:
        code, output = self.check_cli(["check", "--release"])
        self.assertEqual(0, code, output)
        self.assertTrue(json.loads((self.output / "release.json").read_bytes())["passed"])
        self.write(self.jar, b"not a ZIP archive")
        code, output = self.check_cli(["check", "--release"])
        self.assertEqual(1, code)
        self.assertIn("Verification rejected", output)
        self.assertNotIn("Traceback", output)
        self.assertFalse((self.output / "release.json").exists())

    def test_cli_captures_runtime_and_filesystem_errors_without_a_stale_success(self) -> None:
        target = self.output / "automated.json"
        for failure in (RuntimeError("decoder failed"), PermissionError("cannot read evidence")):
            with self.subTest(error=type(failure).__name__):
                self.document(target, {"passed": True})
                with patch.object(gate, "audit_jar", side_effect=failure):
                    code, output = self.check_cli(["check"])
                self.assertEqual(1, code)
                self.assertNotIn("Traceback", output)
                self.assertFalse(target.exists())

    def test_begin_replaces_the_snapshot_and_invalidates_old_receipts(self) -> None:
        for name in ("automated.json", "release.json"):
            self.document(self.output / name, {"passed": True})
        code, output = self.check_cli(["begin"])
        self.assertEqual(0, code, output)
        record = json.loads((self.output / "start.json").read_bytes())
        self.assertNotEqual(self.run_id, record["run_id"])
        self.assertEqual(gate.inputs(), record["inputs"])
        self.assertFalse((self.output / "automated.json").exists())
        self.assertFalse((self.output / "release.json").exists())
        with self.assertRaisesRegex(ValueError, "predates"):
            gate.verify(False)


if __name__ == "__main__":
    unittest.main()
