package com.helloai.core.task.service.impl;

import com.helloai.core.agent.port.LastReviewContext;
import com.helloai.core.agent.port.SubTaskReviewContextPort.BackfillOutcome;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code SubTaskReviewContextPortAdapter} 单测（2026-10-01，W4）。
 *
 * <p>本测试为新写：被测逻辑原位于 agent 侧 {@code ExecutorDoneIssuesBackfiller}
 * 的 {@code peekLastRound} / {@code writeDoneIssues}（<b>此前无专属单测</b>）。
 * 逻辑随 W4 端口反转整体迁入 task 域，本次一并补齐 parse / 条件回填全分支覆盖，
 * 把「context.reviewHistory 解析口径」钉死在提供方一侧。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskReviewContextPortAdapter")
class SubTaskReviewContextPortAdapterTest {

    @Mock
    private SubTaskService subTaskService;

    @InjectMocks
    private SubTaskReviewContextPortAdapter adapter;

    // ══════════════════════════════════════════════════════════════
    //  读取：loadLastReviewContext
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("loadLastReviewContext — 末轮解析")
    class Load {

        @Test
        @DisplayName("子任务不存在 → null")
        void shouldReturnNullWhenSubTaskMissing() {
            when(subTaskService.getById(1L)).thenReturn(null);

            assertThat(adapter.loadLastReviewContext(1L)).isNull();
        }

        @Test
        @DisplayName("context 为 null → null")
        void shouldReturnNullWhenContextNull() {
            when(subTaskService.getById(1L)).thenReturn(subTask(null));

            assertThat(adapter.loadLastReviewContext(1L)).isNull();
        }

        @Test
        @DisplayName("无 reviewHistory / history 为空 → null")
        void shouldReturnNullWhenHistoryMissingOrEmpty() {
            when(subTaskService.getById(1L)).thenReturn(subTask(Map.of()));
            assertThat(adapter.loadLastReviewContext(1L)).isNull();

            when(subTaskService.getById(2L)).thenReturn(subTask(Map.of("reviewHistory", new ArrayList<>())));
            assertThat(adapter.loadLastReviewContext(2L)).isNull();
        }

        @Test
        @DisplayName("末条不是 Map → null（结构异常防御）")
        void shouldReturnNullWhenLastEntryNotMap() {
            when(subTaskService.getById(1L)).thenReturn(subTask(Map.of("reviewHistory", List.of("not-a-map"))));

            assertThat(adapter.loadLastReviewContext(1L)).isNull();
        }

        @Test
        @DisplayName("正常解析：round / issues / executorDoneIssues 三字段")
        void shouldParseLastRound() {
            Map<String, Object> last = new HashMap<>();
            last.put("round", 3);
            last.put("issues", List.of("A2 未解决", "B1 缺证据"));
            last.put("executorDoneIssues", List.of("已补证据"));
            when(subTaskService.getById(1L)).thenReturn(subTask(Map.of("reviewHistory", List.of(last))));

            LastReviewContext ctx = adapter.loadLastReviewContext(1L);

            assertThat(ctx).isNotNull();
            assertThat(ctx.round()).isEqualTo(3);
            assertThat(ctx.issues()).containsExactly("A2 未解决", "B1 缺证据");
            assertThat(ctx.executorDoneIssues()).containsExactly("已补证据");
        }

        @Test
        @DisplayName("round 缺失 → 退化为 history 长度（原实现语义）")
        void shouldFallbackRoundToHistorySize() {
            Map<String, Object> first = Map.of("round", 1);
            Map<String, Object> last = new HashMap<>();   // 无 round 字段
            when(subTaskService.getById(1L)).thenReturn(subTask(Map.of("reviewHistory", List.of(first, last))));

            assertThat(adapter.loadLastReviewContext(1L).round()).isEqualTo(2);
        }

