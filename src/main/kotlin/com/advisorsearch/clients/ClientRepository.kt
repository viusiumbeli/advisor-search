package com.advisorsearch.clients

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/**
 * Maps a client row. Hand-written on purpose: `social_links` is a Postgres `text[]`, which no
 * property-based row mapper converts. Shared with the search repository, which selects the same
 * columns plus its own score.
 */
internal fun ResultSet.toClient(): Client =
    Client(
        id = getObject("id", UUID::class.java),
        firstName = getString("first_name"),
        lastName = getString("last_name"),
        email = getString("email"),
        description = getString("description"),
        socialLinks = socialLinks(),
    )

internal val clientRowMapper = RowMapper { row, _ -> row.toClient() }

private fun ResultSet.socialLinks(): List<String> {
    val array = getArray("social_links") ?: return emptyList()
    @Suppress("UNCHECKED_CAST")
    return (array.array as Array<String?>).filterNotNull()
}

@Repository
class ClientRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(client: NewClient): Client =
        jdbc
            .sql(
                """
                INSERT INTO clients (first_name, last_name, email, description, social_links)
                VALUES (:firstName, :lastName, :email, :description, :socialLinks)
                RETURNING id, first_name, last_name, email, description, social_links
                """.trimIndent(),
            ).param("firstName", client.firstName)
            .param("lastName", client.lastName)
            .param("email", client.email)
            .param("description", client.description)
            .param("socialLinks", client.socialLinks.toTypedArray())
            .query(clientRowMapper)
            .single()

    fun findById(id: UUID): Client? =
        jdbc
            .sql(
                """
                SELECT id, first_name, last_name, email, description, social_links
                FROM clients WHERE id = :id
                """.trimIndent(),
            ).param("id", id)
            .query(clientRowMapper)
            .optional()
            .getOrNull()

    fun findByEmail(email: String): Client? =
        jdbc
            .sql(
                """
                SELECT id, first_name, last_name, email, description, social_links
                FROM clients WHERE lower(email) = lower(:email)
                """.trimIndent(),
            ).param("email", email)
            .query(clientRowMapper)
            .optional()
            .getOrNull()

    fun exists(id: UUID): Boolean =
        jdbc
            .sql("SELECT EXISTS(SELECT 1 FROM clients WHERE id = :id)")
            .param("id", id)
            .query(Boolean::class.java)
            .single()

    /** Whether the corpus holds anything at all. A document references a client, so no client means none. */
    fun anyExist(): Boolean = jdbc.sql("SELECT EXISTS(SELECT 1 FROM clients)").query(Boolean::class.java).single()
}
