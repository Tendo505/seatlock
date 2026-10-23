package io.github.tendo505.seatlock.repo

import io.github.tendo505.seatlock.domain.Event
import io.github.tendo505.seatlock.domain.Hold
import io.github.tendo505.seatlock.domain.HoldStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// Plain SQL through JdbcClient rather than an ORM: the correctness of this
// service rests on a handful of exact statements, and they should be visible.

private fun Instant.toDb(): OffsetDateTime = atOffset(ZoneOffset.UTC)
private fun ResultSet.instant(col: String): Instant = getObject(col, OffsetDateTime::class.java).toInstant()
private fun ResultSet.uuid(col: String): UUID = getObject(col, UUID::class.java)

@Repository
class EventRepository(private val jdbc: JdbcClient) {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = Event(
        id = rs.uuid("id"),
        name = rs.getString("name"),
        venue = rs.getString("venue"),
        startsAt = rs.instant("starts_at"),
        capacity = rs.getInt("capacity"),
        available = rs.getInt("available"),
        createdAt = rs.instant("created_at"),
    )

    fun insert(e: Event) {
        jdbc.sql(
            """
            INSERT INTO events (id, name, venue, starts_at, capacity, available, created_at)
            VALUES (:id, :name, :venue, :startsAt, :capacity, :available, :createdAt)
            """,
        )
            .param("id", e.id).param("name", e.name).param("venue", e.venue)
            .param("startsAt", e.startsAt.toDb()).param("capacity", e.capacity)
            .param("available", e.available).param("createdAt", e.createdAt.toDb())
            .update()
    }

    fun find(id: UUID): Event? =
        jdbc.sql("SELECT * FROM events WHERE id = :id").param("id", id).query(::map).optional().orElse(null)

    fun list(from: Instant?): List<Event> =
        if (from == null) {
            jdbc.sql("SELECT * FROM events ORDER BY starts_at").query(::map).list()
        } else {
            jdbc.sql("SELECT * FROM events WHERE starts_at >= :from ORDER BY starts_at")
                .param("from", from.toDb()).query(::map).list()
        }

    /**
     * Atomically take seats. The WHERE clause makes check-and-decrement a
     * single statement: the row lock the UPDATE takes serialises concurrent
     * buyers, and each one re-evaluates `available >= :qty` against the
     * committed value. Returns false (0 rows) when there aren't enough seats.
     */
    fun tryReserve(eventId: UUID, quantity: Int): Boolean =
        jdbc.sql("UPDATE events SET available = available - :qty WHERE id = :id AND available >= :qty")
            .param("qty", quantity).param("id", eventId)
            .update() == 1

    fun returnSeats(eventId: UUID, quantity: Int) {
        val rows = jdbc.sql("UPDATE events SET available = available + :qty WHERE id = :id")
            .param("qty", quantity).param("id", eventId)
            .update()
        check(rows == 1) { "Event $eventId vanished while returning seats" }
    }
}

@Repository
class HoldRepository(private val jdbc: JdbcClient) {

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = Hold(
        id = rs.uuid("id"),
        eventId = rs.uuid("event_id"),
        quantity = rs.getInt("quantity"),
        customerEmail = rs.getString("customer_email"),
        status = HoldStatus.valueOf(rs.getString("status")),
        expiresAt = rs.instant("expires_at"),
        createdAt = rs.instant("created_at"),
        updatedAt = rs.instant("updated_at"),
    )

    fun insert(h: Hold) {
        jdbc.sql(
            """
            INSERT INTO holds (id, event_id, quantity, customer_email, status, expires_at, created_at, updated_at)
            VALUES (:id, :eventId, :quantity, :email, :status, :expiresAt, :createdAt, :updatedAt)
            """,
        )
            .param("id", h.id).param("eventId", h.eventId).param("quantity", h.quantity)
            .param("email", h.customerEmail).param("status", h.status.name)
            .param("expiresAt", h.expiresAt.toDb()).param("createdAt", h.createdAt.toDb())
            .param("updatedAt", h.updatedAt.toDb())
            .update()
    }

    fun find(id: UUID): Hold? =
        jdbc.sql("SELECT * FROM holds WHERE id = :id").param("id", id).query(::map).optional().orElse(null)

    /**
     * Compare-and-set on status. Exactly one caller can move a hold out of a
     * given state, even if the customer and the expiry job race each other;
     * whoever gets 1 row back owns the side effects (such as returning seats).
     */
    fun transition(id: UUID, from: HoldStatus, to: HoldStatus, now: Instant, notExpiredAt: Instant? = null): Boolean {
        val sql = buildString {
            append("UPDATE holds SET status = :to, updated_at = :now WHERE id = :id AND status = :from")
            if (notExpiredAt != null) append(" AND expires_at > :at")
        }
        var spec = jdbc.sql(sql).param("to", to.name).param("now", now.toDb()).param("id", id).param("from", from.name)
        if (notExpiredAt != null) spec = spec.param("at", notExpiredAt.toDb())
        return spec.update() == 1
    }

    fun findExpired(now: Instant, limit: Int): List<UUID> =
        jdbc.sql("SELECT id FROM holds WHERE status = 'HELD' AND expires_at <= :now ORDER BY expires_at LIMIT :limit")
            .param("now", now.toDb()).param("limit", limit)
            .query { rs, _ -> rs.uuid("id") }
            .list()
}

@Repository
class IdempotencyRepository(private val jdbc: JdbcClient) {
    data class Record(val key: String, val requestHash: String, val holdId: UUID?)

    /** Throws DuplicateKeyException if the key exists (or is being inserted by a concurrent transaction that commits). */
    fun insert(key: String, requestHash: String, now: Instant) {
        jdbc.sql("INSERT INTO idempotency_keys (idem_key, request_hash, created_at) VALUES (:k, :h, :now)")
            .param("k", key).param("h", requestHash).param("now", now.toDb())
            .update()
    }

    fun attachHold(key: String, holdId: UUID) {
        jdbc.sql("UPDATE idempotency_keys SET hold_id = :hold WHERE idem_key = :k").param("hold", holdId).param("k", key).update()
    }

    fun find(key: String): Record? =
        jdbc.sql("SELECT idem_key, request_hash, hold_id FROM idempotency_keys WHERE idem_key = :k")
            .param("k", key)
            .query { rs, _ -> Record(rs.getString("idem_key"), rs.getString("request_hash"), rs.getObject("hold_id", UUID::class.java)) }
            .optional().orElse(null)
}
