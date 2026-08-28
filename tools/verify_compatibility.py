#!/usr/bin/env python3
"""Check fresh Minecraft 1.21.1 unit/server compatibility evidence, never release evidence.

Run begin before the real build/tests, then check with the same --with-cobblemon
choice. Recorded reports are checked, not independently authenticated. No client,
multiplayer, visual, performance, or full release claim is made by this checker.
"""

from __future__ import annotations

import argparse
import io
import math
from pathlib import Path
import re
import struct
import sys
import time
import uuid
from zipfile import ZipFile

import verify_evidence as evidence

ROOT = evidence.ROOT
OUTPUT = ROOT / "build/verification"
MINECRAFT_VERSION = "1.21.1"
MOD_VERSION = "0.5.0-alpha+mc1.21.1"
FABRIC_API_VERSION = "0.116.15+1.21.1"
ENERGY_VERSION = "4.1.0"
COBBLEMON_VERSION = "1.7.3+1.21.1"
SCOPE = "unit-and-server/compatibility"
ARTIFACT_NAME = f"fabricated-backpacks-{MOD_VERSION}.jar"
ENERGY_JAR = f"META-INF/jars/energy-{ENERGY_VERSION}.jar"
CLASS_PREFIX = "com/kadamitas/fabricatedbackpacks/"
SERVER_SOURCE = "src/gametest/java/com/kadamitas/fabricatedbackpacks/gametest"
COBBLEMON_ITEMS = frozenset(f"cobblemon:{name}_ball" for name in ("poke", "great", "ultra"))
require = evidence.require


def native_server_ids(class_name: str) -> set[str]:
    """Match the native 1.21.1 class.method naming rule, without a namespace prefix."""
    path = ROOT / SERVER_SOURCE / f"{class_name}.java"
    require(path.is_file(), f"Missing native server-test source: {path}")
    source = evidence.java_declarations(path)
    declaration = re.search(r"\bclass\s+" + re.escape(class_name) + r"\b[^;{}]*\{", source)
    require(declaration is not None, f"Missing native server-test class: {class_name}")
    begin = declaration.end() - 1
    end = evidence.closing_delimiter(source, begin, "{", "}")
    annotations = list(re.finditer(r"@(?:net\.minecraft\.gametest\.framework\.)?GameTest\b", source))
    require(bool(annotations), f"No native server tests declared by {class_name}")
    require("net.fabricmc.fabric.api.gametest.v1.GameTest" not in source,
            "The 26.2 GameTest annotation is not valid 1.21.1 evidence")
    result = set()
    for annotation in annotations:
        require(begin < annotation.start() < end, f"GameTest declared outside {class_name}")
        cursor = annotation.end()
        while cursor < len(source) and source[cursor].isspace():
            cursor += 1
        require(cursor < len(source) and source[cursor] == "(", "Native tests must declare their template explicitly")
        cursor = evidence.closing_delimiter(source, cursor, "(", ")") + 1
        method = re.match(r"\s*public\s+void\s+(\w+)\s*\(\s*(?:net\.minecraft\.gametest\.framework\.)?GameTestHelper\s+\w+\s*\)",
                          source[cursor:])
        require(method is not None, f"Unrecognized native server-test declaration in {class_name}; update the discovery audit")
        name = (class_name + "." + method.group(1)).lower()
        require(name not in result, f"Duplicate native server-test identity: {name}")
        result.add(name)
    return result


def expected_server_ids(with_cobblemon: bool) -> set[str]:
    result = native_server_ids("BackpackGameTests")
    if with_cobblemon:
        optional = native_server_ids("CobblemonCompatibilityGameTests")
        require(len(optional) == 2, "Exactly two declared Cobblemon compatibility tests are required")
        result |= optional
    return result


