# `.work` — рабочая система MuxTV

Эта папка является единым источником проектной документации, архитектурных решений, планов и метаданных. Документы вне `.work` не должны дублировать детальные требования; корневой `README.md` остаётся краткой публичной витриной.

## Навигация

- [`PROJECT.md`](PROJECT.md) — миссия, принципы, границы и терминология.
- [`PRODUCT.md`](PRODUCT.md) — пользователи, проблемы, сценарии и продуктовые требования.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — целевая архитектура и правила зависимостей.
- [`ROADMAP.md`](ROADMAP.md) — последовательность реализации.
- [`CURRENT-STATE.md`](CURRENT-STATE.md) — фактическое состояние репозитория.
- [`architecture/`](architecture/) — domain model, source refresh, data flow, modules, playback runtime and extensions.
- [`specifications/`](specifications/) — normative behavioral/protocol requirements, critical user journeys and traceability.
- [`design/`](design/) — TV Design System, D-pad/focus and accessibility.
- [`security/`](security/) — threat model and network/source policy.
- [`release/`](release/) — signing, GitHub Releases and self-update trust.
- [`platforms/`](platforms/) — platform-specific requirements; Fire TV is detailed first.
- [`adr/`](adr/) — Architecture Decision Records.
- [`quality/`](quality/) — quality gates, benchmark/fault/endurance methodology and performance budgets.
- [`research/`](research/) — official sources, technology evaluation and critical reference-repository review.
- [`plans/`](plans/) — executable implementation plans.
- [`meta/`](meta/) — machine-readable state, invariants, versions, scoring and error catalogs.

## Нормативные документы по подсистемам

| Область | Документы |
|---|---|
| Project/product | `PROJECT.md`, `PRODUCT.md`, `specifications/user-journeys.md`, `specifications/requirements-traceability.md` |
| Profiles | `specifications/profiles.md`, `adr/0004-profile-and-installation-scope.md`, `meta/profiles.yaml` |
| Catalog/data | `architecture/domain-model.md`, `architecture/source-refresh.md`, `architecture/data-flow.md` |
| M3U/XMLTV | `specifications/m3u-ingestion.md`, `specifications/xmltv-processing.md` |
| Playback | `architecture/playback-runtime.md`, `specifications/playback-errors.md`, `meta/playback-error-catalog.yaml` |
| Smart Channels/Doctor | `specifications/smart-channels.md`, `specifications/tv-doctor.md`, `meta/scoring-model.yaml` |
| Search | `specifications/search.md` |
| Backup/restore | `specifications/backup-and-restore.md` |
| UI | `design/focus-navigation.md`, `design/design-system.md` |
| Local phone setup | `specifications/local-control.md` |
| Extensions | `architecture/extensions.md` |
| Security | `security/threat-model.md`, `security/network-and-source-policy.md` |
| Release/update | `release/self-update-and-signing.md` |
| Fire TV | `platforms/fire-tv.md` |
| Performance/quality | `quality/quality-gates.md`, `quality/benchmark-methodology.md` |
| Stack decisions | `adr/0001-platform-and-stack.md`, `adr/0002-kmp-and-rust-policy.md`, `adr/0003-database-platform-boundary.md`, `research/technology-evaluation.md` |
| External evidence | `research/official-sources.md`, `research/reference-repositories.md` |
| Phase 00 | `plans/2026-07-19-phase-00-foundation.md` |

## Правила ведения

1. Любое крупное архитектурное решение оформляется ADR до реализации.
2. `CURRENT-STATE.md` and `meta/status.yaml` reflect only verified facts, not intent.
3. Dependency versions change in `meta/dependencies.yaml` and then version catalog; architectural behavior change also requires ADR.
4. Unfinished ideas remain deferred and are not smuggled into mandatory scope.
5. Document is current when `last_reviewed` is within 90 days or reviewed in active milestone.
6. Architectural boundary change updates `ARCHITECTURE.md`, module map/meta and traceability.
7. Reference repository is never a blueprint: review official docs, current code/tests/issues/license and MuxTV applicability.
8. Machine-readable meta complements normative Markdown and must pass consistency/link/schema checks.
9. Automatic catalog/Doctor mutation must be explainable, previewable, journaled and reversible where impactful.
10. Profile model has no pre-created household roles: one primary profile and only user-created additional profiles.
11. Remote data and extensions are untrusted; new trust boundary requires threat review.
12. Requirement is not complete until implementation and specified evidence are recorded.
13. Large traces/APKs/corpora are workflow artifacts or external test assets, not committed to `.work`.
14. Factual phase completion requires fresh verification commands and `.work/reviews` evidence.

## Статус документов

Allowed values: `draft`, `accepted`, `superseded`, `deprecated`. Accepted documents define current baseline until explicit revision/ADR. Implementation plans are active only when their phase starts; completed tasks update factual state and review evidence.