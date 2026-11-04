# LC04-S2B — synthetic-only bidirectional AEAD laboratory

Refs #401 and accepted #425.

## Gap and scope

The existing phone proof returns a one-use authenticated TV receipt. The
receipt key is consumed by the verifier and must not be reused for a data
channel. SourceOnboarding already supplies prepare/activate/cancel with an
opaque preparation handle; no parallel backend is needed.

This PR creates a separate JVM-only module with Tink AES-256-GCM. The
in-process test fixture generates two distinct directional keysets and one
random 32-byte session binding. There is no real key handoff between Android
apps. No production app uses this module.

The only permitted input is a generated 32-byte random synthetic probe with
the fixed domain LC04-SYNTHETIC. There is no arbitrary plaintext interface.

## Envelope

MXE1 frame, 113 bytes, big endian:
magic 4; version 1; direction 1; session binding 32; sequence 8;
encrypted size 2; Tink output 65 (5-byte TINK key prefix, 12-byte random nonce,
32-byte payload, 16-byte authentication tag).
Associated data: MuxTV-LC04-MXE1-SEALED-v1| followed by the 48-byte header.

Both directions use different random AES keys. Tink generates nonces.
Receive requires the next exact counter, matching binding and direction,
canonical length, valid authentication and synthetic-domain plaintext.
Bad frames close the receiver. Clock rollback and 120-second expiry close
the session. Each direction permits at most 64 messages. Close is terminal.

## Acceptance

Run the hostile JVM suite on the exact PR HEAD in GitHub-hosted CI.
Coverage: two-way messages, unique encryption, replay, reflection, out of
order, header and ciphertext tampering, foreign binding, malformed lengths,
expiry, clock rollback, capacity and explicit close.
The build-logic isolation contract forbids Android application dependencies,
source-management calls, socket clients, and credential access.

## Remaining blockers

This is NOT an authenticated production data channel. Before any provider
input, implement and review a genuine HPKE/Ed25519-authenticated key
handoff with independent direction keys and transcript binding. Qualify
cross-process Android traffic and network revocation. Integrate only with
the existing SourceOnboarding port in a separate controlled step.

No source forms, tokens, IPTV provider access or HTTP fallback are added.
Tink key material is JVM-managed; heap zeroization cannot be guaranteed.
