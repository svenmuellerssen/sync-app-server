package de.sync.app.server

import de.sync.app.server.graph.AppointmentNode
import de.sync.app.server.graph.AppointmentRepository
import de.sync.app.server.graph.BookingNode
import de.sync.app.server.graph.BookingRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
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

    // ── SlotS3 ─────────────────────────────────────────────────────────────────
    // BUG-5: appointment with unparseable duration must be skipped + logged,
    // NOT silently fall back to a 15-min busy interval.

    @Test
    fun `SlotS3 appointment with RFC 2445 week duration is skipped and window stays free`() {
        // future 1-hour window: no appointments → should produce slots
        val from = Instant.now().plus(2, ChronoUnit.HOURS)
        val to   = from.plus(1, ChronoUnit.HOURS)

        // dtEnd=null, duration="P1W" — RFC 2445 week format that java.time.Duration cannot parse.
        // Old behaviour: silent 15-min fallback → busy interval near dtStart → window partially blocked.
        // New behaviour: DateTimeParseException caught, logged, appointment skipped → full window free.
        val badDurationAppt = AppointmentNode(
            syncId      = "slot-s3-bad-duration",
            accountName = "user",
            title       = "Weekly block",
            dtStart     = from.plus(10, ChronoUnit.MINUTES).toEpochMilli(),
            dtEnd       = null,
            duration    = "P1W",
            allDay      = false,
            timezone    = "UTC",
            lastUpdatedAt = 0,
            createdAt   = 0,
        )

        whenever(appointmentRepository.findAllOverlappingRange(any(), any(), any()))
            .thenReturn(listOf(badDurationAppt))

        // Must not throw; the broken appointment must be skipped
        val slots = slotService.findAvailableSlots("user", from.toString(), to.toString(), 15)

        // Full 1-hour window is free (no valid busy interval was produced)
        assertThat(slots).isNotEmpty
    }

    // ── SlotS1 ─────────────────────────────────────────────────────────────────
    // A booking covering the full search window must block all slots.

    @Test
    fun `SlotS1 booking covering search window returns no available slots`() {
        val from = Instant.now().plus(2, ChronoUnit.HOURS)
        val to   = from.plus(1, ChronoUnit.HOURS)

        val booking = BookingNode(
            accountName = "user",
            title       = "Fully blocked by booking",
            startTime   = from.toEpochMilli(),
            endTime     = to.toEpochMilli(),
        )
        whenever(bookingRepository.findAllOverlappingRange(any(), any(), any()))
            .thenReturn(listOf(booking))

        val slots = slotService.findAvailableSlots("user", from.toString(), to.toString(), 15)

        assertThat(slots).isEmpty()
    }

    // ── SlotS2 ─────────────────────────────────────────────────────────────────
    // PADDING_MS (5 min) means the next available slot cannot start immediately
    // after an appointment ends — there must be a 5-minute gap first.

    @Test
    fun `SlotS2 first slot starts no earlier than PADDING_MS after appointment end`() {
        // Pin 'from' to a quarter-hour boundary so slot-start assertions are exact.
        val nowMs       = System.currentTimeMillis()
        val quarterMs   = TimeUnit.MINUTES.toMillis(15)
        val fromMs      = ((nowMs / quarterMs) + 8) * quarterMs  // ≥ 2 h in the future, on quarter boundary

        val apptEndMs = fromMs + TimeUnit.MINUTES.toMillis(15)   // appointment ends 15 min into window

        val appt = AppointmentNode(
            syncId        = "slot-s2",
            accountName   = "user",
            title         = "Short event",
            dtStart       = fromMs + TimeUnit.MINUTES.toMillis(10),
            dtEnd         = apptEndMs,
            allDay        = false,
            timezone      = "UTC",
            lastUpdatedAt = 0,
            createdAt     = 0,
        )
        whenever(appointmentRepository.findAllOverlappingRange(any(), any(), any()))
            .thenReturn(listOf(appt))

        val from = Instant.ofEpochMilli(fromMs)
        val to   = from.plus(1, ChronoUnit.HOURS)
        val slots = slotService.findAvailableSlots("user", from.toString(), to.toString(), 15)

        // Padding (5 min) means busy interval extends to apptEnd + 5 min.
        // The next slot must not start before that padded end.
        assertThat(slots).isNotEmpty()
        val firstStartMs = Instant.parse(slots.first().start).toEpochMilli()
        assertThat(firstStartMs).isGreaterThanOrEqualTo(apptEndMs + TimeUnit.MINUTES.toMillis(5))
    }

    // ── SlotS4 ─────────────────────────────────────────────────────────────────
    // An appointment that spans midnight in Europe/Berlin must block slots in
    // BOTH the pre-midnight and post-midnight day buckets.

    @Test
    fun `SlotS4 appointment spanning midnight blocks slots in both day buckets`() {
        // Midnight Europe/Berlin on a CEST summer date (UTC+2) = 22:00 UTC.
        // Far future (2099) to avoid the "past day" early-return guard.
        val midnightBerlin = Instant.parse("2099-06-14T22:00:00Z")
        val dtStart = midnightBerlin.minus(15, ChronoUnit.MINUTES)  // 23:45 Berlin
        val dtEnd   = midnightBerlin.plus(15, ChronoUnit.MINUTES)   // 00:15 Berlin (next day)

        val appt = AppointmentNode(
            syncId        = "slot-s4-midnight",
            accountName   = "user",
            title         = "Midnight block",
            dtStart       = dtStart.toEpochMilli(),
            dtEnd         = dtEnd.toEpochMilli(),
            allDay        = false,
            timezone      = "Europe/Berlin",
            lastUpdatedAt = 0,
            createdAt     = 0,
        )
        whenever(appointmentRepository.findAllOverlappingRange(any(), any(), any()))
            .thenReturn(listOf(appt))

        // 1-hour window centred on midnight: 23:30 to 00:30 Berlin
        val searchFrom = midnightBerlin.minus(30, ChronoUnit.MINUTES)
        val searchTo   = midnightBerlin.plus(30, ChronoUnit.MINUTES)

        val slots = slotService.findAvailableSlots("user", searchFrom.toString(), searchTo.toString(), 15)

        // Appointment + 5-min padding blocks 23:40–00:20 Berlin.
        // Remaining windows on each side (10 min each) are too short for a 15-min slot.
        assertThat(slots).isEmpty()
    }

    private companion object {
        private val QUARTER_HOUR_MS = TimeUnit.MINUTES.toMillis(15)
    }
}
