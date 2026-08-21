package com.advisorsearch.embedding

private val PARAGRAPH_BREAK = Regex("\\R[ \\t]*\\R")
private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")

/** Only a starting guess for the hard split; the tokenizer has the final say. */
private const val ESTIMATED_CHARS_PER_TOKEN = 4

/** No natural-language text reaches this density, so it only bites on degenerate input. */
private const val MAX_CHARS_PER_TOKEN = 12

/**
 * One indivisible span of text plus the whitespace that preceded it, so a rendered chunk reproduces
 * the document exactly. Private to this file: it is an implementation detail of [Chunker].
 */
private data class ChunkPiece(
    val text: String,
    val separator: String,
    val tokens: Int,
)

/**
 * Splits document text into overlapping pieces that fit the encoder's window.
 *
 * Boundaries are tried in order of how much meaning they preserve: paragraph, then sentence, then a
 * hard window as a last resort. The fallback is the important one. Without it a single unbroken
 * block longer than the window would be handed to the encoder and silently truncated, which is the
 * exact failure this design exists to avoid.
 *
 * Size is measured with the real tokenizer rather than by character count, because the budget that
 * matters is wordpieces. A character ceiling is applied as well: WordPiece maps any run longer than
 * a hundred characters to a single unknown token, so a wall of characters can satisfy a
 * token-only budget while still being a useless chunk to store or show as a snippet.
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
            val wouldExceed = tokens + piece.tokens > budgetTokens || chars + piece.text.length > maxChars
            if (current.isNotEmpty() && wouldExceed) {
                chunks += render(current)
                current = carryOver(current)
                tokens = current.sumOf { it.tokens }
                chars = current.sumOf { it.text.length }
            }
            current += piece
            tokens += piece.tokens
            chars += piece.text.length
        }
        if (current.isNotEmpty()) chunks += render(current)
        return chunks
    }

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
     * Splits into pieces while remembering the exact whitespace that separated them.
     *
     * Keeping the original separator rather than a canonical one means a rendered chunk is a true
     * substring of the document, so a snippet quotes the source exactly instead of a
     * whitespace-normalised approximation of it.
     */
    private fun split(content: String): List<ChunkPiece> {
        val pieces = mutableListOf<ChunkPiece>()
        var separator = ""
        forEachSegment(content, PARAGRAPH_BREAK) { paragraph, nextSeparator ->
            if (paragraph.isNotEmpty()) {
                if (fits(paragraph)) {
                    pieces += ChunkPiece(paragraph, separator, tokenizer.count(paragraph))
                } else {
                    splitSentences(paragraph).forEachIndexed { position, sentence ->
                        pieces += if (position == 0) sentence.copy(separator = separator) else sentence
                    }
                }
                separator = nextSeparator
            }
        }
        return pieces
    }

    private fun splitSentences(paragraph: String): List<ChunkPiece> {
        val pieces = mutableListOf<ChunkPiece>()
        var separator = " "
        forEachSegment(paragraph, SENTENCE_BREAK) { sentence, nextSeparator ->
            if (sentence.isNotEmpty()) {
                val split = if (fits(sentence)) listOf(ChunkPiece(sentence, separator, tokenizer.count(sentence))) else hardSplit(sentence)
                split.forEachIndexed { position, piece ->
                    pieces += if (position == 0) piece.copy(separator = separator) else piece
                }
                separator = nextSeparator
            }
        }
        return pieces
    }

    /** Walks [text] as segments delimited by [boundary], handing each segment the separator that follows it. */
    private inline fun forEachSegment(
        text: String,
        boundary: Regex,
        action: (segment: String, nextSeparator: String) -> Unit,
    ) {
        var start = 0
        for (match in boundary.findAll(text)) {
            action(text.substring(start, match.range.first), match.value)
            start = match.range.last + 1
        }
        action(text.substring(start), "")
    }

    /**
     * Last resort for text with no usable boundary: a wall of words with no punctuation, a long
     * table row, an unbroken identifier. Cuts on whitespace where one is available and verifies each
     * piece against the tokenizer, shrinking until it genuinely fits rather than trusting a
     * characters-per-token estimate.
     */
    private fun hardSplit(text: String): List<ChunkPiece> {
        val pieces = mutableListOf<ChunkPiece>()
        var remaining = text
        var separator = " "
        while (remaining.isNotEmpty()) {
            var candidate = cutAt(remaining, minOf(remaining.length, budgetTokens * ESTIMATED_CHARS_PER_TOKEN))
            while (!fits(candidate) && candidate.length > 1) {
                candidate = cutAt(remaining, candidate.length / 2)
            }
            val piece = candidate.trim()
            if (piece.isNotEmpty()) pieces += ChunkPiece(piece, separator, tokenizer.count(piece))
            val rest = remaining.substring(candidate.length)
            // Only rejoin with a space where the source had whitespace; a cut made mid-token must
            // not gain a separator it never had.
            separator = if (rest.firstOrNull()?.isWhitespace() == true) " " else ""
            remaining = rest.trimStart()
        }
        return pieces
    }

    /** Cuts at [end], backing up to the previous space when that does not lose most of the piece. */
    private fun cutAt(
        text: String,
        end: Int,
    ): String {
        if (end >= text.length) return text
        val space = text.lastIndexOf(' ', end)
        return if (space > end / 2) text.substring(0, space) else text.substring(0, end)
    }
}
