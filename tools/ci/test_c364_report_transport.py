import base64
import hashlib
import tempfile
import unittest
from pathlib import Path

from c364_report_transport import CHUNK_SIZE, PACKAGE, TESTS, decode_log, extract

SHA = "a" * 40
PREFIX = "11-25 15:25:44.332  3500  3515 I MuxTVC364: "
PAYLOAD = bytes(i % 251 for i in range(200_000))


def records(data=PAYLOAD):
    digest = hashlib.sha256(data).hexdigest()
    encoded = base64.b64encode(data).decode()
    chunks = [encoded[i:i+CHUNK_SIZE] for i in range(0, len(encoded), CHUNK_SIZE)]
    messages = [f"C364-V1 BEGIN {SHA} {len(data)} {len(chunks)} {digest}"]
    messages += [f"C364-V1 CHUNK {i} {part}" for i, part in enumerate(chunks)]
    messages.append(f"C364-V1 END {digest}")
    return [PREFIX + x + "\n" for x in messages]


class TransportTest(unittest.TestCase):
    def test_complete(self):
        self.assertEqual(decode_log(["unrelated log\n", *records()], SHA), PAYLOAD)

    def test_missing_duplicate_reordered_records(self):
        good = records()
        for bad in (good[1:], good[:-1], good[:2]+good[3:],
                    good[:2]+good[1:], good[:1]+[good[2],good[1]]+good[3:],
                    good+[good[-1]]):
            with self.subTest(length=len(bad)), self.assertRaises(ValueError):
                decode_log(bad, SHA)

    def test_wrong_source_digest_length_and_thread(self):
        good = records()
        corrupt = good.copy()
        chunk = corrupt[1]
        i = chunk.index("CHUNK 0 ") + len("CHUNK 0 ")
        corrupt[1] = chunk[:i] + ("B" if chunk[i] == "A" else "A") + chunk[i+1:]
        for bad in (corrupt, [good[0].replace(SHA, "b"*40), *good[1:]],
                    [good[0].replace("200000", "200001"), *good[1:]],
                    [good[0],good[1].replace("3515", "3516"),*good[2:]]):
            with self.assertRaises(ValueError):
                decode_log(bad, SHA)

    def test_junit_and_log_gate(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            cls, method = TESTS["probe"]
            xml = root / "TEST-device.xml"
            good = ('<testsuites tests="1" failures="0" errors="0" skipped="0">'
                    '<testsuite tests="1" failures="0" errors="0" skipped="0">'
                    f'<testcase classname="{PACKAGE}{cls}" name="{method}"/>'
                    '</testsuite></testsuites>')
            xml.write_text(good)
            (root / "test-result-exit-code.txt").write_text("0")
            log = root / f"logcat-{PACKAGE}{cls}-{method}.txt"
            log.write_text("".join(records()))
            data, metadata = extract(root, "probe", SHA)
            self.assertEqual(data, PAYLOAD)
            self.assertEqual(metadata["payloadBytes"], len(PAYLOAD))
            for bad in (good.replace('failures="0"', 'failures="1"'),
                        good.replace(method, "other"),
                        good.replace('tests="1"', 'tests="0"')):
                xml.write_text(bad)
                with self.assertRaises(ValueError):
                    extract(root, "probe", SHA)
            xml.write_text(good)
            (root / "test-result-exit-code.txt").write_text("1")
            with self.assertRaises(ValueError):
                extract(root, "probe", SHA)
            (root / "test-result-exit-code.txt").write_text("0")
            log.unlink()
            with self.assertRaises(ValueError):
                extract(root, "probe", SHA)


if __name__ == "__main__":
    unittest.main()
