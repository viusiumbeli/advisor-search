package com.advisorsearch.clients

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class ClientRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(request: CreateClientRequest): Client =
        jdbc
            .sql(
                """
                INSERT INTO clients (first_name, last_name, email, description, social_links)
                VALUES (:firstName, :lastName, :email, :description, :socialLinks)
                RETURNING id, first_name, last_name, email, description, social_links
                """.trimIndent(),
            ).param("firstName", request.firstName!!.trim())
            .param("lastName", request.lastName!!.trim())
            .param("email", request.email!!.trim())
            .param("description", request.description?.trim()?.ifEmpty { null })
            .param("socialLinks", request.socialLinks.toTypedArray())
            .query(::mapClient)
            .single()

    fun findById(id: UUID): Client? =
        jdbc
            .sql(
                """
                SELECT id, first_name, last_name, email, description, social_links
                FROM clients WHERE id = :id
                """.trimIndent(),
            ).param("id", id)
            .query(::mapClient)
            .optional()
            .orElse(null)

    fun findByEmail(email: String): Client? =
        jdbc
            .sql(
                """
                SELECT id, first_name, last_name, email, description, social_links
                FROM clients WHERE lower(email) = lower(:email)
                """.trimIndent(),
            ).param("email", email)
            .query(::mapClient)
            .optional()
            .orElse(null)

    fun exists(id: UUID): Boolean =
        jdbc
            .sql("SELECT 1 FROM clients WHERE id = :id")
            .param("id", id)
            .query(Int::class.java)
            .optional()
            .isPresent

    companion object {
        fun mapClient(
            row: ResultSet,
            @Suppress("UNUSED_PARAMETER") rowNumber: Int,
        ): Client =
            Client(
                id = row.getObject("id", UUID::class.java),
                firstName = row.getString("first_name"),
                lastName = row.getString("last_name"),
                email = row.getString("email"),
                description = row.getString("description"),
                socialLinks = socialLinks(row),
            )

        private fun socialLinks(row: ResultSet): List<String> {
            val array = row.getArray("social_links") ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            return (array.array as Array<String?>).filterNotNull()
        }
    }
}
