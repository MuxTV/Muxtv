# LC04-S2J2C2B — original-TV-QR-owned six-frame private-LAN TV listener

Refs #401. Stacked research follow-up of #440 at `1cd78be73566f52a173fe57118df360f1101c52e`. This is **not** source enrollment or a new public app endpoint.

## Gap and implementation

The existing Android TV MXH1 opt-in listener completes four frames then discards traffic keys. The selected-Network-leased phone in #440 sends an authenticated fifth 104-byte MXR1 probe and requires an authenticated sixth MXR1 TV reply, so it **cannot** be pointed at the existing four-frame listener. The old listener and actual Android TV app are intentionally unchanged.

A new **internal** `Lc04MxH1SealedPrivateLanTv` in `:local-control:mxh1-lan` provides exactly one eligible TV-side listener:
- Original in-memory TV QR and verified, D-pad-approved Ed25519 phone identity must already be active; a second receiver or asserted fingerprint is never accepted.
- Production factory binds only numeric RFC1918 IPv4, never wildcard, loopback, public, hostname or IPv6. It checks peer RFC1918 address; this does not replace Android selected-route validation.
- Versioned `SIGNED_SEED_OFFER → TV_KEY_CONFIRM → PHONE_KEY_CONFIRM → TV_FINISHED` using the original QR/HPKE key-owner, not a fake laboratory keypair.
- TV **must not send** an MXR1 reply before authenticating one 104-byte F5 AES-GCM probe and **actual phone TCP EOF**. It then sends one 104-byte F6 AEAD and output half-closes.
- Fixed frame bounds, 2.5s I/O deadline, a single peer/attempt, explicit cancellation, and original-QR/key revocation for malformed or incomplete inputs. The one Boolean is diagnostic, not a bearer, principal or credential capability.
- No generic plaintext, provider identifiers, credentials, URL, Ktor/HTTP route, Room, app permission, app UI or persistent grant.

Host-only loopback test factory separately exercises the real phone's selected-Network lease and real Tink keys through a test-only redirected socket. The test-only redirect and injected route eligibility **cannot qualify actual Android Network.bindSocket or physical two-device interop**.

## Evidence requirements

- Exact HEAD hosted `:local-control:mxh1-lan:test` including original-QR six-frame positive, denial without consent, rejected public/bad bind, malformed first frame, onStop during partial frame and expired QR.
- Preserve original 4-frame MXH1 and six-frame phone hostile regression suites, Android TV/phone compile and canonical API26/API36 matrices with zero skipped tests.
- Independent cryptographic/security review and parent-order discipline before merge. An accepted host-only test never enables provider credential transfer.

## Next functional gates

1. Android TV lifecycle/selected-Network composition; phone client actually bound to the chosen Android Wi-Fi/Ethernet Network, with API37 LAN permission grant/denial/revocation evidence.
2. Same-network Android phone ↔ TV encrypted six-frame end-to-end using original QR and explicit user consent (not fake host callbacks).
3. Separate reviewed bounded application records and session-scoped source authorization. Only then connect `ProtectedSourceStaging` to existing `SourceOnboarding.prepare/activate/cancel`.
4. Actual phone M3U/Xtream form, TV preview and safe activation, focus and error handling, final device/security/rollback gates. Only this constitutes user-visible LC04 completion.

No merge of this slice or any parent ahead of its required CI/security gates.
