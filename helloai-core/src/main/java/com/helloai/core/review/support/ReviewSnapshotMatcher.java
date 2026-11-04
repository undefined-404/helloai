package com.helloai.core.review.support;

import com.helloai.core.task.port.AttachmentView;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 判定快照对账器（`RV-5`，纯静态）。
 *
 * <p><b>判什么</b>：当前可读附件集合与执行产出摘要，是否与「原判定时的快照」一致。
 * 一致 ⇒ 复判对象就是原判对象，照常抽检；不一致 ⇒ 复判测到的已是返工后的另一份产出，
 * 本轮抽检不构成有效度量（调用方按 `snapshot_drift` 跳过，不写 `review_recheck_log`）。</p>
 *
 * <p><b>没有快照（历史行）按「一致」处理</b>：历史记录无从对账，跳过会把所有老数据排除出抽检；
 * 这里的取舍与 `RV-1`「先只标不拒」同向——新增能力不上溯改写既有行为。</p>
 *
 * <p><b>比对口径</b>：附件按 {@code id|fileSize} 集合比（JSONB 读回的数字类型可能是
 * Integer/Long，统一转字符串避免类型差误判）；快照被截断（{@code attachmentsTruncated=true}）时
 * 只校验「快照里列出的条目当前仍在」，不要求两边集合相等。</p>
 */
public final class ReviewSnapshotMatcher {

    /** 对账结论：{@code matched=false} 时 {@code reason} 说明漂移类型（进事件 payload）。 */
    public record Drift(boolean matched, String reason) {

        public static Drift same() {
            return new Drift(true, null);
        }

        public static Drift changed(String reason) {
            return new Drift(false, reason);
        }
    }

    private ReviewSnapshotMatcher() {
    }

    /**
     * 对账。
     *
     * @param snapshot           原判定快照（可空 ⇒ 历史行，按一致处理）
     * @param currentAttachments 当前可读物化附件
     * @param currentOutput      当前执行产出全文（只取摘要比对）
     */
    public static Drift check(Map<String, Object> snapshot, List<AttachmentView> currentAttachments,
                              String currentOutput) {
        if (snapshot == null || snapshot.isEmpty()) {
            return Drift.same();
        }
        Object rawAttachments = snapshot.get("attachments");
        if (!(rawAttachments instanceof List<?> expectedItems)) {
            // 快照结构不认识（未来版本 / 脏数据）：不拦（宁可少拦，避免把抽检整体停掉）
            return Drift.same();
        }
        Set<String> current = new HashSet<>();
        if (currentAttachments != null) {
            for (AttachmentView attachment : currentAttachments) {
                if (attachment != null) {
                    current.add(fingerprint(attachment.id(), attachment.fileSize()));
                }
            }
        }
        Set<String> expected = new HashSet<>();
        for (Object item : expectedItems) {
            if (item instanceof Map<?, ?> entry) {
                expected.add(fingerprint(entry.get("id"), entry.get("fileSize")));
            }
        }
        boolean truncated = Boolean.TRUE.equals(snapshot.get("attachmentsTruncated"));
        boolean attachmentsMatch = truncated ? current.containsAll(expected) : current.equals(expected);
        if (!attachmentsMatch) {
            return Drift.changed("attachments_changed");
        }
        Object rawOutput = snapshot.get("output");
        if (rawOutput == null) {
            return currentOutput == null ? Drift.same() : Drift.changed("output_appeared");
        }
        if (!(rawOutput instanceof Map<?, ?> output)) {
            return Drift.same();
        }
        Object expectedHash = output.get("sha256");
        String currentHash = currentOutput == null ? null : ReviewSnapshotBuilder.sha256(currentOutput);
        if (expectedHash == null || !expectedHash.equals(currentHash)) {
            return Drift.changed("output_changed");
        }
        return Drift.same();
    }

    /** JSONB 数字类型不固定，统一字符串化后比较。 */
    private static String fingerprint(Object id, Object fileSize) {
        return String.valueOf(id) + "|" + String.valueOf(fileSize);
    }
}