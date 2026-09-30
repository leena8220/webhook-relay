package dev.gdguesser.webhookrelay.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Base64

class SecurityAndRetryTest {
    private val properties =
        RelayProperties(
            masterKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
            delivery =
                RelayProperties.Delivery(
                    baseDelay = Duration.ofSeconds(2),
                    maxDelay = Duration.ofSeconds(30),
                ),
            security =
                RelayProperties.Security(
                    allowHttp = true,
                    allowPrivateNetworks = true,
                ),
        )

    @Test
    fun `signer creates deterministic HMAC SHA-256 signature`() {
        val signature =
            WebhookSigner().sign(
                secret = "correct horse battery staple",
                timestamp = 1_700_000_000,
                payload = """{"event":"created"}""",
            )

        assertThat(signature)
            .isEqualTo("v1=74f01ba4d50c81f40f062be368cdfbccfc519d38e855ff174634febfb93ef9e9")
    }

    @Test
    fun `secret cipher round trips without storing plaintext`() {
        val cipher = SecretCipher(properties)

        val encrypted = cipher.encrypt("a-secret-that-must-not-be-stored")

        assertThat(String(encrypted.ciphertext)).doesNotContain("must-not-be-stored")
        assertThat(cipher.decrypt(encrypted.ciphertext, encrypted.nonce))
            .isEqualTo("a-secret-that-must-not-be-stored")
    }

    @Test
    fun `backoff uses capped full jitter`() {
        val policy = BackoffPolicy(properties)

        assertThat(policy.delay(attempt = 1) { bound -> bound - 1 })
            .isEqualTo(Duration.ofSeconds(2))
        assertThat(policy.delay(attempt = 5) { bound -> bound - 1 })
            .isEqualTo(Duration.ofSeconds(30))
        assertThat(policy.delay(attempt = 3) { _ -> 0 })
            .isZero()
    }

    @Test
    fun `URL policy rejects plain HTTP by default`() {
        val secureProperties =
            properties.copy(
                security = RelayProperties.Security(allowHttp = false, allowPrivateNetworks = true),
            )

        assertThatThrownBy { TargetUrlPolicy(secureProperties).validate("http://example.com/hook") }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessageContaining("Plain HTTP")
    }
}
