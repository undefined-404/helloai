package com.helloai.core.task.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.system.storage.StoredArtifact;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.ArtifactUploadService;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 产物文件内容上传实现：校验 Agent 激活 + 子任务归属，再 store → register。
 *
 * <p>store 先于 register：register 需要 storageUrl（store 的产物）；
 * 若 register 失败事务回滚 DB，但对象存储中会残留孤儿对象
 * （与执行产出物化链路 ExecutionArtifactServiceImpl 现状一致，可接受）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactUploadServiceImpl implements ArtifactUploadService {

    private final AgentService agentService;
    private final SubTaskService subTaskService;
    private final AttachmentService attachmentService;
    private final ArtifactStorage artifactStorage;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArtifactUploadResult upload(Long agentId, Long subTaskId, String fileName, String mimeType, byte[] content) {
        Agent agent = assertAgentActive(agentId);

        if (fileName == null || fileName.isBlank()) {
            throw new BizException("fileName 不能为空");
        }
        if (content == null || content.length == 0) {
            throw new BizException("文件内容不能为空");
        }

        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (!agentId.equals(subTask.getAssignedAgentId())) {
            throw new BizException("无权为该子任务上传产物: subTaskId=" + subTaskId + ", agentId=" + agentId);
        }

        // objectKey 首层目录使用执行 Agent 注册名（username 维度），与物化链路口径一致
        String ownerName = agent.getName() != null && !agent.getName().isBlank()
                ? agent.getName() : "agent-" + agentId;
        String safeName = ArtifactStorage.sanitizeFileName(fileName);
        StoredArtifact stored = artifactStorage.store(
                ownerName, subTask.getTaskId(), subTaskId, safeName, content);

        // register 复用 AttachmentService 内置归属校验，防止口径漂移
        var attachment = attachmentService.register(
                agentId, subTaskId, safeName, mimeType, stored.fileSize(), stored.storageUrl());

        ArtifactUploadResult result = new ArtifactUploadResult();
        result.setAttachmentId(attachment.getId());
        result.setStorageUrl(stored.storageUrl());
        result.setFileSize(stored.fileSize());
        log.info("产物代理上传完成: agentId={}, subTaskId={}, fileName={}, storageUrl={}",
                agentId, subTaskId, safeName, stored.storageUrl());
        return result;
    }

    /** Agent 存在且 ACTIVE 校验（与 McpToolServiceImpl.assertAgentActive 同口径）。 */
    private Agent assertAgentActive(Long agentId) {
        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }
        if (agent.getStatus() != AgentStatus.ACTIVE) {
            throw new BizException("Agent 未激活: " + agentId + ", status=" + agent.getStatus());
        }
        return agent;
    }
}