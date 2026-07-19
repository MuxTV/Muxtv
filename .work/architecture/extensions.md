---
status: accepted
last_reviewed: 2026-07-19
owners: [architecture, extensions, security]
---

# Модель расширений

## 1. Цели и non-goals

Extensions may add provider/EPG/metadata/resolver behavior without turning MuxTV into an arbitrary code runtime.

Goals:

- evolve providers independently where useful;
- preserve stable host/domain contracts;
- least privilege and revocation;
- validate all extension output through normal pipelines;
- isolate crash, timeout, network and secret access;
- make compatibility testable.

Non-goals:

- loading random scripts/DEX/JAR/native libraries from playlist/network;
- plugin access to internal Room database/player/UI tree/files;
- Kodi/Chrome-like unrestricted plugin platform in early releases;
- plugin marketplace before signed identity, review and update policy.

## 2. Extension levels

### Level 1: Built-in adapter

Code in MuxTV repository/release:

- M3U/XMLTV baseline;
- approved core provider protocols;
- full CI, threat review, parser/fault corpus;
- direct dependency only through internal adapter modules.

Use when protocol is core, security-sensitive, performance-critical or requires Android/Media3 integration.

### Level 2: Declarative extension

Signed/versioned data manifest, no executable code.

Can describe:

- provider metadata and form fields;
- endpoint/request templates;
- bounded auth/token flow state machine from supported primitives;
- allowed headers and sensitivity;
- channel/category/field transforms;
- EPG alias maps;
- refresh/catch-up templates;
- network destinations and capabilities;
- compatibility/version metadata.

Cannot:

- execute JavaScript/shell/regex with unbounded behavior;
- read arbitrary local files/URLs;
- call undeclared destinations;
- access other providers' credentials;
- alter core security/update policies.

Manifest schema and expression language require bounded evaluation, deterministic output and conformance tests.

### Level 3: Companion APK

Separate Android package/process communicates through versioned Binder/AIDL service.

Use only when declarative primitives cannot represent protocol/authorization/resolution behavior.

Host discovers/binds only user-approved compatible services with declared intent/metadata. Package/signature identity saved at trust decision.

## 3. Contract families

```kotlin
interface ProviderExtension
interface EpgExtension
interface PlaybackResolverExtension
interface MetadataExtension
interface ConfigurationExtension
```

Contracts use stable serializable DTOs, typed IDs/errors and bounded streams/pages. No Room/Media3/Compose/OkHttp classes cross boundary.

### Provider capability examples

```text
source.describe_schema
source.validate_configuration
source.sync_catalog
source.resolve_stream
source.resolve_catchup
source.refresh_credentials
```

### EPG

```text
epg.describe_sources
epg.sync_channels_programmes
epg.resolve_aliases
```

### Metadata

```text
metadata.channel_logo
metadata.channel_aliases
metadata.enrich_programme
```

Playback extension does not receive player instance. It resolves typed request/candidates; Media3/libmpv engine remains host-owned.

## 4. Capability grants

Initial capability vocabulary:

```text
source.read_own_configuration
source.write_own_configuration
source.read_own_credentials_reference
source.use_own_credentials
network.request_declared_hosts
catalog.provide_channels
epg.provide
playback.resolve_locator
metadata.provide
ui.host_rendered_configuration
ui.native_configuration_activity
```

Rules:

- default deny;
- grants per extension/package/signature and installation;
- sensitive grants separate and TV-confirmed;
- extension cannot request global database/profile/history/playback access;
- grants visible/revocable;
- new version requesting more capabilities prompts review;
- profile restrictions remain enforced by host after extension output.

## 5. Identity and signing

Extension identity:

```text
extensionId
packageName (companion)
signing certificate digest/lineage
publisher metadata
contract version range
manifest digest
```

- package name alone insufficient;
- signature change blocks bind until explicit re-trust/valid lineage;
- sideload source does not imply trust;
- declarative manifest requires authenticity mechanism/repository trust policy before remote distribution;
- extension binary/manifest not imported from backup, only descriptor/grants references.

## 6. Version negotiation

Contract version `major.minor`:

- minor adds optional fields/capabilities with defaults;
- major may break semantics;
- host advertises supported range/capabilities;
- extension selects compatible version or fails gracefully;
- host supports current and previous stable major for documented window only after ecosystem exists;
- experimental APIs have namespace, no compatibility promise and cannot be required for stable core flow.

DTOs include schema version and unknown-field strategy. Oversized/unknown required payload rejected.

## 7. Execution model

Companion calls:

- occur off main thread;
- have deadline/cancellation;
- page/stream bounded results;
- have per-extension concurrency/rate quotas;
- use correlation IDs;
- survive/handle binder death;
- never hold Room transaction while waiting on IPC;
- output written to staging and validated before commit;
- long sync uses checkpoint/page protocol rather than one huge Binder transaction.

