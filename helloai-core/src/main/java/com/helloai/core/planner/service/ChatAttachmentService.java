package com.helloai.core.planner.service;

import com.helloai.core.planner.entity.ChatAttachment;

import java.util.List;

/**
 * 需求对话附件（会话级）契约：上传 / 清单 / 删除 / 预览读取。
 *
 * <p>与子任务级 {@code AttachmentService} 是两套语义：那边的锚点是「交付审计」，
 * 这边的锚点是「会话输入」；共享的是底层 {@code ArtifactStorage} 与解析端口，
 * 不共享实体与可见性策略（原因见方案 §3.1）。</p>
 *
 * <p>状态推进不在这里做：上传只落 {@code PENDING}，由
 * {@link com.helloai.core.planner.attachment.ChatAttachmentParseWorker} 推进到终态。</p>
 */
public interface ChatAttachmentService {

    /**
     * 待上传文件（Controller 从 multipart 转换而来）。
     *
     * @param fileName 原始文件名（含扩展名；Tika 判类型与编码都依赖它）
     * @param mimeType 浏览器声明的 MIME（可为 null，实际以魔数探测为准）
     * @param content  文件字节
     */
    record UploadFile(String fileName, String mimeType, byte[] content) {
    }

    /**
     * 批量上传（一次请求 1~{@code maxFilesPerUpload} 个）。
     *
     * <p><b>先全量校验再写入</b>：大小 / 类型 / 会话上限全部通过后才落库，避免「第 3 个文件超限
     * 导致前 2 个白传」。校验失败抛 {@code BizException}(400)，消息可直接展示给用户。</p>
     *
     * <p><b>去重</b>：已挂会话时按 {@code sha256} 命中已有附件则直接复用（不重复存储/解析）。</p>
     *
     * @param conversationId 已有会话 ID（新会话首句前为空）
     * @param clientToken    新会话临时归属（前端生成 UUID；两者至少一个非空）
     * @param files          待上传文件
     * @return 落库后的附件（含未去重的新记录），顺序与入参一致
     */
    List<ChatAttachment> uploadBatch(Long conversationId, String clientToken, List<UploadFile> files);

    /**
     * 批量上传会话附件，记录上传者登录用户（`ATT-3`，V123）。
     *
     * <p>上传端点有 {@code @SaCheckPermission("conversation:add")}，登录态必然存在。
     * 记录用途：ingest 到 kb 时落到 {@code kb_chunk.user_id}，让检索隔离能区分同一会话的多个用户。</p>
     *
     * @param uploaderUserId 上传者用户 id；无登录上下文（测试/历史调用）传 null
     */
    List<ChatAttachment> uploadBatch(Long conversationId, String clientToken,
                                     List<UploadFile> files, Long uploaderUserId);

    /**
     * 会话附件清单（按上传顺序）；{@code clientToken} 用于新会话首句前的临时批次。
     *
     * @param conversationId 会话 ID（可空）
     * @param clientToken    临时归属（可空）
     */
    List<ChatAttachment> list(Long conversationId, String clientToken);

    /** 按会话列附件（Prompt 注入用；不做鉴权，调用方已具备会话上下文）。 */
    List<ChatAttachment> listByConversation(Long conversationId);

    /**
     * 存储对账用：全量读取「存储地址 + 字节数」，<b>包含逻辑删除行</b>（保守口径）。
     *
     * <p>仅供 {@code system} 域对账端口适配器使用，不参与业务读写；返回对象除
     * {@code storageUrl} / {@code fileSize} 外其余字段为 null。</p>
     */
    List<ChatAttachment> listStorageRefsIncludingDeleted();

    /** 取附件，不存在抛 {@code BizException}(404)。 */
    ChatAttachment getByIdRequired(Long id);

    /** 软删 + 事务提交后 best-effort 回收对象；已删/不存在同样返回成功（幂等，重试安全）。 */
    void delete(Long id);

    /**
     * 登记一份<b>对话产物</b>（技能执行生成的文件，2026-10-27）。
     *
     * <p><b>为什么要单开一条通道</b>：{@link #uploadBatch} 是「用户上传的输入」——要过大小/类型/数量校验、
     * 要投递异步解析、要参与 Prompt 注入。产物是<b>输出</b>：内容由平台自己生成（可信），
     * 不需要解析进上下文（把 840KB HTML 注入 prompt 既无意义又会挤爆预算），
     * 只需要「能被下载」。</p>
     *
     * <p><b>为什么落在 chat_attachment 而不是子任务 attachment</b>：产物属于「这次对话」。
     * 子任务附件表的每条记录都锚在 {@code sub_task_id} 上，对话轮没有子任务，
     * 塞进去要么造出假子任务、要么让「交付审计」的判据多出第四种情形。</p>
     *
     * <p>解析状态直接置 {@code SKIPPED}：读得出正文但<b>刻意不读</b>（产物不进上下文），
     * 与「重试也无用」的图片/扫描件共用该状态，语义上都是「不要去注入它的正文」。</p>
     *
     * @param conversationId  所属会话（必填——产物必须有归属，否则无法鉴权下载）
     * @param uploaderUserId  生成者登录用户 id（可空，测试/无登录上下文时）
     * @param fileName        产物文件名（会做安全清洗）
     * @param mimeType        产物 MIME（可空 ⇒ 按扩展名兜底）
     * @param content         产物字节
     * @return 落库后的附件（含 id，供消息 payload 引用）
     */
    ChatAttachment saveArtifact(Long conversationId, Long uploaderUserId,
                               String fileName, String mimeType, byte[] content);

    /**
     * 预览载荷（内容 + 可用的 Content-Type + 原始文件名，供 Controller 拼响应头）。
     *
     * <p>Content-Type 由 <b>Tika 魔数探测</b>得出，不自己维护后缀→MIME 映射表：
     * 仓内已有一份（{@code AttachmentServiceImpl}），再抄一份必然漂移；
     * 而 Tika 本来就在依赖里，还比后缀表准（改名的文件也能认出来）。</p>
     */
    record PreviewPayload(byte[] content, String contentType, String fileName) {
    }

    /**
     * 读取浏览器内联预览所需内容；过大或类型不支持抛 {@code BizException}(413)。
     *
     * @param id 附件 ID
     */
    PreviewPayload preview(Long id);

    /**
     * 建会认领：把 {@code clientToken} 名下的临时附件挂到会话上（幂等）。
     *
     * @return 认领条数（0 表示该 token 下无临时附件或已认领）
     */
    int claimByClientToken(String clientToken, Long conversationId);
}
