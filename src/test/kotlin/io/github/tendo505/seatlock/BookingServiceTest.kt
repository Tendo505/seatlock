package io.github.tendo505.seatlock

import io.github.tendo505.seatlock.domain.EventNotFound
import io.github.tendo505.seatlock.domain.HoldExpired
import io.github.tendo505.seatlock.domain.HoldNotActive
import io.github.tendo505.seatlock.domain.HoldStatus
import io.github.tendo505.seatlock.domain.IdempotencyKeyReused
import io.github.tendo505.seatlock.domain.NotEnoughSeats
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookingServiceTest : IntegrationTest() {

    @Nested
    inner class Holding {
        @Test
        fun `a hold takes seats and expires after the TTL`() {
            val event = newEvent(capacity = 10)
            val hold = booking.createHold(event.id, 3, " Ana@Example.com ", null).hold

            assertEquals(HoldStatus.HELD, hold.status)
            assertEquals("ana@example.com", hold.customerEmail)
            assertEquals(clock.instant().plus(Duration.ofMinutes(10)), hold.expiresAt)
            assertEquals(7, available(event.id))
        }

        @Test
        fun `cannot hold more seats than are available`() {
            val event = newEvent(capacity = 5)
            booking.createHold(event.id, 4, "a@x.io", null)

            val e = assertThrows<NotEnoughSeats> { booking.createHold(event.id, 2, "b@x.io", null) }
            assertEquals("Not enough seats: 2 requested, 1 available", e.message)
            assertEquals(1, available(event.id), "a failed hold must not change availability")
        }

        @Test
        fun `holding seats for an unknown event fails cleanly`() {
            assertThrows<EventNotFound> { booking.createHold(UUID.randomUUID(), 1, "a@x.io", null) }
        }

        @Test
        fun `quantity is bounded`() {
            val event = newEvent()
            assertThrows<IllegalArgumentException> { booking.createHold(event.id, 0, "a@x.io", null) }
            assertThrows<IllegalArgumentException> { booking.createHold(event.id, 11, "a@x.io", null) }
        }
    }

    @Nested
    inner class Confirming {
        @Test
        fun `confirming turns a hold into a booking and is idempotent`() {
            val event = newEvent(capacity = 10)
            val hold = booking.createHold(event.id, 2, "a@x.io", null).hold

            assertEquals(HoldStatus.CONFIRMED, booking.confirm(hold.id).status)
            assertEquals(HoldStatus.CONFIRMED, booking.confirm(hold.id).status)
            assertEquals(8, available(event.id))
        }

        @Test
        fun `confirming after the deadline fails and returns the seats immediately`() {
            val event = newEvent(capacity = 10)
            val hold = booking.createHold(event.id, 4, "a@x.io", null).hold
            clock.advance(Duration.ofMinutes(10).plusSeconds(1))

            assertThrows<HoldExpired> { booking.confirm(hold.id) }
            assertEquals(HoldStatus.EXPIRED, booking.hold(hold.id).status)
            assertEquals(10, available(event.id), "seats come back without waiting for the sweep")
            assertThrows<HoldExpired> { booking.confirm(hold.id) }
        }

        @Test
        fun `a released hold cannot be confirmed`() {
            val event = newEvent()
            val hold = booking.createHold(event.id, 1, "a@x.io", null).hold
            booking.cancel(hold.id)
            assertThrows<HoldNotActive> { booking.confirm(hold.id) }
        }
    }

    @Nested
    inner class Releasing {
        @Test
        fun `cancelling a hold returns the seats exactly once`() {
            val event = newEvent(capacity = 10)
            val hold = booking.createHold(event.id, 3, "a@x.io", null).hold

            assertEquals(HoldStatus.RELEASED, booking.cancel(hold.id).status)
            assertEquals(HoldStatus.RELEASED, booking.cancel(hold.id).status)
            assertEquals(10, available(event.id))
        }

        @Test
        fun `cancelling a confirmed booking returns the seats`() {
            val event = newEvent(capacity = 10)
            val hold = booking.createHold(event.id, 3, "a@x.io", null).hold
            booking.confirm(hold.id)

            assertEquals(HoldStatus.CANCELLED, booking.cancel(hold.id).status)
            assertEquals(10, available(event.id))
        }

        @Test
        fun `the sweep expires only overdue holds`() {
            val event = newEvent(capacity = 10)
            val old = booking.createHold(event.id, 2, "a@x.io", null).hold
            clock.advance(Duration.ofMinutes(6))
            val fresh = booking.createHold(event.id, 3, "b@x.io", null).hold
            clock.advance(Duration.ofMinutes(5)) // old is 11 min old, fresh is 5 min old

            assertEquals(1, booking.expireDueHolds())
            assertEquals(HoldStatus.EXPIRED, booking.hold(old.id).status)
            assertEquals(HoldStatus.HELD, booking.hold(fresh.id).status)
            assertEquals(7, available(event.id))
            assertEquals(0, booking.expireDueHolds(), "a second sweep finds nothing")
        }

        @Test
        fun `the sweep pages through large backlogs`() {
            val event = newEvent(capacity = 100)
            repeat(25) { booking.createHold(event.id, 1, "u$it@x.io", null) }
            clock.advance(Duration.ofHours(1))

            assertEquals(25, booking.expireDueHolds(batchSize = 7))
            assertEquals(100, available(event.id))
        }
    }

    @Nested
    inner class Idempotency {
        @Test
        fun `retrying with the same key returns the same hold and takes seats once`() {
            val event = newEvent(capacity = 10)
            val first = booking.createHold(event.id, 2, "a@x.io", "order-123-abc")
            val retry = booking.createHold(event.id, 2, "A@X.io", "order-123-abc")

            assertFalse(first.replayed)
            assertTrue(retry.replayed)
            assertEquals(first.hold.id, retry.hold.id)
            assertEquals(8, available(event.id))
        }

        @Test
        fun `reusing a key for a different request is rejected`() {
            val event = newEvent()
            booking.createHold(event.id, 2, "a@x.io", "order-456-def")
            assertThrows<IdempotencyKeyReused> { booking.createHold(event.id, 3, "a@x.io", "order-456-def") }
        }

        @Test
        fun `a failed request does not burn its key`() {
            val event = newEvent(capacity = 1)
            assertThrows<NotEnoughSeats> { booking.createHold(event.id, 2, "a@x.io", "order-789-ghi") }
            // The transaction rolled back, so the key is free and a corrected retry works.
            booking.createHold(event.id, 1, "a@x.io", "order-789-ghj")
            assertEquals(0, available(event.id))
        }
    }
}
