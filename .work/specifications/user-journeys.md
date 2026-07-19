---
status: accepted
last_reviewed: 2026-07-19
owners: [product, ui, architecture, quality]
---

# Critical user journeys

## 1. Правила

Каждый journey содержит preconditions, happy path, recoverable failures, persistent effects and measurable acceptance. Screens/labels may evolve, but behavioral contracts require ADR/spec update.

## J01 — Fresh install to first Home

**Preconditions:** new app data, no profiles/sources.

**Flow:**

1. application initializes database/schema;
2. creates exactly one primary profile `Основной` atomically;
3. opens Home/onboarding context without profile picker;
4. focus lands on safe primary action: add source/setup;
5. user can inspect offline app before granting any network/source permission.

**Failures:** DB initialization displays recovery/export/reset path; no loop/crash.

**Acceptance:** first interactive shell within startup budget; one profile only; no `Дети/Родители/Гости`; Back/focus predictable.

## J02 — Add M3U using phone QR

**Preconditions:** primary profile, no source, phone same LAN.

**Flow:**

1. TV opens «Настроить с телефона»;
2. shows short-lived QR/pairing token;
3. phone connects; TV confirms client;
4. phone enters M3U URL/name/optional safe settings;
5. TV validates address/security, fetches bounded payload, parses to staging;
6. phone/TV shows counts/warnings/groups;
7. user confirms;
8. atomic source/catalog commit;
9. TV opens channel list with focus on first valid/selected group.

**Failures:** unreachable URL, private host approval, cleartext warning, malformed playlist, gzip bomb/limits, process death, expired session. Existing catalog unchanged.

**Acceptance:** no URL typing by remote; unpaired client no access; credentials not returned/logged; cancel leaves no source; source visible after commit.

## J03 — Add/edit source directly on TV

**Flow:** use system keyboard/file picker; validate before save; retain old catalog until successful refresh after endpoint edit.

**Acceptance:** editing URL/credentials keeps SourceId/overlays; failed edit validation can be cancelled without losing old source.

## J04 — Watch and zap live channels

**Preconditions:** active catalog with playable channels.

**Flow:**

1. user selects canonical channel;
2. app ranks allowed variants;
3. resolves current locator;
4. prepares Media3 and renders first stable frame/audio;
5. player overlay shows channel/programme/now-next;
6. Up/Down or channel keys zap; numeric buffer can jump to profile number;
7. Back closes overlay then returns to previous catalog context.

**Failures:** recovery overlay non-blocking/cancellable; bounded retries/failover; final safe error.

**Acceptance:** current metadata never mismatches target channel; no stale audio/video; Activity recreation does not end session; zapping measured by stable frame/audio.

## J05 — Primary fails, reserve succeeds

**Flow:** current variant fails; orchestrator classifies, applies permitted retry/locator refresh, selects reserve respecting cooldown/hysteresis; playback continues; optional brief notice.

**Acceptance:** no return to catalog; failed variant not looped; manual pin policy respected; attempt chronology/health updated without secrets.

## J06 — Refresh source without losing personalization

**Preconditions:** favorites/order/custom display/manual EPG exist.

**Flow:** fetch/decode/parse staging; diff/reconcile; suspicious churn guard; atomic commit; overlays compose over new provider data; summary shown.

**Failures:** count drop/identity churn/constraint/process death rejects revision and keeps previous active catalog.

**Acceptance:** favorite/order/name/manual binding/history retained; tokenized URL changes variant locator rather than channel identity.

## J07 — Import XMLTV and use guide

**Flow:** add URL/file; secure parse/staging; timezone/conflict report; match proposals; commit; guide opens on current time/current channel; lazy scroll future/past.

**Failures:** `.xml.gz`, missing timezone, duplicate IDs/programmes, invalid new revision. Previous guide stays active.

**Acceptance:** no external DTD fetch; manual binding preserved; Back/player→guide restores channel/time context; memory bounded.

## J08 — Resolve unmatched EPG

