package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.Attachment;

import java.util.List;

/**
 * 附件服务 — 管理 SubTask 的产物附件元数据。
 * v2.7 起平台可直读 local:// 与 minio:// 两类产物（附件物化存储与对象存储
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
     * 自动过滤。</p>
     *
     * @param subTaskId 可选子任务 ID 过滤；null 表示不限
     * @return 附件列表（绝不返回 null）
     */
    List<Attachment> list(Long subTaskId);

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
}
