package com.advisorsearch.clients

import com.advisorsearch.support.ResourceNotFoundException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Thin by design: client creation has no business logic today, and this layer exists for the
 * boundary, not the behaviour. It keeps every controller on the same controller→service→repository
 * path and gives other features (document ingest, seeding) a client API that is not another
 * feature's repository.
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