**Flow:** user opens channel EPG settings; sees candidates/evidence/confidence; selects binding; profile/global scope explicit; guide updates; decision survives refresh.

**Acceptance:** no silent override; wrong mapping reversible; same channel name in different region not auto-selected without strong evidence.

## J09 — Smart Channel merge and split

**Flow:** app proposes possible duplicate with evidence; user previews variants/overlays/EPG impact; confirms merge; later splits a feed; exact inverse journal supports undo.

**Acceptance:** hard conflict prevents auto-merge; manual split persists across algorithm/source refresh; profile overlays preserved/explicit conflicts resolved.

## J10 — Run TV Doctor

**Flow:** choose quick/full/source audit; scheduler shows coverage/progress; collects bounded evidence; summary distinguishes working/unstable/unavailable-now/unmatched; user previews safe fixes; selects/apply; undo available.

**Acceptance:** audit does not degrade foreground playback; user can cancel/resume; no destructive/uncertain silent fix; source/provider rate limits respected.

## J11 — Create and switch profile

**Preconditions:** only primary profile.

**Flow:** profile manager → create; asks only user-defined name initially; optional copy selected preferences/restrictions explicit; second profile activates according user choice; startup picker mode can be set.

**Acceptance:** no built-in roles/type; sources/base catalog not duplicated/refreshed; favorites/history/order isolated; switch restores allowed route/focus.

## J12 — Apply restrictions to any profile

**Flow:** select any profile; configure PIN/settings/content/group/schedule policy; save; test switching/settings/playback restrictions.

**Acceptance:** policy independent of profile name/type; restricted content absent from search/browser; remote/local-control cannot bypass; PIN described as household barrier.

## J13 — Delete additional profile

**Flow:** select additional profile; preview profile-scoped data removal; archive default; restore possible; permanent delete explicit.

**Acceptance:** primary cannot delete; active context switches safely; installation sources/credentials/canonical/EPG unaffected; other profiles unaffected.

## J14 — Search on TV

**Flow:** open Search, enter/voice query such as «футбол сейчас»; deterministic local intent/retrieval; results grouped; select channel/programme; Back exits keyboard then route.

**Acceptance:** exact result priority; focus stable during updates; restricted data filtered; works offline from cached catalog/EPG; no LLM required.

## J15 — Backup and restore

**Flow:** export configuration with no secrets by default through SAF; on destination/import parse to staging, verify, show profile/source/conflict impact; merge/replace selected; checkpoint; atomic commit; post-refresh.

**Acceptance:** no second primary; invalid/cancelled restore changes nothing; source/profile references preserved/unresolved explicitly; no all-files permission; secret option encrypted/separate.

## J16 — Self-update from GitHub

**Flow:** check official metadata; show version/notes/size; user downloads; verify hash/package/version/certificate; PackageInstaller prompt; update; migration startup verifies.

**Failures:** GitHub offline, hash/cert/package mismatch, disk, user deny, migration error.

**Acceptance:** app playback not blocked by check; wrong/downgrade rejected; user/system approval mandatory; nightly cannot overwrite stable.

## J17 — Fire TV basic flow

Install/sideload → launch without GMS → remote onboarding → source import → live playback → Home/return → update/backup access.

**Acceptance:** physical Fire reference device passes remote/lifecycle/network/codec/low-end performance gates.

## J18 — Diagnostic export

**Flow:** user opens diagnostics; chooses scope; preview shows included device/host/error categories and redactions; export through SAF.

**Acceptance:** canary secrets absent; raw passwords/tokens/cookies/PIN absent; correlation chronology useful; local paths/host disclosure explicit.

## Cross-journey quality gates

- every screen has deterministic initial/return focus;
- all essential actions available by five-button D-pad/Back;
- no long task blocks UI or holds DB transaction;
- errors have stable code/action/correlation ID;
- destructive action has preview/confirmation and recovery where feasible;
- profile policy enforced across TV UI, search, playback and local control;
- remote input/data considered untrusted;
- factual implementation state updated only after verified tests.