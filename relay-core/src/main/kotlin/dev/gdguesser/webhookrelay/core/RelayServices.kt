package dev.gdguesser.webhookrelay.core

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

@Service
class EndpointService(
    private val repository: RelayRepository,
    private val secretCipher: SecretCipher,
    private val urlPolicy: TargetUrlPolicy,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    @Transactional
    fun register(
        url: String,
        description: String?,
        requestedSecret: String?,
    ): RegisteredEndpoint {
        val normalizedUrl = urlPolicy.validate(url).normalize().toASCIIString()
        val secret = requestedSecret ?: generateSecret()
        if (secret.length !in MIN_SECRET_LENGTH..MAX_SECRET_LENGTH) {
            throw InvalidRequestException(
                "Signing secret must be between $MIN_SECRET_LENGTH and $MAX_SECRET_LENGTH characters",
            )
        }
        val id = UUID.randomUUID()
        val now = clock.instant()
        repository.insertEndpoint(
            id = id,
            url = normalizedUrl,
            description = description,
            encryptedSecret = secretCipher.encrypt(secret),
            now = now,
        )
        return RegisteredEndpoint(
            endpoint =
                EndpointView(
                    id = id,
                    url = normalizedUrl,
                    description = description,
                    active = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            signingSecret = secret,
        )
    }

    fun get(id: UUID): EndpointView =
        repository.findEndpoint(id)
            ?: throw NotFoundException("Webhook endpoint $id was not found")

    fun list(): List<EndpointView> = repository.listEndpoints()

    private fun generateSecret(): String =
        ByteArray(SECRET_BYTES)
            .also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        private const val SECRET_BYTES = 32
        private const val MIN_SECRET_LENGTH = 32
        private const val MAX_SECRET_LENGTH = 256
    }
}

@Service
class EventService(
    private val repository: RelayRepository,
    private val jsonMapper: JsonMapper,
    private val metrics: RelayMetrics,
    private val properties: RelayProperties,
    private val clock: Clock,
) {
    @Transactional
    fun accept(
        endpointId: UUID,
        idempotencyKey: String,
        payload: JsonNode,
        correlationId: String,
    ): AcceptedEvent {
        if (idempotencyKey.isBlank() || idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw InvalidRequestException(
                "Idempotency-Key must contain 1 to $MAX_IDEMPOTENCY_KEY_LENGTH characters",
            )
        }
        if (correlationId.isBlank() || correlationId.length > MAX_CORRELATION_ID_LENGTH) {
            throw InvalidRequestException(
                "Correlation ID must contain 1 to $MAX_CORRELATION_ID_LENGTH characters",
            )
        }
        val serializedPayload = jsonMapper.writeValueAsString(payload)
        if (serializedPayload.toByteArray(Charsets.UTF_8).size > properties.api.maxPayloadBytes) {
            throw InvalidRequestException("Payload exceeds the configured maximum size")
        }
        val endpoint =
            repository.findEndpoint(endpointId)
                ?: throw NotFoundException("Webhook endpoint $endpointId was not found")
        if (!endpoint.active) {
            throw ConflictException("Webhook endpoint $endpointId is inactive")
        }

        val now = clock.instant()
        val eventId = UUID.randomUUID()
        val deliveryId = UUID.randomUUID()
        val inserted =
            repository.insertEvent(
                eventId = eventId,
                endpointId = endpointId,
                idempotencyKey = idempotencyKey,
                payload = serializedPayload,
                correlationId = correlationId,
                now = now,
            )
        if (!inserted) {
            val existing =
                repository.findStoredEvent(endpointId, idempotencyKey)
                    ?: error("Idempotent event exists but could not be loaded")
            metrics.eventAccepted(duplicate = true)
            return AcceptedEvent(
                eventId = existing.eventId,
                deliveryId = existing.deliveryId,
                correlationId = existing.correlationId,
                duplicate = true,
                status = existing.status,
            )
        }

        repository.createDelivery(deliveryId, eventId, endpointId, now)
        repository.insertOutbox(
            deliveryId = deliveryId,
            dispatchSequence = 0,
            payload = outboxPayload(jsonMapper, deliveryId, correlationId),
            now = now,
        )
        metrics.eventAccepted(duplicate = false)
        return AcceptedEvent(
            eventId = eventId,
            deliveryId = deliveryId,
            correlationId = correlationId,
            duplicate = false,
            status = DeliveryStatus.PENDING,
        )
    }

    companion object {
        private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
        private const val MAX_CORRELATION_ID_LENGTH = 100
    }
}

