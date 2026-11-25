"""Extract a single complete C364 logcat-v1 payload, gated by JUnit success."""
import argparse
import base64
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
CHUNK_SIZE = 2800
MAX_BYTES = 512 * 1024
MAX_LOG_BYTES = 8 * 1024 * 1024
HEX = r"[0-9a-f]{64}"
LINE = re.compile(r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+(\d+)\s+I\s+MuxTVC364\s*:\s+(.*)$")
BEGIN = re.compile(r"C364-V1 BEGIN ([0-9a-f]{40}) ([1-9][0-9]{0,5}) ([1-9][0-9]{0,3}) (" + HEX + ")")
CHUNK = re.compile(r"C364-V1 CHUNK (0|[1-9][0-9]{0,3}) ([A-Za-z0-9+/=]+)")
END = re.compile(r"C364-V1 END (" + HEX + ")")


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


def decode_log(lines, expected_sha):
    header = None
    owner = None
    chunks = []
    ended = False
    for line in lines:
        match = LINE.fullmatch(line.rstrip("\r\n"))
        if not match:
            # A malformed transport entry must not silently disappear.
            require("MuxTVC364" not in line or "C364-V1" not in line,
                    "Malformed C364 transport log entry")
            continue
        pid, tid, msg = match.groups()
        require(not ended, "Unexpected transport record after END")
        if header is None:
            header = BEGIN.fullmatch(msg)
            require(header is not None, "Missing or malformed BEGIN")
            require(header[1] == expected_sha, "Transport source commit mismatch")
            size, count = int(header[2]), int(header[3])
            require(1 <= size <= MAX_BYTES, "Payload outside allowed size")
            encoded_size = 4 * ((size + 2) // 3)
            require(count == (encoded_size + CHUNK_SIZE - 1) // CHUNK_SIZE,
                    "Chunk count inconsistent with payload size")
            owner = (pid, tid)
            continue
        require((pid, tid) == owner, "Transport process/thread changed")
        part = CHUNK.fullmatch(msg)
        if part:
            require(len(chunks) < count and int(part[1]) == len(chunks),
                    "Missing, duplicated or out-of-order chunk")
            expected_length = min(CHUNK_SIZE, encoded_size - len(chunks) * CHUNK_SIZE)
            require(len(part[2]) == expected_length, "Wrong chunk length")
            chunks.append(part[2])
            continue
        end = END.fullmatch(msg)
        require(end is not None and end[1] == header[4], "Malformed or mismatched END")
        require(len(chunks) == count, "Incomplete chunk sequence")
        ended = True
    require(header is not None and ended, "Transport incomplete")
    data = base64.b64decode("".join(chunks), validate=True)
    require(len(data) == size, "Payload length mismatch")
    require(hashlib.sha256(data).hexdigest() == header[4], "Payload SHA-256 mismatch")
    return data


def extract(root, mode, expected_sha):
    require(re.fullmatch(r"[0-9a-f]{40}", expected_sha) is not None, "Invalid source SHA")
    class_name, method = TESTS[mode]
    xml = validate_junit(root, class_name, method)
    log = one(root.rglob(f"logcat-{PACKAGE}{class_name}-{method}.txt"), "per-test logcat")
    require(log.stat().st_size <= MAX_LOG_BYTES, "Per-test logcat exceeds evidence bound")
    with log.open(encoding="utf-8", errors="strict") as stream:
        data = decode_log(stream, expected_sha)
    if mode == "probe":
        require(data == bytes(i % 251 for i in range(200_000)), "Probe bytes mismatch")
    else:
        report = json.loads(data)
        require(isinstance(report, dict) and report.get("sourceCommit") == expected_sha,
                "Report source commit mismatch")
        require(report.get("redactionPassed") is True, "Report redaction missing")
    metadata = {"transport": "logcat-v1", "sourceCommit": expected_sha, "mode": mode,
                "payloadBytes": len(data), "payloadSha256": hashlib.sha256(data).hexdigest(),
                "junit": xml.relative_to(root).as_posix(), "logcat": log.relative_to(root).as_posix(),
                "testClass": PACKAGE + class_name, "testMethod": method}
    return data, metadata


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--mode", choices=TESTS, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(not args.output.exists(), "Stale transport output already exists")
    data, metadata = extract(args.root, args.mode, args.source_commit)
    if args.mode == "probe":
        args.output.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    else:
        args.output.write_bytes(data)
        args.output.with_suffix(".transport.json").write_text(
            json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print("C364 transport PASS: " + json.dumps(metadata, sort_keys=True))


if __name__ == "__main__":
    main()
