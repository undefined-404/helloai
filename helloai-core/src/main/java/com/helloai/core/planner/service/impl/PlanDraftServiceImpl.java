package com.helloai.core.planner.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.constant.UncertaintyKind;
import com.helloai.common.util.DraftGateChecker;
import com.helloai.core.planner.entity.PlanDraft;
import com.helloai.core.planner.mapper.PlanDraftMapper;
import com.helloai.core.planner.port.PlanDraftView;
import com.helloai.core.planner.service.PlanDraftService;
import com.helloai.core.planner.support.PlanDraftViewMapper;
import com.helloai.core.task.port.UncertaintyDraft;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link PlanDraftService} 实现（`AD-5a-3` 读侧）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanDraftServiceImpl implements PlanDraftService {

    private final PlanDraftMapper planDraftMapper;
    private final TaskService taskService;

    @Override
    public List<PlanDraftView> listByTask(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        if (taskService.getView(taskId) == null) {
            throw new BizException(400, "任务不存在: " + taskId);
        }
        int round = planDraftMapper.findMaxActiveRound(taskId);
        if (round <= 0) {
            return List.of();
        }
        List<Long> ids = planDraftMapper.listActiveIdsByRound(taskId, round);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, PlanDraft> byId = new HashMap<>();
        for (PlanDraft d : planDraftMapper.selectBatchIds(ids)) {
            if (d != null) {
                byId.put(d.getId(), d);
            }
        }
        List<PlanDraftView> views = new ArrayList<>(ids.size());
        for (Long id : ids) {
            PlanDraft d = byId.get(id);
            if (d != null) {
                views.add(toView(d));
            }
        }
        return views;
    }

    @Override
    public PlanDraftView getByCode(Long taskId, String code) {
        if (taskId == null || code == null || code.isBlank()) {
            return null;
        }
        Long id = planDraftMapper.findIdByCode(taskId, code);
        if (id == null) {
            return null;
        }
        PlanDraft d = planDraftMapper.selectById(id);
        return d == null ? null : toView(d);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyDependencies(Long taskId, Map<String, List<String>> dependsOnCodes) {
        if (taskId == null || dependsOnCodes == null || dependsOnCodes.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> e : dependsOnCodes.entrySet()) {
            Long id = planDraftMapper.findIdByCode(taskId, e.getKey());
            if (id == null) {
                log.warn("暂存区依赖回写跳过：无 ACTIVE 行: taskId={}, code={}", taskId, e.getKey());
                continue;
            }
            PlanDraft d = planDraftMapper.selectById(id);
            if (d == null) {
                continue;
            }
            d.setDependsOnCodes(e.getValue() == null ? List.of() : e.getValue());
            planDraftMapper.updateById(d);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void demoteContracts(Long taskId, List<String> codes) {
        if (taskId == null || codes == null || codes.isEmpty()) {
            return;
        }
        for (String code : codes) {
            Long id = planDraftMapper.findIdByCode(taskId, code);
            if (id == null) {
                continue;
            }
            PlanDraft d = planDraftMapper.selectById(id);
            if (d != null) {
                d.setContract(0);
                planDraftMapper.updateById(d);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanDraftView updateByCode(Long taskId, String code, String content, String deliverable,
                                      String acceptance, List<String> requiredSkills, String constraints,
                                      List<UncertaintyDraft> uncertainties, String acceptanceKind) {
        Long id = planDraftMapper.findIdByCode(taskId, code);
        if (id == null) {
            throw new BizException(400, "草案不存在或已确认: code=" + code);
        }
        PlanDraft d = planDraftMapper.selectById(id);
        if (d == null) {
            throw new BizException(400, "草案不存在: code=" + code);
        }
        Map<String, Object> context = d.getContext() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(d.getContext());
        String oldAcceptance = d.getAcceptance();
        if (content != null) {
            d.setContent(content);
        }
        if (deliverable != null) {
            d.setDeliverable(deliverable);
        }
        if (acceptance != null) {
            d.setAcceptance(acceptance);
            // TD-11：验收文本被改写 ⇒ 旧标注是按旧文本算的，留着比没有更危险
            context.remove(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS);
        }
        if (requiredSkills != null) {
            d.setRequiredSkills(requiredSkills);
        }
        if (constraints != null) {
            d.setConstraints(constraints);
        }
        if (uncertainties != null) {
            d.setUncertainties(normalizeUncertainties(uncertainties));
        }
        String declared = null;
        if (acceptanceKind != null && !acceptanceKind.isBlank()) {
            declared = parseExplicitKind(acceptanceKind);
            d.setAcceptanceKind(declared != null
                    ? AcceptanceKind.valueOf(declared) : AcceptanceKind.parse(acceptanceKind));
            if (declared != null) {
                context.put(SubTaskContextKeys.ACCEPTANCE_KIND_DECLARED, declared);
            }
        }
        Map<String, Long> codeToId = currentCodeToId(taskId);
        List<Long> depIds = List.of();
        if (d.getDependsOnCodes() != null) {
            depIds = d.getDependsOnCodes().stream().map(codeToId::get)
                    .filter(java.util.Objects::nonNull).toList();
        }
        Object declaredRaw = context.get(SubTaskContextKeys.ACCEPTANCE_KIND_DECLARED);
        DraftGateChecker.Result gate = DraftGateChecker.checkOne(new DraftGateChecker.DraftSnapshot(
                d.getId(), d.getCode(), d.getTitle(), d.getContent(), d.getDeliverable(), d.getAcceptance(),
                declaredRaw == null ? null : String.valueOf(declaredRaw), depIds));
        if (gate.hasBlocking()) {
            DraftGateChecker.Finding first = gate.blocking().get(0);
            throw new BizException("草案未通过门禁：" + first.evidence() + "（" + first.ruleId() + "）");
        }
        String now = OffsetDateTime.now().toString();
        context.put(SubTaskContextKeys.GATE_FINDINGS, gate.findings().isEmpty()
                ? DraftGateChecker.emptyPayload(now) : DraftGateChecker.payload(gate.findings(), now));
        d.setContext(context);
        planDraftMapper.updateById(d);
        log.info("暂存区草案已编辑: taskId={}, code={}, acceptanceChanged={}",
                taskId, code, !java.util.Objects.equals(oldAcceptance, d.getAcceptance()));
        return toView(d);
    }

    /** 空白 note 丢弃、非法 kind 降级 `UNCONFIRMED`——与执行表版 `updateDraft` 同口径。 */
    private static List<UncertaintyDraft> normalizeUncertainties(List<UncertaintyDraft> raw) {
        List<UncertaintyDraft> normalized = new ArrayList<>(raw.size());
        for (UncertaintyDraft u : raw) {
            if (u == null || u.note() == null || u.note().isBlank()) {
                continue;
            }
            if (UncertaintyKind.isKnown(u.kind())) {
                normalized.add(u);
            } else {
                normalized.add(new UncertaintyDraft(UncertaintyKind.UNCONFIRMED.name(),
                        u.note(), u.ref(), u.blockedBy()));
            }
        }
        return normalized;
    }

    /** 显式枚举值解析：非法值返回 null（交给 {@link AcceptanceKind#parse} 做既有回落）。 */
    private static String parseExplicitKind(String raw) {
        try {
            return AcceptanceKind.valueOf(raw.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, Long> currentCodeToId(Long taskId) {
        Map<String, Long> map = new HashMap<>();
        for (PlanDraftView v : listByTask(taskId)) {
            if (v.code() != null) {
                map.put(v.code(), v.id());
            }
        }
        return map;
    }

    private static PlanDraftView toView(PlanDraft d) {
        return PlanDraftViewMapper.toView(d);
    }
}