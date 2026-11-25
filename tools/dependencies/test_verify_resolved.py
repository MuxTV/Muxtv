import copy
import unittest

from verify_resolved import CONFIGURATIONS, combine

SHA = "a" * 40


def fixture():
    declared = {"schemaVersion": 1, "scope": "declared-only", "sourceCommit": SHA}
    reports = {}
    for app in ("tv", "phone"):
        project = f":app:{app}"
        reports[app] = {
            "schemaVersion": 1, "scope": "selected-app-runtime-graphs",
            "sourceCommit": SHA, "gradleVersion": "9.5.0", "unresolvedCount": 0,
            "graphs": [
                {"project": project, "configuration": name,
                 "components": [{"kind": "other", "id": project},
                                {"kind": "module", "id": "g:n:1", "group": "g", "name": "n", "version": "1"}],
                 "edges": [{"from": project, "requested": "g:n:1", "selected": "g:n:1",
                            "constraint": False}]}
                for name in sorted(CONFIGURATIONS)
            ],
        }
    return declared, reports


class VerifyResolvedTest(unittest.TestCase):
    def test_all_six(self):
        declared, reports = fixture()
        result = combine(declared, reports, SHA)
        self.assertEqual(6, len(result["graphs"]))
        self.assertEqual(0, result["unresolvedCount"])
        self.assertEqual(SHA, result["sourceCommit"])

    def test_reject_mutations(self):
        mutations = {
            "stale declared": lambda d, r: d.update(sourceCommit="b" * 40),
            "stale graph": lambda d, r: r["tv"].update(sourceCommit="b" * 40),
            "missing app": lambda d, r: r.pop("phone"),
            "missing configuration": lambda d, r: r["tv"]["graphs"].pop(),
            "duplicate configuration": lambda d, r: r["tv"]["graphs"].append(copy.deepcopy(r["tv"]["graphs"][0])),
            "wrong owner": lambda d, r: r["tv"]["graphs"][0].update(project=":app:phone"),
            "unresolved count": lambda d, r: r["tv"].update(unresolvedCount=1),
            "unresolved edge": lambda d, r: r["tv"]["graphs"][0]["edges"][0].update(unresolved=True),
            "dangling edge": lambda d, r: r["tv"]["graphs"][0]["edges"][0].update(selected="absent"),
            "empty graph": lambda d, r: r["tv"]["graphs"][0].update(edges=[]),
            "missing coordinates": lambda d, r: r["tv"]["graphs"][0]["components"][1].pop("version"),
            "duplicate component": lambda d, r: r["tv"]["graphs"][0]["components"].append(
                copy.deepcopy(r["tv"]["graphs"][0]["components"][1])),
            "different Gradle": lambda d, r: r["tv"].update(gradleVersion="9.9.2"),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                declared, reports = fixture()
                mutate(declared, reports)
                with self.assertRaises(ValueError):
                    combine(declared, reports, SHA)


if __name__ == "__main__":
    unittest.main()