def classfile_major(data: bytes, name: str) -> int:
    """Check bounded class-file structure and Java compatibility, not JVM bytecode semantics."""
    position = 0

    def take(length: int) -> bytes:
        nonlocal position
        require(length >= 0 and position + length <= len(data), f"Truncated Java class: {name}")
        value = data[position:position + length]
        position += length
        return value

    def u1() -> int:
        return take(1)[0]

    def u2() -> int:
        return struct.unpack(">H", take(2))[0]

    def u4() -> int:
        return struct.unpack(">I", take(4))[0]

    require(take(4) == b"\xca\xfe\xba\xbe", f"Invalid Java class magic: {name}")
    minor, major = u2(), u2()
    require(45 <= major <= 65 and minor == 0, f"Class requires a newer or preview Java runtime: {name} ({major}.{minor})")
    count = u2()
    require(count > 1, f"Empty Java constant pool: {name}")
    pool: list[tuple[int, object] | None] = [None] * count
    index = 1
    while index < count:
        tag = u1()
        if tag == 1:
            value = take(u2())
        elif tag in (3, 4):
            value = take(4)
        elif tag in (5, 6):
            value = take(8)
        elif tag in (7, 8, 16, 19, 20):
            value = u2()
        elif tag in (9, 10, 11, 12, 17, 18):
            value = (u2(), u2())
        elif tag == 15:
            value = (u1(), u2())
        else:
            raise ValueError(f"Invalid Java constant-pool tag {tag}: {name}")
        pool[index] = (tag, value)
        index += 1
        if tag in (5, 6):
            require(index < count, f"Invalid wide Java constant: {name}")
            index += 1

    def reference(index: int, tags: tuple[int, ...]) -> object:
        require(0 < index < count and pool[index] is not None and pool[index][0] in tags,
                f"Invalid Java constant-pool reference: {name}")
        return pool[index][1]

    for entry in pool[1:]:
        if entry is None:
            continue
        tag, value = entry
        if tag in (7, 8, 16, 19, 20):
            reference(value, (1,))
        elif tag in (9, 10, 11):
            reference(value[0], (7,))
            reference(value[1], (12,))
        elif tag == 12:
            reference(value[0], (1,))
            reference(value[1], (1,))
        elif tag in (17, 18):
            reference(value[1], (12,))
        elif tag == 15:
            require(1 <= value[0] <= 9, f"Invalid Java method-handle kind: {name}")
            reference(value[1], (9, 10, 11))
    u2()  # access flags
    this_class = reference(reference(u2(), (7,)), (1,)).decode("utf-8")
    require(this_class == name.removesuffix(".class"), f"Java class identity differs from its archive path: {name}")
    superclass = u2()
    if superclass:
        reference(superclass, (7,))
    for _ in range(u2()):
        reference(u2(), (7,))

    def attributes() -> None:
        for _ in range(u2()):
            reference(u2(), (1,))
            take(u4())

    for _ in range(2):  # fields, then methods
        for _ in range(u2()):
            u2()
            reference(u2(), (1,))
            reference(u2(), (1,))
            attributes()
    attributes()
    require(position == len(data), f"Trailing bytes in Java class: {name}")
    return major


def audit_classes(jar: ZipFile, names: list[str], prefix: str) -> dict:
    classes = [name for name in names if name.endswith(".class")]
    require(bool(classes), "Production archive contains no Java classes")
    require(all(name.startswith(prefix) for name in classes), "Foreign or shaded library classes entered the production archive")
    versions = [classfile_major(jar.read(name), name) for name in classes]
    return {"classes": len(classes), "maximum_class_major": max(versions)}


