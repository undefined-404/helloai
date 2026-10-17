package com.helloai.core.planner.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.ClarifyCommandSearchService;
import com.helloai.core.planner.service.ClarifyFinalizeService;
import com.helloai.core.planner.service.ClarifyRoundOrchestratorService;
import com.helloai.core.planner.service.ClarifyStreamService;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 对话式需求澄清编排服务的<b>薄门面</b>（需求模糊 → 多轮追问 → 终稿 → 创建任务）。
 *
 * <p><b>RM9-c 拆分（2026-10-16）</b>：本类由 1557 行上帝类拆为「门面 + 5 职责接口实现」，
 * 门面只保留<b>会话生命周期与模式开关</b>（入参最浅、无 LLM 语义）：会话 CRUD
 * （{@code create} / {@code listConversations} / {@code detail} / {@code abandon} / {@code delete}）、
 * 模式切换（{@code switchToClarify} / {@code switchToChat}）、{@code listPlannerOptions}；
 * 其余方法逐方法委托给职责类（外部契约不变）：</p>
 * <ul>
 *     <li>{@link ClarifyRoundOrchestratorService} —— 轮次编排主链（含 {@code doRound} / {@code runRoundCore}
 *         重载对整组迁移、联合决策、主回复落库半段）；</li>
 *     <li>{@link ClarifyStreamService} —— SSE 流式面；</li>
 *     <li>{@link ClarifyFinalizeService} —— 成任务链路（含 CAS 幂等）；</li>
 *     <li>{@link ClarifyCommandSearchService} —— 命令解析 + 搜索决策 + 会话状态守卫（叶子契约）；</li>
 *     <li>{@code ClarifyPromptRenderService}（impl：{@code ClarifyPromptRenderServiceImpl}）—— 模板渲染 + 描述段守卫。</li>
 * </ul>
 *
 * <p><b>§7.8 类规模拆分评审结论（2026-08-23）原文保留</b>：本类为澄清编排汇聚点，超 500 行 /
 * 8 依赖红线，按 §7.8 选项二书面声明不继续拆分：</p>
 * <ul>
 *     <li>已剥离：PlannerAgentPicker（选 Agent）、ClarifyReplyParser（回复解析）、
 *         ConfirmCardProtocol（确认卡协议）、IntentDetectionService（意图识别）、
 *         ClarifyWebSearchOrchestrator（联网检索）；</li>
 *     <li>剩余职责：会话生命周期（create / sendMessage / retryRound / finalize / regenerate /
 *         abandon / switchToClarify / switchToChat）+ 轮次编排（doRound / runLlmRound）+
 *         终稿转任务（buildTaskFromDraft）；</li>
 *     <li>不拆理由：轮次编排与消息持久化 / 终稿转任务共享会话状态与轮数上限决策，拆分将导致
 *         会话状态在类间传递；已外置部分均为可独立测试的无状态组件，剩余编排逻辑为
 *         单会话状态机的自然整体。</li>
 * </ul>
 *
 * <p><b>★2026-10-16 该声明被 RM9-c 取代（顾虑逐条消解）</b>：</p>
 * <ul>
 *     <li>顾虑①「轮次编排与消息持久化 / 终稿转任务共享会话状态，拆分将导致会话状态在类间传递」
 *         ⇒ 实测<b>不成立</b>：会话实体 {@code RequirementConversation} 本就是入参（非类内可变状态），
 *         拆分后仍在方法间以参数传递，<b>并未新增任何跨类共享可变状态</b>；</li>
 *     <li>顾虑②「剩余编排逻辑为单会话状态机的自然整体，无法独立测试」⇒ 实测<b>不成立</b>：
 *         拆出的三簇（命令/搜索、渲染/守卫、成任务）各自早有独立单测与真实依赖，可脱离编排独立验证；</li>
 *     <li>拆分按 {@code §9.1 业务职责}（轮次编排 / 流式 / 成任务 / 命令搜索 / 渲染守卫），
 *         非按方法数量，故不适用「按行数机械拆」的反对意见。</li>
 * </ul>
 *
 * <p>类不加事务：LLM 调用耗时较长，不能占用数据库事务；单条消息落库走各 ServiceImpl 自带事务。
 * LLM/解析失败时：user 消息保留（round_count 已加），抛 BizException，前端弹错后可重发消息重试；
 * 会话本身无状态回退需求（始终 ACTIVE）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementClarifyServiceImpl implements RequirementClarifyService {

    /** 会话标题取首条用户消息的截断长度。 */
    private static final int TITLE_LIMIT = 50;

    /** 会话列表单次返回上限（首期不做分页）。 */
    private static final int LIST_LIMIT = 50;

    private final RequirementConversationService conversationService;
    private final RequirementMessageService messageService;
    private final TaskService taskService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final ClarifyRoundOrchestratorService orchestrator;
    private final ClarifyStreamService streamService;
    private final ClarifyFinalizeService finalizeService;
    private final ClarifyCommandSearchService commandSearch;

    // ══════════════════════════════════════════════════════════════
    //  会话生命周期
    // ══════════════════════════════════════════════════════════════

    /**
     * 新建澄清会话：首条用户消息截断为标题 → 存 user 消息 → 走一轮 LLM。
     *
     * @param plannerAgentId   手动指定的 Planner Agent ID（空=系统自动选择）；
     *                         指定时严格校验可选性，澄清与后续拆解均跟随该 Planner
     * @param webSearchEnabled 会话级联网搜索开关（NULL=默认开启）；
     *                         每轮 LLM 调用前若 true 服务端会预检索行业资料并注入
     *                         {@code {{WEB_SEARCH_CONTEXT}}} 占位符，失败降级跳过
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail create(String firstMessage, Long plannerAgentId, Boolean webSearchEnabled) {
        return create(firstMessage, plannerAgentId, webSearchEnabled, null);
    }

    /**
     * 新建会话（双模式入口）。
     *
     * @param initialMode 初始对话模式（已废弃：新会话始终 CHAT）；非法值抛 BizException
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail create(String firstMessage, Long plannerAgentId, Boolean webSearchEnabled,
                                            String initialMode) {
        if (firstMessage == null || firstMessage.isBlank()) {
            throw new BizException("首条消息不能为空");
        }
        if (plannerAgentId != null) {
            plannerAgentPicker.validateSelectable(plannerAgentId);
        }
        String trimmed = firstMessage.trim();
        // /task 直达命令的会话标题取附加文本（命令前缀不落标题）
        String title = trimmed;
        if (commandSearch.isTaskCommand(trimmed)) {
            title = commandSearch.taskCommandExtra(trimmed);
            if (title.isEmpty()) {
                title = "/task";
            }
        }
        RequirementConversation conversation = new RequirementConversation();
        conversation.setTitle(title.length() <= TITLE_LIMIT
                ? title : title.substring(0, TITLE_LIMIT));
        conversation.setStatus(STATUS_ACTIVE);
        conversation.setRoundCount(0);
        conversation.setPlannerAgentId(plannerAgentId);
        // NULL 落库为 NULL（兼容老数据默认开启语义由读取侧判定），false/true 严格落库
        conversation.setWebSearchEnabled(webSearchEnabled);
        // 新会话始终 CHAT 自由对话（auto 意图路由 + /planner 命令触发转方案）
        conversation.setMode(MODE_CHAT);
        conversationService.save(conversation);
        log.info("澄清会话创建: id={}, title={}, plannerAgentId={}, webSearchEnabled={}, mode={}",
                conversation.getId(), conversation.getTitle(), plannerAgentId, webSearchEnabled, MODE_CHAT);
        return orchestrator.doRound(conversation, trimmed);
    }

    /** 兼容重载：旧调用方未传开关时默认开启（NULL 代表默认开启，与老数据语义一致）。 */
    @Override
    public ClarifyConversationDetail create(String firstMessage, Long plannerAgentId) {
        return create(firstMessage, plannerAgentId, null);
    }

    /**
     * 向会话追加一条用户消息并走一轮 LLM 澄清（纯文本，无选项快照）。
     *
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail sendMessage(Long conversationId, String message) {
        return orchestrator.sendMessage(conversationId, message, null);
    }

    /**
     * 向会话追加一条用户消息并走一轮 LLM 澄清。
     *
     * @param selections 结构化选项回答快照（可为 null/空=纯文本回答）；
     *                   序列化为 {@code {"selections":[...]}} 存入 user 消息 payload，
     *                   仅作前端回显快照，LLM 上下文仍用 content 可读文本
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail sendMessage(Long conversationId, String message,
                                                 List<ClarifySelection> selections) {
        return orchestrator.sendMessage(conversationId, message, selections);
    }

    /**
     * 重试上一轮 LLM：仅当最后一条消息是 user（即上轮 LLM 失败、助手回复缺失）时可用；
     * 不新增 user 消息、不加轮数（失败那轮已计入 round_count）。
     *
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail retryRound(Long conversationId) {
        return orchestrator.retryRound(conversationId);
    }

    /**
     * 流式对话轮（S1 最小闭环，Chat SSE）：与 {@link #sendMessage(Long, String, List)}
     * 同语义的同步前置，仅主回复 LLM 调用走 token 增量流；主回复完成后一次性落库并发出 {@code done}。
     */
    @Override
    public Flux<ChatStreamEvent> streamRound(Long conversationId, String message,
                                             List<ClarifySelection> selections) {
        return streamService.streamRound(conversationId, message, selections);
    }

    /** Planner 下拉选数据源（平台内 PLANNER 可选 + 在班外部 Agent 置灰）。 */
    @Override
    public List<PlannerAgentPicker.PlannerOption> listPlannerOptions() {
        return plannerAgentPicker.listOptions();
    }

    /**
     * 终稿确认：校验会话存在且 ACTIVE 后，经成任务链路建任务。
     *
     * @return 创建的任务
     */
    @Override
    public TaskView finalize(Long conversationId) {
        return finalizeService.finalize(conversationId);
    }

    /**
     * 重新生成任务：会话已 FINALIZED 且原任务已被删除时，复用会话终稿重建 PENDING Task，
     * 回填新的 task_id（会话保持 FINALIZED）。
     *
     * @return 重新创建的任务
     */
    @Override
    public TaskView regenerate(Long conversationId) {
        return finalizeService.regenerate(conversationId);
    }

    /** 放弃会话：ACTIVE → ABANDONED。 */
    @Override
    public void abandon(Long conversationId) {
        RequirementConversation conversation = commandSearch.requireActive(conversationId);
        conversation.setStatus(STATUS_ABANDONED);
        conversationService.updateById(conversation);
        log.info("澄清会话已放弃: conversationId={}", conversationId);
    }

    /**
     * 删除已放弃会话：校验 ABANDONED → 消息逻辑删 → 会话逻辑删，两表同事务。
     * <p>仅 ABANDONED 放行：ABANDONED 会话按 abandon 前置校验必然无 task_id（终稿未确认），
     * 删除不触碰任务侧；FINALIZED 会话承载任务追溯，禁止删除。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long conversationId) {
        RequirementConversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new BizException("澄清会话不存在: " + conversationId);
        }
        if (!STATUS_ABANDONED.equals(conversation.getStatus())) {
            throw new BizException("仅已放弃的会话可删除，当前状态: " + conversation.getStatus()
                    + ", conversationId=" + conversationId);
        }
        messageService.removeByConversation(conversationId);
        conversationService.removeById(conversationId);
        log.info("澄清会话已删除（软删）: conversationId={}", conversationId);
    }

    /**
     * 切换到方案澄清模式（斜杠命令路径）：先落库附加文本（用户消息，进 LLM 上下文），
     * 再切 CLARIFY 并跑一轮澄清（首轮强制 structured → 推荐卡片必出）。
     *
     * @param extraMessage 斜杠命令后的附加文本；空/空白则不加消息（与既有 switchToClarify 等价）
     */
    @Override
    public ClarifyConversationDetail switchToClarify(Long conversationId, String extraMessage) {
        return orchestrator.enterClarify(conversationId, extraMessage);
    }

    /**
     * 切换到方案澄清模式：置位落库 + 一轮 LLM 基于全量历史产终稿草案/结构化追问。
     *
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail switchToClarify(Long conversationId) {
        return orchestrator.enterClarify(conversationId, null);
    }

    /**
     * 切回自由对话模式：仅置位，不调用 LLM；
     * 历史消息全部保留，后续切回 CLARIFY 时作为全量澄清上下文。
     *
     * @return 会话 + 全部消息
     */
    @Override
    public ClarifyConversationDetail switchToChat(Long conversationId) {
        RequirementConversation conversation = commandSearch.requireActive(conversationId);
        String from = conversation.getMode();
        conversation.setMode(MODE_CHAT);
        // 切回 CHAT 时防御性清除意图词待确认标记（该状态仅 CHAT 模式语义存在）
        conversation.setPendingClarifyConfirm(false);
        conversationService.updateById(conversation);
        log.info("澄清会话切换模式: conversationId={}, from={}, to={}",
                conversationId, from, MODE_CHAT);
        return new ClarifyConversationDetail(conversation,
                messageService.listByConversation(conversationId));
    }

    /** 会话列表（按创建时间倒序，LIMIT 50，首期不分页）。 */
    @Override
    public List<RequirementConversation> listConversations() {
        return conversationService.lambdaQuery()
                .orderByDesc(RequirementConversation::getCreateTime)
                .last("LIMIT " + LIST_LIMIT)
                .list();
    }

    /** 会话详情：会话 + 消息按 seq 升序。 */
    @Override
    public ClarifyConversationDetail detail(Long conversationId) {
        RequirementConversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new BizException("澄清会话不存在: " + conversationId);
        }
        ClarifyConversationDetail view = new ClarifyConversationDetail(conversation,
                messageService.listByConversation(conversationId));
        // 供前端判断 FINALIZED 会话的原任务是否已被删除（悬挂软引用），从而决定能否重新生成
        view.setTaskExists(conversation.getTaskId() != null
                && taskService.getView(conversation.getTaskId()) != null);
        return view;
    }
}
