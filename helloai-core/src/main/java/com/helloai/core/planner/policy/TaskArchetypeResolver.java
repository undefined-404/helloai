package com.helloai.core.planner.policy;

import com.helloai.common.constant.TaskArchetype;

import java.util.Map;

/**
 * `B3`：终稿需求包 → {@code task.task_archetype} 的判定（纯函数，无 IO、无 Spring、可单测）。
 *
 * <p><b>要解决的是什么</b>：`TD-4` 的回落规则本身是对的（判不出就回落 {@code BUILD}，
 * 不猜——猜错会产出「看起来合理但结构错位」的计划），但原来的写法**把三种情况压成同一个
 * 返回值**：模型显式判成 {@code BUILD} / 模型根本没给这个键 / 模型给了非法值，落库全是
 * {@code BUILD}，事后无法区分「判断」与「兜底」。</p>
 *
 * <p>2026-10-25 真机现场就是这样：一份《三家竞品核心功能与定价分析报告》拿到
 * {@code task_archetype=BUILD}，而终稿包顶层键只有
 * {@code goal / scope / outOfScope / assumptions / openQuestions / acceptanceCriteria}——
 * {@code archetype} **根本没出现**。提示词 {@code requirement-finalize.md} 第 93 条写的是
 * 「必填」，实际没有执行，也没有任何信号（`LOG-20261025-018`）。</p>
 *
 * <p><b>本类只负责「判」，可见性由调用方负责</b>：{@link Decision#fallbackReason()} 非空即表示
 * 走了回落，调用方（{@code ClarifyFinalizeServiceImpl}）必须记一条
 * {@code task_archetype_fallback} 时间线事件。<b>回落本身不阻断建任务</b>——缺一个分类维度
 * 不该让整条链路停下，只是不能悄悄发生。</p>
 *
 * <p><b>为什么不能拿 {@link TaskArchetype#parse} 的结果反推「是否显式声明」</b>：它对
 * null / 空白 / 非法值一律回落 {@code BUILD}，返回值恒非 null ⇒ 用它判断显式性恒为 true。
 * 必须用原始字符串 + {@link TaskArchetype#isKnown} 判定（同类陷阱见 `TD-5` 的
 * {@code AcceptanceKind.parse}，判据见 `MEMORY` 第 ⑩ 条）。</p>
 */
public final class TaskArchetypeResolver {

    /** 终稿包为空（连需求包都没有，只有标题/描述）。 */
    public static final String REASON_PACKAGE_EMPTY = "package_empty";

    /** 终稿包存在但**没有** {@code archetype} 键——提示词要求必填却未产出。 */
    public static final String REASON_KEY_MISSING = "key_missing";

    /** 给了键但值不在六值白名单内。 */
    public static final String REASON_INVALID_VALUE = "invalid_value";

    private TaskArchetypeResolver() {
    }

    /**
     * 判定结果。
     *
     * @param value          最终落库值（六值之一）
     * @param fallbackReason 非空 = 走了回落，取值见 {@code REASON_*} 常量
     * @param rawValue       模型给的原始字符串（键缺失时为 {@code null}，供排障时看原文）
     */
    public record Decision(String value, String fallbackReason, String rawValue) {

        /** 是否走了回落（= 需要调用方记事件）。 */
        public boolean fallback() {
            return fallbackReason != null;
        }
    }

    /**
     * 判定 {@code archetype}。
     *
     * @param finalPackage 会话终稿需求包（可为 {@code null} / 空）
     */
    public static Decision resolve(Map<String, Object> finalPackage) {
        String fallback = TaskArchetype.DEFAULT().name();
        if (finalPackage == null || finalPackage.isEmpty()) {
            return new Decision(fallback, REASON_PACKAGE_EMPTY, null);
        }
        Object raw = finalPackage.get(RequirementPackageParser.CONTEXT_KEY_ARCHETYPE);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return new Decision(fallback, REASON_KEY_MISSING, null);
        }
        String rawText = String.valueOf(raw).trim();
        if (!TaskArchetype.isKnown(rawText)) {
            return new Decision(fallback, REASON_INVALID_VALUE, rawText);
        }
        return new Decision(TaskArchetype.parse(rawText).name(), null, rawText);
    }
}
