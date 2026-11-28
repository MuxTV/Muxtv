import unittest
from unittest.mock import patch

import osv_scan as s


def packages(n):
    return [{"purl": f"pkg:maven/g/n{i}@1", "usages": [],
             "inAnyReleaseRuntime": i == 0} for i in range(n)]


def advisory(vid="GHSA-test"):
    return {"id": vid, "modified": "2026-01-01T00:00:00Z"}


class OsvScanTest(unittest.TestCase):
    def test_empty_and_found(self):
        rows, errors = s.scan(packages(2), lambda q: {"results": [{}, {"vulns": [advisory()]}]})
        self.assertEqual([], errors)
        self.assertEqual(["NO_KNOWN_ADVISORIES", "ADVISORY_FOUND"], [r["status"] for r in rows])
        self.assertEqual([advisory()], rows[1]["advisories"])

    def test_per_query_pagination_and_dedup(self):
        calls = []
        def fetch(q):
            calls.append(q)
            if len(calls) == 1:
                return {"results": [{"vulns": [advisory()], "next_page_token": "a"}, {},
                                    {"next_page_token": "b"}]}
            return {"results": [{"vulns": [advisory(), advisory("OSV-two")]},
                                {"vulns": [advisory("OSV-three")]}]}
        rows, errors = s.scan(packages(3), fetch)
        self.assertEqual([], errors)
        self.assertEqual([{"package": {"purl": "pkg:maven/g/n0@1"}, "page_token": "a"},
                          {"package": {"purl": "pkg:maven/g/n2@1"}, "page_token": "b"}], calls[1])
        self.assertEqual(2, len(rows[0]["advisories"]))
        self.assertEqual("NO_KNOWN_ADVISORIES", rows[1]["status"])
        self.assertEqual("ADVISORY_FOUND", rows[2]["status"])

    def test_malformed_responses_fail_closed(self):
        for response in [{}, {"results": []}, {"results": [None]},
                         {"results": [{"vulns": None}]},
                         {"results": [{"vulns": [{"id": "bad"}]}]},
                         {"results": [{"next_page_token": ""}]},
                         {"results": [{"unexpected": True}]}]:
            with self.subTest(response=response):
                rows, errors = s.scan(packages(1), lambda q: response)
                self.assertTrue(errors)
                self.assertEqual("SCAN_FAILED", rows[0]["status"])

    def test_partial_transport_failure(self):
        def fetch(q):
            if "page_token" in q[0]:
                raise s.ScanError("TRANSPORT_ERROR")
            return {"results": [{"vulns": [advisory()], "next_page_token": "a"}, {}]}
        rows, errors = s.scan(packages(2), fetch)
        self.assertEqual(["TRANSPORT_ERROR"], errors)
        self.assertEqual("SCAN_FAILED", rows[0]["status"])
        self.assertEqual([advisory()], rows[0]["advisories"])
        self.assertEqual("NO_KNOWN_ADVISORIES", rows[1]["status"])

    def test_repeated_and_exhausted_tokens(self):
        rows, errors = s.scan(packages(1), lambda q: {"results": [{"next_page_token": "a"}]})
        self.assertEqual(["REPEATED_PAGE_TOKEN"], errors)
        self.assertEqual("SCAN_FAILED", rows[0]["status"])
        with patch.object(s, "MAX_PAGES", 1):
            rows, errors = s.scan(packages(1), lambda q: {"results": [{"next_page_token": "a"}]})
        self.assertEqual(["PAGE_LIMIT"], errors)
        self.assertEqual("SCAN_FAILED", rows[0]["status"])

    def test_batch_bound_and_deadline(self):
        sizes = []
        def fetch(q):
            sizes.append(len(q))
            return {"results": [{} for _ in q]}
        with patch.object(s, "BATCH_SIZE", 2):
            rows, errors = s.scan(packages(5), fetch)
        self.assertEqual([2, 2, 1], sizes)
        self.assertEqual([], errors)
        with patch.object(s, "MAX_SECONDS", 0):
            rows, errors = s.scan(packages(5), fetch, clock=lambda: 0)
        self.assertEqual(["SCAN_TIME_BUDGET"], errors)
        self.assertTrue(all(r["status"] == "SCAN_FAILED" for r in rows))

    def test_retry_bounded(self):
        from urllib.error import URLError
        with patch.object(s, "build_opener") as builder, patch.object(s.time, "sleep"):
            builder.return_value.open.side_effect = URLError("fixture")
            with self.assertRaisesRegex(s.ScanError, "TRANSPORT_ERROR"):
                s.fetch_batch([{"package": {"purl": "pkg:maven/g/n@1"}}])
            self.assertEqual(s.MAX_ATTEMPTS, builder.return_value.open.call_count)


if __name__ == "__main__":
    unittest.main()
