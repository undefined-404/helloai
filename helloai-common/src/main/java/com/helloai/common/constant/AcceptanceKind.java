package com.helloai.common.constant;

/**
 * 子任务验收判据（`TD-2`，任务拆解领域无关化设计 §4）。
 *
 * <p><b>解决的问题</b>：{@code sub_task.acceptance} 是一段自由文本。让模型写「判定动作 + 预期结果」
 * 在代码场景有效（有命令可跑），在非代码场景只能编。所以必须显式声明「<b>谁来判、怎么判</b>」。</p>
 *
 * <p>值域对齐 {@code sub_task.acceptance_kind}（VARCHAR(16) + CHECK 约束
 * {@code chk_sub_task_acceptance_kind}，迁移 {@code V125}）。</p>
 *
 * <p><b>它管的是「怎么判」，不参与「怎么拆」</b>——拆解维度由 {@link TaskArchetype} 在任务级
 * 统一决定，子任务级差异只影响验收方式（设计 §2）。</p>
 *
 * <p><b>不按 {@code acceptance} 文本自动推断 kind</b>：推断错会让校验走错分支，比人工 / 模型
 * 显式声明更危险（计划 §3 `TD-2`「不做的」）。</p>
 */
public enum AcceptanceKind {

    /** 机器判：交付物是代码 / 配置 / 数据文件——acceptance 必须给出可执行命令或断言并含预期输出。 */
    AUTOMATED,

    /** 规则 + 机器判：交付物是文档 / 报告 / 清单——acceptance 必须是可数的结构断言（章节数 / 条目数 / 字数下限 / 字段齐全）。 */
    STRUCTURAL,

    /** 审查 agent 判：交付物是方案 / 设计 / 分析——acceptance 必须给出可逐条打勾的审查清单（≥2 条独立检查项）。 */
    REVIEWED,

    /** 人判：交付物是决策 / 创意 / 对外沟通 / 现场动作——acceptance 必须写明「谁 / 什么时点 / 留什么痕」。 */
    ATTESTED;

    /** 默认判据：与改造前行为一致（`AUTOMATED` 校验即既有的 VERIFICATION 围栏口径）。 */
    public static AcceptanceKind DEFAULT() {
        return AUTOMATED;
    }

    /** 归一化：null / 空白 / 非法值回落 {@code AUTOMATED}；返回枚举 {@code name()} 字符串（与表列 VARCHAR 对齐）。 */
    public static String normalize(String value) {
        return parse(value).name();
    }

    /** 解析：null / 空白 / 非法值回落 {@code AUTOMATED}。 */
    public static AcceptanceKind parse(String value) {
        if (value == null || value.isBlank()) {
            return AUTOMATED;
        }
        String upper = value.trim().toUpperCase();
        for (AcceptanceKind kind : values()) {
            if (kind.name().equals(upper)) {
                return kind;
            }
        }
        return AUTOMATED;
    }
}
