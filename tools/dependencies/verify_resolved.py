"""Independently validate and combine the two exact-source Gradle graph reports."""
import argparse
import json
from pathlib import Path
import re

CONFIGURATIONS = {
    "releaseRuntimeClasspath",
    "debugUnitTestRuntimeClasspath",
    "debugAndroidTestRuntimeClasspath",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def nonempty_string(value):
    return isinstance(value, str) and bool(value.strip())


def combine(declared, reports, expected):
    require(bool(re.fullmatch(r"[0-9a-f]{40}", expected)), "Invalid expected SHA")
    require(declared.get("schemaVersion") == 1
            and declared.get("scope") == "declared-only"
            and declared.get("sourceCommit") == expected, "Declared inventory provenance mismatch")
    require(set(reports) == {"tv", "phone"}, "Expected both app reports")
    graphs = []
    versions = set()
    for app, report in sorted(reports.items()):
        require(report.get("schemaVersion") == 1
                and report.get("scope") == "selected-app-runtime-graphs"
                and report.get("sourceCommit") == expected, f"{app}: provenance mismatch")
        require(type(report.get("unresolvedCount")) is int
                and report["unresolvedCount"] == 0, f"{app}: unresolved dependencies")
        version = report.get("gradleVersion")
        require(nonempty_string(version), f"{app}: missing Gradle version")
        versions.add(version)
        seen = set()
        for graph in report["graphs"]:
            name = graph["configuration"]
            require(graph["project"] == f":app:{app}" and name in CONFIGURATIONS
                    and name not in seen, f"{app}: wrong or duplicate graph")
            seen.add(name)
            ids = set()
            modules = 0
            for component in graph["components"]:
                cid = component.get("id")
                require(nonempty_string(cid) and cid not in ids, f"{app}/{name}: invalid component id")
                ids.add(cid)
                kind = component.get("kind")
                require(kind in {"module", "other"}, f"{app}/{name}: unknown component kind")
                if kind == "module":
                    require(all(nonempty_string(component.get(k)) for k in ("group", "name", "version")),
                            f"{app}/{name}: missing module coordinates")
                    modules += 1
            require(modules > 0 and bool(graph["edges"]), f"{app}/{name}: empty graph")
            require(any(c["id"] == graph.get("rootComponent") and c["kind"] == "other"
                        for c in graph["components"]), f"{app}/{name}: invalid root component")
            for edge in graph["edges"]:
                require(not edge.get("unresolved", False)
                        and edge.get("from") in ids and edge.get("selected") in ids
                        and nonempty_string(edge.get("requested"))
                        and type(edge.get("constraint")) is bool,
                        f"{app}/{name}: invalid or unresolved edge")
            graphs.append(graph)
        require(seen == CONFIGURATIONS, f"{app}: incomplete graph coverage")
    require(len(versions) == 1, "Gradle version mismatch")
    return {"schemaVersion": 1, "sourceCommit": expected, "gradleVersion": versions.pop(),
            "scope": "selected-app-runtime-graphs", "unresolvedCount": 0,
            "graphs": sorted(graphs, key=lambda g: (g["project"], g["configuration"]))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--directory", required=True, type=Path)
    args = parser.parse_args()
    directory = args.directory
    output = directory / "resolved-dependencies.json"
    output.unlink(missing_ok=True)

    def load(name):
        return json.loads((directory / name).read_text(encoding="utf-8"))

    result = combine(load("declared-versions.json"),
                     {app: load(f"resolved-{app}.json") for app in ("tv", "phone")},
                     args.source_commit)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Validated {len(result['graphs'])} exact-source graphs; zero unresolved edges")


if __name__ == "__main__":
    main()