def audit_jar(path: Path, started: float) -> dict:
    require(not path.is_symlink() and path.resolve().is_relative_to(ROOT.resolve()), "Artifact leaves the project")
    evidence.check_fresh(path, started, evidence.MAX_JAR_BYTES)
    with ZipFile(path) as jar:
        names = evidence.archive_names(jar, path.name)
        metadata = evidence.object_json(jar.read("fabric.mod.json"), "1.21.1 production metadata")
        require(metadata.get("id") == "fabricated_backpacks" and metadata.get("version") == MOD_VERSION,
                "Unexpected 1.21.1 production coordinates")
        require(metadata.get("license") == "MIT", "Unexpected project license")
        dependencies = metadata.get("depends")
        require(isinstance(dependencies, dict) and dependencies.get("minecraft") == MINECRAFT_VERSION,
                "Unexpected game target in the production artifact")
        require(dependencies.get("java") == ">=21", "Production metadata must require Java >=21")
        require(dependencies.get("team_reborn_energy") == ">=4.1.0"
                and dependencies.get("fabric-api") == ">=" + FABRIC_API_VERSION,
                "Unexpected production API requirements")
        entrypoints = metadata.get("entrypoints")
        require(isinstance(entrypoints, dict) and bool(entrypoints.get("main")) and bool(entrypoints.get("client")),
                "Missing production entrypoints")
        for key, entries in entrypoints.items():
            require("test" not in key.lower() and isinstance(entries, list) and bool(entries), "Test or malformed production entrypoint")
            for entry in entries:
                require(isinstance(entry, str) and entry.startswith(CLASS_PREFIX.replace("/", ".")), "Foreign production entrypoint")
                class_name = entry.split("::", 1)[0].replace(".", "/") + ".class"
                require(class_name in names, f"Missing production entrypoint class: {entry}")
        require(CLASS_PREFIX + "FabricatedBackpacks.class" in names, "Production mod class missing")
        require(all(name == "assets/" or not name.startswith("assets/") or name.startswith("assets/fabricated_backpacks/") for name in names),
                "Foreign assets entered the production archive")
        nested = [name for name in names if name.lower().endswith((".jar", ".zip"))]
        require(nested == [ENERGY_JAR], "Only the exact Energy 4.1.0 JAR may be bundled; Cobblemon and JEI must remain external")
        require(metadata.get("jars") == [{"file": ENERGY_JAR}], "The sole nested Energy JAR must be declared exactly once")
        classes = audit_classes(jar, names, CLASS_PREFIX)
        with ZipFile(io.BytesIO(jar.read(ENERGY_JAR))) as energy:
            energy_names = evidence.archive_names(energy, ENERGY_JAR)
            energy_metadata = evidence.object_json(energy.read("fabric.mod.json"), "nested Energy metadata")
            require(energy_metadata.get("id") == "team_reborn_energy" and energy_metadata.get("version") == ENERGY_VERSION,
                    "Nested JAR is not Energy 4.1.0")
            require(not energy_metadata.get("jars") and not any(name.lower().endswith((".jar", ".zip")) for name in energy_names),
                    "Energy may not conceal additional bundled libraries")
            energy_classes = audit_classes(energy, energy_names, "team/reborn/energy/")
    return {"path": path.relative_to(ROOT).as_posix(), "sha256": evidence.sha256(path), "entries": len(names),
            **classes, "energy": {"version": ENERGY_VERSION, **energy_classes}}


def audit_runtime(started: float, with_cobblemon: bool) -> dict:
    path = OUTPUT / "compatibility-runtime.json"
    record = evidence.read_object(path, started)
    require(type(record.get("schema")) is int and record["schema"] == 1, "Unsupported compatibility runtime schema")
    pid = evidence.positive_pid(record.get("pid"), "compatibility server runtime")
    recorded = record.get("recorded_at")
    require(type(recorded) is int and int(started * 1000) <= recorded <= int(time.time() * 1000),
            "Compatibility runtime receipt is stale or has an invalid timestamp")
    require(record.get("minecraft_version") == MINECRAFT_VERSION, "Compatibility runtime has the wrong Minecraft target")
    require(type(record.get("java_feature")) is int and record["java_feature"] == 21, "Compatibility runtime must actually use Java 21")
    require(record.get("environment") == "SERVER", "Compatibility receipt is not from a server runtime")
    versions = record.get("mod_versions")
    require(isinstance(versions, dict) and bool(versions)
            and all(isinstance(key, str) and key and isinstance(value, str) and value for key, value in versions.items()),
            "Missing or malformed loaded mod versions")
    expected = {"fabricated_backpacks": MOD_VERSION, "team_reborn_energy": ENERGY_VERSION, "fabric-api": FABRIC_API_VERSION}
    for mod, version in expected.items():
        require(versions.get(mod) == version, f"Actual runtime requires {mod} {version}")
    require(bool(versions.get("fabricloader")), "Runtime does not identify the loaded Fabric Loader")
    items = record.get("registered_items")
    require(isinstance(items, list) and all(isinstance(item, str) for item in items) and len(items) == len(set(items)),
            "Malformed or duplicate registered compatibility items")
    if with_cobblemon:
        require(versions.get("cobblemon") == COBBLEMON_VERSION, "Cobblemon compatibility requires the actual pinned Cobblemon mod")
        require(set(items) == COBBLEMON_ITEMS, "Missing or unexpected actual Cobblemon compatibility registry items")
    else:
        require("cobblemon" not in versions and not items, "Cobblemon runtime disagrees with --with-cobblemon=false")
    return {"path": path.relative_to(ROOT).as_posix(), "sha256": evidence.sha256(path), "pid": pid,
            "recorded_at": recorded, "minecraft_version": MINECRAFT_VERSION, "java_feature": 21,
            "environment": "SERVER", "mod_versions": versions, "registered_items": items}


def start_record(with_cobblemon: bool) -> dict:
    snapshot = evidence.inputs()
    return {"schema": 1, "scope": SCOPE, "minecraft_version": MINECRAFT_VERSION,
            "with_cobblemon": with_cobblemon, "run_id": str(uuid.uuid4()), "started": time.time(), "inputs": snapshot}


