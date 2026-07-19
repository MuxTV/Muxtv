---
status: accepted
last_reviewed: 2026-07-19
owners: [local-control, security, ui, network]
---

# Local control and QR pairing

## 1. Цель

Локальная web-панель позволяет вводить длинные M3U/XMLTV/provider credentials, редактировать каталог и выполнять ограниченное дистанционное управление с телефона в одной сети. Она не требует облачного аккаунта и не превращает телевизор в постоянно открытый web server.

## 2. Activation model

Server starts only when:

- user opens «Настроить с телефона»/pairing screen;
- an already paired device uses a user-enabled bounded remote-control window;
- a foreground operation explicitly requires it.

Default:

- disabled outside active pairing/session;
- not started on boot;
- no UPnP port mapping;
- no Internet exposure;
- visible TV indicator while accepting connections.

## 3. Pairing flow

1. TV generates cryptographically random one-time token and ephemeral pairing session;
2. TV shows QR containing LAN URL plus token fragment/nonce, never provider credentials;
3. phone opens HTTPS if feasible with local certificate strategy, otherwise HTTP on LAN with application-level authenticated session and clear warning/constraints;
4. TV displays client/device summary and explicit confirmation;
5. successful pairing exchanges session key/capability grant;
6. one-time token invalidated;
7. session expires after inactivity/absolute lifetime;
8. paired device can be named/revoked from TV.

QR token entropy and lifetime prevent manual brute force. Token must not appear in normal logs/history where avoidable.

## 4. Network binding

- bind only to selected active LAN interfaces;
- reject loopback-only ambiguity and public/cellular interfaces;
- port dynamically selected or configurable expert setting;
- URL uses actual reachable private address and handles IPv4/IPv6;
- network change invalidates current advertised endpoint and may require re-pair/reconnect;
- requests validate local address/interface and session;
- Host header is validated; DNS rebinding/public host tricks rejected;
- server refuses forwarding/proxying arbitrary URLs.

## 5. Authentication/session

Session cookie/token:

- random, short-lived, HttpOnly/SameSite where browser model permits;
- bound to pairing record and capability set;
- rotated after privilege change;
- rate-limited;
- invalidated on revoke/server restart where policy requires;
- CSRF protection for state-changing actions;
- no credentials in URL query after pairing;
- websocket/SSE authenticated separately and origin-checked.

## 6. Capabilities

```text
source.create
source.edit_nonsecret
source.set_credentials
source.test
source.refresh
source.delete
catalog.read
catalog.edit_overlays
epg.manage
profile.manage
profile.switch
playback.control
doctor.run
doctor.read
backup.export
backup.import
settings.read
settings.edit_safe
diagnostics.export
```

Pairing defaults to setup capabilities needed by active flow. Sensitive actions require additional TV confirmation or installation PIN:

- reveal/replace credentials;
- delete source/profile;
- import/restore backup;
- extension/update/security changes;
- diagnostic export containing hostnames;
- reset database.

The phone never receives existing plaintext provider password/token. It may replace it through a write-only field.

## 7. API architecture

```text
Embedded Ktor server
 → auth/capability middleware
 → DTO validation and size limits
 → application use cases
 → domain ports
```

Web API does not access Room DAO, Media3 or Android services directly. DTO version independent from internal entities.

Endpoints are task-oriented, not generic database CRUD. Example:

```text
POST /v1/sources/validate
POST /v1/sources
POST /v1/sources/{id}/refresh
GET  /v1/catalog/channels?...bounded filters...
PATCH /v1/profiles/{id}/channel-overlays/{channelId}
POST /v1/playback/channel/{channelId}
POST /v1/doctor/audits
```

Pagination, payload limits, optimistic concurrency/version fields and typed errors mandatory.

## 8. Web UI

- responsive phone-first static SPA served locally;
- no CDN/runtime external scripts;
- assets bundled and content-security-policy restricted;
- accessible labels/contrast;
- password managers allowed for write-only credential fields where safe;
- progress and cancellation for validation/import/refresh;
- destructive preview/confirmation;
- session expiration clearly shown;
- TV remains source of truth and reflects changes live.

Do not duplicate entire TV visual design; phone UI optimizes text entry and management.

## 9. Import flow

Phone can submit:

- playlist/EPG URL;
- local phone file uploaded with strict limits;
- provider form/credentials;
- backup file.

Pipeline:

1. bounded upload/URL validation;
2. TV-side network/security validation;
3. parser/staging preview;
4. show counts/warnings/groups;
5. user confirms on phone; sensitive source policy may also require TV confirmation;
6. atomic save/commit.

Phone browser never fetches provider URL on behalf of TV as authoritative validation because CORS/network path/credentials differ.

## 10. Playback control

Optional controls:

- play selected channel;
- channel up/down/previous;
- pause/seek only if capability exists;
- open guide/programme;
- volume not controlled unless Android/platform capability explicitly supports safe behavior.

TV displays remote action feedback. Local remote cannot bypass current profile policy/PIN. Switching to restricted channel is denied.

## 11. Secrets

- existing secrets never returned;
- new credentials transmitted only in authenticated paired session;
- HTTPS/local encryption strategy investigated; when HTTP is used on trusted LAN, UI states limitation and session remains short-lived;
- secrets converted immediately to credential store reference;
- request body not logged;
- crash/error response redacted;
- browser autocomplete/history does not receive secrets through URLs;
- clipboard remains user-controlled and outside app guarantee.

## 12. Abuse controls

- per-IP/session/token rate limits;
- maximum concurrent sessions (initially small, e.g. 3);
- endpoint-specific payload/operation limits;
- upload/decompression/parser limits identical to TV imports;
- no arbitrary filesystem browse;
- no shell/ADB/package install endpoint;
- no generic HTTP proxy;
- long jobs durable and detached from request, with bounded polling/events;
- repeated failed pairing triggers cooldown/new token.

## 13. Lifecycle

- closing pairing screen cancels unused pairing token;
- active paired management session may continue for configured short window with TV indicator;
- app background/process death invalidates ephemeral server sessions safely;
- durable jobs continue through normal work pipeline, not Ktor coroutine only;
- restart requires session re-authentication;
- network interface change updates endpoint and invalidates unsafe assumptions.

## 14. Diagnostics

Audit records:

```text
session/pairing opaque ID
client label/address family
capability/action
result and timestamp
correlation ID
```

No request bodies, credentials or full URLs. User can view/revoke devices and clear audit history.

## 15. Tests

- token entropy/expiry/replay;
- failed pairing rate limit;
- TV confirmation and cancel;
- Host/origin/CSRF/websocket validation;
- public/cellular/non-LAN connection rejection;
- IPv4/IPv6/network change;
- capability denial and privilege escalation;
- secret not returned/logged;
- upload/archive/XML limits;
- session expiration/revocation;
- process death during import/refresh;
- remote playback obeys profile restrictions;
- destructive actions require confirmation;
- CSP/no external web assets.

## 16. Acceptance criteria

- user can add M3U/XMLTV without typing URL by remote;
- server is not permanently exposed by default;
- one-time QR cannot be replayed after pairing;
- unpaired LAN client cannot read catalog/settings or control playback;
- phone cannot retrieve existing credentials;
- local panel cannot fetch arbitrary internal URLs as proxy;
- sensitive actions require scoped capability/confirmation;
- closing/revoking session stops access predictably;
- all long operations use durable application pipelines.