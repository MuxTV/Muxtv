# Android API26 → API37 runtime compatibility audit — 2026-08-24

## Purpose

Static runner-free audit of Android platform behavior relevant to MuxTV's current `minSdk=26`, `compileSdk=37`, `targetSdk=37` contract.

This document distinguishes:

- compile-time compatibility;
- runtime API availability;
- target-SDK behavior changes;
- library backports handled by AndroidX/Media3;
- behavior that still requires explicit MuxTV product handling.

It does not claim device validation. Canonical API26/API36 runtime evidence remains required when the runner returns, and API37 behavior needs real API37 evidence before alpha qualification where the behavior is target/platform-specific.

## Current execution constraints

- no GitHub Actions or self-hosted execution while the current freeze remains active;
- no `.github/workflows/**` changes;
- no additional AVD identity;
- do not touch PR #189/#190/U0 marker;
- production changes from findings below require their own TDD/acceptance path.

---

# 1. SDK contract

Current application convention:

```text
compileSdk = 37
minSdk     = 26
targetSdk  = 37
Java       = 17
```

This means API37 symbols can compile while any platform API introduced after API26 still needs one of:

- AndroidX/library backport;
- runtime API guard;
- desugared/library implementation;
- manifest compatibility behavior;
- explicit target-SDK migration behavior.

The most important current target-SDK-specific change for MuxTV is Android 17 local-network protection.

---

# 2. P0 finding — source onboarding does not gate Android 17 local-network permission

## Platform contract

For apps targeting Android 17 / API37 or higher, `ACCESS_LOCAL_NETWORK` is a dangerous runtime permission required for local-network communication.

The restriction is enforced in the networking stack and applies to normal sockets and libraries built on them, including OkHttp.

Android's local-network definition includes common private/link-local/directly connected/multicast/broadcast traffic. A denied request can surface as socket-level operation-not-permitted failures rather than an application-specific permission callback.

## Repository state

The app manifest correctly declares:

```xml
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
```

However normal source onboarding currently follows:

```text
AddSourceRoute
  ↓
SourceEntrySession.prepare(locator)
  ↓
Remote source onboarding/refresher
  ↓
SourceUrlPolicy
  ↓
OkHttp source fetch
```

`SourceUrlPolicy` accepts normal HTTP/HTTPS URLs regardless of whether their host is LAN/private. It correctly handles scheme/embedded-credential/control-separator/fragment policy, but local-network permission is outside that policy.

`AddSourceRoute` currently has no Android 17 permission request state/launcher.

Therefore a user can enter an otherwise valid local M3U endpoint such as a private-address HTTP(S) URL and reach the network fetch without a prior API37 runtime permission gate.

## Consequence

On targetSdk 37 + Android 17, local M3U/provider endpoints can fail despite being syntactically and security-policy valid.

A generic `Network` or `Unexpected` source failure is not adequate UX because the user needs an actionable runtime permission prompt/settings path.

## Required correction

Local-network permission must become a typed pre-network gate for source onboarding on API37+.

It must **not** be implemented by teaching `catalog:refresh` to launch Android permission UI.

Preferred responsibility split:

```text
shared network target classification
        ↓
Android/app permission coordinator
        ↓
Sources presentation requests permission
        ↓ granted
existing durable onboarding
        ↓
existing source fetch/import/publication
```

This work should coordinate with #202 so the new permission seam does not preserve the current Sources→adapter dependency violation.

---

# 3. Existing external-playback LAN permission handling is directionally correct

`ExternalPlaybackActivity` already:

- constructs a `LocalNetworkPermissionGate(Build.VERSION.SDK_INT)`;
- classifies the target before playback;
- checks `ACCESS_LOCAL_NETWORK` only when required;
- uses `ActivityResultContracts.RequestPermission`;
- distinguishes denied from permanently denied;
- continues to HTTP exact-origin approval only after the LAN gate;
- does not request the API37 permission on older APIs.

This is a good product flow and should be reused conceptually rather than building an unrelated second permission model.

