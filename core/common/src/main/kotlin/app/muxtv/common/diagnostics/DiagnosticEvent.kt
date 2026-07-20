package app.muxtv.common.diagnostics

data class DiagnosticEvent(
    val correlationId: String,
    val category: String,
    val message: String,
    val timestampEpochMillis: Long = System.currentTimeMillis(),
)
