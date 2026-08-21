package com.advisorsearch.search

import com.advisorsearch.search.ReciprocalRankFusion.RankedList
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReciprocalRankFusionTest {
    private val k = 60

    @Test
    fun `applies the 1 over k plus rank formula exactly`() {
        val first = uuid(1)
        val second = uuid(2)

        val fused = ReciprocalRankFusion.fuse(listOf(RankedList("keyword", listOf(first, second))), k)

        assertClose(1.0 / 61, fused[0].score)
        assertClose(1.0 / 62, fused[1].score)
    }

    @Test
    fun `a document found by both retrievers outranks one found by a single retriever`() {
        val agreed = uuid(1)
        val keywordOnly = uuid(2)

        val fused =
            ReciprocalRankFusion.fuse(
                listOf(
                    // keywordOnly is the top keyword hit; agreed is only fifth there but also
                    // eighth in the semantic list.
                    RankedList("keyword", listOf(keywordOnly, uuid(3), uuid(4), uuid(5), agreed)),
                    RankedList("semantic", listOf(uuid(6), uuid(7), uuid(8), uuid(9), uuid(10), uuid(11), uuid(12), agreed)),
                ),
                k,
            )

        assertEquals(agreed, fused.first().id)
        // 1/65 + 1/68 beats a single first place at 1/61: agreement between the two retrievers is
        // worth more than being top of one list.
        assertClose(1.0 / 65 + 1.0 / 68, fused.first().score)
        assertClose(1.0 / 61, fused.single { it.id == keywordOnly }.score)
    }

    @Test
    fun `a single list keeps its own order`() {
        val ids = (1..5).map(::uuid)

        val fused = ReciprocalRankFusion.fuse(listOf(RankedList("semantic", ids)), k)

        assertEquals(ids, fused.map { it.id })
    }

    @Test
    fun `sources record which retrievers found each document`() {
        val both = uuid(1)
        val keywordOnly = uuid(2)
        val semanticOnly = uuid(3)

        val fused =
            ReciprocalRankFusion
                .fuse(
                    listOf(
                        RankedList("keyword", listOf(both, keywordOnly)),
                        RankedList("semantic", listOf(both, semanticOnly)),
                    ),
                    k,
                ).associateBy { it.id }

        assertEquals(setOf("keyword", "semantic"), fused.getValue(both).sources)
        assertEquals(setOf("keyword"), fused.getValue(keywordOnly).sources)
        assertEquals(setOf("semantic"), fused.getValue(semanticOnly).sources)
    }

    @Test
    fun `ties break on id so results are reproducible`() {
        val low = uuid(1)
        val high = uuid(2)

        // Both are first in their own list, so both score exactly 1/61.
        val fused =
            ReciprocalRankFusion.fuse(
                listOf(RankedList("keyword", listOf(high)), RankedList("semantic", listOf(low))),
                k,
            )

        assertEquals(listOf(low, high), fused.map { it.id })
    }

    @Test
    fun `empty lists fuse to nothing`() {
        val fused =
            ReciprocalRankFusion.fuse(
                listOf(RankedList("keyword", emptyList()), RankedList("semantic", emptyList())),
                k,
            )

        assertEquals(emptyList(), fused)
    }

    private fun uuid(seed: Int): UUID = UUID(0L, seed.toLong())

    private fun assertClose(
        expected: Double,
        actual: Double,
    ) = assertTrue(abs(expected - actual) < 1e-12, "expected $expected but was $actual")
}
