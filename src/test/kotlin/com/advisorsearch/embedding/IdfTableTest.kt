package com.advisorsearch.embedding

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdfTableTest {
    private val idf = TestModel.idfTable

    @Test
    fun `covers the whole vocabulary`() {
        assertEquals(SPARSE_DIMENSIONS, idf.size)
        assertTrue((0 until SPARSE_DIMENSIONS).all { idf[it] > 0f }, "every wordpiece must carry a positive weight")
    }

    @Test
    fun `rare words outweigh stopwords`() {
        assertTrue(idf[TestModel.tokenId("the")] < idf[TestModel.tokenId("of")])
        assertTrue(idf[TestModel.tokenId("of")] < idf[TestModel.tokenId("address")])
        assertTrue(idf[TestModel.tokenId("address")] < idf[TestModel.tokenId("electricity")])
    }

    @Test
    fun `the specials are the tokenizer's five`() {
        assertContentEquals(intArrayOf(0, 100, 101, 102, 103), idf.specialTokenIds)
    }
}
