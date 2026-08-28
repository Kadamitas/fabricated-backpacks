#!/usr/bin/env python3
"""Synthetic temporary fixtures exercise the checker, never Minecraft or release evidence."""

from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import copy
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
import warnings
import xml.etree.ElementTree as ET
from zipfile import ZipFile

import verify_compatibility as gate
import verify_evidence as evidence


def zip_bytes(entries) -> bytes:
    output = io.BytesIO()
    with ZipFile(output, "w") as jar:
        for name, data in entries.items() if isinstance(entries, dict) else entries:
            jar.writestr(name, data)
    return output.getvalue()


def class_bytes(name: str, major: int = 65, minor: int = 0) -> bytes:
    """A minimal structurally valid JVM class, generated only inside synthetic fixtures."""
    def utf8(value: str) -> bytes:
        encoded = value.encode()
        return b"\x01" + struct.pack(">H", len(encoded)) + encoded

    pool = (utf8(name) + b"\x07\x00\x01" + utf8("java/lang/Object") + b"\x07\x00\x03"
            + utf8("<init>") + utf8("()V") + utf8("Code") + b"\x0c\x00\x05\x00\x06"
            + b"\x0a\x00\x04\x00\x08")
    code = struct.pack(">HHI", 1, 1, 5) + b"\x2a\xb7\x00\x09\xb1" + struct.pack(">HH", 0, 0)
    method = struct.pack(">HHHHHI", 1, 5, 6, 1, 7, len(code)) + code
    return (b"\xca\xfe\xba\xbe" + struct.pack(">HHH", minor, major, 10) + pool
            + struct.pack(">HHHHHH", 0x21, 2, 4, 0, 0, 1) + method + b"\x00\x00")


class CompatibilityGateTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.output = self.root / "build/verification"
        for module in (gate, evidence):
            replacement = patch.multiple(module, ROOT=self.root, OUTPUT=self.output)
            replacement.start()
            self.addCleanup(replacement.stop)
        self.started = time.time() - 10
        self.fresh = self.started + 1
        self.run_id = "5e10360b-0399-4a2e-962b-9d31b37456a1"
        for name in ("build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat", "LICENSE", ".gitattributes", ".gitignore"):
            self.write(self.root / name, b"Synthetic checker fixture only\n")
        self.unit_source = self.root / "src/test/java/example/RuleTest.java"
        self.write(self.unit_source, b"package example; public class RuleTest { @Test public void rule() {} }")
        self.server_source = self.root / gate.SERVER_SOURCE / "BackpackGameTests.java"
        self.optional_source = self.root / gate.SERVER_SOURCE / "CobblemonCompatibilityGameTests.java"
        self.write(self.server_source, self.server_class("BackpackGameTests", ["nativeRoundTrip", "nativeRollback"]))
        self.write(self.optional_source, self.server_class("CobblemonCompatibilityGameTests", ["realItems", "realCoexistence"]))
        self.unit = self.root / "build/test-results/test/TEST-example.RuleTest.xml"
        self.execution = self.root / "build/test-results/test/unit-execution.json"
        self.server = self.root / "build/gametest-results.xml"
        self.runtime = self.output / "compatibility-runtime.json"
        self.start = self.output / "compatibility-start.json"
        self.jar = self.root / "build/libs" / gate.ARTIFACT_NAME
        self.main_class = gate.CLASS_PREFIX + "FabricatedBackpacks"
        self.client_class = gate.CLASS_PREFIX + "client/FabricatedBackpacksClient"
        self.energy_class = "team/reborn/energy/impl/EnergyImpl"
        self.metadata = {
            "schemaVersion": 1, "id": "fabricated_backpacks", "version": gate.MOD_VERSION, "license": "MIT",
            "depends": {"minecraft": gate.MINECRAFT_VERSION, "java": ">=21", "team_reborn_energy": ">=4.1.0",
                        "fabric-api": ">=" + gate.FABRIC_API_VERSION},
            "entrypoints": {"main": [self.main_class.replace("/", ".")], "client": [self.client_class.replace("/", ".")]},
            "jars": [{"file": gate.ENERGY_JAR}],
        }
        self.unit_reports([("rule", "rule()")])
        self.write(self.jar, zip_bytes(self.jar_entries()))
        self.complete(False)

    @staticmethod
    def server_class(name: str, methods: list[str]) -> bytes:
        declarations = " ".join('@GameTest(template="fabricated_backpacks_tests:platform", timeoutTicks=100) '
                                + f"public void {method}(GameTestHelper helper) {{}}" for method in methods)
        return ("package com.kadamitas.fabricatedbackpacks.gametest; import net.minecraft.gametest.framework.GameTest; "
                + f"public class {name} {{ {declarations} }}").encode()

    def write(self, path: Path, data: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        os.utime(path, (self.fresh, self.fresh))

    def document(self, path: Path, data: dict) -> None:
        self.write(path, json.dumps(data).encode())

    def xml(self, path: Path, data: ET.Element) -> None:
        self.write(path, ET.tostring(data))

    def timestamp(self) -> str:
        return datetime.fromtimestamp(self.fresh, timezone.utc).isoformat()

    def unit_reports(self, methods: list[tuple[str, str]]) -> None:
        root = ET.Element("testsuite", tests=str(len(methods)), failures="0", skipped="0", timestamp=self.timestamp())
        for method, display in methods:
            ET.SubElement(root, "testcase", classname="example.RuleTest", name=display)
        self.xml(self.unit, root)
        self.document(self.execution, {
            "schema": 1, "complete": True, "pid": 1101,
            "started_at": int(self.fresh * 1000), "finished_at": int(self.fresh * 1000), "problems": [],
            "methods": [{"class_name": "example.RuleTest", "method_name": method, "parameter_types": ""}
                        for method in dict.fromkeys(method for method, display in methods)],
            "tests": [{"class_name": "example.RuleTest", "method_name": method, "parameter_types": "",
                       "id": f"[synthetic-checker-fixture:{index}]", "display_name": display, "legacy_name": display, "status": "SUCCESSFUL"}
                      for index, (method, display) in enumerate(methods)],
        })

    def server_report(self, names: list[str]) -> None:
        outer = ET.Element("testsuite")
        suite = ET.SubElement(outer, "testsuite", timestamp=self.timestamp())
        for name in names:
            ET.SubElement(suite, "testcase", classname="fabricated_backpacks_tests:platform", name=name)
        self.xml(self.server, outer)

    def complete(self, with_cobblemon: bool) -> None:
        versions = {"fabricated_backpacks": gate.MOD_VERSION, "fabric-api": gate.FABRIC_API_VERSION,
                    "team_reborn_energy": gate.ENERGY_VERSION, "fabricloader": "0.19.3"}
        if with_cobblemon:
            versions["cobblemon"] = gate.COBBLEMON_VERSION
        self.document(self.runtime, {"schema": 1, "recorded_at": int(self.fresh * 1000), "pid": 2201,
                                   "minecraft_version": gate.MINECRAFT_VERSION, "java_feature": 21, "environment": "SERVER",
                                   "mod_versions": versions, "registered_items": sorted(gate.COBBLEMON_ITEMS) if with_cobblemon else []})
        self.server_report(sorted(gate.expected_server_ids(with_cobblemon)))
        self.start_record(with_cobblemon)

    def start_record(self, with_cobblemon: bool = False) -> None:
        self.document(self.start, {"schema": 1, "scope": gate.SCOPE, "minecraft_version": gate.MINECRAFT_VERSION,
                                 "with_cobblemon": with_cobblemon, "run_id": self.run_id, "started": self.started,
                                 "inputs": evidence.inputs()})

    def jar_entries(self) -> dict[str, bytes]:
        energy = {"fabric.mod.json": json.dumps({"id": "team_reborn_energy", "version": gate.ENERGY_VERSION}).encode(),
                  self.energy_class + ".class": class_bytes(self.energy_class)}
        return {"fabric.mod.json": json.dumps(self.metadata).encode(),
                self.main_class + ".class": class_bytes(self.main_class),
                self.client_class + ".class": class_bytes(self.client_class),
                "assets/": b"", "assets/fabricated_backpacks/": b"",
                gate.ENERGY_JAR: zip_bytes(energy)}

    def check_cli(self, args: list[str]) -> tuple[int, str]:
        output = io.StringIO()
        with redirect_stdout(output), redirect_stderr(output):
            code = gate.main(args)
        return code, output.getvalue()

    def reject_json(self, path: Path, update, with_cobblemon: bool = False) -> None:
        original = path.read_bytes()
        value = json.loads(original)
        update(value)
        self.document(path, value)
        try:
            with self.assertRaises(ValueError):
                gate.verify(with_cobblemon)
        finally:
            self.write(path, original)

    def test_complete_fixtures_have_only_target_specific_compatibility_scope(self) -> None:
        for enabled in (False, True):
            with self.subTest(with_cobblemon=enabled):
                self.complete(enabled)
                result = gate.verify(enabled)
                self.assertEqual(gate.SCOPE, result["scope"])
                self.assertEqual((1, 4 if enabled else 2, 2 if enabled else 0),
                                 (result["unit_tests"], result["server_tests"], result["cobblemon_tests"]))
                self.assertEqual(65, result["artifact"]["maximum_class_major"])
                self.assertEqual(evidence.sha256(self.jar), result["artifact"]["sha256"])
                self.assertNotIn("client", result)
                self.assertNotIn("release", result)

    def test_begin_records_target_flag_uuid_and_inputs_without_touching_original_receipts(self) -> None:
        originals = {name: b"original full-release gate remains separate" for name in ("start.json", "automated.json", "release.json")}
        for name, data in originals.items():
            self.write(self.output / name, data)
        self.write(self.output / "compatibility.json", b"stale compatibility success")
        code, output = self.check_cli(["begin", "--with-cobblemon"])
        self.assertEqual(0, code, output)
        record = json.loads(self.start.read_bytes())
        self.assertEqual((gate.MINECRAFT_VERSION, gate.SCOPE, True), (record["minecraft_version"], record["scope"], record["with_cobblemon"]))
        self.assertEqual(evidence.inputs(), record["inputs"])
        self.assertNotEqual(self.run_id, record["run_id"])
        evidence.canonical_uuid(record["run_id"], "synthetic begin")
        self.assertFalse((self.output / "compatibility.json").exists())
        for name, data in originals.items():
            self.assertEqual(data, (self.output / name).read_bytes())

    def test_release_is_rejected_even_with_fabricated_client_receipts(self) -> None:
        for name in ("full-pass.json", "restart-pass.json", "manual.json", "multiplayer.json"):
            self.document(self.root / ".codex-local/client-evidence" / name, {"passed": True, "synthetic_fixture": True})
        self.document(self.output / "compatibility.json", {"passed": True})
        code, output = self.check_cli(["check", "--release"])
        self.assertEqual(1, code)
        self.assertIn("client harness", output)
        self.assertFalse((self.output / "compatibility.json").exists())
        self.assertFalse((self.output / "release.json").exists())
        self.assertEqual(1, self.check_cli(["begin", "--release"])[0])

    def test_old_full_gate_defaults_still_reject_the_new_target_jar(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unexpected release coordinates"):
            evidence.audit_jar(self.jar, self.started)

    def test_flag_drift_in_both_directions_is_rejected(self) -> None:
        for recorded in (False, True):
            self.complete(recorded)
            with self.assertRaisesRegex(ValueError, "changed after begin"):
                gate.verify(not recorded)

    def test_missing_required_evidence_is_rejected_and_old_success_is_removed(self) -> None:
        for path in (self.start, self.unit, self.execution, self.server, self.runtime, self.jar):
            with self.subTest(path=path.name):
                original = path.read_bytes()
                path.unlink()
                self.document(self.output / "compatibility.json", {"passed": True})
                try:
                    self.assertEqual(1, self.check_cli(["check"])[0])
                    self.assertFalse((self.output / "compatibility.json").exists())
                finally:
                    self.write(path, original)

    def test_stale_file_mtimes_are_rejected(self) -> None:
        for path in (self.start, self.unit, self.execution, self.server, self.runtime, self.jar):
            with self.subTest(path=path.name):
                os.utime(path, (self.started - .001, self.started - .001))
                try:
                    with self.assertRaisesRegex(ValueError, "predates"):
                        gate.verify(False)
                finally:
                    os.utime(path, (self.fresh, self.fresh))

    def test_touched_old_runtime_and_unit_receipts_remain_invalid(self) -> None:
        self.reject_json(self.runtime, lambda value: value.update(recorded_at=int((self.started - 1) * 1000)))
        self.reject_json(self.runtime, lambda value: value.update(recorded_at=int((time.time() + 60) * 1000)))
        self.reject_json(self.execution, lambda value: value.update(started_at=int((self.started - 1) * 1000)))

    def test_touched_old_native_and_junit_execution_timestamps_remain_invalid(self) -> None:
        for path in (self.unit, self.server):
            with self.subTest(path=path.name):
                original = path.read_bytes()
                report = ET.fromstring(original)
                for suite in report.iter("testsuite"):
                    suite.set("timestamp", datetime.fromtimestamp(self.started - 1, timezone.utc).isoformat())
                self.xml(path, report)
                try:
                    with self.assertRaisesRegex(ValueError, "predates"):
                        gate.verify(False)
                finally:
                    self.write(path, original)

    def test_invalid_start_target_scope_time_uuid_and_flag_types_are_rejected(self) -> None:
        for change in ({"minecraft_version": "26.2"}, {"scope": "release"}, {"schema": True},
                       {"with_cobblemon": 0}, {"started": True}, {"started": time.time() + 100},
                       {"run_id": "not-a-uuid"}):
            with self.subTest(change=change):
                self.reject_json(self.start, lambda value: value.update(change))

    def test_changed_hashed_inputs_are_rejected(self) -> None:
        self.write(self.root / "gradle.properties", b"minecraft_version=26.2\n")
        with self.assertRaisesRegex(ValueError, "inputs changed"):
            gate.verify(False)

    def test_strict_duplicate_json_and_nonfinite_numbers_are_rejected(self) -> None:
        for path, invalid in ((self.start, b'{"schema":1,"schema":1}'),
                              (self.runtime, b'{"schema":1,"recorded_at":NaN}'),
                              (self.runtime, b'{"mod_versions":{"cobblemon":"x","cobblemon":"y"}}')):
            with self.subTest(path=path.name, invalid=invalid):
                original = path.read_bytes()
                self.write(path, invalid)
                try:
                    with self.assertRaises(ValueError):
                        gate.verify(False)
                finally:
                    self.write(path, original)

    def test_failed_error_skipped_disabled_and_empty_reports_are_rejected(self) -> None:
        for path in (self.unit, self.server):
            original = path.read_bytes()
            for marker in ("failure", "error", "skipped", "disabled", "empty"):
                with self.subTest(path=path.name, marker=marker):
                    report = ET.fromstring(original)
                    if marker == "empty":
                        report = ET.Element("testsuite")
                    elif marker == "disabled":
                        report.set("disabled", "1")
                    else:
                        ET.SubElement(next(report.iter("testcase")), marker)
                    self.xml(path, report)
                    try:
                        with self.assertRaises(ValueError):
                            gate.verify(False)
                    finally:
                        self.write(path, original)

    def test_unit_discovery_is_method_exact_and_not_a_hardcoded_count(self) -> None:
        self.write(self.unit_source, b"package example; public class RuleTest { @Test void rule() {} @Test void second() {} }")
        self.unit_reports([("rule", "rule()"), ("second", "second()")])
        self.start_record()
        self.assertEqual(2, gate.verify(False)["unit_tests"])
        self.unit_reports([("rule", "rule()")])
        with self.assertRaisesRegex(ValueError, "method discovery"):
            gate.verify(False)

    def test_unit_xml_must_match_listener_leaf_identities(self) -> None:
        self.reject_json(self.execution, lambda value: value["tests"][0].update(display_name="omitted()", legacy_name="omitted()"))
        self.reject_json(self.execution, lambda value: value["tests"].append(copy.deepcopy(value["tests"][0])))
        self.reject_json(self.execution, lambda value: value.update(complete=False))

    def test_server_discovery_is_exact_with_no_silent_vanilla_or_namespace_extras(self) -> None:
        names = sorted(gate.expected_server_ids(False))
        alternatives = [names[:-1], names + [names[0]], names + ["minecraft:always_pass"],
                        ["fabricated_backpacks_tests:" + name for name in names], [name.upper() for name in names],
                        names + ["othermod.syntheticpass"]]
        for values in alternatives:
            with self.subTest(names=values):
                self.server_report(values)
                with self.assertRaises(ValueError):
                    gate.verify(False)

    def test_optional_tests_are_mandatory_only_when_enabled(self) -> None:
        self.complete(True)
        self.server_report(sorted(gate.expected_server_ids(False)))
        with self.assertRaisesRegex(ValueError, "Native server discovery mismatch"):
            gate.verify(True)
        self.complete(False)
        self.server_report(sorted(gate.expected_server_ids(True)))
        with self.assertRaisesRegex(ValueError, "Native server discovery mismatch"):
            gate.verify(False)

    def test_exactly_two_optional_declarations_are_required(self) -> None:
        for methods in (["one"], ["one", "two", "three"]):
            self.write(self.optional_source, self.server_class("CobblemonCompatibilityGameTests", methods))
            with self.assertRaisesRegex(ValueError, "Exactly two"):
                gate.expected_server_ids(True)

    def test_old_or_duplicate_native_declarations_cannot_hide_tests(self) -> None:
        self.write(self.server_source, self.server_class("BackpackGameTests", ["same", "Same"]))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            gate.expected_server_ids(False)
        self.write(self.server_source, self.server_class("BackpackGameTests", ["old"]).replace(
                b"net.minecraft.gametest.framework.GameTest", b"net.fabricmc.fabric.api.gametest.v1.GameTest"))
        with self.assertRaisesRegex(ValueError, "26.2"):
            gate.expected_server_ids(False)

    def test_runtime_target_java_environment_pid_and_required_mods_are_exact(self) -> None:
        for change in ({"schema": True}, {"minecraft_version": "26.2"}, {"java_feature": 17}, {"java_feature": 25},
                       {"java_feature": True}, {"environment": "CLIENT"}, {"pid": 0}, {"pid": True}):
            with self.subTest(change=change):
                self.reject_json(self.runtime, lambda value: value.update(change))
        for name in ("fabricated_backpacks", "team_reborn_energy", "fabric-api", "fabricloader"):
            with self.subTest(mod=name):
                self.reject_json(self.runtime, lambda value: value["mod_versions"].pop(name))
        for name, wrong in (("fabricated_backpacks", "0.5.0-alpha"), ("team_reborn_energy", "5.0.0"), ("fabric-api", "0.116.6+1.21.1")):
            with self.subTest(mod=name):
                self.reject_json(self.runtime, lambda value: value["mod_versions"].update({name: wrong}))

    def test_cobblemon_runtime_and_all_three_real_registry_items_are_required(self) -> None:
        self.complete(True)
        self.reject_json(self.runtime, lambda value: value["mod_versions"].pop("cobblemon"), True)
        self.reject_json(self.runtime, lambda value: value["mod_versions"].update(cobblemon="1.7.2+1.21.1"), True)
        for items in ([], ["cobblemon:poke_ball"], sorted(gate.COBBLEMON_ITEMS) + ["cobblemon:poke_ball"],
                      sorted(gate.COBBLEMON_ITEMS) + ["cobblemon:unverified"], [False]):
            with self.subTest(items=items):
                self.reject_json(self.runtime, lambda value: value.update(registered_items=items), True)

    def test_disabled_option_rejects_loaded_cobblemon_or_its_registered_items(self) -> None:
        self.reject_json(self.runtime, lambda value: value["mod_versions"].update(cobblemon=gate.COBBLEMON_VERSION))
        self.reject_json(self.runtime, lambda value: value.update(registered_items=sorted(gate.COBBLEMON_ITEMS)))

    def test_artifact_target_coordinates_java_and_api_metadata_are_checked(self) -> None:
        for change in ({"version": "0.5.0-alpha"}, {"id": "cobblemon"}, {"license": "other"},
                       {"depends": {**self.metadata["depends"], "minecraft": "26.2"}},
                       {"depends": {**self.metadata["depends"], "java": ">=17"}},
                       {"depends": {**self.metadata["depends"], "team_reborn_energy": ">=5.0.0"}}):
            with self.subTest(change=change):
                entries = self.jar_entries()
                entries["fabric.mod.json"] = json.dumps({**self.metadata, **change}).encode()
                self.write(self.jar, zip_bytes(entries))
                with self.assertRaises(ValueError):
                    gate.verify(False)

    def test_production_class_headers_structure_identity_and_java_limits_are_checked(self) -> None:
        valid = class_bytes(self.main_class)
        for invalid in (b"not a class", b"\xca\xfe\xba\xbe", valid[:-1], valid + b"trailing",
                        class_bytes(self.main_class, 66), class_bytes(self.main_class, 65, 65535),
                        class_bytes(self.main_class + "Wrong"), valid[:8] + b"\x00\x00" + valid[10:]):
            with self.subTest(size=len(invalid), header=invalid[:10]):
                entries = self.jar_entries()
                entries[self.main_class + ".class"] = invalid
                self.write(self.jar, zip_bytes(entries))
                with self.assertRaises(ValueError):
                    gate.verify(False)

    def test_missing_or_test_entrypoints_are_rejected(self) -> None:
        for entries in ({"main": []}, {"main": ["example.Missing"], "client": [self.client_class.replace("/", ".")]},
                        {**self.metadata["entrypoints"], "fabric-gametest": [self.main_class.replace("/", ".")]}):
            jar = self.jar_entries()
            jar["fabric.mod.json"] = json.dumps({**self.metadata, "entrypoints": entries}).encode()
            self.write(self.jar, zip_bytes(jar))
            with self.assertRaises(ValueError):
                gate.verify(False)

    def test_private_test_and_shaded_library_content_is_rejected(self) -> None:
        for path in (".codex-local/private.txt", "src/test/hidden.txt", "com/example/BrowserProtocolTest.class",
                     gate.CLASS_PREFIX + "gametest/CobblemonCompatibilityGameTests.class", "kotlin/Hidden.class",
                     "com/cobblemon/Hidden.class", "mezz/jei/Hidden.class", "assets/cobblemon/item.png", "../escaped.txt"):
            with self.subTest(path=path):
                entries = self.jar_entries()
                entries[path] = class_bytes(path.removesuffix(".class")) if path.endswith(".class") else b"synthetic forbidden content"
                self.write(self.jar, zip_bytes(entries))
                with self.assertRaises(ValueError):
                    gate.verify(False)

    def test_only_exact_declared_energy_is_allowed_as_a_nested_library(self) -> None:
        for path in ("META-INF/jars/cobblemon.jar", "META-INF/jars/jei.jar", "META-INF/jars/extra.zip"):
            entries = self.jar_entries()
            entries[path] = zip_bytes({"fabric.mod.json": b'{"id":"extra"}'})
            self.write(self.jar, zip_bytes(entries))
            with self.assertRaisesRegex(ValueError, "Only the exact Energy"):
                gate.verify(False)
        for declarations in ([], [{"file": gate.ENERGY_JAR}, {"file": gate.ENERGY_JAR}], [{"file": "META-INF/jars/energy-5.0.0.jar"}]):
            entries = self.jar_entries()
            entries["fabric.mod.json"] = json.dumps({**self.metadata, "jars": declarations}).encode()
            self.write(self.jar, zip_bytes(entries))
            with self.assertRaisesRegex(ValueError, "declared exactly once"):
                gate.verify(False)

    def test_nested_energy_metadata_classes_and_hidden_libraries_are_checked(self) -> None:
        for changes in ({"fabric.mod.json": b'{"id":"cobblemon","version":"4.1.0"}'},
                        {"fabric.mod.json": b'{"id":"team_reborn_energy","version":"5.0.0"}'},
                        {"META-INF/jars/hidden.jar": zip_bytes({"hidden.txt": b"fixture"})},
                        {"com/cobblemon/Hidden.class": class_bytes("com/cobblemon/Hidden")},
                        {self.energy_class + ".class": class_bytes(self.energy_class, 66)}):
            entries = self.jar_entries()
            with ZipFile(io.BytesIO(entries[gate.ENERGY_JAR])) as nested:
                energy = {name: nested.read(name) for name in nested.namelist()}
            energy.update(changes)
            entries[gate.ENERGY_JAR] = zip_bytes(energy)
            self.write(self.jar, zip_bytes(entries))
            with self.assertRaises(ValueError):
                gate.verify(False)

    def test_corrupt_and_duplicate_archives_fail_closed(self) -> None:
        entries = self.jar_entries()
        clean = zip_bytes(entries)
        corrupted = bytearray(clean)
        corrupted[clean.index(b"\xca\xfe\xba\xbe")] ^= 1
        for invalid in (b"not a ZIP", clean[:-12], bytes(corrupted)):
            self.write(self.jar, invalid)
            self.assertEqual(1, self.check_cli(["check"])[0])
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            self.write(self.jar, zip_bytes(list(entries.items()) + [("fabric.mod.json", entries["fabric.mod.json"])]))
        with self.assertRaisesRegex(ValueError, "duplicate"):
            gate.verify(False)

    def test_success_writes_only_compatibility_receipt(self) -> None:
        code, output = self.check_cli(["check"])
        self.assertEqual(0, code, output)
        self.assertEqual(gate.SCOPE, json.loads((self.output / "compatibility.json").read_bytes())["scope"])
        self.assertFalse((self.output / "automated.json").exists())
        self.assertFalse((self.output / "release.json").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
