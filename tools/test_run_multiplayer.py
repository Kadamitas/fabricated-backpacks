#!/usr/bin/env python3
"""Synthetic controller rejection tests; these do not run Minecraft or create release evidence."""

import json
from pathlib import Path
import tempfile
import unittest

import run_multiplayer as launcher


class LauncherTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.run_id = "963afbec-a66a-4c6a-b87e-a1ac8108af80"
        self.launch = self.root / ".codex-local/client-evidence" / f"multiplayer-launch-{self.run_id}"
        self.launch.mkdir(parents=True)
        commands = {}
        for role in ("host", "guest"):
            directory = self.launch / role
            directory.mkdir()
            command = {"directory": str(directory), "java": str(Path(__file__).resolve()), "username": role,
                       "stdout": str(self.launch / f"{role}.stdout"), "stderr": str(self.launch / f"{role}.stderr"),
                       "java_arguments": ["--username", role, f"-Dfabricated.backpacks.clientScenario=multiplayer_{role}",
                                          f"-Dfabricated.backpacks.multiplayerRunId={self.run_id}",
                                          "-Dfabricated.backpacks.multiplayerScope=full"]}
            for field, digest in (("argument_file", "argument_sha256"), ("probe_file", "probe_sha256"),
                                  ("configuration_file", "configuration_sha256")):
                path = self.launch / f"{role}-{field}"
                path.write_text("synthetic launcher-test fixture")
                command[field] = str(path)
                command[digest] = launcher.evidence.sha256(path)
            commands[role] = command
        self.manifest = {"schema": 2, "run_id": self.run_id, "scope": "full", "project": str(self.root),
                         "evidence": str(self.launch.parent / f"multiplayer-{self.run_id}"), "commands": commands}

    def validate(self, manifest=None):
        return launcher.validate_manifest(manifest or self.manifest, self.root, self.launch, self.run_id)

    def test_exact_manifest_is_accepted_without_launching(self):
        self.assertEqual(set(self.validate()), {"host", "guest"})

    def test_foreign_project_is_rejected(self):
        self.manifest["project"] = str(self.root.parent)
        with self.assertRaisesRegex(ValueError, "another project"):
            self.validate()

    def test_output_path_escape_is_rejected(self):
        self.manifest["commands"]["host"]["stdout"] = str(self.root / "escape.log")
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            self.validate()

    def test_changed_argument_file_is_rejected(self):
        Path(self.manifest["commands"]["host"]["argument_file"]).write_text("changed")
        with self.assertRaisesRegex(ValueError, "has changed"):
            self.validate()

    def test_same_profile_names_are_rejected(self):
        command = self.manifest["commands"]["guest"]
        command["username"] = "host"
        command["java_arguments"][1] = "host"
        with self.assertRaisesRegex(ValueError, "distinct usernames"):
            self.validate()

    def test_wrong_scenario_is_rejected(self):
        self.manifest["commands"]["host"]["java_arguments"][-1] = "-Dfabricated.backpacks.multiplayerScope=automation"
        with self.assertRaisesRegex(ValueError, "acceptance scenario"):
            self.validate()

    def test_changed_runtime_directory_is_rejected(self):
        directory = self.launch / "host"
        (self.launch / "classpath-inputs.json").write_text(json.dumps([{"path": str(directory), "directory": True, "files": []}]))
        launcher.validate_inputs(self.launch)
        (directory / "unexpected.class").write_bytes(b"fixture")
        with self.assertRaisesRegex(ValueError, "membership changed"):
            launcher.validate_inputs(self.launch)

    def test_replayed_phase_is_rejected(self):
        record = {"run_id": self.run_id, "phase": "ready", "scope": "full"}
        (self.launch / "ready.json").write_text(json.dumps(record))
        self.assertEqual(launcher.phase(self.launch, "ready", self.run_id), record)
        with self.assertRaisesRegex(ValueError, "Stale"):
            launcher.phase(self.launch, "ready", "09d273fb-c811-4a50-8e07-d43958c869bb")


if __name__ == "__main__":
    unittest.main()
