package app.muxtv.catalog.refresh

import com.google.common.truth.Subject

/**
 * Test-only compatibility shim for the Truth version pinned by this repository.
 *
 * Newer Truth examples commonly use Subject.named(...), while the pinned version does not expose
 * that method. Keep the C18 harness source readable without changing production dependencies.
 */
@Suppress("UNUSED_PARAMETER")
internal fun <T : Subject> T.named(displayName: String): T = this
