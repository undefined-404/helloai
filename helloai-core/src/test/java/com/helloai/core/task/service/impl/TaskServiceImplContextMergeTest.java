package com.helloai.core.task.service.impl;

import com.helloai.core.task.entity.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * {@code TaskService#updateContext} 的按键合并语义（`TD-13 S4` 必修 3）。
 *
 * <p>原实现 {@code task.setContext(context)} 整段替换：{@code @Version} 只保护版本号，
 * 不保护内容，两次写入之间第三方改的 key 会被静默丢弃。{@code S5} / {@code S3}
 * 都会写 {@code context}，届时同批多条 findings 并存即是真实竞争面。</p>
 */
class TaskServiceImplContextMergeTest {

    private static final long TASK_ID = 9L;

    private TaskServiceImpl serviceWith(Task row) {
        TaskServiceImpl impl = spy(new TaskServiceImpl(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        doReturn(row).when(impl).getById(any());
        doReturn(true).when(impl).updateById(any());
        return impl;
    }

    @Test
    @DisplayName("只覆盖传入的 key：库中已有的其他 key 保留")
    void shouldKeepExistingKeysWhenMerging() {
        Task row = new Task();
        Map<String, Object> existing = new HashMap<>();
        existing.put("requirementBrief", "已有需求摘要");
        existing.put("gateFindings", Map.of("findings", java.util.List.of("旧")));
        row.setContext(existing);

        TaskServiceImpl impl = serviceWith(row);
        impl.updateContext(TASK_ID, Map.of("gateFindings", Map.of("findings", java.util.List.of("新"))));

        assertThat(row.getContext())
                .containsEntry("requirementBrief", "已有需求摘要")
                .containsEntry("gateFindings", Map.of("findings", java.util.List.of("新")));
        verify(impl).updateById(row);
    }

    @Test
    @DisplayName("库中 context 为 null：按传入 map 建立新 context")
    void shouldCreateContextWhenRowContextIsNull() {
        Task row = new Task();
        row.setContext(null);

        TaskServiceImpl impl = serviceWith(row);
        impl.updateContext(TASK_ID, Map.of("gateFindings", Map.of("findings", java.util.List.of())));

        assertThat(row.getContext()).containsOnlyKeys("gateFindings");
    }

    @Test
    @DisplayName("空 map / null 为空操作：不读库、不写库")
    void shouldNoOpOnEmptyInput() {
        TaskServiceImpl impl = serviceWith(new Task());

        impl.updateContext(TASK_ID, Map.of());
        impl.updateContext(TASK_ID, null);

        verify(impl, never()).getById(anyLong());
        verify(impl, never()).updateById(any());
    }

    @Test
    @DisplayName("独立事务入口与合并入口同语义：只覆盖传入 key，保留库中其他 key")
    void newTxEntryShouldMergeToo() {
        Task row = new Task();
        Map<String, Object> existing = new HashMap<>();
        existing.put("requirementBrief", "已有需求摘要");
        row.setContext(existing);

        TaskServiceImpl impl = serviceWith(row);
        impl.updateContextInNewTx(TASK_ID, Map.of("gateFindings", Map.of("findings", java.util.List.of())));

        assertThat(row.getContext())
                .containsEntry("requirementBrief", "已有需求摘要")
                .containsKey("gateFindings");
        verify(impl).updateById(row);
    }
}
