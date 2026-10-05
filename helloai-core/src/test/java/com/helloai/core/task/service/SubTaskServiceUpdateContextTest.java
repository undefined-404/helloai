package com.helloai.core.task.service;

import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.impl.SubTaskServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code SubTaskService#updateContext} 语义测试（RM5 批 4，W10 不透明命令）。
 *
 * <p>2026-10-04：语义自 {@code SubTaskCommandPortAdapter#updateContext} 上移至提供方服务
 * （适配器改为纯委托），故「取最新行整体覆写 / 不存在静默返回」两态断言随语义一起平移到本类。</p>
 */
@DisplayName("SubTaskService.updateContext（W10 不透明命令）")
class SubTaskServiceUpdateContextTest {

    /** 部分 mock：只让 updateContext 走真实实现，getById/updateById 由用例 stub。 */
    private final SubTaskServiceImpl service = mock(SubTaskServiceImpl.class, CALLS_REAL_METHODS);

    @Test
    @DisplayName("提供方自取最新行整体覆写（非增量合并），保留行内其余字段")
    void shouldOverwriteContextOnLatestRow() {
        SubTask existing = new SubTask();
        existing.setId(22L);
        existing.setTaskId(33L);
        Map<String, Object> stale = new HashMap<>();
        stale.put("stale", "old");
        existing.setContext(stale);
        doReturn(existing).when(service).getById(22L);
        doReturn(true).when(service).updateById(any(SubTask.class));

        Map<String, Object> target = Map.of("lastExecution", Map.of("success", true));
        service.updateContext(22L, target);

        ArgumentCaptor<SubTask> captor = ArgumentCaptor.forClass(SubTask.class);
        verify(service).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(22L);
        assertThat(captor.getValue().getTaskId()).isEqualTo(33L);
        assertThat(captor.getValue().getContext()).isEqualTo(target);
    }

    @Test
    @DisplayName("子任务不存在时静默返回（等价 updateById 更新 0 行，不抛错）")
    void shouldSilentlyReturnWhenMissing() {
        doReturn(null).when(service).getById(22L);

        service.updateContext(22L, Map.of("k", "v"));

        verify(service, never()).updateById(any());
    }
}
