package io.github.tendo505.seatlock

import io.github.tendo505.seatlock.service.BookingService
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock tests can move forward, so expiry is tested without sleeping. */
class MutableClock(private var now: Instant = Instant.parse("2026-11-01T10:00:00Z")) : Clock() {
    @Synchronized fun advance(d: Duration) { now = now.plus(d) }
    @Synchronized override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

@TestConfiguration
class TestClockConfig {
    @Bean @Primary
    fun mutableClock() = MutableClock()
}

/**
 * Runs against the datasource in application.yml: in-memory H2 locally, and
 * real PostgreSQL in CI (via SPRING_DATASOURCE_URL). The expiry job is
 * disabled so tests control exactly when holds expire.
 */
@SpringBootTest(properties = ["seatlock.expiry-job.enabled=false", "seatlock.hold-ttl=10m"])
@Import(TestClockConfig::class)
abstract class IntegrationTest {
    @Autowired lateinit var booking: BookingService
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("DELETE FROM idempotency_keys").update()
        jdbc.sql("DELETE FROM holds").update()
        jdbc.sql("DELETE FROM events").update()
    }

    fun newEvent(capacity: Int = 100) =
        booking.createEvent("Coldplay: Music of the Spheres", "Bukit Jalil Stadium", clock.instant().plus(Duration.ofDays(30)), capacity)

    fun available(eventId: java.util.UUID) = booking.event(eventId).available
}
