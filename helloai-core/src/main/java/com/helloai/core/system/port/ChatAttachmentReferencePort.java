package com.helloai.core.system.port;

import java.util.List;

/**
 * 会话附件引用读取端口（§6.146 端口反转）：与 {@link ArtifactReferencePort} 并列，
 * 专指 {@code chat_attachment} 表（需求对话挂载的文件），供存储对账巡检使用。
 *
 * <p><b>为什么要单开一个端口，而不是给 {@link ArtifactReferencePort} 加方法？</b>
 * 端口实现必须落在单个域，而两张表分属两个域：{@code attachment} 在 task 域、
 * {@code chat_attachment} 在 planner 域。若合并为一个接口，实现方必然要跨域读另一张表
 * ——task 域读 planner 是反向依赖（§6 禁止），因此只能拆成两个端口、各自在所属域实现。</p>
 *
 * <p>由 planner 域 {@code ChatAttachmentReferencePortAdapter} 实现：{@code planner → system}
 * 属合法向下依赖。</p>
 *
 * <p><b>不与 {@code AttachmentService} 合并的另一个理由</b>：会话附件与子任务产物虽然都落
 * 同一个桶（{@code ArtifactStorage} 共用 bucket、仅 objectKey 前缀不同），但生命周期归属
 * 不同——会话删除走 {@code chat_attachment} 软删 + 对象回收，任务删除走 {@code attachment}
 * 软删。对账必须两边都读到，才不会把另一方的对象误判成孤儿。</p>
 */
public interface ChatAttachmentReferencePort {

    /**
     * 全量读取会话附件引用行，<b>包含逻辑删除（{@code deleted=1}）的记录</b>。
     *
     * <p>口径与 {@link ArtifactReferencePort#listAllIncludingDeleted()} 一致：宁可漏判孤儿，
     * 也不误删。只取未删除行会让会话删除后尚未回收的对象被误判成孤儿。</p>
     *
     * @return 全部会话附件引用（绝不返回 null）
     */
    List<ArtifactReference> listAllIncludingDeleted();
}
