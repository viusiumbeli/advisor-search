package com.advisorsearch.embedding

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbedderTest {
    private val embedder = TestModel.embedder

    @Test
    fun `produces 384 unit-length dimensions`() {
        val vector = embedder.embed("Quarterly portfolio review for the Whitfield family trust.")

        assertEquals(384, vector.size)
        val length = kotlin.math.sqrt(vector.fold(0.0) { acc, value -> acc + value * value })
        assertTrue(abs(length - 1.0) < 1e-5, "expected unit length, got $length")
    }

    @Test
    fun `utility bill is closer to address proof than to an unrelated document`() {
        val query = embedder.embed("address proof")
        val utilityBill =
            embedder.embed(
                "Electricity bill for 14 Marlow Court, London. Billing period 01 March to 31 March. " +
                    "This statement shows the supply address and the amount due for the period.",
            )
        val unrelated =
            embedder.embed(
                "Minutes of the investment committee discussing the tactical overweight to " +
                    "emerging market equities and the rebalancing schedule for the next quarter.",
            )

        val toBill = cosine(query, utilityBill)
        val toUnrelated = cosine(query, unrelated)
        assertTrue(
            toBill > toUnrelated,
            "utility bill should win: address proof~bill=$toBill, address proof~unrelated=$toUnrelated",
        )
    }

    @Test
    fun `batch embedding matches single embedding`() {
        val texts = listOf("passport copy", "a much longer text about anti money laundering checks and identity")
        val batched = embedder.embedAll(texts)
        val individually = texts.map { embedder.embed(it) }

        // Padding in a mixed-length batch must not leak into the shorter text's vector.
        texts.indices.forEach { index ->
            assertTrue(
                cosine(batched[index], individually[index]) > 0.9999,
                "batch and single embedding diverged for '${texts[index]}'",
            )
        }
    }

    @Test
    fun `token counter reports true length beyond the encoder window`() {
        val long = (1..400).joinToString(" ") { "compliance" }

        // The counter must not saturate at the window: the chunker relies on it to detect oversize
        // text, which is exactly the failure mode of a tokenizer that truncates silently.
        assertTrue(TestModel.tokenizer.count(long) > 256, "counter saturated at the encoder window")
        assertTrue(TestModel.tokenizer.exceedsWindow(long))
        assertTrue(!TestModel.tokenizer.exceedsWindow("a short sentence"))
    }
}