However the classifier itself has coverage gaps described below.

---

# 4. P0/P1 finding — local-network classifier is narrower than Android's protected network definition

## Current classifier coverage

`LocalNetworkTargetClassifier` currently identifies:

### IPv4 local

- `10.0.0.0/8`;
- `172.16.0.0/12`;
- `192.168.0.0/16`;
- `169.254.0.0/16`.

### IPv6 local

- link-local `fe80::/10`;
- ULA `fc00::/7`.

### Hostnames

- `.local` as local;
- `localhost` / loopback separately;
- ordinary hostnames as ambiguous without DNS.

This is useful but is not the whole Android local-network definition.

## Missing explicit IPv4 classes

At minimum add tests/decisions for:

- `100.64.0.0/10` CGNAT/shared-address range;
- IPv4 multicast `224.0.0.0/4`;
- `255.255.255.255` broadcast.

Android's published local-network protection guidance classifies these as local-network traffic.

## IPv6 / route-sensitive classes

Android also protects multicast and directly connected routes. A pure literal classifier cannot know every directly connected route from the host string alone.

At minimum test/classify:

- IPv6 multicast `ff00::/8` as local/protected;
- known link-local/ULA forms as already implemented;
- IPv4-mapped IPv6 variants consistently.

Do not invent a hardcoded list of every possible subnet to approximate the kernel route table.

---

# 5. P1 finding — ambiguous hostname handling has no demonstrated permission-recovery path

## Current design

Ordinary names such as:

```text
nas
media.home
router-resolved-host.example
```

are classified `AMBIGUOUS` unless they match `.local`.

The classifier comment says ambiguous hosts remain ambiguous until the platform signals a local-network denial during an actual connection.

## Current external playback behavior

External playback proceeds when the classifier says `AMBIGUOUS`.

The observed playback failure path maps setup/controller failures to generic playback/connection errors. Static source does not establish a path that recognizes an Android local-network permission denial after an ambiguous hostname resolves to a local address and then returns to the LAN permission prompt.

## Safer design options

### Preferred: bounded adapter-level address classification before first protected connect

For ambiguous hostnames on API37+:

1. resolve through a bounded system resolver on an IO dispatcher;
2. classify returned addresses;
3. if any usable target is protected local-network space, request permission before OkHttp/player connects;
4. preserve timeout/cancellation;
5. never log the raw hostname/address as telemetry merely for this gate.

System DNS behavior and `.local`/mDNS need platform-specific care. `.local` is already classified before resolution because mDNS itself can require LAN permission.

### Fallback: typed permission-denial recovery

If the network stack returns a deterministic local-network permission denial that can be distinguished safely from generic network failure, the product may redirect to the permission gate and retry once after approval.

Do not classify every generic `IOException` as permission denial.

## Non-option

Do not prompt `ACCESS_LOCAL_NETWORK` for every ordinary public HTTPS hostname merely because a static classifier cannot prove it remote. That would create unnecessary dangerous-permission prompts.

---

# 6. API26-safe findings already in good shape

## User-unlock startup gate

`MuxTvApplication` uses `UserManager.isUserUnlocked`, which is available before the repository's API26 floor, and uses a context-registered `ACTION_USER_UNLOCKED` receiver.

`ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` provides the AndroidX compatibility boundary for receiver flags. The receiver only reacts to the system unlock action and delegates long-running initialization to the application coroutine scope.

No direct post-API26 platform call requiring a new version branch was identified here.

## MediaSessionService manifest

The Media3 module declares:

```text
FOREGROUND_SERVICE
FOREGROUND_SERVICE_MEDIA_PLAYBACK
foregroundServiceType="mediaPlayback"
MediaSessionService intent action
MediaBrowserService compatibility action
```

This matches current Media3 background-playback guidance. Media3 owns compatibility behavior across Android versions; do not replace it with platform `MediaSession2Service` APIs.

## Activity Result contracts / SAF Doctor export

