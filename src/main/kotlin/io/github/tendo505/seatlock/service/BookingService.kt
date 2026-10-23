package io.github.tendo505.seatlock.service

import io.github.tendo505.seatlock.SeatlockProperties
import io.github.tendo505.seatlock.domain.Event
import io.github.tendo505.seatlock.domain.EventNotFound
import io.github.tendo505.seatlock.domain.Hold
import io.github.tendo505.seatlock.domain.HoldExpired
import io.github.tendo505.seatlock.domain.HoldNotActive
import io.github.tendo505.seatlock.domain.HoldNotFound
import io.github.tendo505.seatlock.domain.HoldStatus
import io.github.tendo505.seatlock.domain.IdempotencyKeyReused
import io.github.tendo505.seatlock.domain.NotEnoughSeats
import io.github.tendo505.seatlock.repo.EventRepository
import io.github.tendo505.seatlock.repo.HoldRepository
import io.github.tendo505.seatlock.repo.IdempotencyRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class HoldResult(val hold: Hold, val replayed: Boolean)

/**
 * The booking workflow. Every state change is a guarded single-row UPDATE
 * inside a short transaction, so correctness never depends on in-memory
 * locks: the service is safe to run as many instances against one database.
 */
@Service
class BookingService(
    private val events: EventRepository,
    private val holds: HoldRepository,
    private val idempotency: IdempotencyRepository,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val props: SeatlockProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    fun createEvent(name: String, venue: String, startsAt: Instant, capacity: Int): Event {
        val event = Event(UUID.randomUUID(), name.trim(), venue.trim(), startsAt, capacity, capacity, now())
        events.insert(event)
        return event
    }

    fun event(id: UUID): Event = events.find(id) ?: throw EventNotFound(id)

    fun events(upcomingOnly: Boolean): List<Event> = events.list(if (upcomingOnly) now() else null)

    fun hold(id: UUID): Hold = holds.find(id) ?: throw HoldNotFound(id)

    /**
     * Reserve seats for a limited time.
     *
     * With an Idempotency-Key, retries of the same request return the original
     * hold instead of taking more seats. The key row is inserted *first* in the
     * transaction: if two identical requests race, the database makes the second
     * insert wait for the first to commit and then fail, which rolls it back
     * before it touches seat inventory. The loser then replays the winner's result.
     */
    fun createHold(eventId: UUID, quantity: Int, email: String, idempotencyKey: String?): HoldResult {
        require(quantity in 1..props.maxSeatsPerHold) { "quantity must be between 1 and ${props.maxSeatsPerHold}" }
        val requestHash = sha256("$eventId|$quantity|${email.trim().lowercase()}")

        if (idempotencyKey != null) replay(idempotencyKey, requestHash)?.let { return it }

        return try {
            tx.execute {
                val now = now()
                if (idempotencyKey != null) idempotency.insert(idempotencyKey, requestHash, now)

                if (!events.tryReserve(eventId, quantity)) {
                    val event = events.find(eventId) ?: throw EventNotFound(eventId)
                    throw NotEnoughSeats(quantity, event.available)
                }
                val hold = Hold(
                    id = UUID.randomUUID(), eventId = eventId, quantity = quantity,
                    customerEmail = email.trim().lowercase(), status = HoldStatus.HELD,
                    expiresAt = now.plus(props.holdTtl), createdAt = now, updatedAt = now,
                )
                holds.insert(hold)
                if (idempotencyKey != null) idempotency.attachHold(idempotencyKey, hold.id)
                HoldResult(hold, replayed = false)
            }!!
        } catch (e: DuplicateKeyException) {
            if (idempotencyKey == null) throw e
            replay(idempotencyKey, requestHash) ?: throw IllegalStateException("Idempotency record for '$idempotencyKey' disappeared", e)
        }
    }

    private fun replay(key: String, requestHash: String): HoldResult? {
        val record = idempotency.find(key) ?: return null
        if (record.requestHash != requestHash) throw IdempotencyKeyReused(key)
        val holdId = record.holdId ?: return null
        return HoldResult(hold(holdId), replayed = true)
    }

    /**
     * Turn a hold into a booking. Confirming twice is harmless (returns the
     * booking); confirming after the deadline releases the seats and fails.
     */
    fun confirm(holdId: UUID): Hold {
        val now = now()
        val confirmed = tx.execute { holds.transition(holdId, HoldStatus.HELD, HoldStatus.CONFIRMED, now, notExpiredAt = now) }!!
        if (confirmed) return hold(holdId)

        val current = hold(holdId)
        return when {
            current.status == HoldStatus.CONFIRMED -> current
            current.status == HoldStatus.HELD -> {
                // Still HELD but past its deadline: release it now rather than waiting for the sweep.
                release(holdId, HoldStatus.EXPIRED)
                throw HoldExpired(holdId)
            }
            current.status == HoldStatus.EXPIRED -> throw HoldExpired(holdId)
            else -> throw HoldNotActive(holdId, current.status)
        }
    }

    /** Let go of a hold, or cancel a confirmed booking. Seats return to the pool exactly once. */
    fun cancel(holdId: UUID): Hold {
        val current = hold(holdId)
        val target = when (current.status) {
            HoldStatus.HELD -> HoldStatus.RELEASED
            HoldStatus.CONFIRMED -> HoldStatus.CANCELLED
            else -> return current // already released / expired / cancelled: idempotent
        }
        // If this loses a race (e.g. the expiry job got there first), nothing
        // happens and we simply report whichever state won.
        release(holdId, target, from = current.status)
        return hold(holdId)
    }

    /**
     * Move a hold out of [from] and return its seats, atomically. Returns
     * false if another caller already moved it, in which case nothing happens.
     */
    fun release(holdId: UUID, to: HoldStatus, from: HoldStatus = HoldStatus.HELD): Boolean =
        tx.execute {
            val hold = holds.find(holdId) ?: throw HoldNotFound(holdId)
            if (!holds.transition(holdId, from, to, now())) return@execute false
            events.returnSeats(hold.eventId, hold.quantity)
            true
        }!!

    /** Release every hold whose deadline has passed. Returns how many were released by this call. */
    fun expireDueHolds(batchSize: Int = 500): Int {
        var released = 0
        while (true) {
            val due = holds.findExpired(now(), batchSize)
            if (due.isEmpty()) break
            released += due.count { release(it, HoldStatus.EXPIRED) }
            if (due.size < batchSize) break
        }
        if (released > 0) log.info("Expired {} hold(s) and returned their seats", released)
        return released
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