No implicit retries at both Binder/provider/WorkManager levels; host orchestrator owns high-level retry.

## 8. Network and credentials

Preferred model: host performs HTTP requests using extension-provided typed request templates under source network policy. This centralizes credentials/redaction/redirect/SSRF/limits.

If companion must perform its own network:

- destinations declared and shown;
- OS sandbox applies but host cannot fully inspect traffic;
- no raw credentials other than extension-owned secret where explicitly granted;
- diagnostic/refresh trust reduced;
- feature requires stronger review and may be prohibited for public extension ecosystem.

Credentials:

- stored by host or extension-owned secure storage, never shared globally;
- `use_own_credentials` operation preferred over returning secret;
- no access to another extension/source secret;
- no secret values in Binder DTO/logs unless unavoidable and explicitly designed.

## 9. UI extension

Preference order:

1. host-rendered form from declarative schema;
2. system/browser-like OAuth/device code flow where applicable;
3. explicit native companion Activity launched with minimal request/result contract.

Extension cannot inject arbitrary Compose/View into host process. Native Activity:

- clearly identified as extension;
- cannot receive database/player objects;
- returns bounded signed/typed result;
- errors/cancel restore TV focus;
- accessibility/remote support is extension conformance requirement.

## 10. Output validation

All extension output passes:

- schema/type/size validation;
- source/address/network policy;
- M3U/XMLTV/catalog normalization;
- stable identity/provenance rules;
- profile policy enforcement;
- staging/atomic commit;
- URL/header secret redaction;
- health/diagnostic classification.

Extension cannot mark its data trusted enough to bypass conflict/security rules.

## 11. Failure isolation

Classified failures:

```text
ExtensionNotInstalled
SignatureChanged
ContractIncompatible
CapabilityDenied
Timeout
Cancelled
BinderDied
InvalidPayload
RateLimited
ProviderError
SecurityPolicyRejected
```

- one extension crash cannot terminate MuxTV;
- repeated crash/timeout triggers circuit breaker/disable suggestion;
- old active source revision remains;
- UI shows extension-specific diagnostics and safe actions;
- host may quarantine incompatible/malicious extension;
- kill/revoke does not delete user catalog/overlays automatically.

## 12. Conformance kit

Every extension type must pass host-owned tests:

- contract negotiation/unknown fields;
- cancellation/deadline/binder death;
- large/paginated catalog;
- malformed/hostile payload;
- credentials/redaction canaries;
- undeclared host/network rejection;
- source refresh staging/rollback;
- profile restrictions;
- Activity/TV remote/accessibility if native UI;
- signature/version/update transitions;
- performance quotas.

A sample extension is educational, not certification by itself.

## 13. Distribution/update

Extension update is separate from MuxTV update.

- MuxTV never silently downloads/installs companion APK;
- Android package installer/user consent required;
- extension source/repository/certificate shown;
- capability diff shown after update;
- incompatible update can be blocked while previous APK state remains user-controlled;
- no remote kill code execution; host may refuse binding based on security advisory/version policy.

## 14. Privacy

Extensions receive only minimum DTO/capability. No default access to:

- profile history/favorites/search;
- full source list;
- device identifiers beyond compatibility data needed;
- logs/diagnostics of other extensions;
- local-control sessions;
- update credentials/signing information.

Any future personalization capability requires separate consent/privacy ADR.

## 15. Rollout order

1. Built-in adapter contracts stabilized through M3U/XMLTV and one provider implementation.
2. Declarative manifest prototype for mappings/aliases.
3. Threat/conformance test suite.
4. Companion APK experimental API for one justified use case.
5. Only after ecosystem demand: signed distribution/index policy.

Do not build marketplace before steps 1–4 prove maintenance value.

## 16. Reference lessons

StreamVault demonstrates companion APK viability and broad capability potential. Its approach is useful evidence, not a contract to copy; MuxTV adds explicit process isolation, host-owned network preference, capability grants, staging validation, version negotiation and conformance gates.

Kodi demonstrates ecosystem power and long-term compatibility cost. MuxTV deliberately rejects in-process arbitrary scripting/plugin schemes to protect Android TV stability/security.

## 17. Acceptance criteria

- no arbitrary downloaded code executes in main process;
- extension cannot access Room/player/other credentials directly;
- package signature/capability changes require review;
- timeout/crash/invalid payload cannot corrupt active catalog or app process;
- output uses normal security/staging/provenance rules;
- long catalog transfer is paginated/checkpointed;
- all capabilities are visible/revocable and default deny;
- core M3U/live playback works without extensions.