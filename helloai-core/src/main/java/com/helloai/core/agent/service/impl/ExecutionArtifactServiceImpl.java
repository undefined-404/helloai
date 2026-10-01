package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.output.ArtifactFile;
import com.helloai.core.agent.output.ExecutionOutputParser;
import com.helloai.core.agent.output.ParsedOutput;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.agent.service.ExecutionArtifactService;
import com.helloai.common.config.ArtifactStorageProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.system.storage.StoredArtifact;
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
 *
 * <p><b>跨域访问（2026-10-01 W6）</b>：本类不再持有任何 {@code task} 域类型——
 * 子任务数据经 {@link SubTaskQueryPort} 读快照（含 {@code title}），附件登记经
 * {@link AttachmentPort} 不透明命令端口完成（归属/地址/存在性校验与同名去活整体留在
 * task 侧 {@code AttachmentService#register}）。事务边界与「先校验再落库」的判定
 * 全部由提供方保留，消费方不自建任何判定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionArtifactServiceImpl implements ExecutionArtifactService {

    private final ArtifactStorageProperties properties;
    private final ExecutionOutputParser executionOutputParser;
    private final ArtifactStorage artifactStorage;
    private final SubTaskQueryPort subTaskQueryPort;
    private final AttachmentPort attachmentPort;
    private final TaskTimelinePort taskTimelinePort;
    private final AgentService agentService;

    /**
     * 物化执行产出为附件（best-effort，任何异常吞掉只记日志）。
     *
     * @param subTaskId 执行完成的子任务 ID（子任务不存在时静默跳过）
     * @param agentId   上报结果的 Agent id（仅用于时间线记录）
     * @param output    lastExecution.output 原文
     */
    @Override
    public void materialize(Long subTaskId, Long agentId, String output) {
        if (!properties.isEnabled() || subTaskId == null) {
            return;
        }
        try {
            SubTaskSnapshot subTask = subTaskQueryPort.findById(subTaskId);
            if (subTask == null) {
                return;
            }
            materializeParsed(subTask, agentId, executionOutputParser.parse(subTask.title(), output));
        } catch (Exception e) {
            log.warn("执行产出物化失败（不阻断主链路）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }
    }

    /**
     * 物化已解析结果（方案3：调用方已解析，物化侧不再重复解析）。
     *
     * @param subTaskId 执行完成的子任务 ID（子任务不存在时静默跳过）
     * @param agentId   上报结果的 Agent id（仅用于时间线记录）
     * @param parsed    调用方已解析的产出（含 files 与 displayText）
     */
    @Override
    public void materialize(Long subTaskId, Long agentId, ParsedOutput parsed) {
        if (!properties.isEnabled() || subTaskId == null) {
            return;
        }
        try {
            SubTaskSnapshot subTask = subTaskQueryPort.findById(subTaskId);
            if (subTask == null) {
                return;
            }
            materializeParsed(subTask, agentId, parsed);
        } catch (Exception e) {
            log.warn("执行产出物化失败（不阻断主链路）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    private void materializeParsed(SubTaskSnapshot subTask, Long agentId, ParsedOutput parsed) {
        if (parsed == null || parsed.isEmpty()) {
            log.debug("执行产出为空，跳过物化: subTaskId={}", subTask.id());
            return;
        }
        List<ArtifactFile> files = parsed.files();
        if (files.size() > properties.getMaxFiles()) {
            log.warn("产出文件数超限，截断物化: subTaskId={}, total={}, maxFiles={}",
                    subTask.id(), files.size(), properties.getMaxFiles());
            files = files.subList(0, properties.getMaxFiles());
        }
        // register 归属校验要求 agentId == assignedAgentId，内置链路固定传 assignedAgentId
        Long ownerAgentId = subTask.assignedAgentId();
        // objectKey 首层目录使用执行 Agent 注册名（username 维度），便于按归属者检索
        String ownerName = resolveOwnerName(ownerAgentId);
        List<Long> attachmentIds = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        for (ArtifactFile file : files) {
            byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > properties.getMaxFileSize()) {
                log.warn("产出文件超过单文件大小上限，跳过: subTaskId={}, fileName={}, size={}, max={}",
                        subTask.id(), file.fileName(), bytes.length, properties.getMaxFileSize());
                continue;
            }
            StoredArtifact stored = artifactStorage.store(
                    ownerName, subTask.taskId(), subTask.id(), file.fileName(), bytes);
            Long attachmentId = attachmentPort.register(
                    ownerAgentId, subTask.id(),
                    file.fileName(), file.mimeType(), stored.fileSize(), stored.storageUrl());
            attachmentIds.add(attachmentId);
            fileNames.add(file.fileName());
        }
        if (attachmentIds.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("attachmentIds", attachmentIds);
        payload.put("fileNames", fileNames);
        payload.put("count", attachmentIds.size());
        taskTimelinePort.recordEvent(subTask.taskId(), subTask.id(),
                "sub_task_artifact_materialized", AgentRole.EXECUTOR, agentId, payload);
        log.info("执行产出物化完成: subTaskId={}, attachmentIds={}", subTask.id(), attachmentIds);
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
