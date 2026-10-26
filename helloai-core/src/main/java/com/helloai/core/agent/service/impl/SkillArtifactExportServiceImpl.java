package com.helloai.core.agent.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ArtifactStorageProperties;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.runtime.sandbox.SandboxArtifact;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.SkillArtifactExportService;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.system.storage.StoredArtifact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link SkillArtifactExportService} 实现（`SKX-11`，2026-10-26）。见接口注释的链路说明。
 *
 * <p><b>三条判定都是 fail-close</b>（宁可报错也不产出半成品）：</p>
 * <ol>
 *   <li><b>截断即拒绝</b>：{@code readArtifact} 在产物超过上限时返回 {@code truncated=true}，
 *       此时字节只是前 N 字节。把截断内容登记成附件，用户会下载到一个「看起来完整但被截断的 HTML」——
 *       浏览器打开不报错、页面缺一块，比明确失败难查得多。</li>
 *   <li><b>沙箱已释放即拒绝</b>：产物只在子任务的复用槽里活着。槽已被 turn 末回收时
 *       {@code readArtifact} 返回 {@code null}，此时唯一正确结论是「拿不到了」，
 *       不能 fallback 去新建沙箱（新容器里没有那个文件）。</li>
 *   <li><b>归属取子任务而非调用方</b>：{@code AttachmentService#register} 要求
 *       {@code agentId == assignedAgentId}。用子任务快照里的 {@code assignedAgentId}
 *       既能通过校验，也避免「谁调用就归谁」造成的归属漂移
 *       （与 {@code ExecutionArtifactServiceImpl} 同口径）。</li>
 * </ol>
 *
 * <p><b>为什么文件名要清洗后再登记</b>：{@code ExecutionArtifactServiceImpl} 传的是解析器
 * 给出的受控文件名；本服务的文件名可能来自<b>模型入参</b>（不可信）。{@code ArtifactStorage#store}
 * 内部会清洗对象键，但附件表记的是我们传进去的原值——不清洗会出现「库里叫 {@code a/b.html}、
 * 桶里叫 {@code a_b.html}」的分叉。故先在入口清洗成一份，两处共用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillArtifactExportServiceImpl implements SkillArtifactExportService {

    private final ArtifactStorageProperties properties;
    private final ArtifactStorage artifactStorage;
    private final SubTaskQueryPort subTaskQueryPort;
    private final AttachmentPort attachmentPort;
    private final AgentService agentService;
    private final SandboxExecutionService sandboxExecutionService;

    @Override
    public ExportResult export(Long agentId, Long subTaskId, String path, String fileName) {
        if (subTaskId == null) {
            throw new BizException("缺少子任务上下文，无法导出产物");
        }
        if (path == null || path.isBlank()) {
            throw new BizException("产物路径不能为空（须为容器内绝对路径，如 /workspace/out/archify.html）");
        }
        String normalizedPath = path.trim();
        // 先自己判一次「绝对路径 + 无 ..」，而不是把相对路径丢给 readArtifact：
        // readArtifact 拒绝后只返回 null，调用方无从区分「路径写法错」与「文件真不存在」，
        // 于是模型会去检查文件、却永远不会想到自己少写了开头的 /。错误信息的精确度直接决定返工轮数。
        if (!normalizedPath.startsWith("/") || normalizedPath.contains("..")) {
            throw new BizException("产物路径必须是容器内绝对路径且不含 '..': " + path
                    + "（不按 /workspace 自动补全——静默导出另一个文件比明确失败更难查）");
        }
        SubTaskSnapshot subTask = subTaskQueryPort.findById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }

        // 上限用与「正文物化」同一把尺子（ArtifactStorageProperties.maxFileSize），
        // 否则会出现「沙箱产物能导、正文产物不能导」的口径分裂。
        int limit = (int) Math.min(Integer.MAX_VALUE, properties.getMaxFileSize());
        SandboxArtifact artifact = sandboxExecutionService.readArtifact(subTaskId, normalizedPath, limit);
        if (artifact == null) {
            throw new BizException("产物不存在或已不可读: " + normalizedPath
                    + "（须为容器内绝对路径；沙箱在该子任务结束后会被回收，届时旧文件不再可读）");
        }
        if (artifact.truncated()) {
            throw new BizException("产物超过单文件上限（" + properties.getMaxFileSize()
                    + " 字节），拒绝导出以免交付被截断的文件: " + path);
        }

        String safeName = ArtifactStorage.sanitizeFileName(resolveFileName(fileName, normalizedPath));
        String ownerName = resolveOwnerName(subTask.assignedAgentId());
        StoredArtifact stored = artifactStorage.store(
                ownerName, subTask.taskId(), subTask.id(), safeName, artifact.bytes());
        Long attachmentId = attachmentPort.register(
                subTask.assignedAgentId(), subTask.id(), safeName, artifact.mime(),
                stored.fileSize(), stored.storageUrl());

        log.info("技能产物导出完成: subTaskId={}, agentId={}, path={}, fileName={}, size={}, sha256={}, "
                        + "attachmentId={}, storageUrl={}",
                subTaskId, agentId, artifact.path(), safeName, artifact.size(), artifact.sha256(),
                attachmentId, stored.storageUrl());
        return new ExportResult(attachmentId, safeName, artifact.mime(), artifact.size(), artifact.sha256());
    }

    /**
     * 文件名：调用方给的名字优先，否则取产物路径的最后一段。
     *
     * <p>不从 MIME 反向猜扩展名——产物路径的扩展名就是权威（技能 CLI 生成时定的），
     * 猜反而会造出 {@code x.html} 被写成 {@code x.txt} 这种错配。</p>
     */
    private static String resolveFileName(String fileName, String path) {
        if (fileName != null && !fileName.isBlank()) {
            return fileName.trim();
        }
        String normalized = path.trim();
        int slash = normalized.lastIndexOf('/');
        String base = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return base.isBlank() ? "artifact" : base;
    }

    /** 解析执行 Agent 注册名作为附件归属目录；Agent 不存在时兜底 {@code agent-{id}}（同物化服务口径）。 */
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
