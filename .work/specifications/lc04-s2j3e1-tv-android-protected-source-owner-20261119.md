# LC04-S2J3E1 — explicit Android TV APPDATA source owner

Refs #401, #397. Stacked Draft on PR #450 exact commit `f271ed3a6cd74884c34fab5d976a3d39aaa2bf4c`.
No merge to main before independent security review and exact-head hosted CI.

## User-visible scope
The installed Android TV's original QR and verified Ed25519 phone remain
the only source of trust. Existing manually enabled MXH1/MXR1 six-frame mode
stays diagnostic-only. A **separate** D-pad action chooses source-key-retaining
F1–F6 mode; that mode cannot be silently upgraded from the diagnostic mode.
No HTTP /pair, no persisted bearer or provider data in unencrypted messages.

After authentic F6 write and TCP output half-close, TV offers a second
**explicit D-pad** action to start one bounded APPDATA TCP listener. That
listener consumes the one-use original QR/F6 key port through the already
reviewable `Lc04SourceCommandTcpTv` constructor. The TV-only callback polls
the proposed source name (without URL/tokens) and approves the next
authenticated Activate command under its existing 15s window. All commands
dispatch via existing `ProtectedSourceStaging(SourceOnboarding)`, not Room.

The TV-selected route callback is locked to original QR epoch, Activity
lifecycle, selected Network, exact RFC1918 TV address, Wi-Fi/Ethernet,
absence of VPN/cellular, and API37 local-network permission. Revocation
on onStop/network loss closes APPDATA before retiring the original MXH1
and QR owner; unfinished staging cleanup uses the existing TV worker.

## Honest limitation
This is **TV composition and UI only**. Phone app still runs the existing
terminal synthetic proof; phone M3U/Xtream form and trustworthy APPDATA
port handoff are NOT wired in this slice. TV displays the APPDATA address
for diagnostics; address/port is never authentication and must not be
treated as source permission.

## Gates
- `:app:tv:compileDebugKotlin` and Android TV API26/API36 instrumentation
- `LocalControlNativeTvOptInTest`: no listener from QR/verified phone alone,
  protected source action cannot auto-activate or silently start APPDATA
- Existing LC04 hostile F6/EOF and source-command TCP tests remain required
- Exact-head GitHub-hosted CI (no local PC; no new AVD)
