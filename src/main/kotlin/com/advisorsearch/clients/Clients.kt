package com.advisorsearch.clients

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

/** Response shape from the task's OpenAPI fragment; serialised as snake_case. */
data class Client(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val email: String,
    val description: String?,
    val socialLinks: List<String>,
)

/**
 * Wire shape. Required fields are nullable on purpose: with non-null Kotlin types an absent field
 * dies inside Jackson as an unreadable-body 400 with no field map, whereas nullable fields reach
 * Bean Validation, which reports every violation per field. [toNewClient] resolves it exactly once.
 */
data class CreateClientRequest(
    @field:NotBlank(message = "must not be blank")
    @field:Size(max = 200, message = "must be at most 200 characters")
    val firstName: String?,
    @field:NotBlank(message = "must not be blank")
    @field:Size(max = 200, message = "must be at most 200 characters")
    val lastName: String?,
    @field:NotBlank(message = "must not be blank")
    @field:Email(message = "must be a well-formed email address")
    @field:Size(max = 320, message = "must be at most 320 characters")
    val email: String?,
    @field:Size(max = 5_000, message = "must be at most 5000 characters")
    val description: String? = null,
    @field:Size(max = 20, message = "must contain at most 20 links")
    val socialLinks: List<
        @Size(max = 500, message = "must be at most 500 characters")
        String,
    > = emptyList(),
)

data class NewClient(
    val firstName: String,
    val lastName: String,
    val email: String,
    val description: String?,
    val socialLinks: List<String>,
)

fun CreateClientRequest.toNewClient(): NewClient =
    NewClient(
        firstName = firstName!!.trim(),
        lastName = lastName!!.trim(),
        email = email!!.trim(),
        description = description?.trim()?.ifEmpty { null },
        socialLinks = socialLinks,
    )
