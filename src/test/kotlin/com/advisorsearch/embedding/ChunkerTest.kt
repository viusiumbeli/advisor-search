package com.advisorsearch.embedding

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chunker is measured with the real tokenizer, because a budget checked with a different
 * tokenizer than the encoder uses is not a budget.
 */
class ChunkerTest {
    private val tokenizer = TestModel.tokenizer
    private val chunker = Chunker(tokenizer, budgetTokens = 200, overlapTokens = 30)

    @Test
    fun `short content stays a single chunk`() {
        val content = "A one paragraph note about the client's ISA subscription for the year."

        assertEquals(listOf(content), chunker.chunk(content))
    }

    @Test
    fun `every chunk fits the budget measured by the real tokenizer`() {
        val chunks = chunker.chunk(longDocument())

        assertTrue(chunks.size > 1, "expected the document to be split")
        chunks.forEach { chunk ->
            val tokens = tokenizer.count(chunk)
            assertTrue(tokens <= 200, "chunk of $tokens tokens exceeded the 200 token budget: ${chunk.take(80)}")
        }
    }

    @Test
    fun `consecutive chunks overlap`() {
        val chunks = chunker.chunk(longDocument())

        // The tail of one chunk should reappear at the head of the next, so a passage that straddles
        // a boundary is still whole somewhere.
        val overlaps =
            chunks.zipWithNext().count { (first, second) ->
                val tail = first.takeLast(60)
                second.contains(tail.substringAfter(' ').trim())
            }
        assertTrue(overlaps > 0, "no overlap found between any consecutive chunks")
    }

    @Test
    fun `breaks at a sentence boundary rather than mid word`() {
        val sentences = (1..60).joinToString(" ") { "Sentence number $it explains the fee arrangement in detail." }

        val chunks = chunker.chunk(sentences)

        assertTrue(chunks.size > 1)
        chunks.dropLast(1).forEach { chunk ->
            assertTrue(chunk.trimEnd().endsWith('.'), "chunk did not end at a sentence: ...${chunk.takeLast(40)}")
        }
    }

    @Test
    fun `content with no boundaries falls back to a hard window split`() {
        // No full stops, no blank lines: paragraph and sentence splitting both find nothing, and
        // without the hard-window fallback this would be handed to the encoder and truncated.
        val wall = (1..900).joinToString(" ") { "token$it" }

        val chunks = chunker.chunk(wall)

        assertTrue(chunks.size > 1, "expected the wall of text to be split")
        chunks.forEach { chunk ->
            assertTrue(tokenizer.count(chunk) <= 200, "hard split produced an oversize chunk")
        }
        // Nothing may be dropped between the windows: every word of the source survives somewhere.
        val combined = chunks.joinToString(" ")
        val missing = (1..900).map { "token$it" }.filterNot { combined.contains("$it ") || combined.endsWith(it) }
        assertTrue(missing.isEmpty(), "hard split lost ${missing.size} words, first few: ${missing.take(5)}")
    }

    @Test
    fun `a single unbroken run of characters is still split and nothing is lost`() {
        // WordPiece maps any run over a hundred characters to one unknown token, so this text
        // satisfies a token-only budget while being 6000 characters long. The character ceiling is
        // what stops it from being stored as one useless chunk.
        val unbroken = "x".repeat(6_000)

        val chunks = chunker.chunk(unbroken)

        assertTrue(chunks.size > 1, "expected the run to be split, got ${chunks.size} chunk(s)")
        chunks.forEach { assertTrue(tokenizer.count(it) <= 200) }
        chunks.forEach { assertTrue(it.all { character -> character == 'x' }) }
        // At least the original length, because overlapping chunks repeat text by design.
        assertTrue(chunks.sumOf { it.length } >= unbroken.length)
    }

    @Test
    fun `unicode content survives chunking`() {
        val content =
            (1..40).joinToString("\n\n") {
                "Paragraphe $it. Béatrice Moreau a vendu sa résidence secondaire à Bath. " +
                    "Tomasz Wiśniewski — 日本語のテキスト — résumé, naïve, façade."
            }

        val chunks = chunker.chunk(content)

        assertTrue(chunks.size > 1)
        assertContains(chunks.joinToString(" "), "Wiśniewski")
        assertContains(chunks.joinToString(" "), "日本語")
    }

    @Test
    fun `every chunk is an exact substring of the document`() {
        // Snippets and the extractive summary are served straight from chunk text, so a chunk that
        // silently normalised whitespace would quote the document inaccurately.
        val content =
            "Schedule of holdings.\nHarleston Mutual Personal Pension, policy 12345.\n" +
                "Pelham & Wick With-Profits Plan, policy 67890.\n\n" +
                (1..30).joinToString("\n\n") { "Paragraph $it sets out the charges and the agreed actions." }

        chunker.chunk(content).forEach { chunk ->
            assertTrue(content.contains(chunk), "chunk is not verbatim: ${chunk.take(70)}…")
        }
    }

    @Test
    fun `blank content produces no chunks`() {
        assertEquals(emptyList(), chunker.chunk("   \n\n  "))
    }

    private fun longDocument(): String =
        (1..25).joinToString("\n\n") { paragraph ->
            (1..6).joinToString(" ") { sentence ->
                "Paragraph $paragraph sentence $sentence discusses the drawdown strategy and the " +
                    "sustainability of the withdrawal rate across the retirement period."
            }
        }
}
