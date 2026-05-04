package de.sync.app.server

import de.sync.app.server.graph.AccountNode
import de.sync.app.server.graph.AccountRepository
import de.sync.app.server.graph.AppointmentRepository
import de.sync.app.server.graph.BookingMessageNode
import de.sync.app.server.graph.BookingNode
import de.sync.app.server.graph.BookingRepository
import de.sync.app.server.graph.ContactNode
import de.sync.app.server.graph.ContactRepository
import de.sync.app.server.graph.SharedCalendarNode
import de.sync.app.server.graph.SharedCalendarRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Optional

@WebMvcTest(
    controllers = [BookingController::class],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [WebMvcConfig::class, TokenAuthInterceptor::class])
    ]
)
@AutoConfigureMockMvc(addFilters = false)
@Import(SlotService::class)
class BookingControllerTest : EndpointTestSupport() {

    @MockBean
    lateinit var bookingRepository: BookingRepository

    @MockBean
    lateinit var contactRepository: ContactRepository

    @MockBean
    lateinit var sharedCalendarRepository: SharedCalendarRepository

    @MockBean
    lateinit var accountRepository: AccountRepository

    @MockBean
    lateinit var appointmentRepository: AppointmentRepository

    @MockBean(name = "jsonRedisTemplate")
    lateinit var redisTemplate: RedisTemplate<String, Any>

    @Test
    fun `list bookings returns 200`() {
        Mockito.`when`(bookingRepository.findAllVisibleIdsByAccountName(TEST_ACCOUNT)).thenReturn(emptyList())

        mockMvc.perform(authenticated(get("/booking")))
            .andExpect(status().isOk)
    }

    @Test
    fun `get booking returns 200`() {
        Mockito.`when`(bookingRepository.findById(1L)).thenReturn(Optional.of(activeBooking()))

        mockMvc.perform(authenticated(get("/booking/1")))
            .andExpect(status().isOk)
    }

