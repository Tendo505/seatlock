package io.github.tendo505.seatlock.service

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Periodically returns seats from holds nobody confirmed. Safe to run on
 * every instance at once: each release is a compare-and-set, so two sweepers
 * racing over the same hold return its seats exactly once.
 */
@Component
@ConditionalOnProperty("seatlock.expiry-job.enabled", havingValue = "true", matchIfMissing = true)
class HoldExpiryJob(private val booking: BookingService) {

    @Scheduled(fixedDelayString = "\${seatlock.expiry-sweep-interval}")
    fun sweep() {
        booking.expireDueHolds()
    }
}
