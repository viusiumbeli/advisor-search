package com.advisorsearch.embedding

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Measured with the real tokenizer: a budget checked with a different one is not a budget. */
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
        // Short paragraphs on purpose. The carry-over budget is 30 tokens, so a document built from
        // 140-token paragraphs has nothing small enough to carry and overlaps by nothing at all —
        // and an assertion made on that document passes anyway, because its repetitive sentences
        // reappear in the next chunk whether they were carried there or written there.
        val content = (1..80).joinToString("\n\n") { "Point $it: the adviser confirmed the arrangement." }

        val chunks = chunker.chunk(content)

        assertTrue(chunks.size > 1, "expected the document to be split")
        chunks.zipWithNext().forEach { (first, second) ->
            val tail = first.split("\n\n").last()
            assertTrue(second.contains(tail), "a chunk's tail must reappear in the next one, missing: $tail")
        }
    }

    @Test
    fun `a chunk stays inside the budget when overlap is carried into it`() {
        // Two short paragraphs and then one sentence that nearly fills the budget by itself. The
        // long piece cannot be appended to the tail carried over from the chunk just emitted:
        // overlap plus a full-size piece lands past the budget, which is the silent truncation at
        // the encoder window that the budget exists to prevent.
        val content =
            "Note one: the fee basis was confirmed.\n\nNote two: the fee basis was confirmed.\n\n" +
                (1..194).joinToString(" ") { "review" } + "."

        val chunks = chunker.chunk(content)

        assertTrue(chunks.size > 1, "expected the document to be split")
        chunks.forEach { chunk ->
            val tokens = tokenizer.count(chunk)
            assertTrue(tokens <= 200, "chunk of $tokens tokens exceeded the 200 token budget: ${chunk.take(80)}")
        }
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
        // Every paragraph whole in some chunk, rather than the marker words present somewhere in
        // the joined output: the markers repeat forty times, so one surviving copy would satisfy
        // that while every boundary mangled its neighbours.
        content.split("\n\n").forEach { paragraph ->
            assertTrue(
                chunks.any { it.contains(paragraph) },
                "a paragraph did not survive whole: ${paragraph.take(40)}…",
            )
        }
    }

    @Test
    fun `a hard split never cuts a character in half`() {
        // No spaces and no sentence punctuation, so every cut is made by character count. Each
        // glyph here is a surrogate pair, and the leading 'x' puts the pairs on odd indices, so a
        // cut that ignores them lands between the halves of one.
        val wall = "x" + "🏦".repeat(2_000)

        val chunks = chunker.chunk(wall)

        assertTrue(chunks.size > 1, "expected the wall to be split")
        chunks.forEach { chunk ->
            // Half a surrogate pair survives as a Kotlin String but not as UTF-8, which is how it
            // would reach Postgres — the round trip is what makes the damage visible.
            assertEquals(
                chunk,
                String(chunk.toByteArray(Charsets.UTF_8), Charsets.UTF_8),
                "a chunk carries half a character",
            )
            assertTrue(wall.contains(chunk), "chunk is not verbatim")
        }
    }

    @Test
    fun `consecutive paragraph breaks are reproduced exactly`() {
        // Four newlines are two paragraph breaks with nothing between them. Dropping the empty
        // segment's separator would join the paragraphs with less whitespace than the document has.
        val content = (1..40).joinToString("\n\n\n\n") { "Paragraph $it of the schedule of charges." }

        chunker.chunk(content).forEach { chunk ->
            assertTrue(content.contains(chunk), "chunk is not verbatim: ${chunk.take(70)}…")
        }
    }

    @Test
    fun `whitespace before a paragraph break survives`() {
        // Two trailing spaces are a hard line break in markdown and ordinary in pasted text. The
        // paragraph is too long to fit, so it is split by sentence — and the sentence boundary
        // swallows exactly those two spaces on its way out of the paragraph.
        val long = (1..24).joinToString(" ") { "Sentence $it explains the fee arrangement in the schedule of charges." }
        val content = "$long  \n\nShort closing note."

        chunker.chunk(content).forEach { chunk ->
            assertTrue(content.contains(chunk), "chunk is not verbatim: …${chunk.takeLast(60)}")
        }
    }

    @Test
    fun `a long run of whitespace is not lost between two sentences`() {
        // Longer than the character ceiling, so it cannot be carried inside a piece and has to
        // survive as the gap between two of them.
        val content = "Opening note on the fee basis." + " ".repeat(3_000) + "Closing note on the same."

        val chunks = chunker.chunk(content)

        chunks.forEach { chunk -> assertTrue(content.contains(chunk), "chunk is not verbatim: ${chunk.take(40)}…") }
        assertTrue(
            chunks.any { it.contains("Opening note") } && chunks.any { it.contains("Closing note") },
            "both sentences must still be present, got ${chunks.size} chunk(s)",
        )
    }

    @Test
    fun `irregular whitespace survives a hard split`() {
        // A pasted table: nothing to break on, columns separated by tabs and by double spaces. Both
        // have to come back as they were, or a chunk stops being a substring of the document and
        // the snippet built from it misquotes the source.
        val content = (1..120).joinToString("  ") { "column$it\tvalue$it" }

        val chunks = chunker.chunk(content)

        assertTrue(chunks.size > 1, "expected the table to be split")
        chunks.forEach { chunk ->
            assertTrue(content.contains(chunk), "chunk is not verbatim: ${chunk.take(70)}…")
        }
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
