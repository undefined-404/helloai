package com.helloai.common.constant;

/**
 * 任务约束类型（`G-037` 任务约束类型化试点）。
 *
 * <p><b>本枚举是 {@code kind} 值域的唯一定义源</b>：{@code task.entity.ConstraintRef} 与
 * 各层投影（Draft / View / Snapshot）里的 kind 字符串常量均由此派生。放在
 * {@code helloai-common} 是因为拆解侧（planner 域）与执行侧（agent 域）都要识别 kind，
 * 而它们均不能反向依赖 {@code task.entity}。</p>
 *
 * <ul>
 *   <li>{@link #TEXT}：内联裁决约束——几句话的硬性边界（"不许改的事"），随任务描述走；
 *       试点期不落 {@code constraint_refs}（现有 {@code sub_task.constraints} 已承载）；</li>
 *   <li>{@link #DOCUMENT}：契约文档引用——一份完整规范文档的引用（ref + 章节子集 scope），
 *       正文经 {@code internal_load_constraint_doc} 按需读取，不注入上下文；</li>
 *   <li>{@link #SKILL}：技能约束（预留）——ref 指向平台技能注册表，正文经
 *       {@code internal_load_skill} 按需读取；枚举位先留，试点不接线。</li>
 * </ul>
 *
 * <p><b>解析失败回落 {@code DOCUMENT}？不</b>——非法 kind 值一律按「未知约束」丢弃并在
 * 落库侧 WARN（fail-safe：宁可少一条引用，不把一个文档误判成另一份）。</p>
 */
public enum ConstraintRefKind {

    TEXT,
    DOCUMENT,
    SKILL;

    /** 值域白名单判定（null / 未知值 → false）。 */
    public static boolean isKnown(String value) {
        if (value == null) {
            return false;
        }
        for (ConstraintRefKind k : values()) {
            if (k.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}