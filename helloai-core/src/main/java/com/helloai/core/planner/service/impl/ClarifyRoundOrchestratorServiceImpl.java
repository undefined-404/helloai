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
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.planner.service.ClarifyCommandSearchService;
import com.helloai.core.planner.service.ClarifyFinalizeService;
import com.helloai.core.planner.service.ClarifyPromptRenderService;
import com.helloai.core.planner.service.ClarifyRoundOrchestratorService;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyConversationDetail;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyReply;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifySelection;
import com.helloai.core.planner.service.RoundProcess;
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

    /** 会话标题长度上限（原门面 TITLE_LIMIT，随「只建会话」一并迁入）。 */
    private static final int TITLE_LIMIT = 50;

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

    /** 会话附件认领（新建会话时把 clientToken 下的临时附件挂到会话上）。 */
    private final ChatAttachmentService chatAttachmentService;

    // ══════════════════════════════════════════════════════════════
    //  对外轮次入口
    // ══════════════════════════════════════════════════════════════

    /**
     * 只新建会话（不跑 LLM）：同步 create 与流式 streamCreate 共用（2026-10-18 抽取）。
     *
     * <p>口径与原门面 {@code create} 的建会话段逐字一致，仅把「跑一轮」留给调用方。</p>
     */
    @Override
    public RequirementConversation createConversation(String firstMessage, Long plannerAgentId,
                                                     Boolean webSearchEnabled) {
        return createConversation(firstMessage, plannerAgentId, webSearchEnabled, null);
    }

    @Override
    public RequirementConversation createConversation(String firstMessage, Long plannerAgentId,
                                                     Boolean webSearchEnabled, String clientToken) {
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
        conversation.setTitle(title.length() <= TITLE_LIMIT ? title : title.substring(0, TITLE_LIMIT));
        conversation.setStatus(RequirementClarifyService.STATUS_ACTIVE);
        conversation.setRoundCount(0);
        conversation.setPlannerAgentId(plannerAgentId);
        // NULL 落库为 NULL（兼容老数据默认开启语义由读取侧判定），false/true 严格落库
        conversation.setWebSearchEnabled(webSearchEnabled);
        // 新会话始终 CHAT 自由对话（auto 意图路由 + /planner 命令触发转方案）
        conversation.setMode(RequirementClarifyService.MODE_CHAT);
        conversationService.save(conversation);
        // 认领新会话首句前上传的临时附件（clientToken → conversationId）。
        // 必须在 save 之后：claim 的 WHERE 条件是 conversation_id IS NULL，需要有会话 id 才写得进去。
        if (clientToken != null && !clientToken.isBlank()) {
            chatAttachmentService.claimByClientToken(clientToken, conversation.getId());
        }
        log.info("澄清会话创建（仅建会话，轮次由调用方执行）: id={}, title={}, plannerAgentId={}, webSearchEnabled={}, mode={}",
                conversation.getId(), conversation.getTitle(), plannerAgentId, webSearchEnabled,
                RequirementClarifyService.MODE_CHAT);
        return conversation;
    }

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
        // 确认卡状态处理（抽为共享方法 resolvePendingClarifyConfirm，与流式同一口径）：
        // 确认 ⇒ 切 CLARIFY + 清标记；其他消息 ⇒ 清标记继续自由对话
        resolvePendingClarifyConfirm(conversation, selections);
        return doRound(conversation, trimmed, replyParser.buildSelectionPayload(selections));
    }

    /**
     * 确认卡前置处理（同步与流式共用）：确认 ⇒ 切 CLARIFY + 清标记；其他 ⇒ 清标记继续对话。
     *
     * <p>从 {@code runLlmRound} 抽出（2026-10-18）：流式路径此前直接拒绝待确认卡场景
     * （抛「请使用普通发送回答确认卡」），而结构化卡提交改走流式后该守卫会误拦确认操作。
     * 抽成单一方法后两条路径共用同一口径，不再各写一套。</p>
     */
    @Override
    public boolean resolvePendingClarifyConfirm(RequirementConversation conversation,
                                                List<RequirementClarifyService.ClarifySelection> selections) {
        boolean pendingConfirm = commandSearch.isChatMode(conversation)
                && Boolean.TRUE.equals(conversation.getPendingClarifyConfirm());
        if (!pendingConfirm) {
            return false;
        }
        boolean confirmAccepted = confirmCardProtocol.isAcceptSelected(selections);
        if (confirmAccepted) {
            conversation.setMode(RequirementClarifyService.MODE_CLARIFY);
            log.info("用户确认转方案（确认卡）: conversationId={}", conversation.getId());
        } else {
            log.info("用户取消转方案，继续自由对话: conversationId={}", conversation.getId());
        }
        conversation.setPendingClarifyConfirm(false);
        conversationService.updateById(conversation);
        return confirmAccepted;
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

    /**
     * 编辑已发送的用户消息并重跑（2026-10-19，对齐 ChatGPT/DeepSeek/Kimi「编辑 → 回溯 → 重答」）：
     *
     * <p>三步走：① 校验会话 ACTIVE、目标消息存在且为 user、且是<b>最后一条</b> user 消息
     * （更早消息是「中间编辑」，涉及分支语义，本项目线性会话结构暂不支持，拒绝防误删）；
     * ② 删除该消息之后的所有消息（旧回复 + 后续追问，避免旧上下文污染重跑）——
     * 走 MyBatis-Plus {@code removeByIds} 逻辑删除（BaseEntity.deleted 带 @TableLogic），
     * 查询侧全局过滤 deleted=0，重跑上下文天然不含旧回复；
     * ③ 更新该消息 content → 走 {@link #retryRound} 同语义重跑（不新增消息、不加轮数）。</p>
     *
     * <p>删除语义说明：用逻辑删除而非物理删除，与本项目所有删除路径口径一致
     * （会话/消息删除均软删），避免破坏「可恢复/审计」的既有约定。</p>
     */
    @Override
    public ClarifyConversationDetail editMessage(Long conversationId, Long messageId, String newContent) {
        String trimmed = newContent == null ? "" : newContent.trim();
        if (trimmed.isEmpty()) {
            throw new BizException("编辑后的消息不能为空");
        }
        RequirementConversation conversation = commandSearch.requireActive(conversationId);
        List<RequirementMessage> messages = messageService.listByConversation(conversationId);
        // 定位目标消息（按 id；找不到时尝试按 seq 兜底，防御前端传错 id）
        RequirementMessage target = messages.stream()
                .filter(m -> m.getId().equals(messageId))
                .findFirst().orElse(null);
        if (target == null) {
            throw new BizException("目标消息不存在: messageId=" + messageId);
        }
        if (!ClarifyCommandSearchServiceImpl.ROLE_USER.equals(target.getRole())) {
            throw new BizException("只能编辑自己发送的消息");
        }
        // 仅支持编辑「最后一条 user 消息」：其后若还有 user 消息（中间编辑），拒绝
        // （编辑中间消息会删掉后续用户追问，语义不可控；分支能力留待未来树形会话）
        int targetIndex = messages.indexOf(target);
        boolean lastUserMessage = true;
        for (int i = targetIndex + 1; i < messages.size(); i++) {
            if (ClarifyCommandSearchServiceImpl.ROLE_USER.equals(messages.get(i).getRole())) {
                lastUserMessage = false;
                break;
            }
        }
        if (!lastUserMessage) {
            throw new BizException("只能编辑最后一条自己发送的消息，编辑更早的消息会丢失后续对话");
        }
        // 物理删除目标之后的所有消息（旧回复 + 后续追问）
        List<Long> truncateIds = messages.stream()
                .skip(targetIndex + 1)
                .map(RequirementMessage::getId)
                .toList();
        if (!truncateIds.isEmpty()) {
            messageService.removeByIds(truncateIds);
        }
        // 更新编辑后的内容（payload 保留——纯文本 user 消息无 payload，选择快照等结构化数据不动）
        target.setContent(trimmed);
        messageService.updateById(target);
        log.info("澄清会话编辑消息并重跑: conversationId={}, messageId={}, truncated={}, newLen={}",
                conversationId, messageId, truncateIds.size(), trimmed.length());
        // 重跑该消息（与 retryRound 同语义：不新增消息、不加轮数）
        return runRoundCore(conversation, trimmed, target.getPayload());
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
        return runLlmRound(conversation, null, true, null);
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
        return runLlmRound(conversation, webSearchOutcome,
                roundDecision.decision().shouldInjectAttachment(),
                roundDecision.decision().attachmentTargets());
    }

    /** CLARIFY 模式轮次（V45 语义）：绕过决策，开关开启则按规则搜索后主回复。 */
    private ClarifyConversationDetail runClarifySearchRound(RequirementConversation conversation,
                                                            String userMessage, String userPayload) {
        WebSearchOutcome webSearchOutcome = null;
        if (commandSearch.isWebSearchEnabled(conversation)) {
            webSearchOutcome = webSearchOrchestrator.doWebSearch(
                    commandSearch.resolveSearchSource(conversation.getId(), userMessage, userPayload));
        }
        return runLlmRound(conversation, webSearchOutcome, true, null);
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
            ChatRoundDecisionParser.ChatRoundDecision decision = decisionParser.parse(result.getOutput());
            // ATT-5 可观测位：附件意图的取值直接决定「注不注入正文、搜不搜」，必须能从日志判读。
            // 不打印它的话，attachment_intent=none 在界面上没有任何可观察差异（只是正文区变成占位）。
            log.info("联合决策: conversationId={}, intent={}, intentReason={}, needSearch={}, attachmentIntent={}",
                    conversationId, decision.intent(), decision.intentReason(),
                    decision.webSearch() == null ? null : decision.webSearch().needSearch(),
                    decision.attachmentIntent() == null ? null : decision.attachmentIntent().wireValue());
            return new RoundDecision(decision, false);
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
                                                  WebSearchOutcome webSearchOutcome,
                                                  boolean includeAttachment,
                                                  java.util.List<Integer> attachmentTargets) {
        Long conversationId = conversation.getId();
        // 双模式分派：CLARIFY（含 NULL 老数据）走澄清模板 + JSON 协议解析；
        // CHAT 走通用助手模板，纯文本直接落库
        boolean clarifyMode = commandSearch.isClarifyMode(conversation);

        String webSearchContext = webSearchOutcome != null ? webSearchOutcome.toContextText() : "";
        AgentProfileSnapshot planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        String prompt = clarifyMode
                ? promptRenderService.renderClarifyPrompt(conversationId, webSearchContext)
                : promptRenderService.renderChatPrompt(conversationId, webSearchContext, includeAttachment,
                        attachmentTargets);
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
        persistClarifyOutcome(conversation, reply, webSearchOutcome, RoundProcess.EMPTY);
    }

    @Override
    public void persistClarifyOutcome(RequirementConversation conversation, ClarifyReply reply,
                                      WebSearchOutcome webSearchOutcome, RoundProcess process) {
        Long conversationId = conversation.getId();
        if ("final".equals(reply.getType())) {
            String note = reply.getMessage() != null && !reply.getMessage().isBlank()
                    ? reply.getMessage() : "已生成终稿";
            // 终稿轮同样落联网搜索查验信息（未发生搜索时保持原 3 参形态）
            if (webSearchOutcome != null || hasProcess(process)) {
                messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT, note,
                        replyParser.buildWebSearchOnlyPayload(webSearchOutcome, process));
            } else {
                messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT, note);
            }
            conversation.setFinalTitle(reply.getTitle());
            conversation.setFinalDescription(reply.getDescription());
            // 终稿需求包防御式解析（G-011 S2）：缺失 / 非法形态 → null，行为零变化
            Map<String, Object> finalPackage = replyParser.resolvePackage(reply);
            conversation.setFinalPackage(finalPackage);
            // 终稿字段定点写入：本轮实体是轮开始时的快照，全字段更新会盖掉期间并发
            // finalize 已写入的 status=FINALIZED / task_id（生产实际丢失更新竞态）
            finalizeService.updateFinalDraftFields(conversationId, reply.getTitle(), reply.getDescription(), finalPackage);
            log.info("澄清会话产出终稿: conversationId={}, finalTitle={}",
                    conversationId, reply.getTitle());
        } else {
            messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT,
                    replyParser.composeAssistantContent(reply),
                    replyParser.buildQuestionPayload(reply, webSearchOutcome, process));
        }
    }

    @Override
    public void persistChatOutcome(RequirementConversation conversation, String output,
                                   WebSearchOutcome webSearchOutcome) {
        persistChatOutcome(conversation, output, webSearchOutcome, RoundProcess.EMPTY);
    }

    /** 过程数据是否值得落库（思考非空或至少一条步骤）。 */
    private static boolean hasProcess(RoundProcess process) {
        return process != null && !process.isEmpty();
    }

    @Override
    public void persistChatOutcome(RequirementConversation conversation, String output,
                                   WebSearchOutcome webSearchOutcome, RoundProcess process) {
        Long conversationId = conversation.getId();
        if (output == null || output.isBlank()) {
            throw new BizException("自由对话 LLM 返回内容为空");
        }
        String visible = output.trim();
        ClarifyReply chatReply = replyParser.tryParseChatStructured(visible);
        if (chatReply != null) {
            messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT,
                    replyParser.composeAssistantContent(chatReply),
                    replyParser.buildQuestionPayload(chatReply, webSearchOutcome, process));
            log.info("自由对话结构化追问落库: conversationId={}", conversationId);
        } else {
            if (webSearchOutcome != null || hasProcess(process)) {
                messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT, visible,
                        replyParser.buildWebSearchOnlyPayload(webSearchOutcome, process));
            } else {
                messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_ASSISTANT, visible, null);
            }
            log.info("自由对话回复落库: conversationId={}", conversationId);
        }
    }
}
