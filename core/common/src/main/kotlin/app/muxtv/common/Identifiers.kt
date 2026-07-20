package app.muxtv.common

@JvmInline value class ProfileId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class SourceId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class ProviderChannelId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class CanonicalChannelId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class StreamVariantId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class TrackId(val value: String) { init { require(value.isNotBlank()) } }
