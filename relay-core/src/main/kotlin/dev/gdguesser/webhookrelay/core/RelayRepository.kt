package dev.gdguesser.webhookrelay.core

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID

data class StoredEvent(
    val eventId: UUID,
    val deliveryId: UUID,
    val correlationId: String,
    val status: DeliveryStatus,
)

data class LockedDelivery(
    val id: UUID,
    val status: DeliveryStatus,
    val dispatchSequence: Int,
    val correlationId: String,
)

@Repository
class RelayRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val secretCipher: SecretCipher,
) {
    fun insertEndpoint(
        id: UUID,
        url: String,
        description: String?,
        encryptedSecret: EncryptedSecret,
        now: Instant,
    ) {
        val parameters =
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("url", url)
                .addValue("description", description)
                .addValue("ciphertext", encryptedSecret.ciphertext, Types.BINARY)
                .addValue("nonce", encryptedSecret.nonce, Types.BINARY)
                .addValue("now", now.jdbcValue())
        jdbc.update(
            """
            INSERT INTO webhook_endpoint
                (id, target_url, description, secret_ciphertext, secret_nonce, active, created_at, updated_at)
            VALUES
                (:id, :url, :description, :ciphertext, :nonce, true, :now, :now)
            """.trimIndent(),
            parameters,
        )
    }

    fun findEndpoint(id: UUID): EndpointView? =
        jdbc
            .query(
                """
                SELECT id, target_url, description, active, created_at, updated_at
                FROM webhook_endpoint
                WHERE id = :id
                """.trimIndent(),
                mapOf("id" to id),
                endpointMapper,
            ).firstOrNull()

    fun listEndpoints(): List<EndpointView> =
        jdbc.query(
            """
            SELECT id, target_url, description, active, created_at, updated_at
            FROM webhook_endpoint
            ORDER BY created_at DESC
            """.trimIndent(),
            emptyMap<String, Any>(),
            endpointMapper,
        )

    fun insertEvent(
        eventId: UUID,
        endpointId: UUID,
        idempotencyKey: String,
        payload: String,
        correlationId: String,
        now: Instant,
    ): Boolean =
        jdbc
            .query(
                """
                INSERT INTO webhook_event
                    (id, endpoint_id, idempotency_key, payload, correlation_id, created_at)
                VALUES
                    (:id, :endpointId, :idempotencyKey, CAST(:payload AS jsonb), :correlationId, :now)
                ON CONFLICT (endpoint_id, idempotency_key) DO NOTHING
                RETURNING id
                """.trimIndent(),
                mapOf(
                    "id" to eventId,
                    "endpointId" to endpointId,
                    "idempotencyKey" to idempotencyKey,
                    "payload" to payload,
                    "correlationId" to correlationId,
                    "now" to now.jdbcValue(),
                ),
            ) { resultSet, _ -> resultSet.getObject("id", UUID::class.java) }
            .isNotEmpty()

    fun createDelivery(
        deliveryId: UUID,
        eventId: UUID,
        endpointId: UUID,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO webhook_delivery
                (id, event_id, endpoint_id, status, attempts, dispatch_sequence, created_at, updated_at)
            VALUES
                (:id, :eventId, :endpointId, 'PENDING', 0, 0, :now, :now)
            """.trimIndent(),
            mapOf(
                "id" to deliveryId,
                "eventId" to eventId,
                "endpointId" to endpointId,
                "now" to now.jdbcValue(),
            ),
        )
    }

    fun findStoredEvent(
        endpointId: UUID,
        idempotencyKey: String,
    ): StoredEvent? =
        jdbc
            .query(
                """
                SELECT e.id AS event_id, d.id AS delivery_id, e.correlation_id, d.status
                FROM webhook_event e
                JOIN webhook_delivery d ON d.event_id = e.id
                WHERE e.endpoint_id = :endpointId AND e.idempotency_key = :idempotencyKey
                """.trimIndent(),
                mapOf("endpointId" to endpointId, "idempotencyKey" to idempotencyKey),
            ) { resultSet, _ ->
                StoredEvent(
                    eventId = resultSet.uuid("event_id"),
                    deliveryId = resultSet.uuid("delivery_id"),
                    correlationId = resultSet.getString("correlation_id"),
                    status = DeliveryStatus.valueOf(resultSet.getString("status")),
                )
            }.firstOrNull()

    fun insertOutbox(
        deliveryId: UUID,
        dispatchSequence: Int,
        payload: String,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO outbox_message
                (id, delivery_id, dispatch_sequence, event_type, payload, created_at)
            VALUES
                (:id, :deliveryId, :dispatchSequence, 'DELIVERY_REQUESTED', CAST(:payload AS jsonb), :now)
            ON CONFLICT (delivery_id, dispatch_sequence) DO NOTHING
            """.trimIndent(),
            mapOf(
                "id" to UUID.randomUUID(),
                "deliveryId" to deliveryId,
                "dispatchSequence" to dispatchSequence,
                "payload" to payload,
                "now" to now.jdbcValue(),
            ),
        )
    }

    fun findDelivery(id: UUID): DeliveryView? =
        jdbc
            .query(
                "$DELIVERY_SELECT WHERE d.id = :id",
                mapOf("id" to id),
                deliveryMapper,
            ).firstOrNull()

    fun listDeliveries(
        endpointId: UUID?,
        status: DeliveryStatus?,
        limit: Int,
    ): List<DeliveryView> {
        val conditions = mutableListOf<String>()
        val parameters = mutableMapOf<String, Any>("limit" to limit)
        endpointId?.let {
            conditions += "d.endpoint_id = :endpointId"
            parameters["endpointId"] = it
        }
        status?.let {
            conditions += "d.status = :status"
            parameters["status"] = it.name
        }
        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        return jdbc.query(
            "$DELIVERY_SELECT$where ORDER BY d.created_at DESC LIMIT :limit",
            parameters,
            deliveryMapper,
        )
    }

    fun findDeadLetter(id: UUID): DeadLetterView? =
        jdbc
            .query(
                "$DEAD_LETTER_SELECT WHERE dl.id = :id AND dl.replayed_at IS NULL",
                mapOf("id" to id),
                deadLetterMapper,
            ).firstOrNull()

    fun listDeadLetters(limit: Int): List<DeadLetterView> =
        jdbc.query(
            """
            $DEAD_LETTER_SELECT
            WHERE dl.replayed_at IS NULL
            ORDER BY dl.failed_at DESC
            LIMIT :limit
            """.trimIndent(),
            mapOf("limit" to limit),
            deadLetterMapper,
        )

    fun lockDelivery(id: UUID): LockedDelivery? =
        jdbc
            .query(
                """
                SELECT d.id, d.status, d.dispatch_sequence, e.correlation_id
                FROM webhook_delivery d
                JOIN webhook_event e ON e.id = d.event_id
                WHERE d.id = :id
                FOR UPDATE
                """.trimIndent(),
                mapOf("id" to id),
            ) { resultSet, _ ->
                LockedDelivery(
                    id = resultSet.uuid("id"),
                    status = DeliveryStatus.valueOf(resultSet.getString("status")),
                    dispatchSequence = resultSet.getInt("dispatch_sequence"),
                    correlationId = resultSet.getString("correlation_id"),
                )
            }.firstOrNull()

    fun replay(
        deliveryId: UUID,
        dispatchSequence: Int,
        now: Instant,
    ) {
        jdbc.update(
            """
            UPDATE webhook_delivery
            SET status = 'PENDING',
                attempts = 0,
                dispatch_sequence = :dispatchSequence,
                next_attempt_at = NULL,
                processing_token = NULL,
                processing_until = NULL,
                response_status = NULL,
                response_body = NULL,
                last_error = NULL,
                dead_lettered_at = NULL,
                updated_at = :now
            WHERE id = :id
            """.trimIndent(),
            mapOf(
                "id" to deliveryId,
                "dispatchSequence" to dispatchSequence,
                "now" to now.jdbcValue(),
            ),
        )
        jdbc.update(
            """
            UPDATE dead_letter
            SET replayed_at = :now, replay_count = replay_count + 1
            WHERE delivery_id = :id
            """.trimIndent(),
            mapOf("id" to deliveryId, "now" to now.jdbcValue()),
        )
    }

    fun claim(
        deliveryId: UUID,
        processingToken: UUID,
        now: Instant,
        leaseUntil: Instant,
    ): DeliveryClaim? =
        jdbc
            .query(
                """
                WITH claimed AS (
                    UPDATE webhook_delivery
                    SET status = 'PROCESSING',
                        attempts = attempts + 1,
                        processing_token = :processingToken,
                        processing_until = :leaseUntil,
                        updated_at = :now
                    WHERE id = :deliveryId
                      AND (
                          status = 'PENDING'
                          OR (status = 'RETRYING' AND next_attempt_at <= :now)
                          OR (status = 'PROCESSING' AND processing_until < :now)
                      )
                    RETURNING *
                )
                SELECT c.id AS delivery_id,
                       c.event_id,
                       c.endpoint_id,
                       c.attempts,
                       ep.target_url,
                       ep.secret_ciphertext,
                       ep.secret_nonce,
                       e.payload::text AS payload,
                       e.correlation_id
                FROM claimed c
                JOIN webhook_endpoint ep ON ep.id = c.endpoint_id
                JOIN webhook_event e ON e.id = c.event_id
                """.trimIndent(),
                mapOf(
                    "deliveryId" to deliveryId,
                    "processingToken" to processingToken,
                    "leaseUntil" to leaseUntil.jdbcValue(),
                    "now" to now.jdbcValue(),
                ),
            ) { resultSet, _ ->
                DeliveryClaim(
                    deliveryId = resultSet.uuid("delivery_id"),
                    eventId = resultSet.uuid("event_id"),
                    endpointId = resultSet.uuid("endpoint_id"),
                    targetUrl = resultSet.getString("target_url"),
                    payload = resultSet.getString("payload"),
                    correlationId = resultSet.getString("correlation_id"),
                    signingSecret =
                        secretCipher.decrypt(
                            resultSet.getBytes("secret_ciphertext"),
                            resultSet.getBytes("secret_nonce"),
                        ),
                    attempt = resultSet.getInt("attempts"),
                    processingToken = processingToken,
                )
            }.firstOrNull()

    fun completeDelivery(
        claim: DeliveryClaim,
        result: DeliveryAttemptResult,
        now: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE webhook_delivery
            SET status = 'DELIVERED',
                response_status = :responseStatus,
                response_body = :responseBody,
                last_error = NULL,
                processing_token = NULL,
                processing_until = NULL,
                delivered_at = :now,
                updated_at = :now
            WHERE id = :id AND status = 'PROCESSING' AND processing_token = :processingToken
            """.trimIndent(),
            mapOf(
                "id" to claim.deliveryId,
                "processingToken" to claim.processingToken,
                "responseStatus" to result.statusCode,
                "responseBody" to result.responseBody,
                "now" to now.jdbcValue(),
            ),
        ) == 1

    fun scheduleRetry(
        claim: DeliveryClaim,
        result: DeliveryAttemptResult,
        nextAttemptAt: Instant,
        now: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE webhook_delivery
            SET status = 'RETRYING',
                next_attempt_at = :nextAttemptAt,
                response_status = :responseStatus,
                response_body = :responseBody,
                last_error = :lastError,
                processing_token = NULL,
                processing_until = NULL,
                updated_at = :now
            WHERE id = :id AND status = 'PROCESSING' AND processing_token = :processingToken
            """.trimIndent(),
            mapOf(
                "id" to claim.deliveryId,
                "processingToken" to claim.processingToken,
                "nextAttemptAt" to nextAttemptAt.jdbcValue(),
                "responseStatus" to result.statusCode,
                "responseBody" to result.responseBody,
                "lastError" to result.error,
                "now" to now.jdbcValue(),
            ),
        ) == 1

    fun deadLetter(
        claim: DeliveryClaim,
        result: DeliveryAttemptResult,
        reason: String,
        now: Instant,
    ): Boolean {
        val updated =
            jdbc.update(
                """
                UPDATE webhook_delivery
                SET status = 'DEAD_LETTERED',
                    response_status = :responseStatus,
                    response_body = :responseBody,
                    last_error = :reason,
                    processing_token = NULL,
                    processing_until = NULL,
                    dead_lettered_at = :now,
                    updated_at = :now
                WHERE id = :id AND status = 'PROCESSING' AND processing_token = :processingToken
                """.trimIndent(),
                mapOf(
                    "id" to claim.deliveryId,
                    "processingToken" to claim.processingToken,
                    "responseStatus" to result.statusCode,
                    "responseBody" to result.responseBody,
                    "reason" to reason,
                    "now" to now.jdbcValue(),
                ),
            )
        if (updated == 1) {
            jdbc.update(
                """
                INSERT INTO dead_letter (id, delivery_id, reason, failed_at, replay_count)
                VALUES (:id, :deliveryId, :reason, :now, 0)
                ON CONFLICT (delivery_id) DO UPDATE
                SET reason = EXCLUDED.reason,
                    failed_at = EXCLUDED.failed_at,
                    replayed_at = NULL
                """.trimIndent(),
                mapOf(
                    "id" to UUID.randomUUID(),
                    "deliveryId" to claim.deliveryId,
                    "reason" to reason,
                    "now" to now.jdbcValue(),
                ),
            )
        }
        return updated == 1
    }

    fun dispatchDueRetries(
        now: Instant,
        limit: Int,
    ): List<LockedDelivery> {
        val due =
            jdbc.query(
                """
                SELECT d.id, d.status, d.dispatch_sequence, e.correlation_id
                FROM webhook_delivery d
                JOIN webhook_event e ON e.id = d.event_id
                WHERE d.status = 'RETRYING' AND d.next_attempt_at <= :now
                ORDER BY d.next_attempt_at
                LIMIT :limit
                FOR UPDATE OF d SKIP LOCKED
                """.trimIndent(),
                mapOf("now" to now.jdbcValue(), "limit" to limit),
            ) { resultSet, _ ->
                LockedDelivery(
                    id = resultSet.uuid("id"),
                    status = DeliveryStatus.RETRYING,
                    dispatchSequence = resultSet.getInt("dispatch_sequence") + 1,
                    correlationId = resultSet.getString("correlation_id"),
                )
            }
        due.forEach {
            jdbc.update(
                """
                UPDATE webhook_delivery
                SET status = 'PENDING',
                    dispatch_sequence = :dispatchSequence,
                    next_attempt_at = NULL,
                    updated_at = :now
                WHERE id = :id AND status = 'RETRYING'
                """.trimIndent(),
                mapOf(
                    "id" to it.id,
                    "dispatchSequence" to it.dispatchSequence,
                    "now" to now.jdbcValue(),
                ),
            )
        }
        return due
    }

    fun lockOutbox(limit: Int): List<OutboxMessage> =
        jdbc.query(
            """
            SELECT id, delivery_id, payload::text AS payload, publish_attempts
            FROM outbox_message
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            mapOf("limit" to limit),
        ) { resultSet, _ ->
            OutboxMessage(
                id = resultSet.uuid("id"),
                deliveryId = resultSet.uuid("delivery_id"),
                payload = resultSet.getString("payload"),
                publishAttempts = resultSet.getInt("publish_attempts"),
            )
        }

    fun markOutboxPublished(
        id: UUID,
        now: Instant,
    ) {
        jdbc.update(
            """
            UPDATE outbox_message
            SET published_at = :now, publish_attempts = publish_attempts + 1, last_error = NULL
            WHERE id = :id
            """.trimIndent(),
            mapOf("id" to id, "now" to now.jdbcValue()),
        )
    }

    fun markOutboxFailed(
        id: UUID,
        error: String,
    ) {
        jdbc.update(
            """
            UPDATE outbox_message
            SET publish_attempts = publish_attempts + 1, last_error = :error
            WHERE id = :id
            """.trimIndent(),
            mapOf("id" to id, "error" to error.take(MAX_ERROR_LENGTH)),
        )
    }

    fun countEvents(): Long =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM webhook_event",
            emptyMap<String, Any>(),
            Long::class.java,
        ) ?: 0

    fun countOutbox(): Long =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_message",
            emptyMap<String, Any>(),
            Long::class.java,
        ) ?: 0

    private fun deliveryState(id: UUID): Pair<DeliveryStatus, Instant?>? =
        jdbc
            .query(
                "SELECT status, processing_until FROM webhook_delivery WHERE id = :id",
                mapOf("id" to id),
            ) { resultSet, _ ->
                DeliveryStatus.valueOf(resultSet.getString("status")) to
                    resultSet.getTimestamp("processing_until")?.toInstant()
            }.firstOrNull()

    fun classifyUnclaimed(
        deliveryId: UUID,
        now: Instant,
    ): ClaimResult {
        val state = deliveryState(deliveryId) ?: return ClaimResult.Missing
        return when (state.first) {
            DeliveryStatus.DELIVERED,
            DeliveryStatus.DEAD_LETTERED,
            -> ClaimResult.AlreadyHandled
            DeliveryStatus.PROCESSING ->
                if (state.second?.isAfter(now) == true) ClaimResult.Busy else ClaimResult.NotReady
            DeliveryStatus.RETRYING,
            DeliveryStatus.PENDING,
            -> ClaimResult.NotReady
        }
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 2_000

        private val endpointMapper =
            RowMapper { resultSet, _ ->
                EndpointView(
                    id = resultSet.uuid("id"),
                    url = resultSet.getString("target_url"),
                    description = resultSet.getString("description"),
                    active = resultSet.getBoolean("active"),
                    createdAt = resultSet.instant("created_at"),
                    updatedAt = resultSet.instant("updated_at"),
                )
            }

        private val deliveryMapper =
            RowMapper { resultSet, _ ->
                DeliveryView(
                    id = resultSet.uuid("id"),
                    eventId = resultSet.uuid("event_id"),
                    endpointId = resultSet.uuid("endpoint_id"),
                    status = DeliveryStatus.valueOf(resultSet.getString("status")),
                    attempts = resultSet.getInt("attempts"),
                    correlationId = resultSet.getString("correlation_id"),
                    nextAttemptAt = resultSet.nullableInstant("next_attempt_at"),
                    responseStatus = resultSet.nullableInt("response_status"),
                    responseBody = resultSet.getString("response_body"),
                    lastError = resultSet.getString("last_error"),
                    createdAt = resultSet.instant("created_at"),
                    updatedAt = resultSet.instant("updated_at"),
                    deliveredAt = resultSet.nullableInstant("delivered_at"),
                    deadLetteredAt = resultSet.nullableInstant("dead_lettered_at"),
                )
            }

        private val deadLetterMapper =
            RowMapper { resultSet, _ ->
                DeadLetterView(
                    id = resultSet.uuid("id"),
                    deliveryId = resultSet.uuid("delivery_id"),
                    endpointId = resultSet.uuid("endpoint_id"),
                    reason = resultSet.getString("reason"),
                    failedAt = resultSet.instant("failed_at"),
                    replayedAt = resultSet.nullableInstant("replayed_at"),
                    replayCount = resultSet.getInt("replay_count"),
                    correlationId = resultSet.getString("correlation_id"),
                )
            }

        private const val DELIVERY_SELECT =
            """
            SELECT d.id,
                   d.event_id,
                   d.endpoint_id,
                   d.status,
                   d.attempts,
                   e.correlation_id,
                   d.next_attempt_at,
                   d.response_status,
                   d.response_body,
                   d.last_error,
                   d.created_at,
                   d.updated_at,
                   d.delivered_at,
                   d.dead_lettered_at
            FROM webhook_delivery d
            JOIN webhook_event e ON e.id = d.event_id
            """

        private const val DEAD_LETTER_SELECT =
            """
            SELECT dl.id,
                   dl.delivery_id,
                   d.endpoint_id,
                   dl.reason,
                   dl.failed_at,
                   dl.replayed_at,
                   dl.replay_count,
                   e.correlation_id
            FROM dead_letter dl
            JOIN webhook_delivery d ON d.id = dl.delivery_id
            JOIN webhook_event e ON e.id = d.event_id
            """
    }
}

private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

private fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

private fun ResultSet.nullableInstant(column: String): Instant? = getTimestamp(column)?.toInstant()

private fun ResultSet.nullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

private fun Instant.jdbcValue(): Timestamp = Timestamp.from(this)
