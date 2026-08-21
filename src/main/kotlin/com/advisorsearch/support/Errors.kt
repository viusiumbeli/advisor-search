package com.advisorsearch.support

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.util.UUID

class ResourceNotFoundException(
    resource: String,
    id: UUID,
) : RuntimeException("$resource $id was not found")

class DuplicateEmailException(
    email: String,
) : RuntimeException("A client with email $email already exists")

class InvalidRequestException(
    message: String,
) : RuntimeException(message)

/**
 * Every error leaves as RFC 9457 `application/problem+json`. Validation failures additionally carry
 * an `errors` object keyed by field, because "400 Bad Request" alone does not tell a client which
 * of the five fields it got wrong.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(ResourceNotFoundException::class)
    fun handleNotFound(exception: ResourceNotFoundException): ProblemDetail = problem(HttpStatus.NOT_FOUND, "Not found", exception.message)

    @ExceptionHandler(DuplicateEmailException::class)
    fun handleDuplicateEmail(exception: DuplicateEmailException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "Email already registered", exception.message)

    @ExceptionHandler(InvalidRequestException::class)
    fun handleInvalidRequest(exception: InvalidRequestException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Invalid request", exception.message)

    override fun handleMethodArgumentNotValid(
        exception: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val fieldErrors =
            exception.bindingResult.fieldErrors.associate { error ->
                error.field to (error.defaultMessage ?: "is invalid")
            }
        val detail = problem(HttpStatus.BAD_REQUEST, "Validation failed", "The request body is not valid")
        detail.setProperty("errors", fieldErrors)
        return ResponseEntity.badRequest().body(detail)
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String?,
    ): ProblemDetail =
        ProblemDetail.forStatus(status).apply {
            this.title = title
            this.detail = detail
        }
}
