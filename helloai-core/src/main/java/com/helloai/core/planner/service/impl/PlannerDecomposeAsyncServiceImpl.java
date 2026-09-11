package com.helloai.core.planner.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskPriority;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.SkillNormalizer;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.skill.SkillPackage;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.policy.PlannerGranularity;
import com.helloai.core.planner.policy.PlannerGranularityResolver;
import com.helloai.core.planner.policy.RequirementPackage;
import com.helloai.core.planner.policy.RequirementPackageParser;
import com.helloai.core.planner.service.PlannerAnalysisService.PlanDraftItem;
import com.helloai.core.planner.service.PlannerDecomposeAsyncService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.entity.Uncertainty;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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

    private static final Set<String> VALID_PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final TaskService taskService;
    private final SubTaskService subTaskService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final TaskTimelineService taskTimelineService;
    private final AgentService agentService;
    private final AgentSkillSpecService agentSkillSpecService;
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
        Task task = taskService.getById(taskId);
        if (task == null || task.getStatus() != TaskStatus.PLANNING) {
            log.info("拆解异步执行跳过（任务不存在或已离开 PLANNING）: taskId={}, status={}",
                    taskId, task != null ? task.getStatus() : null);
            return;
        }
        try {
            doDecompose(task);
        } catch (Exception e) {
            rollbackToPending(taskId);
            taskTimelineService.recordEvent(taskId, null, "task_plan_failed",
                    AgentRole.PLANNER, null,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            log.error("任务拆解失败，已回退 PENDING: taskId={}", taskId, e);
        }
    }

    /** LLM 拆解主流程（从同步 decompose 迁出，行为不变）。 */
    private void doDecompose(Task task) {
        Long taskId = task.getId();
        Agent planner = plannerAgentPicker.pickForTask(taskId);
        // G-010：粒度判定提前到主流程，renderPrompt 渲染与 buildDrafts 的 COARSE 校验共用一次判定
        PlannerGranularityResolver.GranularityDecision decision = granularityDecision(task);
        String prompt = renderPrompt(task, decision);

        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(prompt)
                .context(Map.of("taskId", taskId, "scene", "planner_decompose"))
                .requiredCapabilities(Map.of())
                .build();
        taskTimelineService.recordEvent(taskId, null, "task_plan_llm_call_start",
                AgentRole.PLANNER, planner.getId(),
                Map.of("agentId", planner.getId(), "agentName", planner.getName()));
        long startMs = System.currentTimeMillis();
        AgentResult result = platformAgentExecutionService.executeSync(planner, agentTask);
        long costMs = System.currentTimeMillis() - startMs;
        recordLlmCallEnd(taskId, planner, result, costMs);
        if (!result.isSuccess()) {
            throw new BizException("Planner LLM 调用失败: " + result.getErrorMessage());
        }

        List<PlanDraftItem> items = parseDraftItems(taskId, planner, result.getOutput());
        validateDependencies(items);
        List<SubTask> drafts = buildDrafts(task, items, planner, decision);
        subTaskService.saveBatch(drafts);
        // 防御：ServiceImpl.saveBatch 的 @Transactional 边界可能导致实体 ID 未回填，
        // 从 DB 重加载保证 applyDependsOn 拿到的是持久化后的真实 ID（Snowflake 精度）。
        // 关键：必须按 buildDrafts/add 顺序（即 items 顺序）加载，不能 orderByAsc(SubTask::getId)，
        // 因为 dependsOn 序号指向“本批草案中的第 N 条”而不是“按 id 排后的第 N 条”。
        // 采用 getCreateTime asc + 同毫秒按 id asc 的二级序，与 saveBatch 顺序一致。
        drafts = subTaskService.list(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getTaskId, taskId)
                .eq(SubTask::getStatus, SubTaskStatus.PENDING_PLAN_REVIEW)
                .orderByAsc(SubTask::getCreateTime, SubTask::getId));
        log.info("Planner 重加载草案: taskId={}, expectedCount={}, actualCount={}",
                taskId, items.size(), drafts.size());
        applyDependsOn(drafts, items);

        taskTimelineService.recordEvent(taskId, null, "task_plan_generated",
                AgentRole.PLANNER, planner.getId(),
                Map.of("agentId", planner.getId(),
                        "agentName", planner.getName(),
                        "draftCount", drafts.size(),
                        "rawOutputSummary", summarize(result.getOutput())));
        log.info("任务拆解草案生成: taskId={}, plannerAgentId={}, draftCount={}",
                taskId, planner.getId(), drafts.size());
    }

    /** 记录 LLM 调用结束事件（耗时毫秒、finishReason、tokenUsage），补齐可观测缺口。 */
    private void recordLlmCallEnd(Long taskId, Agent planner, AgentResult result, long costMs) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("agentId", planner.getId());
            payload.put("agentName", planner.getName());
            payload.put("costMs", costMs);
            payload.put("success", result.isSuccess());
            payload.put("finishReason", result.getFinishReason());
            payload.put("tokenUsage", result.getTokenUsage());
            taskTimelineService.recordEvent(taskId, null, "task_plan_llm_call_end",
                    AgentRole.PLANNER, planner.getId(), payload);
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
     * G-011：注入需求包段（{{REQUIREMENT_PACKAGE}}，防御式读取 task.context.requirementPackage）。 */
    private String renderPrompt(Task task, PlannerGranularityResolver.GranularityDecision decision) {
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
        return template
                .replace("{{TASK_TITLE}}", task.getTitle() != null ? task.getTitle() : "")
                .replace("{{TASK_DESCRIPTION}}",
                        task.getDescription() != null && !task.getDescription().isBlank()
                                ? task.getDescription() : "（无补充描述，请依据标题拆解）")
                .replace("{{TASK_REQUIRED_SKILLS}}", renderRequiredSkills(task.getRequiredSkills()))
                .replace("{{SKILL_CATALOG}}", renderSkillCatalog())
                .replace("{{EXECUTOR_PROFILE}}", decision.profile())
                .replace("{{TASK_DIFFICULTY}}", TaskAgentPolicy.difficulty(task.getAgentPolicy()).name())
                .replace("{{GRANULARITY}}", decision.granularity().name())
                .replace("{{REQUIREMENT_PACKAGE}}", renderRequirementPackage(task));
    }

    /** G-011：需求包段渲染——task.context.requirementPackage 经防御式解析后五字段逐项列表；
     * 无需求包（非澄清链路任务）→ 渲染占位文案，行为等于现状。 */
    private String renderRequirementPackage(Task task) {
        return RequirementPackageParser.render(
                RequirementPackageParser.fromContext(task.getContext()));
    }

    /** G-010：按任务执行者画像 + 难度定位拆解粒度；白名单内 accessType 一次批量查询，避免 N+1。 */
    private PlannerGranularityResolver.GranularityDecision granularityDecision(Task task) {
        List<Long> executorIds = TaskAgentPolicy.executorAgentIds(task.getAgentPolicy());
        List<AgentAccessType> accessTypes;
        if (executorIds.isEmpty()) {
            accessTypes = List.of();
        } else {
            accessTypes = agentService.listByIds(executorIds).stream()
                    .map(Agent::getAccessType).toList();
        }
        return PlannerGranularityResolver.resolve(task.getAgentPolicy(), accessTypes);
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
    private List<PlanDraftItem> parseDraftItems(Long taskId, Agent planner, String rawOutput) {
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
                taskTimelineService.recordEvent(taskId, null, "task_plan_draft_field_missing",
                        AgentRole.PLANNER, planner.getId(),
                        Map.of("draftSeq", i + 1, "field", missing,
                                "title", item.getTitle() != null ? item.getTitle() : "",
                                "rawOutputSummary", summarize(rawOutput)));
                throw new BizException("拆解结果第 " + (i + 1) + " 条缺少 " + missing
                        + " 字段; 原始输出摘要: " + summarize(rawOutput));
            }
        }
        return items;
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
    private List<SubTask> buildDrafts(Task task, List<PlanDraftItem> items, Agent planner,
                                      PlannerGranularityResolver.GranularityDecision decision) {
        Long taskId = task.getId();
        List<SubTask> drafts = new ArrayList<>(items.size());
        String generatedAt = OffsetDateTime.now().toString();
        for (PlanDraftItem item : items) {
            SubTask draft = new SubTask();
            draft.setTaskId(taskId);
            draft.setTitle(item.getTitle().trim());
            draft.setContent(item.getContent());
            draft.setDeliverable(item.getDeliverable());
            draft.setAcceptance(item.getAcceptance());
            // 优先级继承（N-006，C4-S1）：LLM 显式合法值优先；未给/非法 → 继承 task.priority
            draft.setPriority(resolveSubTaskPriority(item.getPriority(), task.getPriority()));
            // 契约先行拆解（Phase 2）：contract=true → is_contract=1；
            // null/缺省/非法值一律按普通子任务（0）降级，不阻断拆解
            draft.setIsContract(Boolean.TRUE.equals(item.getContract()) ? 1 : 0);
            // G-010 能力感知：子任务级技能指派经目录过滤；constraints 直接落库
            draft.setRequiredSkills(filterRequiredSkills(taskId, item.getRequiredSkills(), planner));
            draft.setConstraints(item.getConstraints());
            // G-011：uncertainties 落库（kind 非法值降级 UNCONFIRMED + 审计，非法形态回落空列表）
            draft.setUncertainties(normalizeUncertainties(taskId, resolveUncertainties(item), planner));
            // G-010 缺口④清偿：COARSE 粒度 constraints 缺失记 timeline WARN（不阻断落库）
            if (decision.granularity() == PlannerGranularity.COARSE
                    && (item.getConstraints() == null || item.getConstraints().isBlank())) {
                taskTimelineService.recordEvent(taskId, null, "task_plan_constraints_missing",
                        AgentRole.PLANNER, planner.getId(),
                        Map.of("draftSeq", drafts.size() + 1, "title", item.getTitle()));
            }
            draft.setStatus(SubTaskStatus.PENDING_PLAN_REVIEW);
            Map<String, Object> context = new HashMap<>();
            context.put("plannerAgentId", planner.getId());
            context.put("plannerAgentName", planner.getName());
            context.put("planGeneratedAt", generatedAt);
            draft.setContext(context);
            drafts.add(draft);
        }
        // D5 兜底审计：任务 openQuestions 非空但拆解产物无任何 UNCONFIRMED 继承 → WARN（不阻断）
        auditOpenQuestionsInheritance(task, drafts, planner);
        return drafts;
    }

    /**
     * G-011：LLM uncertainties 字段（JsonNode，防御承接）→ 元素列表。
     * 缺失 / 非数组 / 转换失败 → 空列表（不阻断拆解）；数组元素级 kind 非法值
     * 由 {@link #normalizeUncertainties} 逐个降级审计。
     */
    private List<Uncertainty> resolveUncertainties(PlanDraftItem item) {
        JsonNode node = item.getUncertaintiesNode();
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(node, new TypeReference<List<Uncertainty>>() { });
        } catch (Exception e) {
            log.warn("拆解 uncertainties 解析失败，回落空列表（不阻断）: title={}, err={}",
                    item.getTitle(), e.getMessage());
            return List.of();
        }
    }

    /**
     * G-011 D3：kind 合法性归一——非 ASSUMPTION / UNCONFIRMED 的值降级 UNCONFIRMED 并记
     * timeline {@code task_plan_uncertainty_degraded}（不丢弃：降级到更严语义符合 fail-close，
     * 与 G-010 幻觉标签丢弃模式差异理由见设计 D3）；note 空白条目丢弃（无信息量，消费侧无法使用）。
     */
    private List<Uncertainty> normalizeUncertainties(Long taskId, List<Uncertainty> raw, Agent planner) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Uncertainty> kept = new ArrayList<>(raw.size());
        for (Uncertainty u : raw) {
            if (u.getNote() == null || u.getNote().isBlank()) {
                continue;
            }
            if (!Uncertainty.KIND_ASSUMPTION.equals(u.getKind())
                    && !Uncertainty.KIND_UNCONFIRMED.equals(u.getKind())) {
                taskTimelineService.recordEvent(taskId, null, "task_plan_uncertainty_degraded",
                        AgentRole.PLANNER, planner.getId(),
                        Map.of("note", u.getNote(), "rawKind", String.valueOf(u.getKind()),
                                "degradedTo", Uncertainty.KIND_UNCONFIRMED));
                u.setKind(Uncertainty.KIND_UNCONFIRMED);
            }
            kept.add(u);
        }
        return kept;
    }

    /**
     * G-011 D5 兜底审计：任务需求包 openQuestions 非空，但拆解产物无任何子任务携带
     * UNCONFIRMED 继承 → timeline 记 WARN 级 {@code task_plan_uncertainty_missing}
     * （仅审计 openQuestions → UNCONFIRMED，不审计 assumptions；不阻断落库）。
     */
    private void auditOpenQuestionsInheritance(Task task, List<SubTask> drafts, Agent planner) {
        RequirementPackage pkg = RequirementPackageParser.fromContext(task.getContext());
        if (pkg.openQuestions().isEmpty()) {
            return;
        }
        boolean hasUnconfirmed = drafts.stream()
                .flatMap(d -> d.getUncertainties() == null
                        ? java.util.stream.Stream.<Uncertainty>empty() : d.getUncertainties().stream())
                .anyMatch(u -> Uncertainty.KIND_UNCONFIRMED.equals(u.getKind()));
        if (!hasUnconfirmed) {
            taskTimelineService.recordEvent(task.getId(), null, "task_plan_uncertainty_missing",
                    AgentRole.PLANNER, planner.getId(),
                    Map.of("openQuestions", pkg.openQuestions()));
        }
    }

    /**
     * G-010：子任务级技能目录过滤。仅保留目录内命中的标签（归一化后精确命中），
     * 未命中（幻觉 / 未登记）标签丢弃并记 timeline {@code task_plan_skill_filtered}；
     * 空指派返回空列表，不阻断拆解。
     */
    private List<String> filterRequiredSkills(Long taskId, List<String> assigned, Agent planner) {
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
                taskTimelineService.recordEvent(taskId, null, "task_plan_skill_filtered",
                        AgentRole.PLANNER, planner.getId(),
                        Map.of("skill", raw, "reason", "未命中平台技能目录"));
            }
        }
        return kept;
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
    private void applyDependsOn(List<SubTask> drafts, List<PlanDraftItem> items) {
        if (drafts.size() != items.size()) {
            // 详细记下两者映射，避免后续排错看不到现场
            StringBuilder sb = new StringBuilder();
            sb.append("applyDependsOn 计数不匹配: drafts=").append(drafts.size())
              .append(" items=").append(items.size()).append("; draftsIds=");
            for (int k = 0; k < drafts.size(); k++) {
                if (k > 0) sb.append(',');
                sb.append(drafts.get(k).getId());
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
                Long depId = drafts.get(dep - 1).getId();
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
            List<SubTask> existingDeps = subTaskService.listByIds(distinctDeps);
            if (existingDeps.size() != distinctDeps.size()) {
                Set<Long> found = existingDeps.stream()
                        .map(SubTask::getId).collect(Collectors.toSet());
                List<Long> missing = distinctDeps.stream()
                        .filter(depId -> !found.contains(depId)).toList();
                log.error("applyDependsOn 依赖存在性校验失败: draftSeq={}, draftId={}, missing={}",
                        (i + 1), drafts.get(i).getId(), missing);
                throw new BizException("拆解结果第 " + (i + 1) + " 条依赖指向不存在的草案 ID: "
                        + missing + "；需取消现有草案后重新拆解");
            }
            SubTask draft = drafts.get(i);
            if (draft.getId() == null) {
                throw new BizException("拆解结果第 " + (i + 1) + " 条自身 id 为空；重加载可能漏取，请重试拆解");
            }
            log.info("applyDependsOn: taskId={}, draftSeq={}, draftId={}, seqDeps={}, realDeps={}",
                    draft.getTaskId(), (i + 1), draft.getId(), deps, depIds);
            subTaskService.updateDependsOn(draft.getId(), depIds);
            draft.setDependsOn(depIds);
        }
    }

    /** 失败回退：仅当 Task 仍处 PLANNING 时回退 PENDING（避免覆盖并发确认结果）。 */
    private void rollbackToPending(Long taskId) {
        try {
            taskService.lambdaUpdate()
                    .eq(Task::getId, taskId)
                    .eq(Task::getStatus, TaskStatus.PLANNING)
                    .set(Task::getStatus, TaskStatus.PENDING)
                    .update();
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
