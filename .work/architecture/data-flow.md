---
status: accepted
last_reviewed: 2026-07-19
---

# Потоки данных каталога и EPG

## Import/refresh pipeline

```text
Source request
  → download stream
  → bounded parser batches
  → staging tables
  → validation report
  → normalization
  → identity reconciliation
  → duplicate candidates
  → EPG matching
  → transactional publish
  → search-index refresh
  → cleanup old staging data
```

## Гарантии

- Активный каталог остаётся доступным до успешного publish.
- Отмена или падение refresh не оставляет наполовину обновлённые данные.
- URL и credentials не используются как стабильная identity канала.
- Source-native identifiers сохраняются, но canonical identity формируется отдельно.
- User overlays применяются после provider refresh.
- Все автоматические объединения имеют confidence и provenance.
- Неуверенные изменения требуют подтверждения пользователя.

## Identity reconciliation

Сопоставление старой и новой provider-записи выполняется по убыванию доверия:

1. стабильный provider ID;
2. нормализованный URL fingerprint без короткоживущих токенов;
3. `tvg-id` + source identity;
4. normalized name + group + language/country;
5. fuzzy candidate, который не применяется автоматически ниже порога.

## Canonical channel

`CanonicalChannel` не принадлежит одному плейлисту. Он содержит ссылки на `StreamVariant`, выбранную EPG binding и user overlay. Удаление source удаляет только принадлежащие ему варианты; canonical channel удаляется, когда не осталось вариантов и пользователь не закрепил его вручную.

## EPG storage policy

- XMLTV разбирается потоково.
- В БД хранится rolling window: по умолчанию 2 дня назад и 14 дней вперёд.
- Исторические записи очищаются отдельной maintenance job.
- Timezone source нормализуется в UTC; отображение происходит в timezone профиля/устройства.
- Программы индексируются для поиска по title, subtitle, category и participants, когда данные доступны.

## Backup

Backup является версионированным архивом и содержит:

- sources без секретов по умолчанию;
- canonical channels и overlays;
- EPG bindings;
- profiles и parental rules;
- app settings;
- schema/version manifest и checksums.

Секреты экспортируются только отдельной явной опцией с паролем и authenticated encryption.