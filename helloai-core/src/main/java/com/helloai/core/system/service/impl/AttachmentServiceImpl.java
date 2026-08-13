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
