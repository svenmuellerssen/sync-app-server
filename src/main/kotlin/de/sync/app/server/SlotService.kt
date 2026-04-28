package de.sync.app.server

import de.sync.app.server.graph.AppointmentNode
import de.sync.app.server.graph.AppointmentRepository
import de.sync.app.server.graph.BookingNode
import de.sync.app.server.graph.BookingRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ScanOptions
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

@Service
class SlotService(
    private val appointmentRepository: AppointmentRepository,
    private val bookingRepository: BookingRepository,
    @Qualifier("jsonRedisTemplate")
    private val jsonRedisTemplate: RedisTemplate<String, Any>,
) {

    fun findAvailableSlots(accountName: String, fromIso: String, toIso: String, durationMinutes: Long): List<TimeSlot> {
        require(durationMinutes in 1L..480) { "duration must be between 1 and 480 minutes (8h)" }

        val originalFrom = parseIso(fromIso)
        val originalTo   = parseIso(toIso)
        require(originalTo > originalFrom) { "to must be later than from" }

        // If the entire range lies in the past, shift it forward so that from = now,
        // preserving the original duration. This avoids returning stale slot windows
        // when a client sends an outdated or test range.
        val now = System.currentTimeMillis()
        val (from, to) = when {
            originalTo <= now -> {
                // Gesamte Range in der Vergangenheit → auf jetzt verschieben, Dauer erhalten
                val rangeMs = originalTo - originalFrom
                Pair<Long, Long>(now, now + rangeMs)
            }
            originalFrom < now -> {
                // Start in der Vergangenheit, Ende in der Zukunft → Start auf jetzt klemmen
                Pair<Long, Long>(now, originalTo)
            }
            else -> Pair<Long, Long>(originalFrom, originalTo)
        }

        val busyIntervals = collectBusyIntervals(accountName, from, to)
        val durationMs = TimeUnit.MINUTES.toMillis(durationMinutes)
        val slots = mutableListOf<TimeSlot>()

        var cursor = ceilToQuarterHour(from)
        for (busy in busyIntervals) {
            if (busy.end <= from || busy.start >= to) continue

            val slotEnd = floorToQuarterHour(min(busy.start, to))
            if (slotEnd - cursor >= durationMs) {
                slots.add(TimeSlot(start = toIso(cursor), end = toIso(slotEnd)))
            }
            cursor = max(cursor, ceilToQuarterHour(max(busy.end, from)))
        }

        val finalEnd = floorToQuarterHour(to)
        if (finalEnd - cursor >= durationMs) {
            slots.add(TimeSlot(start = toIso(cursor), end = toIso(finalEnd)))
        }

        return slots
    }

    fun invalidateRange(accountName: String, startTime: Long, endTime: Long) {
        for (day in daysBetween(startTime, max(startTime, endTime))) {
            jsonRedisTemplate.delete(cacheKey(accountName, day))
        }
    }

    fun invalidateAccount(accountName: String) {
        val pattern = "slots:$accountName:*"
        val keysToDelete = mutableListOf<ByteArray>()
        jsonRedisTemplate.execute<Void?> { connection ->
            val scanOptions = ScanOptions.scanOptions().match(pattern).count(100).build()
            connection.scan(scanOptions).use { cursor ->
                while (cursor.hasNext()) keysToDelete.add(cursor.next())
            }
            null
        }
        if (keysToDelete.isNotEmpty()) {
            jsonRedisTemplate.execute<Void?> { connection ->
                connection.del(*keysToDelete.toTypedArray())
                null
            }
        }
    }

    private fun collectBusyIntervals(accountName: String, from: Long, to: Long): List<BusyInterval> {
        val intervals = daysBetween(from, to).flatMap { day ->
            getOrBuildDayBucket(accountName, day)
                .mapNotNull { interval ->
                    val start = max(interval.start, from)
                    val end = min(interval.end, to)
                    interval.takeIf { end > start }?.copy(start = start, end = end)
                }
        }
        return merge(intervals)
    }

    private fun getOrBuildDayBucket(accountName: String, day: LocalDate): List<BusyInterval> {
        val endOfDay = day.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()

        // Days entirely in the past can never produce future booking slots — skip cache and DB query.
        if (endOfDay <= System.currentTimeMillis()) return emptyList()

        val key = cacheKey(accountName, day)
        decodeBusyIntervals(jsonRedisTemplate.opsForValue().get(key))?.let { return it }

        val startOfDay = day.atStartOfDay(ZONE).toInstant().toEpochMilli()

        // Extend query range by PADDING_MS so appointments just outside the day boundary
        // that would overlap into this day (after padding) are included.
        val queryFrom = startOfDay - PADDING_MS
        val queryTo = endOfDay + PADDING_MS

        val appointments = appointmentRepository.findAllOverlappingRange(accountName, queryFrom, queryTo).mapNotNull {
            busyIntervalFor(day, it.startTime(), it.endTime())
        }
        val bookings = bookingRepository.findAllOverlappingRange(accountName, queryFrom, queryTo).mapNotNull {
            busyIntervalFor(day, it.startTime, it.endTime)
        }

        val merged = merge(appointments + bookings)
        jsonRedisTemplate.opsForValue().set(key, merged, Duration.ofMinutes(15))
        return merged
    }

    private fun busyIntervalFor(day: LocalDate, start: Long, end: Long): BusyInterval? {
        val paddedStart = start - PADDING_MS
        val paddedEnd = end + PADDING_MS
        val dayStart = day.atStartOfDay(ZONE).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
        val boundedStart = max(dayStart, paddedStart)
        val boundedEnd = min(dayEnd, paddedEnd)
        return if (boundedEnd > boundedStart) BusyInterval(boundedStart, boundedEnd) else null
    }

    private fun decodeBusyIntervals(value: Any?): List<BusyInterval>? {
        val list = value as? List<*> ?: return null
        return list.mapNotNull(::toBusyInterval)
    }

    private fun toBusyInterval(value: Any?): BusyInterval? {
        return when (value) {
            is BusyInterval -> value
            is Map<*, *> -> {
                val start = (value["start"] as? Number)?.toLong() ?: return null
                val end = (value["end"] as? Number)?.toLong() ?: return null
                BusyInterval(start = start, end = end)
            }

            else -> null
        }
    }

    private fun merge(intervals: List<BusyInterval>): List<BusyInterval> {
        if (intervals.isEmpty()) return emptyList()

        val sorted = intervals.sortedBy { it.start }
        val merged = mutableListOf(sorted.first())
        for (current in sorted.drop(1)) {
            val previous = merged.last()
            if (current.start <= previous.end) {
                merged[merged.lastIndex] = previous.copy(end = max(previous.end, current.end))
            } else {
                merged.add(current)
            }
        }
        return merged
    }

    private fun parseIso(value: String): Long = OffsetDateTime.parse(value).toInstant().toEpochMilli()

    private fun toIso(value: Long): String = Instant.ofEpochMilli(value).toString()

    private fun daysBetween(from: Long, to: Long): List<LocalDate> {
        val startDay = Instant.ofEpochMilli(from).atZone(ZONE).toLocalDate()
        val endExclusive = max(from + 1, to)
        val endDay = Instant.ofEpochMilli(endExclusive - 1).atZone(ZONE).toLocalDate()
        return generateSequence(startDay) { current ->
            current.plusDays(1).takeIf { !it.isAfter(endDay) }
        }.toList()
    }

    private fun floorToQuarterHour(value: Long): Long =
        Instant.ofEpochMilli(value)
            .truncatedTo(ChronoUnit.MINUTES)
            .let { instant ->
                val minute = instant.atZone(ZONE).minute
                instant.minus((minute % 15).toLong(), ChronoUnit.MINUTES).toEpochMilli()
            }

    private fun ceilToQuarterHour(value: Long): Long {
        val floored = floorToQuarterHour(value)
        return if (floored == value) value else floored + QUARTER_HOUR_MS
    }

    private fun AppointmentNode.startTime(): Long = dtStart

    private fun AppointmentNode.endTime(): Long = dtEnd ?: duration?.let {
        runCatching { dtStart + Duration.parse(it).toMillis() }.getOrNull()
    } ?: (dtStart + QUARTER_HOUR_MS)

    private fun cacheKey(accountName: String, day: LocalDate): String = "slots:$accountName:${ZONE.id}:$day"

    private companion object {
        private val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        private val PADDING_MS = TimeUnit.MINUTES.toMillis(5)
        private val QUARTER_HOUR_MS = TimeUnit.MINUTES.toMillis(15)
    }
}

data class TimeSlot(
    val start: String,
    val end: String,
)

private data class BusyInterval(
    val start: Long,
    val end: Long,
)