`MainActivity` uses AndroidX Activity Result `CreateDocument` and content-resolver output rather than broad storage permissions. This is the preferred cross-version storage boundary and avoids legacy external-storage permission branches.

---

# 7. Target37/local-network test matrix

A future correction must have deterministic host tests first.

## Pure classifier fixtures

### LOCAL

```text
10.0.0.1
172.16.0.1
192.168.1.1
169.254.1.1
100.64.0.1
100.127.255.254
224.0.0.1
239.255.255.250
255.255.255.255
fe80::1
fc00::1
fd00::1
ff02::1
nas.local
```

### LOOPBACK

```text
127.0.0.1
127.255.255.254
::1
localhost
```

### REMOTE

```text
8.8.8.8
93.184.216.34
2001:4860:4860::8888
```

### AMBIGUOUS until resolution

```text
nas
media.home
example.com
```

Keep malformed literal coverage.

## Permission gate fixtures

- API26/API36 + local target -> permission not requested under the Android17 contract;
- API37 + remote -> not requested;
- API37 + loopback -> not requested unless official behavior/evidence says otherwise;
- API37 + local + already granted -> proceed;
- API37 + local + denied -> typed denial;
- API37 + local + permanently denied -> Settings guidance;
- API37 + local + granted after prompt -> exactly one continuation;
- cancellation/replacement while permission UI is active cannot activate stale source/playback setup.

## Source onboarding fixtures

- private IPv4 M3U URL reaches LAN permission gate before network fetch;
- `.local` URL reaches permission gate before mDNS/network access;
- public URL never prompts solely because targetSdk is 37;
- permission denial does not create/publish a source revision;
- grant resumes existing durable onboarding path rather than creating a parallel importer;
- raw URL never appears in diagnostic state.

---

# 8. Device/runtime acceptance when execution returns

The repository's two persistent AVD identities remain API26/API36. Do not create an API37 persistent AVD merely for this issue.

Because the bug is specifically Android17 runtime behavior, API37 evidence must come from an explicitly approved temporary/physical/platform validation strategy that does **not** violate the two-persistent-AVD repository contract. Until such evidence is available:

- host tests can prove classifier/gate decisions;
- API26/API36 can prove old-platform non-regression;
- public claim “Android17 LAN works” must remain unverified.

If repository policy later allows transient SDK37 execution without adding a persistent AVD identity, document that separately before use. Do not silently create a third AVD.

---

# 9. Interaction with architecture issues

## #202 Sources dependency inversion

Coordinate the source permission seam with #202 so `feature:sources` does not gain another direct adapter dependency while removing its existing ones.

Recommended stable split:

```text
network target classification -> shared network policy
Android permission state/request -> app/platform adapter
source lifecycle -> catalog API port
UI rationale/deny/settings copy -> feature presentation
```

## #184 provider/catch-up roadmap

Future Xtream/Stalker/Jellyfin/LAN pairing adapters also need the same permission boundary. Do not implement provider-specific permission checks.

## #31 release qualification

Android17 local-network support is a compatibility claim and belongs in release evidence once actual API37 runtime evidence exists.

---

# 10. Priority

| Finding | Severity | Action |
|---|---:|---|
| Add Source lacks API37 LAN runtime permission gate | P0 for LAN source users on targetSdk37 | fix before Android17 compatibility claim/alpha if LAN sources are supported |
| static classifier misses platform-defined protected ranges | P0/P1 | extend tests + classifier before relying on gate |
| ambiguous hostname permission recovery not demonstrated | P1 | add bounded resolution or typed recovery design |
| Media3 foreground service manifest | no defect found | preserve |
| user-unlock receiver compatibility | no defect found | preserve |
| SAF Doctor export | no defect found | preserve |

---

# 11. Non-goals

This audit does not authorize:

- broad LAN discovery;
- QR pairing implementation;
- permanent background mDNS;
- new provider protocol;
- third persistent Android TV AVD;
- replacing OkHttp;
- weakening exact-origin HTTP approval;
- leaking resolved LAN addresses into telemetry;
- prompting dangerous LAN permission for all internet URLs.
