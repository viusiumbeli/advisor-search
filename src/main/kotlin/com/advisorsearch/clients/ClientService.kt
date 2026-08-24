package com.advisorsearch.clients

import com.advisorsearch.support.ResourceNotFoundException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Thin by design: the layer exists for the boundary, not the behaviour, so document ingest and
 * seeding have a client API to call rather than another feature's repository.
 */
@Service
class ClientService(
    private val repository: ClientRepository,
) {
    fun create(request: CreateClientRequest): Client = repository.insert(request.toNewClient())

    fun get(id: UUID): Client = repository.findById(id) ?: throw ResourceNotFoundException("Client", id)

    fun findByEmail(email: String): Client? = repository.findByEmail(email)

    fun exists(id: UUID): Boolean = repository.exists(id)
}
