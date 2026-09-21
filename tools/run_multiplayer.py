#!/usr/bin/env python3
"""Run the existing full two-client acceptance scenario on macOS/Linux.

Uses Gradle's exact launch manifests and real Java subprocesses, never mocked
clients. Preparation alone cannot create a passing multiplayer receipt.
The focused automation launcher remains tools/run-multiplayer.ps1.
"""

from __future__ import annotations

import argparse
from contextlib import ExitStack
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

import verify_evidence as evidence

ROOT = Path(__file__).resolve().parent.parent


def write_new(path: Path, value: dict) -> None:
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def read_object(path: Path) -> dict:
    evidence.require(path.stat().st_size < 65536, f"Oversized launch record: {path}")
    return evidence.object_json(path.read_bytes(), str(path))


def validate_manifest(manifest: dict, root: Path, launch: Path, run_id: str) -> dict:
    expected = root / ".codex-local/client-evidence" / f"multiplayer-{run_id}"
    evidence.require(manifest.get("schema") == 2 and manifest.get("run_id") == run_id
                     and manifest.get("scope") == "full"
                     and Path(manifest["project"]).resolve() == root
                     and Path(manifest["evidence"]).resolve() == expected,
                     "Prepared launch belongs to another project, run or scope")
    commands = manifest["commands"]
    evidence.require(set(commands) == {"host", "guest"}, "Expected exactly two client commands")
    for role, command in commands.items():
        for field in ("directory", "argument_file", "probe_file", "configuration_file", "stdout", "stderr"):
            path = Path(command[field])
            evidence.require(path.is_absolute() and path.resolve().is_relative_to(launch)
                             and not path.is_symlink(), f"Unsafe {role} launch path: {field}")
        evidence.require(Path(command["directory"]).resolve() == launch / role,
                         f"Unexpected {role} client directory")
        evidence.require(Path(command["java"]).is_file(), f"Missing {role} Java executable")
        for field, digest in (("argument_file", "argument_sha256"), ("probe_file", "probe_sha256"),
                              ("configuration_file", "configuration_sha256")):
            evidence.require(evidence.sha256(Path(command[field])) == command[digest],
                             f"Prepared {role} {field} has changed")
        tokens = command["java_arguments"]
        evidence.require(tokens.count("--username") == 1
                         and tokens[tokens.index("--username") + 1] == command["username"],
                         f"Unexpected {role} username")
        for property_value in (f"clientScenario=multiplayer_{role}", f"multiplayerRunId={run_id}", "multiplayerScope=full"):
            evidence.require(f"-Dfabricated.backpacks.{property_value}" in tokens,
                             f"Prepared {role} does not select this acceptance scenario")
    evidence.require(commands["host"]["username"] != commands["guest"]["username"],
                     "Two clients must have distinct usernames")
    return commands


def validate_inputs(launch: Path) -> None:
    for item in json.loads((launch / "classpath-inputs.json").read_bytes()):
        path = Path(item["path"])
        if item.get("directory"):
            evidence.require(path.is_dir() and {str(member) for member in path.rglob("*") if member.is_file()}
                             == set(item["files"]), f"Runtime directory membership changed: {path}")
        elif item.get("missing"):
            evidence.require(not path.exists(), f"Previously absent runtime input appeared: {path}")
        else:
            evidence.require(path.is_file() and evidence.sha256(path) == item["sha256"],
                             f"Runtime input changed: {path}")


def phase(directory: Path, name: str, run_id: str) -> dict | None:
    path = directory / f"{name}.json"
    if not path.exists():
        return None
    record = read_object(path)
    evidence.require(record.get("phase") == name and record.get("run_id") == run_id
                     and record.get("scope") == "full", f"Stale or mismatched phase: {name}")
    return record


def require_passes(directory: Path, run_id: str, ready: dict, processes: dict) -> None:
    host, guest = processes["host"], processes["guest"]
    evidence.require(host.pid != guest.pid and host.returncode == 0 and guest.returncode == 0,
                     "Both distinct client JVMs must exit successfully")
    host_pass = phase(directory, "host-pass", run_id)
    guest_pass = phase(directory, "guest-pass", run_id)
    evidence.require(host_pass is not None and guest_pass is not None, "Both real client pass reports are required")
    evidence.require(host_pass.get("pid") == host.pid and host_pass.get("guest_pid") == guest.pid
                     and guest_pass.get("pid") == guest.pid and guest_pass.get("host_pid") == host.pid
                     and host_pass.get("role") == "host" and guest_pass.get("role") == "guest",
                     "Pass reports do not identify the two launched JVMs")
    evidence.require(evidence.canonical_uuid(ready.get("host_uuid"), "host")
                     != evidence.canonical_uuid(guest_pass.get("guest_uuid"), "guest"),
                     "Two clients must have different profiles")
    evidence.require(host_pass.get("stored_emeralds") == 19
                     and all(isinstance(record.get("channels"), str) and record["channels"].strip()
                             for record in (host_pass, guest_pass)), "Inventory/audio assertions are incomplete")
    for name in ("host-sees-shared-19.png", "guest-inserts-19.png", "guest-sharing-revoked.png"):
        evidence.png_dimensions((directory / name).read_bytes(), name)


