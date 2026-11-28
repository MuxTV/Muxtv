"""Hosted, disposable-checkout KSP regeneration and configuration-cache probe."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tomllib

DAO = Path("core/database/src/main/kotlin/app/muxtv/database/ProfileDao.kt")
MODULE = Path("app/tv/src/main/kotlin/app/muxtv/di/AppModule.kt")
DB = Path("core/database")
TV = Path("app/tv")
ROOM_MARKER = "dep01aProbeCount"
HILT_MARKER = "provideDep01aProbe"
HILT_FACTORY = "AppModule_ProvideDep01aProbeFactory"
ANCHORS = {
    "database": (DB, "MuxTvDatabase_Impl"),
    "dao": (DB, "ProfileDao_Impl"),
    "factory": (TV, "AppModule_ProvideRemoteSourceAccessManagerFactory"),
    "component": (TV, "DaggerMuxTvApplication_HiltComponents_SingletonC"),
}
TASKS = (
    ":core:database:kspDebugKotlin", ":core:database:compileDebugKotlin",
    ":app:tv:kspDebugKotlin", ":app:tv:compileDebugKotlin",
)
HILT_TASK = ":app:tv:hiltJavaCompileDebug"
SCHEMA_TASK = ":core:database:verifyCurrentRoomSchema"
COMMAND = [
    "bash", "./gradlew", "--no-daemon", "--no-build-cache", "--configuration-cache",
    "--configuration-cache-problems=fail", "--console=plain",
    "-Pksp.incremental=true", "-Pksp.incremental.log=true",
    ":app:tv:assembleDebug", ":core:database:copyRoomSchemas", SCHEMA_TASK,
]
TASK_LINE = re.compile(r"^> Task (:[^\s]+)(?: (UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED|FAILED))?$")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def git(root, *args):
    return subprocess.check_output(["git", *args], cwd=root, text=True).strip()


def changed(root):
    return set(git(root, "diff", "--name-only", "HEAD").splitlines())


def mutate(data, anchor, addition, marker):
    require(marker.encode() not in data and data.count(anchor.encode()) == 1,
            "Probe anchor missing, ambiguous, or marker already exists")
    return data.replace(anchor.encode(), (anchor + addition).encode(), 1)


def mutation_inputs(original):
    return {
        DAO: mutate(original[DAO], "interface ProfileDao {\n",
                    '    @Query("SELECT COUNT(*) FROM profiles")\n'
                    '    suspend fun dep01aProbeCount(): Int\n\n', ROOM_MARKER),
        MODULE: mutate(original[MODULE], "object AppModule {\n",
                       '    @Provides\n    @javax.inject.Named("muxtv-dep01a-probe")\n'
                       '    fun provideDep01aProbe(): String = "probe"\n\n', HILT_MARKER),
    }


def outcomes(log):
    result = {}
    for line in log.splitlines():
        match = TASK_LINE.fullmatch(line.strip())
        if match:
            task, status = match.group(1), match.group(2) or "EXECUTED"
            # Some Gradle console messages repeat the same task heading.
            require(task not in result or result[task] == status,
                    "Conflicting task outcomes: " + task)
            result[task] = status
    return result


def check_outcomes(log, phase):
    status = outcomes(log)
    expected = "UP-TO-DATE" if phase == "warm" else "EXECUTED"
    for task in TASKS:
        require(status.get(task) == expected,
                f"{phase}: {task}: expected {expected}, observed {status.get(task)}")
    require(status.get(SCHEMA_TASK) == "EXECUTED", phase + ": schema verification not executed")
    require(status.get(HILT_TASK) in {"EXECUTED", "UP-TO-DATE"},
            phase + ": Hilt component Java compile missing")
    if phase == "clean":
        require(status[HILT_TASK] == "EXECUTED", "Clean Hilt Java compilation not executed")
        require("Configuration cache entry stored." in log, "Clean configuration cache not stored")
    if phase == "warm":
        require("Reusing configuration cache." in log and "Configuration cache entry reused." in log,
                "Warm configuration cache reuse not established")
    require("BUILD SUCCESSFUL" in log, phase + ": success marker missing")
    return {task: status[task] for task in (*TASKS, HILT_TASK, SCHEMA_TASK)}


def generated(root, module, stem):
    base = root / module / "build/generated"
    return sorted(p for p in base.rglob(stem + ".*")
                  if p.is_file() and p.suffix in {".java", ".kt"}
                  and "debug" in p.relative_to(base).parts)


def snapshot(root, mutated):
    result = {}
    for key, (module, stem) in ANCHORS.items():
        matches = generated(root, module, stem)
        require(len(matches) == 1, f"Expected one debug generated {stem}; found {len(matches)}")
        path = matches[0]
        raw = path.read_bytes()
        require(bool(raw), "Empty generated anchor: " + stem)
        if key == "dao":
            require((ROOM_MARKER.encode() in raw) == mutated, "Room probe method presence mismatch")
        result[key] = {"path": path.relative_to(root).as_posix(), "sha256": sha(raw), "size": len(raw)}
    factories = generated(root, TV, HILT_FACTORY)
    require(len(factories) == int(mutated), "Hilt probe factory presence mismatch")
    if mutated:
        raw = factories[0].read_bytes()
        require(HILT_MARKER.encode() in raw, "Probe factory does not invoke temporary provider")
        result["probeFactory"] = {"path": factories[0].relative_to(root).as_posix(),
                                  "sha256": sha(raw), "size": len(raw)}
    return result


def schemas(root):
    base = root / DB / "schemas"
    result = {p.relative_to(root).as_posix(): sha(p.read_bytes())
              for p in sorted(base.rglob("*.json")) if p.is_file()}
    require(bool(result), "Missing committed Room schema files")
    return result


def schema_clean(root, baseline):
    require(schemas(root) == baseline, "Room schema bytes or file membership changed")
    require(not git(root, "status", "--porcelain=v1", "--", str(DB / "schemas")),
            "Room schema working tree changed")


def copy_dirty_logs(root, evidence, phase):
    names = []
    for owner in (DB, TV):
        for path in sorted((root / owner / "build/kspCaches").rglob("kspDirtySet*.log")):
            if "debug" not in path.relative_to(root / owner / "build/kspCaches").parts:
                continue
            target = evidence / phase / path.relative_to(root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            names.append(target.relative_to(evidence).as_posix())
    return names


def run_phase(root, evidence, phase, mutated, baseline_schemas, report):
    log_path = evidence / (phase + ".log")
    entry = {"phase": phase, "status": "INCOMPLETE", "log": log_path.name}
    report["phases"].append(entry)
    print("KSP probe phase:", phase, flush=True)
    with log_path.open("w", encoding="utf-8") as out:
        result = subprocess.run(COMMAND, cwd=root, stdout=out, stderr=subprocess.STDOUT,
                                timeout=20 * 60, check=False)
    log = log_path.read_text(encoding="utf-8", errors="replace")
    entry["exitCode"] = result.returncode
    entry["logSha256"] = sha(log_path.read_bytes())
    entry["dirtySetLogs"] = copy_dirty_logs(root, evidence, phase)
    if result.returncode != 0:
        print("\n".join(log.splitlines()[-60:]), flush=True)
        raise ValueError(phase + ": Gradle failed; inspect archived log")
    entry["taskOutcomes"] = check_outcomes(log, phase)
    entry["configurationCacheReused"] = "Configuration cache entry reused." in log
    entry["generated"] = snapshot(root, mutated)
    schema_clean(root, baseline_schemas)
    require(changed(root) == ({DAO.as_posix(), MODULE.as_posix()} if mutated else set()),
            phase + ": unexpected tracked changes")
    entry["status"] = "PASS"
    print(json.dumps({"phase": phase, "taskOutcomes": entry["taskOutcomes"],
                      "configurationCacheReused": entry["configurationCacheReused"],
                      "dirtySetLogCount": len(entry["dirtySetLogs"])}, sort_keys=True), flush=True)
    return entry


def probe(root, evidence, expected):
    require(re.fullmatch(r"[0-9a-f]{40}", expected) is not None, "Expected exact SHA")
    require(git(root, "rev-parse", "HEAD") == expected, "Checkout SHA mismatch")
    require(not changed(root) and not git(root, "status", "--porcelain=v1", "--untracked-files=no"),
            "Tracked workspace not clean")
    require(not any((root / owner / "build").exists() for owner in (DB, TV))
            and not (root / ".gradle/configuration-cache").exists(),
            "Probe requires fresh owner outputs and configuration cache")
    require(not evidence.exists(), "Probe evidence directory already exists")
    evidence.mkdir(parents=True)
    original = {p: (root / p).read_bytes() for p in (DAO, MODULE)}
    mutations = mutation_inputs(original)
    baseline = schemas(root)
    schema_clean(root, baseline)
    with (root / "gradle/libs.versions.toml").open("rb") as stream:
        versions = tomllib.load(stream)["versions"]
    report = {"schemaVersion": 1, "sourceCommit": expected, "status": "INCOMPLETE",
              "versions": {k: versions[k] for k in ("agp", "kotlin", "ksp", "room3", "dagger-hilt")},
              "command": COMMAND, "schemaSha256": baseline, "phases": [],
              "originalSourceSha256": {p.as_posix(): sha(raw) for p, raw in original.items()},
              "limitations": ["Debug variant only; release and Android gates are separate.",
                              "Edited-input regeneration correctness, not proof of minimal dirty set or speed.",
                              "No build cache; warm configuration-cache reuse required.",
                              "Dirty-set logs are optional diagnostic evidence, not an efficiency threshold."]}
    try:
        clean = run_phase(root, evidence, "clean", False, baseline, report)
        warm = run_phase(root, evidence, "warm", False, baseline, report)
        require(clean["generated"] == warm["generated"], "Warm generated anchors changed")
        try:
            for path, raw in mutations.items():
                (root / path).write_bytes(raw)
            require(changed(root) == {DAO.as_posix(), MODULE.as_posix()}, "Unexpected mutation scope")
            run_phase(root, evidence, "mutated", True, baseline, report)
        finally:
            for path, raw in original.items():
                (root / path).write_bytes(raw)
        restored = run_phase(root, evidence, "restored", False, baseline, report)
        report["restoredAnchorHashesEqualClean"] = clean["generated"] == restored["generated"]
        require(all((root / path).read_bytes() == raw for path, raw in original.items()),
                "Original source bytes not restored")
        report["status"] = "PASS"
    except Exception as exc:
        report["error"] = str(exc)
        raise
    finally:
        # Restore bytes even on an assertion or build failure; never reset schema drift away.
        for path, raw in original.items():
            if (root / path).read_bytes() != raw:
                (root / path).write_bytes(raw)
        report["trackedChangesAfterRestore"] = sorted(changed(root))
        if report["trackedChangesAfterRestore"]:
            report["status"] = "INCOMPLETE"
        (evidence / "report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n",
                                              encoding="utf-8")
    require(report["status"] == "PASS", "Final probe contract not satisfied")
    print("KSP regeneration probe PASS: four phases; unchanged Room schemas; sources restored", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    root = Path.cwd().resolve()
    probe(root, args.directory.resolve(), args.source_commit)


if __name__ == "__main__":
    main()
