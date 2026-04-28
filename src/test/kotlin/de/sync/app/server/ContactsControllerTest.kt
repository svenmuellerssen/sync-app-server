package de.sync.app.server

import de.sync.app.server.graph.ContactRepository
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
    controllers = [ContactsController::class],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [WebMvcConfig::class, TokenAuthInterceptor::class])
    ]
)
@AutoConfigureMockMvc(addFilters = false)
class ContactsControllerTest : EndpointTestSupport() {

    @MockBean
    lateinit var contactRepository: ContactRepository

    @MockBean
    lateinit var slotService: SlotService

    @Test
    fun `get contacts returns 200`() {
        Mockito.`when`(contactRepository.findAllByAccountNameAndDeletedAtIsNull(TEST_ACCOUNT))
            .thenReturn(emptyList())

        mockMvc.perform(
            authenticated(get("/contacts"))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `get contact count returns 200`() {
        Mockito.`when`(contactRepository.countByAccountNameAndDeletedAtIsNull(TEST_ACCOUNT))
            .thenReturn(0L)

        mockMvc.perform(
            authenticated(get("/contacts/count"))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `upload contacts returns 200`() {
        Mockito.`when`(contactRepository.findAllBySyncIdIn(listOf("sync-1"))).thenReturn(emptyList())
        Mockito.`when`(contactRepository.findAllByAccountNameAndLookupKeyIn(TEST_ACCOUNT, listOf("lk-1")))
            .thenReturn(emptyList())
        Mockito.`when`(contactRepository.save(Mockito.any(de.sync.app.server.graph.ContactNode::class.java)))
            .thenAnswer { it.getArgument(0) }

        val body = ContactBatchRequest(
            contacts = listOf(
                ContactDtoRequest(
                    syncId = "sync-1",
                    lookupKey = "lk-1",
                    lastUpdatedAt = 1L,
                    displayName = "Alice",
                )
            )
        )

        mockMvc.perform(
            authenticated(post("/contacts"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
    }
}
