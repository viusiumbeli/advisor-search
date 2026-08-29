package com.advisorsearch.embedding

import org.junit.jupiter.api.Test
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The pooling on a hand-built logits tensor, where every property can be checked by arithmetic:
 * two rows, three positions, a six-term vocabulary.
 */
class SparsePoolingTest {
    private val width = 6

    // Row 0: two real positions and a padded one full of large values that must never leak in.
    // Row 1: one real position, to prove the row offset is applied.
    private val logits =
        FloatBuffer.wrap(
            floatArrayOf(
                1.0f,
                -1.0f,
                0.0f,
                0.5f,
                2.0f,
                0.0f,
                1.0f,
                3.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                9.0f,
                9.0f,
                9.0f,
                9.0f,
                9.0f,
                9.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                7.0f,
                9.0f,
                9.0f,
                9.0f,
                9.0f,
                9.0f,
                9.0f,
                9.0f,
                9.0f,
                9.0f,
                9.0f,
                9.0f,
                9.0f,
            ),
        )

    @Test
    fun `takes the maximum over real positions, not the sum, and ignores padding`() {
        val vector =
            poolMaxLogits(logits, row = 0, sequenceLength = 3, width = width, attentionMask = longArrayOf(1, 1, 0), IntArray(0), 10)

        // Term 0 is 1.0 at both positions: max gives ln 2, a sum would give ln 3.
        assertContentEquals(intArrayOf(0, 1, 3, 4), vector.indices)
        assertClose(ln(2.0), vector.weights[0])
        assertClose(ln(4.0), vector.weights[1])
        assertClose(ln(1.5), vector.weights[2])
        assertClose(ln(3.0), vector.weights[3])
    }

    @Test
    fun `negative logits and zeroed specials are absent`() {
        val vector =
            poolMaxLogits(logits, row = 0, sequenceLength = 3, width = width, attentionMask = longArrayOf(1, 1, 0), intArrayOf(4), 10)

        // Term 1 was -1 at one position and 3 at the other; term 2 never rose above zero; term 4 is a special.
        assertContentEquals(intArrayOf(0, 1, 3), vector.indices)
    }

    @Test
    fun `the cap keeps the heaviest terms`() {
        val vector = poolMaxLogits(logits, row = 0, sequenceLength = 3, width = width, attentionMask = longArrayOf(1, 1, 0), IntArray(0), 2)

        assertContentEquals(intArrayOf(1, 4), vector.indices)
    }

    @Test
    fun `the second row is read from its own offset`() {
        val vector =
            poolMaxLogits(logits, row = 1, sequenceLength = 3, width = width, attentionMask = longArrayOf(1, 0, 0), IntArray(0), 10)

        assertContentEquals(intArrayOf(5), vector.indices)
        assertClose(ln(8.0), vector.weights[0])
    }

    private fun assertClose(
        expected: Double,
        actual: Float,
    ) = assertTrue(abs(expected - actual) < 1e-6, "expected $expected but was $actual")
}
