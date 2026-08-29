package com.advisorsearch.embedding

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SparseVectorTest {
    @Test
    fun `terms must be ascending, unique and positively weighted`() {
        assertFailsWith<IllegalArgumentException> { SparseVector(intArrayOf(1), floatArrayOf(0f)) }
        assertFailsWith<IllegalArgumentException> { SparseVector(intArrayOf(3, 1), floatArrayOf(1f, 1f)) }
        assertFailsWith<IllegalArgumentException> { SparseVector(intArrayOf(1, 1), floatArrayOf(1f, 1f)) }
        assertFailsWith<IllegalArgumentException> { SparseVector(intArrayOf(1, 2), floatArrayOf(1f)) }
    }

    @Test
    fun `the inner product runs over shared terms only`() {
        val a = SparseVector(intArrayOf(1, 5, 9), floatArrayOf(1f, 2f, 3f))
        val b = SparseVector(intArrayOf(5, 7, 9), floatArrayOf(4f, 100f, 0.5f))

        assertClose(2.0 * 4 + 3.0 * 0.5, a.dot(b))
        assertClose(a.dot(b), b.dot(a))
        assertEquals(0.0, a.dot(SparseVector.EMPTY))
        assertClose(6.0, a.mass)
    }

    @Test
    fun `pooled values become log1p weights on the positive terms`() {
        val vector = sparseVectorOf(floatArrayOf(0f, 1f, -2f, 0.5f), maxTerms = 10)

        assertContentEquals(intArrayOf(1, 3), vector.indices)
        assertClose(ln(2.0), vector.weights[0].toDouble())
        assertClose(ln(1.5), vector.weights[1].toDouble())
    }

    @Test
    fun `the cap keeps the heaviest terms and returns them in id order`() {
        val vector = sparseVectorOf(floatArrayOf(0.1f, 5f, 0.2f, 3f, 4f), maxTerms = 2)

        assertContentEquals(intArrayOf(1, 4), vector.indices)
        assertEquals(2, vector.termCount)
    }

    @Test
    fun `nothing positive is the empty vector`() {
        assertTrue(sparseVectorOf(floatArrayOf(0f, -1f), maxTerms = 5).isEmpty())
    }

    private fun assertClose(
        expected: Double,
        actual: Double,
    ) = assertTrue(abs(expected - actual) < 1e-6, "expected $expected but was $actual")
}
