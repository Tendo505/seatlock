package io.github.tendo505.seatlock

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock
import java.time.Duration

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
class SeatlockApplication {
    /** Injected everywhere time matters, so tests can move time forward deterministically. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

@ConfigurationProperties("seatlock")
data class SeatlockProperties(
    val holdTtl: Duration = Duration.ofMinutes(10),
    val maxSeatsPerHold: Int = 10,
    val adminKey: String,
    val expirySweepInterval: Duration = Duration.ofSeconds(15),
)

fun main(args: Array<String>) {
    runApplication<SeatlockApplication>(*args)
}
