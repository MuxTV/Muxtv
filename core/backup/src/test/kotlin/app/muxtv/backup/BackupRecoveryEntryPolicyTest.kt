package app.muxtv.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackupRecoveryEntryPolicyTest {
    @Test
    fun `continue without restore is always available`() {
        assertThat(BackupRecoveryEntryPolicy.actions(emptyList()))
            .containsExactly(BackupRecoveryEntryAction.CONTINUE_WITHOUT_RESTORE)
            .inOrder()
    }

    @Test
    fun `app specific storage never qualifies as durable tv recovery`() {
        val actions = BackupRecoveryEntryPolicy.actions(
            listOf(
                BackupRecoveryTransportCapability(
                    kind = BackupRecoveryTransportKind.APP_SPECIFIC,
                    readableNow = true,
                    tvOperableWithoutSystemPicker = true,
                    durableOutsideAppSandbox = false,
                ),
            ),
        )

        assertThat(actions).doesNotContain(BackupRecoveryEntryAction.RESTORE_FROM_TV_NATIVE)
        assertThat(actions).contains(BackupRecoveryEntryAction.CONTINUE_WITHOUT_RESTORE)
    }

    @Test
    fun `system picker appears only when readable now`() {
        val unavailable = BackupRecoveryTransportCapability(
            kind = BackupRecoveryTransportKind.SYSTEM_DOCUMENT_PICKER,
            readableNow = false,
            tvOperableWithoutSystemPicker = false,
            durableOutsideAppSandbox = true,
        )
        val available = unavailable.copy(readableNow = true)

        assertThat(BackupRecoveryEntryPolicy.actions(listOf(unavailable)))
            .doesNotContain(BackupRecoveryEntryAction.RESTORE_FROM_SYSTEM_PICKER)
        assertThat(BackupRecoveryEntryPolicy.actions(listOf(available)))
            .contains(BackupRecoveryEntryAction.RESTORE_FROM_SYSTEM_PICKER)
    }

    @Test
    fun `tv native action requires readable picker independent durable capability`() {
        val valid = BackupRecoveryTransportCapability(
            kind = BackupRecoveryTransportKind.TV_NATIVE_DURABLE,
            readableNow = true,
            tvOperableWithoutSystemPicker = true,
            durableOutsideAppSandbox = true,
        )

        assertThat(BackupRecoveryEntryPolicy.actions(listOf(valid)))
            .containsExactly(
                BackupRecoveryEntryAction.RESTORE_FROM_TV_NATIVE,
                BackupRecoveryEntryAction.CONTINUE_WITHOUT_RESTORE,
            )
            .inOrder()
    }

    @Test
    fun `tv native kind does not bypass missing capability properties`() {
        val candidates = listOf(
            BackupRecoveryTransportCapability(
                kind = BackupRecoveryTransportKind.TV_NATIVE_DURABLE,
                readableNow = false,
                tvOperableWithoutSystemPicker = true,
                durableOutsideAppSandbox = true,
            ),
            BackupRecoveryTransportCapability(
                kind = BackupRecoveryTransportKind.TV_NATIVE_DURABLE,
                readableNow = true,
                tvOperableWithoutSystemPicker = false,
                durableOutsideAppSandbox = true,
            ),
            BackupRecoveryTransportCapability(
                kind = BackupRecoveryTransportKind.TV_NATIVE_DURABLE,
                readableNow = true,
                tvOperableWithoutSystemPicker = true,
                durableOutsideAppSandbox = false,
            ),
        )

        assertThat(BackupRecoveryEntryPolicy.actions(candidates))
            .doesNotContain(BackupRecoveryEntryAction.RESTORE_FROM_TV_NATIVE)
    }

    @Test
    fun `capability diagnostics expose only safe metadata`() {
        val capability = BackupRecoveryTransportCapability(
            kind = BackupRecoveryTransportKind.TV_NATIVE_DURABLE,
            readableNow = true,
            tvOperableWithoutSystemPicker = true,
            durableOutsideAppSandbox = true,
        )

        assertThat(capability.toString()).isEqualTo(
            "BackupRecoveryTransportCapability(kind=TV_NATIVE_DURABLE, readableNow=true, " +
                "tvOperableWithoutSystemPicker=true, durableOutsideAppSandbox=true)",
        )
    }
}
