package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentInbox;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.shared.event.InboxMessageCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * {@link AgentInboxService} 门铃响铃接线单元测试（AgentHub V3 门铃响铃 PR-2）。
 *
 * <p>只聚焦 PR-2 新增行为：收件箱首次落库成功后发布 {@link InboxMessageCreatedEvent}，
 * 幂等重复投递（{@link DuplicateKeyException}）不发事件。用 spy 桩掉
 * {@code ServiceImpl.save()}，隔离 MyBatis-Plus / 数据库。</p>
 *
 * <p>本轮新增：投递前守卫——API_KEY_LLM / 不存在的 Agent 跳过写入与响铃。</p>
 */
@DisplayName("AgentInboxService 门铃响铃接线")
class AgentInboxServiceTest {

    private ApplicationEventPublisher eventPublisher;
    private AgentMapper agentMapper;
    private AgentInboxService service;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        agentMapper = mock(AgentMapper.class);
        service = spy(new AgentInboxService(eventPublisher, agentMapper));
        // 默认投递目标为 CLI_CLIENT，保持既有用例行为；守卫用例单独覆盖 stub
        Agent cliAgent = new Agent();
        cliAgent.setId(7L);
        cliAgent.setAccessType(AgentAccessType.CLI_CLIENT);
        when(agentMapper.selectById(7L)).thenReturn(cliAgent);
    }

    @Test
    @DisplayName("首次落库成功后发布 InboxMessageCreatedEvent 且字段透传")
    void shouldPublishEventWhenSaveSucceeds() {
        doReturn(true).when(service).save(any(AgentInbox.class));

        service.send(7L, "evt-1", "sub_task.assigned",
                "新任务已分配", "交付物: xxx", "sub_task", 66L, "HIGH");

        ArgumentCaptor<InboxMessageCreatedEvent> captor =
                ArgumentCaptor.forClass(InboxMessageCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        InboxMessageCreatedEvent event = captor.getValue();
        assertThat(event.getAgentId()).isEqualTo(7L);
        assertThat(event.getEventId()).isEqualTo("evt-1");
        assertThat(event.getEventType()).isEqualTo("sub_task.assigned");
        assertThat(event.getRefType()).isEqualTo("sub_task");
        assertThat(event.getRefId()).isEqualTo(66L);
    }

    @Test
    @DisplayName("幂等重复投递（DuplicateKey）不发事件")
    void shouldNotPublishEventOnDuplicateKey() {
        doThrow(new DuplicateKeyException("dup")).when(service).save(any(AgentInbox.class));

        service.send(7L, "evt-dup", "sub_task.assigned",
                "新任务已分配", "交付物: xxx", "sub_task", 66L, "NORMAL");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("API_KEY_LLM Agent 跳过投递：不落库、不响铃")
    void shouldSkipApiKeyLlmAgent() {
        Agent llmAgent = new Agent();
        llmAgent.setId(8L);
        llmAgent.setAccessType(AgentAccessType.API_KEY_LLM);
        when(agentMapper.selectById(8L)).thenReturn(llmAgent);

        service.send(8L, "evt-llm", "sub_task.assigned",
                "新任务已分配", "交付物: xxx", "sub_task", 66L, "HIGH");

        verify(service, never()).save(any(AgentInbox.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Agent 不存在跳过投递（防御）：不落库、不响铃")
    void shouldSkipWhenAgentNotFound() {
        when(agentMapper.selectById(999L)).thenReturn(null);

        service.send(999L, "evt-ghost", "sub_task.assigned",
                "新任务已分配", "交付物: xxx", "sub_task", 66L, "HIGH");

        verify(service, never()).save(any(AgentInbox.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ══════════════════════════════════════════════════════════════
    //  A0-4（§6.63）getRecentRead：已读消息按 read_time 倒序拉取
    //  ══════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private final LambdaQueryChainWrapper<AgentInbox> queryChain = mock(LambdaQueryChainWrapper.class);

    private void stubQueryChain(List<AgentInbox> result) {
        lenient().doReturn(queryChain).when(service).lambdaQuery();
        lenient().when(queryChain.eq(any(), any())).thenReturn(queryChain);
        lenient().when(queryChain.orderByDesc(any(SFunction.class))).thenReturn(queryChain);
        lenient().when(queryChain.last(anyString())).thenReturn(queryChain);
        lenient().when(queryChain.list()).thenReturn(result);
    }

    @Test
    @DisplayName("getRecentRead：仅查 isRead=1 且未归档，按 read_time 倒序返回")
    void shouldReturnRecentReadOnly() {
        AgentInbox read = new AgentInbox();
        read.setId(10L);
        read.setAgentId(7L);
        read.setIsRead(1);
        read.setIsArchived(0);
        stubQueryChain(List.of(read));

        List<AgentInbox> result = service.getRecentRead(7L, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("getRecentRead：limit 上限 500（防止单次拉爆）")
    void shouldCapLimitAt500() {
        stubQueryChain(List.of());

        service.getRecentRead(7L, 5000);

        verify(queryChain).last("LIMIT 500");
    }
}
