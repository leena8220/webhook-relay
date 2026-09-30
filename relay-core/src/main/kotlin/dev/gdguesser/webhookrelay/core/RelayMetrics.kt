package dev.gdguesser.webhookrelay.core

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RelayMetrics(
    private val registry: MeterRegistry,
) {
    fun eventAccepted(duplicate: Boolean) {
        registry
            .counter("webhook.relay.events", "result", if (duplicate) "duplicate" else "accepted")
            .increment()
    }

    fun replayed() {
        registry.counter("webhook.relay.replays").increment()
    }

    fun outboxPublished() {
        registry.counter("webhook.relay.outbox", "result", "published").increment()
    }

    fun outboxFailed() {
        registry.counter("webhook.relay.outbox", "result", "failed").increment()
    }

    fun deliveryCompleted(
        outcome: String,
        elapsed: Duration,
    ) {
        registry.counter("webhook.relay.deliveries", "outcome", outcome).increment()
        Timer
            .builder("webhook.relay.delivery.duration")
            .description("Time spent making webhook delivery HTTP requests")
            .tag("outcome", outcome)
            .register(registry)
            .record(elapsed)
    }
}
