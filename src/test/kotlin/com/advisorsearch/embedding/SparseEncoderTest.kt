package com.advisorsearch.embedding

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SparseEncoderTest {
    private val encoder = TestModel.sparseEncoder
    private val idf = TestModel.idfTable

    private val passage =
        "Electricity account statement for 14 Marlow Court: meter readings, unit rate and the direct debit collected on 1 March."

    @Test
    fun `a passage becomes ascending positive terms with no specials`() {
        val vector = encoder.encode(passage)

        assertTrue(vector.termCount in 1..1000, "got ${vector.termCount} terms")
        for (position in 1 until vector.termCount) assertTrue(vector.indices[position] > vector.indices[position - 1])
        assertTrue(vector.weights.all { it > 0f })
        assertTrue(vector.indices.none { it in idf.specialTokenIds }, "special tokens must be zeroed")
        assertTrue(vector.indices.all { it in 0 until SPARSE_DIMENSIONS })
    }

    /**
     * Parity with the official PyTorch checkpoint. The reference values come from the model card's
     * own transformers snippet on `opensearch-project/opensearch-neural-sparse-encoding-doc-v2-mini`
     * at revision 4af867a4 — `AutoModelForMaskedLM`, `max(logits * mask, dim=1)`, `log(1 + relu)`,
     * special-token dimensions zeroed — for exactly [passage]. (Not sentence-transformers'
     * `SparseEncoder.encode_document`, which does not zero the specials and so differs by ~0.01.)
     * This is what makes the third-party ONNX export trustworthy: same weights, same graph.
     */
    @Test
    fun `reproduces the checkpoint's published vector`() {
        val vector = encoder.encode(passage)
        val byWeight = vector.indices.indices.sortedByDescending { vector.weights[it] }
        val top = byWeight.take(5).map { TestModel.vocabulary[vector.indices[it]] to vector.weights[it].toDouble() }

        val expected = listOf("electricity" to 0.97512, "court" to 0.87669, "mar" to 0.82972, "when" to 0.8088, "electrical" to 0.80754)
        assertEquals(expected.map { it.first }, top.map { it.first }, "top terms differ: $top")
        expected.zip(top).forEach { (want, got) -> assertTrue(abs(want.second - got.second) < 1e-3, "$want but was $got") }
        assertTrue(abs(vector.termCount - 151) <= 2, "reference has 151 non-zeros, got ${vector.termCount}")
    }

    @Test
    fun `batch encoding matches single encoding`() {
        val texts =
            listOf(
                "passport copy",
                passage,
                "a much longer text about anti money laundering checks, identity verification and the documents an adviser must keep on file",
                "ISA transfer",
            )
        val batched = encoder.encodeAll(texts)
        val individually = texts.map { encoder.encode(it) }

        // Under max pooling a padded position would not dilute a vector, it would inflate it: any
        // term the pad rows activate would win the max. Identical term sets prove the mask holds.
        texts.indices.forEach { index ->
            assertContentEquals(individually[index].indices, batched[index].indices, "term sets diverged for '${texts[index]}'")
            individually[index].weights.zip(batched[index].weights).forEach { (single, batch) ->
                assertTrue(abs(single - batch) < 1e-4, "weights diverged for '${texts[index]}'")
            }
        }
    }

    @Test
    fun `a query is its distinct wordpieces weighted by the table`() {
        val vector = idf.weigh(TestModel.tokenizer.tokenIds("proof of address"))

        // proof=6947, of=1997, address=4769, ascending; weights straight from idf.json.
        assertContentEquals(intArrayOf(1997, 4769, 6947), vector.indices)
        listOf(0.29789, 4.88596, 6.206).zip(vector.weights.toList()).forEach { (want, got) ->
            assertTrue(abs(want - got) < 1e-4, "$want vs $got")
        }
        assertTrue(vector.weights[0] < vector.weights[1], "a stopword must weigh less than a content word")
    }

    @Test
    fun `repeated words weigh once and specials weigh nothing`() {
        val ids = TestModel.tokenizer.tokenIds("address address address")

        assertEquals(1, idf.weigh(ids).termCount)
        assertTrue(idf.weigh(longArrayOf(101, 102)).isEmpty())
    }
}
