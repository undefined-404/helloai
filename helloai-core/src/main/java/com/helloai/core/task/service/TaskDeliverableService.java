package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.shared.util.SubTaskDependencyOrder;
import com.helloai.core.system.entity.Attachment;
import com.helloai.core.system.service.AttachmentService;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.task.entity.ReviewRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.ReviewRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 主任务交付物实时聚合打包（Kimi 式 zip 下载）。
 *
 * <p>下载时现场从子任务产出组 zip 返回，不预生成、不落库：历史任务立即可下、
 * 返工后重下即最新、无存储成本。zip 结构：</p>
 * <ul>
 *   <li>{@code 00-任务概览.md}：任务信息 + 子任务完成情况表（状态/Agent/完成时间/核验结论）</li>
 *   <li>{@code NN-xxx}：按依赖拓扑序编号的 DONE 子任务产出——优先收录方案2 物化的
 *       local:// 附件（同名取最新一轮），无附件时回退 context.lastExecution.output
 *       生成单 Markdown（兼容物化上线前的历史任务）</li>
 * </ul>
 *
 * <p>非 DONE 子任务不收录产出，仅在概览表标注状态。产出均为文本，
 * 内存聚合（byte[]）足够，无需流式落盘。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDeliverableService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TaskService taskService;
    private final SubTaskService subTaskService;
    private final AgentService agentService;
    private final AttachmentService attachmentService;
    private final ArtifactStorage artifactStorage;
    private final ReviewRecordMapper reviewRecordMapper;

    /** 打包结果：fileName 为建议下载名（含 .zip），content 为压缩包字节。 */
    public record DeliverablePackage(String fileName, byte[] content) {
    }

    /**
     * 实时聚合任务交付物 zip；任务不存在抛 {@link BizException}(404)。
     */
    public DeliverablePackage buildZip(Long taskId) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new BizException(404, "任务不存在: " + taskId);
        }
        List<SubTask> subTasks = subTaskService.lambdaQuery()
                .eq(SubTask::getTaskId, taskId)
                .orderByAsc(SubTask::getCreateTime)
                .list();
        // 草案与已取消不属于交付范围
        List<SubTask> visible = new ArrayList<>();
        for (SubTask st : subTasks != null ? subTasks : List.<SubTask>of()) {
            if (st.getStatus() != SubTaskStatus.PENDING_PLAN_REVIEW
                    && st.getStatus() != SubTaskStatus.CANCELLED) {
                visible.add(st);
            }
        }
        List<SubTask> ordered = SubTaskDependencyOrder.orderByDependency(visible);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            Set<String> usedNames = new HashSet<>();
            putTextEntry(zos, claimName(usedNames, "00-任务概览.md"), buildOverview(task, ordered));
            int seq = 1;
            // V32：已生成整合报告时置顶收录，子任务产出顺延从 02- 起；无报告时维持旧编号
            if (task.getFinalReport() != null && !task.getFinalReport().isBlank()) {
                putTextEntry(zos, claimName(usedNames, "01-最终整合报告.md"), task.getFinalReport());
                seq = 2;
            }
            for (SubTask st : ordered) {
                if (st.getStatus() != SubTaskStatus.DONE) {
                    continue;
                }
                if (appendSubTaskDeliverables(zos, usedNames, st, seq)) {
                    seq++;
                }
            }
        } catch (IOException e) {
            throw new BizException("交付物打包失败: " + e.getMessage());
        }
        String zipName = sanitizeBaseName(task.getTitle(), "task-" + taskId) + "-交付物.zip";
        log.info("交付物打包完成: taskId={}, subTaskCount={}, zipBytes={}",
                taskId, ordered.size(), bos.size());
        return new DeliverablePackage(zipName, bos.toByteArray());
    }

    /**
     * 收录单个 DONE 子任务的产出，返回是否写入了至少一个文件。
     * 优先本地物化附件（同名取最新一轮），无可读附件回退 lastExecution.output。
     */
    private boolean appendSubTaskDeliverables(ZipOutputStream zos, Set<String> usedNames,
                                              SubTask subTask, int seq) throws IOException {
        String prefix = String.format("%02d-", seq);
        boolean wrote = false;
        // attachmentService.list 按创建时间倒序，putIfAbsent 即"同名取最新"
        Map<String, Attachment> latestByName = new LinkedHashMap<>();
        for (Attachment att : attachmentService.list(subTask.getId())) {
            if (attachmentService.isContentLoadable(att)) {
                latestByName.putIfAbsent(att.getFileName(), att);
            }
        }
        for (Attachment att : latestByName.values()) {
            try {
                byte[] content = artifactStorage.load(att.getStorageUrl());
                String entryName = claimName(usedNames,
                        prefix + sanitizeBaseName(att.getFileName(), "attachment-" + att.getId()));
                putEntry(zos, entryName, content);
                wrote = true;
            } catch (Exception e) {
                // 单附件缺失不拖垮整包（文件可能被人工清理）
                log.warn("交付物附件读取失败，跳过: attachmentId={}, err={}", att.getId(), e.getMessage());
            }
        }
        if (!wrote) {
            String output = extractExecutionOutput(subTask);
            if (output != null && !output.isBlank()) {
                String entryName = claimName(usedNames,
                        prefix + sanitizeBaseName(subTask.getTitle(), "subtask-" + subTask.getId()) + ".md");
                putTextEntry(zos, entryName, output);
                wrote = true;
            }
        }
        return wrote;
    }

    private String buildOverview(Task task, List<SubTask> ordered) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(task.getTitle() != null ? task.getTitle() : "任务交付物").append("\n\n");
        sb.append("- 任务ID：").append(task.getId()).append('\n');
        sb.append("- 任务状态：").append(task.getStatus()).append('\n');
        sb.append("- 导出时间：").append(OffsetDateTime.now().format(TIME_FMT)).append('\n');
        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            sb.append("\n## 任务描述\n\n").append(task.getDescription()).append('\n');
        }
        sb.append("\n## 子任务完成情况\n\n");
        sb.append("| 序号 | 子任务 | 状态 | 执行Agent | 完成时间 | 核验结论 |\n");
        sb.append("|---|---|---|---|---|---|\n");
        int i = 1;
        int notDone = 0;
        for (SubTask st : ordered) {
            if (st.getStatus() != SubTaskStatus.DONE) {
                notDone++;
            }
            sb.append("| ").append(i++)
                    .append(" | ").append(tableCell(st.getTitle()))
                    .append(" | ").append(st.getStatus())
                    .append(" | ").append(tableCell(agentName(st.getAssignedAgentId())))
                    .append(" | ").append(st.getCompleteTime() != null ? st.getCompleteTime().format(TIME_FMT) : "-")
                    .append(" | ").append(tableCell(latestReviewConclusion(st.getId())))
                    .append(" |\n");
        }
        if (notDone > 0) {
            sb.append("\n> 注：仍有 ").append(notDone)
                    .append(" 个子任务未完成（非 DONE），其产出未收录进本包；完成后重新下载即可获取最新交付物。\n");
        }
        return sb.toString();
    }

    /** 最新一轮核验结论（round 倒序取第一条）；无记录返回 "-"。 */
    private String latestReviewConclusion(Long subTaskId) {
        List<ReviewRecord> reviews = reviewRecordMapper.selectList(
                new LambdaQueryWrapper<ReviewRecord>()
                        .eq(ReviewRecord::getSubTaskId, subTaskId)
                        .orderByDesc(ReviewRecord::getRound));
        if (reviews == null || reviews.isEmpty()) {
            return "-";
        }
        ReviewRecord latest = reviews.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append(latest.getResult() != null ? latest.getResult() : "-");
        if (latest.getScore() != null) {
            sb.append("（").append(latest.getScore()).append("分）");
        }
        return sb.toString();
    }

    private String agentName(Long agentId) {
        if (agentId == null) {
            return "-";
        }
        Agent agent = agentService.getById(agentId);
        return agent != null && agent.getName() != null ? agent.getName() : String.valueOf(agentId);
    }

    /** 读取 context.lastExecution.output（与 SubTaskReviewService.extractExecutionOutput 同款先例）。 */
    private static String extractExecutionOutput(SubTask subTask) {
        Map<String, Object> ctx = subTask.getContext();
        if (ctx != null && ctx.get("lastExecution") instanceof Map<?, ?> lastExecution) {
            Object output = lastExecution.get("output");
            if (output instanceof String text) {
                return text;
            }
        }
        return null;
    }

    /** Markdown 表格单元转义：竖线与换行会破坏表格结构。 */
    private static String tableCell(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    /** 文件名基底清洗：去文件系统保留字符，空白用兜底名，限长 60。 */
    private static String sanitizeBaseName(String name, String fallback) {
        String base = name != null ? name.trim() : "";
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        if (base.isBlank()) {
            base = fallback;
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        return base;
    }

    /** zip 内文件名占用登记：重名自动加 (2)、(3)… 后缀。 */
    private static String claimName(Set<String> usedNames, String candidate) {
        if (usedNames.add(candidate)) {
            return candidate;
        }
        int dot = candidate.lastIndexOf('.');
        String stem = dot > 0 ? candidate.substring(0, dot) : candidate;
        String ext = dot > 0 ? candidate.substring(dot) : "";
        for (int i = 2; ; i++) {
            String next = stem + "(" + i + ")" + ext;
            if (usedNames.add(next)) {
                return next;
            }
        }
    }

    private static void putTextEntry(ZipOutputStream zos, String name, String text) throws IOException {
        putEntry(zos, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void putEntry(ZipOutputStream zos, String name, byte[] content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content);
        zos.closeEntry();
    }
}
