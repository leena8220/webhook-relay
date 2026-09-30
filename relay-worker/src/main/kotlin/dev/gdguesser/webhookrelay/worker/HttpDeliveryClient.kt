package dev.gdguesser.webhookrelay.worker

import dev.gdguesser.webhookrelay.core.DeliveryAttemptResult
import dev.gdguesser.webhookrelay.core.DeliveryClaim
import dev.gdguesser.webhookrelay.core.RelayProperties
import dev.gdguesser.webhookrelay.core.TargetUrlPolicy
import dev.gdguesser.webhookrelay.core.WebhookSigner
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock

@Component
class HttpDeliveryClient(
    private val signer: WebhookSigner,
    private val urlPolicy: TargetUrlPolicy,
    private val properties: RelayProperties,
    private val clock: Clock,
) {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(properties.delivery.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    fun deliver(claim: DeliveryClaim): DeliveryAttemptResult {
        val timestamp = clock.instant().epochSecond
        val target = urlPolicy.validate(claim.targetUrl)
        val request =
            HttpRequest
                .newBuilder(target)
                .timeout(properties.delivery.requestTimeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "webhook-relay/0.1")
                .header("X-Webhook-Id", claim.deliveryId.toString())
                .header("X-Webhook-Event-Id", claim.eventId.toString())
                .header("X-Webhook-Attempt", claim.attempt.toString())
                .header("X-Webhook-Timestamp", timestamp.toString())
                .header("X-Webhook-Signature", signer.sign(claim.signingSecret, timestamp, claim.payload))
                .header("X-Correlation-ID", claim.correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(claim.payload, StandardCharsets.UTF_8))
                .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val responseBody =
                response.body().use {
                    val bytes = it.readNBytes(MAX_RESPONSE_BYTES + 1)
                    val text =
                        String(bytes.copyOf(minOf(bytes.size, MAX_RESPONSE_BYTES)), StandardCharsets.UTF_8)
                    if (bytes.size > MAX_RESPONSE_BYTES) "$text…[truncated]" else text
                }
            val status = response.statusCode()
            DeliveryAttemptResult(
                statusCode = status,
                responseBody = responseBody,
                error = if (status in 200..299) null else "Receiver returned HTTP $status",
                retryable = status in RETRYABLE_STATUS_CODES || status >= 500,
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            DeliveryAttemptResult(
                statusCode = null,
                responseBody = null,
                error = "Delivery interrupted",
                retryable = true,
            )
        } catch (exception: IOException) {
            DeliveryAttemptResult(
                statusCode = null,
                responseBody = null,
                error = "${exception.javaClass.simpleName}: ${exception.message ?: "I/O failure"}",
                retryable = true,
            )
        } catch (exception: IllegalArgumentException) {
            DeliveryAttemptResult(
                statusCode = null,
                responseBody = null,
                error = exception.message ?: "Target URL is no longer permitted",
                retryable = false,
            )
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 8_192
        private val RETRYABLE_STATUS_CODES = setOf(408, 425, 429)
    }
}
