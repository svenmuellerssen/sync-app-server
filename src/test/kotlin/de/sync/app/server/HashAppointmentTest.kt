package de.sync.app.server

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HashAppointmentTest {

    // Minimal valid DTO — used as the baseline for all tests
    private fun baseDto() = AppointmentDtoRequest(
        syncId = "sync-1",
        title = "Standup",
        dtStart = 1_000_000L,
        dtEnd = 1_003_600L,
        allDay = false,
        timezone = "Europe/Berlin",
        lastUpdatedAt = 9_999_999L,
    )

    // HA1: gleiche DTO zweimal → selber Hash
    @Test
    fun `same dto produces same hash`() {
        val hash1 = hashAppointment(baseDto())
        val hash2 = hashAppointment(baseDto())

        assertThat(hash1).isEqualTo(hash2)
    }

    // HA2: nur lastUpdatedAt verschieden → Hash identisch (bewusst ausgeschlossen)
    @Test
    fun `different lastUpdatedAt produces same hash`() {
        val hash1 = hashAppointment(baseDto())
        val hash2 = hashAppointment(baseDto().copy(lastUpdatedAt = 1L))

        assertThat(hash1).isEqualTo(hash2)
    }

    // HA3: attendees in anderer Reihenfolge → Hash identisch (sortiert nach email+name)
    @Test
    fun `attendees in different order produce same hash`() {
        val a1 = AttendeeDto(name = "Alice", email = "alice@example.com")
        val a2 = AttendeeDto(name = "Bob", email = "bob@example.com")

        val hash1 = hashAppointment(baseDto().copy(attendees = listOf(a1, a2)))
        val hash2 = hashAppointment(baseDto().copy(attendees = listOf(a2, a1)))

        assertThat(hash1).isEqualTo(hash2)
    }

    // HA4: reminders in anderer Reihenfolge → Hash identisch (sortiert nach minutes)
    @Test
    fun `reminders in different order produce same hash`() {
        val r10 = ReminderDto(minutes = 10, method = 1)
        val r30 = ReminderDto(minutes = 30, method = 1)

        val hash1 = hashAppointment(baseDto().copy(reminders = listOf(r10, r30)))
        val hash2 = hashAppointment(baseDto().copy(reminders = listOf(r30, r10)))

        assertThat(hash1).isEqualTo(hash2)
    }

    // HA5: title geändert → anderer Hash
    @Test
    fun `changed title produces different hash`() {
        val hash1 = hashAppointment(baseDto())
        val hash2 = hashAppointment(baseDto().copy(title = "Changed Title"))

        assertThat(hash1).isNotEqualTo(hash2)
    }

    // HA6: leere attendees-Liste vs. keine attendees → Hash identisch
    @Test
    fun `empty attendees list and default attendees produce same hash`() {
        val hash1 = hashAppointment(baseDto().copy(attendees = emptyList()))
        val hash2 = hashAppointment(baseDto())   // default ist emptyList()

        assertThat(hash1).isEqualTo(hash2)
    }
}
