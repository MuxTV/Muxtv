"""Validate C364 additional test output files and exact JUnit success."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

PACKAGE = "app.muxtv.database."
TESTS = {
    "probe": ("C03ProductionRoomTransportProbeTest", "transfersBoundedSyntheticPayload"),
    "canonical": ("C03ProductionRoomCanonicalEvidenceTest", "producesCanonicalProductionRoomEvidence"),
}
MAX_BYTES = 512 * 1024
MAX_MANIFEST_BYTES = 4096
TRANSPORT = "additional-output-v1"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def one(paths, name):
    paths = list(paths)
    require(len(paths) == 1, f"Expected exactly one {name}; found {len(paths)}")
    return paths[0]


def validate_junit(root, class_name, method):
    xml_path = one(root.rglob("TEST-*.xml"), "JUnit XML")
    require(xml_path.stat().st_size <= 1024 * 1024, "Unexpected JUnit XML size")
    report = ET.parse(xml_path).getroot()
    require(report.tag in ("testsuite", "testsuites"), "Unexpected JUnit root")
    cases = list(report.iter("testcase"))
    require(len(cases) == 1, "Expected exactly one test case")
    case = cases[0]
    require(case.get("classname") == PACKAGE + class_name and case.get("name") == method,
            "Unexpected test identity")
    require(not list(case), "Test case contains failure, error, skipped or unexpected result")
    for suite in report.iter():
        if suite.tag not in ("testsuite", "testsuites"):
            continue
        require(suite.get("tests") == "1", "Expected exactly one reported test")
        for key in ("failures", "errors", "skipped"):
            require(suite.get(key) == "0", f"JUnit {key} not zero")
    require((root / "test-result-exit-code.txt").read_text().strip() == "0",
            "Android test exit code not zero")
    return xml_path


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key")
        result[key] = value
    return result


def checked_file(path, root, maximum):
    require(path.is_file() and not path.is_symlink(), "Expected a regular transport file")
    require(path.resolve().is_relative_to(root.resolve()), "Transport path escapes output root")
    require(0 < path.stat().st_size <= maximum, "Transport file size outside bound")
    return path.read_bytes()


def extract(root, output_root, mode, expected_sha):
    require(re.fullmatch(r"[0-9a-f]{40}", expected_sha) is not None, "Invalid source SHA")
    class_name, method = TESTS[mode]
    xml = validate_junit(root, class_name, method)
    require(output_root.is_dir(), "AGP additional test output directory missing")
    manifest_name = f"c364-{mode}-manifest.json"
    payload_name = f"c364-{mode}-payload.bin"
    manifest_path = one(output_root.rglob(manifest_name), "transport manifest")
    payload_path = one(output_root.rglob(payload_name), "transport payload")
    require(manifest_path.parent == payload_path.parent, "Transport files not co-located")
    require(set(output_root.rglob("c364-*-manifest.json")) == {manifest_path},
            "Unexpected transport manifest from another phase")
    require(set(output_root.rglob("c364-*-payload.bin")) == {payload_path},
            "Unexpected transport payload from another phase")
    manifest = json.loads(checked_file(manifest_path, output_root, MAX_MANIFEST_BYTES),
                          object_pairs_hook=strict_object)
    expected_keys = {"schemaVersion", "transport", "sourceCommit", "mode",
                     "payloadBytes", "payloadSha256", "payloadName"}
    require(isinstance(manifest, dict) and set(manifest) == expected_keys,
            "Unexpected manifest schema")
    require(type(manifest["schemaVersion"]) is int and manifest["schemaVersion"] == 1,
            "Unsupported manifest schemaVersion")
    require(manifest["transport"] == TRANSPORT and manifest["mode"] == mode,
            "Wrong transport or phase")
    require(manifest["sourceCommit"] == expected_sha, "Transport source commit mismatch")
    require(manifest["payloadName"] == payload_name, "Unexpected payload filename")
    size = manifest["payloadBytes"]
    require(type(size) is int and 0 < size <= MAX_BYTES, "Payload size outside bound")
    digest = manifest["payloadSha256"]
    require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest),
            "Invalid payload SHA-256")
    data = checked_file(payload_path, output_root, MAX_BYTES)
    require(len(data) == size, "Payload length mismatch")
    require(hashlib.sha256(data).hexdigest() == digest, "Payload SHA-256 mismatch")
    if mode == "probe":
        require(data == bytes(i % 251 for i in range(200_000)), "Probe bytes mismatch")
    else:
        report = json.loads(data, object_pairs_hook=strict_object)
        require(isinstance(report, dict) and report.get("sourceCommit") == expected_sha,
                "Report source commit mismatch")
        require(report.get("redactionPassed") is True, "Report redaction missing")
        require(not any(token in data for token in (b"https://stream.invalid", b"token=", b"session=")),
                "Report contains forbidden marker")
    metadata = {**manifest, "junit": xml.relative_to(root).as_posix(),
                "manifest": manifest_path.relative_to(output_root).as_posix(),
                "payload": payload_path.relative_to(output_root).as_posix(),
                "testClass": PACKAGE + class_name, "testMethod": method}
    return data, metadata


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--additional-output", type=Path, required=True)
    parser.add_argument("--mode", choices=TESTS, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(not args.output.exists(), "Stale transport output already exists")
    if args.mode == "canonical":
        require(not args.output.with_suffix(".transport.json").exists(), "Stale manifest output")
    data, metadata = extract(args.root, args.additional_output, args.mode, args.source_commit)
    if args.mode == "probe":
        args.output.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    else:
        args.output.write_bytes(data)
        args.output.with_suffix(".transport.json").write_text(
            json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print("C364 transport PASS: " + json.dumps(metadata, sort_keys=True))


if __name__ == "__main__":
    main()
