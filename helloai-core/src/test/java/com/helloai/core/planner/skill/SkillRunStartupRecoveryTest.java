package com.helloai.core.planner.skill;

import com.helloai.core.planner.mapper.ChatSkillRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillRunStartupRecovery} 聚焦测试（SKX-13 补齐）。
 *
 * <p>两件事必须成立：① 启动时确实执行收尾（否则重启后运行记录会永久占住互斥额度）；
 * ② 收尾失败不阻断应用启动。</p>
 */
@DisplayName("SkillRunStartupRecovery 启动恢复")
class SkillRunStartupRecoveryTest {

    private ChatSkillRunMapper mapper;
    private SkillRunStartupRecovery recovery;

    @BeforeEach
    void setUp() {
        mapper = mock(ChatSkillRunMapper.class);
        recovery = new SkillRunStartupRecovery(mapper);
    }

    @Test
    @DisplayName("启动：把遗留的 PENDING/RUNNING 一律置 FAILED（带可读原因）")
    void run_marksInterruptedRunsFailed() {
        when(mapper.failInterruptedRuns(anyString())).thenReturn(2);

        recovery.run(null);

        verify(mapper).failInterruptedRuns(anyString());
    }

    @Test
    @DisplayName("收尾失败：只打日志，不抛（不能因为运行记录表让应用起不来）")
    void run_swallowsException() {
        when(mapper.failInterruptedRuns(anyString()))
                .thenThrow(new IllegalStateException("relation \"chat_skill_run\" does not exist"));

        assertThatCode(() -> recovery.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("中断原因不含堆栈、长度远小于列宽 1000")
    void interruptedReason_isShortAndReadable() {
        assertThat(SkillRunStartupRecovery.INTERRUPTED_REASON)
                .contains("服务重启")
                .hasSizeLessThan(1000);
    }
}
