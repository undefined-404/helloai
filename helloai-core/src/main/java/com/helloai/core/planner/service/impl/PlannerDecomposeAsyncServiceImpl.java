package com.helloai.core.planner.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskArchetype;
import com.helloai.common.constant.TaskPriority;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.common.constant.UncertaintyKind;
import com.helloai.core.agent.SkillNormalizer;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.skill.SkillPackage;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.context.ArchetypeDimensionsSection;
import com.helloai.core.planner.context.DecomposeContextSnapshotRenderer;
import com.helloai.core.planner.policy.AcceptanceKindValidator;
import com.helloai.core.planner.policy.AcceptanceSatisfiabilityChecker;
import com.helloai.core.planner.policy.PlannerGranularity;
import com.helloai.core.planner.policy.PlannerGranularityResolver;
import com.helloai.core.planner.policy.RequirementPackage;
import com.helloai.core.planner.policy.RequirementPackageParser;
import com.helloai.core.planner.policy.TaskContextKeys;
import com.helloai.core.planner.service.PlannerAnalysisService.PlanDraftItem;
import com.helloai.core.planner.service.PlannerDecomposeAsyncService;
import com.helloai.core.task.port.SubTaskDraft;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.port.UncertaintyDraft;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.task.port.PlannerAgentRef;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Planner 任务拆解异步执行服务实现。
 *
 * <p>承接拆解异步化改造前的 LLM 拆解段：选平台内 API_KEY_LLM Planner → 渲染 Prompt →
 * {@code executeSync} 调 LLM → 解析校验草案 → 批量落库 {@code PENDING_PLAN_REVIEW} →
 * 依赖序号回写真实 id → 记录 {@code task_plan_generated}。</p>
 *
 * <p>运行于专用线程池 {@code plannerDecomposeExecutor}（{@code @Async} 跨类代理生效，
 * 由 {@code PlannerAnalysisServiceImpl.decompose} 注入本接口提交，禁止同类自调用）。
 * 失败路径内部闭环：回退 PLANNING → PENDING + 记录 {@code task_plan_failed}，
 * LLM 调用失败记 ERROR、可恢复降级记 WARN（CODE_STYLE §14.2），日志均带 taskId 业务标识。</p>
 *
 * <p>新增 {@code task_plan_llm_call_end} timeline 事件（耗时毫秒、finishReason、tokenUsage），
 * 补齐原链路"只有 _start 没有 _end"的可观测缺口。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerDecomposeAsyncServiceImpl implements PlannerDecomposeAsyncService {

    /** 单次拆解允许落库的草案数量上限（与 Prompt 模板的 3~10 约定对齐，服务端只做硬上限）。 */
    private static final int MAX_DRAFT_COUNT = 10;

    /** G-010 §5 防御：技能目录注入 Prompt 的条数上限（超限截断至前 N 项并提示，防提示词膨胀）。 */
    private static final int MAX_SKILL_CATALOG_SIZE = 20;

    /** timeline detail 中 LLM 原始输出摘要的截断长度。 */
    private static final int RAW_OUTPUT_SUMMARY_LIMIT = 500;

    private static final String PROMPT_TEMPLATE_PATH = "prompts/planner-decompose.md";

    /** `TD-9` expand：单条草案 FINE 粒度细拆专用模板（不复用整任务模板，避免重拆整任务）。 */
    private static final String EXPAND_PROMPT_TEMPLATE_PATH = "prompts/planner-expand.md";

    /** `TD-9` regenerate：单条草案同粒度重写专用模板。 */
    private static final String REGENERATE_PROMPT_TEMPLATE_PATH = "prompts/planner-regenerate.md";

    private static final Set<String> VALID_PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final TaskService taskService;
    private final SubTaskService subTaskService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final TaskTimelineService taskTimelineService;
    private final AgentService agentService;
    private final AgentSkillSpecService agentSkillSpecService;
    private final DecomposeContextSnapshotRenderer contextSnapshotRenderer;
    private final ObjectMapper objectMapper;

    /**
     * 异步执行拆解（运行于 plannerDecomposeExecutor）。
     *
     * <p>入口幂等防御：仅当任务仍处 PLANNING 才执行——若已被 PlanningTimeoutTask 超时回收
     * 或用户已确认/拒绝，慢线程迟到时直接跳过，避免覆盖并发结果。</p>
     */
    @Async("plannerDecomposeExecutor")
    @Override
    public void executeDecompose(Long taskId) {
        TaskView task = taskService.getView(taskId);
        if (task == null || task.status() != TaskStatus.PLANNING) {
            log.info("拆解异步执行跳过（任务不存在或已离开 PLANNING）: taskId={}, status={}",
                    taskId, task != null ? task.status() : null);
            return;
        }
        try {
            doDecompose(task);
        } catch (Exception e) {
            rollbackToPending(taskId);
            taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_FAILED.code(),
                    AgentRole.PLANNER, null,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            log.error("任务拆解失败，已回退 PENDING: taskId={}", taskId, e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  `TD-9` 单条草案 expand / regenerate（同步，用户等待结果）
    // ══════════════════════════════════════════════════════════════

    /**
     * `TD-9` 单条草案细拆：一条草案 → N 条 FINE 粒度子草案（同步）。
     *
     * <p>门禁复用 {@code updateDraft} 口径（仅 PENDING_PLAN_REVIEW）；被其他草案依赖的
     * 草案拒绝展开（删除后引用成幽灵依赖，fail-close）。事务内完成：LLM/校验失败回滚，
     * 原草案不被破坏。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<SubTaskView> expandById(Long subTaskId) {
        SubTaskView draft = subTaskService.getView(subTaskId);
        if (draft == null) {
            throw new BizException("草案不存在: " + subTaskId);
        }
        if (draft.status() != SubTaskStatus.PENDING_PLAN_REVIEW) {
            throw new BizException("仅草案待审状态可展开，当前状态: " + draft.status());
        }
        Long taskId = draft.taskId();
        TaskView task = taskService.getView(taskId);
        if (task == null) {
            throw new BizException("草案所属任务不存在: " + taskId);
        }
        // 幽灵依赖预防：其他草案 dependsOn 含本草案 → 拒绝（本草案删除会让引用悬空）
        List<Long> referencing = referencingDrafts(taskId, subTaskId);
        if (!referencing.isEmpty()) {
            throw new BizException("该草案被其他草案依赖（ids=" + referencing
                    + "），无法展开；请先处理依赖关系");
        }
        log.info("单条草案展开开始: subTaskId={}, taskId={}, title={}",
                subTaskId, taskId, draft.title());
        PlannerAgentRef planner = plannerAgentPicker.pickForTask(taskId);
        String prompt = renderExpandPrompt(task, draft, planner);
        AgentResult result = executeLlmForSingleDraft(planner, prompt, taskId, "planner_expand");
        List<PlanDraftItem> items = parseDraftItems(taskId, planner, result.getOutput());
        // expand 组内依赖校验（Kahn 环检测；序号 1-based 指向本批）
        validateDependencies(items);
        // FINE 粒度固定：expand 语义就是细拆，不随任务粒度决策漂移
        PlannerGranularityResolver.GranularityDecision decision =
                new PlannerGranularityResolver.GranularityDecision(PlannerGranularity.FINE, "单条展开（FINE）");
        List<SubTaskDraft> drafts = buildDrafts(task, items, planner, decision);
        List<SubTaskView> persisted = subTaskService.saveDrafts(drafts);
        // 依赖序号回写真实 id（组内）；重加载顺序与 items 一致
        List<SubTaskView> reloaded = subTaskService.listDraftsInInsertOrder(
                taskId, SubTaskStatus.PENDING_PLAN_REVIEW);
        List<SubTaskView> newlyCreated = reloaded.stream()
                .filter(v -> v.createTime() != null && persisted.stream()
                        .anyMatch(p -> p.id() != null && p.id().equals(v.id())))
                .toList();
        // 用「新增的 N 条」做依赖回写：从 reloaded 里按 persisted id 定位
        applyDependsOn(reloaded.stream()
                .filter(v -> persisted.stream().anyMatch(p -> p.id() != null && p.id().equals(v.id())))
                .toList(), items);
        // 删除原草案（逻辑删，审计保留）
        subTaskService.removeById(subTaskId);
        taskTimelineService.recordEvent(taskId, subTaskId, TimelineEventCode.TASK_PLAN_DRAFT_EXPANDED.code(),
                AgentRole.PLANNER, planner.id(),
                Map.of("originalSubTaskId", subTaskId, "draftCount", items.size(),
                        "newSubTaskIds", newlyCreated.stream().map(SubTaskView::id).toList()));
        log.info("单条草案展开完成: subTaskId={}, taskId={}, newCount={}",
                subTaskId, taskId, items.size());
        return newlyCreated;
    }

    /**
     * `TD-9` 单条草案重生成：同粒度重写四要素（同步），title/id/dependsOn 不变量白名单。
     *
     * <p>LLM 输出单条对象；服务端只取 {@code content} / {@code deliverable} /
     * {@code acceptance} / {@code acceptanceKind} 四字段落库（走 {@code updateDraftContent}），
     * 其余字段（title/priority/skills/constraints/dependsOn/contract）一律保持原值。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubTaskView regenerateById(Long subTaskId) {
        SubTaskView draft = subTaskService.getView(subTaskId);
        if (draft == null) {
            throw new BizException("草案不存在: " + subTaskId);
        }
        if (draft.status() != SubTaskStatus.PENDING_PLAN_REVIEW) {
            throw new BizException("仅草案待审状态可重生成，当前状态: " + draft.status());
        }
        Long taskId = draft.taskId();
        TaskView task = taskService.getView(taskId);
        if (task == null) {
            throw new BizException("草案所属任务不存在: " + taskId);
        }
        log.info("单条草案重生成开始: subTaskId={}, taskId={}, title={}",
                subTaskId, taskId, draft.title());
        PlannerAgentRef planner = plannerAgentPicker.pickForTask(taskId);
        String prompt = renderRegeneratePrompt(task, draft, planner);
        AgentResult result = executeLlmForSingleDraft(planner, prompt, taskId, "planner_regenerate");
        // 输出为单条 JSON 对象（非数组），解析为单条 PlanDraftItem
        PlanDraftItem item = parseSingleRegenerateOutput(taskId, planner, result.getOutput());
        // 不变量白名单：只取四要素；title / dependsOn 用原草案值（LLM 输出被忽略）
        String newAcceptance = requireChanged(item.getAcceptance(), draft.acceptance(), "acceptance");
        subTaskService.updateDraftContent(subTaskId,
                requireChanged(item.getContent(), draft.content(), "content"),
                requireChanged(item.getDeliverable(), draft.deliverable(), "deliverable"),
                newAcceptance,
                item.getAcceptanceKind());
        // TD-11：验收文本被改写 ⇒ 旧标注（按旧文本算出来的）必须清掉，不能留着。
        // 留着 = 拿过期证据去放宽核验，比「没标注」更危险（计划 §6 风险 8「解析不到即不豁免」）。
        // 未改写则保留——重写正文/交付物不动判定依据，重算只会白烧一次判定。
        clearStaleAcceptancePreconditions(subTaskId, draft, newAcceptance);
        SubTaskView updated = subTaskService.getView(subTaskId);
        taskTimelineService.recordEvent(taskId, subTaskId, TimelineEventCode.TASK_PLAN_DRAFT_REGENERATED.code(),
                AgentRole.PLANNER, planner.id(),
                Map.of("subTaskId", subTaskId, "title", draft.title()));
        log.info("单条草案重生成完成: subTaskId={}, taskId={}, title={}",
                subTaskId, taskId, draft.title());
        return updated;
    }

    /**
     * `TD-11`：重生成改写了验收文本时，清掉按旧文本算出的 {@code acceptancePreconditions}。
     *
     * <p>为什么是「清」而不是「重算」：重算需要归一化后的 uncertainties（含 {@code ref}）与
     * dependsOn 的上游文本，而 {@code SubTaskView} 目前不带 {@code ref}；为一条低频路径扩
     * 快照契约的收益不足。**清掉 = 回落到「无标注」= 核验侧不豁免 = 保持现状严格**，
     * 这正是计划 §6 风险 8 定下的 fail-safe 方向。要恢复标注，重新拆解一次即可。</p>
     */
    private void clearStaleAcceptancePreconditions(Long subTaskId, SubTaskView draft, String newAcceptance) {
        Map<String, Object> ctx = draft.context();
        if (ctx == null || !ctx.containsKey(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS)) {
            return;
        }
        String oldAcceptance = draft.acceptance() == null ? "" : draft.acceptance().trim();
        if (oldAcceptance.equals(newAcceptance == null ? "" : newAcceptance.trim())) {
            return;
        }
        try {
            Map<String, Object> updated = new HashMap<>(ctx);
            updated.remove(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS);
            subTaskService.updateContext(subTaskId, updated);
            log.info("验收文本已改写，清除 TD-11 旧标注（回落为不豁免）: subTaskId={}", subTaskId);
        } catch (Exception e) {
            log.warn("清除 TD-11 旧标注失败（不阻断重生成）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }
    }

    /**
     * regenerate 不变量白名单：LLM 输出的四要素若为空/blank，保留原值（不因 LLM 少字段而清空）。
     */
    private static String requireChanged(String llmValue, String original, String field) {
        return llmValue != null && !llmValue.isBlank() ? llmValue : original;
    }

    /** 查找 dependsOn 列表中含指定 id 的其他草案（expand 幽灵依赖预防）。 */
    private List<Long> referencingDrafts(Long taskId, Long subTaskId) {
        List<SubTaskView> siblings = subTaskService.listDraftsInInsertOrder(
                taskId, SubTaskStatus.PENDING_PLAN_REVIEW);
        List<Long> referencing = new ArrayList<>();
        for (SubTaskView v : siblings) {
            if (v.id() != null && v.id().equals(subTaskId)) {
                continue; // 自己
            }
            if (v.dependsOn() != null && v.dependsOn().contains(subTaskId)) {
                referencing.add(v.id());
            }
        }
        return referencing;
    }

    /** 渲染 expand 提示词（单条草案四要素 + 任务快照 + 技能目录 + 需求包）。 */
    private String renderExpandPrompt(TaskView task, SubTaskView draft, PlannerAgentRef planner) {
        ClassPathResource resource = new ClassPathResource(EXPAND_PROMPT_TEMPLATE_PATH);
        if (!resource.exists()) {
            throw new BizException("未找到展开 Prompt 模板: " + EXPAND_PROMPT_TEMPLATE_PATH);
        }
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("读取展开 Prompt 模板失败: " + e.getMessage());
        }
        TaskArchetype archetype = task.taskArchetype() != null
                ? task.taskArchetype() : TaskArchetype.DEFAULT();
        String skills = draft.requiredSkills() != null && !draft.requiredSkills().isEmpty()
                ? String.join(", ", draft.requiredSkills()) : "（草案未指派技能）";
        String constraints = draft.constraints() != null && !draft.constraints().isBlank()
                ? draft.constraints() : "（草案无约束）";
        return template
                .replace("{{TASK_TITLE}}", task.title() != null ? task.title() : "")
                .replace("{{TASK_ARCHETYPE}}", archetype.name())
                .replace("{{TASK_DESCRIPTION}}",
                        task.description() != null && !task.description().isBlank()
                                ? task.description() : "（无补充描述）")
                .replace("{{DRAFT_TITLE}}", draft.title() != null ? draft.title() : "")
                .replace("{{DRAFT_CONTENT}}", draft.content() != null ? draft.content() : "")
                .replace("{{DRAFT_DELIVERABLE}}", draft.deliverable() != null ? draft.deliverable() : "")
                .replace("{{DRAFT_ACCEPTANCE}}", draft.acceptance() != null ? draft.acceptance() : "")
                .replace("{{DRAFT_ACCEPTANCE_KIND}}",
                        draft.acceptanceKind() != null ? draft.acceptanceKind().name() : "AUTOMATED")
                .replace("{{DRAFT_PRIORITY}}", draft.priority() != null ? draft.priority() : "MEDIUM")
                .replace("{{DRAFT_SKILLS}}", skills)
                .replace("{{DRAFT_CONSTRAINTS}}", constraints)
                .replace("{{SKILL_CATALOG}}", renderSkillCatalog())
                .replace("{{TASK_CONTEXT_SNAPSHOT}}", renderSnapshotSafely(task))
                .replace("{{REQUIREMENT_PACKAGE}}", renderRequirementPackage(task))
                .replace("{{SUBTASK_COUNT_RULE}}", renderSubtaskCountRule(task));
    }

    /** 渲染 regenerate 提示词（同 expand 基础 + 重写语义）。 */
    private String renderRegeneratePrompt(TaskView task, SubTaskView draft, PlannerAgentRef planner) {
        ClassPathResource resource = new ClassPathResource(REGENERATE_PROMPT_TEMPLATE_PATH);
        if (!resource.exists()) {
            throw new BizException("未找到重生成 Prompt 模板: " + REGENERATE_PROMPT_TEMPLATE_PATH);
        }
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("读取重生成 Prompt 模板失败: " + e.getMessage());
        }
        TaskArchetype archetype = task.taskArchetype() != null
                ? task.taskArchetype() : TaskArchetype.DEFAULT();
        String skills = draft.requiredSkills() != null && !draft.requiredSkills().isEmpty()
                ? String.join(", ", draft.requiredSkills()) : "（草案未指派技能）";
        String constraints = draft.constraints() != null && !draft.constraints().isBlank()
                ? draft.constraints() : "（草案无约束）";
        return template
                .replace("{{TASK_TITLE}}", task.title() != null ? task.title() : "")
                .replace("{{TASK_ARCHETYPE}}", archetype.name())
                .replace("{{TASK_DESCRIPTION}}",
                        task.description() != null && !task.description().isBlank()
                                ? task.description() : "（无补充描述）")
                .replace("{{DRAFT_TITLE}}", draft.title() != null ? draft.title() : "")
                .replace("{{DRAFT_CONTENT}}", draft.content() != null ? draft.content() : "")
                .replace("{{DRAFT_DELIVERABLE}}", draft.deliverable() != null ? draft.deliverable() : "")
                .replace("{{DRAFT_ACCEPTANCE}}", draft.acceptance() != null ? draft.acceptance() : "")
                .replace("{{DRAFT_ACCEPTANCE_KIND}}",
                        draft.acceptanceKind() != null ? draft.acceptanceKind().name() : "AUTOMATED")
                .replace("{{DRAFT_PRIORITY}}", draft.priority() != null ? draft.priority() : "MEDIUM")
                .replace("{{DRAFT_SKILLS}}", skills)
                .replace("{{DRAFT_CONSTRAINTS}}", constraints)
                .replace("{{SKILL_CATALOG}}", renderSkillCatalog())
                .replace("{{TASK_CONTEXT_SNAPSHOT}}", renderSnapshotSafely(task))
                .replace("{{REQUIREMENT_PACKAGE}}", renderRequirementPackage(task));
    }

    /** 单条草案 LLM 调用（expand/regenerate 共用；不记 TASK_PLAN_LLM_CALL_START 事件，用轻量日志）。 */
    private AgentResult executeLlmForSingleDraft(PlannerAgentRef planner, String prompt, Long taskId,
                                                 String scene) {
        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(prompt)
                .context(Map.of("taskId", taskId, "subTaskScene", scene))
                .requiredCapabilities(Map.of())
                .skills(List.of())
                .build();
        long startMs = System.currentTimeMillis();
        AgentResult result = platformAgentExecutionService.executeSync(planner.id(), agentTask);
        long costMs = System.currentTimeMillis() - startMs;
        if (!result.isSuccess()) {
            throw new BizException("Planner LLM 调用失败: " + result.getErrorMessage());
        }
        log.info("单条草案 LLM 调用完成: taskId={}, scene={}, costMs={}, success={}",
                taskId, scene, costMs, result.isSuccess());
        return result;
    }

    /** regenerate 输出解析：单条 JSON 对象 → PlanDraftItem（必填四要素 + acceptanceKind 校验）。 */
    private PlanDraftItem parseSingleRegenerateOutput(Long taskId, PlannerAgentRef planner, String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new BizException("Planner LLM 返回内容为空");
        }
        String cleaned = rawOutput.trim();
        // 兼容 markdown fence 包裹
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            int fenceEnd = cleaned.lastIndexOf("```");
            if (fenceEnd >= 0) {
                cleaned = cleaned.substring(0, fenceEnd);
            }
            cleaned = cleaned.trim();
        }
        PlanDraftItem item;
        try {
            item = objectMapper.readValue(cleaned, PlanDraftItem.class);
        } catch (Exception e) {
            throw new BizException("Planner LLM 重生成输出 JSON 解析失败: " + e.getMessage()
                    + "; 原始输出摘要: " + summarize(rawOutput));
        }
        if (item == null) {
            throw new BizException("Planner LLM 未输出草案内容");
        }
        String missing = missingRequiredField(item);
        if (missing != null) {
            taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_DRAFT_FIELD_MISSING.code(),
                    AgentRole.PLANNER, planner.id(),
                    Map.of("draftSeq", 1, "field", missing,
                            "title", item.getTitle() != null ? item.getTitle() : "",
                            "rawOutputSummary", summarize(rawOutput)));
            throw new BizException("重生成结果缺少 " + missing + " 字段; 原始输出摘要: "
                    + summarize(rawOutput));
        }
        // acceptanceKind 校验（单条）：显式声明走 fail-close
        validateAcceptanceKinds(taskId, planner, List.of(item));
        validateFieldLengths(taskId, planner, List.of(item));
        return item;
    }

    /** LLM 拆解主流程（从同步 decompose 迁出，行为不变）。 */
    private void doDecompose(TaskView task) {
        Long taskId = task.id();
        PlannerAgentRef planner = plannerAgentPicker.pickForTask(taskId);
        // G-010：粒度判定提前到主流程，renderPrompt 渲染与 buildDrafts 的 COARSE 校验共用一次判定
        PlannerGranularityResolver.GranularityDecision decision = granularityDecision(task);
        String prompt = renderPrompt(task, decision);

        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(prompt)
                .context(Map.of("taskId", taskId, "scene", "planner_decompose"))
                .requiredCapabilities(Map.of())
                // G-016 契约层技能注入：与任务级 required_skills 同源（null 防御为 List.of()）
                .skills(task.requiredSkills() != null ? task.requiredSkills() : List.of())
                .build();
        taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_LLM_CALL_START.code(),
                AgentRole.PLANNER, planner.id(),
                Map.of("agentId", planner.id(), "agentName", planner.name()));
        long startMs = System.currentTimeMillis();
        AgentResult result = platformAgentExecutionService.executeSync(planner.id(), agentTask);
        long costMs = System.currentTimeMillis() - startMs;
        recordLlmCallEnd(taskId, planner, result, costMs);
        if (!result.isSuccess()) {
            throw new BizException("Planner LLM 调用失败: " + result.getErrorMessage());
        }

        List<PlanDraftItem> items = parseDraftItems(taskId, planner, result.getOutput());
        validateDependencies(items);
        List<SubTaskDraft> drafts = buildDrafts(task, items, planner, decision);
        subTaskService.saveDrafts(drafts);
        // 防御：ServiceImpl.saveBatch 的 @Transactional 边界可能导致实体 ID 未回填，
        // 从 DB 重加载保证 applyDependsOn 拿到的是持久化后的真实 ID（Snowflake 精度）。
        // 关键：必须按 buildDrafts/add 顺序（即 items 顺序）加载，不能 orderByAsc(SubTask::getId)，
        // 因为 dependsOn 序号指向“本批草案中的第 N 条”而不是“按 id 排后的第 N 条”。
        // 采用 getCreateTime asc + 同毫秒按 id asc 的二级序，与 saveBatch 顺序一致。
        List<SubTaskView> persisted = subTaskService.listDraftsInInsertOrder(
                taskId, SubTaskStatus.PENDING_PLAN_REVIEW);
        log.info("Planner 重加载草案: taskId={}, expectedCount={}, actualCount={}",
                taskId, items.size(), persisted.size());
        applyDependsOn(persisted, items);

        taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_GENERATED.code(),
                AgentRole.PLANNER, planner.id(),
                Map.of("agentId", planner.id(),
                        "agentName", planner.name(),
                        "draftCount", persisted.size(),
                        "rawOutputSummary", summarize(result.getOutput())));
        log.info("任务拆解草案生成: taskId={}, plannerAgentId={}, draftCount={}",
                taskId, planner.id(), persisted.size());
    }

    /** 记录 LLM 调用结束事件（耗时毫秒、finishReason、tokenUsage），补齐可观测缺口。 */
    private void recordLlmCallEnd(Long taskId, PlannerAgentRef planner, AgentResult result, long costMs) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("agentId", planner.id());
            payload.put("agentName", planner.name());
            payload.put("costMs", costMs);
            payload.put("success", result.isSuccess());
            payload.put("finishReason", result.getFinishReason());
            payload.put("tokenUsage", result.getTokenUsage());
            taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_LLM_CALL_END.code(),
                    AgentRole.PLANNER, planner.id(), payload);
        } catch (Exception e) {
            // timeline 记录失败不阻断拆解主流程
            log.warn("记录 task_plan_llm_call_end 失败（不阻断拆解）: taskId={}, err={}",
                    taskId, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  内部实现
    // ══════════════════════════════════════════════════════════════

    /** 加载 classpath 模板并替换占位符。G-004 增量 B：注入任务技能要求（task.required_skills，
     * 与执行侧 resolve 命中注入、审查侧核验同一清单——拆解规划须与技能规范对齐）。
     * G-011：注入需求包段（{{REQUIREMENT_PACKAGE}}，防御式读取 task.context.requirementPackage）。
     * `TD-3`：注入 {{TASK_ARCHETYPE}} 与 {{SUBTASK_COUNT_RULE}}，并按原型择一拆分维度小节。
     * `TD-6`：注入 {{TASK_CONTEXT_SNAPSHOT}}（附件 / 知识库 / 历史任务骨架，只给「有什么」）。 */
    private String renderPrompt(TaskView task, PlannerGranularityResolver.GranularityDecision decision) {
        ClassPathResource resource = new ClassPathResource(PROMPT_TEMPLATE_PATH);
        if (!resource.exists()) {
            throw new BizException("未找到拆解 Prompt 模板: " + PROMPT_TEMPLATE_PATH);
        }
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("读取拆解 Prompt 模板失败: " + e.getMessage());
        }
        TaskArchetype archetype = task.taskArchetype() != null
                ? task.taskArchetype() : TaskArchetype.DEFAULT();
        String rendered = template
                .replace("{{TASK_TITLE}}", task.title() != null ? task.title() : "")
                .replace("{{TASK_DESCRIPTION}}",
                        task.description() != null && !task.description().isBlank()
                                ? task.description() : "（无补充描述，请依据标题拆解）")
                .replace("{{TASK_ARCHETYPE}}", archetype.name())
                .replace("{{TASK_REQUIRED_SKILLS}}", renderRequiredSkills(task.requiredSkills()))
                .replace("{{SKILL_CATALOG}}", renderSkillCatalog())
                .replace("{{EXECUTOR_PROFILE}}", decision.profile())
                .replace("{{TASK_DIFFICULTY}}", TaskAgentPolicy.difficulty(task.agentPolicy()).name())
                .replace("{{GRANULARITY}}", decision.granularity().name())
                .replace("{{REQUIREMENT_PACKAGE}}", renderRequirementPackage(task))
                .replace("{{TASK_CONTEXT_SNAPSHOT}}", renderSnapshotSafely(task))
                .replace("{{SUBTASK_COUNT_RULE}}", renderSubtaskCountRule(task));
        // TD-3：六个拆分维度块全在模板内，此处只保留当前原型那一块（缺块即抛，不静默降级）
        return ArchetypeDimensionsSection.select(rendered, archetype);
    }

    /** G-011：需求包段渲染——task.context.requirementPackage 经防御式解析后五字段逐项列表；
     * 无需求包（非澄清链路任务）→ 渲染占位文案，行为等于现状。 */
    private String renderRequirementPackage(TaskView task) {
        return RequirementPackageParser.render(
                RequirementPackageParser.fromContext(task.context()));
    }

    /**
     * `TD-6`：上下文快照渲染，**任何异常只降级为空快照，绝不击穿拆解**。
     *
     * <p>渲染器内部已对三类取数各自 try/catch，这里再包一层是因为「附加价值」不该有否决权：
     * 快照取不到只是拆得糙一点，拆解失败则整件事做不了——代价不对等。</p>
     */
    private String renderSnapshotSafely(TaskView task) {
        try {
            String snapshot = contextSnapshotRenderer.render(task);
            return snapshot != null && !snapshot.isBlank()
                    ? snapshot : "- （本次无可用上下文快照）";
        } catch (Exception e) {
            log.warn("拆解期上下文快照渲染失败，已降级为空快照（不阻断拆解）: taskId={}, err={}",
                    task.id(), e.getMessage());
            return "- （上下文快照渲染失败，按无额外材料处理）";
        }
    }

    /**
     * `TD-3`：数量规则句——**门槛与数量分离**（设计 §3.3，借鉴 task-master 的四级回退链）。
     *
     * <p>本方法<b>只做渲染层</b>：{@code task.context.subtaskCount} 有人工显式传入就渲染成
     * 「恰好 N 个」，没传则沿用改造前的 3~10 区间。数量决策链路（谁该传、传多少）不在本项范围
     * ——`GRANULARITY` 仍是唯一的粒度档位来源（`G-010`）。</p>
     */
    private String renderSubtaskCountRule(TaskView task) {
        Integer pinned = pinnedSubtaskCount(task);
        if (pinned == null) {
            return "子任务数量必须在 3~10 个之间；任务简单时宁少勿滥，不要为凑数而拆。";
        }
        return "子任务数量必须恰好 " + pinned + " 个（上游已钉死数量，不得增减）；"
                + "若确有必要偏离该数量，请在对应条目的 uncertainties 中说明理由，不要自行改数量。";
    }

    /**
     * 读取人工钉死的子任务数量。
     *
     * <p>越界钳制到 {@link #MAX_DRAFT_COUNT}：提示词要求「恰好 N 个」而服务端硬上限是 10 时，
     * 若 N &gt; 10 则模型无论如何都会触发落库前拒绝——那是必然失败，不是校验。钳制并告警
     * 比让整批拆解失败更有用（真实的意图是「多拆一些」，10 已经是能落库的上限）。</p>
     */
    private Integer pinnedSubtaskCount(TaskView task) {
        Map<String, Object> context = task.context();
        if (context == null) {
            return null;
        }
        Object raw = context.get(TaskContextKeys.SUBTASK_COUNT);
        Integer value = null;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else if (raw instanceof String str && !str.isBlank()) {
            try {
                value = Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (value == null || value <= 0) {
            return null;
        }
        if (value > MAX_DRAFT_COUNT) {
            log.warn("子任务数量钉死值超服务端上限，已钳制: taskId={}, pinned={}, capped={}",
                    task.id(), value, MAX_DRAFT_COUNT);
            return MAX_DRAFT_COUNT;
        }
        return value;
    }

    /** G-010：按任务执行者画像 + 难度定位拆解粒度；白名单内 accessType 一次批量查询，避免 N+1。 */
    private PlannerGranularityResolver.GranularityDecision granularityDecision(TaskView task) {
        List<Long> executorIds = TaskAgentPolicy.executorAgentIds(task.agentPolicy());
        List<AgentAccessType> accessTypes;
        if (executorIds.isEmpty()) {
            accessTypes = List.of();
        } else {
            accessTypes = agentService.listProfilesByIds(executorIds).stream()
                    .map(AgentProfileSnapshot::accessType).toList();
        }
        return PlannerGranularityResolver.resolve(task.agentPolicy(), accessTypes);
    }

    /** 技能目录渲染：每技能一行「标签 v版本 — 描述（依赖工具: a, b）」；空目录降级文案。超 20 项截断至前 20。 */
    private String renderSkillCatalog() {
        List<SkillPackage> packages = agentSkillSpecService.listPackages();
        if (packages == null || packages.isEmpty()) {
            return "（平台暂无已登记技能包）";
        }
        List<SkillPackage> visible = packages.size() > MAX_SKILL_CATALOG_SIZE
                ? packages.subList(0, MAX_SKILL_CATALOG_SIZE)
                : packages;
        StringBuilder sb = new StringBuilder();
        for (SkillPackage pkg : visible) {
            sb.append("- ").append(pkg.name()).append(" v").append(pkg.version())
              .append(" — ").append(pkg.description());
            if (pkg.requiredTools() != null && !pkg.requiredTools().isEmpty()) {
                sb.append("（依赖工具: ").append(String.join(", ", pkg.requiredTools())).append("）");
            }
            sb.append('\n');
        }
        if (packages.size() > MAX_SKILL_CATALOG_SIZE) {
            sb.append("（技能目录共 ").append(packages.size())
              .append(" 项，仅展示前 ").append(MAX_SKILL_CATALOG_SIZE)
              .append(" 项；未展示技能不参与指派）");
        }
        return sb.toString().trim();
    }

    /** 技能要求占位符渲染：保持声明序逗号拼接；null/空 → 显式降级文案（行为零变化）。 */
    private static String renderRequiredSkills(List<String> requiredSkills) {
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            return "（任务未声明技能要求）";
        }
        return String.join(", ", requiredSkills);
    }

    /**
     * 解析 LLM 输出为草案条目：strip markdown fence 容错 + 逐条校验必填字段与数量上限。
     *
     * <p>必填校验（P1）覆盖 title / content / deliverable / acceptance 四字段，任一缺失
     * 即 fail-close：落库残缺草案（acceptance=null）会让执行侧失去验收依据、审查侧无标准
     * 可核，代价远高于拆解失败——失败已可经 republish / planById 重触发（入口既有）。
     * 与提示词 planner-decompose.md「字段全部必填」对齐，把提示词约定落到代码兜底。</p>
     */
    private List<PlanDraftItem> parseDraftItems(Long taskId, PlannerAgentRef planner, String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new BizException("Planner LLM 返回内容为空");
        }
        String cleaned = stripToJsonArray(rawOutput);
        List<PlanDraftItem> items;
        try {
            items = objectMapper.readValue(cleaned, new TypeReference<List<PlanDraftItem>>() {});
        } catch (Exception e) {
            throw new BizException("Planner LLM 输出 JSON 解析失败: " + e.getMessage()
                    + "; 原始输出摘要: " + summarize(rawOutput));
        }
        if (items == null || items.isEmpty()) {
            throw new BizException("Planner LLM 未拆解出任何子任务");
        }
        if (items.size() > MAX_DRAFT_COUNT) {
            throw new BizException("拆解结果超过数量上限 " + MAX_DRAFT_COUNT + ": 实际 " + items.size());
        }
        for (int i = 0; i < items.size(); i++) {
            PlanDraftItem item = items.get(i);
            String missing = missingRequiredField(item);
            if (missing != null) {
                // 审计先于抛出：失败原因可回溯到具体字段与原始输出（timeline 记录不阻断主流程）
                taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_DRAFT_FIELD_MISSING.code(),
                        AgentRole.PLANNER, planner.id(),
                        Map.of("draftSeq", i + 1, "field", missing,
                                "title", item.getTitle() != null ? item.getTitle() : "",
                                "rawOutputSummary", summarize(rawOutput)));
                throw new BizException("拆解结果第 " + (i + 1) + " 条缺少 " + missing
                        + " 字段; 原始输出摘要: " + summarize(rawOutput));
            }
        }
        // TD-5：验收判据按 kind 分流校验（设计 §4.2）。显式声明的 kind 与文本写法不匹配
        // → fail-close（挡在落库前）；kind 缺失回落 AUTOMATED 但文本写法不符 → WARN 放行
        // （存量零新失败，见 LOG-20261024-039 观察项与计划 §6 风险 2）
        validateAcceptanceKinds(taskId, planner, items);
        // TD-5：acceptance / deliverable 字符下限质量闸（[实测] 借鉴 expand-task.json 的
        // 字符数下限设计，纯长度判定挡「空话型子任务」）。阈值远低于存量真实样本下限
        // （2026-10-24 实测 50 条存量：acceptance 最短 39 字符 / deliverable 最短 17 字符），
        // 只拦 1~2 字符的空话桩（"a" / "d"），正常产物零误伤。
        validateFieldLengths(taskId, planner, items);
        return items;
    }

    /** TD-5：acceptance / deliverable 字符下限。阈值来自存量真实样本（见上方调用处注释）。 */
    private static final int ACCEPTANCE_MIN_LENGTH = 10;
    private static final int DELIVERABLE_MIN_LENGTH = 8;

    /**
     * TD-5：acceptance / deliverable 字符下限校验（fail-close：长度不足即整批拒绝）。
     *
     * <p>必填校验（P1）只保证字段存在（"a" 也非空），本条保证字段有实质内容——长度不足的
     * acceptance 无法支撑验收（执行侧/审查侧无标准可核），与 {@code missingRequiredField}
     * 同属落库前围栏，失败可经 republish / planById 重触发。</p>
     */
    private void validateFieldLengths(Long taskId, PlannerAgentRef planner, List<PlanDraftItem> items) {
        for (int i = 0; i < items.size(); i++) {
            PlanDraftItem item = items.get(i);
            String acceptance = item.getAcceptance() == null ? "" : item.getAcceptance().trim();
            String deliverable = item.getDeliverable() == null ? "" : item.getDeliverable().trim();
            if (acceptance.length() < ACCEPTANCE_MIN_LENGTH || deliverable.length() < DELIVERABLE_MIN_LENGTH) {
                throw new BizException("拆解结果第 " + (i + 1) + " 条验收判据/交付物过短（acceptance="
                        + acceptance.length() + " 字符 < " + ACCEPTANCE_MIN_LENGTH
                        + " 或 deliverable=" + deliverable.length() + " 字符 < " + DELIVERABLE_MIN_LENGTH
                        + "）——空话型子任务，落库前拒绝");
            }
        }
    }

    /**
     * TD-5：逐条校验 acceptanceKind ↔ acceptance 文本写法匹配。
     *
     * <p>显式声明（LLM 给了结构化字段）走 fail-close：不匹配即整批拒绝（拆解失败回退
     * PENDING 可重拆）；kind 缺失（回落 {@code AUTOMATED}）但文本写法不符走 WARN：
     * timeline 记录 hint，不阻断落库——首期以观测为主，避免大面积误伤存量与 flash 产物。</p>
     */
    private void validateAcceptanceKinds(Long taskId, PlannerAgentRef planner, List<PlanDraftItem> items) {
        for (int i = 0; i < items.size(); i++) {
            PlanDraftItem item = items.get(i);
            // ★显式声明判定必须基于原始字符串，不能用 parse() 结果：
            // parse(null/非法值) 回落 AUTOMATED（非 null），直接拿来判断会把「缺失/非法值」
            // 误判成「显式声明 AUTOMATED」→ 普通文本触发 Fail 误伤存量（LOG-20261024-040 踩坑）。
            // 只有「非空且是合法枚举」才算显式声明；缺失/非法值按未声明处理（Warn 放行）。
            String rawKind = item.getAcceptanceKind();
            AcceptanceKind declared = null;
            if (rawKind != null && !rawKind.isBlank()) {
                try {
                    declared = AcceptanceKind.valueOf(rawKind.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    // 非法值：不视为显式声明（模型乱填，按未声明 Warn 放行，避免误伤）
                }
            }
            AcceptanceKindValidator.Result result =
                    AcceptanceKindValidator.validate(declared, item.getAcceptance());
            String title = item.getTitle() != null ? item.getTitle() : "";
            String acceptance = item.getAcceptance() != null ? item.getAcceptance() : "";
            // Java 17 兼容：instanceof 模式匹配，避免 switch patterns（需 21+ preview）
            if (result instanceof AcceptanceKindValidator.Result.Fail f) {
                // ★携带 acceptance 摘要：fail-close 抛错时把原文带进错误信息，排障可直接定位
                // （2026-10-25 事故 LOG-20261024-049：只有 title 无原文，翻日志才能猜命中词）
                throw new BizException("拆解结果第 " + (i + 1) + " 条验收判据与写法不匹配: "
                        + f.reason() + "（title=" + title + "; acceptance=" + acceptance + "）");
            } else if (result instanceof AcceptanceKindValidator.Result.Warn w) {
                taskTimelineService.recordEvent(taskId, null,
                        TimelineEventCode.TASK_PLAN_ACCEPTANCE_KIND_MISMATCH.code(),
                        AgentRole.PLANNER, planner.id(),
                        Map.of("draftSeq", i + 1, "title", title,
                                "kind", w.kind(), "hint", w.hint()));
                log.warn("拆解草案 acceptance 写法与 kind 疑似错配（放行）: draftSeq={}, title={}, hint={}",
                        i + 1, title, w.hint());
            }
            // Pass：无需处理
        }
    }

    /**
     * 草案必填字段探测：title / content / deliverable / acceptance 四字段任一为 null 或
     * 空白即视为缺失（LLM 常以空串或纯空白占位，需按内容为空处理）。
     *
     * @return 首个缺失字段名；四字段齐全返回 {@code null}
     */
    private static String missingRequiredField(PlanDraftItem item) {
        if (item.getTitle() == null || item.getTitle().isBlank()) {
            return "title";
        }
        if (item.getContent() == null || item.getContent().isBlank()) {
            return "content";
        }
        if (item.getDeliverable() == null || item.getDeliverable().isBlank()) {
            return "deliverable";
        }
        if (item.getAcceptance() == null || item.getAcceptance().isBlank()) {
            return "acceptance";
        }
        return null;
    }

    /** 剥离 markdown 代码块围栏，并兜底截取首尾方括号之间的 JSON 数组。 */
    private String stripToJsonArray(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            int fenceEnd = cleaned.lastIndexOf("```");
            if (fenceEnd >= 0) {
                cleaned = cleaned.substring(0, fenceEnd);
            }
            cleaned = cleaned.trim();
        }
        if (!cleaned.startsWith("[")) {
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
        }
        return cleaned;
    }

    /** 草案实体装配：status=PENDING_PLAN_REVIEW，context 记录拆解来源审计信息。
     * G-011：uncertainties 落库（kind 降级审计）+ COARSE constraints 缺失 WARN（G-010 缺口④清偿）
     * + D5 兜底审计（openQuestions 无 UNCONFIRMED 继承 WARN）。 */
    private List<SubTaskDraft> buildDrafts(TaskView task, List<PlanDraftItem> items, PlannerAgentRef planner,
                                           PlannerGranularityResolver.GranularityDecision decision) {
        Long taskId = task.id();
        List<SubTaskDraft> drafts = new ArrayList<>(items.size());
        String generatedAt = OffsetDateTime.now().toString();
        // G-022 拆解侧前置校验：在岗 Agent 技能集合只查一次（避免逐草案 N+1）
        List<List<String>> activeAgentSkillSets = activeAgentSkillSets();
        // TD-11 准确率修正（2026-10-25 LOG-20261025-016）：先汇总任务级 uncertainties，
        // 供「任务级判据」使用——模型可能把 UNCONFIRMED 只申报在下游草案，而真正需要它的
        // 上游草案报成 TO_LOOK_UP，单条口径会漏标。
        List<UncertaintyDraft> taskLevelUncertainties = new ArrayList<>();
        for (PlanDraftItem item : items) {
            // ★2026-10-26 双重归一化修复（CI 门禁 1 回归）：此处只做**纯归一化**（kind 降级 /
            // 空白过滤 / ensureRefs），不记降级审计事件——事件由下方逐草案装配处的
            // normalizeUncertainties（带审计版）统一记，否则同一批 uncertainties 被归一化两次、
            // 非法 kind 的 task_plan_uncertainty_degraded 会被记两次（实测 2 次）。
            taskLevelUncertainties.addAll(normalizeUncertaintiesPure(resolveUncertainties(item)));
        }
        for (int idx = 0; idx < items.size(); idx++) {
            PlanDraftItem item = items.get(idx);
            List<UncertaintyDraft> uncertainties =
                    normalizeUncertainties(taskId, resolveUncertainties(item), planner);
            // G-010 缺口④清偿：COARSE 粒度 constraints 缺失记 timeline WARN（不阻断落库）
            if (decision.granularity() == PlannerGranularity.COARSE
                    && (item.getConstraints() == null || item.getConstraints().isBlank())) {
                taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_CONSTRAINTS_MISSING.code(),
                        AgentRole.PLANNER, planner.id(),
                        Map.of("draftSeq", drafts.size() + 1, "title", item.getTitle()));
            }
            List<String> keptSkills = filterRequiredSkills(taskId, item.getRequiredSkills(), planner);
            // G-022 拆解侧前置校验（告警而非阻断）：声明技能须有在岗 Agent 可承接
            auditSkillCoverage(taskId, drafts.size() + 1, item.getTitle(), keptSkills, activeAgentSkillSets, planner);
            Map<String, Object> context = new HashMap<>();
            context.put("plannerAgentId", planner.id());
            context.put("plannerAgentName", planner.name());
            context.put("planGeneratedAt", generatedAt);
            // TD-11：验收契约可满足性标注（**只标注，不改任何既有字段、不阻断落库**）。
            // 挂在此处 = 「validateAcceptanceKinds 之后、落库之前」——那时 uncertainties 已归一化、
            // dependsOn 已可解析。TD-5 的校验器物理上看不到 uncertainties（签名里就没有），
            // 所以「验收要求取回外部实证」× 「该外部事实仍未确认」这个死锁只能在这一层发现。
            // 2026-10-25 准确率修正：传入任务级 uncertainties，判据从「本条有 UNCONFIRMED」
            // 改为「任务级存在 UNCONFIRMED ∧ 本条 acceptance 命中实证型来源」。
            AcceptanceSatisfiabilityChecker.Result satisfiability =
                    checkAcceptanceSatisfiability(taskId, planner, items, idx, item, uncertainties,
                            taskLevelUncertainties);
            if (satisfiability.unmet()) {
                context.put(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS, satisfiability.preconditionRefs());
            }
            drafts.add(new SubTaskDraft(taskId, item.getTitle().trim(), item.getContent(),
                    item.getDeliverable(), item.getAcceptance(),
                    resolveSubTaskPriority(item.getPriority(), task.priority()),
                    Boolean.TRUE.equals(item.getContract()) ? 1 : 0,
                    keptSkills,
                    item.getConstraints(), SubTaskStatus.PENDING_PLAN_REVIEW, context, uncertainties,
                    // TD-3 / TD-2：LLM 未给或给了非法值一律回落 AUTOMATED（= 改造前唯一语义）
                    AcceptanceKind.parse(item.getAcceptanceKind())));
        }
        // D5 兜底审计：任务 openQuestions 非空但拆解产物无任何 UNCONFIRMED 继承 → WARN（不阻断）
        auditOpenQuestionsInheritance(task, drafts, planner);
        return drafts;
    }

    /**
     * `TD-11`：验收契约可满足性标注（**只标注，不阻断**）。
     *
     * <p>判据与取舍见 {@link AcceptanceSatisfiabilityChecker} 类注释；本方法只负责
     * 「取上游文本 → 调纯函数 → 命中则记一条 WARN 级时间线事件」。写进
     * {@code sub_task.context.acceptancePreconditions} 的动作在 {@code buildDrafts} 里
     * （那里才有 context 变量），本方法只返回结果。</p>
     *
     * <p><b>异常一律吞掉</b>：标注是纯增量观测，任何异常都不得让整批拆解失败——
     * 那是 `TD-5` 风险 2 的反面教训（校验过严会阻塞主链路）。</p>
     */
    private AcceptanceSatisfiabilityChecker.Result checkAcceptanceSatisfiability(
            Long taskId, PlannerAgentRef planner, List<PlanDraftItem> items, int idx,
            PlanDraftItem item, List<UncertaintyDraft> uncertainties,
            List<UncertaintyDraft> taskLevelUncertainties) {
        try {
            AcceptanceSatisfiabilityChecker.Result result = AcceptanceSatisfiabilityChecker.check(
                    item.getAcceptance(), uncertainties, upstreamTexts(items, item),
                    taskLevelUncertainties);
            if (result.unmet()) {
                taskTimelineService.recordEvent(taskId, null,
                        TimelineEventCode.TASK_PLAN_ACCEPTANCE_PRECONDITION_UNMET.code(),
                        AgentRole.PLANNER, planner.id(),
                        Map.of("draftSeq", idx + 1,
                                "title", item.getTitle() == null ? "" : item.getTitle(),
                                "preconditionRefs", result.preconditionRefs(),
                                "uncertaintyNotes", result.uncertaintyNotes()));
            }
            return result;
        } catch (Exception e) {
            log.warn("验收可满足性标注失败（不阻断拆解）: taskId={}, draftSeq={}, err={}",
                    taskId, idx + 1, e.getMessage());
            return AcceptanceSatisfiabilityChecker.Result.NONE;
        }
    }

    /**
     * 取该条草案 {@code dependsOn} 指向的草案标题 + 正文（供判「缺输入是否已由 DAG 表达」）。
     *
     * <p>{@code dependsOn} 存的是**本批草案的 1-based 序号**（不是 id），越界序号直接跳过——
     * 越界本身由 {@code validateDependencies} 另行 fail-close，这里不重复报错。</p>
     */
    private static List<String> upstreamTexts(List<PlanDraftItem> items, PlanDraftItem item) {
        if (item.getDependsOn() == null || item.getDependsOn().isEmpty()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (Integer seq : item.getDependsOn()) {
            if (seq == null || seq < 1 || seq > items.size()) {
                continue;
            }
            PlanDraftItem up = items.get(seq - 1);
            texts.add((up.getTitle() == null ? "" : up.getTitle()) + " "
                    + (up.getContent() == null ? "" : up.getContent()));
        }
        return texts;
    }

    /**
     * G-011：LLM uncertainties 字段（JsonNode，防御承接）→ 元素列表。
     * 缺失 / 非数组 / 转换失败 → 空列表（不阻断拆解）；数组元素级 kind 非法值
     * 由 {@link #normalizeUncertainties} 逐个降级审计。
     */
    private List<UncertaintyDraft> resolveUncertainties(PlanDraftItem item) {
        JsonNode node = item.getUncertaintiesNode();
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(node, new TypeReference<List<UncertaintyDraft>>() { });
        } catch (Exception e) {
            log.warn("拆解 uncertainties 解析失败，回落空列表（不阻断）: title={}, err={}",
                    item.getTitle(), e.getMessage());
            return List.of();
        }
    }

    /**
     * G-011 D3：kind 合法性归一（**纯函数，无副作用**）——非 ASSUMPTION / UNCONFIRMED / TO_LOOK_UP /
     * TO_DERIVE 的值降级 UNCONFIRMED（不丢弃：降级到更严语义符合 fail-close，与 G-010 幻觉标签
     * 丢弃模式差异理由见设计 D3）；note 空白条目丢弃（无信息量，消费侧无法使用）。
     *
     * <p>★2026-10-26 拆分（CI 门禁 1 回归）：归一化结果会被两处复用（任务级汇总 + 逐草案装配），
     * 若两处都调用带审计的 {@link #normalizeUncertainties}，同一批 uncertainties 的非法 kind
     * 会被记两次 {@code task_plan_uncertainty_degraded}。降级审计事件是副作用，只应在
     * 「真正落库的那一次」记——故拆出本纯版供复用方调用，带审计版只在逐草案装配处用一次。</p>
     */
    private List<UncertaintyDraft> normalizeUncertaintiesPure(List<UncertaintyDraft> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<UncertaintyDraft> kept = new ArrayList<>(raw.size());
        for (UncertaintyDraft u : raw) {
            if (u.note() == null || u.note().isBlank()) {
                continue;
            }
            String kind = u.kind();
            // TD-7：值域白名单单一来源 UncertaintyKind（四值），非法值仍降级 UNCONFIRMED
            if (!UncertaintyKind.isKnown(kind)) {
                kind = UncertaintyDraft.KIND_UNCONFIRMED;
            }
            kept.add(new UncertaintyDraft(kind, u.note()));
        }
        // TD-11：ref 补齐走 `AcceptanceSatisfiabilityChecker#ensureRefs`（全项目唯一实现，
        // 判据侧读 ref 用的也是它——两处各写一份编号逻辑必然分叉）
        return AcceptanceSatisfiabilityChecker.ensureRefs(kept);
    }

    /**
     * G-011 D3：kind 合法性归一 + 降级审计（带副作用版）——语义与 {@link #normalizeUncertaintiesPure}
     * 完全一致，额外对每个非法 kind 记一条 timeline {@code task_plan_uncertainty_degraded}。
     * 只允许在「逐草案装配」这一处调用（每批 uncertainties 只落库一次，事件只记一次）。
     */
    private List<UncertaintyDraft> normalizeUncertainties(Long taskId, List<UncertaintyDraft> raw, PlannerAgentRef planner) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<UncertaintyDraft> kept = new ArrayList<>(raw.size());
        for (UncertaintyDraft u : raw) {
            if (u.note() == null || u.note().isBlank()) {
                continue;
            }
            String kind = u.kind();
            // TD-7：值域白名单单一来源 UncertaintyKind（四值），非法值仍降级 UNCONFIRMED
            if (!UncertaintyKind.isKnown(kind)) {
                taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_UNCERTAINTY_DEGRADED.code(),
                        AgentRole.PLANNER, planner.id(),
                        Map.of("note", u.note(), "rawKind", String.valueOf(kind),
                                "degradedTo", UncertaintyDraft.KIND_UNCONFIRMED));
                kind = UncertaintyDraft.KIND_UNCONFIRMED;
            }
            kept.add(new UncertaintyDraft(kind, u.note()));
        }
        // TD-11：ref 补齐走 `AcceptanceSatisfiabilityChecker#ensureRefs`（全项目唯一实现，
        // 判据侧读 ref 用的也是它——两处各写一份编号逻辑必然分叉）
        return AcceptanceSatisfiabilityChecker.ensureRefs(kept);
    }

    /**
     * G-011 D5 兜底审计：任务需求包 openQuestions 非空，但拆解产物无任何子任务携带
     * UNCONFIRMED 继承 → timeline 记 WARN 级 {@code task_plan_uncertainty_missing}
     * （仅审计 openQuestions → UNCONFIRMED，不审计 assumptions；不阻断落库）。
     */
    private void auditOpenQuestionsInheritance(TaskView task, List<SubTaskDraft> drafts, PlannerAgentRef planner) {
        RequirementPackage pkg = RequirementPackageParser.fromContext(task.context());
        if (pkg.openQuestions().isEmpty()) {
            return;
        }
        boolean hasUnconfirmed = drafts.stream()
                .flatMap(d -> d.uncertainties() == null
                        ? java.util.stream.Stream.<UncertaintyDraft>empty() : d.uncertainties().stream())
                .anyMatch(u -> UncertaintyDraft.KIND_UNCONFIRMED.equals(u.kind()));
        if (!hasUnconfirmed) {
            taskTimelineService.recordEvent(task.id(), null, TimelineEventCode.TASK_PLAN_UNCERTAINTY_MISSING.code(),
                    AgentRole.PLANNER, planner.id(),
                    Map.of("openQuestions", pkg.openQuestions()));
        }
    }

    /**
     * G-010：子任务级技能目录过滤。仅保留目录内命中的标签（归一化后精确命中），
     * 未命中（幻觉 / 未登记）标签丢弃并记 timeline {@code task_plan_skill_filtered}；
     * 空指派返回空列表，不阻断拆解。
     */
    private List<String> filterRequiredSkills(Long taskId, List<String> assigned, PlannerAgentRef planner) {
        if (assigned == null || assigned.isEmpty()) {
            return List.of();
        }
        Set<String> known = agentSkillSpecService.listPackages().stream()
                .map(SkillPackage::name).collect(Collectors.toSet());
        List<String> kept = new ArrayList<>();
        for (String raw : assigned) {
            String normalized = SkillNormalizer.normalize(raw);
            if (normalized != null && known.contains(normalized)) {
                kept.add(normalized);
            } else {
                taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_SKILL_FILTERED.code(),
                        AgentRole.PLANNER, planner.id(),
                        Map.of("skill", raw, "reason", "未命中平台技能目录"));
            }
        }
        return kept;
    }

    /**
     * G-022 拆解侧前置校验：草案声明技能后，校验当前在岗 Agent 中是否存在可承接者
     * —— {@code agent.skills ⊇ required_skills}（AND 语义，走 {@link SkillNormalizer#matches}，
     * 与选人门 {@code AgentSelector} 同一匹配器，防规则漂移）。
     *
     * <p>无承接者 ⇒ 记 timeline {@code task_plan_skill_unmatched}。<b>告警而非阻断</b>：
     * ① Agent 可能在拆解后被注册 / 重注册（引用边回填在岗技能）；② 阻断会让整批拆解失败，
     * 损失远大于「保留草案 + 可观测告警」；③ 与 {@link #filterRequiredSkills} 的「有疑问不阻断」口径一致。
     * 本校验消除的正是「静默等待」——原状态下子任务永久 PENDING，仅事后由
     * {@code SubTaskPendingOrphanTask} 记 {@code sub_task_no_candidate} 才在时间线可见。</p>
     */
    private void auditSkillCoverage(Long taskId, int draftSeq, String title, List<String> requiredSkills,
                                    List<List<String>> activeAgentSkillSets, PlannerAgentRef planner) {
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            return;
        }
        boolean covered = activeAgentSkillSets.stream()
                .anyMatch(agentSkills -> SkillNormalizer.matches(agentSkills, requiredSkills));
        if (covered) {
            return;
        }
        taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_SKILL_UNMATCHED.code(),
                AgentRole.PLANNER, planner.id(),
                Map.of("draftSeq", draftSeq,
                        "title", title != null ? title : "",
                        "requiredSkills", requiredSkills,
                        "activeAgentCount", activeAgentSkillSets.size()));
    }

    /** 在岗 Agent 的技能标签集合（G-022 承接者校验专用；一次查询避免逐草案 N+1）。
     * 经 {@link AgentService} 投影方法获取，不 import agent 实体（§7.2 端口反转）。 */
    private List<List<String>> activeAgentSkillSets() {
        List<List<String>> sets = agentService.listActiveSkillSets();
        return sets != null ? sets : List.of();
    }

    private String normalizePriority(String priority) {
        if (priority == null) {
            return "MEDIUM";
        }
        String upper = priority.trim().toUpperCase();
        return VALID_PRIORITIES.contains(upper) ? upper : "MEDIUM";
    }

    /**
     * 子任务优先级解析（N-006，C4-S1 优先级继承）：LLM 显式合法值优先；
     * 未给 / 空白 / 非法 → 继承 task.priority（task 缺失时回落 MEDIUM）。
     */
    private String resolveSubTaskPriority(String itemPriority, String taskPriority) {
        if (itemPriority != null && !itemPriority.isBlank()
                && VALID_PRIORITIES.contains(itemPriority.trim().toUpperCase())) {
            return itemPriority.trim().toUpperCase();
        }
        return TaskPriority.normalize(taskPriority);
    }

    /**
     * 依赖校验：序号越界/自引用即拒，再用 Kahn 拓扑排序做环检测，
     * 成环整批拒绝（抛 BizException → 拆解失败回退 PENDING 可重拆）。
     *
     * <p>序号为 1-based（指向同批草案中的第 N 条）；dependsOn 为 null/空视为无依赖。</p>
     */
    @Override
    public void validateDependencies(List<PlanDraftItem> items) {
        int n = items.size();
        // 1) 逐条范围校验
        for (int i = 0; i < n; i++) {
            List<Integer> deps = items.get(i).getDependsOn();
            if (deps == null) {
                continue;
            }
            for (Integer dep : deps) {
                if (dep == null || dep < 1 || dep > n) {
                    throw new BizException("拆解结果第 " + (i + 1) + " 条依赖序号非法: " + dep
                            + "（合法范围 1~" + n + "）");
                }
                if (dep == i + 1) {
                    throw new BizException("拆解结果第 " + (i + 1) + " 条不得依赖自身");
                }
            }
        }
        // 2) Kahn 拓扑排序环检测（入度法：能全部出队 = 无环）
        int[] inDegree = new int[n];
        List<List<Integer>> adjacency = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacency.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            List<Integer> deps = items.get(i).getDependsOn();
            if (deps == null) {
                continue;
            }
            for (Integer dep : deps) {
                adjacency.get(dep - 1).add(i); // 前置 → 后继
                inDegree[i]++;
            }
        }
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0) {
                queue.add(i);
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            int node = queue.poll();
            visited++;
            for (int next : adjacency.get(node)) {
                if (--inDegree[next] == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited < n) {
            throw new BizException("拆解结果存在循环依赖，整批拒绝；请重新触发拆解");
        }
    }

    /**
     * 序号→真实 id 映射回写：saveBatch 后草案 id 已由 assign_id 预填，
     * 把 dependsOn 序号换成同批草案的真实 sub_task id 写入 depends_on 列，
     * 并同步回填实体字段（返回给调用方的草案列表携带依赖信息）。
     *
     * <p>防御门门：drafts.size() 必须与 items.size() 一致，否则可能重加载顺序与
     * items 顺序错位（依赖序号拿错 id），甚至漏掉某些 draft；该不变量一旦破坏，
     * 后续 ready 守卫会把有依赖节点误判为就绪，必须报错并清表回退，不能静默写错位依赖。</p>
     */
    private void applyDependsOn(List<SubTaskView> drafts, List<PlanDraftItem> items) {
        if (drafts.size() != items.size()) {
            // 详细记下两者映射，避免后续排错看不到现场
            StringBuilder sb = new StringBuilder();
            sb.append("applyDependsOn 计数不匹配: drafts=").append(drafts.size())
              .append(" items=").append(items.size()).append("; draftsIds=");
            for (int k = 0; k < drafts.size(); k++) {
                if (k > 0) sb.append(',');
                sb.append(drafts.get(k).id());
            }
            log.error(sb.toString());
            throw new BizException("拆解草案重加载数量与 LLM 输出不一致: drafts=" + drafts.size()
                    + " items=" + items.size() + "；需取消现有草案后重新拆解");
        }
        for (int i = 0; i < items.size(); i++) {
            List<Integer> deps = items.get(i).getDependsOn();
            if (deps == null || deps.isEmpty()) {
                continue;
            }
            List<Long> depIds = new ArrayList<>(deps.size());
            for (Integer dep : deps) {
                Long depId = drafts.get(dep - 1).id();
                if (depId == null) {
                    throw new BizException("拆解结果第 " + (i + 1) + " 条依赖指向的草案 id 为空"
                            + "（序号=" + dep + "）；重加载可能漏取，请重试拆解");
                }
                depIds.add(depId);
            }
            // §6.100 幽灵依赖防御：写入前校验所有依赖 ID 真实存在于 sub_task 表。
            // 历史出现过依赖回写引用“未落库 ID”（内存预分配/重加载错位），
            // 静默写库后 isReady 对不存在依赖恒判未就绪，PENDING 子任务永久卡死。
            // 注意：depIds 允许重复（LLM 可输出 [2,2,3] 重复引用同一前置），须去重后比对。
            List<Long> distinctDeps = depIds.stream().distinct().toList();
            List<SubTaskView> existingDeps = subTaskService.listViewsByIds(distinctDeps);
            if (existingDeps.size() != distinctDeps.size()) {
                Set<Long> found = existingDeps.stream()
                        .map(SubTaskView::id).collect(Collectors.toSet());
                List<Long> missing = distinctDeps.stream()
                        .filter(depId -> !found.contains(depId)).toList();
                log.error("applyDependsOn 依赖存在性校验失败: draftSeq={}, draftId={}, missing={}",
                        (i + 1), drafts.get(i).id(), missing);
                throw new BizException("拆解结果第 " + (i + 1) + " 条依赖指向不存在的草案 ID: "
                        + missing + "；需取消现有草案后重新拆解");
            }
            SubTaskView draft = drafts.get(i);
            if (draft.id() == null) {
                throw new BizException("拆解结果第 " + (i + 1) + " 条自身 id 为空；重加载可能漏取，请重试拆解");
            }
            log.info("applyDependsOn: taskId={}, draftSeq={}, draftId={}, seqDeps={}, realDeps={}",
                    draft.taskId(), (i + 1), draft.id(), deps, depIds);
            subTaskService.updateDependsOn(draft.id(), depIds);
        }
    }

    /** 失败回退：仅当 Task 仍处 PLANNING 时回退 PENDING（避免覆盖并发确认结果）。 */
    private void rollbackToPending(Long taskId) {
        try {
            taskService.casStatus(taskId, TaskStatus.PLANNING, TaskStatus.PENDING);
        } catch (Exception e) {
            log.error("任务拆解失败后回退 PENDING 异常: taskId={}", taskId, e);
        }
    }

    private String summarize(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= RAW_OUTPUT_SUMMARY_LIMIT
                ? trimmed : trimmed.substring(0, RAW_OUTPUT_SUMMARY_LIMIT) + "...";
    }
}
