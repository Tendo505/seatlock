package io.github.tendo505.seatlock

import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration

@AutoConfigureMockMvc
class ApiTest : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    private val future get() = clock.instant().plus(Duration.ofDays(10)).toString()

    private fun createEvent(capacity: Int = 5): String {
        val body = mvc.post("/api/events") {
            header("X-Admin-Key", "dev-admin-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Jazz Night","venue":"No Black Tie","startsAt":"$future","capacity":$capacity}"""
        }.andExpect {
            status { isCreated() }
            header { exists("Location") }
        }.andReturn().response.contentAsString
        return json.readTree(body)["id"].asText()
    }

    private fun hold(eventId: String, quantity: Int, key: String? = null): ResultActionsDsl =
        mvc.post("/api/events/$eventId/holds") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":$quantity,"email":"fan@example.com"}"""
            if (key != null) header("Idempotency-Key", key)
        }

    @Test
    fun `full booking flow over HTTP`() {
        val eventId = createEvent(capacity = 5)

        val holdBody = hold(eventId, 2).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("HELD") }
            jsonPath("$.quantity") { value(2) }
            header { exists("Location") }
        }.andReturn().response.contentAsString
        val holdId = json.readTree(holdBody)["id"].asText()

        mvc.get("/api/events/$eventId").andExpect {
            jsonPath("$.available") { value(3) }
            jsonPath("$.soldOut") { value(false) }
        }
        mvc.post("/api/holds/$holdId/confirm").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CONFIRMED") }
        }
        mvc.delete("/api/holds/$holdId").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CANCELLED") }
        }
        mvc.get("/api/events/$eventId").andExpect { jsonPath("$.available") { value(5) } }
    }

    @Test
    fun `creating events requires the admin key`() {
        mvc.post("/api/events") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"x","venue":"y","startsAt":"$future","capacity":1}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("Missing or invalid X-Admin-Key header") }
        }
        mvc.post("/api/events") {
            header("X-Admin-Key", "wrong")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"x","venue":"y","startsAt":"$future","capacity":1}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `validation errors are reported per field`() {
        mvc.post("/api/events") {
            header("X-Admin-Key", "dev-admin-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"","venue":"Hall","startsAt":"2001-01-01T00:00:00Z","capacity":0}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors.name") { exists() }
            jsonPath("$.errors.startsAt") { value("must be a future date") }
            jsonPath("$.errors.capacity") { exists() }
        }

        val eventId = createEvent()
        mvc.post("/api/events/$eventId/holds") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":1,"email":"not-an-email"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors.email") { exists() }
        }
    }

    @Test
    fun `sold out is a 409 problem with machine-readable details`() {
        val eventId = createEvent(capacity = 3)
        hold(eventId, 3).andExpect { status { isCreated() } }
        hold(eventId, 1).andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("https://github.com/Tendo505/seatlock#not-enough-seats") }
            jsonPath("$.requested") { value(1) }
            jsonPath("$.available") { value(0) }
        }
        mvc.get("/api/events/$eventId").andExpect { jsonPath("$.soldOut") { value(true) } }
    }

    @Test
    fun `idempotent retries return 200 with a replay header`() {
        val eventId = createEvent(capacity = 5)
        val first = hold(eventId, 2, key = "retry-key-0001").andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        hold(eventId, 2, key = "retry-key-0001").andExpect {
            status { isOk() }
            header { string("Idempotent-Replayed", "true") }
            jsonPath("$.id") { value(json.readTree(first)["id"].asText()) }
        }
        hold(eventId, 3, key = "retry-key-0001").andExpect { status { isUnprocessableEntity() } }
        hold(eventId, 1, key = "bad key!").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `unknown ids are 404s and malformed ids are 400s`() {
        mvc.get("/api/events/00000000-0000-0000-0000-000000000000").andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("https://github.com/Tendo505/seatlock#not-found") }
        }
        mvc.get("/api/holds/not-a-uuid").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `listing events and the OpenAPI document`() {
        createEvent()
        mvc.get("/api/events?upcoming=true").andExpect { jsonPath("$[*].name") { value(hasItem("Jazz Night")) } }
        mvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/api/events/{id}/holds'].post") { exists() }
        }
        mvc.get("/actuator/health").andExpect { jsonPath("$.status") { value("UP") } }
    }
}
