package de.sync.app.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SyncAppServerApplication

fun main(args: Array<String>) {
	runApplication<SyncAppServerApplication>(*args)
}
