package com.helloai.core.planner.policy;

import java.util.List;

/**
 * 需求包（G-011 澄清终稿结构化准入产物）。
 *
 * <p>字段形态与语义（G-011 D1 压缩版 + P1 修订补回任务级验收条目）：
 * <ul>
 *   <li>{@code goal}：可验收目标（一句话）；</li>
 *   <li>{@code scope}：需求范围条目；</li>
 *   <li>{@code outOfScope}：明确不做项——拆解侧边界硬约束（outOfScope 内条目不得出现在
 *       任何子任务的目标 / 内容 / 交付物中）；</li>
 *   <li>{@code acceptanceCriteria}：任务级验收条目（用户视角、封闭集合，P1 新增）——
 *       拆解侧据此做「封闭集合全覆盖」校验，子任务 acceptance 适用时须回溯锚定到其中一条；
 *       空数组 = 本任务不做回溯要求（存量任务 / 未走澄清链路），行为等于现状；</li>
 *   <li>{@code assumptions}：关键假设（推断项，须标注）——与子任务强相关的条目经拆解继承
 *       为 uncertainties(kind=ASSUMPTION)；</li>
 *   <li>{@code openQuestions}：待确认 / 阻断项——与子任务相关的条目必须继承为
 *       uncertainties(kind=UNCONFIRMED)。</li>
 * </ul>
 * 解析与渲染统一走 {@link RequirementPackageParser}（防御式：键缺失 / 类型异常回落空集合，
 * 与旧数据「无需求包」行为完全一致）。本对象仅为解析结果载体，不承担落库职责
 * （持久化为 requirement_conversation.final_package / task.context.requirementPackage 的 JSONB 形态）。</p>
 */
public record RequirementPackage(
        String goal,
        List<String> scope,
        List<String> outOfScope,
        List<String> acceptanceCriteria,
        List<String> assumptions,
        List<String> openQuestions) {

    /** 全空需求包（无任何字段内容时的等价形态；渲染输出占位文案）。 */
    public static final RequirementPackage EMPTY =
            new RequirementPackage(null, List.of(), List.of(), List.of(), List.of(), List.of());

    /** 是否为空需求包：六字段全无内容（goal 为空且五数组全空）。 */
    public boolean isEmpty() {
        return (goal == null || goal.isBlank())
                && scope.isEmpty()
                && outOfScope.isEmpty()
                && acceptanceCriteria.isEmpty()
                && assumptions.isEmpty()
                && openQuestions.isEmpty();
    }
}
