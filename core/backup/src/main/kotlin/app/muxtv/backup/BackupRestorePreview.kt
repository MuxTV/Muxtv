package app.muxtv.backup

data class ExistingBackupState(
    val profileIds: Set<String> = emptySet(),
    val sourceIds: Set<String> = emptySet(),
) {
    init {
        require(profileIds.none { it.isBlank() || it != it.trim() })
        require(sourceIds.none { it.isBlank() || it != it.trim() })
    }

    override fun toString(): String =
        "ExistingBackupState(profileCount=${profileIds.size}, sourceCount=${sourceIds.size})"
}

enum class BackupConflictKind {
    PROFILE_ID,
    SOURCE_ID,
}

data class BackupRestoreConflict(
    val kind: BackupConflictKind,
    val portableId: String,
) {
    init {
        require(portableId.isNotBlank())
    }

    override fun toString(): String =
        "BackupRestoreConflict(kind=$kind, portableId=<redacted>)"
}

data class BackupRestorePreview(
    val profileCount: Int,
    val sourceCount: Int,
    val overlayCount: Int,
    val recentCount: Int,
    val sourcesRequiringReauth: Int,
    val conflicts: List<BackupRestoreConflict>,
) {
    init {
        require(profileCount >= 0)
        require(sourceCount >= 0)
        require(overlayCount >= 0)
        require(recentCount >= 0)
        require(sourcesRequiringReauth in 0..sourceCount)
    }

    val requiresExplicitConflictDecision: Boolean
        get() = conflicts.isNotEmpty()

    override fun toString(): String =
        "BackupRestorePreview(profileCount=$profileCount, sourceCount=$sourceCount, " +
            "overlayCount=$overlayCount, recentCount=$recentCount, " +
            "sourcesRequiringReauth=$sourcesRequiringReauth, conflictCount=${conflicts.size}, " +
            "requiresExplicitConflictDecision=$requiresExplicitConflictDecision)"
}

object BackupRestorePreviewer {
    fun preview(
        document: PortableBackupDocument,
        existingState: ExistingBackupState,
    ): BackupRestorePreview {
        val payload = document.snapshot.payload
        val conflicts = buildList {
            payload.profiles.forEach { profile ->
                if (profile.id in existingState.profileIds) {
                    add(
                        BackupRestoreConflict(
                            kind = BackupConflictKind.PROFILE_ID,
                            portableId = profile.id,
                        ),
                    )
                }
            }
            payload.sources.forEach { source ->
                if (source.id in existingState.sourceIds) {
                    add(
                        BackupRestoreConflict(
                            kind = BackupConflictKind.SOURCE_ID,
                            portableId = source.id,
                        ),
                    )
                }
            }
        }

        return BackupRestorePreview(
            profileCount = payload.profiles.size,
            sourceCount = payload.sources.size,
            overlayCount = payload.channelOverlays.size,
            recentCount = payload.recentChannels.size,
            sourcesRequiringReauth = payload.sources.count {
                it.recoveryState == PortableSourceRecoveryState.REAUTH_REQUIRED
            },
            conflicts = conflicts,
        )
    }
}
