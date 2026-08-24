package com.advisorsearch.support

import org.springframework.dao.DuplicateKeyException
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

/** The two cases the framework cannot render well on its own. */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    /**
     * The schema enforces exactly one uniqueness rule — `lower(email)` on clients — so a duplicate
     * key coming back from Postgres is a duplicate email, and translating it here keeps the
     * controller free of persistence error handling.
     */
    @ExceptionHandler(DuplicateKeyException::class)
    fun handleDuplicateEmail(exception: DuplicateKeyException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "Email already registered", "A client with this email address already exists")

    /**
     * Validation failures carry an `errors` object keyed by field, because "400 Bad Request" alone
     * does not tell a caller which of five fields it got wrong. Values are lists: a field can
     * violate several constraints at once, and dropping all but one message hides real feedback.
     */
    override fun handleMethodArgumentNotValid(
        exception: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val fieldErrors =
            exception.bindingResult.fieldErrors.groupBy(
                keySelector = { it.field },
                valueTransform = { it.defaultMessage ?: "is invalid" },
            )
        val detail =
            ProblemDetail.forStatusAndDetail(status, "The request body is not valid").apply {
                title = "Validation failed"
                setProperty("errors", fieldErrors)
            }
        return ResponseEntity.status(status).headers(headers).body(detail)
    }
}
