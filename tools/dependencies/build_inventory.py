"""Validate selected build graphs and scan them independently of APK graphs."""
import argparse
import hashlib
import json
import re
from pathlib import Path

from normalize import maven_purl
from osv_scan import scan, utc_now, URL
from verify_resolved import require, nonempty_string

REPORTS = {
    "build-main-root.json": {("main", ":", "buildscript:classpath")},
    "build-main-tv.json": {("main", ":app:tv", "buildscript:classpath")},
    "build-main-database.json": set(),
    "build-main-jvm-benchmark.json": {("main", ":benchmark:jvm", "jmhRuntimeClasspath")},
    "build-build-logic-convention.json": {
        ("build-logic", ":convention", "runtimeClasspath"),
        ("build-logic", ":convention", "testRuntimeClasspath"),
    },
}
# The included convention plugin explicitly declares these implementation dependencies.
CONVENTION_ANCHORS = {("com.android.tools.build", "gradle"),
                      ("org.jetbrains.kotlin", "kotlin-gradle-plugin")}


PROCESSOR_OWNERS = {
    "build-main-tv.json": (":app:tv", {("com.google.dagger", "hilt-compiler"),
                                     ("androidx.hilt", "hilt-compiler")}),
    "build-main-database.json": (":core:database", {("androidx.room3", "room3-compiler")}),
}
PROCESSOR_NAME = re.compile(r"ksp[A-Za-z0-9_]*ProcessorClasspath")


def processor_variant(name):
    # Current Android variant classpaths, excluding test compilations.
    tail = name.removeprefix("ksp").removesuffix("ProcessorClasspath").lower()
    if "test" not in tail:
        for variant in ("debug", "release"):
            if tail.startswith(variant):
                return variant
    return None


def inventory(inputs, expected, gradle_version):
    require(set(inputs) == set(REPORTS), "Missing or unexpected build report")
    packages = {}
    hashes = {}
    coverage = []
    for filename, wanted in sorted(REPORTS.items()):
        raw = inputs[filename]
        hashes[filename] = hashlib.sha256(raw).hexdigest()
        report = json.loads(raw)
        require(report.get("schemaVersion") == 1 and report.get("sourceCommit") == expected
                and report.get("scope") == "selected-build-graphs"
                and report.get("gradleVersion") == gradle_version,
                "Build report provenance mismatch")
        require(type(report.get("unresolvedCount")) is int and report["unresolvedCount"] == 0,
                "Unresolved build dependencies")
        discovered = report.get("discoveredProcessorConfigurations")
        require(isinstance(discovered, list) and all(isinstance(n, str) and PROCESSOR_NAME.fullmatch(n)
                                                    for n in discovered)
                and len(discovered) == len(set(discovered)), "Invalid KSP discovery metadata")
        wanted = set(wanted)
        processor_owner = PROCESSOR_OWNERS.get(filename)
        if processor_owner:
            require(bool(discovered), "No discovered KSP processor classpaths")
            wanted.update(("main", processor_owner[0], n) for n in discovered)
        else:
            require(not discovered, "Unexpected KSP configuration")
        variant_anchors = set()
        seen = set()
        for graph in report["graphs"]:
            key = (graph["build"], graph["project"], graph["configuration"])
            require(key in wanted and key not in seen, "Wrong or duplicate build graph")
            seen.add(key)
            ids = set()
            modules = set()
            for component in graph["components"]:
                cid = component.get("id")
                require(nonempty_string(cid) and cid not in ids, "Invalid build component ID")
                ids.add(cid)
                require(component.get("kind") in {"module", "other"}, "Unknown build component kind")
            root = graph.get("rootComponent")
            is_processor = bool(PROCESSOR_NAME.fullmatch(key[2]))
            require(root in ids and (bool(graph["edges"]) or is_processor), "Empty or invalid build graph")
            direct = set()
            for edge in graph["edges"]:
                require(not edge.get("unresolved", False) and edge.get("from") in ids
                        and edge.get("selected") in ids and nonempty_string(edge.get("requested"))
                        and type(edge.get("constraint")) is bool, "Invalid build edge")
                if edge["from"] == root and not edge["constraint"]:
                    direct.add(edge["selected"])
            purls = set()
            for component in graph["components"]:
                if component["kind"] != "module":
                    continue
                g, n, v = (component[k] for k in ("group", "name", "version"))
                purl = maven_purl(g, n, v)
                require(purl not in purls, "Duplicate build Maven GAV")
                purls.add(purl)
                modules.add((g, n))
                entry = packages.setdefault(purl, {"purl": purl, "group": g, "name": n, "version": v,
                                                    "inAnyReleaseRuntime": False, "usages": []})
                entry["usages"].append({"build": key[0], "project": key[1], "configuration": key[2],
                                        "releaseRuntime": False, "rootDependencyEdge": component["id"] in direct,
                                        "scope": ("KSP_PROCESSOR" if is_processor else
                                                  "BENCHMARK" if key[2] == "jmhRuntimeClasspath" else
                                                  "BUILD_TEST" if key[2] == "testRuntimeClasspath" else "BUILD")})
            if is_processor:
                variant = processor_variant(key[2])
                if variant:
                    require(processor_owner[1] <= modules, "Missing expected compiler anchors in " + key[2])
                    variant_anchors.add(variant)
                # Empty processor classpaths are allowed only outside required main variants.
            else:
                require(bool(purls), "No selected Maven modules in build graph")
            if key[2] == "jmhRuntimeClasspath":
                require(("org.openjdk.jmh", "jmh-core") in modules, "Missing JMH core anchor")
            if key[0] == "build-logic":
                require(CONVENTION_ANCHORS <= modules, "Missing convention AGP/Kotlin anchors")
            coverage.append({"build": key[0], "project": key[1], "configuration": key[2],
                             "mavenPackageCount": len(purls)})
        require(seen == wanted, "Incomplete build graph coverage")
        if processor_owner:
            require(variant_anchors == {"debug", "release"}, "Missing debug/release KSP compiler coverage")
    return {"schemaVersion": 1, "sourceCommit": expected, "gradleVersion": gradle_version,
            "scope": "selected-build-maven-components", "inputSha256": hashes,
            "scopeCounts": sorted(coverage, key=lambda x: (x["build"], x["project"], x["configuration"])),
            "packageCount": len(packages), "packages": [packages[p] for p in sorted(packages)],
            "securityStatus": "NOT_SCANNED",
            "limitations": ["Selected build graphs only, not all plugin classloaders or artifact bytes.",
                            "Settings plugins, other project classpaths, macrobenchmark and npm remain outside scope.",
                            "Build-only labels do not assert absence from application graphs. Join by PURL."]}


