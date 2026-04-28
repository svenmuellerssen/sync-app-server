package de.sync.app.server

import de.sync.app.server.graph.AppointmentRepository
import de.sync.app.server.graph.BookingRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@ExtendWith(MockitoExtension::class)
class SlotServiceTest {

    @Mock
    lateinit var appointmentRepository: AppointmentRepository

    @Mock
    lateinit var bookingRepository: BookingRepository

    @Mock(name = "jsonRedisTemplate")
    lateinit var redisTemplate: RedisTemplate<String, Any>

    private lateinit var slotService: SlotService

    @BeforeEach
    fun setUp() {
        val valueOps = mock<ValueOperations<String, Any>>()
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        whenever(appointmentRepository.findAllOverlappingRange(any(), any(), any())).thenReturn(emptyList())
        whenever(bookingRepository.findAllOverlappingRange(any(), any(), any())).thenReturn(emptyList())
        slotService = SlotService(appointmentRepository, bookingRepository, redisTemplate)
    }

    @Test
    fun `fully past range is shifted to now preserving duration`() {
        val before = System.currentTimeMillis()
        val result = slotService.findAvailableSlots("user", "2020-01-01T09:00:00Z", "2020-01-01T10:00:00Z", 15)
        val after = System.currentTimeMillis()

        assertThat(result).isNotEmpty
        val firstStart = Instant.parse(result.first().start).toEpochMilli()
        // Start must be near now, not in 2020
        assertThat(firstStart).isBetween(before - 1, after + QUARTER_HOUR_MS)
        // End must be near now + 1 hour (original duration)
        val lastEnd = Instant.parse(result.last().end).toEpochMilli()
        assertThat(lastEnd).isBetween(
            before + TimeUnit.HOURS.toMillis(1) - QUARTER_HOUR_MS,
            after  + TimeUnit.HOURS.toMillis(1) + QUARTER_HOUR_MS,
        )
    }

    @Test
    fun `partially past range clamps from to now, keeping original to`() {
        val futureTo = Instant.now().plus(2, ChronoUnit.HOURS)
        val futureToIso = futureTo.toString()

        val before = System.currentTimeMillis()
        val result = slotService.findAvailableSlots("user", "2020-01-01T09:00:00Z", futureToIso, 15)
        val after = System.currentTimeMillis()

        assertThat(result).isNotEmpty
        val firstStart = Instant.parse(result.first().start).toEpochMilli()
        // Start must be near now (clamped), not in 2020
        assertThat(firstStart).isBetween(before - 1, after + QUARTER_HOUR_MS)
        // End must be near futureTo (unchanged, only floored to quarter hour)
        val lastEnd = Instant.parse(result.last().end).toEpochMilli()
        assertThat(lastEnd).isBetween(futureTo.toEpochMilli() - QUARTER_HOUR_MS, futureTo.toEpochMilli())
    }

    @Test
    fun `future range is used unchanged`() {
        val futureFrom = Instant.now().plus(1, ChronoUnit.HOURS)
        val futureTo   = Instant.now().plus(2, ChronoUnit.HOURS)

        val result = slotService.findAvailableSlots("user", futureFrom.toString(), futureTo.toString(), 15)

        assertThat(result).isNotEmpty
        val firstStart = Instant.parse(result.first().start).toEpochMilli()
        // Start must be within [futureFrom, futureFrom + 15min] (quarter-hour ceiling)
        assertThat(firstStart).isBetween(futureFrom.toEpochMilli(), futureFrom.toEpochMilli() + QUARTER_HOUR_MS)
    }

    private companion object {
        private val QUARTER_HOUR_MS = TimeUnit.MINUTES.toMillis(15)
    }
}
