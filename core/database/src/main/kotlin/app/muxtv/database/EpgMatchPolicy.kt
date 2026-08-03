package app.muxtv.database

/**
 * Stable provenance for derived EPG matching rows.
 *
 * Increment [CURRENT_EPG_MATCH_POLICY_VERSION] whenever normalization or deterministic matching
 * semantics change in a way that requires rebuilding persisted derived matches. Individual
 * [EpgMatchReasonCode] values are evidence/reason provenance and do not replace this policy version.
 */
internal const val CURRENT_EPG_MATCH_POLICY_VERSION: Int = 1

/** Rows migrated from the pre-versioned Room v7 schema are deliberately stale. */
internal const val LEGACY_UNVERSIONED_MATCH_POLICY_VERSION: Int = 0
