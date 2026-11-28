import json
import unittest

from advisory_review import alias_groups, checked_matches, detail_for, digest, package_paths
from osv_scan import URL
from test_verify_resolved import SHA

VID = "GHSA-example"
MODIFIED = "2026-11-01T00:00:00Z"
PACKAGE = {"group": "g", "name": "n", "version": "1", "purl": "pkg:maven/g/n@1",
           "inAnyReleaseRuntime": True,
           "usages": [{"project": ":app:tv", "configuration": "releaseRuntimeClasspath",
                       "releaseRuntime": True}]}


def detail():
    return {"id": VID, "modified": MODIFIED, "aliases": ["CVE-2026-1234"],
            "affected": [{"package": {"ecosystem": "Maven", "name": "g:n"},
                          "ranges": [{"type": "ECOSYSTEM", "events": [{"introduced": "0"}, {"fixed": "2"}]}]},
                         {"package": {"ecosystem": "Maven", "name": "g:other"},
                          "ranges": [{"type": "ECOSYSTEM", "events": [{"fixed": "99"}]}]}]}


class AdvisoryReviewTest(unittest.TestCase):
    def test_matching_detail_and_revision(self):
        row = detail_for(detail(), VID, MODIFIED, PACKAGE)
        self.assertEqual(["2"], row["reportedFixBoundaries"])
        self.assertEqual("APPLICABILITY_REVIEW_REQUIRED", row["reviewStatus"])
        for changed in [{"id": "other"}, {"modified": "2026-11-02T00:00:00Z"}, {"affected": []}]:
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                detail_for({**detail(), **changed}, VID, MODIFIED, PACKAGE)

    def test_withdrawn_and_aliases(self):
        row = detail_for({**detail(), "withdrawn": MODIFIED, "affected": []}, VID, MODIFIED, PACKAGE)
        self.assertEqual("WITHDRAWN_REVIEW_REQUIRED", row["reviewStatus"])
        groups = alias_groups({VID: detail(), "OSV-other": {"aliases": ["CVE-2026-1234"]},
                              "OSV-independent": {"aliases": []}})
        self.assertEqual(2, len(groups))
        self.assertTrue(any(g["matchedAdvisoryIds"] == [VID, "OSV-other"] for g in groups))

    def test_paths_exclude_constraints_and_cycles(self):
        graph = {"rootComponent": "root", "project": ":app:tv", "configuration": "releaseRuntimeClasspath",
                 "components": [{"kind": "module", "id": "g:n:1", "group": "g", "name": "n", "version": "1"}],
                 "edges": [{"from": "root", "selected": "g:n:1", "constraint": True},
                           {"from": "root", "selected": "parent", "constraint": False},
                           {"from": "parent", "selected": "g:n:1", "constraint": False},
                           {"from": "g:n:1", "selected": "parent", "constraint": False}]}
        graphs = {("runtime", ("main", ":app:tv", "releaseRuntimeClasspath")): graph}
        row = package_paths(PACKAGE, graphs, "runtime")[0]
        self.assertEqual(["root", "parent", "g:n:1"], row["shortestDependencyPath"])
        self.assertEqual(["parent"], row["incomingDependencyParents"])
        self.assertEqual(["root"], row["incomingConstraintParents"])
        graph["edges"] = graph["edges"][:1]
        self.assertEqual("NO_NON_CONSTRAINT_PATH", package_paths(PACKAGE, graphs, "runtime")[0]["pathStatus"])

    def test_scan_provenance_and_coverage(self):
        normalized = {"scope": "selected-build-maven-components", "packages": [PACKAGE]}
        raw = json.dumps(normalized).encode()
        scan = {"schemaVersion": 1, "sourceCommit": SHA, "scope": normalized["scope"], "source": URL,
                "buildPackagesSha256": digest(raw), "scanStatus": "COMPLETE", "errors": [],
                "counts": {"ADVISORY_FOUND": 1, "NO_KNOWN_ADVISORIES": 0, "SCAN_FAILED": 0, "NOT_SCANNED": 0},
                "distinctAdvisoryIds": [VID],
                "packages": [{**PACKAGE, "status": "ADVISORY_FOUND",
                              "advisories": [{"id": VID, "modified": MODIFIED}]}]}
        self.assertEqual([(PACKAGE, VID, MODIFIED)], checked_matches(scan, normalized, raw, SHA, "buildPackagesSha256"))
        for changed in [{"sourceCommit": "b" * 40}, {"packages": []}, {"counts": {}},
                        {"distinctAdvisoryIds": []}, {"buildPackagesSha256": "wrong"}]:
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                checked_matches({**scan, **changed}, normalized, raw, SHA, "buildPackagesSha256")


if __name__ == "__main__":
    unittest.main()