@Service
class DeliveryQueryService(
    private val repository: RelayRepository,
) {
    fun get(id: UUID): DeliveryView =
        repository.findDelivery(id)
            ?: throw NotFoundException("Delivery $id was not found")

    fun list(
        endpointId: UUID?,
        status: DeliveryStatus?,
        limit: Int,
    ): List<DeliveryView> {
        if (limit !in 1..MAX_PAGE_SIZE) {
            throw InvalidRequestException("limit must be between 1 and $MAX_PAGE_SIZE")
        }
        return repository.listDeliveries(endpointId, status, limit)
    }

    companion object {
        private const val MAX_PAGE_SIZE = 200
    }
}

@Service
class DeadLetterQueryService(
    private val repository: RelayRepository,
) {
    fun get(id: UUID): DeadLetterView =
        repository.findDeadLetter(id)
            ?: throw NotFoundException("Dead letter $id was not found")

    fun list(limit: Int): List<DeadLetterView> {
        if (limit !in 1..MAX_PAGE_SIZE) {
            throw InvalidRequestException("limit must be between 1 and $MAX_PAGE_SIZE")
        }
        return repository.listDeadLetters(limit)
    }

    companion object {
        private const val MAX_PAGE_SIZE = 200
    }
}

@Service
class ReplayService(
    private val repository: RelayRepository,
    private val jsonMapper: JsonMapper,
    private val metrics: RelayMetrics,
    private val clock: Clock,
) {
    @Transactional
    fun replayDeadLetter(deadLetterId: UUID): DeliveryView {
        val deadLetter =
            repository.findDeadLetter(deadLetterId)
                ?: throw NotFoundException("Dead letter $deadLetterId was not found")
        return replay(deadLetter.deliveryId)
    }

    @Transactional
    fun replay(id: UUID): DeliveryView {
        val locked =
            repository.lockDelivery(id)
                ?: throw NotFoundException("Delivery $id was not found")
        if (locked.status != DeliveryStatus.DEAD_LETTERED) {
            throw ConflictException("Only dead-lettered deliveries can be replayed")
        }
        val now = clock.instant()
        val dispatchSequence = locked.dispatchSequence + 1
        repository.replay(id, dispatchSequence, now)
        repository.insertOutbox(
            deliveryId = id,
            dispatchSequence = dispatchSequence,
            payload = outboxPayload(jsonMapper, id, locked.correlationId),
            now = now,
        )
        metrics.replayed()
        return repository.findDelivery(id) ?: error("Replayed delivery disappeared")
    }
}

@Service
class DeliveryStateService(
    private val repository: RelayRepository,
    private val backoffPolicy: BackoffPolicy,
    private val properties: RelayProperties,
    private val clock: Clock,
) {
    @Transactional
    fun claim(deliveryId: UUID): ClaimResult {
        val now = clock.instant()
        val token = UUID.randomUUID()
        val claimed =
            repository.claim(
                deliveryId = deliveryId,
                processingToken = token,
                now = now,
                leaseUntil = now.plus(properties.delivery.leaseDuration),
            )
        return claimed?.let(ClaimResult::Claimed)
            ?: repository.classifyUnclaimed(deliveryId, now)
    }

    @Transactional
    fun record(
        claim: DeliveryClaim,
        result: DeliveryAttemptResult,
    ): DeliveryStatus {
        val now = clock.instant()
        if (result.successful) {
            repository.completeDelivery(claim, result, now)
            return DeliveryStatus.DELIVERED
        }

        val shouldRetry = result.retryable && claim.attempt < properties.delivery.maxAttempts
        if (shouldRetry) {
            val nextAttemptAt = now.plus(backoffPolicy.delay(claim.attempt))
            repository.scheduleRetry(claim, result, nextAttemptAt, now)
            return DeliveryStatus.RETRYING
        }

        val reason =
            if (result.retryable) {
                "Maximum attempt count ${properties.delivery.maxAttempts} reached: ${result.error}"
            } else {
                "Non-retryable response: ${result.error}"
            }
        repository.deadLetter(claim, result, reason.take(MAX_ERROR_LENGTH), now)
        return DeliveryStatus.DEAD_LETTERED
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 2_000
    }
}

@Service
class RetryDispatchService(
    private val repository: RelayRepository,
    private val jsonMapper: JsonMapper,
    private val properties: RelayProperties,
    private val clock: Clock,
) {
    @Transactional
    fun enqueueDue(): Int {
        val now = clock.instant()
        val due = repository.dispatchDueRetries(now, properties.delivery.retryBatchSize)
        due.forEach {
            repository.insertOutbox(
                deliveryId = it.id,
                dispatchSequence = it.dispatchSequence,
                payload = outboxPayload(jsonMapper, it.id, it.correlationId),
                now = now,
            )
        }
        return due.size
    }
}

private fun outboxPayload(
    jsonMapper: JsonMapper,
    deliveryId: UUID,
    correlationId: String,
): String =
    jsonMapper.writeValueAsString(
        mapOf(
            "deliveryId" to deliveryId.toString(),
            "correlationId" to correlationId,
        ),
    )
