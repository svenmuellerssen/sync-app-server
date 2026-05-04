package de.sync.app.server

import de.sync.app.server.cache.SessionEntity
import de.sync.app.server.cache.SessionRepository
import de.sync.app.server.cache.SharedCalendarInviteEntity
import de.sync.app.server.cache.SharedCalendarInviteRepository
import de.sync.app.server.graph.AccountNode
import de.sync.app.server.graph.AccountRepository
import de.sync.app.server.graph.BookingRepository
import de.sync.app.server.graph.CalendarRepository
import de.sync.app.server.graph.GoogleCalendarRepository
import de.sync.app.server.graph.SharedCalendarNode
import de.sync.app.server.graph.SharedCalendarRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.data.redis.RedisConnectionFailureException
import java.util.Optional

@WebMvcTest(
    controllers = [SharedCalendarController::class],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [WebMvcConfig::class, TokenAuthInterceptor::class])
    ]
)
@AutoConfigureMockMvc(addFilters = false)
class SharedCalendarControllerTest : EndpointTestSupport() {

    @MockBean
    lateinit var sharedCalendarRepository: SharedCalendarRepository

    @MockBean
    lateinit var sharedCalendarInviteRepository: SharedCalendarInviteRepository

    @MockBean
    lateinit var bookingRepository: BookingRepository

    @MockBean
    lateinit var accountRepository: AccountRepository

    @MockBean
    lateinit var sessionRepository: SessionRepository

    @MockBean
    lateinit var googleCalendarRepository: GoogleCalendarRepository

    @MockBean
    lateinit var calendarRepository: CalendarRepository

    @MockBean
    lateinit var slotService: SlotService

    @Test
    fun `create shared calendar returns 200`() {
        val owner = AccountNode(username = TEST_ACCOUNT, passwordHash = "hash")
        Mockito.`when`(sessionRepository.findById(TEST_TOKEN))
            .thenReturn(Optional.of(SessionEntity(TEST_TOKEN, TEST_ACCOUNT)))
        Mockito.`when`(accountRepository.findByUsername(TEST_ACCOUNT)).thenReturn(owner)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<SharedCalendarNode>(0)
        }.`when`(sharedCalendarRepository).save(Mockito.any(SharedCalendarNode::class.java))

