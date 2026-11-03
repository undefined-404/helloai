package com.helloai.core.planner.assistant.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TokenEstimator} 纯函数单测（PA-12）。
 */
class TokenEstimatorTest {

    @Test
    void estimateText_nullOrEmpty_returnsOverhead() {
        assertEquals(4, TokenEstimator.estimateText(null, 2.0, 4));
        assertEquals(4, TokenEstimator.estimateText("", 2.0, 4));
        assertEquals(0, TokenEstimator.estimateText(null, 2.0, -3));
    }

    @Test
    void estimateText_roundsUp() {
        // 10 字符 / 2.0 = 5，+ 4 = 9
        assertEquals(9, TokenEstimator.estimateText("abcdefghij", 2.0, 4));
        // 5 字符 / 2.0 = 2.5 ⇒ ceil 3，+ 0 = 3
        assertEquals(3, TokenEstimator.estimateText("abcde", 2.0, 0));
    }

    @Test
    void estimateText_nonPositiveRatio_fallsBackToFour() {
        // 8 字符 / 4.0 = 2，+ 0 = 2
        assertEquals(2, TokenEstimator.estimateText("abcdefgh", 0, 0));
        assertEquals(2, TokenEstimator.estimateText("abcdefgh", -1, 0));
    }

    @Test
    void estimateMessages_sumsWithOverhead() {
        // ("abcd": 4/2=2+1=3) + ("ef": 2/2=1+1=2) = 5
        assertEquals(5, TokenEstimator.estimateMessages(List.of("abcd", "ef"), 2.0, 1));
        assertEquals(0, TokenEstimator.estimateMessages(List.of(), 2.0, 1));
        assertEquals(0, TokenEstimator.estimateMessages(null, 2.0, 1));
    }
}