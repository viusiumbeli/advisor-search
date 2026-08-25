package com.advisorsearch.embedding

import com.advisorsearch.support.wholeCharacterEnd

private val PARAGRAPH_BREAK = Regex("\\R[ \\t]*\\R")
private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")

/** Only a starting guess for the hard split; the tokenizer has the final say. */
private const val ESTIMATED_CHARS_PER_TOKEN = 4

/** No natural-language text reaches this density, so it only bites on degenerate input. */
private const val MAX_CHARS_PER_TOKEN = 12

/**
 * Splits document text into overlapping pieces that fit the encoder's window: paragraph boundaries,
 * then sentence, then a hard window. The last one matters — without it an unbroken block longer than
 * the window reaches the encoder and is silently truncated.
 *
 * Sizes are measured with the real tokenizer, plus a character ceiling: WordPiece maps any run over
 * a hundred characters to one unknown token, so a wall of text can satisfy a token-only budget while
 * being a useless chunk to store or show.
 */
class Chunker(
    private val tokenizer: WordPieceTokenizer,
    private val budgetTokens: Int,
    private val overlapTokens: Int,
) {
    private val maxChars = budgetTokens * MAX_CHARS_PER_TOKEN

    init {
        require(budgetTokens > 0) { "Chunk budget must be positive" }
        require(overlapTokens in 0..<budgetTokens) { "Overlap must be smaller than the chunk budget" }
    }

    fun chunk(content: String): List<String> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (fits(trimmed)) return listOf(trimmed)

        val pieces = split(trimmed)
        val chunks = mutableListOf<String>()
        var current = mutableListOf<ChunkPiece>()
        var tokens = 0
        var chars = 0

        for (piece in pieces) {
            if (current.isNotEmpty() && wouldExceed(tokens, chars, piece)) {
                chunks += render(current)
                current = carryOver(current)
                tokens = current.sumOf { it.tokens }
                chars = current.sumOf { it.text.length }
                // The carried tail is spent budget like anything else. Measuring a second time is
                // what stops a chunk being emitted at the budget plus the overlap: 230 tokens, plus
                // the title prepended at embedding time, is over the 256-token window and back into
                // the silent truncation the budget exists to prevent. Dropping the tail costs
                // nothing — it is whole in the chunk just emitted.
                if (current.isNotEmpty() && wouldExceed(tokens, chars, piece)) {
                    current = mutableListOf()
                    tokens = 0
                    chars = 0
                }
            }
            current += piece
            tokens += piece.tokens
            chars += piece.text.length
        }
        if (current.isNotEmpty()) chunks += render(current)
        return chunks
    }

    private fun wouldExceed(
        tokens: Int,
        chars: Int,
        piece: ChunkPiece,
    ): Boolean = tokens + piece.tokens > budgetTokens || chars + piece.text.length > maxChars

    private fun fits(text: String): Boolean = text.length <= maxChars && tokenizer.count(text) <= budgetTokens

    /**
     * Seeds the next chunk with the tail of the one just emitted, so a passage split across a
     * boundary is still whole in at least one chunk. Never carries the entire chunk over: that would
     * make no progress and loop forever.
     */
    private fun carryOver(emitted: List<ChunkPiece>): MutableList<ChunkPiece> {
        val carried = mutableListOf<ChunkPiece>()
        var tokens = 0
        for (piece in emitted.asReversed()) {
            if (carried.size == emitted.size - 1) break
            if (tokens + piece.tokens > overlapTokens) break
            carried.addFirst(piece)
            tokens += piece.tokens
        }
        return carried
    }

    private fun render(pieces: List<ChunkPiece>): String =
        buildString {
            pieces.forEachIndexed { index, piece ->
                if (index > 0) append(piece.separator)
                append(piece.text)
            }
        }

    /**
     * Locates the pieces as spans of the document, then reads each one's separator out of the gap it
     * left behind. Deriving the separator rather than tracking it is what makes a rendered chunk a
     * true substring of the document — whitespace a boundary consumed cannot go missing, because
     * nothing is holding it to be dropped — and that is what lets a snippet quote the source exactly
     * rather than a whitespace-normalised approximation.
     */
    private fun split(content: String): List<ChunkPiece> {
        val spans = mutableListOf<ChunkSpan>()
        forEachSegment(content, 0, content.length, PARAGRAPH_BREAK) { start, end ->
            if (fits(content.substring(start, end))) spans += ChunkSpan(start, end) else splitSentences(content, start, end, spans)
        }

        var previousEnd = 0
        return spans.map { span ->
            val text = content.substring(span.start, span.end)
            val piece = ChunkPiece(text, content.substring(previousEnd, span.start), tokenizer.count(text))
            previousEnd = span.end
            piece
        }
    }

    private fun splitSentences(
        content: String,
        from: Int,
        to: Int,
        spans: MutableList<ChunkSpan>,
    ) {
        forEachSegment(content, from, to, SENTENCE_BREAK) { start, end ->
            if (fits(content.substring(start, end))) spans += ChunkSpan(start, end) else hardSplit(content, start, end, spans)
        }
    }

    /**
     * Calls [action] with the bounds of every non-empty segment between [from] and [to]. Whatever a
     * boundary consumes — including a run of boundaries with nothing between them — is simply left
     * out of the spans, which is what puts it in the next piece's separator.
     */
    private inline fun forEachSegment(
        content: String,
        from: Int,
        to: Int,
        boundary: Regex,
        action: (start: Int, end: Int) -> Unit,
    ) {
        var start = from
        for (match in boundary.findAll(content.substring(from, to))) {
            val matchStart = from + match.range.first
            if (matchStart > start) action(start, matchStart)
            start = from + match.range.last + 1
        }
        if (to > start) action(start, to)
    }

    /**
     * Last resort for text with no usable boundary — a wall of words, a long table row, an unbroken
     * identifier. Cuts on whitespace where there is one and verifies each piece against the
     * tokenizer, shrinking until it fits rather than trusting a characters-per-token estimate.
     */
    private fun hardSplit(
        content: String,
        from: Int,
        to: Int,
        spans: MutableList<ChunkSpan>,
    ) {
        var start = from
        while (start < to) {
            // Whitespace at a cut is left between the spans, so it reappears as the next piece's
            // separator instead of on either piece.
            while (start < to && content[start].isWhitespace()) start++
            if (start >= to) return

            var end = cut(content, start, minOf(to, start + budgetTokens * ESTIMATED_CHARS_PER_TOKEN), to)
            while (!fits(content.substring(start, end)) && end - start > 1) {
                end = cut(content, start, start + (end - start) / 2, to)
            }
            val piece = trimmedEnd(content, start, end)
            if (piece > start) spans += ChunkSpan(start, piece)
            start = end
        }
    }

    /**
     * Cuts at [end], backing up to the previous space when that does not lose most of the piece.
     * Always returns past [start], so the caller cannot fail to make progress: without that, an
     * astral character sitting exactly on the cut would make the loop stand still forever.
     */
    private fun cut(
        content: String,
        start: Int,
        end: Int,
        limit: Int,
    ): Int {
        if (end >= limit) return limit
        val space = content.lastIndexOf(' ', end)
        if (space > start + (end - start) / 2) return space
        val whole = content.wholeCharacterEnd(end)
        return if (whole > start) whole else minOf(limit, start + 2)
    }

    /** [end] pulled back over trailing whitespace, which belongs to the gap and not to the piece. */
    private fun trimmedEnd(
        content: String,
        start: Int,
        end: Int,
    ): Int {
        var trimmed = end
        while (trimmed > start && content[trimmed - 1].isWhitespace()) trimmed--
        return trimmed
    }
}
