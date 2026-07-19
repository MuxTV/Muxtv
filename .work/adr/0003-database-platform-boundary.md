---
status: accepted
last_reviewed: 2026-07-19
---

# ADR-0003: Database remains Android-first until a second client exists

## Context

Room 3.0 provides a new Kotlin Multiplatform-oriented API and MuxTV wants platform-neutral domain/parsing/matching logic. However, the only committed runtime platform is Android TV/Google TV/Fire TV. Making the complete database KMP from the empty-repository stage would introduce new plugin/tooling/API risk without demonstrated reuse.

The largest early data risks are not platform portability but:

- atomic source/EPG revisions;
- Room schema/migrations;
- query performance on weak TVs;
- WAL/storage/process-death behavior;
- profile/user-overlay isolation;
- large XMLTV/catalog handling.

## Decision

Phase 00–03 database implementation is Android-first Room/SQLite behind platform-neutral repository/domain contracts.

- domain IDs/models/use cases do not import Room or Android;
- parsers/matchers may be pure Kotlin/JVM or KMP-compatible without forcing database KMP;
- Room entities, DAO, migrations and transaction orchestration live in `core/database` Android module;
- schema export/migration tests are mandatory;
- no direct DAO exposure to feature/domain;
- database API is designed so a future desktop/server storage adapter can implement the same ports;
- Room 3.0 may be used for Android-only implementation after scaffold prototype verifies plugin/KSP/toolchain stability;
- actual KMP database target requires a second client need, prototype, migration plan and new ADR.

## Rationale

This preserves clean boundaries and most future portability while avoiding premature build complexity. It also makes it easier to use Android-specific lifecycle/storage/instrumentation facilities during the high-risk early stages.

## Rejected alternatives

### Full KMP database from first commit

Rejected because there is no second platform, Room 3 is newly stabilized, and it expands Gradle/KSP/driver/testing complexity before domain/schema behavior is proven.

### Plain SQLite custom layer

Rejected for baseline because Room provides schema verification, migrations, DAO compile-time checking and Android instrumentation integration. A custom layer can be reconsidered only with measured limitations.

### Single app module database access

Rejected because it would leak DAO/Room types into features and make profile/source/playback tests difficult.

## Consequences

Positive:

- smaller Phase 00 risk;
- platform-neutral domain remains portable;
- better Android instrumentation/migration focus;
- KMP adoption remains evidence-based.

Negative:

- future second client may require a new storage adapter or Room KMP migration;
- some DTO/schema mapping duplication may arise;
- database module itself is not portable initially.

## Revisit trigger

Review when one condition is true:

1. a desktop/server/iOS client is approved;
2. Room Android implementation blocks required test/performance behavior;
3. Room KMP has demonstrated stable migration/tooling path in a prototype;
4. a shared offline database provides clear product value exceeding migration cost.

Review requires benchmark, schema migration experiment, build-time impact and exact platform requirements.