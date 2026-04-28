package de.sync.app.server

import de.sync.app.server.dto.ManifestRequest
import de.sync.app.server.graph.AppointmentRepository
import de.sync.app.server.graph.ContactRepository
import de.sync.app.server.graph.SharedCalendarRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    controllers = [SyncController::class],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [WebMvcConfig::class, TokenAuthInterceptor::class])
    ]
)
@AutoConfigureMockMvc(addFilters = false)
class SyncControllerTest : EndpointTestSupport() {

    @MockBean
    lateinit var contactRepository: ContactRepository

    @MockBean
    lateinit var appointmentRepository: AppointmentRepository

    @MockBean
    lateinit var sharedCalendarRepository: SharedCalendarRepository

    @MockBean
    lateinit var slotService: SlotService

    @Test
    fun `manifest returns 200`() {
        Mockito.`when`(contactRepository.findAllByAccountNameAndDeletedAtIsNull(TEST_ACCOUNT))
            .thenReturn(emptyList())
        Mockito.`when`(appointmentRepository.findAllCurrentByAccountName(TEST_ACCOUNT))
            .thenReturn(emptyList())
        Mockito.`when`(appointmentRepository.findAllCurrentSharedByAccountName(TEST_ACCOUNT))
            .thenReturn(emptyList())
        Mockito.`when`(sharedCalendarRepository.findAllAccessibleByAccountName(TEST_ACCOUNT))
            .thenReturn(emptyList())

        val body = ManifestRequest(accountName = TEST_ACCOUNT)

        mockMvc.perform(
            authenticated(post("/sync/manifest"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
    }
}
