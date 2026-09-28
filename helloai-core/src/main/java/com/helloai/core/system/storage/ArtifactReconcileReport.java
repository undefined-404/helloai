package com.helloai.core.system.storage;

import java.util.List;

/**
 * 存储对账一轮的结果快照（DB {@code attachment} ↔ 对象存储真实对象）。
 *
 * <p>三个不一致方向：</p>
 * <ul>
 *   <li>{@link #dangling()} —— DB 有记录、对象缺失。附件预览 / 证据核验会 404，
 *       典型成因是运行环境切换（DB 共享、对象存储各环境一份）。</li>
 *   <li>{@link #orphaned()} —— 对象存在、无任何 attachment 行引用（含逻辑删除行）。
 *       典型成因是 {@code store()} 成功而 {@code register()} 失败，或任务被物理级联删除。</li>
 *   <li>{@link #sizeMismatch()} —— 双侧都在但字节数不符，说明对象被覆盖或截断。</li>
 * </ul>
 *
 * @param protocol        参与对账的平台协议（{@code minio://} / {@code local://}）
 * @param bucket          参与对账的桶
 * @param attachmentRows  参与对账的 attachment 行数（含逻辑删除，保守口径）
 * @param objectCount     桶内真实对象数
 * @param dangling        悬空对象键清单
 * @param orphaned        孤儿对象键清单
 * @param sizeMismatch    字节数不符描述清单
 * @param removed         本轮实际删除的孤儿对象键清单（未开启清理时为空）
 * @param cleanupEnabled  本轮清理开关状态（供上层判断 removed 为空是"没开启"还是"没得删"）
 */
public record ArtifactReconcileReport(
        String protocol,
        String bucket,
        int attachmentRows,
        int objectCount,
        List<String> dangling,
        List<String> orphaned,
        List<String> sizeMismatch,
        List<String> removed,
        boolean cleanupEnabled) {

    public int danglingCount() {
        return dangling.size();
    }

    public int orphanedCount() {
        return orphaned.size();
    }

    public int sizeMismatchCount() {
        return sizeMismatch.size();
    }

    public int removedCount() {
        return removed.size();
    }

    /** 三个方向均无不一致。 */
    public boolean consistent() {
        return dangling.isEmpty() && orphaned.isEmpty() && sizeMismatch.isEmpty();
    }
}
