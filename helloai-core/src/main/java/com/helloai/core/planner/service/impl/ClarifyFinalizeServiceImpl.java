package com.helloai.core.planner.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.planner.clarify.SkillDirective;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.mapper.RequirementConversationMapper;
import com.helloai.core.planner.memory.service.LongTermMemoryService;
import com.helloai.core.planner.policy.RequirementPackageParser;
import com.helloai.core.planner.policy.TaskArchetypeResolver;
import com.helloai.core.planner.policy.TaskContextKeys;
import com.helloai.core.planner.port.PlanDraftView;
import com.helloai.core.planner.service.ClarifyCommandSearchService;
import com.helloai.core.planner.service.ClarifyFinalizeService;
import com.helloai.core.planner.service.PlanDraftService;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.task.port.TaskDraft;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.TaskDerivationService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ClarifyFinalizeService} 实现：澄清会话的<b>成任务链路</b>
 * （RM9-c 从 {@link RequirementClarifyServiceImpl} 抽出的副作用簇）。
 *
 * <p>终稿 → {@code Task}：幂等校验 + CAS 推进 {@code ACTIVE → FINALIZED} + 建 PENDING Task +
 * best-effort 通知全部 PLANNER + 回填 task_id + timeline + 会话摘要归档（N-009，C5-S2）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClarifyFinalizeServiceImpl implements ClarifyFinalizeService {

    private final RequirementConversationService conversationService;
    private final RequirementMessageService messageService;
    private final TaskService taskService;
    private final AgentService agentService;
    private final AgentInboxService agentInboxService;
    private final TaskTimelineService taskTimelineService;
    private final LongTermMemoryService longTermMemoryService;
    private final RequirementConversationMapper requirementConversationMapper;
    private final ObjectMapper objectMapper;
    private final ClarifyCommandSearchService commandSearch;

    /** 显式技能：建任务时把用户选中的产出型技能包写进 required_skills。 */
    private final SkillDirective skillDirective;

    /** 执行期重构派生（`ADR-008` §13，AD-4）：会话带 sourceSubTaskId 时建 Y 而非普通任务。 */
    private final TaskDerivationService taskDerivationService;

    /** 草案期派生的草案存在性校验（`ADR-008` §5）：草案在 planner 域，task 域不能反查。 */
    private final PlanDraftService planDraftService;

    @Override
    public TaskView finalize(Long conversationId) {
        return finalizeAfterDraft(commandSearch.requireActive(conversationId),
                TimelineEventCode.TASK_CREATED_FROM_CLARIFY.code());
    }

    @Override
    public TaskView finalizeAfterDraft(RequirementConversation conversation, String timelineEvent) {
        Long conversationId = conversation.getId();
        if (conversation.getFinalTitle() == null || conversation.getFinalTitle().isBlank()) {
            throw new BizException("会话尚无终稿，请先对话至 LLM 产出终稿: conversationId=" + conversationId);
        }
        // 幂等防御：已关联存活任务则拒绝重复创建（防竞态脏数据 / 重复点击造成双任务）。
        // 派生会话（sourceSubTaskId / sourceDraftCode 非空）的 task_id 预挂父任务 X
        // （ADR-008 §13.1 / §5），不是「已建任务」的信号；重复定稿由下方 CAS 兜底。
        if (!isDerivationConversation(conversation)
                && conversation.getTaskId() != null && taskService.getView(conversation.getTaskId()) != null) {
            throw new BizException("会话已创建关联任务，请勿重复创建: taskId=" + conversation.getTaskId());
        }
        // 草案期会话在建任务前先确认草案还在——放在 CAS 之前，避免「会话已终稿但没建出任务」
        // 的悬挂态（CAS 是立即提交的定点更新，回滚不了）。并发窗口由 buildTaskFromDraft 再查一次兜底。
        if (conversation.getSourceDraftCode() != null) {
            assertDraftStillStaged(conversation);
        }
        // CAS 推进 ACTIVE → FINALIZED：失败即并发已推进，拒绝建任务避免重复
        if (!casFinalize(conversationId)) {
            throw new BizException("会话状态已变化（可能已被终稿确认），请刷新后重试: conversationId=" + conversationId);
        }
        conversation.setStatus(RequirementClarifyService.STATUS_FINALIZED);
        return buildTaskFromDraft(conversation, timelineEvent);
    }

    /**
     * CAS 推进会话状态：ACTIVE → FINALIZED（定点更新，只写 status）。
     *
     * <p>全字段 updateById 会用加载期陈旧快照整体写回，与正在跑的澄清轮并发时互相覆盖
     * （FINALIZED 被回退成 ACTIVE 的丢失更新事故）；CAS 按行条件推进，仅一方可成功。</p>
     *
     * @return true=推进成功；false=状态已变化（并发已终稿确认 / 放弃等）
     */
    private boolean casFinalize(Long conversationId) {
        return conversationService.lambdaUpdate()
                .eq(RequirementConversation::getId, conversationId)
                .eq(RequirementConversation::getStatus, RequirementClarifyService.STATUS_ACTIVE)
                .set(RequirementConversation::getStatus, RequirementClarifyService.STATUS_FINALIZED)
                .update();
    }

    @Override
    public TaskView regenerate(Long conversationId) {
        RequirementConversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new BizException("澄清会话不存在: " + conversationId);
        }
        // regenerate 只查 FINALIZED、不经 requireActive，这里单独补 kind 守卫
        if (conversation.getKind() != null
                && !RequirementClarifyService.KIND_REQUIREMENT.equals(conversation.getKind())) {
            throw new BizException("该会话为助理对话，不能使用需求澄清接口: conversationId=" + conversationId);
        }
        if (!RequirementClarifyService.STATUS_FINALIZED.equals(conversation.getStatus())) {
            throw new BizException("仅已建任务（FINALIZED）的会话可重新生成: conversationId=" + conversationId
                    + ", status=" + conversation.getStatus());
        }
        if (conversation.getFinalTitle() == null || conversation.getFinalTitle().isBlank()) {
            throw new BizException("会话缺少终稿，无法重新生成: conversationId=" + conversationId);
        }
        // 判「原任务是否还在」要用产出指针：派生会话的 task_id 是父任务 X，
        // 拿它判存在会恒真——派生任务被删后仍拒绝重新生成，等于没有重生成入口。
        Long oldTaskId = conversation.getDerivedTaskId() != null
                ? conversation.getDerivedTaskId()
                : conversation.getTaskId();
        if (oldTaskId != null && taskService.getView(oldTaskId) != null) {
            throw new BizException("原任务仍然存在（taskId=" + oldTaskId
                    + "），请先在任务管理中删除后再重新生成");
        }
        return buildTaskFromDraft(conversation, "task_regenerated_from_clarify");
    }

    /**
     * 建任务时回填「用户显式选中的技能」。
     *
     * <p><b>为什么沿历史回溯而不存在会话表上</b>：技能是「一句消息」的属性——用户这一轮选了
     * archify、下一轮可能换成业务流程图。挂 payload 与 {@code /task} 的「命令随消息走」同构，
     * 建任务时取最近一次即可，且不改表结构。</p>
     *
     * <p><b>失败不阻断建任务</b>：技能回填是增强项，读消息/目录异常时退回空列表
     * （= 与改造前的普通任务完全一致），而不是让用户点了「创建任务」却失败。</p>
     *
     * @param conversationId 会话 ID
     * @return 技能标签（含依赖包，如 biz-process-lifecycle → [archify, biz-process-lifecycle]）
     */
    private List<String> skillsForTask(Long conversationId) {
        try {
            List<RequirementMessage> messages = messageService.listByConversation(conversationId);
            for (int i = messages.size() - 1; i >= 0; i--) {
                RequirementMessage message = messages.get(i);
                if (!ClarifyCommandSearchServiceImpl.ROLE_USER.equals(message.getRole())) {
                    continue;
                }
                String skill = skillDirective.skillOfPayload(message.getPayload());
                if (skill != null) {
                    return skillDirective.requiredSkillsWithDependencies(skill);
                }
            }
        } catch (Exception e) {
            log.warn("回溯显式技能失败（不阻断建任务）: conversationId={}, err={}", conversationId, e.getMessage());
        }
        return List.of();
    }

    /**
     * 是否有在班 Agent 能承接这些技能标签（{@code required_skills} 是 AND 硬匹配）。
     *
     * <p><b>为什么必须探</b>：{@code AgentSelectionConstraints.denialReason} 对非空
     * {@code required_skills} 要求 {@code agent.skills} 全部包含，否则拒绝派发。
     * 平台技能包标签只在 Agent 注册 / 编辑时经
     * {@code AgentSkillPolicyService#deriveSkillsForRegistration} 并入——新入库的技能包
     * （如今天的 archify）不会自动出现在<b>存量</b> Agent 的 skills 里。
     * 直接写进任务就会让子任务永久 PENDING（无人可选），比「不写」更糟。</p>
     *
     * <p>探测异常按「接不住」处理（fail-safe）：宁可退化成普通任务，也不制造卡死的任务。</p>
     *
     * @param skills 技能标签（含依赖）
     * @return true=存在一个在班 Agent 的 skills 覆盖全部标签
     */
    private boolean anyActiveAgentSupports(List<String> skills) {
        try {
            List<List<String>> skillSets = agentService.listActiveSkillSets();
            if (skillSets == null) {
                return false;
            }
            for (List<String> set : skillSets) {
                if (set != null && set.containsAll(skills)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("显式技能承接面探测失败，按「接不住」处理: err={}", e.getMessage());
        }
        return false;
    }

    /**
     * 把「交付物须由该技能生成」写进任务描述（无论 required_skills 是否写得进去）。
     *
     * <p>为什么要写进描述而不是只靠 required_skills：required_skills 有降级路径（无 Agent 承接时
     * 不写），但用户「我要一张图 / 一个 HTML」的意图不能因此丢失——拆解与执行都读得到描述。</p>
     *
     * @param description 终稿描述
     * @param skills      技能标签（含依赖；空则原样返回）
     * @return 追加交付要求后的描述
     */
    private String describeWithSkill(String description, List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return description;
        }
        // 依赖展开时自身排在最后（依赖在前），取最后一个即用户实际选中的技能
        String skillName = skills.get(skills.size() - 1);
        String base = description == null ? "" : description;
        return base + "\n\n---\n\n**交付物要求**：本任务选定了技能包 `" + skillName + "`，"
                + "交付物须是该技能生成的文件（如离线单文件 HTML），并在完成时导出为可下载附件，"
                + "不要只交文字说明。";
    }

    /**
     * `B3`：记录 archetype 回落时间线事件（**观测位，不阻断建任务**）。
     *
     * <p>事件语义 = 「本任务的交付物原型不是模型判出来的，是兜底的」。下游看时间线即可判断
     * 拆解维度是否可能错位（{@code BUILD} 与 {@code DOCUMENT} 的拆分维度不同），
     * 不必再去猜 {@code task_archetype} 那个值是判断还是兜底。</p>
     */
    private void recordArchetypeFallback(Long taskId, Long conversationId, TaskArchetypeResolver.Decision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("fallbackTo", decision.value());
        payload.put("reason", decision.fallbackReason());
        if (decision.rawValue() != null) {
            payload.put("rawValue", decision.rawValue());
        }
        log.warn("终稿未给出合法 archetype，回落 {}: taskId={}, conversationId={}, reason={}, raw={}",
                decision.value(), taskId, conversationId, decision.fallbackReason(), decision.rawValue());
        // 事件写失败不得让建任务回滚：整条链路是 fail-open，B3 只加可见性
        try {
            taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_ARCHETYPE_FALLBACK.code(),
                    AgentRole.PLANNER, null, payload);
        } catch (Exception e) {
            log.warn("记录 archetype 回落事件失败（不阻断建任务）: taskId={}, reason={}, err={}",
                    taskId, decision.fallbackReason(), e.getMessage());
        }
    }

    /**
     * 复用会话终稿创建 PENDING Task、best-effort 通知全部 PLANNER、回填 task_id 并写 timeline。
     * 状态由调用方提前推进（finalize / /task 直达走 CAS，regenerate 保持 FINALIZED）；
     * task_id 定点字段写入，避免全字段 updateById 用陈旧快照覆盖并发写入。
     *
     * <p>`B3`：{@code archetype} 的判定抽到 {@link TaskArchetypeResolver}（纯函数、可单测），
     * 本方法只负责「回落时记一条时间线事件」——回落允许，但不能不可见。</p>
     */
    private TaskView buildTaskFromDraft(RequirementConversation conversation, String timelineEvent) {
        Long conversationId = conversation.getId();
        // G-011 双写：会话终稿需求包原样进 task.context.requirementPackage（S3 拆解链经
        // RequirementPackageParser.fromContext 防御式读取；与 runningSpec 键空间隔离互不影响）。
        // TD-6：本方法同时写入 conversationId（拆解期上下文快照据此取会话附件清单）。
        // 注：context 因此恒非空——原「无需求包则不初始化 context」的口径随之取消；
        // 下游读取方一律防御式取值（缺键与 null 行为一致），故不改变既有语义。
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(TaskContextKeys.CONVERSATION_ID, conversationId);
        if (conversation.getFinalPackage() != null && !conversation.getFinalPackage().isEmpty()) {
            context.put(RequirementPackageParser.CONTEXT_KEY_REQUIREMENT_PACKAGE,
                    conversation.getFinalPackage());
        }
        // 执行环境：终稿里**人工**声明了 executionEnv 时原样搬进 task.context，
        // 拆解链据此注入 {{EXECUTION_ENV}}（解析/渲染唯一实现在 common 的 ExecutionEnv）。
        // 刻意不让模型产出这个字段：requirement-finalize.md 的输出契约里没有它——路径与连接串
        // 一旦由模型编造，执行者会照着一条不存在的路径去找，比「未声明」更糟。
        if (conversation.getFinalPackage() != null) {
            Object executionEnv = conversation.getFinalPackage().get(TaskContextKeys.EXECUTION_ENV);
            if (executionEnv instanceof Map<?, ?> envMap && !envMap.isEmpty()) {
                context.put(TaskContextKeys.EXECUTION_ENV, executionEnv);
            }
        }
        // TD-4 / B3：终稿 package.archetype（六值）→ task.task_archetype；判不出 / 未给 / 非法
        // 一律回落 BUILD（与改造前语义一致，不做猜测性判定）。B3 只补「回落必须可见」。
        TaskArchetypeResolver.Decision archetypeDecision =
                TaskArchetypeResolver.resolve(conversation.getFinalPackage());
        String archetype = archetypeDecision.value();
        // 显式技能：写进 required_skills 才会被执行链展开成「技能规范 + requiredTools」。
        // 但 required_skills 在选人处是 AND 硬匹配（agent.skills 必须全部包含），若没有任何在班 Agent
        // 承接该标签，写进去会让子任务永久 PENDING——那比不写更糟。故先探一次承接面，接不住就降级。
        List<String> requiredSkills = skillsForTask(conversationId);
        List<String> effectiveSkills = requiredSkills;
        if (!requiredSkills.isEmpty() && !anyActiveAgentSupports(requiredSkills)) {
            log.warn("显式技能无在班 Agent 承接，降级为不写 required_skills（防子任务永久 PENDING）: "
                    + "conversationId={}, skills={}", conversationId, requiredSkills);
            effectiveSkills = List.of();
        }
        TaskDraft draft = new TaskDraft(conversation.getFinalTitle(),
                describeWithSkill(conversation.getFinalDescription(), requiredSkills),
                TaskStatus.PENDING, context, archetype, effectiveSkills,
                // PA-1：任务 owner 继承澄清会话 owner（宽松并入）
                conversation.getOwnerId());
        // ADR-008 §13（AD-4）：带 sourceSubTaskId 的会话是「执行期重构派生」——
        // 定稿即建 Y（父锚 parent_sub_task_id），并在同一事务把 A2 置 AWAITING_DERIVED；
        // 普通需求会话仍走原建任务路径。
        // 解耦方向一：二次拆分的会话「重新生成任务和子任务」不应被上级任务硬卡死——
        // 仅当来源 SubTask 已被级联删除（存在性判断，见 restructureViable）时才回退普通任务创建，
        // 而不是撞 createRestructureTask 的 400「父 SubTask 不存在」。
        // 状态非 RESTRUCTURING / 层级超限均不由探针吞掉：交给下方 createRestructureTask 的 400，
        // 让用户看到原因（避免静默建成独立任务导致血缘/层级上限被绕过）。
        // 故意不写库清 source_sub_task_id：回退后 task_id 已回填指向新任务，前端 taskExists 转真、
        // 按钮自动变「查看任务」；下次删任务再降级一次也幂等安全。清除会改会话语义，得不偿失。
        boolean derivedRestructure = conversation.getSourceSubTaskId() != null
                && taskDerivationService.restructureViable(conversation.getSourceSubTaskId());
        // 「本次是否真的建成了派生任务」——决定 task_id 要不要回填。
        // 草案期分支只会建派生或抛 400，走到 task 赋值就一定是派生，故可提前合并判定。
        boolean builtDerived = derivedRestructure || conversation.getSourceDraftCode() != null;
        TaskView task;
        if (derivedRestructure) {
            task = taskDerivationService.createRestructureTask(draft, conversation.getSourceSubTaskId());
            log.info("执行期重构派生任务已创建: conversationId={}, sourceSubTaskId={}, derivedTaskId={}",
                    conversationId, conversation.getSourceSubTaskId(), task.id());
        } else if (conversation.getSourceDraftCode() != null) {
            // 草案期派生（ADR-008 §5）：草案尚未物化，父锚是 (parent_task_id, parent_draft_code)
            task = createFromDraftAnchor(conversation, draft);
        } else {
            task = taskService.createFromDraft(draft);
        }
        if (!derivedRestructure && conversation.getSourceSubTaskId() != null) {
            // 父锚存在性断裂是数据级异常，降级必须留痕排障
            log.warn("解耦方向一：重构来源子任务已不存在，降级为独立任务: "
                            + "conversationId={}, sourceSubTaskId={}, taskId={}",
                    conversationId, conversation.getSourceSubTaskId(), task.id());
        }
        log.info("澄清终稿建任务: conversationId={}, taskId={}, title={}, event={}, archetype={}, fallback={}",
                conversationId, task.id(), task.title(), timelineEvent, archetype,
                archetypeDecision.fallbackReason());
        if (archetypeDecision.fallback()) {
            recordArchetypeFallback(task.id(), conversationId, archetypeDecision);
        }

        // 照 TaskController.create 的通知段：best-effort 通知全部 PLANNER，失败不阻断
        try {
            List<AgentProfileSnapshot> planners = agentService.listProfilesByRole(AgentRole.PLANNER);
            String eventId = "task.create." + task.id() + "." + System.currentTimeMillis();
            for (AgentProfileSnapshot planner : planners) {
                agentInboxService.send(planner.id(), eventId, "task.created",
                        "新任务需要规划: " + task.title(),
                        task.description() != null ? task.description() : "请查看详情",
                        "task", task.id(), "HIGH");
            }
            log.info("已通知 {} 个 PLANNER Agent", planners.size());
        } catch (Exception e) {
            log.warn("澄清建任务后通知 PLANNER 失败: taskId={}", task.id(), e);
        }

        // 会话两个指针各司其职（V150）：
        //   task_id         = 会话挂在哪个任务下（派生会话保持父任务 X，ADR-008 §13.1）
        //   derived_task_id = 本会话派生出的产出任务 Y
        // 读侧一律「derived_task_id 优先，回退 task_id」。（2026-11-12 真机：此前只有
        // task_id，派生会话的「查看任务」跳到 X，用户在 X 上点拒绝重拆，整条链被重置。）
        // 解耦方向一的回退不算派生：父锚缺失时新任务已是独立任务，task_id 指向它。
        Long producedTaskId = task.id();
        var pointerUpdate = conversationService.lambdaUpdate()
                .eq(RequirementConversation::getId, conversationId);
        if (builtDerived) {
            conversation.setDerivedTaskId(producedTaskId);
            pointerUpdate.set(RequirementConversation::getDerivedTaskId, producedTaskId);
        } else {
            conversation.setTaskId(producedTaskId);
            // 降级成独立任务后旧的产出指针已失实（可能指向已删的 Y），一并清掉，
            // 否则 taskExists 会按悬挂的 derived_task_id 判假、把「重新生成」入口一直留着
            conversation.setDerivedTaskId(null);
            pointerUpdate.set(RequirementConversation::getTaskId, producedTaskId)
                    .set(RequirementConversation::getDerivedTaskId, null);
        }
        // 定点更新只写这两个指针：全字段 updateById 会用加载期陈旧快照覆盖并发澄清轮
        // 已落库的终稿字段 / 轮数（丢失更新竞态）
        pointerUpdate.update();

        taskTimelineService.recordEvent(task.id(), null, timelineEvent,
                AgentRole.PLANNER, null, Map.of("conversationId", conversationId));
        // 会话摘要归档（N-009，C5-S2）：finalize 建任务后 best-effort 归档，失败不阻断
        archiveSessionMemory(conversationId);
        return task;
    }

    /**
     * 本会话是否「派生会话」——终稿建的是派生任务 Y，而不是普通任务。
     *
     * <p>两个锚互斥：{@code sourceSubTaskId}（执行期，子任务已物化）与
     * {@code sourceDraftCode}（草案期，子任务尚未物化）。任一非空即派生。
     * 派生会话的 {@code task_id} 预挂父任务 X，不能当作「已建任务」的证据。</p>
     */
    private static boolean isDerivationConversation(RequirementConversation conversation) {
        return conversation.getSourceSubTaskId() != null || conversation.getSourceDraftCode() != null;
    }

    /**
     * 草案期派生（`ADR-008` §5 对话版）：会话终稿 → 派生任务 `Y`。
     *
     * <p><b>定稿前必须再确认草案还在</b>：会话可能开了很久，这期间用户可能已经确认了
     * 原方案（草案物化后被清出暂存区）、删掉了草案、或整批作废。此时建 Y 会得到一个
     * 没有有效父锚的任务——血缘断在无提示处，后续「这条派生任务是从哪条草案来的」
     * 再也查不回来。宁可 400 让用户回草案页重来。</p>
     */
    private TaskView createFromDraftAnchor(RequirementConversation conversation, TaskDraft draft) {
        assertDraftStillStaged(conversation);
        Long parentTaskId = conversation.getTaskId();
        String draftCode = conversation.getSourceDraftCode();
        TaskView derived = taskDerivationService.createDraftDeriveTask(draft, parentTaskId, draftCode);
        log.info("草案期派生任务已创建（对话定稿）: conversationId={}, parentTaskId={}, code={}, derivedTaskId={}",
                conversation.getId(), parentTaskId, draftCode, derived.id());
        return derived;
    }

    /**
     * 校验草案期会话的来源草案仍在暂存区（`ADR-008` §5）。
     *
     * <p>两处调用：① 定稿 CAS 之前——避免「会话已 FINALIZED 但没有任务」的悬挂；
     * ② 建 Y 之前——覆盖①与建任务之间的并发窗口（用户在这几百毫秒里点了确认方案）。</p>
     */
    private void assertDraftStillStaged(RequirementConversation conversation) {
        Long parentTaskId = conversation.getTaskId();
        String draftCode = conversation.getSourceDraftCode();
        PlanDraftView staged = parentTaskId == null
                ? null : planDraftService.getByCode(parentTaskId, draftCode);
        if (staged == null) {
            throw new BizException(400, "来源草案已不在暂存区（方案可能已确认物化成子任务，"
                    + "或草案已删除/作废）。请回任务查看当前子任务；若要重新拆，请先重新拆解再「拆出去」: "
                    + "parentTaskId=" + parentTaskId + ", draftCode=" + draftCode);
        }
    }

    /**
     * 会话摘要归档（N-009，C5-S2）：finalize 建任务后 best-effort 归档，失败不阻断主链路。
     */
    private void archiveSessionMemory(Long conversationId) {
        try {
            RequirementConversation conv = conversationService.getById(conversationId);
            List<RequirementMessage> messages = messageService.listByConversation(conversationId);
            longTermMemoryService.archiveSession(conv, messages);
        } catch (Exception e) {
            log.warn("会话摘要归档失败（不阻断）: conversationId={}, err={}", conversationId, e.getMessage());
        }
    }

    @Override
    public void updateFinalDraftFields(Long conversationId, String title, String description,
                                       Map<String, Object> finalPackage) {
        String packageJson = null;
        if (finalPackage != null) {
            try {
                packageJson = objectMapper.writeValueAsString(finalPackage);
            } catch (Exception e) {
                log.warn("终稿需求包序列化失败，跳过 final_package 列写入: conversationId={}",
                        conversationId, e);
            }
        }
        requirementConversationMapper.updateFinalDraftFields(
                conversationId, title, description, packageJson);
    }
}
