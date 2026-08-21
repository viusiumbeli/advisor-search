package com.advisorsearch.clients

import com.advisorsearch.support.DuplicateEmailException
import com.advisorsearch.support.ResourceNotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

@RestController
@RequestMapping("/clients")
@Tag(name = "Clients")
class ClientController(
    private val repository: ClientRepository,
) {
    @PostMapping
    @Operation(
        summary = "Create a client",
        description = "Email is the identity key advisors search by, so a repeat email is rejected with 409.",
    )
    fun create(
        @Valid @RequestBody request: CreateClientRequest,
    ): ResponseEntity<Client> {
        val client =
            try {
                repository.insert(request)
            } catch (_: DuplicateKeyException) {
                throw DuplicateEmailException(request.email!!.trim())
            }
        val location = UriComponentsBuilder.fromPath("/clients/{id}").build(client.id)
        return ResponseEntity.created(location).body(client)
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a client by id")
    fun get(
        @PathVariable id: UUID,
    ): Client = repository.findById(id) ?: throw ResourceNotFoundException("Client", id)
}
