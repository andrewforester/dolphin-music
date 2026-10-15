package com.voiceproducer.feature.producer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PillRowsTest {
    @Test
    fun pillsThatFitOneLineStayOnOne() {
        assertEquals(3, secondLineStart(listOf(30, 30, 30), gap = 5, width = 100))
    }

    @Test
    fun pillsThatFitTwoLinesWrapLikeAFlow() {
        assertEquals(3, secondLineStart(listOf(30, 30, 30, 30), gap = 5, width = 100))
    }

    @Test
    fun morePillsThanTwoLinesSplitIntoTwoBalancedLines() {
        assertEquals(4, secondLineStart(List(8) { 30 }, gap = 5, width = 100))
        assertEquals(1, secondLineStart(listOf(200, 30, 30, 30, 30), gap = 5, width = 100))
    }

    @Test
    fun noPillsIsOneEmptyLine() {
        assertEquals(0, secondLineStart(emptyList(), gap = 5, width = 100))
    }
}
