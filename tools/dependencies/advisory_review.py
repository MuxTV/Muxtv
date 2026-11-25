"""Join exact-source version matches to OSV detail and selected dependency paths."""
import argparse
from collections import defaultdict, deque
from datetime import datetime
import hashlib
import json
from pathlib import Path
import time
from urllib.parse import quote
from urllib.request import Request

from build_inventory import REPORTS, inventory as build_inventory
from normalize import maven_purl, normalize
from osv_scan import ADVISORY_ID, ScanError, URL, fetch_json, utc_now
from verify_resolved import require

DETAIL_URL = "https://api.osv.dev/v1/vulns/"
MAX_IDS = 200
MAX_SECONDS = 600
STATUSES = {"ADVISORY_FOUND", "NO_KNOWN_ADVISORIES", "SCAN_FAILED", "NOT_SCANNED"}


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def stamp(value):
    require(isinstance(value, str), "Missing advisory timestamp")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(parsed.tzinfo is not None, "Naive advisory timestamp")
    return parsed


def fetch_detail(vid):
    require(bool(ADVISORY_ID.fullmatch(vid)), "Invalid advisory ID")
    return fetch_json(Request(DETAIL_URL + quote(vid, safe=""),
                              headers={"Accept": "application/json"}, method="GET"))


def checked_matches(scan, normalized, raw, expected, hash_key):
    require(scan.get("schemaVersion") == 1 and scan.get("sourceCommit") == expected
            and scan.get("source") == URL and scan.get("scope") == normalized["scope"]
            and scan.get(hash_key) == digest(raw)
            and scan.get("scanStatus") == "COMPLETE" and scan.get("errors") == [],
            "Scan provenance or coverage mismatch")
    if hash_key == "mavenPackagesSha256":
        require(scan.get("resolvedGraphSha256") == normalized["resolvedGraphSha256"],
                "Runtime graph hash mismatch")
    packages = {p["purl"]: p for p in normalized["packages"]}
    seen = set()
    ids = set()
    counts = dict.fromkeys(STATUSES, 0)
    matches = []
    for row in scan["packages"]:
        purl = row["purl"]
        require(purl in packages and purl not in seen, "Unexpected or duplicate scanned PURL")
        seen.add(purl)
        package = packages[purl]
        require(row["usages"] == package["usages"]
                and row["inAnyReleaseRuntime"] == package["inAnyReleaseRuntime"],
                "Scan usage mismatch")
        status = row["status"]
        require(status in {"ADVISORY_FOUND", "NO_KNOWN_ADVISORIES"}, "Incomplete package scan")
        counts[status] += 1
        advisories = row["advisories"]
        require(isinstance(advisories, list) and bool(advisories) == (status == "ADVISORY_FOUND"),
                "Scan status/advisories mismatch")
        revisions = set()
        for advisory in advisories:
            vid, modified = advisory["id"], advisory["modified"]
            require(isinstance(vid, str) and bool(ADVISORY_ID.fullmatch(vid)), "Invalid advisory ID")
            timestamp = stamp(modified)
            require((vid, timestamp) not in revisions, "Duplicate advisory revision")
            revisions.add((vid, timestamp))
            ids.add(vid)
            matches.append((package, vid, modified))
    require(seen == set(packages) and counts == scan["counts"]
            and sorted(ids) == scan["distinctAdvisoryIds"], "Incomplete or inconsistent scan summary")
    return matches


def graph_key(graph):
    return (graph.get("build", "main"), graph["project"], graph["configuration"])


def graph_paths(graph):
    """One deterministic shortest non-constraint path per reachable node."""
    adjacency = defaultdict(set)
    parents = defaultdict(set)
    constraints = defaultdict(set)
    for edge in graph["edges"]:
        parent, child = edge["from"], edge["selected"]
        if edge["constraint"]:
            constraints[child].add(parent)
        else:
            adjacency[parent].add(child)
            parents[child].add(parent)
    root = graph["rootComponent"]
    previous = {root: None}
    queue = deque([root])
    while queue:
        parent = queue.popleft()
        for child in sorted(adjacency[parent]):
            if child not in previous:
                previous[child] = parent
                queue.append(child)
    return previous, parents, constraints


