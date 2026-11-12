package com.helloai.core.task.support;

import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.util.DraftGateChecker;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code sub_task.context.gateFindings} 的**唯一写入点**（`2026-11-08` 草案实体化）。
 *
 * <p><b>为什么落在草案上而不是任务上</b>：findings 描述的是「这一条草案哪里不合格」。
 * 挂在 {@code task.context} 时带来三个必然后果——无法按草案过滤（清理只能整体清）、
 * {@code draftRef} 只能写 LLM 输出的 1-based 序号（跨入口必然错位）、UI 无法把结论定位到
 * 具体草案。改挂 {@code sub_task.context} 后，这三件事同时消失：findings 与草案同生共死，
 * 「写」与「清」是同一份结构的两种取值，不存在「修了写点漏了清点」的配对义务。</p>
 *
 * <p><b>两次写各自独立事务</b>：{@code updateDraftByCode} / {@code regenerateById} 带
 * {@code @Transactional}，审计若与业务共用事务，紧接着抛出的 {@code BizException} 会把审计
 * 一并回滚——用户只看到错误消息、库里不留排障线索。本类刻意<b>不加</b> {@code @Transactional}，
 * 下游 {@link SubTaskService#updateContextInNewTx} 内部已是 {@code REQUIRES_NEW}。</p>
 *
 * <p><b>审计失败不改变结论</b>：写入失败只记 {@code warn}。阻断语义由调用方的
 * {@code BizException}（改造类操作）或「草案不可确认」（生成类操作）保证；最坏后果是残留
 * 一条过期 findings，消费方按 {@code checkedAt} 判断新鲜度。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubTaskGateFindingsStore {

    private final SubTaskService subTaskService;

    /**
     * **整批落结论**：命中草案写各自 findings，未命中的写「清零」payload。
     *
     * <p>这就是「写与清同源」的落地点——同一次调用同时完成「给谁写什么」和「把谁的旧结论清掉」，
     * 调用方不需要记得再补一次 clear。重复判定（拆解重跑、确认前复检）天然幂等。</p>
     *
     * @param allDraftIds 本轮参与判定的全部草案 id（含未命中的）
     * @param byDraft     按草案 id 分组的 findings（键可为 {@code null}，表示批次级问题）
     * @param attachBatchLevelTo 批次级问题挂到哪条草案（通常取首条）；{@code null} 表示丢弃
     */
    public void applyBatch(List<Long> allDraftIds,
                           Map<Long, List<DraftGateChecker.Finding>> byDraft,
                           Long attachBatchLevelTo) {
        applyBatchInternal(allDraftIds, byDraft, attachBatchLevelTo, false);
    }

    /**
     * 同 {@link #applyBatch}，但结论与调用方**共用同一事务**。
     *
     * <p><b>什么时候必须用这个</b>：整批拆解的落库段已经事务化（草案 / 依赖 / 归一 / 状态推进
     * 同生共死）。此时若 findings 仍走独立事务，外层回滚后草案行消失、findings 却留下了，
     * 变成指向不存在草案的孤儿记录。反过来，展开 / 重生成 / 编辑是「回滚草案变更但仍要留诊断」，
     * 必须继续用 {@link #applyBatch}（独立事务）——同一份结构在两种事务语义下各有正确用法，
     * 判据是「结论描述的主体还在不在」。</p>
     */
    public void applyBatchInTx(List<Long> allDraftIds,
                               Map<Long, List<DraftGateChecker.Finding>> byDraft,
                               Long attachBatchLevelTo) {
        applyBatchInternal(allDraftIds, byDraft, attachBatchLevelTo, true);
    }

    private void applyBatchInternal(List<Long> allDraftIds,
                                    Map<Long, List<DraftGateChecker.Finding>> byDraft,
                                    Long attachBatchLevelTo,
                                    boolean sameTx) {
        if (allDraftIds == null || allDraftIds.isEmpty()) {
            return;
        }
        String checkedAt = OffsetDateTime.now().toString();
        Map<Long, List<DraftGateChecker.Finding>> grouped = byDraft == null
                ? new HashMap<>() : new HashMap<>(byDraft);
        List<DraftGateChecker.Finding> batchLevel = grouped.remove(null);
        for (Long draftId : allDraftIds) {
            List<DraftGateChecker.Finding> findings = new ArrayList<>();
            if (byDraft != null) {
                findings.addAll(grouped.getOrDefault(draftId, List.of()));
            }
            if (draftId.equals(attachBatchLevelTo) && batchLevel != null && !batchLevel.isEmpty()) {
                findings.addAll(batchLevel);
            }
            write(draftId, findings, checkedAt, sameTx);
        }
    }

    /** 批量清零（确认转正 / 驳回作废后调用）。 */
    public void clearAll(List<Long> subTaskIds) {
        if (subTaskIds == null || subTaskIds.isEmpty()) {
            return;
        }
        String clearedAt = OffsetDateTime.now().toString();
        for (Long id : subTaskIds) {
            if (id == null) {
                continue;
            }
            writePayload(id, DraftGateChecker.emptyPayload(clearedAt), false);
        }
    }

    private void write(Long subTaskId, List<DraftGateChecker.Finding> findings,
                       String checkedAt, boolean sameTx) {
        Map<String, Object> payload = findings.isEmpty()
                ? DraftGateChecker.emptyPayload(checkedAt)
                : DraftGateChecker.payload(findings, checkedAt);
        writePayload(subTaskId, payload, sameTx);
    }

    private void writePayload(Long subTaskId, Map<String, Object> payload, boolean sameTx) {
        try {
            Map<String, Object> patch = new HashMap<>();
            patch.put(SubTaskContextKeys.GATE_FINDINGS, payload);
            if (sameTx) {
                // 同事务分支传的是 patch（只有一个键），必须走「按键合并」。
                // 借用 updateContext（整段覆写）会把 plannerAgentId / planGeneratedAt /
                // acceptancePreconditions 一并抹掉 —— 2026-11-08 真机实测的正是这个：
                // 草案落库后 context 只剩 gateFindings，TD-11 的验收前置标注被自己人擦掉。
                subTaskService.mergeContextInTx(subTaskId, patch);
            } else {
                subTaskService.updateContextInNewTx(subTaskId, patch);
            }
        } catch (Exception e) {
            // 审计写失败不改变结论：阻断语义由调用方保证
            log.warn("写 sub_task.context.gateFindings 失败（不改变门禁结论）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
        }
    }
}
