package dev.gdguesser.webhookrelay.core

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestConstructor
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.Base64
import java.util.UUID

@SpringBootTest(classes = [CoreIntegrationTestApplication::class])
@Testcontainers(disabledWithoutDocker = true)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RelayPersistenceIntegrationTest(
    private val endpointService: EndpointService,
    private val eventService: EventService,
    private val deliveryStateService: DeliveryStateService,
    private val deliveryQueryService: DeliveryQueryService,
    private val deadLetterQueryService: DeadLetterQueryService,
    private val replayService: ReplayService,
    private val repository: RelayRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val jsonMapper: JsonMapper,
) {
    @Test
    fun `duplicate input returns original delivery and creates no extra rows`() {
        val endpoint = endpointService.register("http://localhost:9999/hook", null, null)
        val key = "duplicate-${UUID.randomUUID()}"
        val payload = jsonMapper.readTree("""{"kind":"duplicate-test"}""")

        val first = eventService.accept(endpoint.endpoint.id, key, payload, "correlation-one")
        val duplicate = eventService.accept(endpoint.endpoint.id, key, payload, "correlation-two")

        assertThat(duplicate.duplicate).isTrue()
        assertThat(duplicate.eventId).isEqualTo(first.eventId)
        assertThat(duplicate.deliveryId).isEqualTo(first.deliveryId)
        assertThat(duplicate.correlationId).isEqualTo("correlation-one")
    }

    @Test
    fun `outbox failure rolls back event and delivery atomically`() {
        val endpoint = endpointService.register("http://localhost:9999/hook", null, null)
        val eventsBefore = repository.countEvents()
        val outboxBefore = repository.countOutbox()
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION fail_test_outbox() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'forced test outbox failure';
            END;
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER fail_test_outbox_trigger
            BEFORE INSERT ON outbox_message
            FOR EACH ROW EXECUTE FUNCTION fail_test_outbox()
            """.trimIndent(),
        )
        try {
            assertThatThrownBy {
                eventService.accept(
                    endpoint.endpoint.id,
                    "atomic-${UUID.randomUUID()}",
                    jsonMapper.readTree("""{"kind":"atomicity-test"}"""),
                    "atomicity-correlation",
                )
            }.hasMessageContaining("forced test outbox failure")
        } finally {
            jdbcTemplate.execute("DROP TRIGGER fail_test_outbox_trigger ON outbox_message")
            jdbcTemplate.execute("DROP FUNCTION fail_test_outbox()")
        }

        assertThat(repository.countEvents()).isEqualTo(eventsBefore)
        assertThat(repository.countOutbox()).isEqualTo(outboxBefore)
    }

    @Test
    fun `delivery state deduplicates concurrent and redelivered transport messages`() {
        val endpoint = endpointService.register("http://localhost:9999/hook", null, null)
        val accepted =
            eventService.accept(
                endpoint.endpoint.id,
                "dedupe-${UUID.randomUUID()}",
                jsonMapper.readTree("""{"kind":"dedupe-test"}"""),
                "dedupe-correlation",
            )

        val firstClaim = deliveryStateService.claim(accepted.deliveryId)
        assertThat(firstClaim).isInstanceOf(ClaimResult.Claimed::class.java)
        assertThat(deliveryStateService.claim(accepted.deliveryId)).isEqualTo(ClaimResult.Busy)

        deliveryStateService.record(
            (firstClaim as ClaimResult.Claimed).delivery,
            DeliveryAttemptResult(
                statusCode = 204,
                responseBody = null,
                error = null,
                retryable = false,
            ),
        )

        assertThat(deliveryStateService.claim(accepted.deliveryId))
            .isEqualTo(ClaimResult.AlreadyHandled)
    }

    @Test
    fun `transient failure persists a jittered retry schedule`() {
        val endpoint = endpointService.register("http://localhost:9999/hook", null, null)
        val accepted =
            eventService.accept(
                endpoint.endpoint.id,
                "retry-${UUID.randomUUID()}",
                jsonMapper.readTree("""{"kind":"retry-test"}"""),
                "retry-correlation",
            )
        val claim = deliveryStateService.claim(accepted.deliveryId) as ClaimResult.Claimed

        val status =
            deliveryStateService.record(
                claim.delivery,
                DeliveryAttemptResult(
                    statusCode = 503,
                    responseBody = "temporarily unavailable",
                    error = "Receiver returned HTTP 503",
                    retryable = true,
                ),
            )

        val delivery = deliveryQueryService.get(accepted.deliveryId)
        assertThat(status).isEqualTo(DeliveryStatus.RETRYING)
        assertThat(delivery.status).isEqualTo(DeliveryStatus.RETRYING)
        assertThat(delivery.attempts).isEqualTo(1)
        assertThat(delivery.nextAttemptAt).isNotNull()
    }

    @Test
    fun `terminal failure persists dead letter and replay requeues same delivery`() {
        val endpoint = endpointService.register("http://localhost:9999/hook", null, null)
        val accepted =
            eventService.accept(
                endpoint.endpoint.id,
                "dead-${UUID.randomUUID()}",
                jsonMapper.readTree("""{"kind":"dead-letter-test"}"""),
                "dead-letter-correlation",
            )
        val claim = deliveryStateService.claim(accepted.deliveryId) as ClaimResult.Claimed

        val status =
            deliveryStateService.record(
                claim.delivery,
                DeliveryAttemptResult(
                    statusCode = 400,
                    responseBody = "invalid",
                    error = "Receiver returned HTTP 400",
                    retryable = false,
                ),
            )

        assertThat(status).isEqualTo(DeliveryStatus.DEAD_LETTERED)
        assertThat(deliveryQueryService.get(accepted.deliveryId).status)
            .isEqualTo(DeliveryStatus.DEAD_LETTERED)

        val deadLetter =
            deadLetterQueryService
                .list(10)
                .single { it.deliveryId == accepted.deliveryId }
        assertThat(deadLetter.reason).contains("Non-retryable response")

        val replayed = replayService.replayDeadLetter(deadLetter.id)
        assertThat(replayed.id).isEqualTo(accepted.deliveryId)
        assertThat(replayed.status).isEqualTo(DeliveryStatus.PENDING)
        assertThat(replayed.attempts).isZero()
    }

    companion object {
        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer(
                DockerImageName.parse("postgres:18.4-alpine3.24"),
            )

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("relay.master-key-base64") {
                Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
            }
            registry.add("relay.security.allow-http") { "true" }
            registry.add("relay.security.allow-private-networks") { "true" }
        }
    }
}

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan("dev.gdguesser.webhookrelay.core")
class CoreIntegrationTestApplication {
    @Bean
    fun jsonMapper(): JsonMapper = jsonMapper { addModule(kotlinModule()) }

    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}
