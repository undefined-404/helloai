package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.output.ArtifactFile;
import com.helloai.core.agent.output.ExecutionOutputParser;
import com.helloai.core.agent.output.ParsedOutput;
import com.helloai.core.agent.service.ExecutionArtifactService;
import com.helloai.common.config.ArtifactStorageProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.system.storage.StoredArtifact;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行产出物化编排（方案2）：执行成功提交 REVIEW 后，把 lastExecution.output
 * 解析为文件、落盘到 {@link ArtifactStorage} 并注册 attachment 元数据。
 *
 * <p><b>Best-effort 语义</b>：本服务由 {@code ExecutionResultHandler} 在主事务
 * afterCommit 回调中触发（此时行锁已释放、REVIEW 推进已提交），物化失败仅记
 * 日志与告警级 log，绝不回滚/阻断执行结果主链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionArtifactServiceImpl implements ExecutionArtifactService {

    private final ArtifactStorageProperties properties;
    private final ExecutionOutputParser executionOutputParser;
    private final ArtifactStorage artifactStorage;
    private final AttachmentService attachmentService;
    private final TaskTimelineService taskTimelineService;
    private final AgentService agentService;

    /**
     * 物化执行产出为附件（best-effort，任何异常吞掉只记日志）。
     *
     * @param subTask 执行完成的子任务（用其 title 生成文件名、assignedAgentId 过归属校验）
     * @param agentId 上报结果的 Agent id（仅用于时间线记录）
     * @param output  lastExecution.output 原文
     */
    public void materialize(SubTask subTask, Long agentId, String output) {
        if (!properties.isEnabled() || subTask == null) {
            return;
        }
        try {
            materializeParsed(subTask, agentId, executionOutputParser.parse(subTask.getTitle(), output));
        } catch (Exception e) {
            log.warn("执行产出物化失败（不阻断主链路）: subTaskId={}, err={}",
                    subTask.getId(), e.getMessage());
        }
    }

    /**
     * 物化已解析结果（方案3：调用方已解析，物化侧不再重复解析）。
     *
     * @param parsed 调用方已解析的产出（含 files 与 displayText）
     */
    public void materialize(SubTask subTask, Long agentId, ParsedOutput parsed) {
        if (!properties.isEnabled() || subTask == null) {
            return;
        }
        try {
            materializeParsed(subTask, agentId, parsed);
        } catch (Exception e) {
            log.warn("执行产出物化失败（不阻断主链路）: subTaskId={}, err={}",
                    subTask.getId(), e.getMessage());
        }
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    private void materializeParsed(SubTask subTask, Long agentId, ParsedOutput parsed) {
        if (parsed == null || parsed.isEmpty()) {
            log.debug("执行产出为空，跳过物化: subTaskId={}", subTask.getId());
            return;
        }
        List<ArtifactFile> files = parsed.files();
        if (files.size() > properties.getMaxFiles()) {
            log.warn("产出文件数超限，截断物化: subTaskId={}, total={}, maxFiles={}",
                    subTask.getId(), files.size(), properties.getMaxFiles());
            files = files.subList(0, properties.getMaxFiles());
        }
        // register 归属校验要求 agentId == assignedAgentId，内置链路固定传 assignedAgentId
        Long ownerAgentId = subTask.getAssignedAgentId();
        // objectKey 首层目录使用执行 Agent 注册名（username 维度），便于按归属者检索
        String ownerName = resolveOwnerName(ownerAgentId);
        List<Long> attachmentIds = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        for (ArtifactFile file : files) {
            byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > properties.getMaxFileSize()) {
                log.warn("产出文件超过单文件大小上限，跳过: subTaskId={}, fileName={}, size={}, max={}",
                        subTask.getId(), file.fileName(), bytes.length, properties.getMaxFileSize());
                continue;
            }
            StoredArtifact stored = artifactStorage.store(
                    ownerName, subTask.getTaskId(), subTask.getId(), file.fileName(), bytes);
            Attachment attachment = attachmentService.register(
                    ownerAgentId, subTask.getId(),
                    file.fileName(), file.mimeType(), stored.fileSize(), stored.storageUrl());
            attachmentIds.add(attachment.getId());
            fileNames.add(file.fileName());
        }
        if (attachmentIds.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("attachmentIds", attachmentIds);
        payload.put("fileNames", fileNames);
        payload.put("count", attachmentIds.size());
        taskTimelineService.recordEvent(subTask.getTaskId(), subTask.getId(),
                "sub_task_artifact_materialized", AgentRole.EXECUTOR, agentId, payload);
        log.info("执行产出物化完成: subTaskId={}, attachmentIds={}", subTask.getId(), attachmentIds);
    }

    /** 解析执行 Agent 注册名作为附件归属目录；Agent 不存在时兜底 agent-{id}。 */
    private String resolveOwnerName(Long ownerAgentId) {
        if (ownerAgentId != null) {
            Agent agent = agentService.getById(ownerAgentId);
            if (agent != null && agent.getName() != null && !agent.getName().isBlank()) {
                return agent.getName();
            }
        }
        return "agent-" + (ownerAgentId != null ? ownerAgentId : "unknown");
    }
}
