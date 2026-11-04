package com.helloai.core.review.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.helloai.core.review.service.SubTaskReviewService.ReviewVerdict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 验收点覆盖清单解析器（`RV-3`，纯静态防御归一）。
 *
 * <p><b>契约</b>（与 {@code prompts/subtask-review.md} 输出格式同源）：每条形如</p>
 * <pre>
 * {"criterionRef":"验收标准原文子串","status":"covered|failed|not_verifiable",
 *  "method":"attachment|sandbox","evidenceRef":"附件名/台账条目","note":"说明"}
 * </pre>
 *
 * <p><b>承接策略与 {@code missingEvidence} 完全一致</b>：非数组 / 元素非对象 / 键缺失一律降级
 * 丢弃，不抛异常、不影响判定解析（{@link ReviewVerdict#coverage} 用 {@code JsonNode} 裸接，
 * 就是为了不让模型的畸形输出击穿整条判定）。上限 {@value #MAX_COVERAGE_ITEMS} 条。</p>
 *
 * <p><b>不做</b>：不校验 {@code criterionRef} 是否真是验收标准子串（那是
 * {@link ReviewCoverageCritic} 的判据，需要 acceptance 在手）。</p>
 */
public final class ReviewCoverageParser {

    /** 覆盖判定的唯一「已覆盖」取值：{@code failed} / {@code not_verifiable} 都不算覆盖。 */
    public static final String STATUS_COVERED = "covered";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_NOT_VERIFIABLE = "not_verifiable";

    /**
     * 计入覆盖的证据方式：附件正文与沙箱一手执行。
     *
     * <p><b>没有 {@code source}</b>（实施计划 §3 `RV-3`）：reviewer 的证据面只有「产出文本 +
     * 物化附件正文 + 验证信号」，看不到源码树——{@code source} 会是永远填不出也永远核不了的枚举。
     * 需要源码级验证时走 {@code sandbox}，{@code evidenceRef} 指向台账条目。</p>
     *
     * <p><b>「产出文本自述」不在白名单内</b>：提示词第 12 条禁止以自报值当证据，因此
     * {@code method=text}（或任意其它值）的 {@code covered} 一律<b>不计入覆盖</b>。</p>
     */
    public static final String METHOD_ATTACHMENT = "attachment";
    public static final String METHOD_SANDBOX = "sandbox";

    /** 覆盖清单条目上限（与 {@code missingEvidence} 同一防御口径）。 */
    public static final int MAX_COVERAGE_ITEMS = 20;

    /**
     * 单条覆盖声明。
     *
     * @param criterionRef 验收标准原文子串（覆盖归属的键）
     * @param status       归一化（小写 trim）后的状态；缺失为空串
     * @param method       证据方式；缺失为空串
     * @param evidenceRef  证据指向；缺失为空串
     * @param note         补充说明；缺失为空串
     */
    public record CoverageItem(String criterionRef, String status, String method,
                               String evidenceRef, String note) {

        /** 引用是否真的指向验收标准原文（由调用方按 acceptance 判定）。 */
        public boolean hasRef() {
            return criterionRef != null && !criterionRef.isBlank();
        }

        /** 状态是否写了 {@code covered}（不看 method，那是另一条判据）。 */
        public boolean claimsCovered() {
            return STATUS_COVERED.equals(status);
        }

        /** 证据方式是否落在「附件 / 沙箱」白名单内。 */
        public boolean hasAdmissibleMethod() {
            return METHOD_ATTACHMENT.equals(method) || METHOD_SANDBOX.equals(method);
        }

        /** 是否构成「这条验收点被合格证据覆盖」。 */
        public boolean covers() {
            return hasRef() && claimsCovered() && hasAdmissibleMethod();
        }
    }

    private ReviewCoverageParser() {
    }

    /**
     * 归一化解析覆盖清单。
     *
     * @param raw {@link ReviewVerdict#getCoverage()} 裸节点（可空、可非数组）
     * @return 条目清单（永不返回 null；超限截断；畸形元素跳过）
     */
    public static List<CoverageItem> parse(JsonNode raw) {
        if (raw == null || !raw.isArray() || raw.isEmpty()) {
            return List.of();
        }
        List<CoverageItem> items = new ArrayList<>();
        for (JsonNode node : raw) {
            if (items.size() >= MAX_COVERAGE_ITEMS) {
                break;
            }
            CoverageItem item = toItem(node);
            if (item != null) {
                items.add(item);
            }
        }
        return List.copyOf(items);
    }

    /** 单条归一：非对象、或五键全空 ⇒ 丢弃（返回 null）。 */
    private static CoverageItem toItem(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String criterionRef = text(node.get("criterionRef"));
        String status = lower(text(node.get("status")));
        String method = lower(text(node.get("method")));
        String evidenceRef = text(node.get("evidenceRef"));
        String note = text(node.get("note"));
        if (criterionRef.isEmpty() && status.isEmpty() && method.isEmpty()
                && evidenceRef.isEmpty() && note.isEmpty()) {
            return null;
        }
        return new CoverageItem(criterionRef, status, method, evidenceRef, note);
    }

    /** 落库 / 事件用的结构化 Map（保留原始五键，键名与提示词契约一致）。 */
    public static List<Map<String, String>> toMaps(List<CoverageItem> items) {
        List<Map<String, String>> result = new ArrayList<>(items.size());
        for (CoverageItem item : items) {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("criterionRef", item.criterionRef());
            map.put("status", item.status());
            map.put("method", item.method());
            map.put("evidenceRef", item.evidenceRef());
            map.put("note", item.note());
            result.add(map);
        }
        return result;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isObject() || node.isArray()) {
            return "";
        }
        String value = node.asText();
        return value == null ? "" : value.trim();
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
