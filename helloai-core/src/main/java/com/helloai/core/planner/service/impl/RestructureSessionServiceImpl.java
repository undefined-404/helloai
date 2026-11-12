package com.helloai.core.planner.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.util.ExecutionEnv;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.port.PlanDraftView;
import com.helloai.core.planner.service.PlanDraftService;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.planner.service.RestructureSessionService;
import com.helloai.core.shared.util.CurrentUser;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskDerivationService;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 执行期重构会话实现（`ADR-008` §16 四段上下文装配）。
 *
 * <p><b>四段缺一不可</b>：没有「失败轨迹」新方案会重复踩同一个坑；没有「下游期待」
 * 拆出来的东西自己跑通却满足不了下游。两段都只能从 task 域现有数据派生，不额外调模型。</p>
 *
 * <p><b>另有第五段「执行环境声明」（2026-11-12，与四段正交，不属于 ADR §16 的业务上下文）</b>：
 * 从父任务 X 的 {@code task.context.executionEnv} 原样读出。不加这一段的话，重构会话里的
 * 模型和之前拆解时一样不知道仓库在哪，只能把「仓库中」当已知事实写进终稿，接着由派生任务 Y
 * 再传下去——`2026-11-12 09:35` 那次「缺仓库检出 / schema / DB 连接」的人工介入就是这么绕回来的。
 * 环境是**任务级**的（Y 是 X 的细分，跑在同一台机器同一个仓库），故不在此处追问，只做呈现。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestructureSessionServiceImpl implements RestructureSessionService {

    private static final int TITLE_LIMIT = 100;
    private static final int UPSTREAM_MAX_CHARS = 4000;
    private static final int DOWNSTREAM_MAX_CHARS = 2000;
    private static final String ROLE_USER = "user";

    /** 未声明执行环境时的显式标记（与拆解提示词的兜底文案同语义：不说就是没有，不是「随便」）。 */
    private static final String ENV_UNDECLARED =
            "（未声明执行环境：仓库 / 运行时 / 数据源均未提供）";

    private final SubTaskService subTaskService;
    private final TaskService taskService;
    private final TaskDerivationService taskDerivationService;
    private final RequirementConversationService conversationService;
    private final RequirementMessageService messageService;
    private final PlanDraftService planDraftService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long startSession(Long subTaskId, Long plannerAgentId) {
        if (subTaskId == null) {
            throw new BizException(400, "子任务 id 不能为空");
        }
        SubTaskView subTask = subTaskService.getView(subTaskId);
        if (subTask == null) {
            throw new BizException(400, "子任务不存在: " + subTaskId);
        }
        // 判据下沉到 task 域（TaskDerivationService#assertDerivable）：AWAITING_DERIVED 不能
        // 一律拒——它只是派生结果的投影，派生任务被物理删除后投影已失实。按「是否还有活跃
        // 派生」这个事实判定：有 ⇒ 400 告知派生任务 id；无 ⇒ 自愈回 RESTRUCTURING 后放行。
        // planner → task 是顺向依赖，直接注入合规，不需要端口反转。
        taskDerivationService.assertDerivable(subTaskId);
        RequirementConversation existing = conversationService.findActiveBySourceSubTask(subTaskId);
        if (existing != null) {
            return existing.getId();
        }
        List<SubTaskView> siblings = subTaskService.listViewsByTaskId(subTask.taskId());
        String seed = buildSeed(subTask, siblings, executionEnvBlock(subTask.taskId()));

        String title = "重构派生：" + (subTask.title() == null ? "" : subTask.title());
        RequirementConversation conversation = new RequirementConversation();
        conversation.setTitle(title.length() <= TITLE_LIMIT ? title : title.substring(0, TITLE_LIMIT));
        conversation.setStatus(RequirementClarifyService.STATUS_ACTIVE);
        conversation.setRoundCount(0);
        conversation.setPlannerAgentId(plannerAgentId);
        conversation.setMode(RequirementClarifyService.MODE_CHAT);
        conversation.setKind(RequirementClarifyService.KIND_REQUIREMENT);
        conversation.setOwnerId(CurrentUser.userId());
        conversation.setTaskId(subTask.taskId());
        conversation.setSourceSubTaskId(subTaskId);
        conversationService.save(conversation);
        messageService.addMessage(conversation.getId(), ROLE_USER, seed);
        log.info("重构派生会话已创建: subTaskId={}, conversationId={}, title={}",
                subTaskId, conversation.getId(), conversation.getTitle());
        return conversation.getId();
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>为什么草案也要开会话，而不是继续「直接派生」</b>：派生任务 Y 一落地就自拆，
     * 拆解提示词能看到的执行前提只有 {@code task.context.executionEnv}。草案期直接派生时
     * 没有任何环节问过用户「仓库在哪、本机有什么运行时、数据源是哪种」，于是 Y 拆出来的
     * 子任务把「仓库中」当已知事实写进要求，执行侧只能人工介入。开会话是为了在
     * <b>建任务之前</b>把这几件事问清楚，并把答案写进终稿。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long startDraftSession(Long parentTaskId, String draftCode, Long plannerAgentId) {
        if (parentTaskId == null || draftCode == null || draftCode.isBlank()) {
            throw new BizException(400, "草案派生会话参数不完整: parentTaskId=" + parentTaskId
                    + ", draftCode=" + draftCode);
        }
        TaskView task = taskService.getView(parentTaskId);
        if (task == null) {
            throw new BizException(400, "草案所属任务不存在: " + parentTaskId);
        }
        // 与 PlannerAnalysisService#deriveFrom 同一判据：草案只在待确认窗口里可派生。
        // 状态不对时直接拒，避免「会话开出来了但终稿建不出 Y」的半成品。
        if (task.status() != TaskStatus.AWAITING_PLAN_CONFIRM) {
            throw new BizException(400, "只有草案待确认状态可拆出去: taskId=" + parentTaskId
                    + ", status=" + task.status());
        }
        PlanDraftView draft = planDraftService.getByCode(parentTaskId, draftCode);
        if (draft == null) {
            throw new BizException(400, "草案定位失败（可能已确认/已作废/非本任务）: taskId="
                    + parentTaskId + ", code=" + draftCode);
        }
        // 判据前置：该草案已有活跃派生时直接拒，别让用户聊完一轮才在定稿撞
        // uk_task_parent_draft_active。查的是「事实」（派生行 deleted=0 且未取消），
        // 派生任务被删掉后查不到 ⇒ 允许重新拆（与执行期 assertDerivable 同一套语义）。
        TaskView activeDerivation = taskDerivationService.findActivePlanDerivation(parentTaskId, draftCode);
        if (activeDerivation != null) {
            throw new BizException(400, "该草案已拆出去过（派生任务 #" + activeDerivation.id()
                    + "），如需重新拆请先删除或作废该派生任务");
        }
        RequirementConversation existing = conversationService.findActiveBySourceDraft(parentTaskId, draftCode);
        if (existing != null) {
            return existing.getId();
        }
        List<PlanDraftView> siblings = planDraftService.listByTask(parentTaskId);
        String seed = buildDraftSeed(draft, siblings, executionEnvBlock(parentTaskId));

        String title = "草案派生：" + (draft.title() == null ? "" : draft.title());
        RequirementConversation conversation = new RequirementConversation();
        conversation.setTitle(title.length() <= TITLE_LIMIT ? title : title.substring(0, TITLE_LIMIT));
        conversation.setStatus(RequirementClarifyService.STATUS_ACTIVE);
        conversation.setRoundCount(0);
        conversation.setPlannerAgentId(plannerAgentId);
        conversation.setMode(RequirementClarifyService.MODE_CHAT);
        conversation.setKind(RequirementClarifyService.KIND_REQUIREMENT);
        conversation.setOwnerId(CurrentUser.userId());
        // 会话挂在父任务下（与执行期重构一致）：终稿建出的是派生任务 Y，回填 task_id 会把它指错
        conversation.setTaskId(parentTaskId);
        conversation.setSourceDraftCode(draftCode);
        conversationService.save(conversation);
        messageService.addMessage(conversation.getId(), ROLE_USER, seed);
        log.info("草案派生会话已创建: parentTaskId={}, draftCode={}, conversationId={}",
                parentTaskId, draftCode, conversation.getId());
        return conversation.getId();
    }

    /**
     * 草案期种子（`ADR-008` §5）。
     *
     * <p>与执行期同构，但第二段没有「失败轨迹」可写（草案还没跑过），换成「同方案里
     * 谁在我上游 / 谁在我下游」——派生出去的任务要独立跑通，不能只满足自己，还得让
     * 下游拿得到东西。第五段是本次改动的目的：把执行前提从「隐含假设」变成「问过」。</p>
     */
    private static String buildDraftSeed(PlanDraftView draft, List<PlanDraftView> siblings,
                                         String executionEnvBlock) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("【草案派生上下文】以下是一条待确认草案的完整上下文与它在方案中的位置，")
                .append("请先确认执行前提，再讨论并产出需求终稿。\n\n");

        sb.append("## ① 草案原要求\n");
        appendField(sb, "标题", draft.title());
        appendField(sb, "位置编号", draft.code());
        appendField(sb, "要求内容", draft.content());
        appendField(sb, "交付物", draft.deliverable());
        appendField(sb, "验收标准", draft.acceptance());
        appendField(sb, "执行约束", draft.constraints());
        if (draft.requiredSkills() != null && !draft.requiredSkills().isEmpty()) {
            appendField(sb, "产出型技能", String.join(", ", draft.requiredSkills()));
        }

        sb.append("\n## ② 上游草案（本草案依赖谁）\n");
        boolean anyUpstream = false;
        if (draft.dependsOnCodes() != null) {
            for (String code : draft.dependsOnCodes()) {
                PlanDraftView up = findByCode(siblings, code);
                if (up == null) {
                    continue;
                }
                anyUpstream = true;
                sb.append("### ").append(up.title()).append("（").append(code).append("）\n");
                appendField(sb, "上游要求", truncate(up.content(), DOWNSTREAM_MAX_CHARS));
                appendField(sb, "上游验收", truncate(up.acceptance(), DOWNSTREAM_MAX_CHARS));
                sb.append('\n');
            }
        }
        if (!anyUpstream) {
            sb.append("（无上游依赖）\n");
        }

        sb.append("\n## ③ 下游草案（谁依赖本草案）\n");
        boolean anyDownstream = false;
        String selfCode = draft.code();
        for (PlanDraftView sibling : siblings) {
            if (selfCode == null || sibling.dependsOnCodes() == null
                    || !sibling.dependsOnCodes().contains(selfCode)) {
                continue;
            }
            anyDownstream = true;
            sb.append("### ").append(sibling.title()).append("（").append(sibling.code()).append("）\n");
            appendField(sb, "下游要求", truncate(sibling.content(), DOWNSTREAM_MAX_CHARS));
            appendField(sb, "下游验收", truncate(sibling.acceptance(), DOWNSTREAM_MAX_CHARS));
            sb.append('\n');
        }
        if (!anyDownstream) {
            sb.append("（无下游依赖）\n");
        }

        sb.append("\n## ④ 执行环境声明（在哪跑：仓库 / 运行时 / 数据源）\n");
        sb.append(executionEnvBlock).append('\n');
        sb.append("\n> 上面是**上级任务已声明的执行前提**，不是本草案可选的条件。据此判断方案可行性：\n")
                .append("> 若方案依赖的仓库 / 运行时 / 数据源不在其中，要么把范围收敛到不再依赖，\n")
                .append("> 要么在终稿里写清「需要补充的执行前提」。\n");

        sb.append("\n## ⑤ 派出前必须先问清的事项\n");
        sb.append("本草案要派生成为独立任务并交给另一个执行者，它**看不到本次对话**。")
                .append("因此下列信息若 ④ 里没有，请**先问用户、拿到答案后再写终稿**，")
                .append("不要用「仓库中」「数据库中」「本机已有」这类没有指代的表述代替：\n");
        sb.append("- 仓库：本地绝对路径，还是 git/gitee 地址（含分支）；若本地，路径是什么；\n");
        sb.append("- 运行时：本机（执行机）已装的语言/构建工具及版本，是否需装依赖；\n");
        sb.append("- 数据源：MySQL / Oracle / PostgreSQL / Redis / ES 中是哪一种，连接方式与测试库是否可用；\n");
        sb.append("- 外部依赖：CI 平台、消息队列、对象存储等是否已就绪、是否允许触碰。\n");
        sb.append("一次最多问 3 条最关键的（多条会淹没用户），其余按「假设 + 待确认」写进终稿。")
                .append("用户明确说「不需要」的项，要写成「不需要 X（原因）」，**不能省略**——")
                .append("省略会被执行侧当成待确认而阻塞。\n");

        sb.append("\n请先确认重构方向，再产出终稿；终稿确认后本草案将派生为独立任务并接管后续拆解。");
        return sb.toString();
    }

    private static PlanDraftView findByCode(List<PlanDraftView> drafts, String code) {
        if (drafts == null || code == null) {
            return null;
        }
        for (PlanDraftView draft : drafts) {
            if (code.equals(draft.code())) {
                return draft;
            }
        }
        return null;
    }

    private static String buildSeed(SubTaskView subTask, List<SubTaskView> siblings, String executionEnvBlock) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("【重构派生上下文】以下是本子任务的完整上下文，请基于它继续讨论并产出需求终稿。\n\n");
        sb.append("## ① 子任务原要求\n");
        appendField(sb, "标题", subTask.title());
        appendField(sb, "要求内容", subTask.content());
        appendField(sb, "交付物", subTask.deliverable());
        appendField(sb, "验收标准", subTask.acceptance());
        appendField(sb, "执行约束", subTask.constraints());

        sb.append("\n## ② 失败/阻塞轨迹\n");
        sb.append("- 返工次数：").append(subTask.reworkCount() == null ? 0 : subTask.reworkCount()).append('\n');
        Map<String, Object> ctx = subTask.context();
        appendContextField(sb, ctx, "blockReason", "阻塞原因");
        appendContextField(sb, ctx, "manualIntervention", "人工介入");
        appendContextField(sb, ctx, "lastAutoReview", "最近一次自审");
        appendContextField(sb, ctx, "reviewHistory", "评审历史");

        sb.append("\n## ③ 上游已完成产出\n");
        boolean anyUpstream = false;
        List<Long> deps = subTask.dependsOn();
        if (deps != null && !deps.isEmpty()) {
            for (Long depId : deps) {
                SubTaskView dep = findById(siblings, depId);
                if (dep == null) {
                    continue;
                }
                anyUpstream = true;
                sb.append("### ").append(dep.title()).append('\n');
                String output = SubTaskOutputExtractor.extractExecutionOutput(dep.context());
                String body = output != null && !output.isBlank() ? output : dep.content();
                sb.append(truncate(body, UPSTREAM_MAX_CHARS)).append("\n\n");
            }
        }
        if (!anyUpstream) {
            sb.append("（无前置依赖）\n");
        }

        sb.append("\n## ④ 下游依赖期待\n");
        boolean anyDownstream = false;
        for (SubTaskView sibling : siblings) {
            if (sibling.id().equals(subTask.id()) || sibling.dependsOn() == null
                    || !sibling.dependsOn().contains(subTask.id())) {
                continue;
            }
            anyDownstream = true;
            sb.append("### ").append(sibling.title()).append('\n');
            appendField(sb, "下游要求", truncate(sibling.content(), DOWNSTREAM_MAX_CHARS));
            appendField(sb, "下游验收", truncate(sibling.acceptance(), DOWNSTREAM_MAX_CHARS));
            sb.append('\n');
        }
        if (!anyDownstream) {
            sb.append("（无下游依赖）\n");
        }

        sb.append("\n## ⑤ 执行环境声明（在哪跑：仓库 / 运行时 / 数据源）\n");
        sb.append(executionEnvBlock).append('\n');
        sb.append("\n> 上面是**上级任务已声明的执行前提**，不是本子任务可选的条件。据此判断方案可行性：\n")
                .append("> 若方案依赖的仓库 / 运行时 / 数据源不在其中，要么把范围收敛到不再依赖，\n")
                .append("> 要么在终稿里写清「需要补充的执行前提」；**禁止**写「仓库中」「数据库中」\n")
                .append("> 这类没有指代的表述——执行者照着找不到。\n");

        sb.append("\n请先确认重构方向，再产出终稿；终稿确认后将创建一个独立派生任务并接管本子任务。");
        return sb.toString();
    }

    /**
     * 读父任务 X 的 {@code task.context.executionEnv} 并渲染。
     *
     * <p>取父任务而非子任务：环境是任务级的，{@code sub_task.context} 上没有这个键。读失败
     * （任务不存在 / context 为脏）一律按「未声明」处理——环境是附加价值，不该有否决权，
     * 更不该让一次解析异常把「有环境」变成「没环境」的反向误判。</p>
     */
    private String executionEnvBlock(Long taskId) {
        try {
            TaskView task = taskService.getView(taskId);
            if (task == null || task.context() == null) {
                return ENV_UNDECLARED;
            }
            String rendered = ExecutionEnv.parse(task.context().get(ExecutionEnv.CONTEXT_KEY)).render();
            return rendered.isBlank() ? ENV_UNDECLARED : rendered;
        } catch (Exception e) {
            log.warn("读取父任务执行环境失败，按未声明处理: taskId={}, err={}", taskId, e.getMessage());
            return ENV_UNDECLARED;
        }
    }

    private static SubTaskView findById(List<SubTaskView> siblings, Long id) {
        for (SubTaskView sibling : siblings) {
            if (sibling.id().equals(id)) {
                return sibling;
            }
        }
        return null;
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("- ").append(label).append("：").append(value.trim()).append('\n');
        }
    }

    private static void appendContextField(StringBuilder sb, Map<String, Object> ctx, String key, String label) {
        if (ctx == null || ctx.get(key) == null) {
            return;
        }
        String value = String.valueOf(ctx.get(key));
        if (!value.isBlank()) {
            sb.append("- ").append(label).append("：").append(truncate(value, DOWNSTREAM_MAX_CHARS)).append('\n');
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "\n…（已截断）";
    }
}
