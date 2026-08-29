package com.advisorsearch.support

import com.advisorsearch.embedding.SparseVector
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SqlTest {
    @Test
    fun `wildcards a user types are escaped to literals`() {
        // Without escaping, a query of "%" would match every client in the table.
        assertEquals("\\%", "%".escapeLikeWildcards())
        assertEquals("\\_", "_".escapeLikeWildcards())
        assertEquals("\\\\", "\\".escapeLikeWildcards())
        assertEquals("100\\%\\_ off\\\\", "100%_ off\\".escapeLikeWildcards())
    }

    @Test
    fun `ordinary text is left alone`() {
        assertEquals("aldgatewealth", "aldgatewealth".escapeLikeWildcards())
        assertEquals("o'brien", "o'brien".escapeLikeWildcards())
    }

    @Test
    fun `vectors are rendered in pgvector's literal format`() {
        assertEquals("[1.0,-0.5,0.25]", floatArrayOf(1.0f, -0.5f, 0.25f).toVectorLiteral())
        assertEquals("[]", floatArrayOf().toVectorLiteral())
    }

    @Test
    fun `sparse vectors are rendered one-based with the vocabulary size`() {
        // Vocabulary id 0 is index 1: pgvector numbers sparse indices like SQL arrays.
        assertEquals("{1:0.5,3:1.25}/30522", SparseVector(intArrayOf(0, 2), floatArrayOf(0.5f, 1.25f)).toSparseVectorLiteral())
        assertEquals("{30522:2.0}/30522", SparseVector(intArrayOf(30521), floatArrayOf(2.0f)).toSparseVectorLiteral())
        assertEquals("{}/30522", SparseVector.EMPTY.toSparseVectorLiteral())
    }
}
