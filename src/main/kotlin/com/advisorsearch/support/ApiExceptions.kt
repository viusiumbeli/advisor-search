package com.advisorsearch.support

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import java.util.UUID

/**
 * Domain exceptions are `ErrorResponseException`s, so Spring renders them as RFC 9457
 * `application/problem+json` on its own — no advice method per exception type.
 */
class ResourceNotFoundException(
    resource: String,
    id: UUID,
) : ErrorResponseException(HttpStatus.NOT_FOUND, problem(HttpStatus.NOT_FOUND, "Not found", "$resource $id was not found"), null)

class InvalidRequestException(
    message: String,
) : ErrorResponseException(HttpStatus.BAD_REQUEST, problem(HttpStatus.BAD_REQUEST, "Invalid request", message), null)

internal fun problem(
    status: HttpStatus,
    title: String,
    detail: String,
): ProblemDetail =
    ProblemDetail.forStatusAndDetail(status, detail).apply {
        this.title = title
    }
