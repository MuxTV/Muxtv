# Xtream Live playback resolution implementation plan

Date: 2026-08-31
Owner: #234
Implementation PR: #235
Parent: #224

## Goal

Complete the smallest provider-neutral runtime resolution seam that turns a persisted non-secret Xtream Live playback reference into an ephemeral transport locator without persisting or exposing provider credentials.

## Invariants

- Direct M3U HTTP(S) playback remains on the existing `PlaybackAccessPolicyResolver` path.
- `catalog:api` owns only provider-neutral playback-reference contracts.
- Concrete Xtream credential lookup and URL construction remain in `catalog:refresh`.
- Credential-bearing URLs exist only ephemerally in memory and never become Room identity or diagnostics.
- Existing exact-origin HTTP approval remains authoritative.
- Unknown provider namespaces fail closed.
- No Room migration, feature-to-adapter dependency, new AVD, catch-up, VOD, Series, DVR, or provider framework.

## TDD sequence

1. Preserve the already-confirmed RED contract in `XtreamPlaybackReferenceResolverContractTest`.
2. Add the minimal provider-neutral `PlaybackReferenceRequest`, `PlaybackReferenceResolution`, and `PlaybackReferenceResolver` contract in `catalog:api` with redacted diagnostics.
3. Add `XtreamPlaybackReferenceResolver` in `catalog:refresh`:
   - accept only bounded `muxtv-provider://xtream/live/<positive stream id>[/ts|/m3u8]` references;
   - legacy format-less references resolve as `ts`;
   - parse the credential id strictly;
   - read through `XtreamSourceAccessManager`;
   - map not-found/corrupt/unavailable credentials to typed results;
   - evaluate the stored base URL through the existing URL policy;
   - require stored Xtream HTTP approval before constructing/exposing a credential-bearing cleartext URL;
   - build the final URL with encoded path segments, never raw string concatenation;
   - rethrow coroutine cancellation.
4. Add integration coverage at the existing playback-catalog composition boundary before changing that boundary, proving:
   - direct locators retain existing behavior;
   - opaque Xtream references resolve before access policy;
   - approved HTTP resolution reaches the existing policy as preapproved rather than creating a second approval owner.
5. Wire the resolver only in adapter/composition code.
6. Run affected host tests and exact-head Hosted validation. No AVD is required for this host/API slice.
7. Merge only after the exact final head is green.

## Exit gate

`persisted playback reference -> PlaybackReferenceResolver -> ephemeral transport locator -> PlaybackAccessPolicyResolver -> existing player` is executable for Xtream Live, while direct M3U behavior and all security/redaction invariants remain unchanged.