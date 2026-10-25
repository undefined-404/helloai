package com.helloai.common.constant;

/**
 * 任务交付物原型（`TD-1`，任务拆解领域无关化设计 §3）。
 *
 * <p><b>分类依据是「怎么判定完成」，不是行业</b>——拆解的边界由判据决定，同一个行业的
 * 不同交付物可以有完全不同的判据。值域对齐 {@code task.task_archetype}（VARCHAR(16) +
 * CHECK 约束 {@code chk_task_archetype}，迁移 {@code V124}）。</p>
 *
 * <p><b>它管的是「按什么维度拆」，与 {@code GRANULARITY}（拆多细）正交、不互相替代</b>
 * （设计 §7 第 5 条）。</p>
 *
 * <p><b>判定时机</b>：只在澄清终稿产出时判（`TD-4`），<b>不在拆解时判</b>——拆解阶段模型
 * 只见 title + description，判不准（设计 §3.2）。判不出回落 {@link #BUILD}，
 * <b>不做猜测性判定</b>（猜错会产出「看起来合理但结构错位」的计划）。</p>
 */
public enum TaskArchetype {

    /** 交付可运行的系统 / 功能 / 修复：拆模块分层，多模块协作保留契约先行，验收靠编译 / 测试 / lint。 */
    BUILD,

    /** 交付成篇文档 / 报告 / 方案：每个子任务对应「读者要回答的一个问题」，不按章节版面拆。 */
    DOCUMENT,

    /** 交付结论 / 调研判断：问题分解树（子问题互斥且合并覆盖），交付物必须含证据 + 结论 + 置信度。 */
    RESEARCH,

    /** 交付数据集 / 分析结果 / 指标：按数据流阶段拆，阶段之间有输入 / 输出 schema 契约。 */
    DATA,

    /** 交付一次状态变更（发布 / 巡检 / 应急）：按时间轴节点拆，依赖锚由「时点」表达，必须有回滚节点。 */
    OPERATE,

    /** 交付一个决策 / 取舍结论：选项生成 → 维度评估 → 推荐，只到「建议」，不含执行动作。 */
    DECIDE;

    /**
     * 默认原型。
     *
     * <p>判不出时回落 {@code BUILD} 而不是「留空」——留空会让下游每条消费链各自决定怎么处理空值，
     * 而 {@code BUILD} 恰好等于改造前的行为，回落即「零行为变化」。</p>
     */
    public static TaskArchetype DEFAULT() {
        return BUILD;
    }

    /** 归一化：null / 空白 / 非法值回落 {@code BUILD}；返回枚举 {@code name()} 字符串（与表列 VARCHAR 对齐）。 */
    public static String normalize(String value) {
        return parse(value).name();
    }

    /**
     * 是否为白名单值（**不做回落判断**）。
     *
     * <p><b>为什么必须有这个入口</b>：{@link #parse} 对 null / 空白 / 非法值一律回落
     * {@code BUILD}，于是「模型显式给了 BUILD」与「模型没给、被回落成 BUILD」在
     * {@code parse} 的返回值上**完全一样**（同 {@code UncertaintyKind} 的 `isKnown` 与
     * `AcceptanceKind.parse` 那类陷阱）。要判断「是否显式声明」只能看原始字符串，
     * 不能用 parse 的结果反推。</p>
     */
    public static boolean isKnown(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String upper = value.trim().toUpperCase();
        for (TaskArchetype archetype : values()) {
            if (archetype.name().equals(upper)) {
                return true;
            }
        }
        return false;
    }

    /** 解析：null / 空白 / 非法值回落 {@code BUILD}。 */
    public static TaskArchetype parse(String value) {
        if (value == null || value.isBlank()) {
            return BUILD;
        }
        String upper = value.trim().toUpperCase();
        for (TaskArchetype archetype : values()) {
            if (archetype.name().equals(upper)) {
                return archetype;
            }
        }
        return BUILD;
    }

    /** 是否为「计划可以事先画出」的静态 DAG 类型（`TD-10` ledger 的触发判据，设计 §6.1）。 */
    public boolean staticPlanFriendly() {
        return this != RESEARCH && this != DECIDE;
    }
}
