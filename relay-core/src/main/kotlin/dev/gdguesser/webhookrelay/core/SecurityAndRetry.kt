package dev.gdguesser.webhookrelay.core

import org.springframework.stereotype.Component
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ThreadLocalRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

data class EncryptedSecret(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
)

@Component
class SecretCipher(
    properties: RelayProperties,
) {
    private val key =
        Base64
            .getDecoder()
            .decode(properties.masterKeyBase64)
            .also {
                require(it.size == KEY_BYTES) {
                    "relay.master-key-base64 must decode to exactly $KEY_BYTES bytes"
                }
            }
    private val random = SecureRandom()

    fun encrypt(secret: String): EncryptedSecret {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return EncryptedSecret(
            ciphertext = cipher.doFinal(secret.toByteArray(StandardCharsets.UTF_8)),
            nonce = nonce,
        )
    }

    fun decrypt(
        ciphertext: ByteArray,
        nonce: ByteArray,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    companion object {
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

@Component
class WebhookSigner {
    fun sign(
        secret: String,
        timestamp: Long,
        payload: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$timestamp.$payload".toByteArray(StandardCharsets.UTF_8))
        return "v1=${HexFormat.of().formatHex(digest)}"
    }
}

@Component
class BackoffPolicy(
    private val properties: RelayProperties,
) {
    fun delay(
        attempt: Int,
        randomLong: (Long) -> Long = { bound -> ThreadLocalRandom.current().nextLong(bound) },
    ): Duration {
        require(attempt >= 1) { "attempt must be at least 1" }
        val exponent = min(attempt - 1, MAX_EXPONENT)
        val uncapped = properties.delivery.baseDelay.toMillis() * (1L shl exponent)
        val capMillis = min(uncapped, properties.delivery.maxDelay.toMillis()).coerceAtLeast(1)
        return Duration.ofMillis(randomLong(capMillis + 1))
    }

    companion object {
        private const val MAX_EXPONENT = 30
    }
}

@Component
class TargetUrlPolicy(
    private val properties: RelayProperties,
) {
    fun validate(rawUrl: String): URI {
        val uri =
            runCatching { URI(rawUrl) }
                .getOrElse { throw InvalidRequestException("Endpoint URL is not a valid URI") }
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https")) {
            throw InvalidRequestException("Endpoint URL must use http or https")
        }
        if (scheme == "http" && !properties.security.allowHttp) {
            throw InvalidRequestException("Plain HTTP endpoint URLs are disabled")
        }
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) {
            throw InvalidRequestException("Endpoint URL must have a host and no userinfo or fragment")
        }
        if (!properties.security.allowPrivateNetworks) {
            val addresses =
                runCatching { InetAddress.getAllByName(uri.host) }
                    .getOrElse { throw InvalidRequestException("Endpoint host could not be resolved") }
            if (addresses.isEmpty() || addresses.any(::isRestricted)) {
                throw InvalidRequestException("Endpoint URL resolves to a private or reserved address")
            }
        }
        return uri
    }

    private fun isRestricted(address: InetAddress): Boolean =
        when (address) {
            is Inet4Address -> isRestrictedIpv4(address.address)
            is Inet6Address -> isRestrictedIpv6(address.address)
            else -> true
        }

    @Suppress("MagicNumber")
    private fun isRestrictedIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            first >= 224 ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 192 && second == 0 && third in setOf(0, 2)) ||
            (first == 198 && second in setOf(18, 19, 51)) ||
            (first == 203 && second == 0 && third == 113)
    }

    private fun isRestrictedIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return bytes.all { it == 0.toByte() } ||
            (bytes.dropLast(1).all { it == 0.toByte() } && bytes.last() == 1.toByte()) ||
            first and 0xfe == 0xfc ||
            (first == 0xfe && second and 0xc0 == 0x80) ||
            first == 0xff ||
            isIpv4Mapped(bytes)
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean =
        bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() &&
            bytes[11] == 0xff.toByte() &&
            isRestrictedIpv4(bytes.copyOfRange(12, 16))
}
