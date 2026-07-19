---
status: accepted
last_reviewed: 2026-07-19
owners: [security, architecture, network, release]
method: STRIDE-inspired asset and trust-boundary analysis
---

# Threat model MuxTV

## 1. Security posture

MuxTV принимает URLs, playlists, XMLTV, images, provider credentials, extension metadata и APK updates из внешних источников. Все внешние данные считаются недоверенными. Цель — ограничить воздействие на приложение, устройство, домашнюю сеть и приватность пользователя.

MuxTV не обещает защиту от владельца root/ADB-доступа или физического извлечения данных с полностью скомпрометированного устройства.

## 2. Assets

- provider credentials, tokens, cookies, MAC/device IDs;
- profile preferences, history, favorites и PIN policy;
- source/catalog/EPG database;
- signing key identity и update trust state;
- local-control pairing secrets;
- extension grants;
- diagnostics and logs;
- device/network identifiers;
- availability/performance of TV device;
- integrity of channel/EPG mapping and user overlays.

## 3. Trust boundaries

```text
Remote provider / playlist / EPG / image host
            ↓
Network policy + bounded fetch/decode/parser
            ↓
Staging database
            ↓ validated transaction
Active catalog/database
            ↓
Domain/use cases
            ↓
TV UI / Player

Phone browser on LAN
    ↔ paired local-control server

GitHub Releases/update metadata
    → verification pipeline
    → Android PackageInstaller

Companion extension APK
    ↔ versioned Binder/AIDL boundary
```

Crossing a boundary always requires validation, redaction and capability checks.

## 4. Threat actors

- malicious or compromised IPTV/EPG/image host;
- honest provider with malformed/unstable data;
- network attacker on LAN/public Wi-Fi;
- malicious playlist author;
- malicious companion APK;
- supply-chain attacker targeting dependencies/workflows/releases;
- another local application/process;
- untrusted person with remote control access;
- accidental user action;
- abusive batch probing that overloads provider/device.

## 5. Major threats and controls

### SSRF and local network access

Threat: playlist/logo/EPG URLs target localhost, link-local, router/admin services, cloud metadata or private hosts.

Controls:

- per-source network scope: PublicOnly or UserApprovedPrivateNetwork;
- resolve and validate every address, including redirects;
- block loopback/link-local/multicast/unspecified by default;
- private IP requires explicit source-level approval and clear UI;
- prevent URL userinfo leakage;
- validate IPv4 and IPv6 forms, mapped/obfuscated addresses;
- DNS rebinding protection by revalidating connected address/redirect;
- no generic URL fetch endpoint in local-control server.

### Credential leakage

Threat: Authorization/Cookie/Referer/token follows redirect, enters logs, backup or diagnostics.

Controls:

- credentials stored by opaque reference;
- origin-bound header policy;
- drop sensitive headers on cross-origin redirect unless explicitly allowed;
- redact URL userinfo/query and headers centrally;
- secrets excluded from backup by default;
- debug logging cannot bypass redaction in release;
- tests use canary secrets and property-based detection.

### XML/Archive/Decompression DoS

Threat: XXE, external DTD, Billion Laughs, deep nesting, zip/gzip bomb, huge text/line/image.

Controls:

- secure pull parser, DTD/entities/XInclude disabled;
- compressed/decompressed/ratio/entry/depth limits;
- bounded streaming parse and cancellation;
- temporary files in app-private storage with quota;
- image dimension/decode limits;
- partial revision never committed;
- watchdog/metrics and cleanup after process death.

### Malicious media/codec input

Threat: malformed stream triggers codec/native crash, resource leak or endless retry.

Controls:

- Media3 stable versions and dependency monitoring;
- isolated playback adapter/state machine;
- bounded retries and decoder fallback;
- no arbitrary native extension in main process;
- libmpv optional separate flavor/process consideration;
- device evidence/circuit breaker;
- crash-safe service recovery and redacted report.

### Catalog integrity manipulation

Threat: provider renames/removes/reuses IDs causing wrong merge, hidden channels or lost favorites.

Controls:

- immutable source revisions/staging;
- user overlays separated;
- suspicious churn/count gates;
- conservative Smart Channel thresholds;
- provenance for displayed metadata;
- merge/split preview and undo;
- manual decisions persist;
- tombstones/retention.

### Local-control hijacking

Threat: another LAN client controls TV, reads sources or changes settings.

Controls:

