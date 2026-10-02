package com.helloai.core.agent.quality;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.port.LastReviewContext;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskReviewContextPort;
import com.helloai.core.agent.port.SubTaskReviewContextPort.BackfillOutcome;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskTimelinePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExecutorDoneIssuesBackfiller} 分布式回填互斥锁测试（2026-10-02）。
 *
 * <p>背景：回填原由 {@code ConcurrentHashMap + synchronized} 的 JVM 本地分段锁保护
 * （原注释自承「单实例安全」），多实例部署下锁不跨实例。本轮迁 Redisson RLock，
 * 本测试锁定其**跨实例互斥语义**与**防御释放**两条关键行为。</p>
 *
 * <p>纯 Mockito，不启动容器、不连 Redis。语义取 waitTime=0「抢占失败即跳过」的前提是
 * 回填**幂等**（同 round 同一份 assess 结果写同一处），故跳过不丢数据。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutorDoneIssuesBackfiller（分布式回填互斥锁）")
class ExecutorDoneIssuesBackfillerTest {

    private static final Long SUB_TASK_ID = 1001L;
    private static final Long TASK_ID = 10L;
    private static final int ROUND = 2;
    private static final long TTL_SECONDS = 30L;
    private static final List<String> ISSUES = List.of("驳回意见-1");
    private static final List<String> DONE_ISSUES = List.of("已解决-1");

    @Mock
    private SubTaskQueryPort subTaskQueryPort;
    @Mock
    private SubTaskReviewContextPort reviewContextPort;
    @Mock
    private TaskTimelinePort taskTimelinePort;
    @Mock
    private ExecutorIssueResolutionAssessor assessor;
    @Mock
    private RedissonClient redissonClient;
    @InjectMocks
    private ExecutorDoneIssuesBackfiller backfiller;

    @Mock
    private RLock lock;

    private SubTaskSnapshot snapshot;
    private ExecutorIssueResolutionAssessor.IssueResolutionResult assessResult;

    @BeforeEach
    void setUp() {
        // 前置链路：子任务存在、末轮有 issues 且 executorDoneIssues 为空（即需要回填）
        snapshot = mock(SubTaskSnapshot.class);
        when(subTaskQueryPort.findById(SUB_TASK_ID)).thenReturn(snapshot);
        when(reviewContextPort.loadLastReviewContext(SUB_TASK_ID))
                .thenReturn(new LastReviewContext(ROUND, ISSUES, List.of()));

        assessResult = mock(ExecutorIssueResolutionAssessor.IssueResolutionResult.class);
        when(assessor.assess(eq(ISSUES), anyString())).thenReturn(assessResult);

        when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    /**
     * 补齐「走到写入路径」才用到的存根：LLM 评估结果与 timeline 所需的 taskId。
     *
     * <p>这两条刻意<b>不放 setUp</b>——Mockito 严格存根会为「抢占失败即 return」的用例
     * 报 UnnecessaryStubbing，把它们下沉到真正用到的用例才是准确表达。</p>
     */
    private void givenDownstreamWritePathReady() {
        when(snapshot.taskId()).thenReturn(TASK_ID);
        when(assessResult.getDoneIssues()).thenReturn(DONE_ISSUES);
    }

    @Test
    @DisplayName("抢占锁失败：跳过写入（不调端口、不记 timeline、不释放锁），锁外评估照常发生")
    void shouldSkipWhenLockNotAcquired() throws InterruptedException {
        when(lock.tryLock(0, TTL_SECONDS, TimeUnit.SECONDS)).thenReturn(false);

        backfiller.backfill(SUB_TASK_ID, "产出正文");

        // 锁外长耗时评估已发生（与「抢占失败即跳过」只省掉写入竞争的设计一致）
        verify(assessor).assess(ISSUES, "产出正文");
        verify(reviewContextPort, never()).backfillExecutorDoneIssues(any(), eq(ROUND), eq(DONE_ISSUES));
        verify(lock, never()).unlock();
        verify(taskTimelinePort, never()).recordEvent(any(), any(), anyString(),
                any(), any(), any());
    }

    @Test
    @DisplayName("抢占锁成功：写入并释放锁（WRITTEN → 记 success timeline）")
    void shouldBackfillAndUnlockWhenLockAcquired() throws InterruptedException {
        givenDownstreamWritePathReady();
        when(lock.tryLock(0, TTL_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(reviewContextPort.backfillExecutorDoneIssues(SUB_TASK_ID, ROUND, DONE_ISSUES))
                .thenReturn(BackfillOutcome.WRITTEN);

        backfiller.backfill(SUB_TASK_ID, "产出正文");

        verify(reviewContextPort).backfillExecutorDoneIssues(SUB_TASK_ID, ROUND, DONE_ISSUES);
        verify(lock).unlock();
        verify(taskTimelinePort).recordEvent(any(), any(),
                eq(ExecutorDoneIssuesBackfiller.TIMELINE_EVENT),
                eq(AgentRole.REVIEWER), any(), any());
    }

    @Test
    @DisplayName("防御释放：锁过期被他人接管时，本线程不得 unlock（修掉误删他人锁的窗口）")
    void shouldNotUnlockWhenLockNotHeldByCurrentThread() throws InterruptedException {
        givenDownstreamWritePathReady();
        when(lock.tryLock(0, TTL_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        // 临界区执行期间锁过期并被其他实例/线程接管
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        when(reviewContextPort.backfillExecutorDoneIssues(SUB_TASK_ID, ROUND, DONE_ISSUES))
                .thenReturn(BackfillOutcome.WRITTEN);

        backfiller.backfill(SUB_TASK_ID, "产出正文");

        verify(reviewContextPort).backfillExecutorDoneIssues(SUB_TASK_ID, ROUND, DONE_ISSUES);
        verify(lock, never()).unlock();
    }
}