def run(args: argparse.Namespace) -> None:
    root = ROOT.resolve()
    run_id = evidence.canonical_uuid(args.run_id, "launcher")
    client = root / ".codex-local/client-evidence"
    launch = client / f"multiplayer-launch-{run_id}"
    directory = client / f"multiplayer-{run_id}"
    output = root / "build/verification"
    gate = output / "multiplayer.json"
    output.mkdir(parents=True, exist_ok=True)
    if not args.prepare_only:
        gate.unlink(missing_ok=True)
    processes: dict[str, subprocess.Popen] = {}
    try:
        if not args.skip_prepare:
            evidence.require(not launch.exists() and not directory.exists(), "Run ID already exists; choose a fresh UUID")
            client.mkdir(parents=True, exist_ok=True)
            with (client / f"multiplayer-prepare-{run_id}.log").open("x") as log:
                subprocess.run([str(root / "gradlew"), "--no-daemon", "--max-workers=2", "--console=plain",
                                "prepareMultiplayerClients", f"-PmultiplayerRunId={run_id}", "-PmultiplayerScenario=full"],
                               cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
        commands = validate_manifest(read_object(launch / "launch.json"), root, launch, run_id)
        validate_inputs(launch)
        probe_id = str(uuid.uuid4())
        probes = {"run_id": run_id, "scope": "full", "probe_id": probe_id}
        for role, command in commands.items():
            with (launch / f"{role}-java-probe-{probe_id}.log").open("x") as log:
                result = subprocess.run([command["java"], "@" + command["probe_file"]], cwd=command["directory"],
                                        stdout=log, stderr=subprocess.STDOUT, timeout=60, check=True)
            probes[role] = {"exit_code": result.returncode, "argument_sha256": command["argument_sha256"]}
        write_new(launch / f"java-probes-{probe_id}.json", probes)
        if args.prepare_only:
            print(f"Prepared and argument-file probes verified; no Minecraft client launched: {launch / 'launch.json'}")
            return
        evidence.require(not directory.exists(), "This actual acceptance run cannot be replayed")
        with ExitStack() as files:
            def start(role: str) -> subprocess.Popen:
                command = commands[role]
                record = launch / f"{role}-process.json"
                evidence.require(not record.exists(), f"The {role} command was already launched")
                process = subprocess.Popen([command["java"], "@" + command["argument_file"]], cwd=command["directory"],
                                           env=os.environ | command["environment"],
                                           stdout=files.enter_context(Path(command["stdout"]).open("x")),
                                           stderr=files.enter_context(Path(command["stderr"]).open("x")))
                processes[role] = process
                write_new(record, {"run_id": run_id, "scope": "full", "role": role, "pid": process.pid,
                                   "started_at": datetime.now(timezone.utc).isoformat(), "java": command["java"],
                                   "argument_file": command["argument_file"], "directory": command["directory"]})
                return process

            deadline = time.monotonic() + args.timeout_seconds

            def check_running() -> None:
                for role, process in processes.items():
                    failure = phase(directory, f"{role}-failure", run_id)
                    evidence.require(failure is None, f"{role} acceptance failed: {failure}")
                    evidence.require(process.poll() in (None, 0), f"{role} JVM exited with {process.returncode}")
                evidence.require(time.monotonic() < deadline, "Timed out waiting for real client acceptance")

            host = start("host")
            ready = None
            while ready is None:
                check_running()
                evidence.require(host.poll() is None, "Host exited before publishing its TCP listener")
                ready = phase(directory, "ready", run_id)
                if ready is None:
                    time.sleep(0.2)
            evidence.require(ready.get("pid") == host.pid and ready.get("role") == "host"
                             and type(ready.get("port")) is int and 1 <= ready["port"] <= 65535,
                             "Readiness record does not identify the launched host listener")
            start("guest")
            while any(process.poll() is None for process in processes.values()):
                check_running()
                time.sleep(0.2)
            check_running()
            require_passes(directory, run_id, ready, processes)
            result = {"run_id": run_id, "scope": "full", "host_pid": host.pid, "guest_pid": processes["guest"].pid,
                      "host_exit": host.returncode, "guest_exit": processes["guest"].returncode,
                      "evidence_dir": str(directory), "passed": True, "completed_at": datetime.now(timezone.utc).isoformat(),
                      "launch_manifest": str(launch / "launch.json")}
            write_new(launch / "multiplayer-result.json", result)
            temporary_gate = output / f".multiplayer-{run_id}.tmp"
            write_new(temporary_gate, result)
            temporary_gate.replace(gate)
            print(f"MULTIPLAYER_PASS scope=full {directory}")
    except BaseException as failure:
        if launch.is_dir() and not (launch / "controller-failure.json").exists():
            write_new(launch / "controller-failure.json", {"run_id": run_id, "scope": "full", "passed": False,
                                                         "failure": str(failure)})
        raise
    finally:
        for process in processes.values():
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", default=str(uuid.uuid4()))
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--skip-prepare", action="store_true")
    parser.add_argument("--timeout-seconds", type=int, default=900)
    options = parser.parse_args()
    if not 120 <= options.timeout_seconds <= 3600:
        parser.error("--timeout-seconds must be between 120 and 3600")
    run(options)
