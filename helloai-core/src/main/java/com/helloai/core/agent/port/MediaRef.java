package com.helloai.core.agent.port;

/**
 * 媒体引用快照 —— 「把哪张图送给模型」的<b>引用</b>形态，<b>不含字节</b>。
 *
 * <p><b>为什么不直接放 {@code List<Media>}（`VU-9` 判据⑥ / 不变量 I-3）</b>：
 * 媒体装载是「判能力 → 判类型 → 判体积 → 读字节」四步，其中<b>判能力需要在请求装配层</b>
 * 按「本次实际调用的模型」进行（不变量 I-2，成因形态见 OpenCode 官方 issue
 * {@code anomalyco/opencode#60736}：前端选中的模型 ≠ 实际调用的模型）。若调用方在构造
 * {@code AgentTask} 时就把字节读进来，则「模型不支持视觉」这条分支已经白读了最多 25MB
 * （{@code AttachmentMediaAssembler} 的 5 张 × 5MB 上限），且能力判定被迫提前到
 * 不知道实际模型的位置——两件事都指向「引用先走、字节后读」。</p>
 *
 * <p>另一层理由来自参考实现（Octop {@code middleware/workspace_image.py:1-10} 的 docstring：
 * {@code base64 and resized copies never land in LangGraph state or the dashboard history payload}）：
 * 字节一旦进了携带「任务意图」的载体，后续任何一次顺手缓存/落库都会把它带进历史表。
 * 本项目 {@code AgentTask} 当前<b>不落库、不进 MQ</b>（纯内存，已实测确认），但把
 * 「不落字节」固化成契约形状，将来它被持久化时不会静默放大。</p>
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 {@code planner} <b>高于</b>提供方
 * {@code agent}（依赖链 {@code planner > agent}）⇒ 契约落【提供方】{@code agent.port}；
 * {@code planner → agent} 属顺向合法。</p>
 *
 * @param attachmentId 附件主键（按 {@code source} 解释归属哪张表）
 * @param fileName     文件名（MIME 判不出时按扩展名兜底，可空）
 * @param mimeType     MIME 类型（可空；调用方填值时应尽可能带上，嗅探责任在读取侧）
 * @param fileSize     声明字节数（可空；装载器先按它跳过大文件，避免先读进内存再丢弃）
 * @param source       归属表：task 域 {@code attachment} 或 planner 域 {@code chat_attachment}
 */
public record MediaRef(Long attachmentId, String fileName, String mimeType,
                       Long fileSize, Source source) {

    /**
     * 附件归属表。
     *
     * <p><b>为什么必须显式区分而不是「一个 ID + 两个加载器都试」</b>：两张表的主键空间
     * 各自独立，同一个 ID 在两边可能都存在但指向不同文件——「试探式加载」会静默送错图。
     * 该约束正是 {@code ChatAttachmentReferencePort} 拆端口而非合并的同一原因
     * （两张表分属两域、生命周期不同）。</p>
     */
    public enum Source {
        /** task 域 {@code attachment}（子任务产物；提供方 {@code AttachmentPort}）。 */
        TASK_ATTACHMENT,
        /** planner 域 {@code chat_attachment}（会话挂载文件；提供方 {@code ChatAttachmentMediaPort}）。 */
        CHAT_ATTACHMENT
    }

    /** 子任务产物附件引用（`VU-2` 落点使用）。 */
    public static MediaRef ofTaskAttachment(Long attachmentId, String fileName, String mimeType, Long fileSize) {
        return new MediaRef(attachmentId, fileName, mimeType, fileSize, Source.TASK_ATTACHMENT);
    }

    /** 会话附件引用（`VU-9` 对话面使用）。 */
    public static MediaRef ofChatAttachment(Long attachmentId, String fileName, String mimeType, Long fileSize) {
        return new MediaRef(attachmentId, fileName, mimeType, fileSize, Source.CHAT_ATTACHMENT);
    }
}