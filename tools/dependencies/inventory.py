"""Exact-source declared dependency inventory; no network or Gradle execution."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tomllib


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def read(root, path):
    return (root / path).read_text(encoding="utf-8")


def actions_in(text):
    return re.findall(r"(?m)^\s*(?:-\s*)?uses:\s*['\"]?([^\s'\"#]+)", text)


def inventory(root, expected):
    if not re.fullmatch(r"[0-9a-f]{40}", expected):
        raise ValueError("Expected full lowercase source SHA")
    actual = git(root, "rev-parse", "HEAD").decode().strip()
    if actual != expected or git(root, "status", "--porcelain", "--untracked-files=no"):
        raise ValueError("Source SHA mismatch or modified tracked files")
    tracked = git(root, "ls-files", "-z").decode().split("\0")
    catalog = tomllib.loads(read(root, "gradle/libs.versions.toml"))
    versions = catalog["versions"]
    wrapper = read(root, "gradle/wrapper/gradle-wrapper.properties")
    url = re.search(r"(?m)^distributionUrl=(.+)$", wrapper).group(1).replace("\\:", ":")
    checksum = re.search(r"(?m)^distributionSha256Sum=([0-9a-fA-F]{64})$", wrapper)
    convention = read(root, "build-logic/convention/build.gradle.kts")
    for coordinate, key in [("com.android.tools.build:gradle", "agp"),
                            ("org.jetbrains.kotlin:kotlin-gradle-plugin", "kotlin")]:
        values = re.findall(r'"' + re.escape(coordinate) + r':([^"]+)"', convention)
        if values != [versions[key]]:
            raise ValueError("Included-build version drift: " + coordinate)
    workflows = []
    build_files = []
    for path in sorted(p for p in tracked if p):
        if path.startswith((".github/workflows/", ".github/actions/")) and path.endswith((".yml", ".yaml")):
            for ref in actions_in(read(root, path)):
                local = ref.startswith("./")
                pin = ref.rsplit("@", 1)[-1]
                workflows.append({"file": path, "uses": ref, "local": local,
                                  "fullSha": not local and bool(re.fullmatch(r"[0-9a-fA-F]{40}", pin))})
        if path.endswith((".gradle.kts", ".gradle")):
            build_files.append({"path": path, "sha256": hashlib.sha256((root / path).read_bytes()).hexdigest()})
    if not workflows or not build_files:
        raise ValueError("Empty workflow or build-file inventory")
    browser = "tools/local-control/browser-probe/package.json"
    return {"schemaVersion": 1, "sourceCommit": actual, "scope": "declared-only",
            "versions": versions, "libraries": catalog["libraries"], "plugins": catalog["plugins"],
            "wrapper": {"url": url, "distributionSha256": checksum.group(1).lower() if checksum else None,
                        "jarSha256": hashlib.sha256((root / "gradle/wrapper/gradle-wrapper.jar").read_bytes()).hexdigest()},
            "includedBuild": {"agp": versions["agp"], "kotlin": versions["kotlin"]},
            "actions": workflows, "buildFiles": build_files,
            "browserProbe": json.loads(read(root, browser))["dependencies"],
            "limitations": ["Catalog entries are declarations, not selected versions or usage.",
                            "Build file hashes identify inputs; they do not enumerate all plugin classpaths.",
                            "Action pin detection does not authenticate upstream commits.",
                            "Security advisories and latest versions are not evaluated by this collector."]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    report = inventory(root, args.source_commit)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
