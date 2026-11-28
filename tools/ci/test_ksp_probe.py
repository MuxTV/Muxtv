import tempfile
from pathlib import Path
import unittest

from ksp_probe import (
    ANCHORS, DAO, MODULE, ROOM_MARKER, HILT_MARKER, HILT_FACTORY,
    DB, TV, TASKS, HILT_TASK, SCHEMA_TASK, check_outcomes, mutation_inputs, snapshot, schemas,
)


def log(phase):
    suffix = " UP-TO-DATE" if phase == "warm" else ""
    lines = ["> Task " + task + suffix for task in TASKS + (HILT_TASK,)]
    lines.append("> Task " + SCHEMA_TASK)
    lines.append("BUILD SUCCESSFUL in 1s")
    if phase == "clean":
        lines.append("Configuration cache entry stored.")
    if phase == "warm":
        lines.extend(["Reusing configuration cache.", "Configuration cache entry reused."])
    return "\n".join(lines)


class KspProbeTest(unittest.TestCase):
    def test_outcomes(self):
        for phase in ("clean", "warm", "mutated", "restored"):
            self.assertEqual(len(TASKS) + 2, len(check_outcomes(log(phase), phase)))
        for invalid in (
            log("warm").replace("UP-TO-DATE", "FROM-CACHE", 1),
            log("warm").replace("Configuration cache entry reused.", ""),
            log("warm").replace("> Task " + SCHEMA_TASK, ""),
            log("warm").replace("> Task " + TASKS[0] + " UP-TO-DATE", ""),
            log("warm") + "\n> Task " + TASKS[0] + " FAILED",
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                check_outcomes(invalid, "warm")

    def test_mutations_require_unique_anchors(self):
        original = {DAO: b"interface ProfileDao {\n}\n", MODULE: b"object AppModule {\n}\n"}
        result = mutation_inputs(original)
        self.assertIn(ROOM_MARKER.encode(), result[DAO])
        self.assertIn(HILT_MARKER.encode(), result[MODULE])
        self.assertNotIn(ROOM_MARKER.encode(), original[DAO])
        for bad in (b"wrong", original[DAO] * 2, result[DAO]):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                mutation_inputs({**original, DAO: bad})

    def test_generated_anchors_and_removed_symbols(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = {}
            for key, (owner, stem) in ANCHORS.items():
                path = root / owner / "build/generated/ksp/debug/java" / (stem + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("generated", encoding="utf-8")
                paths[key] = path
            self.assertEqual(4, len(snapshot(root, False)))
            with self.assertRaises(ValueError):
                snapshot(root, True)
            paths["dao"].write_text(ROOM_MARKER, encoding="utf-8")
            factory = root / TV / "build/generated/ksp/debug/java" / (HILT_FACTORY + ".java")
            factory.write_text(HILT_MARKER, encoding="utf-8")
            self.assertEqual(5, len(snapshot(root, True)))
            with self.assertRaises(ValueError):
                snapshot(root, False)
            paths["dao"].write_text("generated", encoding="utf-8")
            factory.unlink()
            self.assertEqual(4, len(snapshot(root, False)))
            paths["component"].unlink()
            with self.assertRaises(ValueError):
                snapshot(root, False)

    def test_schema_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                schemas(root)
            path = root / DB / "schemas/1.json"
            path.parent.mkdir(parents=True)
            path.write_text("one", encoding="utf-8")
            baseline = schemas(root)
            path.write_text("two", encoding="utf-8")
            self.assertNotEqual(baseline, schemas(root))


if __name__ == "__main__":
    unittest.main()