    @Test
    fun `create booking returns 201`() {
        val sharedCalendar = sharedCalendar("cal-1")
        val owner = AccountNode(username = TEST_ACCOUNT, passwordHash = "hash")
        Mockito.`when`(sharedCalendarRepository.findByCalendarIdAndDeletedAtIsNull("cal-1")).thenReturn(sharedCalendar)
        Mockito.`when`(accountRepository.findByUsername(TEST_ACCOUNT)).thenReturn(owner)
        Mockito.`when`(bookingRepository.findAllOverlappingRange(TEST_ACCOUNT, 10L, 20L)).thenReturn(emptyList())
        Mockito.`when`(sharedCalendarRepository.findMemberUsernamesByCalendarIds(listOf("cal-1")))
            .thenReturn(listOf(TEST_ACCOUNT))
        Mockito.doAnswer { invocation ->
            val original = invocation.getArgument<BookingNode>(0)
            BookingNode(
                id = 1L,
                bookingId = "bk-1",
                accountName = original.accountName,
                title = original.title,
                description = original.description,
                startTime = original.startTime,
                endTime = original.endTime,
                locationName = original.locationName,
                createdAt = original.createdAt,
                updatedAt = original.updatedAt,
                cancelledAt = original.cancelledAt,
                invitees = original.invitees,
                messages = original.messages,
                sharedCalendar = original.sharedCalendar,
                creator = original.creator,
            )
        }.`when`(bookingRepository).save(Mockito.any(BookingNode::class.java))

        val body = CreateBookingRequest(
            title = "Planning",
            description = "Sprint planning",
            startTime = 10L,
            endTime = 20L,
            locationName = "Room 1",
            sharedCalendarId = "cal-1",
        )

        mockMvc.perform(
            authenticated(post("/booking"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isCreated)
    }

    @Test
    fun `update booking returns 200`() {
        val existing = activeBooking()
        val newCalendar = sharedCalendar("cal-2")
        Mockito.`when`(bookingRepository.findById(1L)).thenReturn(Optional.of(existing))
        Mockito.`when`(sharedCalendarRepository.findByCalendarIdAndDeletedAtIsNull("cal-2")).thenReturn(newCalendar)
        Mockito.`when`(bookingRepository.findAllOverlappingRange(TEST_ACCOUNT, 30L, 40L)).thenReturn(emptyList())
        Mockito.`when`(sharedCalendarRepository.findMemberUsernamesByCalendarIds(listOf("cal-1")))
            .thenReturn(listOf(TEST_ACCOUNT))
        Mockito.doAnswer { invocation ->
            val original = invocation.getArgument<BookingNode>(0)
            BookingNode(
                id = original.id,
                bookingId = original.bookingId,
                accountName = original.accountName,
                title = original.title,
                description = original.description,
                startTime = original.startTime,
                endTime = original.endTime,
                locationName = original.locationName,
                createdAt = original.createdAt,
                updatedAt = original.updatedAt,
                cancelledAt = original.cancelledAt,
                invitees = original.invitees,
                messages = original.messages,
                sharedCalendar = original.sharedCalendar,
                creator = original.creator,
            )
        }.`when`(bookingRepository).save(Mockito.any(BookingNode::class.java))

        val body = UpdateBookingRequest(
            title = "Updated",
            description = "Updated desc",
            startTime = 30L,
            endTime = 40L,
            locationName = "Room 2",
            sharedCalendarId = "cal-2",
        )

        mockMvc.perform(
            authenticated(put("/booking/1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `delete booking returns 204`() {
        Mockito.`when`(bookingRepository.findById(1L)).thenReturn(Optional.of(activeBooking()))

        mockMvc.perform(authenticated(delete("/booking/1")))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `add invitees returns 200`() {
        val contact = contactNode()
        val booking = activeBooking()
        Mockito.`when`(bookingRepository.findById(1L)).thenReturn(Optional.of(booking))
        Mockito.`when`(contactRepository.findAllActiveByAccountNameAndLookupKeyIn(TEST_ACCOUNT, listOf("lk-1")))
            .thenReturn(listOf(contact))
        Mockito.doAnswer { invocation ->
            invocation.getArgument<BookingNode>(0)
        }.`when`(bookingRepository).save(Mockito.any(BookingNode::class.java))

        val body = AddInviteesRequest(contactIds = listOf("lk-1"))

        mockMvc.perform(
            authenticated(post("/booking/1/invitees"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `remove invitee returns 204`() {
        val booking = activeBooking(invitees = linkedSetOf(contactNode()))
        Mockito.`when`(bookingRepository.findById(1L)).thenReturn(Optional.of(booking))
        Mockito.doAnswer { invocation ->
            invocation.getArgument<BookingNode>(0)
        }.`when`(bookingRepository).save(Mockito.any(BookingNode::class.java))

        mockMvc.perform(authenticated(delete("/booking/1/invitees/lk-1")))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `list invitees returns 200`() {
        val booking = activeBooking(invitees = linkedSetOf(contactNode()))
        Mockito.`when`(bookingRepository.findById(1L)).thenReturn(Optional.of(booking))

        mockMvc.perform(authenticated(get("/booking/1/invitees")))
            .andExpect(status().isOk)
    }

    @Test
    fun `add message returns 201`() {
        val booking = activeBooking(messages = mutableListOf())
        Mockito.`when`(bookingRepository.findById(1L)).thenReturn(Optional.of(booking))
        Mockito.doAnswer { invocation ->
            val original = invocation.getArgument<BookingNode>(0)
            BookingNode(
                id = original.id,
                bookingId = original.bookingId,
                accountName = original.accountName,
                title = original.title,
                description = original.description,
                startTime = original.startTime,
                endTime = original.endTime,
                locationName = original.locationName,
                createdAt = original.createdAt,
                updatedAt = original.updatedAt,
                cancelledAt = original.cancelledAt,
                invitees = original.invitees,
                messages = mutableListOf(
                    BookingMessageNode(
                        id = 2L,
                        senderName = TEST_ACCOUNT,
                        text = "Hello",
                        createdAt = 1L,
                    )
                ),
                sharedCalendar = original.sharedCalendar,
                creator = original.creator,
            )
        }.`when`(bookingRepository).save(Mockito.any(BookingNode::class.java))

        val body = CreateBookingMessageRequest(text = "Hello")

        mockMvc.perform(
            authenticated(post("/booking/1/message"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isCreated)
    }

    @Test
    fun `get chat returns 200`() {
        val booking = activeBooking(messages = mutableListOf(messageNode()))
        Mockito.`when`(bookingRepository.findById(1L)).thenReturn(Optional.of(booking))

        mockMvc.perform(authenticated(get("/booking/1/chat")))
            .andExpect(status().isOk)
    }

    @Test
    fun `edit message returns 200`() {
        val booking = activeBooking(messages = mutableListOf(messageNode()))
        Mockito.`when`(bookingRepository.findById(1L)).thenReturn(Optional.of(booking))
        whenever(bookingRepository.updateMessage(eq(1L), eq(1L), eq("Updated"), any()))
            .thenReturn(BookingMessageNode(id = 1L, senderName = TEST_ACCOUNT, text = "Updated", createdAt = 1L, editedAt = 2L))

        val body = UpdateBookingMessageRequest(text = "Updated")

        mockMvc.perform(
            authenticated(put("/booking/1/message/1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `delete message returns 204`() {
        val booking = activeBooking(messages = mutableListOf(messageNode()))
        Mockito.`when`(bookingRepository.findById(1L)).thenReturn(Optional.of(booking))

        mockMvc.perform(authenticated(delete("/booking/1/message/1")))
            .andExpect(status().isNoContent)
    }

    // -------------------------------------------------------------------------
    // BC-v1: startTime <= 0 → 400
    // -------------------------------------------------------------------------

    @Test
    fun `BC-v1 startTime zero returns 400`() {
        mockMvc.perform(
            authenticated(post("/booking"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"T","startTime":0,"endTime":100,"sharedCalendarId":"c"}""")
        ).andExpect(status().isBadRequest)
    }

    // -------------------------------------------------------------------------
    // BC-v2: endTime <= startTime → 400
    // -------------------------------------------------------------------------

    @Test
    fun `BC-v2 endTime before startTime returns 400`() {
        mockMvc.perform(
            authenticated(post("/booking"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"T","startTime":200,"endTime":100,"sharedCalendarId":"c"}""")
        ).andExpect(status().isBadRequest)
    }

    // -------------------------------------------------------------------------
    // BC-v3: sharedCalendarId blank (@NotBlank) → 400
    // -------------------------------------------------------------------------

    @Test
    fun `BC-v3 blank sharedCalendarId returns 400`() {
        mockMvc.perform(
            authenticated(post("/booking"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"T","startTime":1,"endTime":2,"sharedCalendarId":""}""")
        ).andExpect(status().isBadRequest)
    }

    // -------------------------------------------------------------------------
    // BC-v4: title blank (@NotBlank) → 400
    // -------------------------------------------------------------------------

    @Test
    fun `BC-v4 blank title returns 400`() {
        mockMvc.perform(
            authenticated(post("/booking"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"","startTime":1,"endTime":2,"sharedCalendarId":"c"}""")
        ).andExpect(status().isBadRequest)
    }

    // -------------------------------------------------------------------------
    // BC-v5: booking not found → 404
    // -------------------------------------------------------------------------

    @Test
    fun `BC-v5 get nonexistent booking returns 404`() {
        Mockito.`when`(bookingRepository.findById(99L)).thenReturn(Optional.empty())

        mockMvc.perform(authenticated(get("/booking/99")))
            .andExpect(status().isNotFound)
    }

    // -------------------------------------------------------------------------
    // BC-v6: cancelled booking → 404
    // -------------------------------------------------------------------------

    @Test
    fun `BC-v6 get cancelled booking returns 404`() {
        val cancelled = BookingNode(
            id = 1L,
            bookingId = "bk-x",
            accountName = TEST_ACCOUNT,
            title = "Cancelled",
            startTime = 10L,
            endTime = 20L,
            cancelledAt = 999L,
        )
        Mockito.`when`(bookingRepository.findById(1L)).thenReturn(Optional.of(cancelled))

        mockMvc.perform(authenticated(get("/booking/1")))
            .andExpect(status().isNotFound)
    }

    // -------------------------------------------------------------------------
    // BC-v7: booking owned by other account → ownership check fails → 404
    // -------------------------------------------------------------------------

    @Test
    fun `BC-v7 delete booking owned by other account returns 404`() {
        val othersBooking = BookingNode(
            id = 1L,
            bookingId = "bk-other",
            accountName = "other-user",
            title = "Not mine",
            startTime = 10L,
            endTime = 20L,
        )
        Mockito.`when`(bookingRepository.findById(1L)).thenReturn(Optional.of(othersBooking))

        mockMvc.perform(authenticated(delete("/booking/1")))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `get available slots returns 200`() {
        // The past range is shifted to now by SlotService — Redis and repos are hit.
        val valueOps = mock<ValueOperations<String, Any>>()
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        // valueOps.get() returns null by default → cache miss → DB query
        // appointmentRepository and bookingRepository return emptyList() by default (Mockito collection default)

        mockMvc.perform(
            authenticated(get("/booking/slots/available?from=2026-01-01T00:00:00Z&to=2026-01-01T01:00:00Z&duration=15"))
        )
            .andExpect(status().isOk)
    }

    private fun activeBooking(
        invitees: MutableSet<ContactNode> = linkedSetOf(),
        messages: MutableList<BookingMessageNode> = mutableListOf(),
    ): BookingNode {
        return BookingNode(
            id = 1L,
            bookingId = "bk-1",
            accountName = TEST_ACCOUNT,
            title = "Planning",
            description = "Sprint planning",
            startTime = 10L,
            endTime = 20L,
            locationName = "Room 1",
            createdAt = 1L,
            updatedAt = 1L,
            cancelledAt = null,
            invitees = invitees,
            messages = messages,
            sharedCalendar = sharedCalendar("cal-1"),
            creator = AccountNode(username = TEST_ACCOUNT, passwordHash = "hash"),
        )
    }

    private fun sharedCalendar(calendarId: String): SharedCalendarNode =
        SharedCalendarNode(
            calendarId = calendarId,
            name = "Team",
            createdBy = TEST_ACCOUNT,
            owner = AccountNode(username = TEST_ACCOUNT, passwordHash = "hash"),
        )

    private fun contactNode(): ContactNode =
        ContactNode(
            syncId = "sync-1",
            lookupKey = "lk-1",
            accountName = TEST_ACCOUNT,
            lastUpdatedAt = 1L,
            createdAt = 1L,
            displayName = "Alice",
        )

    private fun messageNode(): BookingMessageNode =
        BookingMessageNode(
            id = 1L,
            senderName = TEST_ACCOUNT,
            text = "Hello",
            createdAt = 1L,
        )
}
