---
status: accepted
date: 2026-07-19
deciders: [MuxTV]
---

# ADR-0002: Границы Kotlin Multiplatform и политика Rust

## Контекст

MuxTV потенциально получит phone companion, desktop service и другие клиенты. Требуется сохранить возможность reuse, не усложняя первый Android TV релиз.

## Решение по KMP

KMP применяется только к platform-neutral библиотекам:

- domain model;
- normalization/matching;
- parser contracts и чистые parser implementations;
- stream scoring;
- backup schema;
- search interpretation;
- extension DTO/contracts.

Android application, Compose TV UI, Media3, Room wiring, WorkManager и PackageInstaller остаются Android-specific.

Для shared libraries используется официальный `com.android.kotlin.multiplatform.library`. Application entry point находится в отдельном Android module.

## Решение по Rust

Rust не добавляется в Phase 00–02. Сначала создаётся Kotlin implementation и benchmark corpus. Rust/UniFFI разрешается только для конкретного hot path после ADR с результатами.

Минимальные условия принятия Rust implementation:

- не менее 25% выигрыша CPU time либо 30% снижения peak memory на целевом corpus;
- отсутствие ухудшения cold start более чем на 50 ms;
- допустимый прирост compressed APK;
- полноценные Kotlin contract tests и Rust tests;
- symbolized native crash diagnostics;
- reproducible builds для поддерживаемых ABI.

## Причины

- KMP позволяет переиспользовать логику без компромисса TV UI;
- ранний Rust создаёт NDK, ABI, FFI и debugging cost до доказанной пользы;
- интерфейс `CatalogComputeEngine` позволяет заменить implementation позже.

## Последствия

- shared code обязан избегать Android types;
- не каждая библиотека автоматически становится KMP module;
- Rust считается оптимизацией, а не маркетинговой особенностью;
- libmpv регулируется отдельным ADR, поскольку относится к playback engine, а не compute core.