        @Test
        @DisplayName("issues 为标量 → 单元素列表；null 元素被跳过")
        void shouldNormalizeScalarAndNullIssues() {
            Map<String, Object> scalar = new HashMap<>();
            scalar.put("round", 1);
            scalar.put("issues", "单条意见");
            when(subTaskService.getById(1L)).thenReturn(subTask(Map.of("reviewHistory", List.of(scalar))));
            assertThat(adapter.loadLastReviewContext(1L).issues()).containsExactly("单条意见");

            Map<String, Object> withNull = new HashMap<>();
            withNull.put("round", 2);
            withNull.put("issues", listWithNull("ok"));
            when(subTaskService.getById(2L)).thenReturn(subTask(Map.of("reviewHistory", List.of(withNull))));
            assertThat(adapter.loadLastReviewContext(2L).issues()).containsExactly("ok");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  条件回填：backfillExecutorDoneIssues
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("backfillExecutorDoneIssues — 重读 + 判定 + 写")
    class Backfill {

        @Test
        @DisplayName("子任务不存在 → SUB_TASK_MISSING（原：锁内重读为 null 静默返回）")
        void shouldReportMissing() {
            when(subTaskService.getById(1L)).thenReturn(null);

            assertThat(adapter.backfillExecutorDoneIssues(1L, 1, List.of("x")))
                    .isEqualTo(BackfillOutcome.SUB_TASK_MISSING);
            verify(subTaskService, never()).updateById(any());
        }

        @Test
        @DisplayName("轮次已变 → SKIPPED_ROUND_CHANGED，不写库")
        void shouldSkipWhenRoundChanged() {
            Map<String, Object> last = new HashMap<>();
            last.put("round", 4);
            when(subTaskService.getById(1L)).thenReturn(subTask(Map.of("reviewHistory", List.of(last))));

            assertThat(adapter.backfillExecutorDoneIssues(1L, 3, List.of("x")))
                    .isEqualTo(BackfillOutcome.SKIPPED_ROUND_CHANGED);
            verify(subTaskService, never()).updateById(any());
        }

        @Test
        @DisplayName("已被并发回填 → SKIPPED_ALREADY_FILLED，不写库")
        void shouldSkipWhenAlreadyFilled() {
            Map<String, Object> last = new HashMap<>();
            last.put("round", 3);
            last.put("executorDoneIssues", List.of("已填过"));
            when(subTaskService.getById(1L)).thenReturn(subTask(Map.of("reviewHistory", List.of(last))));

            assertThat(adapter.backfillExecutorDoneIssues(1L, 3, List.of("x")))
                    .isEqualTo(BackfillOutcome.SKIPPED_ALREADY_FILLED);
            verify(subTaskService, never()).updateById(any());
        }

        @Test
        @DisplayName("正常回填 → WRITTEN，且落库 context 的末轮 executorDoneIssues 被覆写")
        void shouldWriteWhenRoundMatches() {
            Map<String, Object> original = new HashMap<>();
            original.put("round", 3);
            original.put("issues", List.of("i1"));
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("reviewHistory", List.of(original));
            ctx.put("keepMe", "untouched");
            SubTask subTask = subTask(ctx);
            when(subTaskService.getById(1L)).thenReturn(subTask);

            assertThat(adapter.backfillExecutorDoneIssues(1L, 3, List.of("done1", "done2")))
                    .isEqualTo(BackfillOutcome.WRITTEN);

            ArgumentCaptor<SubTask> captor = ArgumentCaptor.forClass(SubTask.class);
            verify(subTaskService).updateById(captor.capture());
            Map<String, Object> saved = captor.getValue().getContext();
            assertThat(saved).containsEntry("keepMe", "untouched");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = (List<Map<String, Object>>) saved.get("reviewHistory");
            assertThat(history).hasSize(1);
            assertThat(history.get(0)).containsEntry("executorDoneIssues", List.of("done1", "done2"));
            // 拷贝语义：写回的 history 是新集合，不得篡改原 context 内层对象
            assertThat(original).doesNotContainKey("executorDoneIssues");
        }

        @Test
        @DisplayName("空 history → SKIPPED_ROUND_CHANGED（peek 先判空；写分支内的空写是防御性代码）")
        void shouldSkipWhenHistoryEmpty() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("reviewHistory", new ArrayList<>());
            when(subTaskService.getById(1L)).thenReturn(subTask(ctx));

            assertThat(adapter.backfillExecutorDoneIssues(1L, 1, List.of("x")))
                    .isEqualTo(BackfillOutcome.SKIPPED_ROUND_CHANGED);
            verify(subTaskService, never()).updateById(any());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  helpers
    // ══════════════════════════════════════════════════════════════

    private SubTask subTask(Map<String, Object> context) {
        SubTask subTask = new SubTask();
        subTask.setId(1L);
        subTask.setTaskId(2L);
        subTask.setContext(context);
        return subTask;
    }

    private List<String> listWithNull(String value) {
        List<String> list = new ArrayList<>();
        list.add(null);
        list.add(value);
        return list;
    }
}
