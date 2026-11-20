# LC04-S2J3E4: real product Cancel and durable SourceOnboarding bridge

## Scope and dependency
Extend the current stacked Draft #451 on #450, keeping every older
#433..#450 prerequisite unmerged until independent security review.
No PC, self-hosted runner, third Android AVD, plaintext phone credential
route or new protocol.

## Product requirement: protected M3U cancellation
1. After authenticated original F6/EOF + APPDATA PrepareM3u -> PREPARED,
   phone shows **Activate** or **Cancel**. Neither action is automatic.
2. Phone's one-use Cancel can run from SOURCE_PREPARED or
   SOURCE_WAIT_TV (after the initial TV_CONFIRMATION_REQUIRED reply),
   but never during an in-flight or completed irreversible Activate.
3. Selected Android Network, original numeric TV IP and original
   AEAD direction/counters remain required; TV routes Cancel through
   the existing ProtectedSourceStaging -> SourceOnboarding.cancel(handle)
   instead of HTTP /pair.
4. Only authenticated CANCELED confirms deletion of a temporary
   credential and its preparation metadata; CLEANUP_PENDING and missing
   or unexpected responses are terminal, not hidden retries.
5. TV consent does not automatically grant source creation. Cancel
   invalidates pending name-bound D-pad approval; canceled source must
   not be activated later by a stale phone button.
6. onStop / route loss close all sockets, F6/AEAD keys and clear source
   fields. Do not claim cancellation when a raced Activity was revoked.

## Evidence
- Installed phone UI test: no QR -> cancel hidden; no provider data sent.
- Android selected Network API26/API36: authentic F1..F6+EOF,
  second selected-bound APPDATA socket, PrepareM3u, Cancel, no
  activation or TV consent; **8/8 non-skipped** canonical phone tests.
- JVM bridge qualification (separate): real AppSourceOnboarding,
  DurableRemoteSourceOnboarding + DefaultRemoteSourceOnboarding under
  TV-owned encrypted F6 command owner, sensitive credential in fake
  CredentialStore and opaque durable registry; preserve redaction and
  fail-closed TV D-pad activation/Cancel.
- Existing on-device Room catalog, UI channel browse and Media3
  tests remain independent. No new claim of full physical phone↔TV
  or streaming Media3 end-to-end until integrated evidence exists.

## Acceptance
Require the exact #451 HEAD 31/31 successful GitHub-hosted workflows,
including API26/API36 8/8 and TV UI + JVM crypto/catalog guards.
Do not merge the stacked PR before independent security review.
