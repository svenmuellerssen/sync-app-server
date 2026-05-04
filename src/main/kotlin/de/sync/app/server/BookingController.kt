package de.sync.app.server

import de.sync.app.server.graph.AccountRepository
import de.sync.app.server.graph.BookingMessageNode
import de.sync.app.server.graph.BookingNode
import de.sync.app.server.graph.BookingRepository
import de.sync.app.server.graph.ContactNode
import de.sync.app.server.graph.ContactRepository
import de.sync.app.server.graph.SharedCalendarNode
import de.sync.app.server.graph.SharedCalendarRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/booking")
class BookingController(
    private val bookingRepository: BookingRepository,
    private val contactRepository: ContactRepository,
    private val sharedCalendarRepository: SharedCalendarRepository,
    private val accountRepository: AccountRepository,
    private val slotService: SlotService,
) {

    @GetMapping
    fun listBookings(
        request: HttpServletRequest,
    ): ResponseEntity<List<BookingResponse>> {
        val accountName = request.accountName()
        val ids = bookingRepository.findAllVisibleIdsByAccountName(accountName)
        val bookings = bookingRepository.findAllById(ids)
            .sortedBy { it.startTime }
            .map { it.toResponse() }
        return ResponseEntity.ok(bookings)
    }

    @GetMapping("/{id}")
    fun getBooking(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): ResponseEntity<BookingResponse> {
        val booking = requireBooking(id, request.accountName(), requireOwnership = false)
        return ResponseEntity.ok(booking.toResponse())
    }

    @Transactional
    @PostMapping
    fun createBooking(
        @RequestBody @Valid requestBody: CreateBookingRequest,
        request: HttpServletRequest,
    ): ResponseEntity<BookingResponse> {
        validateTimeRange(requestBody.startTime, requestBody.endTime)

        val accountName = request.accountName()
        val sharedCalendar = requireSharedCalendar(requestBody.sharedCalendarId, accountName)

        // Race condition guard: reject if an active booking already occupies this time slot
        // (includes other members' bookings in the same SharedCalendar)
        val overlapping = bookingRepository.findAllOverlappingRange(accountName, requestBody.startTime, requestBody.endTime)
        if (overlapping.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A booking already exists in this time range")
        }

        val creator = accountRepository.findByUsername(accountName)
        val now = System.currentTimeMillis()
        val booking = BookingNode(
            bookingId = UUID.randomUUID().toString(),
            accountName = accountName,
            title = requestBody.title.trim(),
            description = requestBody.description?.trim()?.takeIf { it.isNotEmpty() },
            startTime = requestBody.startTime,
            endTime = requestBody.endTime,
            locationName = requestBody.locationName?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = now,
            updatedAt = now,
            sharedCalendar = sharedCalendar,
            creator = creator,
        )

        val saved = bookingRepository.save(booking)
        slotService.invalidateRange(accountName, saved.startTime, saved.endTime)
        invalidateSharedCalendarMembers(sharedCalendar.calendarId, accountName)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    @Transactional
    @PutMapping("/{id}")
    fun updateBooking(
        @PathVariable id: Long,
        @RequestBody @Valid requestBody: UpdateBookingRequest,
        request: HttpServletRequest,
    ): ResponseEntity<BookingResponse> {
        validateTimeRange(requestBody.startTime, requestBody.endTime)

        val existing = requireBooking(id, request.accountName())
        val sharedCalendar = requireSharedCalendar(requestBody.sharedCalendarId, existing.accountName)

        // Overlap check with self-exclude (including other SharedCalendar members' bookings)
        val overlapping = bookingRepository.findAllOverlappingRange(
            existing.accountName, requestBody.startTime, requestBody.endTime
        ).filter { it.id != existing.id }
        if (overlapping.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A booking already exists in this time range")
        }

        val updated = BookingNode(
            id = existing.id,
            bookingId = existing.bookingId,
            accountName = existing.accountName,
            title = requestBody.title.trim(),
            description = requestBody.description?.trim()?.takeIf { it.isNotEmpty() },
            startTime = requestBody.startTime,
            endTime = requestBody.endTime,
            locationName = requestBody.locationName?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = existing.createdAt,
            updatedAt = System.currentTimeMillis(),
            cancelledAt = existing.cancelledAt,
            invitees = existing.invitees,
            messages = existing.messages,
            sharedCalendar = sharedCalendar,
            creator = existing.creator,
        )

        val saved = bookingRepository.save(updated)
        slotService.invalidateRange(existing.accountName, existing.startTime, existing.endTime)
        slotService.invalidateRange(saved.accountName, saved.startTime, saved.endTime)
        invalidateSharedCalendarMembers(existing.sharedCalendar?.calendarId, existing.accountName)
        return ResponseEntity.ok(saved.toResponse())
    }

    @Transactional
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteBooking(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ) {
        val booking = requireBooking(id, request.accountName())
        bookingRepository.softDeleteById(booking.id!!, System.currentTimeMillis())
        slotService.invalidateRange(booking.accountName, booking.startTime, booking.endTime)
        invalidateSharedCalendarMembers(booking.sharedCalendar?.calendarId, booking.accountName)
    }

    @Transactional
    @PostMapping("/{id}/invitees")
    fun addInvitees(
        @PathVariable id: Long,
        @RequestBody @Valid requestBody: AddInviteesRequest,
        request: HttpServletRequest,
    ): ResponseEntity<InviteeMutationResponse> {
        require(requestBody.contactIds.size <= 200) { "max 200 invitees per request" }

        val booking = requireBooking(id, request.accountName(), requireOwnership = false)
        val lookupKeys = requestBody.contactIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        // Only active (non-deleted) contacts — prevents soft-deleted contacts from being added as invitees
        val contactsByLookupKey = contactRepository
            .findAllActiveByAccountNameAndLookupKeyIn(booking.accountName, lookupKeys)
            .associateBy { it.lookupKey }

        var filtered = 0
        var saved = 0
        for (lookupKey in lookupKeys) {
            val contact = contactsByLookupKey[lookupKey]
            if (contact == null) {
                filtered++
                continue
            }
            if (booking.invitees.add(contact)) saved++
        }

        val persisted = bookingRepository.save(booking)
        val response = InviteeMutationResponse(
            saved = saved,
            filtered = filtered,
            invitees = persisted.invitees.filter { it.deletedAt == null }.sortedBy { it.displayName ?: it.lookupKey }.map { it.toInviteeResponse() },
        )
        val status = if (filtered > 0) HttpStatus.MULTI_STATUS else HttpStatus.OK
        return ResponseEntity.status(status).body(response)
    }

    @Transactional
    @DeleteMapping("/{id}/invitees/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeInvitee(
        @PathVariable id: Long,
        @PathVariable contactId: String,
        request: HttpServletRequest,
    ) {
        val booking = requireBooking(id, request.accountName())
        val removed = booking.invitees.removeIf { it.lookupKey == contactId }
        if (!removed) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Invitee not found")
        }
        bookingRepository.save(booking)
    }

    @GetMapping("/{id}/invitees")
    fun listInvitees(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): ResponseEntity<List<InviteeResponse>> {
        val booking = requireBooking(id, request.accountName(), requireOwnership = false)
        return ResponseEntity.ok(
            booking.invitees
                .filter { it.deletedAt == null }
                .sortedBy { it.displayName ?: it.lookupKey }
                .map { it.toInviteeResponse() }
        )
    }

    @Transactional
    @PostMapping("/{id}/message")
    fun addMessage(
        @PathVariable id: Long,
        @RequestBody @Valid requestBody: CreateBookingMessageRequest,
        request: HttpServletRequest,
    ): ResponseEntity<BookingMessageResponse> {
        val booking = requireBooking(id, request.accountName(), requireOwnership = false)
        // senderName always comes from the authenticated token, not from the request body
        val senderName = request.accountName()
        val newMessage = BookingMessageNode(
            senderName = senderName,
            text = requestBody.text.trim(),
        )
        booking.messages.add(newMessage)
        val persisted = bookingRepository.save(booking)
        // Find the newly saved message by highest assigned id (newest node)
        val message = persisted.messages.maxByOrNull { it.id ?: Long.MIN_VALUE }
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Message was not persisted")
        return ResponseEntity.status(HttpStatus.CREATED).body(message.toResponse())
    }

    @GetMapping("/{id}/chat")
    fun getChat(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): ResponseEntity<List<BookingMessageResponse>> {
        val booking = requireBooking(id, request.accountName(), requireOwnership = false)
        return ResponseEntity.ok(booking.messages.sortedBy { it.createdAt }.map { it.toResponse() })
    }

    @Transactional
    @PutMapping("/{id}/message/{messageId}")
    fun editMessage(
        @PathVariable id: Long,
        @PathVariable messageId: Long,
        @RequestBody @Valid requestBody: UpdateBookingMessageRequest,
        request: HttpServletRequest,
    ): ResponseEntity<BookingMessageResponse> {
        val accountName = request.accountName()
        val booking = requireBooking(id, accountName, requireOwnership = false)

        val message = booking.messages.find { it.id == messageId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found")

        // Only the original sender may edit — booking owner cannot edit others' messages
        if (message.senderName != accountName) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the sender may edit this message")
        }

        val updated = bookingRepository.updateMessage(id, messageId, requestBody.text.trim(), System.currentTimeMillis())
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found")
        return ResponseEntity.ok(updated.toResponse())
    }

    @Transactional
    @DeleteMapping("/{id}/message/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMessage(
        @PathVariable id: Long,
        @PathVariable messageId: Long,
        request: HttpServletRequest,
    ) {
        val accountName = request.accountName()
        val booking = requireBooking(id, accountName, requireOwnership = false)

        val message = booking.messages.find { it.id == messageId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found")

        val isBookingOwner = booking.accountName == accountName
        val isSender = message.senderName == accountName
        if (!isBookingOwner && !isSender) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to delete this message")
        }

        bookingRepository.deleteMessage(id, messageId)
    }

    @GetMapping("/slots/available")
    fun getAvailableSlots(
        @RequestParam from: String,
        @RequestParam to: String,
        @RequestParam @Min(1) duration: Long,
        request: HttpServletRequest,
    ): ResponseEntity<List<TimeSlot>> {
        val slots = slotService.findAvailableSlots(request.accountName(), from, to, duration)
        return ResponseEntity.ok(slots)
    }

    private fun invalidateSharedCalendarMembers(calendarId: String?, excludeAccount: String) {
        if (calendarId == null) return
        val affected = sharedCalendarRepository
            .findMemberUsernamesByCalendarIds(listOf(calendarId))
            .toSet()
            .minus(excludeAccount)
        for (username in affected) {
            slotService.invalidateAccount(username)
        }
    }

    /**
     * Loads and validates a booking for the given account.
     *
     * @param requireOwnership If true (default), only the booking owner can access — used for
     *                         write operations (update, delete, manage invitees).
     *                         If false, SharedCalendar members may also access — used for read
     *                         and chat operations (get, listInvitees, getChat, addMessage, deleteMessage).
     */
    private fun requireBooking(id: Long, accountName: String, requireOwnership: Boolean = true): BookingNode {
        val booking = bookingRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found")
        }
        if (booking.cancelledAt != null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found")
        }
        // Defence-in-depth: reject bookings whose shared calendar was deleted (orphan guard)
        if (booking.sharedCalendar?.deletedAt != null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found")
        }
        if (booking.accountName == accountName) return booking
        if (!requireOwnership) {
            val calendarId = booking.sharedCalendar?.calendarId
            if (calendarId != null && sharedCalendarRepository.countAccessibleByAccount(calendarId, accountName) > 0) {
                return booking
            }
        }
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found")
    }

    private fun requireSharedCalendar(calendarId: String, accountName: String): SharedCalendarNode {
        val sharedCalendar = sharedCalendarRepository.findByCalendarIdAndDeletedAtIsNull(calendarId)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Shared calendar not found")
        if (sharedCalendar.members.none { it.username == accountName } && sharedCalendar.owner?.username != accountName) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Shared calendar is not accessible")
        }
        return sharedCalendar
    }

    private fun validateTimeRange(startTime: Long, endTime: Long) {
        require(startTime > 0) { "startTime must be a positive unix timestamp" }
        require(endTime > startTime) { "endTime must be later than startTime" }
    }

    private fun HttpServletRequest.accountName(): String =
        getAttribute("accountName") as? String
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing account context")
}

