#!/usr/bin/env python3
"""Passed-stage ledger keyed by the production hash of the shipped mod.

The hash covers only inputs that end up in the release JAR: the shipped source
sets, the Gradle build files and the wrapper. Test sources, the test harness,
workflows and scripts are excluded, so a harness-only fix keeps the hash stable
and CI re-runs only the stages that have not yet passed for this exact mod.

A stage is recorded only from the step that just passed it; nothing here runs
from a failed or cancelled step. Any production change gives a new hash, so no
mod bug can hide behind an earlier pass.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
LEDGER = ROOT / ".ci-ledger/ledger.json"
SKIPPED = ROOT / "build/verification/skipped.json"
PRODUCTION_FILES = ("build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat", "LICENSE")
PRODUCTION_DIRECTORIES = ("src/main", "src/client", "gradle/wrapper")
STAGES = ("unit-server", "client-full", "client-restart", "multiplayer")
# The restart scenario replays the world the full scenario archived in the same job,
# so the two are skipped together or run together.
COUPLED = {"client-full": ("client-restart",), "client-restart": ("client-full",)}


def production_hash(root: Path = ROOT) -> str:
    files = [root / name for name in PRODUCTION_FILES if (root / name).is_file()]
    for directory in PRODUCTION_DIRECTORIES:
        files.extend(path for path in (root / directory).rglob("*") if path.is_file())
    digest = hashlib.sha256()
    for path in sorted(set(files)):
        digest.update(path.relative_to(root).as_posix().encode() + b"\0" + hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def load_ledger() -> dict:
    if not LEDGER.is_file():
        return {"schema": 1, "passed": {}}
    ledger = json.loads(LEDGER.read_text(encoding="utf-8"))
    if ledger.get("schema") != 1 or not isinstance(ledger.get("passed"), dict):
        raise ValueError("Unsupported ledger schema")
    return ledger


def passed_entries(digest: str) -> dict:
    """Stage -> {run_id, run_url, job_url, recorded_at, summary} recorded as passed for this hash."""
    entries = load_ledger()["passed"].get(digest, {})
    return {stage: entry for stage, entry in entries.items() if isinstance(entry, dict) and entry.get("run_id")}


def skipped_stages(path: Path = SKIPPED, digest: str | None = None) -> dict:
    """Stage -> ledger entry for stages this run skipped (written by `check`), for this production hash only."""
    if not path.is_file():
        return {}
    record = json.loads(path.read_text(encoding="utf-8"))
    stages = record.get("stages")
    if record.get("hash") != (digest or production_hash()) or not isinstance(stages, dict):
        return {}
    return {stage: entry for stage, entry in stages.items() if isinstance(entry, dict)}


def github_output(name: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as stream:
            stream.write(f"{name}={value}\n")


def summarize(line: str) -> None:
    print(line)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as stream:
            stream.write(line + "\n\n")


def job_url() -> str:
    server, repository, run = (os.environ.get(name, "") for name in ("GITHUB_SERVER_URL", "GITHUB_REPOSITORY", "GITHUB_RUN_ID"))
    run_url = f"{server}/{repository}/actions/runs/{run}"
    job = os.environ.get("GITHUB_JOB", "")
    if os.environ.get("GH_TOKEN") and job:
        try:
            listing = subprocess.run(["gh", "api", f"repos/{repository}/actions/runs/{run}/jobs", "--paginate"],
                                     capture_output=True, text=True, check=True, timeout=60).stdout
            for entry in json.loads(listing).get("jobs", []):
                if entry.get("status") == "in_progress" and entry.get("html_url"):
                    return entry["html_url"]
        except (subprocess.SubprocessError, ValueError):
            pass
    return run_url


def check(stage: str, output: str) -> int:
    digest = production_hash()
    entries = passed_entries(digest)
    needed = (stage,) + COUPLED.get(stage, ())
    skip = all(name in entries for name in needed)
    record = {"hash": digest, "stages": skipped_stages(SKIPPED, digest)}
    if skip:
        record["stages"][stage] = entries[stage]
        summarize(f"{stage}: skipped: passed in run {entries[stage]['run_id']} ({entries[stage].get('job_url') or entries[stage].get('run_url')}) for production hash {digest[:12]}")
    else:
        record["stages"].pop(stage, None)
        print(f"{stage}: running (no recorded pass for production hash {digest[:12]})")
    SKIPPED.parent.mkdir(parents=True, exist_ok=True)
    SKIPPED.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    github_output(output, "true" if skip else "false")
    return 0


def record(stage: str, summary_path: str | None) -> int:
    run_id = os.environ.get("GITHUB_RUN_ID")
    if not run_id:
        raise SystemExit("record is only meaningful inside a GitHub Actions job")
    if stage in skipped_stages():
        raise SystemExit(f"{stage} was skipped in this run; nothing new to record")
    digest = production_hash()
    ledger = load_ledger()
    entry = {"run_id": run_id, "run_url": f"{os.environ.get('GITHUB_SERVER_URL', '')}/{os.environ.get('GITHUB_REPOSITORY', '')}/actions/runs/{run_id}",
             "job_url": job_url(), "branch": os.environ.get("GITHUB_REF_NAME", ""), "commit": os.environ.get("GITHUB_SHA", ""),
             "recorded_at": datetime.now(timezone.utc).isoformat(timespec="seconds")}
    if summary_path:
        entry["summary"] = json.loads(Path(summary_path).read_text(encoding="utf-8"))
    ledger["passed"].setdefault(digest, {})[stage] = entry
    LEDGER.parent.mkdir(parents=True, exist_ok=True)
    LEDGER.write_text(json.dumps(ledger, indent=2) + "\n", encoding="utf-8")
    summarize(f"{stage}: passed in this run ({run_id}); recorded for production hash {digest[:12]}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("hash", help="Print the production hash")
    checker = commands.add_parser("check", help="Decide whether a stage may be skipped; writes the skip decision and the `skip` output")
    checker.add_argument("stage", choices=STAGES)
    checker.add_argument("--output", default="skip", help="GitHub Actions step output name for the decision")
    recorder = commands.add_parser("record", help="Record a stage that just passed in this job")
    recorder.add_argument("stage", choices=STAGES)
    recorder.add_argument("--summary", help="JSON file whose contents are stored with the entry (counts, ids)")
    args = parser.parse_args(argv)
    if args.command == "hash":
        digest = production_hash()
        print(digest)
        github_output("hash", digest)
        return 0
    if args.command == "check":
        return check(args.stage, args.output)
    return record(args.stage, args.summary)


if __name__ == "__main__":
    raise SystemExit(main())
