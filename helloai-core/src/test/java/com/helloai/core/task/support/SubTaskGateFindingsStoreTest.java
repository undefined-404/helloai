package com.helloai.core.task.support;

import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.core.task.service.SubTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code sub_task.context.gateFindings} 写入点的**事务边界与合并语义**守门测试。
 *
 * <p>守的是 2026-11-08 真机抓到的那个缺陷：同事务分支借用了 {@code updateContext}
 * （整段覆写），而它交出去的是只含 {@code gateFindings} 一个键的 patch ——
 * 草案落库后 {@code context} 只剩 {@code gateFindings}，
 * {@code plannerAgentId} / {@code planGeneratedAt} / {@code acceptancePreconditions} 全被擦掉。</p>
 *
 * <p><b>为什么守在这里而不是守 service</b>：{@code updateContext} 的「整段覆写」语义本身是对的
 * （它服务于「我持有完整 context」的调用方）；错的是「只持有一个键的人用了它」。
 * 所以判据落在调用点：patch 形态 ⇒ 必须走合并。</p>
 */
@ExtendWith(MockitoExtension.class)
class SubTaskGateFindingsStoreTest {

    private static final long DRAFT_ID = 11L;

    @Mock
    private SubTaskService subTaskService;

    @InjectMocks
    private SubTaskGateFindingsStore store;

    @Test
    @DisplayName("同事务分支必须走按键合并，绝不能借用整段覆写的 updateContext")
    void shouldMergeContextWhenSameTx() {
        store.applyBatchInTx(List.of(DRAFT_ID), Map.of(), null);

        verify(subTaskService).mergeContextInTx(eq(DRAFT_ID), anyMap());
        // 这一条是本测试的全部意义：覆写会把同批写入的其它键一起抹掉
        verify(subTaskService, never()).updateContext(anyLong(), any());
    }

    @Test
    @DisplayName("独立事务分支仍走 updateContextInNewTx（审计要活过外层回滚）")
    void shouldUseNewTxWhenNotSameTx() {
        store.applyBatch(List.of(DRAFT_ID), Map.of(), null);

        verify(subTaskService).updateContextInNewTx(eq(DRAFT_ID), anyMap());
        verify(subTaskService, never()).updateContext(anyLong(), any());
        verify(subTaskService, never()).mergeContextInTx(anyLong(), anyMap());
    }

    @Test
    @DisplayName("交出去的 patch 只含 gateFindings 一个键 —— 这正是「必须合并」的前提")
    void patchShouldCarryOnlyGateFindings() {
        store.applyBatchInTx(List.of(DRAFT_ID), Map.of(), null);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(subTaskService).mergeContextInTx(eq(DRAFT_ID), captor.capture());

        assertThat(captor.getValue()).containsOnlyKeys(SubTaskContextKeys.GATE_FINDINGS);
        Object payload = captor.getValue().get(SubTaskContextKeys.GATE_FINDINGS);
        assertThat(payload).isInstanceOf(Map.class);
        // 未命中任何判据 ⇒ 写的是「清零」payload，而不是不写（写与清同源）
        assertThat(((Map<?, ?>) payload).get("findings")).isEqualTo(List.of());
    }
}
