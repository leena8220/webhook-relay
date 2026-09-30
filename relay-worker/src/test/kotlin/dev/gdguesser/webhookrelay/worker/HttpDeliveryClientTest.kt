package dev.gdguesser.webhookrelay.worker

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import dev.gdguesser.webhookrelay.core.DeliveryClaim
import dev.gdguesser.webhookrelay.core.RelayProperties
import dev.gdguesser.webhookrelay.core.TargetUrlPolicy
import dev.gdguesser.webhookrelay.core.WebhookSigner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class HttpDeliveryClientTest {
    private lateinit var wireMock: WireMockServer
    private val clock = Clock.fixed(Instant.parse("2026-08-06T10:00:00Z"), ZoneOffset.UTC)
    private val signer = WebhookSigner()
    private val properties =
        RelayProperties(
            masterKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(32)),
            security =
                RelayProperties.Security(
                    allowHttp = true,
                    allowPrivateNetworks = true,
                ),
        )

    @BeforeEach
    fun startServer() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()
    }

    @AfterEach
    fun stopServer() {
        wireMock.stop()
    }

    @Test
    fun `delivery signs exact payload and forwards correlation headers`() {
        wireMock.stubFor(post("/receiver").willReturn(aResponse().withStatus(204)))
        val claim = claim()

        val result = client().deliver(claim)

        val timestamp = clock.instant().epochSecond
        assertThat(result.successful).isTrue()
        wireMock.verify(
            postRequestedFor(urlEqualTo("/receiver"))
                .withRequestBody(equalTo(claim.payload))
                .withHeader("X-Webhook-Signature", equalTo(signer.sign(claim.signingSecret, timestamp, claim.payload)))
                .withHeader("X-Webhook-Timestamp", equalTo(timestamp.toString()))
                .withHeader("X-Correlation-ID", equalTo(claim.correlationId))
                .withHeader("X-Webhook-Attempt", equalTo("2")),
        )
    }

    @Test
    fun `transient server failure is retryable`() {
        wireMock.stubFor(
            post("/receiver")
                .willReturn(aResponse().withStatus(503).withBody("temporarily unavailable")),
        )

        val result = client().deliver(claim())

        assertThat(result.successful).isFalse()
        assertThat(result.retryable).isTrue()
        assertThat(result.statusCode).isEqualTo(503)
        assertThat(result.responseBody).isEqualTo("temporarily unavailable")
    }

    private fun client(): HttpDeliveryClient =
        HttpDeliveryClient(
            signer = signer,
            urlPolicy = TargetUrlPolicy(properties),
            properties = properties,
            clock = clock,
        )

    private fun claim(): DeliveryClaim =
        DeliveryClaim(
            deliveryId = UUID.randomUUID(),
            eventId = UUID.randomUUID(),
            endpointId = UUID.randomUUID(),
            targetUrl = "http://localhost:${wireMock.port()}/receiver",
            payload = """{"order":"created","id":42}""",
            correlationId = "integration-correlation",
            signingSecret = "test-secret-".repeat(4),
            attempt = 2,
            processingToken = UUID.randomUUID(),
        )
}
