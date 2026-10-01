package com.helloai.core.agent.port;

import java.util.List;

/**
 * 产物附件端口 —— 消费方 {@code agent} 定义、提供方 {@code task} 实现（CODE_STYLE §7.2 端口反转）。
 *
 * <p><b>为什么是不透明命令端口（§16.8）</b>：{@code AttachmentService#register} 并非单纯落库，
 * 而是「子任务存在性校验 → 归属校验（{@code agentId == assignedAgentId}）→ 地址合法性/对象存在性
 * 校验 → 同子任务同名 ACTIVE 旧版批量去活 → 插入」的**整体事务**。这些判定属于**持有附件状态的
 * {@code task} 域**，消费方只表达「登记这个产物」的意图、不参与任何判定，故整个动作以
 * **单方法不透明暴露**，不得对半拆开（拆开会把事务边界与判定一起撕开）。</p>
 *
 * <p><b>归属判据</b>：消费方 {@code agent} <b>低于</b>提供方 {@code task}，端口落消费方
 * {@code agent.port}；提供方适配器的 {@code task → agent} 依赖属 <b>顺向合法</b>。</p>
 *
 * <p><b>为什么 {@code register} 返回主键而非实体</b>：全部消费方（{@code ExecutionArtifactServiceImpl}、
 * {@code McpToolServiceImpl}）都只取新附件 ID。契约按实际需要收敛为 {@code Long}，
 * 避免把 task 实体漏进 agent 侧；需要更多字段时按下述「字段按需增长」补只读方法。</p>
 *
 * <p><b>字段按需增长（W7 兑现）</b>：W6 建端口时只声明了 {@code register}（返回主键）；
 * W7 出现「详情下发 / 上游产出装载」消费方，确实需要附件的名称、类型、大小、状态与可读性，
 * 于是按需补 {@link #listActive(Long)} 返回 {@link AttachmentRef} 快照 +
 * {@link #loadContent(Long)} 读字节。<b>读与写同属一个附件端口、不复用两个文件</b>——
 * 端口「扩方法」不新增适配器文件，`task->agent` 前向计数不额外上涨（W5 已固化的经验）。</p>
 */
public interface AttachmentPort {

    /**
     * 注册产物附件元数据（归属校验、地址与存在性校验、同名去活、落库整体由提供方完成）。
     *
     * @param agentId    上报/上传的 Agent ID（提供方用它做归属校验）
     * @param subTaskId  目标子任务 ID
     * @param fileName   文件名
     * @param mimeType   MIME 类型（提供方在为空时回退 {@code application/octet-stream}）
     * @param fileSize   文件字节数（提供方在为空时回退 {@code 0}）
     * @param storageUrl 产物存储地址
     * @return 新登记的附件主键
     */
    Long register(Long agentId, Long subTaskId, String fileName, String mimeType,
                  Long fileSize, String storageUrl);

    /**
     * 按子任务读取<b>有效（ACTIVE）</b>附件快照列表（W7 新增）。
     *
     * <p>语义与 {@code AttachmentService#listActive(Long)} 一致：只返回当前有效版本
     * （同名文件至多一条 —— 再次上传会把历史版本置为 INACTIVE），按创建时间<b>倒序</b>；
     * 入参为 {@code null} 表示不限子任务（调用方不做此用法，保留原语义）；绝不返回 {@code null}。</p>
     *
     * <p>返回 {@link AttachmentRef}，其中的 {@code contentLoadable} 由提供方在映射时
     * 顺带算好（判定责任留在提供方、消费方零判定，且不暴露 {@code storageUrl}）。</p>
     *
     * @param subTaskId 子任务 ID（{@code null} 表示不限）
     * @return 有效附件快照列表（按创建时间倒序，绝不返回 {@code null}）
     */
    List<AttachmentRef> listActive(Long subTaskId);

    /**
     * 读取附件内容字节（仅供 {@code contentLoadable=true} 的平台可读产物，W7 新增）。
     *
     * <p>语义与 {@code AttachmentService#loadContent(Long)} 一致：附件不存在 / 地址不可读 /
     * 文件缺失时抛 {@code BizException}。消费方（上游附件渲染）逐条 try/catch，
     * 单条失败只跳过该条、不阻断整体。</p>
     *
     * @param attachmentId 附件主键
     * @return 文件内容字节
     */
    byte[] loadContent(Long attachmentId);
}
