package app.muxtv.database

/**
 * Describes what the durable refresh boundary actually committed after re-checking ownership.
 *
 * Workers must base retry/failure behavior on this disposition rather than only on an earlier
 * network/import decision because the resource binding or DB lease may change before completion.
 */
enum class RefreshCompletionDisposition {
    APPLIED,
    SUPERSEDED,
    IGNORED,
}
