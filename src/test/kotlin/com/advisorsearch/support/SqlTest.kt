package com.advisorsearch.support

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
}
