package com.helloai.core.task.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.base.BizException;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.port.AttachmentView;

import java.util.List;

/**
 * 附件服务 — 管理 SubTask 的产物附件元数据。
 * 平台可直读 local:// 与 minio:// 两类产物（附件物化存储与对象存储
 * 均经 {@link #loadContent(Long)} 直接读取内容供流式下载与证据核验）。
 */
public interface AttachmentService extends IService<Attachment> {

    /**
     * 注册产物附件元数据。
     * 仅允许对归属于 agentId 的 SubTask 上传附件。
     */
    Attachment register(Long agentId, Long subTaskId,
                        String fileName, String mimeType, Long fileSize,
                        String storageUrl);

    /**
     * 按子任务 ID 查询附件列表（按创建时间倒序）。
     *
     * <p>{@code subTaskId} 为空时返回所有附件；逻辑删除由 {@code @TableLogic}
     * 自动过滤。含全部状态（ACTIVE/INACTIVE/DELETED），供附件管理页
     * 历史版本回查使用。</p>
     *
     * @param subTaskId 可选子任务 ID 过滤；null 表示不限
     * @return 附件列表（绝不返回 null）
     */
    List<Attachment> list(Long subTaskId);

    /**
     * 按子任务 ID 查询<b>指定 agent 可读</b>的附件列表（按创建时间倒序）。
     *
     * <p>与 {@link #list(Long)} 同源，但逐条经
     * {@code AttachmentVisibilityPolicy} 过滤：同一子任务下的附件可能具有不同
     * {@code visibility}，故"A 可见、B 不可见"必须在<b>行粒度</b>上剔除，
     * 而非整表放行或整表 403。</p>
     *
     * <p><b>为什么读端可见性判定放在 Service 而不是 Controller</b>（§8.1）：
     * Controller 只应依赖 Service 契约，可见性判据属业务规则（§8.2），
     * 且将来 MCP 读取工具等通道需要同一判据。故收敛在此，
     * Controller 不再直接 import {@code task.policy}。</p>
     *
     * @param subTaskId 子任务 ID（null 表示不限）
     * @param agentId   请求 Agent ID（{@code null} 时按不可读处理，返回空列表）
     * @return 该 agent 可读的附件列表（绝不返回 null）
     */
    List<Attachment> listReadable(Long subTaskId, Long agentId);

    /**
     * 校验指定 agent 可读该附件；不可读抛 {@link BizException}(403)。
     *
     * <p>判据唯一来源为 {@code AttachmentVisibilityPolicy}（"声明范围 × 任务成员关系"），
     * 本方法只做"判定 → 转异常"的收口，不复制任何判定逻辑。传入已加载的实体而非
     * {@code id}，避免调用方为做校验再查一次库。</p>
     *
     * <p><b>调用方约定</b>：仅 Agent 通道需要调用；平台账号 / 无主体请求由管理侧鉴权覆盖
     * （通道判定属 HTTP 关注点，留在 Controller）。</p>
     *
     * @param attachment 已加载的附件实体
     * @param agentId    请求 Agent ID
     * @throws BizException 不可读时抛 403
     */
    void assertReadable(Attachment attachment, Long agentId);

    /**
     * 校验指定 agent 可<b>删除</b>该附件；不可删抛 {@link BizException}(403)。
     *
     * <p>判据唯一来源为 {@code AttachmentVisibilityPolicy#canDelete}（仅上传者），
     * 本方法只做「判定 → 转异常」的收口。删除判据<b>比读判据更严</b>：
     * 读侧允许 TASK 团队成员互通，删除<b>只认上传者</b>，避免同任务他人互删产出。</p>
     *
     * <p><b>调用方约定</b>：仅 Agent 通道需要调用；平台账号 / 无主体请求由管理侧鉴权覆盖
     * （通道判定属 HTTP 关注点，留在 Controller）。</p>
     *
     * @param attachment 已加载的附件实体
     * @param agentId    请求 Agent ID
     * @throws BizException 不可删时抛 403
     */
    void assertDeletable(Attachment attachment, Long agentId);

    /**
     * 删除附件（P2 删除通道，2026-10-07）：<b>软删</b> DB 行（{@code @TableLogic}）+ 事务提交后
     * best-effort 回收对象存储 + 写入 timeline 事件 {@code attachment_deleted}。
     *
     * <p><b>幂等</b>：附件不存在 / 已逻辑删除时直接返回（不抛 404/500），使外部 Agent 重试安全。</p>
     *
     * <p><b>fail-safe</b>：对象回收失败只记日志，不回滚、不影响删除主结果（留对账兜底）。</p>
     *
     * @param id               附件主键
     * @param requesterAgentId 请求 Agent ID；<b>非 null</b> 时强制「仅上传者可删」（不可删抛 403）；
     *                         {@code null} 表示平台 / 无主体通道，放行（管理侧鉴权覆盖）
     */
    void deleteAttachment(Long id, Long requesterAgentId);