- disabled until user opens pairing flow;
- short-lived one-time pairing token shown on TV;
- explicit on-TV confirmation for first device;
- session keys and expiration;
- origin/host validation and CSRF protection;
- no secrets returned to browser;
- read/write capabilities separated;
- rate limiting and local audit;
- revoke paired device;
- bind to selected interfaces, not wildcard by accident;
- avoid exposing service through UPnP/port forwarding.

### Profile/PIN bypass expectations

Threat: users assume PIN is strong account security.

Controls:

- document PIN as household UI barrier;
- salted adaptive hash and rate limiting;
- no profile role names/privilege inference;
- installation-level critical actions separately protected;
- restrict switching/settings based on policy;
- do not claim protection against root/ADB/app data access.

### Extension compromise

Threat: companion APK reads credentials, tampers catalog/player or breaks process.

Controls:

- no dynamic DEX/JS/native loading;
- separate APK/process and Binder/AIDL contract;
- package/signature identity and user approval;
- granular capability grants;
- no direct Room/database access;
- no raw global credential store;
- payload size/time/rate limits;
- version negotiation and kill switch/revocation;
- crashes/timeouts isolated;
- grants visible and revocable.

### Update/supply-chain attack

Threat: fake GitHub release, compromised workflow/dependency/signing key, downgrade.

Controls:

- fixed application ID and signing identity;
- Android signature verification remains final package trust;
- update metadata constrained to official repository/channel;
- verify versionCode, packageName, certificate digest and artifact checksum/signature;
- no silent installation; use PackageInstaller;
- reject downgrade unless explicit recovery mode;
- protected release workflow, minimal permissions, pinned actions by commit;
- dependency review/lockfiles/SBOM/secret scanning;
- signing key offline/secure storage and recovery plan;
- reproducible build comparison where feasible.

### Privacy leakage

Threat: history, source names, device/network data or stream viewing leaves device unexpectedly.

Controls:

- no mandatory account/telemetry;
- telemetry/crash upload opt-in by separate ADR;
- diagnostics export explicit and previewable;
- local logs bounded/redacted;
- no SSID/content samples by default;
- profiles/history local;
- network requests limited to user sources and approved update/reference endpoints.

## 6. Abuse and availability

- TV Doctor concurrency/rate limits;
- provider per-host circuit breakers;
- background jobs constrained by thermal/network/storage;
- UI remains usable while source/EPG refresh runs;
- database transactions short and staged;
- disk quotas for temp/cache/logs/EPG;
- no unbounded image preload;
- process death recovery and stale work leases;
- corrupted current revision can fall back to previous known-good revision.

## 7. Security events

Structured events without secrets:

```text
NetworkPolicyBlocked
CrossOriginCredentialStripped
RedirectRejected
ArchiveLimitExceeded
XmlExternalEntityRejected
SuspiciousSourceRevision
ExtensionCapabilityDenied
PairingAttemptRateLimited
UpdateSignatureMismatch
DiagnosticExportCreated
```

Security event retention bounded and user-readable where actionable.

## 8. Security testing

- SSRF matrix: IPv4/IPv6/private/link-local/localhost/decimal/hex/mapped/DNS rebinding;
- redirect credential stripping;
- XXE/Billion Laughs/deep XML;
- zip slip/nested archive/decompression bombs;
- oversized lines/attributes/logos/image dimensions;
- malicious M3U schemes and headers;
- fuzz parsers and provider adapters;
- Binder permission/signature/capability tests;
- pairing replay/CSRF/brute-force/session expiry;
- update wrong package/certificate/checksum/downgrade;
- redaction canary scans across logs/crashes/backups;
- dependency/workflow permissions review;
- restore corrupted backup and migration rollback.

## 9. Security review gates

Separate threat review required for:

- enabling cleartext broadly;
- adding new URL scheme/provider auth method;
- local web server feature expansion;
- executable extensions/native code;
- libmpv/FFmpeg/native library;
- cloud sync/telemetry;
- DVR/external storage;
- self-update changes;
- sharing/export credentials;
- DRM or paid-content integration.

## 10. Acceptance criteria

- remote inputs cannot read arbitrary local files;
- cross-origin redirect cannot receive source credentials by default;
- XXE/archive bombs fail safely with bounded resources;
- rejected source revision never corrupts active catalog;
- pairing requires TV-visible consent and expires;
- extension cannot access database/credentials without explicit bounded capability;
- updater rejects wrong package/signing certificate/downgrade;
- diagnostic export contains no canary secrets;
- security assumptions and residual risks remain documented.