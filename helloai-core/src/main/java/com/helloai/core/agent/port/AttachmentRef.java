package com.helloai.core.agent.port;

import com.helloai.common.constant.AttachmentStatus;

/**
 * 产物附件只读快照 —— agent 域自有的数据契约，**零 task 实体泄漏**。
 *
 * <p>消费方全部为 agent 域（{@code McpToolServiceImpl} 的详情 / 前置产出装载，
 * {@code AgentRuntimeContextAssembler} 的上游附件渲染），此前直接 import
 * {@code task.entity.Attachment}，构成 CODE_STYLE §6 反向依赖。</p>
 *
 * <p><b>{@code contentLoadable} 为什么是快照字段而非独立查询</b>：该布尔来自提供方的
 * {@code AttachmentService#isContentLoadable}（判定依据是 {@code storageUrl} 是否被
 * 存储后端支持，属**附件领域的判定**）。若做成 {@code isContentLoadable(attachmentId)}
 * 独立查询，提供方适配器需**再读一次实体**（+N 次 DB 往返，且随附件数线性增长）。
 * 由 task 侧适配器在产出快照时**顺带算好**，判定责任仍留在提供方、消费方零判定，
 * 且不暴露 {@code storageUrl} 这一实现细节。</p>
 *
 * <p><b>归属判据（§7.2）</b>：消费方 {@code agent} 低于提供方 {@code task}，契约落
 * {@code agent.port}；映射由提供方完成，{@code task → agent} 属顺向合法。</p>
 *
 * @param id              附件主键
 * @param fileName        文件名（可空）
 * @param fileType        文件类型（按扩展名推断，可空）
 * @param mimeType        MIME 类型（可空）
 * @param fileSize        字节数（可空）
 * @param status          附件状态枚举（{@code common} 域常量，无 task 耦合）
 * @param contentLoadable 平台是否可直接读取正文（由提供方判定后透传）
 * @param subTaskId       附件所属子任务 ID（`G-034` 追加：任务级列举后必须能回答「这份产出自哪个子任务」）
 */
public record AttachmentRef(Long id, String fileName, String fileType, String mimeType,
                            Long fileSize, AttachmentStatus status, boolean contentLoadable,
                            Long subTaskId) {

    /**
     * 兼容构造：`G-034` 之前的调用方（子任务级列举，归属已知、无需回指）。
     *
     * <p>保留它而不是改所有构造点：{@code subTaskId} 只对<b>任务级</b>列举有信息量，
     * 子任务级消费方拿到它是自己刚传进去的那个值，没有新增语义。测试与老调用点不必跟着改。</p>
     */
    public AttachmentRef(Long id, String fileName, String fileType, String mimeType,
                         Long fileSize, AttachmentStatus status, boolean contentLoadable) {
        this(id, fileName, fileType, mimeType, fileSize, status, contentLoadable, null);
    }
}
