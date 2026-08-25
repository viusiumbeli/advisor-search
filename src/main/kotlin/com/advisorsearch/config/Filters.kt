package com.advisorsearch.config

import com.advisorsearch.support.problem
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.ObjectMapper

/**
 * The two servlet filters guard the same surface, so it is described once here.
 *
 * These paths carry no client data: the health probes an orchestrator polls, the API documentation,
 * and the console page — a browser cannot attach a header to a plain navigation, and a probe that
 * waited for readiness could never report readiness. Everything else is data and is filtered.
 */
private val PUBLIC_PREFIXES = listOf("/actuator/", "/swagger-ui/", "/v3/api-docs")
private val PUBLIC_PATHS = listOf("/swagger-ui.html", "/", "/index.html")

internal fun HttpServletRequest.isPublicSurface(): Boolean {
    val path = requestURI
    return path in PUBLIC_PATHS || PUBLIC_PREFIXES.any(path::startsWith)
}

/**
 * A refusal from the filter chain, in the RFC 9457 shape the controllers' own errors use: a caller
 * that parses `problem+json` should not need a second branch for the failures that happen before a
 * controller is reached.
 */
internal fun HttpServletResponse.sendProblem(
    objectMapper: ObjectMapper,
    status: HttpStatus,
    title: String,
    detail: String,
) {
    this.status = status.value()
    contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    characterEncoding = Charsets.UTF_8.name()
    objectMapper.writeValue(outputStream, problem(status, title, detail))
}
