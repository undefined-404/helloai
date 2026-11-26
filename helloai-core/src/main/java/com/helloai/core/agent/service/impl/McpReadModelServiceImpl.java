package com.helloai.core.agent.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.AttachmentRef;
import com.helloai.core.agent.port.ConversationMediaRefPort;
import com.helloai.core.agent.port.MediaRef;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskRunningSpecPort;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.McpReadModelService;
import com.helloai.core.agent.service.McpToolService.AttachmentItem;
import com.helloai.core.agent.service.McpToolService.GetDepsSummaryResult;
import com.helloai.core.agent.service.McpToolService.InputAttachmentItem;
import com.helloai.core.agent.service.McpToolService.SubTaskDetail;
import com.helloai.core.agent.support.AttachmentMediaAssembler;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import com.helloai.core.shared.util.TextTruncator;
import com.helloai.core.shared.util.UpstreamAttachmentRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具只读簇：前置产出摘要（{@code getDepsSummary}）与子任务详情（{@code getSubTaskDetail}）。
 *
 * <p><b>来源</b>：由 {@code McpToolServiceImpl}（RM9-a）按职责抽取 —— 本簇两个入口
 * 均为<b>纯只读组装</b>（零状态变更、零事务写入），是 {@code RM计划 §4.3.1} 三类中最小且最安全的一簇。</p>
 *
 * <p><b>守卫不在此层</b>：{@code assertAgentActive} / {@code assertToolEnabled} /
 * {@code refreshDutyLease} 三个横切守卫仍留在门面 {@code McpToolServiceImpl}（每个工具入口都调，
 * 单一注册点），本类只承担「拿到已鉴权的入参后如何组装返回」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpReadModelServiceImpl implements McpReadModelService {

    private final SubTaskQueryPort subTaskQueryPort;
    private final TaskRunningSpecPort taskRunningSpecPort;
    private final AttachmentPort attachmentPort;
    private final AgentExecutionRecordService agentExecutionRecordService;
    /**
     * 会话输入附件作用域端口（`G-051`）：内部执行链下发 {@code inputAttachments} 的数据源。
     *
     * <p>作用域解析（{@code task.context.conversationId} + {@code sub_task.context} 的附件声明）
     * 整体留在 planner 域实现里——本类只拿引用，不认识任何一个键名。字段追加在末尾，
     * 避免位置化构造（测试 / 手写 new）错位。</p>
     */
    private final ConversationMediaRefPort conversationMediaRefPort;

    /**
     * 获取指定子任务的直接前置产出摘要（结构化）。
     *
     * <p>执行链在 executeOnce 时已把依赖产出注入 Prompt（buildDependencySection），
     * 但外部 Agent 开工前无法主动查看——本工具提供接口层能力，数据口径与执行链同源：
     * 执行记录摘要（TaskRunningSpec）优先 + 内容本体（物化附件 local:// 平台直读，回退
     * {@code context.lastExecution.output}），单条超 {@link UpstreamAttachmentRenderer#DEP_CONTENT_MAX_CHARS} 字符截断并打标；
     * 任何异常降级为 degraded（deps 为空，不阻断调用），与执行链降级哲学一致。</p>
     */
    @Override
    public GetDepsSummaryResult getDepsSummary(Long agentId, Long subTaskId) {
        SubTaskSnapshot subTask = subTaskQueryPort.findById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }

        List<Long> dependsOn = subTask.dependsOn();
        GetDepsSummaryResult result = new GetDepsSummaryResult();
        result.setSubTaskId(subTaskId);
        result.setTaskId(subTask.taskId());
        result.setDegraded(false);
        if (dependsOn == null || dependsOn.isEmpty()) {
            result.setDepCount(0);
            result.setLoadedCount(0);
            result.setTruncatedCount(0);
            // 无前置视为就绪（与 SubTaskService.isReady 的「空依赖恒就绪」同口径）
            result.setReady(true);
            result.setNotReadyCount(0);
            result.setDeps(Collections.emptyList());
            return result;
        }

        try {
            List<SubTaskSnapshot> deps = subTaskQueryPort.listByIds(dependsOn);
            Map<Long, SubTaskSnapshot> depMap = new HashMap<>();
            if (deps != null) {
                for (SubTaskSnapshot dep : deps) {
                    depMap.put(dep.id(), dep);
                }
            }

            List<GetDepsSummaryResult.DepItem> items = new ArrayList<>();
            int loadedCount = 0;
            int truncatedCount = 0;
            int notReadyCount = 0;
            for (Long depId : dependsOn) {
                SubTaskSnapshot dep = depMap.get(depId);
                if (dep == null) {
                    continue;
                }
                GetDepsSummaryResult.DepItem item = new GetDepsSummaryResult.DepItem();
                item.setSubTaskId(dep.id());
                item.setTitle(dep.title());
                item.setStatus(dep.status() != null ? dep.status().name() : null);
                if (dep.status() != SubTaskStatus.DONE) {
                    notReadyCount++;
                }
                String summary = taskRunningSpecPort.findExecutionSummary(subTask.taskId(), depId);
                if (summary != null && !summary.isBlank()) {
                    item.setSummary(summary);
                }
                String content = loadUpstreamContent(dep);
                if (content != null && !content.isBlank()) {
                    // P-1 防御：行边界回退截断 + 结构化 [TRUNCATED] 标注行（与核验侧同口径，
                    // 消费方可机读「哪些内容不可见」），替代原硬切 substring(0, 4000)
                    int originalChars = content.length();
                    String render = TextTruncator.truncateAtLineBoundary(content, UpstreamAttachmentRenderer.DEP_CONTENT_MAX_CHARS);
                    if (render.length() < originalChars) {
                        render = render + "\n[TRUNCATED] shown=" + render.length()
                                + " total=" + originalChars + " reason=dep_content_limit";
                        item.setTruncated(true);
                        truncatedCount++;
                    } else if (render.contains("[TRUNCATED] file=")) {
                        // R2（审计 §15.4）：附件路径逐附件截断标注已在 render 内
                        // （总长受控故顶层不触发）——布尔位与计数如实反映「存在不可见内容」
                        item.setTruncated(true);
                        truncatedCount++;
                    }
                    item.setContent(render);
                    item.setLoaded(true);
                    item.setContentChars(render.length());
                    loadedCount++;
                } else {
                    // P1-3：显式标注「未读到内容」，让消费方与「无前置」区分开
                    item.setLoaded(false);
                    item.setContentChars(0);
                }
                // G-034 剩余项①：deps 里必须给出上游附件的 id 与 loadable——
                // 否则消费方只能对着上面截断过的 content 文本做猜测：猜不到就 find / 白跑，
                // 或者凭空重建一份上游文档（真机症状已复现两次）；给了 id 才能
                // internal_read_attachment_range 定向续读、internal_stage_attachment_to_sandbox 实测。
                // 复用子任务级同一装配（映射口径单源，判据 4），异常内部降级空列表不阻断摘要。
                item.setAttachments(buildAttachmentItems(dep.id()));
                items.add(item);
            }
            result.setDeps(items);
            result.setDepCount(dependsOn.size());
            result.setLoadedCount(loadedCount);
            result.setTruncatedCount(truncatedCount);
            result.setReady(notReadyCount == 0);
            result.setNotReadyCount(notReadyCount);
            return result;
        } catch (Exception e) {
            log.warn("getDepsSummary 收集失败，降级返回: subTaskId={}, err={}", subTaskId, e.getMessage());
            result.setDeps(Collections.emptyList());
            result.setDepCount(dependsOn.size());
            result.setLoadedCount(0);
            result.setTruncatedCount(0);
            result.setDegraded(true);
            return result;
        }
    }

    /**
     * 读取前置子任务的完成内容本体：物化附件（local:// 平台直读，仅 ACTIVE 有效版本——同名历史版本已由 {@code AttachmentService.register} 自动去活）
     * 优先，失败/无附件回退 {@code context.lastExecution.output} 原始产出；两者均无返回 null。
     * 与执行链侧的上游产出装载逻辑同源但**消费方隔离**（各自持有，避免渲染逻辑耦合）。
     *
     * <p><b>P-1 修复</b>：反转 {@code listActive} 倒序为正序（最早创建在前，
     * 主文件优先）+ 拼接全部可加载 ACTIVE 附件（各带 {@code 【文件：xxx】} 来源标题行）——
     * 原实现只取倒序第一个可加载附件，后创建的附录会把主文件挤出（tku-e2e-01 事故根因）。</p>
     *
     * <p><b>P-1 修复 R2（审计 §15.4）</b>：拼接改为
     * {@link UpstreamAttachmentRenderer} 逐附件配额渲染（主附件保底 + 次要最低配额 +
     * 逐附件 {@code [TRUNCATED] file=...} 标注）——替代「拼接后单点 4000 截断」，
     * 第二个及以后附件不再整体不可见。</p>
     */
    private String loadUpstreamContent(SubTaskSnapshot dep) {
        try {
            List<AttachmentRef> attachments = attachmentPort.listActive(dep.id());
            if (attachments != null && !attachments.isEmpty()) {
                List<AttachmentRef> ordered = new ArrayList<>(attachments);
                Collections.reverse(ordered);
                List<UpstreamAttachmentRenderer.LoadedAttachment> loaded = new ArrayList<>();
                for (AttachmentRef attachment : ordered) {
                    try {
                        if (!attachment.contentLoadable()) {
                            continue;
                        }
                        // `VU-1` 同一判据的第二个消费方：图片/音视频不得进文本通道。
                        // 此前这里与装配器同款无差别 UTF-8 解码——上游产出图（如 chart.png）
                        // 会被解成乱码塞进 MCP 工具的 deps content，执行者读到的是垃圾。
                        // 判据走公共组件，不在此处另写扩展名表（判据 4：同一事实双写必分叉）。
                        if (AttachmentMediaAssembler.isInlineImage(attachment)) {
                            continue;
                        }
                        if (AttachmentContentPolicy.isMedia(attachment.mimeType(), attachment.fileName())) {
                            continue;
                        }
                        byte[] bytes = attachmentPort.loadContent(attachment.id());
                        if (bytes == null || bytes.length == 0) {
                            continue;
                        }
                        String fileName = attachment.fileName() != null && !attachment.fileName().isBlank()
                                ? attachment.fileName() : "attachment-" + attachment.id();
                        loaded.add(new UpstreamAttachmentRenderer.LoadedAttachment(
                                fileName, new String(bytes, StandardCharsets.UTF_8), attachment.id()));
                    } catch (Exception singleEx) {
                        log.warn("读取单个前置附件失败，跳过该附件: subTaskId={}, attachmentId={}, err={}",
                                dep.id(), attachment.id(), singleEx.getMessage());
                    }
                }
                if (!loaded.isEmpty()) {
                    return UpstreamAttachmentRenderer.render(loaded, UpstreamAttachmentRenderer.DEP_CONTENT_MAX_CHARS);
                }
            }
        } catch (Exception e) {
            log.warn("读取前置物化附件内容失败，回退原始产出: subTaskId={}, err={}", dep.id(), e.getMessage());
        }
        return SubTaskOutputExtractor.extractExecutionOutput(dep.context());
    }

    /**
     * 查询子任务详情（执行内容 / 交付物 / 验收标准 / 约束 / 不确定性）。
     *
     * <p>缺口背景：外部执行者此前只能从收件箱摘要看到「交付物」，而审查侧轨道 A 按
     * {@code acceptance} 核验——执行者看不到验收标准却要被按验收标准判定，是独立的
     * 结构性错配。本工具把开工所需的子任务全文一次性下发，字段口径与平台内执行 Prompt
     * 的「当前任务」四要素同源，不做事后截断（截断验收标准正是缺口成因之一）。</p>
     *
     * <p>授权（最小必要）：仅「已分配给本 Agent」或「未分配且 PENDING（可认领）」的
     * 子任务可查——前者覆盖认领后开工 / 返工 / 重连，后者覆盖认领前评估；已转给他人、
     * 已回收或已终结的子任务对非归属者不可见。</p>
     */
    @Override
    public SubTaskDetail getSubTaskDetail(Long agentId, Long subTaskId) {
        return buildSubTaskDetail(loadAuthorized(agentId, subTaskId));
    }

    /**
     * 内部执行链专用详情：多下发「本子任务作用域内的会话输入附件」清单（`G-051`）。
     *
     * <p><b>为什么外部通道不下发</b>：外部 Agent 取字节走 {@code GET /api/attachments/downloadById/{id}}，
     * 那条路读的是 {@code attachment}（task 域产物表）。把 {@code chat_attachment} 的 id 给它，
     * 它只会拿到「附件不存在」，等于用一份看得见拿不到的清单把它引向死路。</p>
     */
    @Override
    public SubTaskDetail getSubTaskDetailForInternal(Long agentId, Long subTaskId) {
        return buildSubTaskDetail(loadAuthorized(agentId, subTaskId), true);
    }

    /**
     * 授权校验 + 取快照（两条详情入口共用，避免授权口径漂移）。
     *
     * <p>授权（最小必要）：仅「已分配给本 Agent」或「未分配且 PENDING（可认领）」的子任务可查——
     * 前者覆盖认领后开工 / 返工 / 重连，后者覆盖认领前评估；已转给他人、已回收或已终结的子任务
     * 对非归属者不可见。</p>
     */
    private SubTaskSnapshot loadAuthorized(Long agentId, Long subTaskId) {
        SubTaskSnapshot subTask = subTaskQueryPort.findById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        boolean assignedToMe = agentId.equals(subTask.assignedAgentId());
        boolean claimable = subTask.assignedAgentId() == null
                && subTask.status() == SubTaskStatus.PENDING;
        if (!assignedToMe && !claimable) {
            throw new BizException("无权查看该子任务: subTaskId=" + subTaskId
                    + ", assignedAgentId=" + subTask.assignedAgentId()
                    + ", status=" + subTask.status());
        }
        return subTask;
    }

    /**
     * 子任务快照 → 详情 DTO（claimSubTask 成功路径与 getSubTaskDetail 共用，避免两处口径漂移）。
     *
     * <p>{@code requiredSkills} 走 {@code SubTaskQueryPort#mergeSkills}（子任务级 ∪ 任务级，
     * 合并规则整体留 task 域），与执行侧注入 / 审查侧核验同一清单；提供方已保证非 null，
     * 此处仍回落空列表（防御，不阻断下发）。</p>
     *
     * <p><b>跨类唯一消费方</b>：{@code McpTaskClaimService.claimSubTask} 认领成功路径同样调用本方法
     * （RM9-a 抽取后成为 {@code McpTaskClaimService → McpReadModelService} 的单向依赖，
     * 无环、无 helper 环）。</p>
     */
    @Override
    public SubTaskDetail buildSubTaskDetail(SubTaskSnapshot subTask) {
        return buildSubTaskDetail(subTask, false);
    }

    /**
     * 子任务快照 → 详情 DTO（可选是否附带输入附件清单，`G-051`）。
     *
     * <p>{@code inputAttachments} 恒被显式赋值：空列表而非 null。JSON 里 {@code null} 与 {@code []}
     * 对模型的含义不同——后者是「我查过了，作用域内没有输入件」，前者像「这个字段不存在」，
     * 后者会让模型以为可以继续试。</p>
     */
    private SubTaskDetail buildSubTaskDetail(SubTaskSnapshot subTask, boolean withInputAttachments) {
        SubTaskDetail detail = new SubTaskDetail();
        detail.setSubTaskId(subTask.id());
        detail.setTaskId(subTask.taskId());
        // ADR-009 §8.7：conversationGroupId = taskId（A2A 会话分组最小投影，第一批取值即 taskId）
        detail.setConversationGroupId(subTask.taskId());
        detail.setTitle(subTask.title());
        detail.setContent(subTask.content());
        detail.setDeliverable(subTask.deliverable());
        detail.setAcceptance(subTask.acceptance());
        detail.setConstraints(subTask.constraints());
        // G-037：类型化约束文档引用随详情下发（外部 Agent 无进程内工具通道，只能靠此字段看见契约）
        detail.setConstraintRefs(subTask.constraintRefs() != null
                ? subTask.constraintRefs() : Collections.emptyList());
        detail.setUncertainties(subTask.uncertainties());
        List<String> mergedSkills = subTaskQueryPort.mergeSkills(subTask.id());
        detail.setRequiredSkills(mergedSkills != null ? mergedSkills : Collections.emptyList());
        detail.setPriority(subTask.priority());
        detail.setStatus(subTask.status() != null ? subTask.status().name() : null);
        detail.setContract(Integer.valueOf(1).equals(subTask.isContract()));
        detail.setDependsOn(subTask.dependsOn());
        detail.setDeadline(subTask.deadline() != null ? subTask.deadline().toString() : null);
        detail.setReworkCount(subTask.reworkCount());
        detail.setAttachments(buildAttachmentItems(subTask.id()));
        detail.setInputAttachments(withInputAttachments
                ? buildInputAttachmentItems(subTask) : Collections.emptyList());
        detail.setContributors(buildContributors(subTask));
        return detail;
    }

    /**
     * 组装子任务贡献者清单（P1-7 产出归属可见性）。
     *
     * <p>数据源：执行记录（{@code agent_execution_record.agent_id}）∪ 当前执行者
     * （{@code sub_task.assigned_agent_id}），去重保序（当前执行者优先）。
     * 读取失败降级为「仅当前执行者」，不阻断详情下发。</p>
     */
    private List<Long> buildContributors(SubTaskSnapshot subTask) {
        LinkedHashSet<Long> contributors = new LinkedHashSet<>();
        if (subTask.assignedAgentId() != null) {
            contributors.add(subTask.assignedAgentId());
        }
        try {
            List<AgentExecutionRecord> records = agentExecutionRecordService.lambdaQuery()
                    .select(AgentExecutionRecord::getAgentId)
                    .eq(AgentExecutionRecord::getSubTaskId, subTask.id())
                    .list();
            if (records != null) {
                for (AgentExecutionRecord record : records) {
                    if (record.getAgentId() != null) {
                        contributors.add(record.getAgentId());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("组装子任务贡献者清单失败（降级为仅当前执行者）: subTaskId={}, err={}",
                    subTask.id(), e.getMessage());
        }
        return new ArrayList<>(contributors);
    }

    /**
     * 组装子任务 ACTIVE 附件清单（P1-4-b 附件可发现性）。
     *
     * <p>读取失败降级为空列表，不阻断详情下发（与事件记录 best-effort 同口径）。</p>
     */
    private List<AttachmentItem> buildAttachmentItems(Long subTaskId) {
        try {
            List<AttachmentRef> attachments = attachmentPort.listActive(subTaskId);
            if (attachments == null || attachments.isEmpty()) {
                return Collections.emptyList();
            }
            List<AttachmentItem> items = new ArrayList<>(attachments.size());
            for (AttachmentRef attachment : attachments) {
                AttachmentItem item = new AttachmentItem();
                item.setAttachmentId(attachment.id());
                item.setFileName(attachment.fileName());
                item.setFileType(attachment.fileType());
                item.setMimeType(attachment.mimeType());
                item.setFileSize(attachment.fileSize());
                item.setStatus(attachment.status() != null ? attachment.status().name() : null);
                item.setLoadable(attachment.contentLoadable());
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.warn("组装子任务附件清单失败（降级为空列表）: subTaskId={}, err={}", subTaskId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 组装本子任务作用域内的会话输入附件清单（`G-051`）。
     *
     * <p><b>为什么不能顺手并进 {@code attachments}</b>：那张清单的语义是「本子任务<b>产出</b>的东西」，
     * 六个消费方（交付物闸、上游产出装载、契约回流、核验证据集、MCP 读模型、「我的产物」）都以此为前提。
     * 把用户上传的输入件混进去，最直接的后果是交付物闸对所有带图子任务恒判「有产物」，闸门形同废弃。</p>
     *
     * <p>读取失败降级为空列表（与 {@link #buildAttachmentItems} 同口径）：清单是「可发现性」增强，
     * 不该因为它把详情下发整条打断。空列表的语义是「作用域内没有可注入的输入件」，
     * 注入侧据此 fail-close，行为与「查询失败」一致（都是拒绝注入），不会误放行。</p>
     */
    private List<InputAttachmentItem> buildInputAttachmentItems(SubTaskSnapshot subTask) {
        try {
            List<MediaRef> refs = conversationMediaRefPort.listScopedInputAttachments(
                    subTask.taskId(), subTask.id(), subTask.context());
            if (refs == null || refs.isEmpty()) {
                return Collections.emptyList();
            }
            List<InputAttachmentItem> items = new ArrayList<>(refs.size());
            for (MediaRef ref : refs) {
                if (ref == null || ref.attachmentId() == null) {
                    continue;
                }
                InputAttachmentItem item = new InputAttachmentItem();
                item.setAttachmentId(ref.attachmentId());
                item.setFileName(ref.fileName());
                item.setMimeType(ref.mimeType());
                item.setFileSize(ref.fileSize());
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.warn("组装子任务输入附件清单失败（降级为空列表）: subTaskId={}, err={}",
                    subTask.id(), e.getMessage());
            return Collections.emptyList();
        }
    }
}
