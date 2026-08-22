package com.helloai.job.task;

import com.helloai.common.config.ReviewProperties;
import com.helloai.core.review.support.ReviewRecheckExecutor;
import com.helloai.core.task.service.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReviewerRecheckTask} 单元测试（反馈回路 Phase 4 抽检）。
 *
 * <p>覆盖：开关关闭跳过 / 锁占用跳过 / 无候选跳过 / 抽样批量折算（候选 × 比例，
 * 上限 maxBatch）/ 单条失败不中断 / Lua 安全解锁。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewerRecheckTask")
class ReviewerRecheckTaskTest {

    @Mock
    private ReviewService reviewService;
    @Mock
    private ReviewRecheckExecutor reviewRecheckExecutor;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private ReviewerRecheckTask task;

    @BeforeEach
    void setUp() {
        task = new ReviewerRecheckTask(reviewService, reviewRecheckExecutor, props(), redis);
        // 默认 tryLock 成功（锁用例单独 stub 为 false）
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
    }

    private static ReviewProperties props() {
        ReviewProperties p = new ReviewProperties();
        p.setRecheckEnabled(true);
        p.setRecheckSampleRatio(0.05);
        p.setRecheckMaxBatch(20);
        p.setRecheckWindowDays(7);
        return p;
    }

    @Nested
    @DisplayName("前置条件短路")
    class Precondition {

        @Test
        @DisplayName("开关关闭 → 跳过（不查候选）")
        void shouldSkipWhenDisabled() {
            ReviewProperties disabled = props();
            disabled.setRecheckEnabled(false);
            task = new ReviewerRecheckTask(reviewService, reviewRecheckExecutor, disabled, redis);

            task.recheck();

            verify(reviewService, never()).countRecheckCandidates(any());
        }

        @Test
        @DisplayName("锁被占用 → 跳过（不查候选）")
        void shouldSkipWhenLocked() {
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                    .thenReturn(false);

            task.recheck();

            verify(reviewService, never()).countRecheckCandidates(any());
        }

        @Test
        @DisplayName("无候选 → 跳过（不查 ID 列表）")
        void shouldSkipWhenNoCandidates() {
            when(reviewService.countRecheckCandidates(any())).thenReturn(0L);

            task.recheck();

            verify(reviewService, never()).listRecheckCandidateIds(any(), anyInt());
        }
    }

    @Nested
    @DisplayName("抽样执行")
    class Sampling {

        @Test
        @DisplayName("候选 100 × 比例 0.05 → 抽样 5 条，逐条复审")
        void shouldSampleByRatio() {
            when(reviewService.countRecheckCandidates(any())).thenReturn(100L);
            when(reviewService.listRecheckCandidateIds(any(), anyInt()))
                    .thenReturn(List.of(1L, 2L, 3L, 4L, 5L));

            task.recheck();

            verify(reviewService).listRecheckCandidateIds(any(), eq(5));
            verify(reviewRecheckExecutor, times(5)).recheckReviewRecord(anyLong());
        }

        @Test
        @DisplayName("候选 1000 × 比例 0.5 → 批量被 maxBatch=20 封顶")
        void shouldCapBatchAtMax() {
            ReviewProperties p = props();
            p.setRecheckSampleRatio(0.5);
            task = new ReviewerRecheckTask(reviewService, reviewRecheckExecutor, p, redis);
            when(reviewService.countRecheckCandidates(any())).thenReturn(1000L);
            when(reviewService.listRecheckCandidateIds(any(), anyInt()))
                    .thenReturn(List.of(1L, 2L));

            task.recheck();

            verify(reviewService).listRecheckCandidateIds(any(), eq(20));
        }

        @Test
        @DisplayName("单条复审失败不中断同轮其它记录")
        void shouldContinueOnSingleFailure() {
            when(reviewService.countRecheckCandidates(any())).thenReturn(10L);
            when(reviewService.listRecheckCandidateIds(any(), anyInt()))
                    .thenReturn(List.of(1L, 2L));
            doThrow(new RuntimeException("synthetic failure"))
                    .when(reviewRecheckExecutor).recheckReviewRecord(1L);

            task.recheck();

            verify(reviewRecheckExecutor).recheckReviewRecord(2L);
        }

        @Test
        @DisplayName("安全释放锁：Lua 脚本比对 token（防误删他人锁）")
        void shouldUseLuaUnlockScriptWithMatchingToken() {
            when(reviewService.countRecheckCandidates(any())).thenReturn(0L);

            task.recheck();

            // finally 必须调用 redis.execute(Lua, ...) 而不是 redis.delete
            verify(redis).execute(any(RedisScript.class), any(List.class), any());
            verify(redis, never()).delete((String) any());
        }
    }
}
