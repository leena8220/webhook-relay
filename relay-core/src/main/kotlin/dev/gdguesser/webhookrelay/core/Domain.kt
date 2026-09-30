package dev.gdguesser.webhookrelay.core

import java.time.Instant
import java.util.UUID

enum class DeliveryStatus {
    PENDING,
    PROCESSING,
    RETRYING,
    DELIVERED,
    DEAD_LETTERED,
}

data class EndpointView(
    val id: UUID,
    val url: String,
    val description: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class RegisteredEndpoint(
    val endpoint: EndpointView,
    val signingSecret: String,
)

data class AcceptedEvent(
    val eventId: UUID,
    val deliveryId: UUID,
    val correlationId: String,
    val duplicate: Boolean,
    val status: DeliveryStatus,
)

data class DeliveryView(
    val id: UUID,
    val eventId: UUID,
    val endpointId: UUID,
    val status: DeliveryStatus,
    val attempts: Int,
    val correlationId: String,
    val nextAttemptAt: Instant?,
    val responseStatus: Int?,
    val responseBody: String?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deliveredAt: Instant?,
    val deadLetteredAt: Instant?,
)

data class DeadLetterView(
    val id: UUID,
    val deliveryId: UUID,
    val endpointId: UUID,
    val reason: String,
    val failedAt: Instant,
    val replayedAt: Instant?,
    val replayCount: Int,
    val correlationId: String,
)

data class DeliveryClaim(
    val deliveryId: UUID,
    val eventId: UUID,
    val endpointId: UUID,
    val targetUrl: String,
    val payload: String,
    val correlationId: String,
    val signingSecret: String,
    val attempt: Int,
    val processingToken: UUID,
)

sealed interface ClaimResult {
    data class Claimed(
        val delivery: DeliveryClaim,
    ) : ClaimResult

    data object AlreadyHandled : ClaimResult

    data object NotReady : ClaimResult

    data object Busy : ClaimResult

    data object Missing : ClaimResult
}

data class OutboxMessage(
    val id: UUID,
    val deliveryId: UUID,
    val payload: String,
    val publishAttempts: Int,
)

data class DeliveryAttemptResult(
    val statusCode: Int?,
    val responseBody: String?,
    val error: String?,
    val retryable: Boolean,
) {
    val successful: Boolean
        get() = statusCode != null && statusCode in 200..299
}

class NotFoundException(
    message: String,
) : RuntimeException(message)

class ConflictException(
    message: String,
) : RuntimeException(message)

class InvalidRequestException(
    message: String,
) : RuntimeException(message)

class DeliveryBusyException(
    deliveryId: UUID,
) : RuntimeException("Delivery $deliveryId is currently leased by another worker")
