import json
import unittest

from build_inventory import REPORTS, PROCESSOR_OWNERS, inventory, findings
from test_verify_resolved import SHA


def fixture():
    result = {}
    for name, keys in REPORTS.items():
        graphs = []
        discovered = []
        keys = set(keys)
        if name in PROCESSOR_OWNERS:
            owner, _ = PROCESSOR_OWNERS[name]
            discovered = ["kspDebugKotlinProcessorClasspath", "kspReleaseKotlinProcessorClasspath",
                          "kspDebugUnitTestKotlinProcessorClasspath"]
            keys.update(("main", owner, config) for config in discovered)
        for build, project, config in sorted(keys):
            components = [{"kind": "other", "id": "root"}]
            edges = []
            modules = [("com.android.tools.build", "gradle"), ("org.jetbrains.kotlin", "kotlin-gradle-plugin")]
            if config in discovered:
                modules = sorted(PROCESSOR_OWNERS[name][1]) if "UnitTest" not in config else []
            if config == "jmhRuntimeClasspath":
                modules = [("org.openjdk.jmh", "jmh-core")]
            for group, module in modules:
                cid = f"{group}:{module}:1"
                components.append({"kind": "module", "id": cid, "group": group,
                                   "name": module, "version": "1"})
                edges.append({"from": "root", "selected": cid, "requested": cid, "constraint": False})
            graphs.append({"build": build, "project": project, "configuration": config,
                           "rootComponent": "root", "components": components, "edges": edges})
        result[name] = {"schemaVersion": 1, "sourceCommit": SHA, "gradleVersion": "9.5.0",
                        "scope": "selected-build-graphs", "unresolvedCount": 0,
                        "discoveredProcessorConfigurations": discovered, "graphs": graphs}
    return result


def encoded(reports):
    return {k: json.dumps(v).encode() for k, v in reports.items()}


class BuildInventoryTest(unittest.TestCase):
    def test_dedup_and_scope(self):
        normalized = inventory(encoded(fixture()), SHA, "9.5.0")
        self.assertEqual(11, len(normalized["scopeCounts"]))
        self.assertEqual(6, normalized["packageCount"])
        by_name = {p["group"] + ":" + p["name"]: p for p in normalized["packages"]}
        self.assertEqual(4, len(by_name["com.android.tools.build:gradle"]["usages"]))
        self.assertTrue(all(not p["inAnyReleaseRuntime"] for p in normalized["packages"]))
        self.assertEqual(5, len(normalized["inputSha256"]))
        self.assertEqual({"BUILD", "BUILD_TEST", "KSP_PROCESSOR", "BENCHMARK"},
                         {u["scope"] for p in normalized["packages"] for u in p["usages"]})

    def test_invalid_inputs(self):
        name = "build-build-logic-convention.json"
        mutations = [
            lambda r: r.pop(name),
            lambda r: r[name].update(sourceCommit="b" * 40),
            lambda r: r[name].update(gradleVersion="9.9.2"),
            lambda r: r[name].update(unresolvedCount=1),
            lambda r: r[name]["graphs"].pop(),
            lambda r: r[name]["graphs"][0]["edges"][0].update(selected="absent"),
            lambda r: r[name]["graphs"][0]["components"][1].update(group="other"),
        ]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                reports = fixture()
                mutate(reports)
                with self.assertRaises(ValueError):
                    inventory(encoded(reports), SHA, "9.5.0")

    def test_processor_and_benchmark_coverage(self):
        db = "build-main-database.json"
        tv = "build-main-tv.json"
        jmh = "build-main-jvm-benchmark.json"
        mutations = [
            lambda r: r[db].update(discoveredProcessorConfigurations=[]),
            lambda r: r[db]["graphs"].pop(),
            lambda r: r[db]["graphs"][0]["components"][1].update(group="wrong"),
            lambda r: r[tv]["discoveredProcessorConfigurations"].append("runtimeClasspath"),
            lambda r: r[jmh]["graphs"][0]["components"][1].update(name="other"),
        ]
        for mutate in mutations:
            reports = fixture()
            mutate(reports)
            with self.assertRaises(ValueError):
                inventory(encoded(reports), SHA, "9.5.0")

    def test_scan_complete_and_incomplete(self):
        normalized = inventory(encoded(fixture()), SHA, "9.5.0")
        raw = json.dumps(normalized).encode()
        result = findings(normalized, raw, lambda q: {"results": [{} for _ in q]})
        self.assertEqual("COMPLETE", result["scanStatus"])
        self.assertEqual(6, result["counts"]["NO_KNOWN_ADVISORIES"])
        result = findings(normalized, raw, lambda q: {"results": []})
        self.assertEqual("INCOMPLETE", result["scanStatus"])
        self.assertEqual(6, result["counts"]["SCAN_FAILED"])


if __name__ == "__main__":
    unittest.main()