data class CreateBookingRequest(
    @field:NotBlank val title: String,
    val description: String? = null,
    val startTime: Long,
    val endTime: Long,
    val locationName: String? = null,
    @field:NotBlank val sharedCalendarId: String,
)

data class UpdateBookingRequest(
    @field:NotBlank val title: String,
    val description: String? = null,
    val startTime: Long,
    val endTime: Long,
    val locationName: String? = null,
    @field:NotBlank val sharedCalendarId: String,
)

data class AddInviteesRequest(
    val contactIds: List<String> = emptyList(),
)

data class UpdateBookingMessageRequest(
    @field:NotBlank val text: String,
)

data class CreateBookingMessageRequest(
    /** Ignored — sender is always taken from the authenticated token. */
    val senderName: String? = null,
    @field:NotBlank val text: String,
)

data class InviteeResponse(
    val lookupKey: String,
    val displayName: String?,
    val syncId: String,
    /** True when the contact has been soft-deleted on the server. */
    val deleted: Boolean = false,
)

data class InviteeMutationResponse(
    val saved: Int,
    val filtered: Int,
    val invitees: List<InviteeResponse>,
)

data class BookingMessageResponse(
    val id: Long,
    val senderName: String,
    val text: String,
    val createdAt: Long,
    /** Null = never edited. Non-null = last edit timestamp (Unix ms). */
    val editedAt: Long? = null,
)

