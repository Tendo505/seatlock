package io.github.tendo505.seatlock.web

import io.github.tendo505.seatlock.domain.BookingException
import io.github.tendo505.seatlock.domain.EventNotFound
import io.github.tendo505.seatlock.domain.HoldExpired
import io.github.tendo505.seatlock.domain.HoldNotActive
import io.github.tendo505.seatlock.domain.HoldNotFound
import io.github.tendo505.seatlock.domain.IdempotencyKeyReused
import io.github.tendo505.seatlock.domain.NotEnoughSeats
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI

/** Every error is an RFC 9457 problem+json document with a stable `type` clients can switch on. */
@RestControllerAdvice
class ApiErrors : ResponseEntityExceptionHandler() {

    @ExceptionHandler(BookingException::class)
    fun booking(e: BookingException): ProblemDetail {
        val (status, slug) = when (e) {
            is EventNotFound, is HoldNotFound -> HttpStatus.NOT_FOUND to "not-found"
            is NotEnoughSeats -> HttpStatus.CONFLICT to "not-enough-seats"
            is HoldExpired -> HttpStatus.CONFLICT to "hold-expired"
            is HoldNotActive -> HttpStatus.CONFLICT to "hold-not-active"
            is IdempotencyKeyReused -> HttpStatus.UNPROCESSABLE_ENTITY to "idempotency-key-reused"
        }
        return ProblemDetail.forStatusAndDetail(status, e.message ?: status.reasonPhrase).apply {
            type = URI.create("https://github.com/Tendo505/seatlock#$slug")
            if (e is NotEnoughSeats) {
                setProperty("requested", e.requested)
                setProperty("available", e.available)
            }
        }
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: "Bad request")

    /** Field-level validation errors, as { "errors": { "quantity": "must be greater than or equal to 1" } }. */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed").apply {
            setProperty("errors", ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") })
        }
        return ResponseEntity.badRequest().body(problem)
    }
}
