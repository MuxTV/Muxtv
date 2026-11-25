"""Bounded OSV version-match evidence for validated selected Maven packages."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener

from normalize import normalize
from verify_resolved import require

URL = "https://api.osv.dev/v1/querybatch"
BATCH_SIZE = 50
MAX_PAGES = 12
MAX_BYTES = 8 * 1024 * 1024
MAX_ATTEMPTS = 3
TIMEOUT = 30
MAX_PACKAGES = 5000
MAX_SECONDS = 600
ADVISORY_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}")


class ScanError(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def fetch_batch(queries):
    """Only public Maven coordinates are transmitted. No auth or redirects."""
    request = Request(URL, data=json.dumps({"queries": queries}).encode("utf-8"),
                      headers={"Content-Type": "application/json", "Accept": "application/json"},
                      method="POST")
    for attempt in range(MAX_ATTEMPTS):
        try:
            with build_opener(NoRedirect()).open(request, timeout=TIMEOUT) as response:
                raw = response.read(MAX_BYTES + 1)
                if len(raw) > MAX_BYTES:
                    raise ScanError("RESPONSE_SIZE_LIMIT")
                try:
                    return json.loads(raw)
                except (ValueError, UnicodeError) as exc:
                    raise ScanError("INVALID_JSON") from exc
        except HTTPError as exc:
            if exc.code not in {408, 429, 500, 502, 503, 504}:
                raise ScanError(f"HTTP_{exc.code}") from exc
            reason = f"HTTP_{exc.code}"
        except (URLError, TimeoutError, OSError) as exc:
            reason = "TRANSPORT_ERROR"
        if attempt + 1 < MAX_ATTEMPTS:
            time.sleep(2 ** attempt)
    raise ScanError(reason)


def decode_batch(response, count):
    if not isinstance(response, dict) or set(response) != {"results"}:
        raise ScanError("INVALID_RESPONSE_ENVELOPE")
    results = response["results"]
    if not isinstance(results, list) or len(results) != count:
        raise ScanError("RESULT_COUNT_MISMATCH")
    decoded = []
    for item in results:
        if not isinstance(item, dict) or set(item) - {"vulns", "next_page_token"}:
            raise ScanError("INVALID_RESULT")
        vulns = item.get("vulns", [])
        if not isinstance(vulns, list):
            raise ScanError("INVALID_VULNERABILITIES")
        matches = []
        for vuln in vulns:
            if not isinstance(vuln, dict):
                raise ScanError("INVALID_ADVISORY")
            vid, modified = vuln.get("id"), vuln.get("modified")
            if not isinstance(vid, str) or not ADVISORY_ID.fullmatch(vid):
                raise ScanError("INVALID_ADVISORY_ID")
            try:
                stamp = datetime.fromisoformat(modified.replace("Z", "+00:00"))
                if stamp.tzinfo is None:
                    raise ValueError()
            except (AttributeError, TypeError, ValueError) as exc:
                raise ScanError("INVALID_ADVISORY_TIMESTAMP") from exc
            matches.append({"id": vid, "modified": modified})
        token = item.get("next_page_token")
        if "next_page_token" in item and (not isinstance(token, str) or not token
                                         or len(token) > 16384):
            raise ScanError("INVALID_PAGE_TOKEN")
        decoded.append((matches, token))
    return decoded


def scan(packages, fetch=fetch_batch, clock=time.monotonic):
    require(0 < len(packages) <= MAX_PACKAGES, "Package count outside scan bounds")
    deadline = clock() + MAX_SECONDS
    records = [{"purl": p["purl"], "usages": p["usages"],
                "inAnyReleaseRuntime": p["inAnyReleaseRuntime"],
                "status": "NOT_SCANNED", "advisories": []} for p in packages]
    errors = []
    for start in range(0, len(packages), BATCH_SIZE):
        pending = [(i, None) for i in range(start, min(start + BATCH_SIZE, len(packages)))]
        seen = {i: set() for i, _ in pending}
        found = {i: {} for i, _ in pending}
        for page in range(MAX_PAGES):
            if not pending:
                break
            try:
                if clock() >= deadline:
                    raise ScanError("SCAN_TIME_BUDGET")
                queries = []
                for i, token in pending:
                    query = {"package": {"purl": packages[i]["purl"]}}
                    if token is not None:
                        query["page_token"] = token
                    queries.append(query)
                decoded = decode_batch(fetch(queries), len(pending))
            except ScanError as exc:
                errors.append(str(exc))
                for i, _ in pending:
                    records[i]["status"] = "SCAN_FAILED"
                pending = []
                break
            next_pending = []
            for (i, _), (matches, token) in zip(pending, decoded):
                record = records[i]
                for match in matches:
                    # Keep distinct revisions if OSV changes during pagination.
                    found[i][(match["id"], match["modified"])] = match
                record["advisories"] = [found[i][key] for key in sorted(found[i])]
                if token is None:
                    record["status"] = ("ADVISORY_FOUND" if found[i]
                                        else "NO_KNOWN_ADVISORIES")
                elif token in seen[i]:
                    record["status"] = "SCAN_FAILED"
                    errors.append("REPEATED_PAGE_TOKEN")
                else:
                    seen[i].add(token)
                    next_pending.append((i, token))
            pending = next_pending
        if pending:
            errors.append("PAGE_LIMIT")
            for i, _ in pending:
                records[i]["status"] = "SCAN_FAILED"
        if clock() >= deadline:
            break
    return records, sorted(set(errors))


def report(directory, expected, fetch=fetch_batch):
    def load(name):
        return json.loads((directory / name).read_bytes())
    raw = (directory / "maven-packages.json").read_bytes()
    inventory = json.loads(raw)
    rebuilt = normalize(load("declared-versions.json"),
                        {app: load(f"resolved-{app}.json") for app in ("tv", "phone")},
                        (directory / "resolved-dependencies.json").read_bytes(), expected)
    require(inventory == rebuilt, "Normalized inventory does not match validated inputs")
    started = utc_now()
    records, errors = scan(inventory["packages"], fetch)
    counts = {status: sum(r["status"] == status for r in records) for status in
              ("NOT_SCANNED", "SCAN_FAILED", "ADVISORY_FOUND", "NO_KNOWN_ADVISORIES")}
    complete = not errors and not counts["NOT_SCANNED"] and not counts["SCAN_FAILED"]
    return {
        "schemaVersion": 1, "sourceCommit": expected, "source": URL,
        "scope": inventory["scope"], "mavenPackagesSha256": hashlib.sha256(raw).hexdigest(),
        "resolvedGraphSha256": inventory["resolvedGraphSha256"],
        "startedAt": started, "finishedAt": utc_now(),
        "scanStatus": "COMPLETE" if complete else "INCOMPLETE",
        "counts": counts, "errors": errors, "packages": records,
        "distinctAdvisoryIds": sorted({a["id"] for r in records for a in r["advisories"]}),
        "limitations": [
            "OSV version matches only; no exploitability, severity or reachability conclusion.",
            "Distinct OSV IDs are not deduplicated CVEs; aliases and fix versions require detail review.",
            "Empty completed responses mean no known OSV matches, not absence of vulnerabilities.",
            "Incomplete queries may retain partial advisory IDs, never a clean status.",
            "Build/plugin/benchmark/npm and non-Maven components are not covered.",
        ],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--directory", required=True, type=Path)
    args = parser.parse_args()
    output = args.directory / "security-findings.json"
    output.unlink(missing_ok=True)
    result = report(args.directory, args.source_commit)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("OSV scan", result["scanStatus"], json.dumps(result["counts"], sort_keys=True))
    print("Distinct OSV IDs:", len(result["distinctAdvisoryIds"]))
    for row in result["packages"]:
        for advisory in row["advisories"]:
            print("Runtime OSV match", row["purl"], advisory["id"],
                  "releaseRuntime=" + str(row["inAnyReleaseRuntime"]))
    if result["scanStatus"] != "COMPLETE":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