def package_paths(package, graphs, origin):
    results = []
    for usage in package["usages"]:
        key = (usage.get("build", "main"), usage["project"], usage["configuration"])
        graph = graphs[(origin, key)]
        nodes = [c["id"] for c in graph["components"] if c["kind"] == "module"
                 and maven_purl(c["group"], c["name"], c["version"]) == package["purl"]]
        require(len(nodes) == 1, "Matched package missing or ambiguous in usage graph")
        node = nodes[0]
        previous, parents, constraints = graph_paths(graph)
        path = []
        if node in previous:
            current = node
            while current is not None:
                path.append(current)
                current = previous[current]
            path.reverse()
        results.append({"origin": origin, **usage, "shortestDependencyPath": path,
                        "pathStatus": "FOUND" if path else "NO_NON_CONSTRAINT_PATH",
                        "incomingDependencyParents": sorted(parents[node]),
                        "incomingConstraintParents": sorted(constraints[node])})
    return results


def detail_for(record, vid, modified, package):
    require(isinstance(record, dict) and record.get("id") == vid, "Advisory identity mismatch")
    require(stamp(record.get("modified")) == stamp(modified), "Advisory revision changed; rescan")
    withdrawn = record.get("withdrawn")
    if withdrawn is not None:
        stamp(withdrawn)
    aliases = record.get("aliases", [])
    require(isinstance(aliases, list) and all(isinstance(x, str) and ADVISORY_ID.fullmatch(x)
                                              for x in aliases), "Invalid advisory aliases")
    affected = record.get("affected", [])
    require(isinstance(affected, list), "Invalid affected records")
    name = package["group"] + ":" + package["name"]
    relevant = [a for a in affected if a.get("package", {}).get("ecosystem") == "Maven"
                and a.get("package", {}).get("name") == name]
    require(bool(relevant) or withdrawn is not None, "No affected Maven package in detail")
    fixed = set()
    for entry in relevant:
        for interval in entry.get("ranges", []):
            if interval.get("type") in {"ECOSYSTEM", "SEMVER"}:
                for event in interval.get("events", []):
                    if "fixed" in event:
                        require(isinstance(event["fixed"], str) and bool(event["fixed"]),
                                "Invalid fixed version")
                        fixed.add(event["fixed"])
    return {"aliases": sorted(set(aliases)), "withdrawn": withdrawn,
            "affectedPackageRecords": relevant, "reportedFixBoundaries": sorted(fixed),
            "severity": record.get("severity", []),
            "databaseSpecific": record.get("database_specific", {}),
            "reviewStatus": "WITHDRAWN_REVIEW_REQUIRED" if withdrawn else "APPLICABILITY_REVIEW_REQUIRED"}


def alias_groups(details):
    # Connected components by exact ID/alias identity, not by related/upstream.
    links = defaultdict(set)
    for vid, record in details.items():
        links[vid]
        for alias in record.get("aliases", []):
            links[vid].add(alias)
            links[alias].add(vid)
    groups = []
    seen = set()
    for vid in sorted(links):
        if vid in seen:
            continue
        queue = [vid]
        seen.add(vid)
        members = []
        while queue:
            node = queue.pop()
            members.append(node)
            for child in links[node] - seen:
                seen.add(child)
                queue.append(child)
        groups.append({"idsAndAliases": sorted(members),
                       "matchedAdvisoryIds": sorted(set(members) & set(details))})
    return groups


