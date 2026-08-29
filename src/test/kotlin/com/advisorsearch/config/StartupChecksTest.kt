package com.advisorsearch.config

import com.advisorsearch.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A corpus encoded by a model other than the configured one must stop the instance, for either
 * model. Transactional, so the foreign chunk each test plants rolls back before the next class runs.
 */
@Transactional
class StartupChecksTest(
    private val startupChecks: StartupChecks,
    private val jdbc: JdbcClient,
) : IntegrationTest() {
    @Test
    fun `a matching corpus passes and both models warm up`() {
        startupChecks.run(DefaultApplicationArguments())
    }

    @Test
    fun `a chunk from another dense model stops the instance`() {
        plantChunk(embeddingModel = "some-other-embedding-model", sparseModel = "opensearch-neural-sparse-encoding-doc-v2-mini")

        val failure = assertFailsWith<IllegalStateException> { startupChecks.run(DefaultApplicationArguments()) }

        assertTrue("some-other-embedding-model" in failure.message!!, failure.message)
    }

    @Test
    fun `a chunk from another sparse model stops the instance`() {
        plantChunk(embeddingModel = "all-MiniLM-L6-v2", sparseModel = "some-other-sparse-model")

        val failure = assertFailsWith<IllegalStateException> { startupChecks.run(DefaultApplicationArguments()) }

        assertTrue("some-other-sparse-model" in failure.message!!, failure.message)
    }

    private fun plantChunk(
        embeddingModel: String,
        sparseModel: String,
    ) {
        val clientId =
            jdbc
                .sql("INSERT INTO clients (first_name, last_name, email) VALUES ('Startup', 'Probe', :e) RETURNING id")
                .param("e", "startup.${UUID.randomUUID()}@example.com")
                .query(UUID::class.java)
                .single()
        val documentId =
            jdbc
                .sql("INSERT INTO documents (client_id, title, content) VALUES (:c, 'Probe', 'Probe content.') RETURNING id")
                .param("c", clientId)
                .query(UUID::class.java)
                .single()
        val zeros = (1..384).joinToString(",", prefix = "[", postfix = "]") { "0" }
        jdbc
            .sql(
                """
                INSERT INTO document_chunks (document_id, chunk_index, content, embedding, embedding_model, sparse_embedding, sparse_model)
                VALUES (:d, 0, 'probe', CAST(:v AS vector), :em, CAST('{1:1}/30522' AS sparsevec), :sm)
                """.trimIndent(),
            ).param("d", documentId)
            .param("v", zeros)
            .param("em", embeddingModel)
            .param("sm", sparseModel)
            .update()
    }
}
