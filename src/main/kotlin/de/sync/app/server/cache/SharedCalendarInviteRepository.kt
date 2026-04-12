package de.sync.app.server.cache

import org.springframework.data.repository.CrudRepository

interface SharedCalendarInviteRepository : CrudRepository<SharedCalendarInviteEntity, String>
