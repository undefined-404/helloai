package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AttachmentStatus;
import com.helloai.core.entity.Attachment;
import com.helloai.core.entity.SubTask;
import com.helloai.core.mapper.AttachmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 附件服务 — 管理 SubTask 的产物附件元数据。
 * 实际文件存储由 MinIO/对象存储负责，本服务只管理 DB 元数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService extends ServiceImpl<AttachmentMapper, Attachment> {

    private final SubTaskService subTaskService;

    /**
     * 注册产物附件元数据。
     * 仅允许对归属于 agentId 的 SubTask 上传附件。
     */
    @Transactional(rollbackFor = Exception.class)
    public Attachment register(Long agentId, Long subTaskId,
                               String fileName, String mimeType, Long fileSize,
                               String storageUrl) {
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (!agentId.equals(subTask.getAssignedAgent())) {
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
