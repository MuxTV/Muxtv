package app.muxtv.credentials

import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialPrimitivesTest {
    @Test
    fun `generated credential id is canonical lower-case UUID`() {
        val id = CredentialId.random()

        assertThat(id.value).isEqualTo(UUID.fromString(id.value).toString())
        assertThat(id.value).isEqualTo(id.value.lowercase())
    }

    @Test
    fun `credential id parser accepts canonical UUID only`() {
        val canonical = "123e4567-e89b-12d3-a456-426614174000"

        assertThat(CredentialId.parse(canonical).value).isEqualTo(canonical)
        assertThrows(IllegalArgumentException::class.java) {
            CredentialId.parse(canonical.uppercase())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialId.parse(" $canonical ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialId.parse("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialId.parse("provider-password")
        }
    }

    @Test
    fun `secret bytes own copies and redact string representation`() {
        val source = byteArrayOf(1, 2, 3)
        val secret = SecretBytes.copyOf(source)
        source.fill(9)

        val firstCopy = secret.copyBytes()
        val secondCopy = secret.copyBytes()

        assertThat(firstCopy).asList().containsExactly(1, 2, 3).inOrder()
        assertThat(secondCopy).asList().containsExactly(1, 2, 3).inOrder()
        assertThat(firstCopy).isNotSameInstanceAs(secondCopy)
        assertThat(secret.toString()).isEqualTo("<redacted>")

        firstCopy.fill(8)
        assertThat(secret.copyBytes().asList()).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `closing secret makes further access fail and is idempotent`() {
        val secret = SecretBytes.copyOf(byteArrayOf(4, 5, 6))

        secret.close()
        secret.close()

        assertThrows(IllegalStateException::class.java) {
            secret.copyBytes()
        }
        assertThat(secret.toString()).isEqualTo("<redacted>")
    }

    @Test
    fun `useBytes provides a temporary defensive copy`() {
        val secret = SecretBytes.copyOf(byteArrayOf(7, 8, 9))
        lateinit var leakedReference: ByteArray

        val sum = secret.useBytes { bytes ->
            leakedReference = bytes
            bytes.sum()
        }

        assertThat(sum).isEqualTo(24)
        assertThat(leakedReference.asList()).containsExactly(0, 0, 0)
        assertThat(secret.copyBytes().asList()).containsExactly(7, 8, 9).inOrder()
    }
}
