package io.github.tendo505.seatlock.web

import io.github.tendo505.seatlock.SeatlockProperties
import io.github.tendo505.seatlock.domain.Event
import io.github.tendo505.seatlock.domain.Hold
import io.github.tendo505.seatlock.service.BookingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

// ---------- DTOs ----------

data class CreateEventRequest(
    @field:NotBlank @field:Size(max = 200) val name: String?,
    @field:NotBlank @field:Size(max = 200) val venue: String?,
    @field:NotNull @field:Future val startsAt: Instant?,
    @field:NotNull @field:Min(1) @field:Max(100_000) val capacity: Int?,
)

data class EventResponse(
    val id: UUID,
    val name: String,
    val venue: String,
    val startsAt: Instant,
    val capacity: Int,
    val available: Int,
    val soldOut: Boolean,
) {
    companion object {
        fun of(e: Event) = EventResponse(e.id, e.name, e.venue, e.startsAt, e.capacity, e.available, e.available == 0)
    }
}

data class CreateHoldRequest(
    @field:NotNull @field:Min(1) val quantity: Int?,
    @field:NotBlank @field:Email @field:Size(max = 320) val email: String?,
)

data class HoldResponse(
    val id: UUID,
    val eventId: UUID,
    val quantity: Int,
    val email: String,
    val status: String,
    val expiresAt: Instant,
    val createdAt: Instant,
) {
    companion object {
        fun of(h: Hold) = HoldResponse(h.id, h.eventId, h.quantity, h.customerEmail, h.status.name, h.expiresAt, h.createdAt)
    }
}

// ---------- controllers ----------

@RestController
@RequestMapping("/api/events")
@Tag(name = "Events")
class EventController(private val booking: BookingService) {

    @PostMapping
    @Operation(summary = "Create an event (requires X-Admin-Key)")
    fun create(@Valid @RequestBody req: CreateEventRequest): ResponseEntity<EventResponse> {
        val event = booking.createEvent(req.name!!, req.venue!!, req.startsAt!!, req.capacity!!)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(event.id).toUri()
        return ResponseEntity.created(location).body(EventResponse.of(event))
    }

    @GetMapping
    @Operation(summary = "List events, soonest first")
    fun list(@RequestParam(defaultValue = "false") upcoming: Boolean): List<EventResponse> =
        booking.events(upcoming).map(EventResponse::of)

    @GetMapping("/{id}")
    @Operation(summary = "Get an event with live seat availability")
    fun get(@PathVariable id: UUID): EventResponse = EventResponse.of(booking.event(id))

    @PostMapping("/{id}/holds")
    @Operation(summary = "Hold seats for a limited time. Send an Idempotency-Key to make retries safe.")
    fun hold(
        @PathVariable id: UUID,
        @Valid @RequestBody req: CreateHoldRequest,
        @Parameter(description = "Unique key per purchase attempt (e.g. a UUID). Retries with the same key return the original hold.")
        @RequestHeader("Idempotency-Key", required = false)
        idempotencyKey: String?,
    ): ResponseEntity<HoldResponse> {
        if (idempotencyKey != null) {
            require(Regex("^[A-Za-z0-9_-]{8,100}$").matches(idempotencyKey)) {
                "Idempotency-Key must be 8-100 characters of letters, digits, '-' or '_'"
            }
        }
        val result = booking.createHold(id, req.quantity!!, req.email!!, idempotencyKey)
        val location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/holds/{id}").buildAndExpand(result.hold.id).toUri()
        return if (result.replayed) {
            ResponseEntity.ok().header("Idempotent-Replayed", "true").location(location).body(HoldResponse.of(result.hold))
        } else {
            ResponseEntity.created(location).body(HoldResponse.of(result.hold))
        }
    }
}

@RestController
@RequestMapping("/api/holds")
@Tag(name = "Holds & bookings")
class HoldController(private val booking: BookingService) {

    @GetMapping("/{id}")
    @Operation(summary = "Get a hold or booking")
    fun get(@PathVariable id: UUID): HoldResponse = HoldResponse.of(booking.hold(id))

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm a hold into a booking (idempotent)")
    fun confirm(@PathVariable id: UUID): HoldResponse = HoldResponse.of(booking.confirm(id))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Release a hold or cancel a booking; seats return to the event")
    fun cancel(@PathVariable id: UUID): HoldResponse = HoldResponse.of(booking.cancel(id))
}

// ---------- admin key ----------

/** Guards event creation with a shared secret, compared in constant time. */
@Component
class AdminKeyInterceptor(private val props: SeatlockProperties) : HandlerInterceptor, WebMvcConfigurer {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method != "POST" || request.requestURI.trimEnd('/') != "/api/events") return true
        val given = request.getHeader("X-Admin-Key") ?: ""
        if (MessageDigest.isEqual(given.toByteArray(), props.adminKey.toByteArray())) return true
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = "application/problem+json"
        response.writer.write("""{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Missing or invalid X-Admin-Key header"}""")
        return false
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(this).addPathPatterns("/api/events", "/api/events/")
    }
}
