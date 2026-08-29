#!/usr/bin/env python3
"""Bind fresh, complete verification reports to unchanged project inputs.

The gate checks recorded evidence. It does not attest that every possible behavior
was tested, or turn an operator's manual observations into an independent audit.
"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import io
import json
import math
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import struct
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from zipfile import ZipFile
import zlib

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "build/verification"
CLIENT = ROOT / ".codex-local/client-evidence"
MAX_EVIDENCE_BYTES = 64 * 1024 * 1024
MAX_JAR_BYTES = 512 * 1024 * 1024
MOD_TEST_PREFIX = "fabricated_backpacks_tests:"
VERSION_PATTERN = re.compile(
    r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?(?:\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"
)
MINECRAFT_VERSION_PATTERN = re.compile(r"[0-9]+(?:\.[0-9]+){1,2}(?:[-+][0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def object_json(data: bytes | str, context: str) -> dict:
    def unique(pairs: list[tuple[str, object]]) -> dict:
        result = {}
        for key, value in pairs:
            require(key not in result, f"Duplicate JSON key {key!r} in {context}")
            result[key] = value
        return result

    def constant(value: str) -> None:
        raise ValueError(f"Non-finite JSON number {value} in {context}")

    result = json.loads(data, object_pairs_hook=unique, parse_constant=constant)
    require(isinstance(result, dict), f"Expected a JSON object in {context}")
    return result


def positive_pid(value: object, context: str) -> int:
    require(type(value) is int and value > 0, f"Invalid process ID in {context}")
    return value


def canonical_uuid(value: object, context: str) -> str:
    require(isinstance(value, str) and str(uuid.UUID(value)) == value, f"Invalid canonical run/profile UUID in {context}")
    return value


def project_properties() -> dict[str, str]:
    path = ROOT / "gradle.properties"
    require(path.is_file(), "Missing gradle.properties")
    properties: dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        require("=" in line, f"Malformed gradle.properties line {number}")
        key, value = (part.strip() for part in line.split("=", 1))
        require(re.fullmatch(r"[A-Za-z0-9_.-]+", key) is not None and bool(value),
                f"Malformed gradle.properties line {number}")
        require(key not in properties, f"Duplicate gradle.properties key: {key}")
        properties[key] = value
    return properties


def release_coordinates() -> tuple[str, str]:
    properties = project_properties()
    version = properties.get("mod_version")
    minecraft = properties.get("minecraft_version")
    require(isinstance(version, str) and VERSION_PATTERN.fullmatch(version) is not None,
            "Missing or invalid mod_version in gradle.properties")
    require(isinstance(minecraft, str) and MINECRAFT_VERSION_PATTERN.fullmatch(minecraft) is not None,
            "Missing or invalid minecraft_version in gradle.properties")
    require(version.endswith(f"+mc{minecraft}"),
            "mod_version must identify the configured Minecraft target")
    return version, minecraft


def inputs() -> dict[str, str]:
    required = ("build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat", "LICENSE", ".gitattributes", ".gitignore")
    files = [ROOT / name for name in required]
    for path in files:
        require(path.is_file(), f"Missing required source/build input: {path.name}")
    files.extend(ROOT / name for name in ("README.md", "CHANGELOG.md") if (ROOT / name).is_file())
    for directory in ("src", "gradle", "tools", ".github"):
        files.extend(path for path in (ROOT / directory).rglob("*")
                     if path.is_file() and "__pycache__" not in path.parts and path.suffix not in (".pyc", ".pyo"))
    for path in files:
        require(not path.is_symlink() and path.resolve().is_relative_to(ROOT.resolve()), f"Input leaves the project: {path}")
    return {path.relative_to(ROOT).as_posix(): sha256(path) for path in sorted(set(files))}


def check_fresh(path: Path, started: float, limit: int = MAX_EVIDENCE_BYTES) -> None:
    require(path.is_file(), f"Missing evidence: {path}")
    status = path.stat()
    require(status.st_mtime_ns >= int(started * 1_000_000_000), f"Evidence predates this verification run: {path}")
    require(0 < status.st_size <= limit, f"Empty or oversized evidence: {path}")


def read_fresh(path: Path, started: float) -> bytes:
    check_fresh(path, started)
    return path.read_bytes()


def read_object(path: Path, started: float) -> dict:
    return object_json(read_fresh(path, started), str(path))


def test_cases(path: Path, started: float) -> list[ET.Element]:
    data = read_fresh(path, started)
    require(b"<!DOCTYPE" not in data.upper() and b"<!ENTITY" not in data.upper(), f"XML declarations are not allowed in reports: {path}")
    root = ET.fromstring(data)
    require(root.tag in ("testsuite", "testsuites"), f"Unexpected XML test-report root: {path}")
    cases = list(root.iter("testcase"))
    require(bool(cases), f"No tests discovered in {path}")
    for element in root.iter():
        require(element.tag not in ("failure", "error", "skipped"), f"Unsuccessful or skipped test in {path.name}: {element.attrib}")
        if element.tag not in ("testsuite", "testsuites"):
            continue
        for attribute in ("tests", "failures", "errors", "skipped", "disabled"):
            if attribute not in element.attrib:
                continue
            value = element.attrib[attribute]
            require(re.fullmatch(r"\d+", value) is not None, f"Invalid suite {attribute} count in {path.name}")
            if attribute == "tests":
                require(int(value) == len(list(element.iter("testcase"))), f"Suite discovery count mismatch in {path.name}")
            else:
                require(int(value) == 0, f"Suite reports {attribute}={value} in {path.name}")
        if "timestamp" in element.attrib:
            timestamp = datetime.fromisoformat(element.attrib["timestamp"].replace("Z", "+00:00"))
            if timestamp.tzinfo is None:
                timestamp = timestamp.replace(tzinfo=timezone.utc)
            require(timestamp.timestamp() >= started, f"Suite execution predates this verification run: {path.name}")
    require(all(case.attrib.get("name", "").strip() for case in cases), f"Unnamed test case in {path.name}")
    return cases


def java_declarations(path: Path) -> str:
    source = path.read_text(encoding="utf-8")
    return re.sub(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/',
                  lambda match: re.sub(r"[^\n]", " ", match.group()), source)


def closing_delimiter(source: str, start: int, opening: str, closing: str) -> int:
    depth = 0
    for index in range(start, len(source)):
        if source[index] == opening:
            depth += 1
        elif source[index] == closing:
            depth -= 1
            if depth == 0:
                return index
    raise ValueError("Unbalanced Java declaration; update the discovery audit")


def expected_unit_methods() -> set[tuple[str, str]]:
    """Read declaration identities, not display names; unsupported/overloaded forms fail closed."""
    methods = set()
    for path in (ROOT / "src/test/java").rglob("*.java"):
        source = java_declarations(path)
        annotations = list(re.finditer(r"@(?:[\w.]+\.)?(?:Test|TestFactory|ParameterizedTest|RepeatedTest|TestTemplate)\b", source))
        if not annotations:
            continue
        package = re.search(r"\bpackage\s+([\w.]+)\s*;", source)
        require(package is not None, f"Unit test has no package: {path}")
        classes = []
        for match in re.finditer(r"\b(?:class|interface|record|enum)\s+([\w$]+)[^;{}]*\{", source):
            opening = match.end() - 1
            classes.append((opening, closing_delimiter(source, opening, "{", "}"), match.group(1)))
        for annotation in annotations:
            cursor = annotation.end()
            while True:
                while cursor < len(source) and source[cursor].isspace():
                    cursor += 1
                if cursor < len(source) and source[cursor] == "(":
                    cursor = closing_delimiter(source, cursor, "(", ")") + 1
                    continue
                extra = re.match(r"@[\w.]+", source[cursor:])
                if extra:
                    cursor += extra.end()
                    continue
                break
            declaration = re.match(r"([^;{}=]*?)\b([\w$]+)\s*\(", source[cursor:])
            require(declaration is not None and declaration.group(1).strip(),
                    f"Unrecognized unit-test method in {path}; update the discovery audit")
            enclosing = [name for opening, closing, name in classes if opening < annotation.start() < closing]
            require(bool(enclosing), f"Unit-test method has no declaring class in {path}")
            identity = (package.group(1) + "." + "$".join(enclosing), declaration.group(2))
            require(identity not in methods, f"Duplicate/overloaded unit-test method is not supported by the discovery audit: {identity}")
            methods.add(identity)
    require(bool(methods), "No unit-test declarations found")
    return methods


def expected_unit_classes() -> set[str]:
    return {name for name, method in expected_unit_methods()}


def audit_unit_execution(cases: list[ET.Element], started: float) -> dict:
    path = ROOT / "build/test-results/test/unit-execution.json"
    record = read_object(path, started)
    require(record.get("schema") == 1 and record.get("complete") is True, "Incomplete JUnit execution identities")
    pid = positive_pid(record.get("pid"), "JUnit execution")
    first, last = record.get("started_at"), record.get("finished_at")
    require(type(first) is int and type(last) is int and int(started * 1000) <= first <= last <= int(time.time() * 1000),
            "JUnit execution identities predate the run or have invalid timestamps")
    require(record.get("problems") == [], "JUnit listener recorded unsuccessful or unfinished execution")
    methods, tests = record.get("methods"), record.get("tests")
    require(isinstance(methods, list) and bool(methods) and isinstance(tests, list) and bool(tests),
            "Missing JUnit method/leaf execution identities")

    def identity(value: object) -> tuple[str, str]:
        require(isinstance(value, dict), "Malformed JUnit execution identity")
        name, method = value.get("class_name"), value.get("method_name")
        require(isinstance(name, str) and re.fullmatch(r"[\w$]+(?:\.[\w$]+)+", name) is not None
                and isinstance(method, str) and re.fullmatch(r"[\w$]+", method) is not None
                and isinstance(value.get("parameter_types"), str), "Malformed JUnit method identity")
        return name, method

    declared = {}
    for method in methods:
        key = identity(method)
        require(key not in declared, f"Duplicate JUnit method identity: {key}")
        declared[key] = method["parameter_types"]
    expected = expected_unit_methods()
    require(set(declared) == expected,
            f"JUnit method discovery mismatch: missing={sorted(expected - set(declared))}, unexpected={sorted(set(declared) - expected)}")
    ids = set()
    executed = Counter()
    display = Counter()
    legacy = Counter()
    for test in tests:
        key = identity(test)
        require(key in declared and test["parameter_types"] == declared[key], "JUnit test refers to an undeclared method")
        unique_id = test.get("id")
        require(isinstance(unique_id, str) and bool(unique_id) and unique_id not in ids, "Missing or duplicate JUnit execution ID")
        ids.add(unique_id)
        require(test.get("status") == "SUCCESSFUL", "JUnit test did not execute successfully")
        require(all(isinstance(test.get(field), str) and test[field].strip() for field in ("display_name", "legacy_name")),
                "Missing JUnit test display/legacy identity")
        executed[key] += 1
        display[(key[0], test["display_name"])] += 1
        legacy[(key[0], test["legacy_name"])] += 1
    require(set(executed) == expected,
            f"JUnit methods without successful test cases: {sorted(expected - set(executed))}")
    reported = Counter((case.attrib.get("classname", ""), case.attrib["name"]) for case in cases)
    require(reported == display or reported == legacy, "JUnit XML and execution identities disagree on the exact test cases")
    return {"path": path.relative_to(ROOT).as_posix(), "sha256": sha256(path), "pid": pid, "methods": len(declared)}


def expected_server_ids() -> set[str]:
    path = ROOT / "src/gametest/java/com/kadamitas/fabricatedbackpacks/gametest/BackpackGameTests.java"
    source = java_declarations(path)
    names = re.findall(r"@GameTest\([^)]*\)\s+public\s+void\s+(\w+)\s*\(", source)
    require(bool(names) and len(set(names)) == len(names), "Missing or duplicate server-test declarations")
    require(len(names) == len(re.findall(r"@GameTest\b", source)), "Unrecognized server-test declaration; update the discovery audit")
    return {MOD_TEST_PREFIX + re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", "BackpackGameTests_" + name).lower() for name in names}


def test_source_classes() -> set[str]:
    result = set()
    for directory in ("src/test/java", "src/gametest/java"):
        for path in (ROOT / directory).rglob("*.java"):
            source = java_declarations(path)
            package = re.search(r"\bpackage\s+([\w.]+)\s*;", source)
            if package is not None:
                result.add(package.group(1).replace(".", "/") + "/" + path.stem)
    return result


def archive_names(jar: ZipFile, context: str) -> list[str]:
    names = jar.namelist()
    require(bool(names) and len(names) == len(set(names)), f"Empty or duplicate JAR entries in {context}")
    require(len(names) <= 100_000 and sum(entry.file_size for entry in jar.infolist()) <= MAX_JAR_BYTES,
            f"Oversized JAR contents in {context}")
    for entry in jar.infolist():
        require(entry.orig_filename == entry.filename, f"Unsafe JAR entry was normalized by the ZIP reader in {context}: {entry.orig_filename!r}")
    forbidden = ("gametest", "fabricated_backpacks_tests", ".codex-local", "fixture", "test_instance", "secret")
    source_classes = test_source_classes()
    for name in names:
        path = PurePosixPath(name)
        require(not path.is_absolute() and ".." not in path.parts and "\\" not in name
                and not PureWindowsPath(name).drive, f"Unsafe JAR entry in {context}: {name}")
        require(not any(token in name.lower() for token in forbidden)
                and not any(part.lower() in (".git", ".github", ".gradle", "agents", "agent", "tests", "test") for part in path.parts)
                and path.name.lower() not in ("agents.md", "claude.md", "codex.md", ".env")
                and not path.name.lower().startswith(".env."),
                f"Private or test content entered the production JAR: {name}")
        if path.suffix in (".class", ".java"):
            outer = name.rsplit(".", 1)[0].split("$", 1)[0]
            require(outer not in source_classes and re.search(r"(?:Test|Tests|TestCase)$", outer.rsplit("/", 1)[-1]) is None,
                    f"Private or test class entered the production JAR: {name}")
    require(jar.testzip() is None, f"Corrupt JAR contents in {context}")
    return names


def audit_jar(path: Path, started: float, version: str, minecraft: str) -> dict:
    check_fresh(path, started, MAX_JAR_BYTES)
    with ZipFile(path) as jar:
        names = archive_names(jar, path.name)
        metadata = object_json(jar.read("fabric.mod.json"), "production fabric.mod.json")
        require(metadata.get("id") == "fabricated_backpacks" and metadata.get("version") == version,
                "Unexpected release coordinates")
        dependencies = metadata.get("depends")
        require(isinstance(dependencies, dict) and dependencies.get("minecraft") == minecraft,
                "Unexpected game target")
        require(metadata.get("license") == "MIT", "Unexpected project license")
        entrypoints = metadata.get("entrypoints")
        require(isinstance(entrypoints, dict) and "main" in entrypoints and "client" in entrypoints, "Missing production entrypoints")
        require("gametest" not in json.dumps(entrypoints).lower(), "Test entrypoint in production")
        require("com/kadamitas/fabricatedbackpacks/FabricatedBackpacks.class" in names, "Production mod class missing")
        energy = [name for name in names if name.startswith("META-INF/jars/energy-") and name.endswith(".jar")]
        require(len(energy) == 1, "Bundled Energy API missing or duplicated")
        declared = {entry["file"] for entry in metadata.get("jars", [])}
        require(energy[0] in declared, "Bundled Energy API is not declared for loading")
        for name in names:
            if name.endswith(".jar"):
                with ZipFile(io.BytesIO(jar.read(name))) as nested:
                    archive_names(nested, name)
    return {"path": path.relative_to(ROOT).as_posix(), "sha256": sha256(path), "entries": len(names),
            "version": version, "minecraft": minecraft}


def relative_screenshot(value: object) -> Path:
    require(isinstance(value, str) and bool(value.strip()), "Screenshot path must be a nonempty project-relative string")
    normalized = value.replace("\\", "/")
    require(not PureWindowsPath(value).drive and not PurePosixPath(normalized).is_absolute()
            and all(part not in ("", ".", "..") for part in normalized.split("/")),
            f"Unsafe screenshot path: {value}")
    path = (ROOT / normalized).resolve()
    require(path.is_relative_to(ROOT.resolve()), f"Screenshot leaves the project: {value}")
    return path


def png_dimensions(data: bytes, context: str) -> tuple[int, int]:
    """Validate complete PNG framing and bounded scanline data; every acceptance capture is PNG."""
    require(data.startswith(b"\x89PNG\r\n\x1a\n"), f"Screenshot must be a complete PNG: {context}")
    offset, header, palette = 8, None, None
    seen_data, ended_data, ended = False, False, False
    compressed = bytearray()
    while offset < len(data):
        require(offset + 12 <= len(data), f"Truncated PNG chunk: {context}")
        length = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        require(re.fullmatch(b"[A-Za-z]{4}", kind) is not None and not kind[2] & 32,
                f"Invalid PNG chunk type: {context}")
        end = offset + 12 + length
        require(end <= len(data), f"Truncated PNG chunk data: {context}")
        content = data[offset + 8:offset + 8 + length]
        checksum = struct.unpack_from(">I", data, offset + 8 + length)[0]
        require(zlib.crc32(kind + content) == checksum, f"PNG chunk CRC mismatch: {context}")
        require(header is not None or kind == b"IHDR", f"PNG must begin with IHDR: {context}")
        if kind == b"IHDR":
            require(header is None and offset == 8 and length == 13, f"Invalid/duplicate PNG header: {context}")
            header = struct.unpack(">IIBBBBB", content)
            width, height, depth, color, compression, filtering, interlace = header
            allowed = {0: (1, 2, 4, 8, 16), 2: (8, 16), 3: (1, 2, 4, 8), 4: (8, 16), 6: (8, 16)}
            require(0 < width <= 16_384 and 0 < height <= 16_384 and color in allowed and depth in allowed[color]
                    and compression == filtering == 0 and interlace in (0, 1), f"Unsupported/invalid PNG header: {context}")
        elif kind == b"PLTE":
            require(not seen_data and palette is None and 0 < length <= 768 and length % 3 == 0,
                    f"Invalid PNG palette: {context}")
            palette = length // 3
            require(header[3] not in (0, 4) and (header[3] != 3 or palette <= 1 << header[2]), f"Invalid PNG palette size: {context}")
        elif kind == b"IDAT":
            require(not ended_data, f"Nonconsecutive PNG image data: {context}")
            seen_data = True
            compressed.extend(content)
        elif kind == b"IEND":
            require(length == 0 and seen_data and end == len(data), f"Invalid PNG end or trailing data: {context}")
            ended = True
            offset = end
            break
        elif not kind[0] & 32:
            raise ValueError(f"Unknown critical PNG chunk in {context}")
        if seen_data and kind != b"IDAT":
            ended_data = True
        offset = end
    require(header is not None and ended and bool(compressed), f"PNG lacks complete image data/end: {context}")
    width, height, depth, color, compression, filtering, interlace = header
    require(color != 3 or palette is not None, f"Indexed PNG has no palette: {context}")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
    passes = ((0, 0, 1, 1),) if interlace == 0 else (
        (0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8), (2, 0, 4, 4),
        (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2))
    rows = []
    for left, top, across, down in passes:
        columns = max(0, (width - left + across - 1) // across)
        count = max(0, (height - top + down - 1) // down)
        if columns and count:
            rows.append((count, (columns * depth * channels + 7) // 8))
    expected = sum(count * (length + 1) for count, length in rows)
    require(0 < expected <= 128 * 1024 * 1024, f"PNG decompression exceeds the evidence limit: {context}")
    decoder = zlib.decompressobj()
    try:
        pixels = decoder.decompress(bytes(compressed), expected + 1)
    except zlib.error as failure:
        raise ValueError(f"Invalid PNG compressed image data: {context}") from failure
    require(len(pixels) == expected and decoder.eof and not decoder.unconsumed_tail and not decoder.unused_data,
            f"Truncated, excess, or incomplete PNG scanline data: {context}")
    cursor = 0
    for count, length in rows:
        for row in range(count):
            require(pixels[cursor] <= 4, f"Invalid PNG row filter: {context}")
            cursor += length + 1
    return width, height


def audit_screenshot(path: Path, started: float) -> dict:
    width, height = png_dimensions(read_fresh(path, started), path.name)
    return {"path": path.relative_to(ROOT).as_posix(), "sha256": sha256(path), "width": width, "height": height}


def verify_multiplayer(started: float) -> dict:
    record = read_object(OUTPUT / "multiplayer.json", started)
    run_id = canonical_uuid(record.get("run_id"), "multiplayer gate")
    host_pid = positive_pid(record.get("host_pid"), "multiplayer host")
    guest_pid = positive_pid(record.get("guest_pid"), "multiplayer guest")
    require(record.get("passed") is True and host_pid != guest_pid
            and type(record.get("host_exit")) is int and record["host_exit"] == 0
            and type(record.get("guest_exit")) is int and record["guest_exit"] == 0,
            "Two-JVM multiplayer acceptance is incomplete")
    require(isinstance(record.get("evidence_dir"), str), "Missing multiplayer evidence directory")
    directory = Path(record["evidence_dir"]).resolve()
    expected = (CLIENT / ("multiplayer-" + run_id)).resolve()
    require(directory == expected and directory.is_relative_to(CLIENT.resolve()), "Multiplayer evidence is outside its exact run directory")
    phases = {}
    for phase, role, pid in (("ready", "host", host_pid), ("host-pass", "host", host_pid), ("guest-pass", "guest", guest_pid)):
        proof = read_object(directory / f"{phase}.json", started)
        require(proof.get("run_id") == run_id, "Mixed multiplayer test runs")
        require(proof.get("phase") == phase and proof.get("role") == role, "Mismatched multiplayer phase or role")
        require(positive_pid(proof.get("pid"), phase) == pid, "Multiplayer report PID differs from the actual launched JVM")
        recorded = proof.get("recorded_at")
        require(type(recorded) is int and recorded >= int(started * 1000), "Multiplayer phase predates this verification run")
        phases[phase] = proof
    require(positive_pid(phases["host-pass"].get("guest_pid"), "host peer") == guest_pid
            and positive_pid(phases["guest-pass"].get("host_pid"), "guest peer") == host_pid,
            "Multiplayer reports do not refer to the same two JVMs")
    host_profile = canonical_uuid(phases["ready"].get("host_uuid"), "host profile")
    guest_profile = canonical_uuid(phases["guest-pass"].get("guest_uuid"), "guest profile")
    require(host_profile != guest_profile, "Multiplayer clients used the same profile")
    port = phases["ready"].get("port")
    require(type(port) is int and 1 <= port <= 65535, "Multiplayer evidence lacks the actual bound TCP port")
    require(phases["host-pass"].get("stored_emeralds") == 19
            and all(isinstance(phases[name].get("channels"), str) and phases[name]["channels"].strip()
                    for name in ("host-pass", "guest-pass")),
            "Multiplayer final inventory/audio assertions are incomplete")
    record["screenshots"] = [audit_screenshot(directory / name, started) for name in
                             ("host-sees-shared-19.png", "guest-inserts-19.png", "guest-sharing-revoked.png")]
    return record


def verify_clients(started: float, artifact: dict) -> dict:
    full = read_object(CLIENT / "full-pass.json", started)
    restart = read_object(CLIENT / "restart-pass.json", started)
    require(full.get("passed") is True and restart.get("passed") is True, "Client acceptance did not finish successfully")
    full_pid = positive_pid(full.get("pid"), "full client")
    writer = positive_pid(restart.get("writer_pid"), "restart writer")
    reader = positive_pid(restart.get("reader_pid"), "restart reader")
    require(full_pid == writer and reader != writer, "Restart was not a separate JVM for the tested world")
    require(isinstance(full.get("checks"), list) and full["checks"]
            and all(isinstance(check, str) and check.strip() for check in full["checks"]), "Full client acceptance has no specific checks")
    for directory in ("full-screenshots", "restart-screenshots"):
        screenshots = sorted((CLIENT / directory).glob("*.png"))
        require(bool(screenshots), f"Missing client screenshots: {directory}")
        for screenshot in screenshots:
            audit_screenshot(screenshot, started)
    multiplayer = verify_multiplayer(started)
    manual = read_object(OUTPUT / "manual.json", started)
    require(manual.get("passed") is True and manual.get("artifact_sha256") == artifact["sha256"],
            "Manual installed-JAR acceptance is missing or for another binary")
    observations = manual.get("observations")
    require(isinstance(observations, list) and bool(observations)
            and all(isinstance(value, str) and value.strip() for value in observations), "Manual acceptance needs specific observations")
    screenshots = manual.get("screenshots")
    require(isinstance(screenshots, list) and bool(screenshots), "Manual acceptance needs screenshots")
    resolved = [relative_screenshot(value) for value in screenshots]
    require(len(set(resolved)) == len(resolved), "Manual acceptance repeats a screenshot")
    manual["verified_screenshots"] = [audit_screenshot(path, started) for path in resolved]
    return {"full": full, "restart": restart, "multiplayer": multiplayer, "manual": manual}


def verify(release: bool) -> dict:
    start = object_json((OUTPUT / "start.json").read_bytes(), "verification start")
    require(start.get("schema") == 1, "Unsupported verification-start schema; run begin again")
    run_id = canonical_uuid(start.get("run_id"), "verification start")
    started = start.get("started")
    require(type(started) in (int, float) and math.isfinite(started) and 0 < started <= time.time(), "Invalid verification start time")
    require(inputs() == start.get("inputs"), "Source/build inputs changed after verification began; rerun the checks")
    version, minecraft = release_coordinates()
    reports = sorted((ROOT / "build/test-results/test").glob("TEST-*.xml"))
    require(bool(reports), "No JUnit reports")
    unit_cases = [case for report in reports for case in test_cases(report, started)]
    actual_unit = {case.attrib.get("classname", "") for case in unit_cases}
    expected_unit = expected_unit_classes()
    require(actual_unit == expected_unit,
            f"JUnit discovery mismatch: missing={sorted(expected_unit - actual_unit)}, unexpected={sorted(actual_unit - expected_unit)}")
    unit_execution = audit_unit_execution(unit_cases, started)
    server = test_cases(ROOT / "build/gametest-results.xml", started)
    names = [case.attrib["name"] for case in server]
    require(len(names) == len(set(names)), "Duplicate server-test results")
    actual = {name for name in names if name.startswith(MOD_TEST_PREFIX)}
    expected = expected_server_ids()
    require(actual == expected, f"Server discovery mismatch: missing={sorted(expected - actual)}, unexpected={sorted(actual - expected)}")
    require(set(names) - actual <= {"minecraft:always_pass"}, "Unexpected non-mod server-test results")
    result = {"schema": 1, "passed": True, "run_id": run_id, "verified_at": time.time(),
              "unit_tests": len(unit_cases), "unit_test_classes": len(actual_unit), "server_tests": len(server),
              "unit_test_methods": unit_execution["methods"], "unit_execution": unit_execution,
              "mod_server_tests": len(actual), "scope": "release" if release else "unit-and-server", "inputs": start["inputs"]}
    jar = ROOT / "build/libs" / f"fabricated-backpacks-{version}.jar"
    require(jar.is_file(), f"Expected current main release JAR: {jar.name}")
    result["artifact"] = audit_jar(jar, started, version, minecraft)
    if release:
        result["client"] = verify_clients(started, result["artifact"])
    return result


def write_atomic(path: Path, record: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name("." + path.name + "." + str(uuid.uuid4()) + ".tmp")
    with temporary.open("x", encoding="utf-8") as stream:
        json.dump(record, stream, indent=2, allow_nan=False)
        stream.write("\n")
    os.replace(temporary, path)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("begin", "check"))
    parser.add_argument("--release", action="store_true", help="Require full client, separate-JVM restart, multiplayer and installed-JAR manual evidence")
    args = parser.parse_args(argv)
    try:
        OUTPUT.mkdir(parents=True, exist_ok=True)
        if args.command == "begin":
            snapshot = inputs()
            record = {"schema": 1, "run_id": str(uuid.uuid4()), "started": time.time(), "inputs": snapshot}
            for stale in ("automated.json", "release.json"):
                (OUTPUT / stale).unlink(missing_ok=True)
            write_atomic(OUTPUT / "start.json", record)
            print(f"Verification started: {record['run_id']} ({len(snapshot)} input files)")
        else:
            target = OUTPUT / ("release.json" if args.release else "automated.json")
            target.unlink(missing_ok=True)
            record = verify(args.release)
            write_atomic(target, record)
            print(f"Verified {record['unit_tests']} unit and {record['server_tests']} server tests; scope={record['scope']}; sha256={record['artifact']['sha256']}")
        return 0
    except Exception as error:
        # Malformed XML/ZIP/JSON, missing fields, and filesystem/runtime failures
        # all reject the gate. Never leave a success receipt after such a failure.
        print(f"Verification rejected ({type(error).__name__}): {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
