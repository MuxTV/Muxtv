package app.muxtv.common.diagnostics

class BoundedEventBuffer(
    private val capacity: Int,
) {
    private val events = ArrayDeque<DiagnosticEvent>(capacity.coerceAtLeast(1))

    init {
        require(capacity > 0) { "Capacity must be positive" }
    }

    @Synchronized
    fun add(event: DiagnosticEvent) {
        if (events.size == capacity) events.removeFirst()
        events.addLast(event)
    }

    @Synchronized
    fun snapshot(): List<DiagnosticEvent> = events.toList()

    @Synchronized
    fun clear() = events.clear()
}