def findings(normalized, raw, fetch=None):
    started = utc_now()
    records, errors = scan(normalized["packages"], fetch) if fetch is not None else scan(normalized["packages"])
    counts = {status: sum(r["status"] == status for r in records) for status in
              ("NOT_SCANNED", "SCAN_FAILED", "ADVISORY_FOUND", "NO_KNOWN_ADVISORIES")}
    complete = not errors and not counts["NOT_SCANNED"] and not counts["SCAN_FAILED"]
    return {"schemaVersion": 1, "sourceCommit": normalized["sourceCommit"], "source": URL,
            "scope": normalized["scope"], "buildPackagesSha256": hashlib.sha256(raw).hexdigest(),
            "startedAt": started, "finishedAt": utc_now(), "scanStatus": "COMPLETE" if complete else "INCOMPLETE",
            "counts": counts, "errors": errors, "packages": records,
            "distinctAdvisoryIds": sorted({a["id"] for r in records for a in r["advisories"]}),
            "limitations": normalized["limitations"] + ["Version matches only; severity and applicability require review."]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    d = args.directory
    package_path = d / "build-maven-packages.json"
    findings_path = d / "build-security-findings.json"
    for p in (package_path, findings_path):
        p.unlink(missing_ok=True)
    runtime = json.loads((d / "resolved-dependencies.json").read_bytes())
    require(runtime.get("sourceCommit") == args.source_commit and runtime.get("unresolvedCount") == 0,
            "Runtime provenance mismatch")
    normalized = inventory({name: (d / name).read_bytes() for name in REPORTS},
                           args.source_commit, runtime["gradleVersion"])
    raw = (json.dumps(normalized, indent=2, sort_keys=True) + "\n").encode()
    package_path.write_bytes(raw)
    print(f"Validated {len(normalized['scopeCounts'])} build graphs; {normalized['packageCount']} Maven GAVs")
    result = findings(normalized, raw)
    findings_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("Build OSV scan", result["scanStatus"], json.dumps(result["counts"], sort_keys=True))
    for row in result["packages"]:
        for advisory in row["advisories"]:
            print("Build OSV match", row["purl"], advisory["id"])
    if result["scanStatus"] != "COMPLETE":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
