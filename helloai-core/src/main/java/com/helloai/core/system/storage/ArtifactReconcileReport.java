package com.helloai.core.system.storage;

import java.util.List;

/**
 * 存储对账一轮的结果快照（DB {@code attachment} / {@code chat_attachment} ↔ 对象存储真实对象）。
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
 * <p><b>为何有三类引用行</b>：会话附件（{@code chat_attachment}，planner 域）、子任务产物
 * （{@code attachment}，task 域）与技能包原始 zip（{@code skill_package.origin_zip_url}，
 * agent 域）共用同一个桶，仅 objectKey 前缀不同。孤儿判定必须让三类记录<b>共同</b>作为
 * 「被引用集合」——只认其中一类，另一类的对象会整批被判成孤儿。</p>
 *
 * @param protocol              参与对账的平台协议（{@code minio://} / {@code local://}）
 * @param bucket                参与对账的桶
 * @param attachmentRows        子任务产物引用行数（含逻辑删除，保守口径）
 * @param chatAttachmentRows    会话附件引用行数（含逻辑删除，保守口径）
 * @param skillPackageRows      技能包原始 zip 引用行数（含逻辑删除，保守口径）
 * @param objectCount           桶内真实对象数
 * @param dangling              悬空对象键清单（三类记录合并后仍找不到对象）
 * @param orphaned              孤儿对象键清单（三类记录都未引用）
 * @param orphanedChatPrefixed  {@link #orphaned()} 中 objectKey 以 {@code chat-} 开头的子集。
 *                              <b>仅为定位方便，按前缀推断来源，不参与任何判定</b>——
 *                              归属者注册名恰好以 {@code chat-} 开头的产物也会落在这里。
 * @param sizeMismatch          字节数不符描述清单
 * @param removed               本轮实际删除的孤儿对象键清单（未开启清理时为空）
 * @param cleanupEnabled        本轮清理开关状态（供上层判断 removed 为空是"没开启"还是"没得删"）
 */
public record ArtifactReconcileReport(
        String protocol,
        String bucket,
        int attachmentRows,
        int chatAttachmentRows,
        int skillPackageRows,
        int objectCount,
        List<String> dangling,
        List<String> orphaned,
        List<String> orphanedChatPrefixed,
        List<String> sizeMismatch,
        List<String> removed,
        boolean cleanupEnabled) {

    public int danglingCount() {
        return dangling.size();
    }

    public int orphanedCount() {
        return orphaned.size();
    }

    /** 孤儿中疑似来自会话附件的数量（按 {@code chat-} 前缀推断）。 */
    public int chatPrefixedOrphanedCount() {
        return orphanedChatPrefixed.size();
    }

    /** 孤儿中疑似来自子任务产物的数量（总数减去会话附件前缀部分）。 */
    public int otherOrphanedCount() {
        return orphaned.size() - orphanedChatPrefixed.size();
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
