package dev.gdguesser.webhookrelay.worker

import dev.gdguesser.webhookrelay.core.ClaimResult
import dev.gdguesser.webhookrelay.core.DeliveryBusyException
import dev.gdguesser.webhookrelay.core.DeliveryStateService
import dev.gdguesser.webhookrelay.core.RelayMetrics
import dev.gdguesser.webhookrelay.core.RelayProperties
import dev.gdguesser.webhookrelay.core.RelayRepository
import dev.gdguesser.webhookrelay.core.RetryDispatchService
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@Service
class DeliveryProcessor(
    private val stateService: DeliveryStateService,
    private val httpDeliveryClient: HttpDeliveryClient,
    private val metrics: RelayMetrics,
    private val clock: Clock,
) {
    fun process(deliveryId: UUID) {
        when (val claimResult = stateService.claim(deliveryId)) {
            is ClaimResult.Claimed -> {
                val startedAt = clock.instant()
                val claim = claimResult.delivery
                MDC.put("correlationId", claim.correlationId)
                MDC.put("deliveryId", claim.deliveryId.toString())
                try {
                    val result = httpDeliveryClient.deliver(claim)
                    val status = stateService.record(claim, result)
                    metrics.deliveryCompleted(
                        outcome = status.name.lowercase(),
                        elapsed = Duration.between(startedAt, clock.instant()),
                    )
                    logger.info(
                        "Webhook delivery attempt completed: status={}, attempt={}, responseStatus={}",
                        status,
                        claim.attempt,
                        result.statusCode,
                    )
                } finally {
                    MDC.remove("deliveryId")
                    MDC.remove("correlationId")
                }
            }
            ClaimResult.Busy -> throw DeliveryBusyException(deliveryId)
            ClaimResult.AlreadyHandled -> logger.debug("Ignoring already handled delivery {}", deliveryId)
            ClaimResult.NotReady -> logger.debug("Ignoring delivery {} that is not due", deliveryId)
            ClaimResult.Missing -> logger.warn("Ignoring message for unknown delivery {}", deliveryId)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DeliveryProcessor::class.java)
    }
}

@Component
class DeliveryMessageListener(
    private val deliveryProcessor: DeliveryProcessor,
    private val jsonMapper: JsonMapper,
) {
    @KafkaListener(
        topics = ["\${relay.outbox.topic}"],
        groupId = "\${spring.kafka.consumer.group-id}",
    )
    fun onMessage(
        payload: String,
        acknowledgment: Acknowledgment,
    ) {
        val deliveryId =
            runCatching {
                val node = jsonMapper.readTree(payload)
                UUID.fromString(node.path("deliveryId").stringValue())
            }.getOrElse {
                logger.error("Discarding malformed internal delivery message", it)
                acknowledgment.acknowledge()
                return
            }
        deliveryProcessor.process(deliveryId)
        acknowledgment.acknowledge()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DeliveryMessageListener::class.java)
    }
}

@Service
class OutboxPublisher(
    private val repository: RelayRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val properties: RelayProperties,
    private val metrics: RelayMetrics,
    private val clock: Clock,
) {
    @Transactional
    fun publishBatch(): Int {
        val messages = repository.lockOutbox(properties.outbox.batchSize)
        messages.forEach { message ->
            try {
                kafkaTemplate
                    .send(properties.outbox.topic, message.deliveryId.toString(), message.payload)
                    .get(properties.outbox.sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
                repository.markOutboxPublished(message.id, clock.instant())
                metrics.outboxPublished()
            } catch (exception: Exception) {
                val error = "${exception.javaClass.simpleName}: ${exception.message ?: "publish failed"}"
                repository.markOutboxFailed(message.id, error)
                metrics.outboxFailed()
                logger.warn("Outbox publish failed for message {}", message.id, exception)
            }
        }
        return messages.size
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OutboxPublisher::class.java)
    }
}

@Component
class WorkerSchedules(
    private val outboxPublisher: OutboxPublisher,
    private val retryDispatchService: RetryDispatchService,
) {
    @Scheduled(fixedDelayString = "\${relay.outbox.publish-interval:250ms}")
    fun publishOutbox() {
        outboxPublisher.publishBatch()
    }

    @Scheduled(fixedDelayString = "\${relay.delivery.retry-scan-interval:500ms}")
    fun enqueueRetries() {
        retryDispatchService.enqueueDue()
    }
}