        mockMvc.perform(
            authenticated(post("/shared-calendar"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Team","color":65280}""")
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `list shared calendars returns 200`() {
        Mockito.`when`(sessionRepository.findById(TEST_TOKEN))
            .thenReturn(Optional.of(SessionEntity(TEST_TOKEN, TEST_ACCOUNT)))
        Mockito.`when`(sharedCalendarRepository.findAllAccessibleByAccountName(TEST_ACCOUNT))
            .thenReturn(emptyList())

        mockMvc.perform(
            authenticated(get("/shared-calendar/list"))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `generate invite returns 200`() {
        Mockito.`when`(sessionRepository.findById(TEST_TOKEN))
            .thenReturn(Optional.of(SessionEntity(TEST_TOKEN, TEST_ACCOUNT)))
        Mockito.`when`(sharedCalendarRepository.findByCalendarIdAndDeletedAtIsNull("cal-1"))
            .thenReturn(
                SharedCalendarNode(
                    calendarId = "cal-1",
                    name = "Team",
                    createdBy = TEST_ACCOUNT,
                    owner = AccountNode(username = TEST_ACCOUNT, passwordHash = "hash"),
                )
            )

        mockMvc.perform(
            authenticated(get("/shared-calendar/invite/cal-1"))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `join shared calendar returns 200`() {
        val owner = AccountNode(username = TEST_ACCOUNT, passwordHash = "hash")
        Mockito.`when`(sessionRepository.findById(TEST_TOKEN))
            .thenReturn(Optional.of(SessionEntity(TEST_TOKEN, TEST_ACCOUNT)))
        Mockito.`when`(sharedCalendarInviteRepository.findById("invite-1"))
            .thenReturn(Optional.of(SharedCalendarInviteEntity("invite-1", "cal-1", TEST_ACCOUNT)))
        Mockito.`when`(sharedCalendarRepository.findByCalendarIdAndDeletedAtIsNull("cal-1"))
            .thenReturn(
                SharedCalendarNode(
                    calendarId = "cal-1",
                    name = "Team",
                    createdBy = TEST_ACCOUNT,
                    owner = owner,
                )
            )
        Mockito.`when`(accountRepository.findByUsername(TEST_ACCOUNT)).thenReturn(owner)

        mockMvc.perform(
            authenticated(post("/shared-calendar/join"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"inviteCode":"invite-1"}""")
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `leave shared calendar returns 204`() {
        val owner = AccountNode(username = "owner", passwordHash = "hash")
        val member = AccountNode(username = TEST_ACCOUNT, passwordHash = "hash")
        Mockito.`when`(sessionRepository.findById(TEST_TOKEN))
            .thenReturn(Optional.of(SessionEntity(TEST_TOKEN, TEST_ACCOUNT)))
        Mockito.`when`(sharedCalendarRepository.findByCalendarIdAndDeletedAtIsNull("cal-1"))
            .thenReturn(
                SharedCalendarNode(
                    calendarId = "cal-1",
                    name = "Team",
                    createdBy = owner.username,
                    owner = owner,
                    members = mutableListOf(member),
                )
            )
        Mockito.`when`(sharedCalendarRepository.findMemberUsernamesByCalendarIds(listOf("cal-1")))
            .thenReturn(listOf(TEST_ACCOUNT, owner.username))

        mockMvc.perform(
            authenticated(delete("/shared-calendar/cal-1/leave"))
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `delete shared calendar returns 204`() {
        val owner = AccountNode(username = TEST_ACCOUNT, passwordHash = "hash")
        Mockito.`when`(sessionRepository.findById(TEST_TOKEN))
            .thenReturn(Optional.of(SessionEntity(TEST_TOKEN, TEST_ACCOUNT)))
        Mockito.`when`(sharedCalendarRepository.findByCalendarId("cal-1"))
            .thenReturn(
                SharedCalendarNode(
                    calendarId = "cal-1",
                    name = "Team",
                    createdBy = TEST_ACCOUNT,
                    owner = owner,
                )
            )
        Mockito.`when`(sharedCalendarRepository.findMemberUsernamesByCalendarIds(listOf("cal-1")))
            .thenReturn(listOf(TEST_ACCOUNT))
        Mockito.`when`(sharedCalendarInviteRepository.findAllByCalendarId("cal-1"))
            .thenReturn(listOf(SharedCalendarInviteEntity("invite-1", "cal-1", TEST_ACCOUNT)))

        mockMvc.perform(
            authenticated(delete("/shared-calendar/cal-1"))
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `get personal calendars returns 200`() {
        Mockito.`when`(sessionRepository.findById(TEST_TOKEN))
            .thenReturn(Optional.of(SessionEntity(TEST_TOKEN, TEST_ACCOUNT)))
        Mockito.`when`(calendarRepository.findAllActiveByAccountName(TEST_ACCOUNT))
            .thenReturn(emptyList())

        mockMvc.perform(
            authenticated(get("/calendar"))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `get google calendars returns 200`() {
        Mockito.`when`(sessionRepository.findById(TEST_TOKEN))
            .thenReturn(Optional.of(SessionEntity(TEST_TOKEN, TEST_ACCOUNT)))
        Mockito.`when`(googleCalendarRepository.findAllByAccountName(TEST_ACCOUNT))
            .thenReturn(emptyList())

        mockMvc.perform(
            authenticated(get("/calendar/google"))
        )
            .andExpect(status().isOk)
    }

    // -------------------------------------------------------------------------
    // SCS-v1: invite without valid session → 401
    // -------------------------------------------------------------------------

    @Test
    fun `SCS-v1 invite without valid session returns 401`() {
        Mockito.`when`(sessionRepository.findById(TEST_TOKEN)).thenReturn(Optional.empty())

        mockMvc.perform(authenticated(get("/shared-calendar/invite/cal-1")))
            .andExpect(status().isUnauthorized)
    }

    // -------------------------------------------------------------------------
    // SCS-v2: invite for calendar not owned by caller → 403
    // -------------------------------------------------------------------------

    @Test
    fun `SCS-v2 invite for calendar not owned by caller returns 403`() {
        Mockito.`when`(sessionRepository.findById(TEST_TOKEN))
            .thenReturn(Optional.of(SessionEntity(TEST_TOKEN, TEST_ACCOUNT)))
        Mockito.`when`(sharedCalendarRepository.findByCalendarIdAndDeletedAtIsNull("cal-1"))
            .thenReturn(
                SharedCalendarNode(
                    calendarId = "cal-1",
                    name = "Team",
                    createdBy = "other-owner",
                    owner = AccountNode(username = "other-owner", passwordHash = "h"),
                )
            )

        mockMvc.perform(authenticated(get("/shared-calendar/invite/cal-1")))
            .andExpect(status().isForbidden)
    }

    // -------------------------------------------------------------------------
    // SCS-v3: calendar owner tries to leave → 409
    // -------------------------------------------------------------------------

    @Test
    fun `SCS-v3 owner tries to leave own calendar returns 409`() {
        Mockito.`when`(sessionRepository.findById(TEST_TOKEN))
            .thenReturn(Optional.of(SessionEntity(TEST_TOKEN, TEST_ACCOUNT)))
        Mockito.`when`(sharedCalendarRepository.findByCalendarIdAndDeletedAtIsNull("cal-1"))
            .thenReturn(
                SharedCalendarNode(
                    calendarId = "cal-1",
                    name = "Team",
                    createdBy = TEST_ACCOUNT,
                    owner = AccountNode(username = TEST_ACCOUNT, passwordHash = "hash"),
                )
            )

        mockMvc.perform(authenticated(delete("/shared-calendar/cal-1/leave")))
            .andExpect(status().isConflict)
    }

    // -------------------------------------------------------------------------
    // SCS-v4: non-owner tries to delete calendar → 403
    // -------------------------------------------------------------------------

    @Test
    fun `SCS-v4 non-owner tries to delete calendar returns 403`() {
        Mockito.`when`(sessionRepository.findById(TEST_TOKEN))
            .thenReturn(Optional.of(SessionEntity(TEST_TOKEN, TEST_ACCOUNT)))
        Mockito.`when`(sharedCalendarRepository.findByCalendarId("cal-1"))
            .thenReturn(
                SharedCalendarNode(
                    calendarId = "cal-1",
                    name = "Team",
                    createdBy = "other-owner",
                    owner = AccountNode(username = "other-owner", passwordHash = "h"),
                )
            )

        mockMvc.perform(authenticated(delete("/shared-calendar/cal-1")))
            .andExpect(status().isForbidden)
    }

    // -------------------------------------------------------------------------
    // SC-R1: Redis nicht erreichbar beim Invite-Code speichern → 503 statt 500
    // Derzeit: unbehandelte Exception → 500 (RED).
    // -------------------------------------------------------------------------

    @Test
    fun `SC-R1 generate invite returns 503 when Redis is unavailable`() {
        Mockito.`when`(sessionRepository.findById(TEST_TOKEN))
            .thenReturn(Optional.of(SessionEntity(TEST_TOKEN, TEST_ACCOUNT)))
        Mockito.`when`(sharedCalendarRepository.findByCalendarIdAndDeletedAtIsNull("cal-r1"))
            .thenReturn(
                SharedCalendarNode(
                    calendarId = "cal-r1",
                    name = "Team",
                    createdBy = TEST_ACCOUNT,
                    owner = AccountNode(username = TEST_ACCOUNT, passwordHash = "hash"),
                )
            )
        Mockito.`when`(sharedCalendarInviteRepository.save(Mockito.any()))
            .thenThrow(RedisConnectionFailureException("Redis unavailable"))

        mockMvc.perform(authenticated(get("/shared-calendar/invite/cal-r1")))
            .andExpect(status().isServiceUnavailable)
    }
}
