package com.advisorsearch.clients

import com.advisorsearch.IntegrationTest
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Transactional for the reason given on SchemaConstraintTest: the suite shares one database, and a
 * client left behind here is a client competing for a place in another class's result page. MockMvc
 * calls the controller on the test's own thread, so the writes made through the API join this
 * transaction and roll back with it.
 */
@Transactional
class ClientApiTest(
    private val mockMvc: MockMvc,
) : IntegrationTest() {
    @Test
    fun `creates a client and returns it at the Location header`() {
        val email = "location.roundtrip.${UUID.randomUUID()}@example.com"
        val location =
            mockMvc
                .post("/clients") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """
                            {"first_name":"Ada","last_name":"Lovelace","email":"$email",
                             "description":"Analytical engine enthusiast",
                             "social_links":["https://example.com/ada"]}
                        """
                }.andExpect {
                    status { isCreated() }
                    jsonPath("$.id") { exists() }
                    jsonPath("$.first_name") { value("Ada") }
                    jsonPath("$.social_links[0]") { value("https://example.com/ada") }
                }.andReturn()
                .response
                .getHeader("Location")!!

        mockMvc.get(location).andExpect {
            status { isOk() }
            jsonPath("$.email") { value(email) }
            jsonPath("$.last_name") { value("Lovelace") }
        }
    }

    @Test
    fun `rejects an invalid body with per-field messages`() {
        mockMvc
            .post("/clients") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"first_name":"","last_name":"Doe","email":"not-an-email"}"""
            }.andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errors.firstName") { exists() }
                jsonPath("$.errors.email") { exists() }
            }
    }

    @Test
    fun `rejects a name of Unicode whitespace`() {
        // The escape is JSON's, not Kotlin's — a raw string leaves it alone — so the body carries a
        // real non-breaking space. It passes @NotBlank and would reach the insert as "".
        mockMvc
            .post("/clients") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"first_name":"\u00a0","last_name":"Doe","email":"nbsp.${UUID.randomUUID()}@example.com"}"""
            }.andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.detail") { value(containsString("first_name")) }
            }
    }

    @Test
    fun `rejects a duplicate email with 409`() {
        val email = "duplicate.${UUID.randomUUID()}@example.com"
        val body = """{"first_name":"First","last_name":"Owner","email":"$email"}"""

        mockMvc
            .post("/clients") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isCreated() } }

        // Case is not part of the identity: advisors search by address, not by capitalisation.
        mockMvc
            .post("/clients") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"first_name":"Second","last_name":"Owner","email":"${email.uppercase()}"}"""
            }.andExpect {
                status { isConflict() }
                jsonPath("$.title") { value("Email already registered") }
            }
    }

    @Test
    fun `returns 404 for an unknown client`() {
        mockMvc.get("/clients/${UUID.randomUUID()}").andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }
}
