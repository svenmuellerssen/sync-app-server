package de.sync.app.server

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

data class ErrorResponse(val error: String, val message: String)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.badRequest().body(ErrorResponse("BAD_REQUEST", "Malformed or incomplete request body"))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(ErrorResponse("VALIDATION_ERROR", details))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.badRequest().body(ErrorResponse("BAD_REQUEST", ex.message ?: "Invalid input"))
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<ErrorResponse> {
        val reason = ex.reason ?: HttpStatusCode.valueOf(ex.statusCode.value()).toString()
        return ResponseEntity.status(ex.statusCode).body(ErrorResponse(ex.statusCode.value().toString(), reason))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"))
    }
}
