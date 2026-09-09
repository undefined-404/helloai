package com.helloai.core.planner.policy;

import java.util.List;

/**
 * 需求包（G-011 澄清终稿结构化准入产物，5 字段压缩版）。
 *
 * <p>五字段形态与语义（三方建议并集的裁剪口径，见设计 D1）：
 * <ul>
 *   <li>{@code goal}：可验收目标（一句话）；</li>
 *   <li>{@code scope}：需求范围条目；</li>
 *   <li>{@code outOfScope}：明确不做项——拆解侧边界硬约束（outOfScope 内条目不得出现在
 *       任何子任务的目标 / 内容 / 交付物中）；</li>
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
        List<String> assumptions,
        List<String> openQuestions) {

    /** 全空需求包（无任何字段内容时的等价形态；渲染输出占位文案）。 */
    public static final RequirementPackage EMPTY =
            new RequirementPackage(null, List.of(), List.of(), List.of(), List.of());

    /** 是否为空需求包：五字段全无内容（goal 为空且四数组全空）。 */
    public boolean isEmpty() {
        return (goal == null || goal.isBlank())
                && scope.isEmpty()
                && outOfScope.isEmpty()
                && assumptions.isEmpty()
                && openQuestions.isEmpty();
    }
}