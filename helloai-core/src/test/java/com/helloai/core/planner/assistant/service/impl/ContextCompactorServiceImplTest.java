package com.helloai.core.planner.assistant.service.impl;

import com.helloai.common.config.ContextWindowProperties;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.assistant.entity.ConversationContextState;
import com.helloai.core.planner.assistant.service.ConversationContextStateService;
import com.helloai.core.planner.assistant.service.ContextCompactor.CompactResult;
import com.helloai.core.planner.assistant.service.ContextUsageService;
import com.helloai.core.planner.assistant.service.ContextUsageService.ContextUsage;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ContextCompactorServiceImpl} 单测（PA-12，小窗口确定性验证压缩决策）。
 */
@ExtendWith(MockitoExtension.class)
class ContextCompactorServiceImplTest {

    private static final long CONV_ID = 1L;
    private static final long PLANNER_ID = 99L;

    @Mock
    private RequirementConversationService conversationService;
    @Mock
    private RequirementMessageService messageService;
    @Mock
    private ConversationContextStateService stateService;
    @Mock
    private ContextUsageService usageService;
    @Mock
    private PlannerAgentPicker plannerAgentPicker;
    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;

    private ContextCompactorServiceImpl compactor;
    private RequirementConversation conversation;
    private ConversationContextState state;

    @BeforeEach
    void setUp() {
        ContextWindowProperties properties = new ContextWindowProperties();
        properties.setEnabled(true);
        properties.setContextSize(1000);
        properties.setCharsPerToken(2.0);
        properties.setMessageOverhead(0);
        properties.setSummaryMaxChars(10000);

        compactor = new ContextCompactorServiceImpl(conversationService, messageService, stateService,
                usageService, properties, plannerAgentPicker, platformAgentExecutionService);

        conversation = new RequirementConversation();
        conversation.setId(CONV_ID);

        state = new ConversationContextState();
        state.setConversationId(CONV_ID);
        state.setCompactedUntilSeq(0);
        state.setSummaryVersion(0);
        state.setEnabled(1);
        state.setContextSize(1000);
        state.setTriggerRatio(new BigDecimal("0.5"));
        state.setReserveRatio(new BigDecimal("0.1"));

        when(conversationService.getById(CONV_ID)).thenReturn(conversation);
        when(stateService.getOrInit(CONV_ID, null)).thenReturn(state);
    }

    @Test
    void autoCompact_belowThreshold_doesNothing() {
        ContextUsage low = new ContextUsage(0, 0, 0, 200, 0, 200, 1000, 0.2, 0, 0, null);
        when(usageService.usage(CONV_ID)).thenReturn(low);

        CompactResult result = compactor.compact(CONV_ID, false);

        assertFalse(result.compacted());
        assertEquals("below_threshold", result.reason());
        verify(stateService, never()).updateById(any());
    }

    @Test
    void manualCompact_evictsMiddle_keepsTail_andUpdatesState() {
        // 10 条消息，每条 100 字符 / 2.0 = 50 token；reserve=100 token ⇒ 保留最近 2 条
        when(messageService.listByConversation(CONV_ID)).thenReturn(messages(10, 100));
        ContextUsage before = new ContextUsage(0, 0, 0, 500, 0, 500, 1000, 0.9, 0, 0, null);
        ContextUsage after = new ContextUsage(0, 0, 0, 200, 0, 200, 1000, 0.2, 8, 1, null);
        when(usageService.usage(CONV_ID)).thenReturn(before, after);
        when(plannerAgentPicker.pick(null)).thenReturn(AgentProfileSnapshot.builder().id(PLANNER_ID).build());
        when(platformAgentExecutionService.executeSync(eq(PLANNER_ID), any(AgentTask.class)))
                .thenReturn(AgentResult.success("summary text", "stop", "test", 10));

        CompactResult result = compactor.compact(CONV_ID, true);

        assertTrue(result.compacted());
        assertEquals(8, result.evictedMessages());
        assertEquals(8, result.compactedUntilSeq());
        assertEquals("summary text", state.getSummary());
        assertEquals(8, state.getCompactedUntilSeq());
        assertEquals(1, state.getSummaryVersion());
        verify(stateService, times(1)).updateById(state);
    }

    @Test
    void summaryFailure_keepsPrevious_andDoesNotUpdateState() {
        when(messageService.listByConversation(CONV_ID)).thenReturn(messages(10, 100));
        ContextUsage before = new ContextUsage(0, 0, 0, 500, 0, 500, 1000, 0.9, 0, 0, null);
        when(usageService.usage(CONV_ID)).thenReturn(before);
        when(plannerAgentPicker.pick(null)).thenReturn(AgentProfileSnapshot.builder().id(PLANNER_ID).build());
        when(platformAgentExecutionService.executeSync(eq(PLANNER_ID), any(AgentTask.class)))
                .thenReturn(AgentResult.failure("boom", "error", "test"));

        CompactResult result = compactor.compact(CONV_ID, true);

        assertFalse(result.compacted());
        assertEquals("summary_failed", result.reason());
        assertEquals(0, state.getCompactedUntilSeq());
        verify(stateService, never()).updateById(any());
    }

    private static List<RequirementMessage> messages(int count, int contentLength) {
        String content = "a".repeat(contentLength);
        List<RequirementMessage> rows = new ArrayList<>();
        for (int seq = 1; seq <= count; seq++) {
            RequirementMessage message = new RequirementMessage();
            message.setConversationId(CONV_ID);
            message.setSeq(seq);
            message.setRole(seq % 2 == 0 ? "assistant" : "user");
            message.setContent(content);
            rows.add(message);
        }
        return rows;
    }
}