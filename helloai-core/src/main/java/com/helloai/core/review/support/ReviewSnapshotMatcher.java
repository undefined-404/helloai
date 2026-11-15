package com.helloai.core.review.support;

import com.helloai.core.task.port.AttachmentView;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 判定快照对账器（`RV-5`，纯静态）。
 *
 * <p>核对当前可读附件集合与产出摘要是否与原判快照一致：一致照常抽检；不一致说明复判测到的是返工后另一份产出，
 * 调用方按 {@code snapshot_drift} 跳过（不写 {@code review_recheck_log}）。无快照的历史行按「一致」处理（不上溯改写既有行为）。</p>
 *
 * <p>附件按 {@code id|fileSize} 集合比（JSONB 数字统一转字符串避免 Integer/Long 类型差）；
 * 快照截断（{@code attachmentsTruncated=true}）时只校验快照所列条目当前仍在，不要求集合相等。</p>
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