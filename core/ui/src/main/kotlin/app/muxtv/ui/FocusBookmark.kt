package app.muxtv.ui

class FocusBookmark<K : Any> {
    private val values = mutableMapOf<String, K>()

    fun remember(scope: String, key: K) {
        values[scope] = key
    }

    fun restore(scope: String): K? = values[scope]

    fun restoreValid(
        scope: String,
        isAvailable: (K) -> Boolean,
    ): K? {
        val key = values[scope] ?: return null
        if (isAvailable(key)) return key

        values.remove(scope)
        return null
    }

    fun clear(scope: String) {
        values.remove(scope)
    }
}
