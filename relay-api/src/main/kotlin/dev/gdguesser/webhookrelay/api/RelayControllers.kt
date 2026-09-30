package dev.gdguesser.webhookrelay.api

import dev.gdguesser.webhookrelay.core.AcceptedEvent
import dev.gdguesser.webhookrelay.core.ConflictException
import dev.gdguesser.webhookrelay.core.DeadLetterQueryService
import dev.gdguesser.webhookrelay.core.DeadLetterView
import dev.gdguesser.webhookrelay.core.DeliveryQueryService
import dev.gdguesser.webhookrelay.core.DeliveryStatus
import dev.gdguesser.webhookrelay.core.DeliveryView
import dev.gdguesser.webhookrelay.core.EndpointService
import dev.gdguesser.webhookrelay.core.EndpointView
import dev.gdguesser.webhookrelay.core.EventService
import dev.gdguesser.webhookrelay.core.InvalidRequestException
import dev.gdguesser.webhookrelay.core.NotFoundException
import dev.gdguesser.webhookrelay.core.RegisteredEndpoint
import dev.gdguesser.webhookrelay.core.ReplayService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.JsonNode
import java.net.URI
import java.util.UUID

data class RegisterEndpointRequest(
    @field:NotBlank
    @field:Size(max = 2_048)
    @field:Schema(example = "https://example.com/webhooks")
    val url: String,
    @field:Size(max = 500)
    val description: String? = null,
    @field:Size(min = 32, max = 256)
    @field:Schema(
        description = "Optional signing secret. If omitted, a strong secret is generated and returned once.",
        accessMode = Schema.AccessMode.WRITE_ONLY,
    )
    val signingSecret: String? = null,
)

@RestController
@RequestMapping("/v1/endpoints")
class EndpointController(
    private val endpointService: EndpointService,
    private val eventService: EventService,
) {
    @PostMapping
    @Operation(summary = "Register a webhook endpoint")
    @ApiResponse(responseCode = "201", description = "Endpoint registered; signing secret returned once")
    fun register(
        @Valid @RequestBody request: RegisterEndpointRequest,
    ): ResponseEntity<RegisteredEndpoint> {
        val registered =
            endpointService.register(
                url = request.url,
                description = request.description,
                requestedSecret = request.signingSecret,
            )
        return ResponseEntity
            .created(URI.create("/v1/endpoints/${registered.endpoint.id}"))
            .body(registered)
    }

    @GetMapping
    @Operation(summary = "List webhook endpoints")
    fun list(): List<EndpointView> = endpointService.list()

    @GetMapping("/{endpointId}")
    @Operation(summary = "Inspect a webhook endpoint")
    fun get(
        @PathVariable endpointId: UUID,
    ): EndpointView = endpointService.get(endpointId)

    @PostMapping("/{endpointId}/events")
    @Operation(
        summary = "Accept an event for delivery",
        description = "The endpoint-scoped Idempotency-Key makes duplicate submissions safe.",
    )
    @ApiResponse(responseCode = "202", description = "New event accepted")
    @ApiResponse(responseCode = "200", description = "Existing event returned for a duplicate key")
    fun acceptEvent(
        @PathVariable endpointId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody payload: JsonNode,
    ): ResponseEntity<AcceptedEvent> {
        val accepted =
            eventService.accept(
                endpointId = endpointId,
                idempotencyKey = idempotencyKey,
                payload = payload,
                correlationId = CorrelationIdFilter.current(),
            )
        return ResponseEntity
            .status(if (accepted.duplicate) HttpStatus.OK else HttpStatus.ACCEPTED)
            .location(URI.create("/v1/deliveries/${accepted.deliveryId}"))
            .body(accepted)
    }
}

@RestController
@RequestMapping("/v1/deliveries")
class DeliveryController(
    private val deliveryQueryService: DeliveryQueryService,
    private val replayService: ReplayService,
) {
    @GetMapping
    @Operation(summary = "List recent deliveries")
    fun list(
        @RequestParam(required = false) endpointId: UUID?,
        @RequestParam(required = false) status: DeliveryStatus?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<DeliveryView> = deliveryQueryService.list(endpointId, status, limit)

    @GetMapping("/{deliveryId}")
    @Operation(summary = "Inspect a delivery")
    fun get(
        @PathVariable deliveryId: UUID,
    ): DeliveryView = deliveryQueryService.get(deliveryId)

    @PostMapping("/{deliveryId}/replay")
    @Operation(summary = "Replay a dead-lettered delivery")
    @ApiResponse(responseCode = "202", description = "Delivery requeued")
    @ApiResponse(responseCode = "409", description = "Delivery is not dead-lettered")
    fun replay(
        @PathVariable deliveryId: UUID,
    ): ResponseEntity<DeliveryView> =
        ResponseEntity
            .accepted()
            .location(URI.create("/v1/deliveries/$deliveryId"))
            .body(replayService.replay(deliveryId))
}

@RestController
@RequestMapping("/v1/dead-letters")
class DeadLetterController(
    private val queryService: DeadLetterQueryService,
    private val replayService: ReplayService,
) {
    @GetMapping
    @Operation(summary = "List unresolved dead letters")
    fun list(
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<DeadLetterView> = queryService.list(limit)

    @GetMapping("/{deadLetterId}")
    @Operation(summary = "Inspect an unresolved dead letter")
    fun get(
        @PathVariable deadLetterId: UUID,
    ): DeadLetterView = queryService.get(deadLetterId)

    @PostMapping("/{deadLetterId}/replay")
    @Operation(summary = "Replay a dead letter")
    @ApiResponse(responseCode = "202", description = "Dead-lettered delivery requeued")
    fun replay(
        @PathVariable deadLetterId: UUID,
    ): ResponseEntity<DeliveryView> {
        val delivery = replayService.replayDeadLetter(deadLetterId)
        return ResponseEntity
            .accepted()
            .location(URI.create("/v1/deliveries/${delivery.id}"))
            .body(delivery)
    }
}

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(NotFoundException::class)
    fun notFound(exception: NotFoundException): ProblemDetail =
        problem(
            HttpStatus.NOT_FOUND,
            exception.message ?: "Resource not found",
        )

    @ExceptionHandler(ConflictException::class)
    fun conflict(exception: ConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, exception.message ?: "Request conflicts with resource state")

    @ExceptionHandler(InvalidRequestException::class)
    fun invalid(exception: InvalidRequestException): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            exception.message ?: "Invalid request",
        )

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        BindException::class,
        HttpMessageNotReadableException::class,
    )
    fun validation(exception: Exception): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            exception.message ?: "Request validation failed",
        )

    private fun problem(
        status: HttpStatus,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).also {
            it.title = status.reasonPhrase
            it.setProperty("correlationId", CorrelationIdFilter.current())
        }
}

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
    @Bean
    fun relayOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("Webhook Relay API")
                .version("v1")
                .description("Register endpoints, enqueue idempotent events, inspect deliveries, and replay dead letters."),
        )
}
