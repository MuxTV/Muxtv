package app.muxtv.ui

class FocusBookmark<K : Any> {
    private val values = mutableMapOf<String, K>()
    fun remember(scope: String, key: K) { values[scope] = key }
    fun restore(scope: String): K? = values[scope]
    fun clear(scope: String) { values.remove(scope) }
}
