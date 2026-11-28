"""Normalize verified selected Maven GAVs, retaining exact graph membership."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import quote

from verify_resolved import combine, require

COORDINATE = re.compile(r"[A-Za-z0-9_.-]+")
# Selected Maven versions may contain punctuation; encode delimiters, never
# normalize case or silently trim. Reject whitespace/control characters.
def maven_purl(group, name, version):
    for value in (group, name):
        require(isinstance(value, str) and bool(COORDINATE.fullmatch(value))
                and value not in {".", ".."}, "Invalid Maven group or name")
    require(isinstance(version, str) and bool(version)
            and all(ch.isprintable() and not ch.isspace() for ch in version),
            "Invalid Maven version")
    return f"pkg:maven/{quote(group, safe='')}/{quote(name, safe='')}@{quote(version, safe='')}"


def normalize(declared, reports, resolved_bytes, expected):
    resolved = json.loads(resolved_bytes)
    require(resolved == combine(declared, reports, expected),
            "Combined graph differs from validated app reports")
    packages = {}
    scope_counts = []
    for graph in resolved["graphs"]:
        project = graph["project"]
        configuration = graph["configuration"]
        root = graph["rootComponent"]
        release = configuration == "releaseRuntimeClasspath"
        direct = {e["selected"] for e in graph["edges"]
                  if e["from"] == root and not e["constraint"]}
        constraints = {e["selected"] for e in graph["edges"]
                       if e["from"] == root and e["constraint"]}
        scope_purls = set()
        other_count = 0
        for component in graph["components"]:
            if component["kind"] != "module":
                other_count += 1
                continue
            group, name, version = (component[k] for k in ("group", "name", "version"))
            purl = maven_purl(group, name, version)
            require(purl not in scope_purls, "Duplicate Maven GAV in one graph")
            scope_purls.add(purl)
            cid = component["id"]
            entry = packages.setdefault(purl, {
                "purl": purl, "group": group, "name": name, "version": version,
                "securityStatus": "NOT_SCANNED", "usages": []})
            entry["usages"].append({
                "project": project, "configuration": configuration,
                "releaseRuntime": release, "rootDependencyEdge": cid in direct,
                "rootConstraintEdge": cid in constraints,
            })
        scope_counts.append({"project": project, "configuration": configuration,
                             "mavenPackageCount": len(scope_purls),
                             "nonMavenComponentCount": other_count})
    versions_by_package = defaultdict(set)
    for package in packages.values():
        package["usages"].sort(key=lambda u: (u["project"], u["configuration"]))
        package["inAnyReleaseRuntime"] = any(u["releaseRuntime"] for u in package["usages"])
        versions_by_package[(package["group"], package["name"])].add(package["version"])
    multiple = [{"group": g, "name": n, "versions": sorted(versions)}
                for (g, n), versions in sorted(versions_by_package.items()) if len(versions) > 1]
    result = [packages[key] for key in sorted(packages)]
    return {
        "schemaVersion": 1, "sourceCommit": expected,
        "scope": "selected-app-runtime-maven-components",
        "resolvedGraphSha256": hashlib.sha256(resolved_bytes).hexdigest(),
        "gradleVersion": resolved["gradleVersion"],
        "securityStatus": "NOT_SCANNED",
        "packageCount": len(result),
        "releaseRuntimePackageCount": sum(p["inAnyReleaseRuntime"] for p in result),
        "scopeCounts": scope_counts, "multipleSelectedVersions": multiple,
        "packages": result,
        "limitations": [
            "Selected graph membership is not APK packaging or reachable code.",
            "Root dependency edge is relative to the app graph, not every module's direct declarations.",
            "Root constraints are distinct from dependency edges; BOMs may be selected components.",
            "Non-Maven components are counted, not treated as external Maven packages.",
            "Build/plugin/benchmark and npm graphs are outside this scope.",
            "Multiple versions across configurations are observations, not proven conflicts.",
            "No advisory query was performed.",
        ],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    directory = args.directory
    output = directory / "maven-packages.json"
    output.unlink(missing_ok=True)
    def load(name):
        return json.loads((directory / name).read_bytes())
    report = normalize(load("declared-versions.json"),
                       {app: load(f"resolved-{app}.json") for app in ("tv", "phone")},
                       (directory / "resolved-dependencies.json").read_bytes(),
                       args.source_commit)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Normalized {report['packageCount']} Maven GAVs; "
          f"{report['releaseRuntimePackageCount']} in release graphs; NOT_SCANNED")


if __name__ == "__main__":
    main()
