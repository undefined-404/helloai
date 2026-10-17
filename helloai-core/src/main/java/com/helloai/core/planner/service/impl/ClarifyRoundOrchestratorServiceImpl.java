package com.helloai.core.planner.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.clarify.ChatRoundDecisionParser;
import com.helloai.core.planner.clarify.ClarifyReplyParser;
import com.helloai.core.planner.clarify.ClarifyWebSearchOrchestrator;
import com.helloai.core.planner.clarify.ConfirmCardProtocol;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.search.WebSearchOutcome;
import com.helloai.core.planner.service.ClarifyCommandSearchService;
import com.helloai.core.planner.service.ClarifyFinalizeService;
import com.helloai.core.planner.service.ClarifyPromptRenderService;
import com.helloai.core.planner.service.ClarifyRoundOrchestratorService;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyConversationDetail;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyReply;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifySelection;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.task.port.TaskView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * {@link ClarifyRoundOrchestratorService} 实现：澄清会话的<b>轮次编排主链</b>
 * （RM9-c 从 {@link RequirementClarifyServiceImpl} 抽出的编排簇，风险最高的一簇）。
 *
 * <p>承接 {@code doRound} / {@code runRoundCore} <b>重载对整组迁移</b>、CHAT 联合决策、
 * 主回复落库半段（{@code persistClarifyOutcome} / {@code persistChatOutcome}，同步与流式共用），
 * 以及 {@code enterClarify}（模式切换进入澄清轮）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClarifyRoundOrchestratorServiceImpl implements ClarifyRoundOrchestratorService {

    /** 用户消息轮数硬上限（Prompt 侧引导尽早出终稿，服务端只做兜底）。 */
    static final int MAX_ROUNDS = 20;

    /** CHAT 自由对话轮数硬上限（独立于 CLARIFY 的 MAX_ROUNDS；达上限引导转方案或新会话）。 */
    static final int MAX_CHAT_ROUNDS = 50;

    /** 联合决策 AgentTask 的 scene 标识（测试 stub 与日志可观测区分决策轮/主回复轮）。 */
    private static final String DECISION_SCENE = "requirement_chat_decision";

    private final ClarifyCommandSearchService commandSearch;
    private final ClarifyPromptRenderService promptRenderService;
    private final ClarifyFinalizeService finalizeService;
    private final RequirementMessageService messageService;
    private final RequirementConversationService conversationService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final ClarifyReplyParser replyParser;
    private final ConfirmCardProtocol confirmCardProtocol;
    private final ChatRoundDecisionParser decisionParser;
    private final ClarifyWebSearchOrchestrator webSearchOrchestrator;

    // ══════════════════════════════════════════════════════════════
    //  对外轮次入口
    // ══════════════════════════════════════════════════════════════

    @Override
    public ClarifyConversationDetail sendMessage(Long conversationId, String message) {
        return sendMessage(conversationId, message, null);
    }

    @Override
    public ClarifyConversationDetail sendMessage(Long conversationId, String message,
                                                 List<ClarifySelection> selections) {
        if (message == null || message.isBlank()) {
            throw new BizException("消息不能为空");
        }
        RequirementConversation conversation = commandSearch.requireActive(conversationId);
        String trimmed = message.trim();
        int rounds = conversation.getRoundCount() != null ? conversation.getRoundCount() : 0;
        // 确认卡待确认状态处理（LLM auto 意图路由触发）：
        //   确认卡点「确认」（selections 快照）→ 切 CLARIFY 并清标记
        //   其他消息 → 清标记继续自由对话
        boolean pendingConfirm = commandSearch.isChatMode(conversation)
                && Boolean.TRUE.equals(conversation.getPendingClarifyConfirm());
        boolean confirmAccepted = pendingConfirm && confirmCardProtocol.isAcceptSelected(selections);
        // 逃生通道（不被轮数上限挡住）：斜杠命令（/planner|/plan 转澄清、/task 直达拆解）、确认卡点「确认」
        // —— 上限提示语本身引导用户输 /planner 转方案，命令/确认必须放行
        boolean anyCommand = commandSearch.isPlannerCommand(trimmed) || commandSearch.isTaskCommand(trimmed);
        if (!confirmAccepted && !anyCommand) {
            // 轮数上限按模式分派：CHAT 用独立上限；CLARIFY（含 NULL 老数据）沿用既有 20 轮上限
            if (commandSearch.isChatMode(conversation) && rounds >= MAX_CHAT_ROUNDS) {
                throw new BizException("自由对话轮数已达上限 " + MAX_CHAT_ROUNDS
                        + "，可输入 /planner 转为方案模式，或新建会话");
            } else if (commandSearch.isClarifyMode(conversation) && rounds >= MAX_ROUNDS) {
                throw new BizException("澄清轮数已达上限 " + MAX_ROUNDS
                        + "，请放弃本会话并在任务管理中手动创建任务");
            }
        }
        if (pendingConfirm) {
            if (confirmAccepted) {
                conversation.setMode(RequirementClarifyService.MODE_CLARIFY);
                conversation.setPendingClarifyConfirm(false);
                conversationService.updateById(conversation);
                log.info("用户确认转方案（确认卡）: conversationId={}", conversationId);
            } else {
                conversation.setPendingClarifyConfirm(false);
                conversationService.updateById(conversation);
                log.info("用户取消转方案，继续自由对话: conversationId={}", conversationId);
            }
        }
        return doRound(conversation, trimmed, replyParser.buildSelectionPayload(selections));
    }

    @Override
    public ClarifyConversationDetail retryRound(Long conversationId) {
        RequirementConversation conversation = commandSearch.requireActive(conversationId);
        List<RequirementMessage> messages = messageService.listByConversation(conversationId);
        if (messages.isEmpty()
                || !ClarifyCommandSearchServiceImpl.ROLE_USER.equals(messages.get(messages.size() - 1).getRole())) {
            throw new BizException("最后一条消息已有助手回复，无需重试: conversationId=" + conversationId);
        }
        log.info("澄清会话重试一轮 LLM: conversationId={}", conversationId);
        RequirementMessage lastUser = messages.get(messages.size() - 1);
        return runRoundCore(conversation,
                lastUser.getContent() == null ? "" : lastUser.getContent(),
                lastUser.getPayload());
    }

    @Override
    public ClarifyConversationDetail doRound(RequirementConversation conversation, String userMessage) {
        return doRound(conversation, userMessage, null);
    }

    @Override
    public ClarifyConversationDetail doRound(RequirementConversation conversation, String userMessage,
                                             String userPayload) {
        Long conversationId = conversation.getId();
        // /task 直达拆解命令（任意模式优先）：切 CLARIFY + 终稿直出轮，
        // LLM 产出 final 即自动建任务（信息严重不足自动降级追问，不建任务）
        if (commandSearch.isTaskCommand(userMessage)) {
            return runTaskDirectRound(conversation, userMessage);
        }
        // 转方案类斜杠命令检测（/planner|/plan，跳过决策）：直接进入澄清轮
        if (commandSearch.isChatMode(conversation) && commandSearch.isPlannerCommand(userMessage)) {
            String extraText = commandSearch.plannerCommandExtra(userMessage);
            log.info("CHAT 会话斜杠命令触发转方案: conversationId={}, extraLen={}",
                    conversationId, extraText.length());
            return enterClarify(conversationId, extraText.isEmpty() ? null : extraText);
        }
        messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_USER, userMessage, userPayload);
        int rounds = conversation.getRoundCount() != null ? conversation.getRoundCount() : 0;
        conversation.setRoundCount(rounds + 1);
        conversationService.updateById(conversation);
        return runRoundCore(conversation, userMessage, userPayload);
    }

    @Override
    public ClarifyConversationDetail enterClarify(Long conversationId, String extraMessage) {
        if (extraMessage != null && !extraMessage.isBlank()) {
            messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_USER, extraMessage.trim(), null);
            log.info("澄清会话斜杠命令附加文本落库: conversationId={}", conversationId);
        }
        RequirementConversation conversation = commandSearch.requireActive(conversationId);
        String from = conversation.getMode();
        conversation.setMode(RequirementClarifyService.MODE_CLARIFY);
        // 手动切换时一并清除意图词待确认标记，避免残留状态影响后续轮次
        conversation.setPendingClarifyConfirm(false);
        conversationService.updateById(conversation);
        log.info("澄清会话切换模式: conversationId={}, from={}, to={}",
                conversationId, from, RequirementClarifyService.MODE_CLARIFY);
        // 切换轮不做联网搜索（后续再评估）；澄清模板基于全量历史直接产草案/追问
        return runLlmRound(conversation, null);
    }

    // ══════════════════════════════════════════════════════════════
    //  内部轮次实现
    // ══════════════════════════════════════════════════════════════

    /**
     * 轮次核心：前置决策 → 分发（CHAT 联合决策 / CLARIFY 规则搜索）。
     * doRound（新用户消息，已落库加轮数）与 retryRound（重试，不落库不加轮数）共用；
     * retry 继续跑决策与搜索，与 sendMessage 完全同语义。
     */
    private ClarifyConversationDetail runRoundCore(RequirementConversation conversation,
                                                   String userMessage, String userPayload) {
        if (commandSearch.isClarifyMode(conversation)) {
            return runClarifySearchRound(conversation, userMessage, userPayload);
        }
        RoundDecision roundDecision = makeRoundDecision(conversation, userMessage);
        if (roundDecision.decision().isClarify()) {
            return applyClarifyDecision(conversation, roundDecision.decision());
        }
        WebSearchOutcome webSearchOutcome = commandSearch.resolveChatSearchOutcome(conversation, userMessage, userPayload,
                roundDecision.decision(), roundDecision.degraded());
        return runLlmRound(conversation, webSearchOutcome);
    }

    /** CLARIFY 模式轮次（V45 语义）：绕过决策，开关开启则按规则搜索后主回复。 */
    private ClarifyConversationDetail runClarifySearchRound(RequirementConversation conversation,
                                                            String userMessage, String userPayload) {
        WebSearchOutcome webSearchOutcome = null;
        if (commandSearch.isWebSearchEnabled(conversation)) {
            webSearchOutcome = webSearchOrchestrator.doWebSearch(
                    commandSearch.resolveSearchSource(conversation.getId(), userMessage, userPayload));
        }
        return runLlmRound(conversation, webSearchOutcome);
    }

    /**
     * /task 直达拆解轮：需求描述即终稿输入，跳过多轮澄清直接产出终稿并建任务（信息严重不足自动降级追问）。
     */
    private ClarifyConversationDetail runTaskDirectRound(RequirementConversation conversation,
                                                         String userMessage) {
        Long conversationId = conversation.getId();
        if (!RequirementClarifyService.MODE_CLARIFY.equals(conversation.getMode())) {
            conversation.setMode(RequirementClarifyService.MODE_CLARIFY);
            conversation.setPendingClarifyConfirm(false);
            conversationService.updateById(conversation);
        }
        String extraText = commandSearch.taskCommandExtra(userMessage);
        if (!extraText.isEmpty()) {
            messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_USER, extraText, null);
            int rounds = conversation.getRoundCount() != null ? conversation.getRoundCount() : 0;
            conversation.setRoundCount(rounds + 1);
            conversationService.updateById(conversation);
        }
        log.info("/task 直达拆解轮: conversationId={}, extraLen={}",
                conversationId, extraText.length());
        WebSearchOutcome webSearchOutcome = null;
        if (commandSearch.isWebSearchEnabled(conversation)) {
            webSearchOutcome = webSearchOrchestrator.doWebSearch(
                    commandSearch.resolveSearchSource(conversationId, extraText, null));
        }
        return runFinalizeLlmRound(conversation, webSearchOutcome);
    }

    /** 终稿直出 LLM 轮（/task 专用）：final 自动建任务、question 降级为普通追问。 */
    private ClarifyConversationDetail runFinalizeLlmRound(RequirementConversation conversation,
                                                          WebSearchOutcome webSearchOutcome) {
        Long conversationId = conversation.getId();
        String webSearchContext = webSearchOutcome != null ? webSearchOutcome.toContextText() : "";
        AgentProfileSnapshot planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        String prompt = promptRenderService.renderFinalizePrompt(conversationId, webSearchContext);
        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(prompt)
                .context(Map.of("conversationId", conversationId, "scene", "requirement_finalize"))
                .requiredCapabilities(Map.of())
                .build();
        AgentResult result = platformAgentExecutionService.executeSync(planner.id(), agentTask);
        if (!result.isSuccess()) {
            throw new BizException("终稿产出 LLM 调用失败: " + result.getErrorMessage());
        }
        ClarifyReply reply = replyParser.parseReply(result.getOutput());
        // P1-3：description 四小节不全 → 同轮纠偏重试一次（fail-open，不阻断建任务）
        reply = promptRenderService.applyDescriptionSectionGuard(conversation, planner, prompt,
                "requirement_finalize", reply);
        if ("final".equals(reply.getType())) {
            String note = reply.getMessage() != null && !reply.getMessage().isBlank()
                    ? reply.getMessage() : "已生成终稿";
            if (webSearchOutcome != null) {
                messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT, note,
                        replyParser.buildWebSearchOnlyPayload(webSearchOutcome));
            } else {
                messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT, note);
            }
            conversation.setFinalTitle(reply.getTitle());
            conversation.setFinalDescription(reply.getDescription());
            Map<String, Object> finalPackage = replyParser.resolvePackage(reply);
            conversation.setFinalPackage(finalPackage);
            // 终稿字段定点写入：不全字段覆盖，避免盖掉并发写入的 status / task_id
            finalizeService.updateFinalDraftFields(conversationId, reply.getTitle(), reply.getDescription(), finalPackage);
            // 复用手动终稿确认：幂等校验 + CAS 推进 + 建任务（事件区分直达路径），
            // 与用户在澄清轮在跑时并发点「创建任务并自动拆解」的竞态由同一套守卫兼顾
            TaskView task = finalizeService.finalizeAfterDraft(conversation, "task_created_from_clarify_direct");
            log.info("/task 直达拆解产出终稿并建任务: conversationId={}, taskId={}",
                    conversationId, task.id());
        } else {
            // 信息严重不足：LLM 输出追问，降级为普通澄清轮（不建任务，会话保持 ACTIVE）
            messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT,
                    replyParser.composeAssistantContent(reply),
                    replyParser.buildQuestionPayload(reply, webSearchOutcome));
            log.info("/task 终稿直出降级为追问: conversationId={}", conversationId);
        }
        return new ClarifyConversationDetail(conversation,
                messageService.listByConversation(conversationId));
    }

    @Override
    public RoundDecision makeRoundDecision(RequirementConversation conversation, String userMessage) {
        Long conversationId = conversation.getId();
        AgentProfileSnapshot planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(promptRenderService.renderDecisionPrompt(conversation, userMessage))
                .context(Map.of("conversationId", conversationId, "scene", DECISION_SCENE))
                .requiredCapabilities(Map.of())
                .build();
        try {
            AgentResult result = platformAgentExecutionService.executeSync(planner.id(), agentTask);
            if (!result.isSuccess()) {
                log.warn("联合决策 LLM 调用失败，降级 chat 决策: conversationId={}, err={}",
                        conversationId, result.getErrorMessage());
                return RoundDecision.degradedDefaults();
            }
            return new RoundDecision(decisionParser.parse(result.getOutput()), false);
        } catch (Exception e) {
            log.warn("联合决策解析失败，降级 chat 决策: conversationId={}, err={}",
                    conversationId, e.getMessage());
            return RoundDecision.degradedDefaults();
        }
    }

    @Override
    public ClarifyConversationDetail applyClarifyDecision(RequirementConversation conversation,
                                                          ChatRoundDecisionParser.ChatRoundDecision decision) {
        Long conversationId = conversation.getId();
        String question = decision.clarificationQuestion();
        messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT,
                confirmCardProtocol.buildAskText(),
                confirmCardProtocol.buildAskPayload());
        conversation.setPendingClarifyConfirm(true);
        conversationService.updateById(conversation);
        log.info("联合决策 intent=clarify，落库单条确认卡: conversationId={}, question(下一轮重新生成)={}",
                conversationId, question);
        return new ClarifyConversationDetail(conversation,
                messageService.listByConversation(conversationId));
    }

    /**
     * LLM 一轮（不落 user 消息）：选 Planner → 全量历史渲染模板 → LLM → 解析 question/final 分支落库。
     *
     * @param webSearchOutcome 本轮联网搜索归一化记录（未搜索/重试/切换轮传 null）
     */
    private ClarifyConversationDetail runLlmRound(RequirementConversation conversation,
                                                  WebSearchOutcome webSearchOutcome) {
        Long conversationId = conversation.getId();
        // 双模式分派：CLARIFY（含 NULL 老数据）走澄清模板 + JSON 协议解析；
        // CHAT 走通用助手模板，纯文本直接落库
        boolean clarifyMode = commandSearch.isClarifyMode(conversation);

        String webSearchContext = webSearchOutcome != null ? webSearchOutcome.toContextText() : "";
        AgentProfileSnapshot planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        String prompt = clarifyMode
                ? promptRenderService.renderClarifyPrompt(conversationId, webSearchContext)
                : promptRenderService.renderChatPrompt(conversationId, webSearchContext);
        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(prompt)
                .context(Map.of("conversationId", conversationId,
                        "scene", clarifyMode ? "requirement_clarify" : "requirement_chat"))
                .requiredCapabilities(Map.of())
                .build();
        AgentResult result = platformAgentExecutionService.executeSync(planner.id(), agentTask);
        if (!result.isSuccess()) {
            throw new BizException("需求澄清 LLM 调用失败: " + result.getErrorMessage());
        }

        // CHAT 模式（纯回复落库，意图/搜索决策已前置到 runRoundCore）：
        //   决策=clarify 的轮次在 runRoundCore 已落单条确认卡返回，不会走到这里；
        //   主回复 LLM 不再输出/解析 __intent__ 标记，只承担回答正文（结构化追问 / 纯文本）
        if (!clarifyMode) {
            persistChatOutcome(conversation, result.getOutput(), webSearchOutcome);
            return new ClarifyConversationDetail(conversation,
                    messageService.listByConversation(conversationId));
        }

        ClarifyReply reply = replyParser.parseReply(result.getOutput());
        // P1-3：description 四小节不全 → 同轮纠偏重试一次（fail-open，不阻断终稿落库）
        reply = promptRenderService.applyDescriptionSectionGuard(conversation, planner, prompt,
                "requirement_clarify", reply);
        persistClarifyOutcome(conversation, reply, webSearchOutcome);
        return new ClarifyConversationDetail(conversation,
                messageService.listByConversation(conversationId));
    }

    @Override
    public void persistClarifyOutcome(RequirementConversation conversation, ClarifyReply reply,
                                      WebSearchOutcome webSearchOutcome) {
        Long conversationId = conversation.getId();
        if ("final".equals(reply.getType())) {
            String note = reply.getMessage() != null && !reply.getMessage().isBlank()
                    ? reply.getMessage() : "已生成终稿";
            // 终稿轮同样落联网搜索查验信息（未发生搜索时保持原 3 参形态）
            if (webSearchOutcome != null) {
                messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT, note,
                        replyParser.buildWebSearchOnlyPayload(webSearchOutcome));
            } else {
                messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT, note);
            }
            conversation.setFinalTitle(reply.getTitle());
            conversation.setFinalDescription(reply.getDescription());
            // 终稿需求包防御式解析（G-011 S2）：缺失 / 非法形态 → null，行为零变化
            Map<String, Object> finalPackage = replyParser.resolvePackage(reply);
            conversation.setFinalPackage(finalPackage);
            // 终稿字段定点写入：本轮实体是轮开始时的快照，全字段 updateById 会盖掉期间并发
            // finalize 已写入的 status=FINALIZED / task_id（生产实际丢失更新竞态）
            finalizeService.updateFinalDraftFields(conversationId, reply.getTitle(), reply.getDescription(), finalPackage);
            log.info("澄清会话产出终稿: conversationId={}, finalTitle={}",
                    conversationId, reply.getTitle());
        } else {
            messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT,
                    replyParser.composeAssistantContent(reply),
                    replyParser.buildQuestionPayload(reply, webSearchOutcome));
        }
    }

    @Override
    public void persistChatOutcome(RequirementConversation conversation, String output,
                                   WebSearchOutcome webSearchOutcome) {
        Long conversationId = conversation.getId();
        if (output == null || output.isBlank()) {
            throw new BizException("自由对话 LLM 返回内容为空");
        }
        String visible = output.trim();
        ClarifyReply chatReply = replyParser.tryParseChatStructured(visible);
        if (chatReply != null) {
            messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT,
                    replyParser.composeAssistantContent(chatReply),
                    replyParser.buildQuestionPayload(chatReply, webSearchOutcome));
            log.info("自由对话结构化追问落库: conversationId={}", conversationId);
        } else {
            if (webSearchOutcome != null) {
                messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT, visible,
                        replyParser.buildWebSearchOnlyPayload(webSearchOutcome));
            } else {
                messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT, visible, null);
            }
            log.info("自由对话回复落库: conversationId={}", conversationId);
        }
    }
}
