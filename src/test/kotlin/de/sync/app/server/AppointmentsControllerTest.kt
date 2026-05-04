package de.sync.app.server

import de.sync.app.server.graph.AppointmentRepository
import de.sync.app.server.graph.CalendarNode
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    controllers = [AppointmentsController::class],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [WebMvcConfig::class, TokenAuthInterceptor::class])
    ]
)
@AutoConfigureMockMvc(addFilters = false)
class AppointmentsControllerTest : EndpointTestSupport() {

    @MockBean
    lateinit var appointmentRepository: AppointmentRepository

    @MockBean
    lateinit var appointmentService: AppointmentService

    @Test
    fun `get appointments returns 200`() {
        Mockito.`when`(appointmentRepository.findAllCurrentByAccountName(TEST_ACCOUNT))
            .thenReturn(emptyList())

        mockMvc.perform(
            authenticated(get("/appointments"))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `get appointment count returns 200`() {
        Mockito.`when`(appointmentRepository.countAllCurrentByAccountName(TEST_ACCOUNT))
            .thenReturn(0L)

        mockMvc.perform(
            authenticated(get("/appointments/count"))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `get appointment history returns 200`() {
        Mockito.`when`(appointmentRepository.findAllBySyncIdOrderByVersionCreatedAtDesc("sync-1"))
            .thenReturn(emptyList())

        mockMvc.perform(
            authenticated(get("/appointments/sync-1/history"))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `upload appointments returns 200`() {
        val appointments = listOf(
            AppointmentDtoRequest(
                syncId = "sync-1",
                title = "Standup",
                dtStart = 1L,
                allDay = false,
                timezone = "UTC",
                lastUpdatedAt = 1L,
            )
        )
        Mockito.`when`(appointmentService.processBatch(appointments, TEST_ACCOUNT))
            .thenReturn(AppointmentService.BatchResult(stored = 1, skipped = 0, newCalendars = emptyList()))

        val body = AppointmentBatchRequest(appointments = appointments)

        mockMvc.perform(
            authenticated(post("/appointments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
    }

    // -------------------------------------------------------------------------
    // AD-1: DELETE /appointments/{syncId} → 204 wenn gefunden
    // -------------------------------------------------------------------------

    @Test
    fun `AD-1 delete existing appointment returns 204`() {
        Mockito.`when`(appointmentService.deleteByExplicit("sync-abc", TEST_ACCOUNT))
            .thenReturn(true)

        mockMvc.perform(
            authenticated(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/appointments/sync-abc"))
        )
            .andExpect(status().isNoContent)
    }

    // -------------------------------------------------------------------------
    // AD-2: DELETE /appointments/{syncId} → 404 wenn nicht gefunden
    // -------------------------------------------------------------------------

    @Test
    fun `AD-2 delete non-existent appointment returns 404`() {
        Mockito.`when`(appointmentService.deleteByExplicit("sync-abc", TEST_ACCOUNT))
            .thenReturn(false)

        mockMvc.perform(
            authenticated(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/appointments/sync-abc"))
        )
            .andExpect(status().isNotFound)
    }
}
