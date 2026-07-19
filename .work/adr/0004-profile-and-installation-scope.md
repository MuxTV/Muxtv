---
status: accepted
last_reviewed: 2026-07-19
---

# ADR-0004: One primary profile, user-created additional profiles

## Context

TV/media applications often ship demographic templates such as children, parents or guests. These labels impose assumptions, complicate onboarding and incorrectly combine identity, content restrictions and administrative permissions.

MuxTV needs independent favorites, history, ordering, UI/accessibility preferences and optional restrictions for several viewers, while keeping provider sources, credentials and catalog refresh shared per installation.

## Decision

- first launch atomically creates exactly one profile named `Основной`;
- primary profile is immediately active and no picker is shown while it is the only profile;
- primary profile can be renamed/configured but not deleted;
- all additional profiles are explicitly created and named by the user;
- no built-in profiles `Дети`, `Родители`, `Гости` and no `profileType` field;
- restrictions/PIN/schedules are independent `ProfilePolicy` objects applicable to any profile;
- sources, credentials, provider catalog, canonical channels, EPG base data, health and extensions are installation-scoped;
- favorites/history/order/custom groups/display and playback preferences are profile-scoped;
- technical/admin actions use installation policy/PIN rather than profile name/type;
- startup behavior after multiple profiles is configurable: last, primary or picker.

## Rationale

This model is simpler, neutral and scalable. It avoids duplicating provider sync and allows any household to name profiles according to real people or purposes. Restrictions remain composable instead of encoded in an irreversible profile category.

## Rejected alternatives

### Pre-created family templates

Rejected as unnecessary onboarding, culturally/household-specific and semantically weak.

### Every profile owns sources

Rejected because credentials, refresh work and catalog would duplicate/diverge. Future per-profile source visibility can be an overlay/policy without copying source data.

### Role-based admin/child profile types

Rejected because role, UI complexity and content restrictions are separate concerns. It also creates migration problems when a user's needs change.

### No profiles

Rejected because separate favorites/history/order are a common multi-viewer need and expensive to retrofit after schema v1.

## Consequences

- database schema must distinguish installation-scoped and profile-scoped rows from v1;
- primary profile invariant and deletion rules require migration tests;
- profile switch must restore focus/state without refreshing sources;
- PIN is a household UI barrier, not strong device security;
- backup/restore needs conflict and primary identity rules.

## Acceptance

The detailed lifecycle, data ownership and UX contract are normative in `.work/specifications/profiles.md`.