package io.github.tendo505.seatlock.domain

import java.time.Instant
import java.util.UUID

data class Event(
    val id: UUID,
    val name: String,
    val venue: String,
    val startsAt: Instant,
    val capacity: Int,
    val available: Int,
    val createdAt: Instant,
)

enum class HoldStatus {
    /** Seats are reserved until `expiresAt`. */
    HELD,
    /** Paid for: the seats are the customer's. */
    CONFIRMED,
    /** The customer let the hold go before it expired. */
    RELEASED,
    /** Nobody confirmed in time; the expiry job returned the seats. */
    EXPIRED,
    /** A confirmed booking that was later cancelled. */
    CANCELLED,
}

data class Hold(
    val id: UUID,
    val eventId: UUID,
    val quantity: Int,
    val customerEmail: String,
    val status: HoldStatus,
    val expiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
)

// ---------- domain errors, mapped to HTTP problem responses in the web layer ----------

sealed class BookingException(message: String) : RuntimeException(message)

class EventNotFound(id: UUID) : BookingException("Event $id does not exist")
class HoldNotFound(id: UUID) : BookingException("Hold $id does not exist")
class NotEnoughSeats(val requested: Int, val available: Int) :
    BookingException("Not enough seats: $requested requested, $available available")
class HoldNotActive(id: UUID, status: HoldStatus) :
    BookingException("Hold $id is ${status.name.lowercase()} and can no longer be changed")
class HoldExpired(id: UUID) : BookingException("Hold $id expired before it was confirmed; the seats were released")
class IdempotencyKeyReused(key: String) :
    BookingException("Idempotency-Key '$key' was already used with a different request body")
