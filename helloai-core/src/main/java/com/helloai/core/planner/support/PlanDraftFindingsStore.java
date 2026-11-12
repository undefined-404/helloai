package com.helloai.core.planner.support;

import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.util.DraftGateChecker;
import com.helloai.core.planner.entity.PlanDraft;
import com.helloai.core.planner.mapper.PlanDraftMapper;
import com.helloai.core.planner.port.PlanDraftView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `AD-5a-3b-2b`：把门禁结论写进暂存区 `context.gateFindings`（唯一写点）。
 *
 * <p>停止写执行表副本后，findings 必须挂在暂存区行上；结构与旧 `SubTaskGateFindingsStore`
 * 完全一致（`findings / ruleVersion / checkedAt|clearedAt`），消费方（审阅弹窗）无需区分来源。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanDraftFindingsStore {

    private final PlanDraftMapper planDraftMapper;

    public void persist(Long taskId, List<PlanDraftView> drafts, DraftGateChecker.Result gate) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        Map<String, List<DraftGateChecker.Finding>> byRef = new HashMap<>();
        for (DraftGateChecker.Finding f : gate.findings()) {
            String ref = f.draftRef();
            byRef.computeIfAbsent(ref, k -> new ArrayList<>()).add(f);
        }
        String batchRef = drafts.get(0).code();
        String now = OffsetDateTime.now().toString();
        for (PlanDraftView d : drafts) {
            if (d.id() == null || d.code() == null) {
                continue;
            }
            List<DraftGateChecker.Finding> findings = new ArrayList<>(byRef.getOrDefault(d.code(), List.of()));
            if (d.code().equals(batchRef)) {
                for (DraftGateChecker.Finding f : gate.findings()) {
                    if (f.draftRef() == null) {
                        findings.add(f);
                    }
                }
            }
            PlanDraft row = planDraftMapper.selectById(d.id());
            if (row == null) {
                continue;
            }
            Map<String, Object> context = row.getContext() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(row.getContext());
            context.put(SubTaskContextKeys.GATE_FINDINGS, findings.isEmpty()
                    ? DraftGateChecker.emptyPayload(now)
                    : DraftGateChecker.payload(findings, now));
            row.setContext(context);
            planDraftMapper.updateById(row);
        }
        log.info("暂存区门禁结论已落库: taskId={}, drafts={}, findings={}",
                taskId, drafts.size(), gate.findings().size());
    }
}