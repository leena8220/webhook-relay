package dev.gdguesser.webhookrelay.core

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("relay")
data class RelayProperties(
    @field:NotBlank
    val masterKeyBase64: String,
    @field:Valid
    val delivery: Delivery = Delivery(),
    @field:Valid
    val outbox: Outbox = Outbox(),
    @field:Valid
    val security: Security = Security(),
    @field:Valid
    val api: Api = Api(),
) {
    data class Delivery(
        @field:Min(1)
        @field:Max(100)
        val maxAttempts: Int = 8,
        val baseDelay: Duration = Duration.ofSeconds(1),
        val maxDelay: Duration = Duration.ofMinutes(5),
        val leaseDuration: Duration = Duration.ofMinutes(2),
        val connectTimeout: Duration = Duration.ofSeconds(3),
        val requestTimeout: Duration = Duration.ofSeconds(10),
        val retryScanInterval: Duration = Duration.ofMillis(500),
        @field:Min(1)
        @field:Max(500)
        val retryBatchSize: Int = 100,
    )

    data class Outbox(
        @field:NotBlank
        val topic: String = "webhook-deliveries",
        val publishInterval: Duration = Duration.ofMillis(250),
        val sendTimeout: Duration = Duration.ofSeconds(10),
        @field:Min(1)
        @field:Max(500)
        val batchSize: Int = 100,
    )

    data class Security(
        val allowHttp: Boolean = false,
        val allowPrivateNetworks: Boolean = false,
    )

    data class Api(
        @field:Min(1)
        @field:Max(10_000_000)
        val maxPayloadBytes: Int = 1_048_576,
    )
}
