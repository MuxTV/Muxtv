# Database Current Migration Chain and Schema Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the current Room version, ordered migration chain, current-schema test opens, and committed generated schema one executable contract so a future schema bump cannot omit a migration or generated JSON artifact.

**Architecture:** `core:database` owns `CURRENT_DATABASE_VERSION` and one validated ordered migration array. Production and tests that open the current database consume that array; targeted migration validation remains independent. The existing Room schema export task is followed by a deterministic guard that validates the current JSON version/identity and rejects an uncommitted generated change.

**Tech Stack:** Kotlin, Room 3, AndroidX SQLite migrations, Gradle Kotlin DSL, JUnit4, Truth, self-hosted Windows validation.

## Global Constraints

- Keep Room schema at version 10; this package must not add tables, columns, indexes, or a migration 10→11.
- Never enable destructive migration or weaken `MigrationTestHelper.runMigrationsAndValidate`.
- Targeted migration tests continue to validate only the migration under test before opening the current DAO surface.
- The schema guard must consume the normal Room-generated artifact; it must never manufacture JSON.
- The guard runs inside existing build/Full acceptance and must not add another AVD lifecycle.
- Preserve the accepted Guide active-membership code from `main@d78bfb89e11806d27012813b3b36cbb5c062f9eb`.

---

### Task 1: Central current-version and migration-chain owner

**Files:**
- Create: `core/database/src/main/kotlin/app/muxtv/database/CurrentDatabaseMigrations.kt`
- Create: `core/database/src/test/kotlin/app/muxtv/database/CurrentDatabaseMigrationsTest.kt`

**Interfaces:**
- Produces: `CURRENT_DATABASE_VERSION: Int`
- Produces: `CURRENT_DATABASE_MIGRATIONS: Array<Migration>`
- Produces: `validateCurrentMigrationChain(migrations: List<Migration>, currentVersion: Int): List<Migration>`

- [ ] **Step 1: Add unit contracts for a contiguous 1→10 chain, omitted latest migration, empty chain, wrong first version, and a gap.**
- [ ] **Step 2: Run `./gradlew.bat :core:database:testDebugUnitTest --tests app.muxtv.database.CurrentDatabaseMigrationsTest --stacktrace --console=plain`; expect compile failure because the owner does not exist.**
- [ ] **Step 3: Implement the version constant, ordered 1→10 array, and validation with precise `IllegalArgumentException` messages.**
- [ ] **Step 4: Rerun the focused unit test; expect all contracts to pass.**
- [ ] **Step 5: Commit as `refactor(database): centralize current migration chain`.**

### Task 2: Consume the shared owner in production and current-schema tests

**Files:**
- Modify: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabase.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabaseFactory.kt`
- Modify: `core/database/src/androidTest/kotlin/app/muxtv/database/EpgMigration4To5Test.kt`

**Interfaces:**
- Consumes: `CURRENT_DATABASE_VERSION`
- Consumes: `CURRENT_DATABASE_MIGRATIONS`

- [ ] **Step 1: Replace the literal `@Database(version = 10)` with `CURRENT_DATABASE_VERSION`.**
- [ ] **Step 2: Replace the factory's manual migration list with `.addMigrations(*CURRENT_DATABASE_MIGRATIONS)`.**
- [ ] **Step 3: Keep `runMigrationsAndValidate(5, ..., MIGRATION_4_5)` unchanged, but replace the later current-DAO open's manual 4→10 list with the shared current chain.**
- [ ] **Step 4: Run `./gradlew.bat :core:database:assembleDebugAndroidTest :core:database:testDebugUnitTest --stacktrace --console=plain`; expect success.**
- [ ] **Step 5: Commit as `refactor(database): use shared current migration chain`.**

### Task 3: Verify committed generated Room schema parity

**Files:**
- Modify: `core/database/build.gradle.kts`

**Interfaces:**
- Produces Gradle task: `verifyCurrentRoomSchema`
- Consumes: `CurrentDatabaseMigrations.kt`
- Consumes: `schemas/app.muxtv.database.MuxTvDatabase/<version>.json`

- [ ] **Step 1: Register `verifyCurrentRoomSchema` with explicit inputs and no declared outputs.**
- [ ] **Step 2: Parse `CURRENT_DATABASE_VERSION` from the source owner and require the matching generated JSON file.**
- [ ] **Step 3: Parse JSON and require `database.version == CURRENT_DATABASE_VERSION` and a nonblank `database.identityHash`.**
- [ ] **Step 4: Run `git status --porcelain=v1 -- <schema path>` and fail if Room generated an uncommitted addition or modification.**
- [ ] **Step 5: Wire the guard after `copyRoomSchemas` together with the existing evidence publication task.**
- [ ] **Step 6: Run `./gradlew.bat :core:database:copyRoomSchemas :core:database:verifyCurrentRoomSchema --stacktrace --console=plain`; expect committed v10 schema acceptance.**
- [ ] **Step 7: Commit as `build(database): verify current generated Room schema parity`.**

### Task 4: Acceptance and repository truth

**Files:**
- Update PR description and issue #121 evidence only; no README state change until merge.

- [ ] **Step 1: Open a draft PR from the fresh branch and document that the old pre-#123 branch is superseded.**
- [ ] **Step 2: Run Self-hosted Full and the existing Database DeviceMatrix on the exact head.**
- [ ] **Step 3: Confirm current migration tests, schema export, API26/API36 database suites, and zero unresolved review threads.**
- [ ] **Step 4: Review the final diff for accidental schema/runtime changes and secret-bearing diagnostics.**
- [ ] **Step 5: Mark ready and squash-merge with `Closes #121` only after exact-head evidence is green.**
