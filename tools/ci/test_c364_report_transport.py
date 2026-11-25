import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from c364_report_transport import PACKAGE, TESTS, TRANSPORT, extract

SHA = "a" * 40
PAYLOAD = bytes(i % 251 for i in range(200_000))


class TransportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "results"
        self.root.mkdir()
        self.output = Path(self.temp.name) / "additional"
        self.device = self.output / "debugAndroidTest" / "connected" / "device"
        self.device.mkdir(parents=True)
        self.write_fixture("probe", PAYLOAD)

    def write_fixture(self, mode, data):
        cls, method = TESTS[mode]
        self.xml = self.root / "TEST-device.xml"
        self.xml.write_text(
            '<testsuites tests="1" failures="0" errors="0" skipped="0">'
            '<testsuite tests="1" failures="0" errors="0" skipped="0">'
            f'<testcase classname="{PACKAGE}{cls}" name="{method}"/>'
            '</testsuite></testsuites>')
        (self.root / "test-result-exit-code.txt").write_text("0")
        self.payload = self.device / f"c364-{mode}-payload.bin"
        self.payload.write_bytes(data)
        self.manifest = self.device / f"c364-{mode}-manifest.json"
        self.record = {"schemaVersion": 1, "transport": TRANSPORT,
                       "sourceCommit": SHA, "mode": mode, "payloadBytes": len(data),
                       "payloadSha256": hashlib.sha256(data).hexdigest(),
                       "payloadName": self.payload.name}
        self.manifest.write_text(json.dumps(self.record))

    def reject(self, mode="probe"):
        with self.assertRaises(ValueError):
            extract(self.root, self.output, mode, SHA)

    def test_complete(self):
        data, metadata = extract(self.root, self.output, "probe", SHA)
        self.assertEqual(data, PAYLOAD)
        self.assertEqual(metadata["payloadBytes"], 200_000)

    def test_missing_duplicate_and_partial_files(self):
        self.manifest.unlink()
        self.reject()
        self.manifest.write_text(json.dumps(self.record))
        duplicate = self.output / self.payload.name
        duplicate.write_bytes(PAYLOAD)
        self.reject()
        duplicate.unlink()
        self.payload.write_bytes(PAYLOAD[:-1])
        self.reject()
        self.payload.unlink()
        self.reject()

    def test_manifest_fields_and_corruption(self):
        for key, value in (("sourceCommit", "b"*40), ("mode", "canonical"),
                           ("transport", "logcat-v1"), ("payloadBytes", True),
                           ("payloadBytes", len(PAYLOAD)+1), ("payloadName", "../other"),
                           ("payloadSha256", "0"*64), ("schemaVersion", 2)):
            with self.subTest(key=key):
                self.manifest.write_text(json.dumps({**self.record, key: value}))
                self.reject()
        self.manifest.write_text(json.dumps(self.record)[:-1] + ', "mode": "probe"}')
        self.reject()
        self.manifest.write_text(json.dumps(self.record))
        self.payload.write_bytes(b"X" + PAYLOAD[1:])
        self.reject()

    def test_junit_gate(self):
        good = self.xml.read_text()
        for bad in (good.replace('failures="0"', 'failures="1"'),
                    good.replace('transfersBoundedSyntheticPayload', "other"),
                    good.replace('tests="1"', 'tests="0"')):
            self.xml.write_text(bad)
            self.reject()
        self.xml.write_text(good)
        (self.root / "test-result-exit-code.txt").write_text("1")
        self.reject()

    def test_canonical_source_and_redaction(self):
        self.payload.unlink()
        self.manifest.unlink()
        valid = {"sourceCommit": SHA, "redactionPassed": True}
        self.write_fixture("canonical", json.dumps(valid).encode())
        extract(self.root, self.output, "canonical", SHA)
        for bad in ({**valid, "sourceCommit": "b"*40},
                    {**valid, "redactionPassed": False},
                    {**valid, "extra": "token=secret"}):
            self.write_fixture("canonical", json.dumps(bad).encode())
            self.reject("canonical")


if __name__ == "__main__":
    unittest.main()
