---
status: accepted
last_reviewed: 2026-07-19
owners: [product, architecture, data, ui]
---

# Профили MuxTV

## 1. Цель

Профиль изолирует пользовательский опыт одного зрителя внутри одной установки MuxTV. Профиль не является аккаунтом, облачным пользователем, ролью или способом лицензирования.

## 2. Базовая модель

После первого запуска приложение атомарно создаёт один профиль:

```text
ProfileId: generated UUID
name: Основной
isPrimary: true
state: Active
```

Правила:

- в установке всегда существует ровно один primary profile;
- primary profile нельзя удалить;
- primary profile можно переименовать, сменить аватар и полностью настроить;
- дополнительные профили создаёт и называет только пользователь;
- предустановленные профили «Дети», «Родители», «Гости» запрещены;
- поле `profileType` в domain/database отсутствует;
- ограничения контента задаются отдельной `ProfilePolicy`, поэтому любой профиль может быть ограниченным или неограниченным;
- пока существует один профиль, приложение не показывает profile picker на старте;
- после создания второго профиля пользователь выбирает: открывать последний профиль, Основной профиль или показывать picker.

## 3. Данные установки и данные профиля

### Installation-scoped

Общие для всех профилей:

- Source и provider credentials;
- загруженный provider catalog;
- CanonicalChannel и StreamVariant;
- EPG sources и базовые EPG bindings;
- health history и device capabilities;
- release/update state;
- extension installation and grants;
- общие network/security policies.

### Profile-scoped

Отдельные для каждого профиля:

- Favorites;
- recently watched и playback history;
- last channel;
- custom groups;
- channel ordering и numbering overlay;
- hidden channels/groups;
- manual channel display name/logo overlay;
- profile-specific EPG override, если пользователь намеренно отличается от общего binding;
- default audio/subtitle language;
- UI mode, density preset, theme, reduced motion и high contrast;
- search history;
- playback preference profile: quality/stability/data saving;
- policy bindings и PIN requirement.

Источники не копируются в профиль. Это предотвращает расхождение credentials, многократный refresh и ненужное увеличение базы.

## 4. Domain entities

```kotlin
@JvmInline value class ProfileId(val value: String)

data class UserProfile(
    val id: ProfileId,
    val name: String,
    val isPrimary: Boolean,
    val avatar: ProfileAvatar?,
    val state: ProfileState,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ProfilePolicy(
    val profileId: ProfileId,
    val requiresPinForSwitch: Boolean,
    val requiresPinForSettings: Boolean,
    val hiddenRatings: Set<ContentRating>,
    val hiddenGroups: Set<CanonicalGroupId>,
    val viewingSchedule: ViewingSchedule?,
)
```

`UserProfile` не содержит признаков child/adult/guest/admin. Административные действия проверяются через installation policy, а не через название профиля.

## 5. Жизненный цикл

```text
Created → Active → Archived → Deleted
            ↑          ↓
            └──────────┘ restore
```

- primary profile остаётся `Active` и не может перейти в `Deleted`;
- дополнительный профиль сначала архивируется;
- физическое удаление выполняется только после grace period или явного подтверждения «Удалить навсегда»;
- удаление профиля каскадно удаляет только profile-scoped rows;
- installation-scoped sources, channels, EPG и credentials не удаляются;
- последнее активное состояние нельзя удалить без автоматического переключения на primary profile;
- операции create/rename/archive/restore/delete транзакционны.

## 6. Переключение

Переключение профиля:

1. завершает запись profile-local UI state текущего профиля;
2. отменяет только profile-scoped search/load jobs;
3. не пересоздаёт provider catalog и player service без необходимости;
4. применяет preferences нового профиля;
5. восстанавливает допустимый последний экран и фокус;
6. если канал скрыт политикой нового профиля, playback прекращается с понятным сообщением и переходом на разрешённый экран.

Переключение не должно очищать общий Media3 cache, EPG cache или пересоздавать Room database.

## 7. PIN и ограничения

- PIN — не криптографическая защита от владельца устройства, а бытовой барьер в UI;
- PIN хранится как salted adaptive password hash, не в plaintext;
- приложение ограничивает частоту попыток и вводит временную задержку;
- recovery выполняется только через installation-level recovery code/export либо очистку policy с подтверждённым доступом к настройкам устройства;
- скрытие контента не должно зависеть только от слов в названии категории;
- автоматические adult heuristics могут предложить политику, но не применяют её без подтверждения;
- parental restrictions не дают ложного обещания защиты при root/ADB/доступе к данным приложения.

## 8. Backup и restore

Backup schema хранит:

- primary marker;
- profile metadata;
- profile-scoped overlays и policy;
- ссылки на installation-scoped entities через stable IDs;
- version и provenance.

При restore:

- primary profile текущей установки сохраняет identity;
- imported primary данные merge-ятся в primary либо импортируются как новый профиль по выбору пользователя;
- конфликт имён не является identity conflict;
- missing channel references сохраняются как unresolved overlays с retention window;
- credentials не включаются по умолчанию.

## 9. UX

Profile manager показывает:

- Основной профиль первым и с меткой «Основной»;
- пользовательские профили в заданном порядке;
- «Создать профиль»;
- настройки старта;
- экспорт/удаление конкретного профиля.

Создание профиля требует только имени. Аватар, копирование настроек и ограничения являются необязательными следующими шагами.

Недопустимо:

- заставлять выбирать профиль при единственном Основном;
- показывать шаблоны «ребёнок/родитель/гость» как обязательные варианты;
- связывать expert mode с правами администратора;
- молча копировать историю или ограничения из другого профиля;
- удалять общие источники при удалении профиля.

## 10. Критерии приёмки

- чистая установка открывается сразу в Основном профиле;
- primary profile остаётся после любых операций удаления;
- создание дополнительного профиля не дублирует источники и EPG;
- favorites/history/order полностью изолированы;
- смена профиля не вызывает полный source refresh;
- PIN policy работает для любого профиля;
- удаление дополнительного профиля не меняет данные других профилей;
- backup/restore сохраняет stable references и не раскрывает credentials;
- profile switch восстанавливает корректный focus target и не оставляет запрещённый playback.