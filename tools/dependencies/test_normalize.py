import copy
import hashlib
import json
import unittest

from normalize import maven_purl, normalize
from test_verify_resolved import SHA, fixture
from verify_resolved import combine


def graph_bytes(declared, reports):
    return json.dumps(combine(declared, reports, SHA), sort_keys=True).encode()


def selected(graph, group, name, version, parent, constraint=False):
    cid = f"{group}:{name}:{version}"
    graph["components"].append({"kind": "module", "id": cid, "group": group,
                                "name": name, "version": version})
    graph["edges"].append({"from": parent, "requested": cid, "selected": cid,
                           "constraint": constraint})


class NormalizeTest(unittest.TestCase):
    def test_purl(self):
        self.assertEqual("pkg:maven/Com.Example/Lib@1.0%2Bbuild",
                         maven_purl("Com.Example", "Lib", "1.0+build"))
        for coords in [("", "n", "1"), ("g/x", "n", "1"), ("g", ".", "1"),
                       ("g", "n", ""), ("g", "n", "1\n"), ("g", "n", " 1")]:
            with self.subTest(coords=coords), self.assertRaises(ValueError):
                maven_purl(*coords)

    def test_dedup_scopes_and_hash(self):
        declared, reports = fixture()
        data = graph_bytes(declared, reports)
        report = normalize(declared, reports, data, SHA)
        self.assertEqual(1, report["packageCount"])
        self.assertEqual(1, report["releaseRuntimePackageCount"])
        self.assertEqual(6, len(report["packages"][0]["usages"]))
        self.assertEqual(6, len(report["scopeCounts"]))
        self.assertEqual(hashlib.sha256(data).hexdigest(), report["resolvedGraphSha256"])
        self.assertEqual("NOT_SCANNED", report["packages"][0]["securityStatus"])

    def test_distinct_versions_and_edge_semantics(self):
        declared, reports = fixture()
        graph = next(g for g in reports["tv"]["graphs"]
                     if g["configuration"] == "debugUnitTestRuntimeClasspath")
        selected(graph, "g", "transitive", "1", "g:n:1")
        selected(graph, "g", "bom", "1", graph["rootComponent"], constraint=True)
        graph["components"][1].update(id="g:n:2", version="2")
        graph["edges"][0].update(selected="g:n:2")
        graph["edges"][1].update({"from": "g:n:2"})
        result = normalize(declared, reports, graph_bytes(declared, reports), SHA)
        packages = {p["purl"]: p for p in result["packages"]}
        self.assertEqual([{"group": "g", "name": "n", "versions": ["1", "2"]}],
                         result["multipleSelectedVersions"])
        transitive = packages["pkg:maven/g/transitive@1"]
        self.assertFalse(transitive["inAnyReleaseRuntime"])
        self.assertFalse(transitive["usages"][0]["rootDependencyEdge"])
        bom = packages["pkg:maven/g/bom@1"]["usages"][0]
        self.assertTrue(bom["rootConstraintEdge"])
        self.assertFalse(bom["rootDependencyEdge"])
        self.assertTrue(packages["pkg:maven/g/n@2"]["usages"][0]["rootDependencyEdge"])

    def test_reject_stale_tampered_and_duplicate_gav(self):
        declared, reports = fixture()
        data = graph_bytes(declared, reports)
        with self.assertRaises(ValueError):
            normalize(declared, reports, data, "b" * 40)
        tampered = json.loads(data)
        tampered["graphs"].pop()
        with self.assertRaises(ValueError):
            normalize(declared, reports, json.dumps(tampered).encode(), SHA)
        graph = reports["tv"]["graphs"][0]
        extra = copy.deepcopy(graph["components"][1])
        extra["id"] = "another-id"
        graph["components"].append(extra)
        with self.assertRaises(ValueError):
            normalize(declared, reports, graph_bytes(declared, reports), SHA)

    def test_invalid_root_rejected(self):
        declared, reports = fixture()
        reports["tv"]["graphs"][0]["rootComponent"] = "g:n:1"
        with self.assertRaises(ValueError):
            graph_bytes(declared, reports)


if __name__ == "__main__":
    unittest.main()