data class BookingResponse(
    val id: Long,
    val bookingId: String?,
    val accountName: String,
    val title: String,
    val description: String?,
    val startTime: Long,
    val endTime: Long,
    val locationName: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val cancelledAt: Long?,
    val sharedCalendarId: String?,
    val sharedCalendarName: String?,
    val invitees: List<InviteeResponse>,
    val messages: List<BookingMessageResponse>,
)

private fun BookingNode.toResponse() = BookingResponse(
    id = id ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Booking id is missing"),
    bookingId = bookingId,
    accountName = accountName,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    locationName = locationName,
    createdAt = createdAt,
    updatedAt = updatedAt,
    cancelledAt = cancelledAt,
    sharedCalendarId = sharedCalendar?.calendarId,
    sharedCalendarName = sharedCalendar?.name,
    invitees = invitees.filter { it.deletedAt == null }.sortedBy { it.displayName ?: it.lookupKey }.map { it.toInviteeResponse() },
    messages = messages.sortedBy { it.createdAt }.map { it.toResponse() },
)

private fun ContactNode.toInviteeResponse() = InviteeResponse(
    lookupKey = lookupKey,
    displayName = displayName,
    syncId = syncId,
    deleted = deletedAt != null,
)

private fun BookingMessageNode.toResponse() = BookingMessageResponse(
    id = id ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Message id is missing"),
    senderName = senderName,
    text = text,
    createdAt = createdAt,
    editedAt = editedAt,
)
