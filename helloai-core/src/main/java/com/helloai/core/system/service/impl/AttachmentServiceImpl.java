package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AttachmentStatus;
import com.helloai.core.system.entity.Attachment;
import com.helloai.core.system.mapper.AttachmentMapper;
import com.helloai.core.system.service.AttachmentService;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 附件服务实现 — 管理 SubTask 的产物附件元数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentServiceImpl extends ServiceImpl<AttachmentMapper, Attachment> implements AttachmentService {

    private final SubTaskService subTaskService;
    private final TaskService taskService;
    private final ArtifactStorage artifactStorage;

    /**
     * 注册产物附件元数据。
     * 仅允许对归属于 agentId 的 SubTask 上传附件。
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public Attachment register(Long agentId, Long subTaskId,
                               String fileName, String mimeType, Long fileSize,
                               String storageUrl) {
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (!agentId.equals(subTask.getAssignedAgentId())) {
            throw new BizException("无权为该子任务上传附件: subTaskId=" + subTaskId + ", agentId=" + agentId);
        }

        Attachment attachment = new Attachment();
        attachment.setSubTaskId(subTaskId);
        attachment.setFileName(fileName);
        attachment.setFileType(detectFileType(fileName));
        attachment.setMimeType(mimeType != null ? mimeType : "application/octet-stream");
        attachment.setFileSize(fileSize != null ? fileSize : 0L);
        attachment.setBucketName(detectBucketName(storageUrl));
        attachment.setObjectKey(detectObjectKey(storageUrl, subTaskId, fileName));
        attachment.setStorageUrl(storageUrl);
        attachment.setStatus(AttachmentStatus.ACTIVE);
        save(attachment);

        log.info("附件注册: id={}, subTaskId={}, fileName={}", attachment.getId(), subTaskId, fileName);
        return attachment;
    }

    /**
     * 按子任务 ID 查询附件列表（按创建时间倒序）。
     *
     * <p>{@code subTaskId} 为空时返回所有附件；逻辑删除由 {@code @TableLogic}
     * 自动过滤。</p>
     *
     * @param subTaskId 可选子任务 ID 过滤；null 表示不限
     * @return 附件列表（绝不返回 null）
     */
    @Override
    public List<Attachment> list(Long subTaskId) {
        List<Attachment> result = lambdaQuery()
                .eq(subTaskId != null, Attachment::getSubTaskId, subTaskId)
                .orderByDesc(Attachment::getCreateTime)
                .list();
        if (result == null || result.isEmpty()) {
            return result != null ? result : Collections.emptyList();
        }
        // 回填主任务/子任务标题（transient 字段，不落库），供附件管理按层级浏览时展示名称
        Set<Long> subTaskIds = result.stream().map(Attachment::getSubTaskId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (!subTaskIds.isEmpty()) {
            Map<Long, SubTask> subTaskMap = subTaskService.listByIds(subTaskIds).stream()
                    .collect(Collectors.toMap(SubTask::getId, s -> s, (a, b) -> a));
            Set<Long> taskIds = subTaskMap.values().stream().map(SubTask::getTaskId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            Map<Long, Task> taskMap = taskIds.isEmpty() ? Collections.emptyMap()
                    : taskService.listByIds(taskIds).stream()
                            .collect(Collectors.toMap(Task::getId, t -> t, (a, b) -> a));
            for (Attachment att : result) {
                SubTask subTask = subTaskMap.get(att.getSubTaskId());
                if (subTask == null) {
                    continue;
                }
                att.setTaskId(subTask.getTaskId());
                att.setSubTaskTitle(subTask.getTitle());
                Task task = taskMap.get(subTask.getTaskId());
                if (task != null) {
                    att.setTaskTitle(task.getTitle());
                }
            }
        }
        return result;
    }

    /**
     * 按 ID 查询附件；不存在时抛 {@link BizException}(404, "附件不存在")，
     * 供 Controller 统一透传给全局异常处理。
     *
     * @param id 附件主键
     * @return 附件实体
     * @throws BizException 当附件不存在
     */
    @Override
    public Attachment getByIdRequired(Long id) {
        Attachment attachment = getById(id);
        if (attachment == null) {
            throw new BizException(404, "附件不存在");
        }
        return attachment;
    }

    /**
     * 获取附件存储地址（用于下载重定向）。
     *
     * @param id 附件主键
     * @return 存储 URL；为空或空白时抛 {@link BizException}(500, "附件存储地址不可用")
     * @throws BizException 当附件不存在或存储地址不可用
     */
    @Override
    public String getStorageUrlRequired(Long id) {
        Attachment attachment = getByIdRequired(id);
        String downloadUrl = attachment.getStorageUrl();
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new BizException(500, "附件存储地址不可用");
        }
        return downloadUrl;
    }

    /**
     * 判断附件是否可由平台直接读取内容（local:// 或 minio:// 产物）；
     * 不可读时下载链路回退 302 重定向到外部存储地址。
     */
    @Override
    public boolean isContentLoadable(Attachment attachment) {
        return attachment != null && artifactStorage.supports(attachment.getStorageUrl());
    }

    /**
     * 读取附件内容字节（仅限 {@link #isContentLoadable} 的平台可读产物）。
     *
     * @param id 附件主键
     * @return 文件内容
     * @throws BizException 附件不存在 / 地址不可读 / 文件缺失
     */
    @Override
    public byte[] loadContent(Long id) {
        Attachment attachment = getByIdRequired(id);
        if (!artifactStorage.supports(attachment.getStorageUrl())) {
            throw new BizException("附件不支持平台直读: id=" + id);
        }
        return artifactStorage.load(attachment.getStorageUrl());
    }

    /**
     * 浏览器内联预览文件大小上限（5 MiB）。
     * 经验值：5MB 以下浏览器 iframe / pre 渲染尚可，超过会出现明显卡顿，
     * 应引导走下载。
     */
    private static final long PREVIEW_MAX_SIZE_BYTES = 5L * 1024 * 1024;

    /**
     * 浏览器可内联预览的 MIME 前缀 / 精确值白名单。
     * 命中其一即可走 inline 渲染；其余类型统一走下载。
     */
    private static final Set<String> PREVIEWABLE_MIME_PREFIXES = Set.of(
            "text/",
            "image/",
            "application/json",
            "application/xml",
            "application/yaml"
    );
    private static final Set<String> PREVIEWABLE_MIME_EXACT = Set.of(
            "application/pdf"
    );

    /**
     * 推断预览所需 MIME（按 fileName 后缀 → attachment.mimeType → octet-stream）。
     * 文本类追加 charset=UTF-8，避免浏览器按 GBK 误读中文日志。
     */
    @Override
    public String resolveContentType(Attachment attachment) {
        if (attachment == null) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        String byName = detectContentTypeByName(attachment.getFileName());
        if (byName != null) {
            return byName;
        }
        String stored = attachment.getMimeType();
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    /**
     * 判定附件是否适合浏览器内联预览。
     * 必经三关：平台可读 + MIME 命中白名单 + 文件大小未超阈值。
     */
    @Override
    public boolean isPreviewable(Attachment attachment) {
        if (attachment == null) {
            return false;
        }
        if (!isContentLoadable(attachment)) {
            return false;
        }
        Long size = attachment.getFileSize();
        if (size != null && size > PREVIEW_MAX_SIZE_BYTES) {
            return false;
        }
        String mime = resolveContentType(attachment);
        if (mime == null) {
            return false;
        }
        for (String prefix : PREVIEWABLE_MIME_PREFIXES) {
            if (mime.startsWith(prefix)) {
                return true;
            }
        }
        return PREVIEWABLE_MIME_EXACT.contains(mime);
    }

    /**
     * 按 fileName 后缀推断 MIME（与 {@link com.helloai.core.system.storage.MinioArtifactStorage#detectContentType}
     * 同步演进，但由 Service 私有持有，避免 Storage 接口被 Controller 侧语义污染）。
     * 文本类统一追加 charset=UTF-8；识别失败返回 null。
     */
    private String detectContentTypeByName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md")) {
            return "text/markdown;charset=UTF-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json;charset=UTF-8";
        }
        if (lower.endsWith(".xml")) {
            return "application/xml;charset=UTF-8";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".log")) {
            return MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8";
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return "application/yaml;charset=UTF-8";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv;charset=UTF-8";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html;charset=UTF-8";
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF_VALUE;
        }
        if (lower.endsWith(".svg")) {
            // Spring 6.x MediaType 没有 IMAGE_SVG_VALUE 常量，这里直接用字面量
            return "image/svg+xml";
        }
        if (lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF_VALUE;
        }
        // JS 家族源码（.js / .mjs / .cjs / .jsx）：
        // 统一按 text/javascript 返回，命中 PREVIEWABLE_MIME_PREFIXES 中的 text/ 前缀。
        if (lower.endsWith(".js") || lower.endsWith(".mjs")
                || lower.endsWith(".cjs") || lower.endsWith(".jsx")) {
            return "text/javascript;charset=UTF-8";
        }
        // TS 家族源码（.ts / .tsx）：text/typescript 是事实标准，浏览器/编辑器通用识别。
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return "text/typescript;charset=UTF-8";
        }
        return null;
    }

    private String detectFileType(String fileName) {
        if (fileName == null) return "other";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".log") || lower.endsWith(".txt")) return "log";
        if (lower.endsWith(".md")) return "markdown";
        if (lower.endsWith(".zip") || lower.endsWith(".tar.gz")) return "archive";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image";
        return "other";
    }

    /**
     * 从 storageUrl 推导 bucketName。
     * 支持 minio://bucket/objectKey、s3://bucket/objectKey；
     * 解析失败时返回默认 bucket "helloai"。
     */
    private String detectBucketName(String storageUrl) {
        if (storageUrl == null || storageUrl.isBlank()) {
            return "helloai";
        }
        String prefix = null;
        if (storageUrl.startsWith("minio://")) {
            prefix = "minio://";
        } else if (storageUrl.startsWith("s3://")) {
            prefix = "s3://";
        } else if (storageUrl.startsWith("oss://")) {
            prefix = "oss://";
        } else if (storageUrl.startsWith("local://")) {
            prefix = "local://";
        }
        if (prefix == null) {
            return "helloai";
        }
        String rest = storageUrl.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return "helloai";
        }
        return rest.substring(0, slash);
    }

    /**
     * 从 storageUrl 推导 objectKey。
     * 解析失败时使用 subTaskId/fileName 构造默认 key。
     */
    private String detectObjectKey(String storageUrl, Long subTaskId, String fileName) {
        if (storageUrl != null && !storageUrl.isBlank()) {
            String prefix = null;
            if (storageUrl.startsWith("minio://")) {
                prefix = "minio://";
            } else if (storageUrl.startsWith("s3://")) {
                prefix = "s3://";
            } else if (storageUrl.startsWith("oss://")) {
                prefix = "oss://";
            } else if (storageUrl.startsWith("local://")) {
                prefix = "local://";
            }
            if (prefix != null) {
                String rest = storageUrl.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash >= 0 && slash < rest.length() - 1) {
                    return rest.substring(slash + 1);
                }
            }
        }
        return (subTaskId != null ? subTaskId : "0") + "/" + (fileName != null ? fileName : "unknown");
    }
}
