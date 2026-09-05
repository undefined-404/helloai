package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.common.config.AgentInboxProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentInbox;
import com.helloai.core.agent.mapper.AgentInboxMapper;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.service.impl.AgentInboxServiceImpl;
import com.helloai.core.shared.event.InboxMessageCreatedEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
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
 * {@link AgentInboxService} 门铃响铃接线单元测试（AgentHub 门铃响铃 PR-2）。
 *
 * <p>只聚焦 PR-2 新增行为：收件箱首次落库成功后发布 {@link InboxMessageCreatedEvent}，
 * 幂等重复投递（{@code DuplicateKeyException}）不发事件。用 spy 桩掉
 * {@code ServiceImpl.save()}，隔离 MyBatis-Plus / 数据库。</p>
 *
 * <p>本轮新增：投递前守卫——API_KEY_LLM / 不存在的 Agent 跳过写入与响铃。</p>
 *
 * <p>N-008（Phase 2 A3）新增：消息 TTL——{@code send} 落库即写 expireTime；
 * {@code archiveExpired} 过期归档（baseMapper 行数模式，TableInfo 预热规避 MP 3.5.9
 * lambda 缓存坑，参照 {@code TaskFinalReportServiceTest.initTableInfo}）。</p>
 */
@DisplayName("AgentInboxService 门铃响铃接线")
class AgentInboxServiceTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new MybatisConfiguration(), ""), AgentInbox.class);
    }

    private ApplicationEventPublisher eventPublisher;
    private AgentMapper agentMapper;
    private AgentInboxMapper inboxMapper;
    private AgentInboxProperties inboxProperties;
    private AgentInboxService service;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        agentMapper = mock(AgentMapper.class);
        inboxMapper = mock(AgentInboxMapper.class);
        inboxProperties = new AgentInboxProperties();
        service = spy(new AgentInboxServiceImpl(eventPublisher, agentMapper, inboxProperties));
        ReflectionTestUtils.setField(service, "baseMapper", inboxMapper);
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
    //  getRecentRead：已读消息按 read_time 倒序拉取
    //  ══════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private final LambdaQueryChainWrapper<AgentInbox> queryChain = mock(LambdaQueryChainWrapper.class);

    private void stubQueryChain(List<AgentInbox> result) {
        lenient().doReturn(queryChain).when(service).lambdaQuery();
        lenient().when(queryChain.eq(any(), any())).thenReturn(queryChain);
        // N-008 A3：getUnread/countUnread 新增 and(w -> isNull OR gt) 过期过滤，mock 层补 stub
        lenient().when(queryChain.and(any(Consumer.class))).thenReturn(queryChain);
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

    // ══════════════════════════════════════════════════════════════
    //  N-008（Phase 2 A3）：消息 TTL + 过期归档
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("send 落库即写 expireTime = now + TTL（默认 168h）")
    void shouldWriteExpireTimeOnSend() {
        doReturn(true).when(service).save(any(AgentInbox.class));
        OffsetDateTime before = OffsetDateTime.now();

        service.send(7L, "evt-ttl-1", "sub_task.assigned",
                "新任务已分配", "交付物: xxx", "sub_task", 66L, "HIGH");

        ArgumentCaptor<AgentInbox> captor = ArgumentCaptor.forClass(AgentInbox.class);
        verify(service).save(captor.capture());
        OffsetDateTime expireTime = captor.getValue().getExpireTime();
        assertThat(expireTime).isNotNull();
        // now+168h 落在 [before+167.9h, after+168.1h] 区间内（容忍执行耗时）
        assertThat(expireTime).isAfterOrEqualTo(before.plusHours(168).minusMinutes(1));
        assertThat(expireTime).isBeforeOrEqualTo(OffsetDateTime.now().plusHours(168).plusMinutes(1));
    }

    @Test
    @DisplayName("send：expireHours<=0 视为关闭——expireTime 留 NULL（存量语义）")
    void shouldLeaveExpireTimeNullWhenDisabled() {
        inboxProperties.setExpireHours(0);
        doReturn(true).when(service).save(any(AgentInbox.class));

        service.send(7L, "evt-ttl-2", "sub_task.assigned",
                "新任务已分配", "交付物: xxx", "sub_task", 66L, "HIGH");

        ArgumentCaptor<AgentInbox> captor = ArgumentCaptor.forClass(AgentInbox.class);
        verify(service).save(captor.capture());
        assertThat(captor.getValue().getExpireTime()).isNull();
    }

    @Test
    @DisplayName("getUnread：含过期过滤条件（and 嵌套被调用——链上可见 and(isNull OR gt)）")
    void shouldApplyExpireFilterInGetUnread() {
        stubQueryChain(List.of());

        service.getUnread(7L, 10);

        // and(...) 被调用即证明过期过滤进入查询链（条件内容由真实 wrapper 语义保证）
        verify(queryChain).and(any(Consumer.class));
    }

    @Test
    @DisplayName("archiveExpired：先查后更——过期 id 集中软删，返回 UPDATE 行数")
    void shouldArchiveExpiredAndReturnRowCount() {
        AgentInbox expired1 = new AgentInbox();
        expired1.setId(101L);
        AgentInbox expired2 = new AgentInbox();
        expired2.setId(102L);
        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<AgentInbox> archiveChain = mock(LambdaQueryChainWrapper.class);
        lenient().doReturn(archiveChain).when(service).lambdaQuery();
        lenient().when(archiveChain.lt(any(SFunction.class), any())).thenReturn(archiveChain);
        lenient().when(archiveChain.eq(any(SFunction.class), any())).thenReturn(archiveChain);
        lenient().when(archiveChain.last(anyString())).thenReturn(archiveChain);
        lenient().when(archiveChain.list()).thenReturn(List.of(expired1, expired2));
        when(inboxMapper.update(isNull(), any())).thenReturn(2);

        int archived = service.archiveExpired(200);

        assertThat(archived).isEqualTo(2);
        verify(inboxMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("archiveExpired：batchLimit<=0 直接返回 0，不触碰查询与 mapper")
    void shouldReturnZeroWithoutMapperWhenBatchLimitNonPositive() {
        int archived = service.archiveExpired(0);

        assertThat(archived).isZero();
        verify(inboxMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("archiveExpired：无过期消息 → 不发 UPDATE（空转）")
    void shouldSkipUpdateWhenNothingExpired() {
        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<AgentInbox> archiveChain = mock(LambdaQueryChainWrapper.class);
        lenient().doReturn(archiveChain).when(service).lambdaQuery();
        lenient().when(archiveChain.lt(any(SFunction.class), any())).thenReturn(archiveChain);
        lenient().when(archiveChain.eq(any(SFunction.class), any())).thenReturn(archiveChain);
        lenient().when(archiveChain.last(anyString())).thenReturn(archiveChain);
        lenient().when(archiveChain.list()).thenReturn(List.of());

        int archived = service.archiveExpired(200);

        assertThat(archived).isZero();
        verify(inboxMapper, never()).update(any(), any());
    }
}
