package com.advisorsearch.experiments

import com.advisorsearch.embedding.Chunker
import com.advisorsearch.embedding.IdfTable
import com.advisorsearch.embedding.SPARSE_DIMENSIONS
import com.advisorsearch.embedding.SparseEncoder
import com.advisorsearch.embedding.SparseVector
import com.advisorsearch.embedding.WordPieceTokenizer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.time.measureTimedValue

/**
 * The experiment behind the sparse arm's checkpoint: it chunks the seeded documents exactly as ingest
 * does, encodes them with each candidate, and ranks every document for the evaluation queries —
 * printing ranks, raw and mass-normalised inner products, non-zeros per chunk, timings and the
 * expansion terms two chunks actually receive.
 *
 * Not part of the suite's guarantees: it passes as a no-op unless the candidates are downloaded,
 * because they are 140 MB and 530 MB and only needed to reproduce the measurement:
 *
 *   ./gradlew test --tests '*SparseModelExperiment' -i -Dsparse-candidates=/path/containing/docv2mini,spladepp
 *
 * `docv2mini/` holds opensearch-neural-sparse-encoding-doc-v2-mini's model.onnx (the seerware fp32
 * export), tokenizer.json and idf.json; `spladepp/` holds Qdrant/Splade_PP_en_v1's model.onnx and
 * tokenizer.json. The result is reported in docs/search-design.md under "Sparse".
 */
class SparseModelExperiment {
    private val mapper = JsonMapper()

    private val candidates =
        listOf(
            SparseCandidate("opensearch-neural-sparse-encoding-doc-v2-mini", "docv2mini", QueryMode.STATIC_IDF, zeroSpecials = true),
            SparseCandidate("Splade_PP_en_v1", "spladepp", QueryMode.MLM, zeroSpecials = false),
        )

    @Test
    @EnabledIfSystemProperty(named = "sparse-candidates", matches = ".+")
    fun `compare sparse candidates on the evaluation queries`() {
        val root = Path.of(System.getProperty("sparse-candidates"))
        val corpus = seedDocuments()
        val golden = goldenDocumentQueries()
        candidates.forEach { candidate -> evaluate(root.resolve(candidate.dir), candidate, corpus, golden) }
    }

    private fun evaluate(
        dir: Path,
        candidate: SparseCandidate,
        corpus: List<SeedText>,
        golden: List<Pair<String, String>>,
    ) {
        val modelPath = dir.resolve("model.onnx")
        if (!modelPath.toFile().isFile) return println("skipped ${candidate.name}: no model at $modelPath")
        val tokenizerPath = dir.resolve("tokenizer.json")
        val vocabulary = vocabulary(tokenizerPath)
        val specials = if (candidate.zeroSpecials) specialTokenIds(tokenizerPath) else IntArray(0)

        WordPieceTokenizer(tokenizerPath, maxTokens = 512).use { tokenizer ->
            val idf =
                if (candidate.queryMode == QueryMode.STATIC_IDF) {
                    IdfTable.load(dir.resolve("idf.json"), tokenizerPath, Path.of("models/tokenizer.json"), mapper)
                } else {
                    null
                }
            SparseEncoder(modelPath, tokenizer, candidate.name, specials, maxTerms = SPARSE_DIMENSIONS).use { encoder ->
                val chunker = Chunker(tokenizer, budgetTokens = 200, overlapTokens = 30)
                println("\n=== ${candidate.name} (${candidate.queryMode}) ===")

                val (encoded, encodeTime) =
                    measureTimedValue {
                        corpus.map { document ->
                            val chunks = chunker.chunk(document.text)
                            EncodedDocument(document, chunks, encoder.encodeAll(chunks.map { "Title: ${document.title}\n\n$it" }))
                        }
                    }
                val chunkCount = encoded.sumOf { it.chunks.size }
                val nonZeros = encoded.flatMap { it.vectors }.map { it.termCount }.sorted()
                println(
                    "%d chunks encoded in %d ms (%.1f ms/chunk, batches of 4); non-zeros per chunk min %d, median %d, p99 %d, max %d"
                        .format(
                            chunkCount,
                            encodeTime.inWholeMilliseconds,
                            encodeTime.inWholeMilliseconds.toDouble() / chunkCount,
                            nonZeros.first(),
                            nonZeros[nonZeros.size / 2],
                            nonZeros[minOf(nonZeros.lastIndex, nonZeros.size * 99 / 100)],
                            nonZeros.last(),
                        ),
                )

                fun encodeQuery(query: String): SparseVector = idf?.weigh(tokenizer.tokenIds(query)) ?: encoder.encode(query)

                fun ranked(query: SparseVector): List<Pair<EncodedDocument, Double>> =
                    encoded
                        .map { document -> document to (document.vectors.maxOfOrNull(query::dot) ?: 0.0) }
                        .sortedWith(compareByDescending<Pair<EncodedDocument, Double>> { it.second }.thenBy { it.first.seed.title })

                fun report(
                    query: String,
                    expectedTitle: String?,
                ): Int {
                    val (vector, queryTime) = measureTimedValue { encodeQuery(query) }
                    val ranking = ranked(vector)
                    val rank =
                        expectedTitle?.let { title ->
                            ranking.indexOfFirst {
                                it.first.seed.title
                                    .contains(title, ignoreCase = true)
                            } + 1
                        }
                            ?: 0
                    val expectedScore = if (rank > 0) ranking[rank - 1].second else 0.0
                    val best = ranking.first().second
                    val mass = vector.mass
                    println(
                        "%-46s rank %-3s raw %7.3f norm %6.3f | best raw %7.3f norm %6.3f | %3d terms %3d ms | top: %s".format(
                            "\"$query\"",
                            if (rank > 0) "$rank" else "-",
                            expectedScore,
                            if (mass > 0) expectedScore / mass else 0.0,
                            best,
                            if (mass > 0) best / mass else 0.0,
                            vector.termCount,
                            queryTime.inWholeMilliseconds,
                            ranking
                                .first()
                                .first.seed.title
                                .take(44),
                        ),
                    )
                    return rank
                }

                println("--- golden document queries (rank of the expected document, sparse arm alone) ---")
                var hits = 0
                var reciprocalRankSum = 0.0
                golden.forEach { (query, expect) ->
                    val rank = report(query, expect)
                    if (rank in 1..5) hits++
                    if (rank > 0) reciprocalRankSum += 1.0 / rank
                }
                println("golden: hit@5 = $hits/${golden.size}   MRR = %.3f".format(reciprocalRankSum / golden.size))

                println("--- the dense experiment's probes, by expected file ---")
                PROBES.forEach { (query, expectedFile) ->
                    val ranking = ranked(encodeQuery(query))
                    val rank =
                        ranking.indexOfFirst {
                            it.first.seed.file
                                .startsWith(expectedFile)
                        } + 1
                    println(
                        "%-46s rank %-3s top: %s".format(
                            "\"$query\"",
                            if (rank >
                                0
                            ) {
                                "$rank"
                            } else {
                                "-"
                            },
                            ranking
                                .first()
                                .first.seed.title
                                .take(44),
                        ),
                    )
                }

                println("--- sparse diagnostics: partial-term queries ---")
                SPARSE_DIAGNOSTIC_PROBES.forEach { (query, expect) -> report(query, expect) }

                println("--- nonsense: the floor has to sit above these ---")
                NONSENSE_PROBES.forEach { report(it, null) }

                println("--- lexical: proper nouns and a reference code ---")
                LEXICAL_PROBES.forEach { report(it, null) }
                val code = "PLC-88213"
                val codeRanking = ranked(encodeQuery(code))
                println(
                    "\"$code\" tokenises to ${tokenizer.tokenIds(code).map { vocabulary[it.toInt()] }}; " +
                        "Policy Schedule %.3f vs Trust Deed %.3f".format(
                            codeRanking
                                .first {
                                    it.first.seed.title
                                        .contains("Policy Schedule")
                                }.second,
                            codeRanking
                                .first {
                                    it.first.seed.title
                                        .contains("Trust Deed")
                                }.second,
                        ),
                )

                println("--- expansion terms ---")
                encoded.firstOrNull { it.seed.title.contains("CRS") }?.let { crs ->
                    val index = crs.chunks.indexOfFirst { it.contains("double taxation", ignoreCase = true) }
                    if (index >=
                        0
                    ) {
                        printTerms(
                            "CRS chunk with 'double taxation'",
                            crs.vectors[index],
                            vocabulary,
                            listOf("treaty", "agreement", "tax"),
                        )
                    }
                }
                encoded.firstOrNull { it.seed.title.contains("Electricity") }?.let { bill ->
                    printTerms(
                        "electricity bill, first chunk",
                        bill.vectors.first(),
                        vocabulary,
                        listOf("utility", "bill", "proof", "address", "residence"),
                    )
                }
            }
        }
    }