def verify(with_cobblemon: bool) -> dict:
    start = evidence.read_object(OUTPUT / "compatibility-start.json", 0)
    require(type(start.get("schema")) is int and start["schema"] == 1 and start.get("scope") == SCOPE,
            "Unsupported compatibility-start scope/schema; run compatibility begin again")
    require(start.get("minecraft_version") == MINECRAFT_VERSION, "Compatibility start has the wrong Minecraft target")
    require(type(start.get("with_cobblemon")) is bool and start["with_cobblemon"] is with_cobblemon,
            "--with-cobblemon changed after begin; start a new verification run")
    run_id = evidence.canonical_uuid(start.get("run_id"), "compatibility start")
    started = start.get("started")
    require(type(started) in (int, float) and math.isfinite(started) and 0 < started <= time.time(), "Invalid compatibility start time")
    evidence.check_fresh(OUTPUT / "compatibility-start.json", started)
    require(evidence.inputs() == start.get("inputs"), "Source/build inputs changed after compatibility begin")
    reports = sorted((ROOT / "build/test-results/test").glob("TEST-*.xml"))
    require(bool(reports), "No JUnit reports")
    unit_cases = [case for report in reports for case in evidence.test_cases(report, started)]
    actual_classes = {case.attrib.get("classname", "") for case in unit_cases}
    expected_classes = evidence.expected_unit_classes()
    require(actual_classes == expected_classes,
            f"JUnit discovery mismatch: missing={sorted(expected_classes - actual_classes)}, unexpected={sorted(actual_classes - expected_classes)}")
    unit_execution = evidence.audit_unit_execution(unit_cases, started)
    server_path = ROOT / "build/gametest-results.xml"
    server = evidence.test_cases(server_path, started)
    names = [case.attrib["name"] for case in server]
    require(len(names) == len(set(names)), "Duplicate native server-test results")
    actual, expected = set(names), expected_server_ids(with_cobblemon)
    require(actual == expected,
            f"Native server discovery mismatch: missing={sorted(expected - actual)}, unexpected={sorted(actual - expected)}")
    runtime = audit_runtime(started, with_cobblemon)
    artifact = audit_jar(ROOT / "build/libs" / ARTIFACT_NAME, started)
    require(evidence.inputs() == start["inputs"], "Source/build inputs changed while compatibility evidence was checked")
    return {"schema": 1, "passed": True, "scope": SCOPE, "minecraft_version": MINECRAFT_VERSION,
            "with_cobblemon": with_cobblemon, "run_id": run_id, "verified_at": time.time(), "inputs": start["inputs"],
            "unit_tests": len(unit_cases), "unit_test_classes": len(actual_classes), "unit_test_methods": unit_execution["methods"],
            "unit_execution": unit_execution,
            "unit_reports": [{"path": path.relative_to(ROOT).as_posix(), "sha256": evidence.sha256(path)} for path in reports],
            "server_tests": len(server), "server_test_ids": sorted(actual),
            "cobblemon_tests": sum(name.startswith("cobblemoncompatibilitygametests.") for name in names),
            "server_report": {"path": server_path.relative_to(ROOT).as_posix(), "sha256": evidence.sha256(server_path)},
            "runtime": runtime, "artifact": artifact}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("begin", "check"))
    parser.add_argument("--with-cobblemon", action="store_true", help="Require the pinned Cobblemon runtime and both compatibility tests")
    parser.add_argument("--release", action="store_true", help="Unsupported: this target has no completed client release harness")
    args = parser.parse_args(argv)
    target = OUTPUT / "compatibility.json"
    try:
        OUTPUT.mkdir(parents=True, exist_ok=True)
        target.unlink(missing_ok=True)
        require(not args.release, "Release verification is unavailable on this target: the 1.21.1 client harness is not implemented; client receipts cannot replace it")
        if args.command == "begin":
            record = start_record(args.with_cobblemon)
            evidence.write_atomic(OUTPUT / "compatibility-start.json", record)
            print(f"Compatibility verification started: {record['run_id']}; Minecraft={MINECRAFT_VERSION}; Cobblemon={args.with_cobblemon}; scope={SCOPE}")
        else:
            record = verify(args.with_cobblemon)
            evidence.write_atomic(target, record)
            print(f"Verified {record['unit_tests']} unit and {record['server_tests']} native server tests; Cobblemon={args.with_cobblemon}; scope={SCOPE}; sha256={record['artifact']['sha256']}")
        return 0
    except Exception as error:
        print(f"Compatibility verification rejected ({type(error).__name__}): {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