def review(directory, expected, fetch=fetch_detail, clock=time.monotonic):
    def read(name):
        return (directory / name).read_bytes()

    def load(name):
        return json.loads(read(name))

    resolved_raw = read("resolved-dependencies.json")
    resolved = json.loads(resolved_raw)
    runtime_raw = read("maven-packages.json")
    runtime = json.loads(runtime_raw)
    require(runtime == normalize(load("declared-versions.json"),
                                   {app: load(f"resolved-{app}.json") for app in ("tv", "phone")},
                                   resolved_raw, expected), "Runtime inventory mismatch")
    build_inputs = {name: read(name) for name in REPORTS}
    build_raw = read("build-maven-packages.json")
    build = json.loads(build_raw)
    require(build == build_inventory(build_inputs, expected, resolved["gradleVersion"]),
            "Build inventory mismatch")
    graphs = {("runtime", graph_key(g)): g for g in resolved["graphs"]}
    for raw in build_inputs.values():
        for graph in json.loads(raw)["graphs"]:
            key = ("build", graph_key(graph))
            require(key not in graphs, "Duplicate build graph")
            graphs[key] = graph
    inputs = {"security-findings.json": read("security-findings.json"),
              "build-security-findings.json": read("build-security-findings.json")}
    matches = []
    for origin, name, normalized, raw, hash_key in (
            ("runtime", "security-findings.json", runtime, runtime_raw, "mavenPackagesSha256"),
            ("build", "build-security-findings.json", build, build_raw, "buildPackagesSha256")):
        for package, vid, modified in checked_matches(json.loads(inputs[name]), normalized, raw, expected, hash_key):
            matches.append((origin, package, vid, modified))
    ids = sorted({vid for _, _, vid, _ in matches})
    require(len(ids) <= MAX_IDS, "Advisory count exceeds review bounds")
    started = utc_now()
    deadline = clock() + MAX_SECONDS
    records = {}
    errors = {}
    for vid in ids:
        if clock() >= deadline:
            errors[vid] = "DETAIL_TIME_BUDGET"
            continue
        try:
            record = fetch(vid)
            require(isinstance(record, dict) and record.get("id") == vid, "Advisory identity mismatch")
            # Validate aliases before admitting the raw record to the alias graph.
            aliases = record.get("aliases", [])
            require(isinstance(aliases, list) and all(isinstance(a, str) and ADVISORY_ID.fullmatch(a)
                                                     for a in aliases), "Invalid aliases")
            records[vid] = record
        except (ScanError, ValueError, TypeError, KeyError):
            errors[vid] = "DETAIL_FETCH_OR_VALIDATION_FAILED"
    findings = []
    for origin, package, vid, modified in matches:
        paths = package_paths(package, graphs, origin)
        row = {"purl": package["purl"], "advisoryId": vid, "matchedModified": modified,
               "paths": paths, "inAnyReleaseRuntime": package["inAnyReleaseRuntime"],
               "reviewStatus": "DETAIL_UNAVAILABLE"}
        if vid in records:
            try:
                row.update(detail_for(records[vid], vid, modified, package))
            except (ValueError, TypeError, KeyError, AttributeError):
                errors[vid] = "DETAIL_REVISION_OR_PACKAGE_MISMATCH"
        findings.append(row)
    findings.sort(key=lambda r: (not r["inAnyReleaseRuntime"], r["purl"], r["advisoryId"],
                                 r["paths"][0]["origin"]))
    return {"schemaVersion": 1, "sourceCommit": expected, "source": DETAIL_URL,
            "startedAt": started, "finishedAt": utc_now(), "reviewStatus": "INCOMPLETE" if errors else "COMPLETE",
            "inputSha256": {name: digest(raw) for name, raw in inputs.items()},
            "advisoryCount": len(ids), "matchedRows": len(findings),
            "errors": errors, "aliasGroups": alias_groups(records), "advisoryRecords": records,
            "findings": findings,
            "limitations": [
                "OSV detail is time-dependent; a changed revision requires a fresh version scan.",
                "Fixed boundaries are reported, not compared as Maven versions or asserted compatible.",
                "Alias groups are not independently verified CVE counts.",
                "One shortest dependency path per graph and all immediate parents; not all paths.",
                "Graph path is not execution or exploit reachability; no automatic applicability verdict.",
                "Only the selected Maven graph scope is covered; Gradle distribution advisories are separate.",
            ]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    output = args.directory / "advisory-review.json"
    output.unlink(missing_ok=True)
    result = review(args.directory, args.source_commit)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("Advisory detail review", result["reviewStatus"], "IDs", result["advisoryCount"],
          "matched rows", result["matchedRows"])
    for row in result["findings"]:
        # Escape externally controlled fields rather than emitting raw workflow commands.
        print("Advisory review", json.dumps({k: row[k] for k in
              ("purl", "advisoryId", "inAnyReleaseRuntime", "reviewStatus")}, ensure_ascii=True),
              "fixed boundaries", json.dumps(row.get("reportedFixBoundaries", []), ensure_ascii=True),
              "paths", json.dumps([p["shortestDependencyPath"] for p in row["paths"]], ensure_ascii=True))
    if result["reviewStatus"] != "COMPLETE":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
