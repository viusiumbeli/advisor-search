package com.advisorsearch.clients

import com.advisorsearch.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

class ClientApiTest
    @Autowired
    constructor(
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
