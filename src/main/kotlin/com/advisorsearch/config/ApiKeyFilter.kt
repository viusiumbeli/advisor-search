package com.advisorsearch.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

private val log = LoggerFactory.getLogger(ApiKeyFilter::class.java)

private const val HEADER = "X-API-Key"

/**
 * Shared-secret header check for the deployed instance. An empty `api.key` — the compose default —
 * disables it, so running locally needs no credentials. A shared key, not a user identity system;
 * see docs/operating-notes.md, "Authentication".
 */
@Component
class ApiKeyFilter(
    properties: ApiProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val enabled = properties.key.isNotBlank()

    // Comparing fixed-width digests instead of the raw strings keeps MessageDigest.isEqual
    // constant-time without leaking the key's length the way a bare isEqual on unequal-length
    // inputs would (it short-circuits on a length mismatch).
    private val expectedDigest = sha256(properties.key.trim())

    init {
        log.info(if (enabled) "API key authentication is enabled" else "API key authentication is disabled")
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !enabled || request.isPublicSurface()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val presented = request.getHeader(HEADER)
        if (presented == null || !MessageDigest.isEqual(sha256(presented), expectedDigest)) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, HEADER)
            response.sendProblem(objectMapper, HttpStatus.UNAUTHORIZED, "Unauthorized", "Provide a valid $HEADER header.")
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
}
