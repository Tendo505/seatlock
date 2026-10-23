package io.github.tendo505.seatlock

import io.github.tendo505.seatlock.domain.HoldStatus
import io.github.tendo505.seatlock.domain.NotEnoughSeats
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The core promise: no matter how requests interleave, seats are never
 * oversold and never leaked. Every task waits on a start gate so they all
 * hit the database at once, which maximises contention.
 */
class ConcurrencyTest : IntegrationTest() {

    private fun <T> race(tasks: List<() -> T>, threads: Int = 32): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(threads)
        val gate = CountDownLatch(1)
        try {
            val futures = tasks.map { task -> pool.submit(Callable { gate.await(); runCatching(task) }) }
            gate.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun holdCount(eventId: java.util.UUID, status: HoldStatus) =
        jdbc.sql("SELECT COUNT(*) FROM holds WHERE event_id = :id AND status = :s")
            .param("id", eventId).param("s", status.name).query(Int::class.java).single()

    @Test
    fun `200 buyers racing for the last 50 seats - exactly 50 succeed`() {
        val event = newEvent(capacity = 50)
        val results = race((1..200).map { i -> { booking.createHold(event.id, 1, "fan$i@x.io", null) } })

        val won = results.count { it.isSuccess }
        val soldOut = results.count { it.exceptionOrNull() is NotEnoughSeats }
        assertEquals(50, won)
        assertEquals(150, soldOut, "every loser must get a clean sold-out error: ${results.mapNotNull { it.exceptionOrNull() }.filter { it !is NotEnoughSeats }.take(3)}")
        assertEquals(0, available(event.id))
        assertEquals(50, holdCount(event.id, HoldStatus.HELD))
    }

    @RepeatedTest(3)
    fun `random quantities never oversell, and seats held always match inventory`() {
        val event = newEvent(capacity = 137)
        val rnd = Random(42)
        val results = race((1..300).map { i -> val q = rnd.nextInt(1, 6); { booking.createHold(event.id, q, "b$i@x.io", null) } })

        val held = results.mapNotNull { it.getOrNull()?.hold?.quantity }.sum()
        val left = available(event.id)
        assertTrue(left >= 0)
        assertEquals(137, held + left, "seats held + seats available must equal capacity")
        assertTrue(left < 5, "with 300 buyers the event should be (nearly) sold out, but $left seats are left")
    }

    @Test
    fun `customer cancel and expiry sweep racing on the same holds release seats exactly once`() {
        val event = newEvent(capacity = 60)
        val holds = (1..60).map { booking.createHold(event.id, 1, "r$it@x.io", null).hold }
        clock.advance(Duration.ofMinutes(11)) // all overdue

        // For each hold, the customer cancels while two sweepers run concurrently.
        val tasks = holds.flatMap { h -> listOf({ booking.cancel(h.id); 0 }) } +
            List(4) { { booking.expireDueHolds(batchSize = 10) } }
        val results = race(tasks.shuffled(Random(7)))

        assertTrue(results.all { it.isSuccess }, "no task may fail: ${results.mapNotNull { it.exceptionOrNull() }.take(3)}")
        assertEquals(60, available(event.id), "each seat returned once: no leaks, no double-credit")
        assertEquals(60, holdCount(event.id, HoldStatus.RELEASED) + holdCount(event.id, HoldStatus.EXPIRED))
    }

    @Test
    fun `confirm and cancel racing on one hold - one wins, seats stay consistent`() {
        repeat(30) {
            cleanDatabase()
            val event = newEvent(capacity = 5)
            val hold = booking.createHold(event.id, 2, "a@x.io", null).hold

            race(listOf({ booking.confirm(hold.id) }, { booking.cancel(hold.id) }), threads = 2)

            val final = booking.hold(hold.id).status
            val expected = when (final) {
                HoldStatus.CONFIRMED -> 3
                HoldStatus.RELEASED, HoldStatus.CANCELLED -> 5
                else -> error("unexpected final state $final")
            }
            assertEquals(expected, available(event.id), "final state $final")
        }
    }

    @Test
    fun `20 concurrent retries with one idempotency key create exactly one hold`() {
        val event = newEvent(capacity = 10)
        val results = race((1..20).map { { booking.createHold(event.id, 3, "same@x.io", "checkout-7f3a9c") } }, threads = 20)

        assertTrue(results.all { it.isSuccess }, "every retry should succeed: ${results.mapNotNull { it.exceptionOrNull() }.take(3)}")
        assertEquals(1, results.map { it.getOrThrow().hold.id }.toSet().size)
        assertEquals(19, results.count { it.getOrThrow().replayed })
        assertEquals(7, available(event.id))
    }

    @Test
    fun `throughput sanity check`() {
        val event = newEvent(capacity = 100_000)
        val count = AtomicInteger()
        val start = System.nanoTime()
        race((1..2000).map { i -> { booking.createHold(event.id, 1, "t$i@x.io", null); count.incrementAndGet() } })
        val seconds = (System.nanoTime() - start) / 1e9
        println("Created ${count.get()} holds concurrently in ${"%.2f".format(seconds)}s (${(count.get() / seconds).toInt()} holds/s)")
        assertEquals(2000, count.get())
        assertEquals(98_000, available(event.id))
    }
}
