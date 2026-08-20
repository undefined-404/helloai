package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.Attachment;

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
}
