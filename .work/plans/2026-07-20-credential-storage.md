---
status: accepted
last_reviewed: 2026-07-20
branch: feat/credential-storage
base_commit: 63432e75adc197feda7861e17d8ccc92825abb98
---

# Credential storage design and implementation plan

## Goal

Store provider credentials locally without placing plaintext secrets in Room, source URLs, logs, backups or exported configuration. The store must remain usable by unattended source refresh and TV playback, while key loss or tampering must produce an explicit re-authentication state rather than looking like a missing credential.

## Evaluated approaches

### AndroidX Security Crypto

Rejected. `EncryptedSharedPreferences`, `EncryptedFile` and `MasterKey` are deprecated in favor of direct platform APIs and Android Keystore.

### Tink envelope encryption

Deferred. It is appropriate for interoperable vault protocols and key rotation hierarchies, but adds a second cryptographic format and dependency without current MuxTV requirements.

### Direct Android Keystore + AES-256-GCM + DataStore

Selected.

- API 26 minimum means symmetric Android Keystore keys are available on every supported device.
- The key is non-exportable and restricted to AES/GCM/NoPadding encryption and decryption.
- StrongBox is not required because many Android TV and Fire TV devices do not provide it.
- User authentication is not required because background source refresh and playback must operate without a biometric or PIN prompt.
- Each encryption operation receives a fresh provider-generated 96-bit IV.
- Credential ID and envelope version are authenticated as AAD.
- Encrypted records are stored by one DataStore instance under `noBackupFilesDir`.
- DataStore corruption is rethrown and mapped to an unavailable state; it is not silently replaced with an empty file.

## Public boundary

```kotlin
@JvmInline
value class CredentialId

class SecretBytes : AutoCloseable

interface CredentialStore {
    suspend fun put(id: CredentialId, secret: SecretBytes): CredentialWriteResult
    suspend fun read(id: CredentialId): CredentialReadResult
    suspend fun remove(id: CredentialId): CredentialRemoveResult
    suspend fun reset(): CredentialResetResult
}
```

`SecretBytes` owns a private byte array, returns only short-lived copies, zeroes memory on `close`, and always renders as `<redacted>`. It is intentionally not a data class and does not implement value-based equality or hashing.

## Result model

Reads distinguish:

- `Found` — decrypted secret is returned;
- `NotFound` — no encrypted record exists;
- `Unavailable(KeyMissingOrInvalidated)` — persisted ciphertext cannot be used because its Keystore key is unavailable;
- `Unavailable(AuthenticationFailed)` — GCM authentication failed, indicating tampering, wrong AAD or an incompatible key;
- `Unavailable(StoreCorrupted)` — DataStore cannot deserialize its file;
- `Unavailable(IoFailure)` — storage or provider operation failed.

No failure includes plaintext, ciphertext, raw key material or credential-bearing URLs.

## Components

### `CredentialEnvelopeCodec`

Pure Kotlin binary format:

```text
magic: 4 bytes  "MXCR"
version: 1 byte
ivLength: 1 byte
iv: 12 bytes for v1
ciphertextLength: 4-byte unsigned-compatible positive int
ciphertextAndTag: remaining bytes
```

The decoder rejects wrong magic, unsupported version, invalid IV length, negative/oversized lengths, truncation and trailing bytes.

### `CredentialAead`

Small interface used by the store. Production implementation uses Android Keystore. JVM tests use a deterministic in-memory AES key and deterministic IV source to verify format and AAD behavior.

### `AndroidKeystoreCredentialAead`

- provider: `AndroidKeyStore`;
- alias: `app.muxtv.credentials.v1`;
- algorithm: AES, key size 256;
- purposes: encrypt/decrypt;
- block mode: GCM;
- padding: none;
- randomized encryption required;
- no user-auth requirement;
- no StrongBox requirement;
- synchronized get-or-create path to avoid duplicate generation races.

### `DataStoreCredentialStore`

- uses one injected `DataStore<Preferences>`;
- preferences key is derived only from validated `CredentialId`;
- value is base64 without wrapping of the versioned encrypted envelope;
- file path is `noBackupFilesDir/muxtv-credentials/credentials.preferences_pb`;
- writes encrypt before entering the DataStore transaction;
- reads copy the encoded value, decrypt outside mutation, and map failures explicitly;
- reset deletes all encrypted records and the Keystore alias only after an explicit caller action.

## Credential ID

Canonical form is lower-case UUID text generated with `UUID.randomUUID()`. Parsing rejects blank, non-canonical and malformed values. IDs are opaque references safe to persist in Room; they do not reveal provider username, host or secret type.

## Backup and restore

The encrypted file is created under `noBackupFilesDir`, which Android excludes from automatic cloud backup. This avoids restoring ciphertext onto a device where the original Keystore key cannot exist. The application backup/export format contains credential IDs only and requires re-entry after restore.

## Reference decisions

- Android Keystore and Android cryptography documentation are the normative source.
- AndroidX Security Crypto is an explicit anti-reference because all high-level crypto APIs are deprecated.
- Android DataStore is used only for atomic single-process persistence; cryptography remains owned by MuxTV.
- Bitwarden legacy secure storage is used as a migration and failure corpus, not copied: MuxTV rejects legacy RSA fallbacks, MD5 key names, disabled randomized encryption and silent deletion on authentication failure.
- Aegis is used as evidence for AES-256-GCM encrypted local vaults and explicit unlock/recovery states; MuxTV does not require biometric unlock.
- Proton Keystore failure guidance is used as a product requirement for recoverable re-authentication rather than crashes or silent data loss.

## TDD sequence

1. Add `core:credentials` module and pure tests for `CredentialId` and `SecretBytes`.
2. Add RED tests for envelope encoding/decoding and malformed corpus.
3. Implement the bounded binary codec.
4. Add RED AES-GCM tests for unique IV, AAD binding and tamper rejection.
5. Implement provider-neutral AES-GCM engine and Android Keystore key provider.
6. Add RED store contract tests with in-memory DataStore/fakes.
7. Implement DataStore persistence in `noBackupFilesDir` and explicit failure mapping.
8. Add Android instrumentation tests for real Keystore round-trip, replacement, removal, reset and key-loss recovery.
9. Add module tasks to Fast/Full/Device validation.
10. Run Full and Device gates, record evidence, self-review and merge.

## Explicit non-goals

- provider-specific Basic/Bearer/header serialization;
- applying credentials to OkHttp requests;
- biometric lock screen;
- cloud sync or encrypted export;
- shared credentials across apps;
- credential metadata in Room;
- automatic key rotation before a real migration requirement.
