package com.helloai.core.planner.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.config.SkillExecutionProperties;
import com.helloai.common.constant.SkillExecutionPhase;
import com.helloai.common.constant.SkillExecutionStatus;
import com.helloai.core.planner.entity.ChatSkillRun;
import com.helloai.core.planner.mapper.ChatSkillRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatSkillRunServiceImpl} 聚焦测试（SKX-13）。
 *
 * <p>覆盖「提交落 PENDING」「同会话互斥拒绝（活跃 = PENDING 或 RUNNING）」「取消需条件更新命中」三件事 ——
 * 其中互斥是本项最关键的行为：沙箱槽按会话复用，并发两次会互相踩工作目录与产物路径。</p>
 */
@DisplayName("ChatSkillRunServiceImpl 技能执行提交面")
class ChatSkillRunServiceImplTest {

    private ChatSkillRunMapper mapper;
    private ChatSkillRunServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(ChatSkillRunMapper.class);
        service = new ChatSkillRunServiceImpl(mapper, new SkillExecutionProperties());
        when(mapper.selectById(anyLong())).thenAnswer(inv -> {
            ChatSkillRun run = new ChatSkillRun();
            run.setId(inv.getArgument(0));
            return run;
        });
    }

    @Test
    @DisplayName("提交：落 PENDING + SPEC 阶段，返回运行记录")
    void submit_createsPendingRun() {
        when(mapper.countActiveByConversationId(2002L)).thenReturn(0);

        ChatSkillRun run = service.submit(2002L, null, "archify", "CHAT");

        assertThat(run.getStatus()).isEqualTo(SkillExecutionStatus.PENDING.name());
        assertThat(run.getPhase()).isEqualTo(SkillExecutionPhase.SPEC.name());
        assertThat(run.getConversationId()).isEqualTo(2002L);
        assertThat(run.getSkillName()).isEqualTo("archify");
        assertThat(run.getTimeoutSeconds()).isEqualTo(600);
        verify(mapper).insert(any(ChatSkillRun.class));
    }

    @Test
    @DisplayName("同会话已有活跃任务（PENDING 或 RUNNING）：直接拒绝（不排队）")
    void submit_rejectsWhenRunning() {
        when(mapper.countActiveByConversationId(2002L)).thenReturn(1);

        assertThatThrownBy(() -> service.submit(2002L, null, "archify", "CHAT"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已有技能在执行");
        verify(mapper, org.mockito.Mockito.never()).insert(any(ChatSkillRun.class));
    }

    @Test
    @DisplayName("取消：活跃态（PENDING/RUNNING）→ CANCELLED 命中才算取消成功")
    void cancel_onlyWhenRunning() {
        when(mapper.markCancelled(1001L)).thenReturn(1);
        assertThat(service.cancel(1001L)).isTrue();

        when(mapper.markCancelled(1001L)).thenReturn(0);
        assertThat(service.cancel(1001L)).isFalse();
    }

    @Test
    @DisplayName("取消：空 id 直接 false（不查库）")
    void cancel_nullId_returnsFalse() {
        assertThat(service.cancel(null)).isFalse();
        verify(mapper, org.mockito.Mockito.never()).markCancelled(anyLong());
    }

    @Test
    @DisplayName("活跃执行：按会话查到就返回（刷新页面恢复进度用）")
    void active_returnsActiveRun() {
        ChatSkillRun run = new ChatSkillRun();
        run.setId(1001L);
        run.setStatus(SkillExecutionStatus.RUNNING.name());
        when(mapper.selectActiveByConversationId(2002L)).thenReturn(run);

        assertThat(service.active(2002L)).isSameAs(run);
    }

    @Test
    @DisplayName("活跃执行：空会话 id 直接 null（不查库）")
    void active_nullConversation_returnsNull() {
        assertThat(service.active(null)).isNull();
        verify(mapper, org.mockito.Mockito.never()).selectActiveByConversationId(anyLong());
    }
}
