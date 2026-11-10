# LC04-S2J3A — bounded source-command plaintext + TV-owned staging boundary

Issue #401 (security); umbrella #397 (product). Parent exact HEAD #443
`f62ca065efb9e99d92aadd75772743c3fb60eca9`: 31/31 hosted
workflows SUCCESS. This PR is stacked Draft and must not be merged
out of order. Keep the parent chain #433 → #434 → #436 → #437 →
#438 → #440 → #441 → #442 → #443 → this PR.

## Product gap and critical staging issue

The installed six-frame MXH1/MXR1 Android implementation only
authenticates two **fixed synthetic** records, then consumes its key
owners. No provider URL, login or password can be carried safely. It
is **not permissible** to route raw source commands over MXR1, to
treat an MXH1 proof Boolean as a source grant, or to use the existing
plaintext HTTP `/pair`.

Additionally, `ProtectedSourceStaging.activate` previously returned
`FAILED` after an exception from `SourceOnboarding.activate`,
leaving a prepared handle available for a later retry. Some
implementations may have committed a source before throwing. This can
double-activate or lie about rollback. A cancellation after a
potential commit must also be terminal.

## Implemented: stable bounded source-command plaintext schema

`local-control/protected-channel/.../Lc04SourceCommandCodec.kt`:

- Four typed operations: `PrepareM3u(locator)`,
  `PrepareXtream(endpoint,username,password)`,
  `Activate(displayName)`, `Cancel()`.
- One exactly versioned, canonical format: magic `MXSC`, version=1,
  opcode, zero privilege/reserved bits, positive u64 sequence,
  canonical big-endian u16 size and **strict** UTF-8 fields.
- Maximum request 6,656 bytes; UTF-8 limits: locator/endpoint
  4,096 bytes, username 256, password 2,048, display name 120.
  Reject truncated/extended frames, invalid Unicode, unknown kind,
  odd field count, zero sequence and unauthorized future flags.
- Reply `MXSR` (16 bytes) contains only sequence and explicit
  `SourceStagingResult`-derived status. No `SourcePreparationHandle`,
  provider address, user, password or secrets are in replies.
  Source-command `toString` is fully redacted. No phone-asserted
  `insecureHttpApproved` field exists.
- **THIS IS PLAINTEXT ENCODING, NOT ENCRYPTION.** Neither the codec nor
  any new code opens sockets or exposes HTTP endpoints. It must only
  be invoked on decrypted *and independently authenticated* AEAD
  records from a future reviewed app-data key owner with distinct
  keys, transcript binding and replay detection.
  No custom cryptographic algorithms have been added.

## Implemented: TV-only source-command session

`local-control/onboarding/.../Lc04TvSourceCommandSession.kt`:

- `internal` constructor, no Android or HTTP exposure.
- Requires a TV-owned `ProtectedSourcePrincipal` and existing
  `ProtectedSourceStaging`; rejects revoked pairing authority.
- Strict one-use increasing sequence `1..16`, monotonic 120-second
  budget; duplicate, gap, malformed, expired or revoked requests
  terminate the session. This counter is an *additional application*
  check, **not** a substitute for authenticating the AEAD record
  header/direction/transcript.
- Executes `SourceOnboarding.prepare/activate/cancel` **only**
  through existing TV-owned `ProtectedSourceStaging`. Source APIs,
  credentials and Room are not duplicated.
- `Activate` is denied by default until a separate trusted
  **local TV D-pad gesture** is consumed by the injected approval
  callback. The callback is not wired yet: no phone can currently
  approve itself. Insecure HTTP opt-in is always false; TV must
  independently approve unsafe HTTP using its established policy.
- On unexpected exception/cancellation after attempting activation,
  prepared state is invalidated, no implicit retry is allowed, and
  a returned outcome is `ACTIVATION_OUTCOME_UNKNOWN` (cancellation
  propagates but the session becomes terminal). Never automatically
  cancel a possibly committed source.
- TV session stop/revocation fencing: any success racing with closure
  reports unknown outcome for activation. Cleanup after revocation
  removes prepared handles without exposing them to phone.

## Tests and CI

- 9 strict codec JVM tests: M3U/Xtream round-trip, Unicode,
  field limits, malformed/trailing/oversized/forged flags,
  source-name rules, secret-free replies and fixed reply codes.
- 11 TV dispatcher tests: real delegated prepare/activate/cancel
  calls, TV consent gating, Xtream secrecy, replay/order invalidation,
  malformed terminal close, exception/cancellation after possible
  commit, simultaneous grant revoke, onStop race, monotonic TTL,
  HTTP confirmation owned by TV, owner revocation cleanup.
- Direct staging regression tests for exception/cancellation after
  potentially durable commit.
- LC04 Android private-LAN GitHub-hosted workflow additionally
  executes `:local-control:protected-channel:test` and
  `:local-control:onboarding:test`, plus exact-source static
  `Lc04SourceCommandBoundaryContractTest`. Source path filters
  trigger these checks. No skipped tests, no self-hosted runner.
- This slice is NOT complete unless exact-head 31/31 GitHub Actions
  succeeds, including all new tests.

## Stop gates: what is NOT implemented

1. No reviewed production AEAD application-data key owner after
   synthetic MXH1/MXR1; no actual encrypted source commands over
   Android network, no new source pairing principal minted from the
   verified handshake. No HTTP credentials. Device route checks
   and API37 permission gate must remain active at every frame.
2. No TV D-pad activation-approval component is wired to this
   internal TV session; no public inbound API and no source-form
   transmission in Android Phone yet.
3. No phone M3U/Xtream UI and no user end-to-end import in this
   slice. It creates the source-command/schema and staging bridge
   necessary to build those capabilities with a separate security
   review. Provider secrets remain in JVM Strings only as long as
   necessary; the JVM cannot guarantee zeroization of Strings.
4. API37 grant/deny/revoke evidence and independent source/crypto
   review remain. Physical two-device LAN testing is recommended
   under #401 but not a formal release prerequisite; do not claim
   two-device interoperability until measured.

**Next allowed product slice:** independently reviewed application-data
AEAD key derivation/ownership and directional authenticated records,
separate from synthetic probe keys, with exact QR/Ed25519/TV transcript
binding, per-direction counters, terminal failures and TV consent.
Then wire this dispatcher to the production TV lifecycle, design the
phone M3U/Xtream entry form and complete activation to catalog/Media3
with a precise no-duplicate-commit test.
