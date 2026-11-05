package com.helloai.core.review.support;

import com.helloai.core.task.port.AttachmentView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 判定快照构建器（`RV-5`，纯静态、零 Spring 依赖）。
 *
 * <p><b>为什么需要快照</b>：抽检复判读的是 {@code subTaskService.getView(...)} 的<b>当前</b>视图；
 * 对已 `REJECTED` 的记录，复判时产出可能已被返工覆盖 ⇒ 测到的不是原始判定对象。快照把
 * 「判定当时看到了什么」固定下来，复判前先对账（见 {@link ReviewSnapshotMatcher}）。</p>
 *
 * <p><b>只存摘要、不存全文</b>（实施计划 §3 `RV-5`）：附件只记 {@code id / fileName / fileSize}，
 * 执行产出只记 {@code chars / sha256}。附件本体在对象存储、全文在 {@code context} 里，快照存的是
 * 「引用 + 指纹」，存储增长可控（体积阈值见 {@link #WARN_BYTES}）。</p>
 *
 * <p><b>为什么附件用 {@code id + fileSize} 当"版本"</b>：附件表没有 version 列，重新物化会新增一行
 * （id 变），同 id 内容变化则 {@code fileSize} 变；两者合起来足以发现「复判对象被换掉」。
 * 不引入新列是本项的取舍：追版本号需要在物化链路维护版本计数，收益低于「能发现漂移」。</p>
 */
public final class ReviewSnapshotBuilder {

    /**
     * 快照结构版本（新增字段时递增，消费方据此兼容）。
     *
     * <p>v2（2026-11-05，`RV-6`）：驳回记录增可选 {@code frozenEvidence}（原判证据五段文本），
     * 复判直接复用；v1 快照无该字段，消费方按「无冻结证据」处理。</p>
     */
    public static final int SCHEMA_VERSION = 2;

    /** 附件条目上限（防异常子任务把 JSONB 撑大）。 */
    public static final int MAX_ATTACHMENTS = 50;

    /** 体积告警阈值（字节）：超过只告警不阻断，用于「存储增长」观测。 */
    public static final int WARN_BYTES = 8 * 1024;

    private ReviewSnapshotBuilder() {
    }

    /**
     * 构建快照。
     *
     * @param attachments 核验当时可读物化附件（调用方传入与证据装配同一来源）
     * @param output      核验当时的执行产出全文（只取其摘要，不入快照）
     * @return JSONB 可落库的有序 Map（键序固定，便于测试与人工比对）
     */
    public static Map<String, Object> build(List<AttachmentView> attachments, String output) {
        return build(attachments, output, null);
    }

    /**
     * 构建快照（可附冻结证据）。
     *
     * @param frozenEvidence 驳回记录的原判证据（`RV-6`，见 {@link ReviewEvidenceView}）；
     *                       null / 空 ⇒ 不写入该字段（通过记录与历史记录保持原结构）
     */
    public static Map<String, Object> build(List<AttachmentView> attachments, String output,
                                            Map<String, Object> frozenEvidence) {
        List<Map<String, Object>> items = new ArrayList<>();
        int total = attachments == null ? 0 : attachments.size();
        if (attachments != null) {
            for (AttachmentView attachment : attachments) {
                if (attachment == null || items.size() >= MAX_ATTACHMENTS) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", attachment.id());
                item.put("fileName", attachment.fileName());
                item.put("fileSize", attachment.fileSize());
                items.add(item);
            }
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", SCHEMA_VERSION);
        snapshot.put("attachmentCount", total);
        snapshot.put("attachmentsTruncated", total > items.size());
        snapshot.put("attachments", items);
        snapshot.put("output", output == null ? null
                : Map.of("chars", output.length(), "sha256", sha256(output)));
        if (frozenEvidence != null && !frozenEvidence.isEmpty()) {
            snapshot.put("frozenEvidence", frozenEvidence);
        }
        return snapshot;
    }

    /** 快照体积估算（JSON 等价内容的 UTF-8 字节数）：用于上限告警，不引第三方序列化。 */
    public static int estimatedBytes(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return 0;
        }
        StringBuilder estimate = new StringBuilder();
        appendEstimate(estimate, snapshot);
        return estimate.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /** 文本 sha256（十六进制小写）；空文本也返回其摘要。 */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须提供 SHA-256；真缺了也只能返回空串（由调用方按「摘要缺失」处理，不抛）
            return "";
        }
    }

    private static void appendEstimate(StringBuilder estimate, Object value) {
        if (value == null) {
            estimate.append("null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            estimate.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    estimate.append(',');
                }
                first = false;
                estimate.append('"').append(entry.getKey()).append("\":");
                appendEstimate(estimate, entry.getValue());
            }
            estimate.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            estimate.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    estimate.append(',');
                }
                appendEstimate(estimate, list.get(i));
            }
            estimate.append(']');
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            estimate.append(value);
            return;
        }
        estimate.append('"').append(value).append('"');
    }
}