package com.helloai.core.planner.service.impl;

import com.helloai.core.planner.entity.RequirementMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClarifyPromptRenderServiceImpl} 上下文压缩纯函数单测（PA-12 v1.1）。
 */
class ClarifyPromptRenderServiceImplCompactionTest {

    @Test
    void liveHistory_filtersCompactedPrefix() {
        List<RequirementMessage> history = messages(5);

        List<RequirementMessage> live = ClarifyPromptRenderServiceImpl.liveHistory(history, 3);

        assertEquals(2, live.size());
        assertEquals(4, live.get(0).getSeq());
        assertEquals(5, live.get(1).getSeq());
    }

    @Test
    void liveHistory_zeroOrNull_keepsOriginal() {
        List<RequirementMessage> history = messages(3);

        assertSame(history, ClarifyPromptRenderServiceImpl.liveHistory(history, 0));
        assertNull(ClarifyPromptRenderServiceImpl.liveHistory(null, 3));
    }

    @Test
    void summaryPrefix_blankReturnsEmpty_elseWraps() {
        assertEquals("", ClarifyPromptRenderServiceImpl.summaryPrefix(null));
        assertEquals("", ClarifyPromptRenderServiceImpl.summaryPrefix("   "));
        String prefix = ClarifyPromptRenderServiceImpl.summaryPrefix("用户偏好简洁");
        assertTrue(prefix.contains("此前对话摘要"));
        assertTrue(prefix.contains("用户偏好简洁"));
    }

    private static List<RequirementMessage> messages(int count) {
        List<RequirementMessage> rows = new ArrayList<>();
        for (int seq = 1; seq <= count; seq++) {
            RequirementMessage message = new RequirementMessage();
            message.setSeq(seq);
            message.setRole(seq % 2 == 0 ? "assistant" : "user");
            message.setContent("c" + seq);
            rows.add(message);
        }
        return rows;
    }
}