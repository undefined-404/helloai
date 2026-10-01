package com.helloai.core.agent.port;

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
 * <p><b>为什么返回主键而非实体</b>：当前全部消费方（{@code ExecutionArtifactServiceImpl}、
 * {@code McpToolServiceImpl}）都只取新附件 ID。契约按实际需要收敛为 {@code Long}，
 * 避免把 task 实体（或字段冗余的快照）漏进 agent 侧。后续若出现需要更多附件字段的消费方，
 * 再按「字段按需增长」追加只读方法/B 侧快照。</p>
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
}
