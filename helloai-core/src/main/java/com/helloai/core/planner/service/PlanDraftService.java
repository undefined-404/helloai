package com.helloai.core.planner.service;

import com.helloai.core.planner.port.PlanDraftView;
import com.helloai.core.task.port.UncertaintyDraft;

import java.util.List;
import java.util.Map;

/**
 * 草案暂存区读服务（`AD-5a-3`）。
 *
 * <p>只读列表给草案审阅页使用；写路径在 5a-3 的后续切片迁入。</p>
 */
public interface PlanDraftService {

    /**
     * 某任务当前轮（{@code max(round)}）的 ACTIVE 草案，按 {@code code} 升序。
     *
     * <p>暂存区没有当前轮行时返回空列表（存量任务在 5a-4 迁移前仍走旧读法，由调用方回落）。</p>
     */
    List<PlanDraftView> listByTask(Long taskId);

    /** 按 code 取当前 ACTIVE 行（不存在返回 null）。 */
    PlanDraftView getByCode(Long taskId, String code);

    /** 落库后回写依赖（code 数组）。 */
    void applyDependencies(Long taskId, Map<String, List<String>> dependsOnCodes);

    /** 契约位归一后把被降级行置 `contract=0`。 */
    void demoteContracts(Long taskId, List<String> codes);

    /**
     * 按 code 编辑（`AD-5a-3b-2b`）：直接写暂存区；写前跑单条门禁，block 即拒。
     *
     * <p>语义与执行表版 {@code SubTaskService#updateDraft} 对齐：空白 note 的 uncertainty 丢弃、
     * 非法 kind 降级 {@code UNCONFIRMED}、显式声明写 {@code context.acceptanceKindDeclared}、
     * 验收文本被改写时清掉按旧文本算出的 {@code acceptancePreconditions}。</p>
     */
    PlanDraftView updateByCode(Long taskId, String code, String content, String deliverable,
                               String acceptance, List<String> requiredSkills, String constraints,
                               List<UncertaintyDraft> uncertainties, String acceptanceKind);
}