    private fun printTerms(
        label: String,
        vector: SparseVector,
        vocabulary: Array<String>,
        watched: List<String>,
    ) {
        val byWeight = vector.indices.indices.sortedByDescending { vector.weights[it] }
        val top = byWeight.take(12).joinToString(", ") { "${vocabulary[vector.indices[it]]} %.2f".format(vector.weights[it]) }
        val present =
            watched.joinToString(", ") { word ->
                val position = vector.indices.indexOfFirst { vocabulary[it] == word }
                if (position >= 0) "$word=%.2f".format(vector.weights[position]) else "$word=absent"
            }
        println("$label (${vector.termCount} terms): $top")
        println("    watched: $present")
    }

    private fun seedDocuments(): List<SeedText> {
        val corpus = mapper.readTree(Path.of("src/main/resources/seed/corpus.json"))
        return corpus
            .path("clients")
            .values()
            .flatMap { client -> client.path("documents").values() }
            .map { document ->
                val file = document.path("file").asString()
                SeedText(document.path("title").asString(), file, Path.of("src/main/resources/seed/documents/$file").readText())
            }.sortedBy { it.file }
    }

    private fun goldenDocumentQueries(): List<Pair<String, String>> =
        mapper
            .readTree(Path.of("src/test/resources/golden-queries.json"))
            .path("documents")
            .values()
            .map { it.path("query").asString() to it.path("expect").asString() }

    private fun vocabulary(tokenizerPath: Path): Array<String> {
        val vocabulary = Array(SPARSE_DIMENSIONS) { "" }
        for ((token, id) in mapper
            .readTree(tokenizerPath)
            .path("model")
            .path("vocab")
            .properties()) {
            vocabulary[id.asInt()] = token
        }
        return vocabulary
    }

    private fun specialTokenIds(tokenizerPath: Path): IntArray =
        mapper
            .readTree(tokenizerPath)
            .path("added_tokens")
            .values()
            .filter { it.path("special").booleanValue(false) }
            .map { it.path("id").asInt() }
            .sorted()
            .toIntArray()
}

/** A seeded document as the experiment sees it: its manifest entry plus the text on disk. */
internal data class SeedText(
    val title: String,
    val file: String,
    val text: String,
)

/** One document after encoding: the production chunking and one sparse vector per chunk. */
internal data class EncodedDocument(
    val seed: SeedText,
    val chunks: List<String>,
    val vectors: List<SparseVector>,
)
