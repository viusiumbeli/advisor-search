package com.advisorsearch.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.ReadinessState
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import kotlin.time.Duration.Companion.seconds

/**
 * Holds API traffic until the instance is genuinely ready.
 *
 * Tomcat accepts connections as soon as the context refreshes, which is *before* the startup checks
 * and the seed runner have run: the published port answers while the corpus is still being
 * embedded, and — the case that does lasting damage — a `POST` landing in that window can commit
 * vectors from one model into a corpus built by another, moments before the check that exists to
 * catch exactly that fails the instance. Readiness flips only once every runner has returned
 * ([StartupChecks] first, then seeding), so gating on it closes the window with no flag of this
 * filter's own to keep correct.
 *
 * It runs ahead of [ApiKeyFilter]: whether the instance can serve at all is answerable before
 * whether this caller may, and `/actuator/health/readiness` says the same thing to anyone who asks.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
class ReadinessFilter(
    private val availability: ApplicationAvailability,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.isPublicSurface()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (availability.readinessState == ReadinessState.ACCEPTING_TRAFFIC) {
            filterChain.doFilter(request, response)
            return
        }
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER.inWholeSeconds.toString())
        response.sendProblem(
            objectMapper,
            HttpStatus.SERVICE_UNAVAILABLE,
            "Not ready",
            "The instance is still starting: the embedding models are being verified and warmed up. " +
                "Poll /actuator/health/readiness.",
        )
    }

    private companion object {
        /** Startup is a few seconds unseeded and a minute or two with a corpus to embed. */
        val RETRY_AFTER = 5.seconds
    }
}