    /**
     * 按子任务 ID 查询有效（ACTIVE）附件列表（按创建时间倒序）。
     *
     * <p>平台可信视角：同名文件每次上传会把历史版本置为 INACTIVE，
     * 因此本方法返回的每个文件名至多一条记录（最新一次上传）。
     * 供自动核验证据检查、上游依赖产出装载、交付物打包等
     * "只认当前有效版本"的场景使用，避免同名多版本污染判定。</p>
     *
     * @param subTaskId 子任务 ID（null 表示不限）
     * @return 有效附件列表（绝不返回 null）
     */
    List<Attachment> listActive(Long subTaskId);

    /**
     * 驳回打回时将该子任务全部有效（ACTIVE）附件批量置为 INACTIVE。
     *
     * <p>打回失效语义：自动核验驳回 / 人工驳回打回子任务后，
     * 旧提交的证据不应再作为有效版本参与下次核验、依赖装载或交付物打包；
     * 外部执行 Agent 必须基于驳回意见重新产出并重新上传最新版附件
     * （同名上传自然成为唯一 ACTIVE，不同名则新文件生效、旧文件保持失效）。
     * 历史版本保留在附件表中（状态回查 + 内容直读不受影响）。</p>
     *
     * @param subTaskId 子任务 ID
     */
    void invalidateBySubTask(Long subTaskId);

    /**
     * 全量读取附件行，<b>包含逻辑删除（{@code deleted=1}）的记录</b>，供存储对账巡检
     * 构建"被引用对象"集合。
     *
     * <p>口径必须保守：只要还有任意一行（任意状态、含已逻辑删除）指向某个对象，
     * 该对象就不算孤儿，不得被清理。若只取 {@code deleted=0} 的行，
     * 任务级联删除（{@link com.helloai.core.task.mapper.AttachmentMapper#physicalDeleteByTaskId}
     * 之外的逻辑删除路径）留下的对象会被误判成孤儿而删除。</p>
     *
     * <p>仅供对账巡检使用，业务查询请走 {@link #list}/{@link #listActive}。</p>
     *
     * @return 全部附件行（绝不返回 null）
     */
    List<Attachment> listAllIncludingDeleted();

    /**
     * 按 ID 查询附件；不存在时抛 {@link BizException}(404, "附件不存在")，
     * 供 Controller 统一透传给全局异常处理。
     *
     * @param id 附件主键
     * @return 附件实体
     * @throws BizException 当附件不存在
     */
    Attachment getByIdRequired(Long id);

    /**
     * 获取附件存储地址（用于下载重定向）。
     *
     * @param id 附件主键
     * @return 存储 URL；为空或空白时抛 {@link BizException}(500, "附件存储地址不可用")
     * @throws BizException 当附件不存在或存储地址不可用
     */
    String getStorageUrlRequired(Long id);

    /**
     * 判断附件是否可由平台直接读取内容（local:// 或 minio:// 产物）；
     * 不可读时下载链路回退 302 重定向到外部存储地址。
     */
    boolean isContentLoadable(Attachment attachment);

    /**
     * 读取附件内容字节（仅限 {@link #isContentLoadable} 的平台可读产物）。
     *
     * @param id 附件主键
     * @return 文件内容
     * @throws BizException 附件不存在 / 地址不可读 / 文件缺失
     */
    byte[] loadContent(Long id);

    /**
     * 推断浏览器预览所需的 MIME 类型（按 fileName 后缀）。
     * 推断顺序：fileName 后缀 → attachment.mimeType → application/octet-stream。
     * 用于 {@code /previewById/{id}} 端点构造 {@code Content-Type} 响应头。
     *
     * @param attachment 附件实体
     * @return MIME 字符串（含 charset 时一并返回），永不为 null
     */
    String resolveContentType(Attachment attachment);

    /**
     * 判断附件是否可在浏览器内联预览。
     * 判定规则：必须能被平台直读（{@link #isContentLoadable}），
     * 且 MIME 命中预览白名单（text/* / image/* / application/pdf / json / xml），
     * 且文件大小不超过实现类内部的预览大小阈值（5 MiB）。
     *
     * @param attachment 附件实体
     * @return 是否适合浏览器内联预览
     */
    boolean isPreviewable(Attachment attachment);

    // ── 只读快照（RM5 批 4）：供 review 域消费，避免其 import task.entity ──

    /** 启用态附件的只读快照（顺序与 {@link #listActive} 一致；{@code contentLoadable} 由提供方派生，W11）。 */
    List<AttachmentView> listActiveViews(Long subTaskId);
}
