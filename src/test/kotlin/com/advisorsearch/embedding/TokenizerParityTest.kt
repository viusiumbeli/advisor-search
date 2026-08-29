package com.advisorsearch.embedding

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

/**
 * One tokenizer serves both models, so the sparse checkpoint's own tokenizer.json must produce the
 * very same ids as the dense model's — on text, not just by comparing vocabularies. The two files
 * differ only in their truncation and padding metadata (the dense one says 128, the sparse one 512),
 * and neither binds here: DJL takes the window from `optMaxLength`, and its `modelMaxLength` guard
 * defaults to 512 when no tokenizer_config.json is beside the file — which is why provisionModel
 * deliberately does not fetch one.
 */
class TokenizerParityTest {
    private val texts =
        listOf(
            "jane.roe@aldgatewealth.example",
            "PLC-88213",
            "anti-money-laundering checks",
            "Tomasz Wiśniewski, Kraków",
            "meter readings 📊 and unit rates",
            (1..300).joinToString(" ") { "compliance" },
        )

    @Test
    fun `the sparse checkpoint's tokenizer yields the dense model's ids`() {
        WordPieceTokenizer(TestModel.sparseTokenizerPath, maxTokens = 256).use { sparse ->
            texts.forEach { text ->
                assertContentEquals(TestModel.tokenizer.tokenIds(text), sparse.tokenIds(text), "ids diverged for '${text.take(40)}'")
            }
        }
    }
}
