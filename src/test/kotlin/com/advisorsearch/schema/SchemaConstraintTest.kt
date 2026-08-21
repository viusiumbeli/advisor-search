package com.advisorsearch.schema

import com.advisorsearch.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The schema is the backstop for any writer that is not the API — a migration, a bulk import, an
 * ad-hoc psql session. These tests bypass the API on purpose and prove the database itself rejects
 * what the DTO validation would have rejected, so the invariants hold even for code that has never
 * heard of `@Size`.
 *
 * Transactional so every probe row rolls back: the suite shares one database, and a leftover
 * client whose profile happens to contain a search test's substring would change that test's
 * results. That is not hypothetical — the original non-transactional version displaced a
 * two-character-query result with its own residue.
 */
@Transactional
class SchemaConstraintTest
    @Autowired
    constructor(
        private val jdbc: JdbcClient,
    ) : IntegrationTest() {
        @Test
        fun `a first name beyond 200 characters is rejected by the column type`() {
            assertFailsWith<DataIntegrityViolationException> {
                insertClient(firstName = "x".repeat(201))
            }
        }

        @Test
        fun `a blank first name is rejected by a check constraint`() {
            assertFailsWith<DataIntegrityViolationException> {
                insertClient(firstName = "   ")
            }
        }

        @Test
        fun `an email beyond 320 characters is rejected by the column type`() {
            assertFailsWith<DataIntegrityViolationException> {
                insertClient(email = "a".repeat(310) + "@example.com")
            }
        }

        @Test
        fun `a blank document title is rejected by a check constraint`() {
            val clientId = insertClient()

            assertFailsWith<DataIntegrityViolationException> {
                insertDocument(clientId, title = "  ", content = "Some content.")
            }
        }

        @Test
        fun `document content beyond the 100000 character ceiling is rejected`() {
            val clientId = insertClient()

            assertFailsWith<DataIntegrityViolationException> {
                insertDocument(clientId, title = "Too long", content = "x".repeat(100_001))
            }
        }

        @Test
        fun `values exactly at the bounds are accepted`() {
            // The limits are inclusive: a 200-character name and 100000-character content are legal.
            val clientId = insertClient(firstName = "x".repeat(200))
            insertDocument(clientId, title = "At the limit", content = "y".repeat(100_000))
        }

        @Test
        fun `default-generated ids are UUIDv7`() {
            // Pins that the column default really is uuidv7(): time-ordered ids keep primary-key
            // inserts append-mostly. A future image bump that silently fell back to v4 fails here.
            val id = insertClient()
            val versionNibble =
                jdbc
                    .sql("SELECT substring(id::text, 15, 1) FROM clients WHERE id = :id")
                    .param("id", id)
                    .query(String::class.java)
                    .single()

            assertEquals("7", versionNibble)
        }

        /** Raw insert, deliberately not going through ClientRepository: the API must not be needed. */
        private fun insertClient(
            firstName: String = "Schema",
            email: String = "schema.${UUID.randomUUID()}@example.com",
        ): UUID =
            jdbc
                .sql("INSERT INTO clients (first_name, last_name, email) VALUES (:f, 'Probe', :e) RETURNING id")
                .param("f", firstName)
                .param("e", email)
                .query(UUID::class.java)
                .single()

        private fun insertDocument(
            clientId: UUID,
            title: String,
            content: String,
        ) {
            jdbc
                .sql("INSERT INTO documents (client_id, title, content) VALUES (:c, :t, :b)")
                .param("c", clientId)
                .param("t", title)
                .param("b", content)
                .update()
        }
    }
