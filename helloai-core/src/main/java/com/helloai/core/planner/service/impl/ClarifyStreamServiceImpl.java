package com.helloai.core.planner.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.clarify.ChatRoundDecisionParser;
import com.helloai.core.planner.clarify.ClarifyReplyParser;
import com.helloai.core.planner.clarify.ClarifyWebSearchOrchestrator;
import com.helloai.core.planner.clarify.ConfirmCardProtocol;
import com.helloai.core.planner.clarify.SkillDirective;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.search.WebSearchOutcome;
import com.helloai.core.planner.service.ClarifyCommandSearchService;
import com.helloai.core.planner.service.ClarifyPromptRenderService;
import com.helloai.core.planner.service.ClarifyRoundOrchestratorService;
import com.helloai.core.planner.service.ClarifyRoundOrchestratorService.RoundDecision;
import com.helloai.core.planner.service.ClarifyStreamService;
import com.helloai.core.planner.service.RequirementClarifyService.ChatStreamEvent;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyReply;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifySelection;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RoundProcess;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.planner.support.ConversationRoundLocker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link ClarifyStreamService} 实现：澄清会话的 <b>SSE 流式面</b>
 * （RM9-c 从 {@link RequirementClarifyServiceImpl} 抽出的副作用簇，与同步链零耦合）。
 *
 * <p>流式轮实现：同步前置段（校验/落库/决策/搜索）在订阅线程执行，主回复段返回 token 流；
 * 任何前置/流式异常统一转 {@code error} 事件，Controller 层无需再做异常兜底。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClarifyStreamServiceImpl implements ClarifyStreamService {

    /** 确认卡（intent=clarify）流式播放分片大小（字符/片）：question 按此切片发射 token 帧驱动打字机。 */
    private static final int CLARIFY_PLAY_CHUNK_SIZE = 5;

    /** 确认卡流式播放片间延时（毫秒）：模拟逐字节奏，避免瞬间整段出现。 */
    private static final long CLARIFY_PLAY_DELAY_MS = 12L;

    /** 过程步骤里「正在联网检索：xxx」的检索词展示上限（防止把整段消息塞进过程区）。 */
    private static final int PROGRESS_TEXT_LIMIT = 60;

    private final ClarifyRoundOrchestratorService orchestrator;
    private final ClarifyPromptRenderService promptRenderService;
    private final ClarifyCommandSearchService commandSearch;
    private final RequirementMessageService messageService;
    private final RequirementConversationService conversationService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final ClarifyReplyParser replyParser;
    private final ConfirmCardProtocol confirmCardProtocol;
    private final ClarifyWebSearchOrchestrator webSearchOrchestrator;

    /** 每会话轮次互斥锁（RC-1）：同步前置段持锁、解锁后再入 LLM 流段。 */
    private final ConversationRoundLocker roundLocker;

    /** 显式技能判定（2026-10-26）：技能消息不走流式，与 /task 同口径拒绝。 */
    private final SkillDirective skillDirective;

    @Override
    public Flux<ChatStreamEvent> streamRound(Long conversationId, String message,
                                             List<ClarifySelection> selections) {
        return streamRound(conversationId, message, selections, null);
    }

    @Override
    public Flux<ChatStreamEvent> streamRound(Long conversationId, String message,
                                             List<ClarifySelection> selections,
                                             List<Long> attachmentIds) {
        return Flux.defer(() -> runStreamRound(conversationId, message, selections, attachmentIds))
                .onErrorResume(e -> {
                    log.warn("流式对话轮失败: conversationId={}, err={}", conversationId, e.getMessage());
                    return Flux.just(ChatStreamEvent.error(streamErrorMessage(e)));
                });
    }

    /** 流式轮实现：同步前置段（校验/落库/决策/搜索）在订阅线程执行，主回复段返回 token 流。 */
    private Flux<ChatStreamEvent> runStreamRound(Long conversationId, String message,
                                                 List<ClarifySelection> selections,
                                                 List<Long> attachmentIds) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty()) {
            throw new BizException("消息不能为空");
        }
        RequirementConversation conversation = commandSearch.requireActive(conversationId);
        // 斜杠命令（/planner /task）与显式技能（[skill:name]）暂不支持流式，保持普通发送：
        // 三者的共同点是「服务端要读返回值做联动」（切模式 / 建任务 / 写 required_skills），
        // 流式通道只吐文本，带不出这个信号。
        if (commandSearch.isTaskCommand(trimmed) || commandSearch.isPlannerCommand(trimmed)
                || skillDirective.isSkillCommand(trimmed)) {
            throw new BizException("命令与技能暂不支持流式，请使用普通发送");
        }
        // 确认卡前置处理（2026-10-18）：与同步 runLlmRound 共用同一方法——确认即切 CLARIFY
        // 并清标记（随后按 CLARIFY 分支流式出回复），非确认消息仅清标记继续对话。
        // 此前这里是「抛 BizException 要求走普通发送」，会把「确认卡点确认」误拦
        // （结构化卡提交已改走流式），故改为与同步路径同口径。
        orchestrator.resolvePendingClarifyConfirm(conversation, selections);
        String userPayload = replyParser.buildSelectionPayload(selections);
        // RC-1 每会话互斥：同步前置段（校验/落库/自增轮数）持锁串行化，解锁后再入 LLM 流段。
        // 竞态全在前置段「读 roundCount → addMessage → setRoundCount(+1) → updateById」，
        // 抢占失败即抛 BizException（前端可读）。unlock 由 runLocked finally 保障，异常不成对泄漏。
        roundLocker.runLocked(conversationId, () -> {
            doStreamRoundPrelude(conversation, trimmed, userPayload);
            return null;
        });
        log.info("流式对话轮开始: conversationId={}, round={}, mode={}",
                conversationId, conversation.getRoundCount(), conversation.getMode());

        // CLARIFY 模式：绕过意图决策，规则搜索后真流式主回复（结构化 JSON 正文流式展示）
        if (commandSearch.isClarifyMode(conversation)) {
            return runClarifyStream(conversation, trimmed, userPayload);
        }

        // CHAT 模式主回复段（决策 + 三态搜索 + 流式主回复），与 streamCreate 首轮共用
        return runChatStream(conversation, trimmed, userPayload, conversation.getRoundCount(), attachmentIds);
    }

    /** RC-1 流式轮同步前置段：轮数上限校验 → 落 user 消息 → 自增轮数（加锁保护，见 §4.1）。 */
    private void doStreamRoundPrelude(RequirementConversation conversation, String trimmed,
                                      String userPayload) {
        Long conversationId = conversation.getId();
        // 锁内重读轮数（§7.2 读改写回写用最新行）：conversation 是锁外快照，
        // 并发轮次下直接基于它 +1 会把别人的自增覆盖掉（丢轮数）。
        int rounds = latestRoundCount(conversationId, conversation);
        if (rounds >= ClarifyRoundOrchestratorServiceImpl.MAX_CHAT_ROUNDS) {
            throw new BizException("对话轮数已达上限 " + ClarifyRoundOrchestratorServiceImpl.MAX_CHAT_ROUNDS
                    + "，可输入 /planner 转为方案模式，或新建会话");
        }
        messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_USER, trimmed, userPayload);
        conversation.setRoundCount(rounds + 1);
        conversationService.updateById(conversation);
    }

    /** 锁内取最新轮数；行被并发删除时退回调用方快照（不阻断本轮落库）。 */
    private int latestRoundCount(Long conversationId, RequirementConversation fallback) {
        RequirementConversation latest = conversationService.getById(conversationId);
        Integer rounds = latest != null ? latest.getRoundCount() : fallback.getRoundCount();
        return rounds != null ? rounds : 0;
    }

    /**
     * CHAT 模式流式段（决策 → 搜索 → 流式主回复 → 落库 → done）：
     * 老会话 {@link #runStreamRound} 与 {@link #streamCreate} 首轮<b>共用同一段</b>。
     *
     * <p>抽出的意义（2026-10-18）：新会话首条消息此前只能走同步 {@code create}，
     * 用户要等完整一轮（含搜索）才见到回复且无思考过程。抽出后两条入口行为完全一致。</p>
     *
     * @param roundNo 本轮序号（仅日志用：老会话 = round_count+1，新会话 = 1）
     */
    private Flux<ChatStreamEvent> runChatStream(RequirementConversation conversation, String userMessage,
                                                String userPayload, int roundNo,
                                                List<Long> attachmentIds) {
        Long conversationId = conversation.getId();
        // ★过程数据（2026-10-18）：思考全文 + 检索步骤，随消息 payload 落库
        // （done 后/刷新后仍可回看，对齐 DeepSeek「已深度思考」与 ChatGPT「Thinking」面板）
        StringBuilder thinking = new StringBuilder();
        List<String> steps = new ArrayList<>();
        // ★分层 defer（关键）：决策（~2s）与联网检索（15~25s）都是同步阻塞调用。
        // 若在 Flux 组装期执行，「正在检索」这条步骤要等检索结束才一起发出——用户照样干等。
        // 故：每一步都「先发进度帧 → 再 defer 执行该步」，保证进度帧在耗时动作开始前就已达前端。
        return Flux.concat(
                Flux.just(ChatStreamEvent.progress("正在理解问题并规划检索…")),
                Flux.defer(() -> {
                    // 联合决策（intent + 澄清问题 + 搜索决策）
                    RoundDecision roundDecision = orchestrator.makeRoundDecision(conversation, userMessage);
                    if (roundDecision.decision().isClarify()) {
                        // 确认卡流式播放：question 分片打字机 → 落库确认卡 → done
                        // 2026-10-27：用户已拒绝过（clarify_declined=1）时不再弹卡，
                        // 退回普通对话主回复（防「取消 → 再判 clarify → 再弹卡」死循环）。
                        // ★抑制判断必须无副作用（不落库）：确认卡由 playClarifyQuestion 内
                        //   applyClarifyDecision 唯一落库——这里再调一次会把确认卡消息落两条
                        //   （曾发生：A 方案把判断改为 applyClarifyDecision()!=null 后双落库回归）。
                        if (!Boolean.TRUE.equals(conversation.getClarifyDeclined())) {
                            return playClarifyQuestion(conversation, roundDecision.decision());
                        }
                    }
                    boolean willSearch = commandSearch.isWebSearchEnabled(conversation);
                    String searchStep = willSearch
                            ? "正在联网检索：" + abbreviate(userMessage, PROGRESS_TEXT_LIMIT)
                            : "本轮未联网检索（会话搜索开关关闭）";
                    steps.add(searchStep);
                    return Flux.concat(
                            Flux.just(ChatStreamEvent.progress(searchStep)),
                            Flux.defer(() -> {
                                WebSearchOutcome outcome = commandSearch.resolveChatSearchOutcome(
                                        conversation, userMessage, userPayload,
                                        roundDecision.decision(), roundDecision.degraded());
                                if (willSearch) {
                                    steps.add(describeSearchResult(outcome));
                                }
                                return runChatMainReply(conversation, outcome, steps, thinking, roundNo,
                                        roundDecision.decision().shouldInjectAttachment(),
                                        roundDecision.decision().attachmentTargets(), attachmentIds);
                            }));
                }));
    }

    /** CHAT 主回复段（检索完成后）：流式主回复 → 连同过程数据落库 → done。 */
    private Flux<ChatStreamEvent> runChatMainReply(RequirementConversation conversation,
                                                   WebSearchOutcome outcome, List<String> steps,
                                                   StringBuilder thinking, int roundNo,
                                                   boolean includeAttachment,
                                                   java.util.List<Integer> attachmentTargets,
                                                   java.util.List<Long> attachmentIds) {
        Long conversationId = conversation.getId();
        // 主回复 prompt 构造（与 runLlmRound CHAT 分支同构：固定 CHAT 模板）
        String webSearchContext = outcome != null ? outcome.toContextText() : "";
        AgentProfileSnapshot planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(promptRenderService.renderChatPrompt(conversationId, webSearchContext, includeAttachment,
                        attachmentTargets, attachmentIds))
                .context(Map.of("conversationId", conversationId, "scene", "requirement_chat"))
                .requiredCapabilities(Map.of())
                .build();
        // 先补发「检索结果」进度帧（此时检索确已完成），再进主回复流
        Flux<ChatStreamEvent> resultSteps = steps.size() > 1
                ? Flux.just(ChatStreamEvent.progress(steps.get(steps.size() - 1)))
                : Flux.empty();
        StringBuilder buffer = new StringBuilder();
        return Flux.concat(resultSteps, platformAgentExecutionService.executeStream(planner.id(), agentTask)
                .map(chunk -> {
                    if (chunk.isReasoning()) {
                        thinking.append(chunk.text());
                        return ChatStreamEvent.reasoning(chunk.text());
                    }
                    buffer.append(chunk.text());
                    return ChatStreamEvent.token(chunk.text());
                })
                .concatWith(Flux.defer(() -> {
                    orchestrator.persistChatOutcome(conversation, buffer.toString(), outcome,
                            new RoundProcess(thinking.toString(), steps));
                    log.info("流式对话轮完成并落库: conversationId={}, round={}, thinkingChars={}, steps={}",
                            conversationId, roundNo, thinking.length(), steps.size());
                    return Flux.just(ChatStreamEvent.done());
                })));
    }

    /**
     * 检索结果 → 一行过程步骤文本（前端过程区展示用）。
     *
     * <p>对齐 DeepSeek「已搜索 N 个网页」/ ChatGPT「Searching the web」：让用户知道
     * 「搜了什么词、拿到几条、花了多久」，而不仅仅是一个转圈。</p>
     */
    private static String describeSearchResult(WebSearchOutcome outcome) {
        if (outcome == null) {
            // 搜索未执行（开关关闭）、调用被短路，或候选词全是元词被过滤：
            // 措辞用「未返回结果」而非「未执行」，因为调用方无法区分二者。
            // 结尾用「本轮可用资料」而不是「模型自有知识」——有附件时答案来自附件，
            // 说成"自有知识"会让用户误以为附件没被读到。
            return "联网检索未返回结果（将基于本轮可用资料回答）";
        }
        if (outcome.isFailed()) {
            return "联网检索失败，已降级为模型自有知识回答";
        }
        String queries = outcome.getQueries() == null || outcome.getQueries().isEmpty()
                ? "" : "（检索词：" + String.join(" / ", outcome.getQueries()) + "）";
        String cards = outcome.getModalCards() == null || outcome.getModalCards().isEmpty()
                ? "" : "，含 " + outcome.getModalCards().size() + " 条结构化数据";
        return "已检索 " + outcome.getTotal() + " 条结果" + cards + queries
                + "，耗时 " + String.format(java.util.Locale.ROOT, "%.1fs", outcome.getCostMs() / 1000.0);
    }

    /** 过程步骤文本截断（防止把整段用户消息塞进过程区）。 */
    private static String abbreviate(String text, int limit) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= limit ? oneLine : oneLine.substring(0, limit) + "…";
    }

    /**
     * 流式新建会话并跑首轮：建会话 → 首帧 SESSION 事件 → 落 user 消息 + round_count=1 →
     * CHAT 流式段（与老会话完全同一条路径）。
     */
    @Override
    public Flux<ChatStreamEvent> streamCreate(String message, Long plannerAgentId, Boolean webSearchEnabled) {
        return streamCreate(message, plannerAgentId, webSearchEnabled, null);
    }

    @Override
    public Flux<ChatStreamEvent> streamCreate(String message, Long plannerAgentId, Boolean webSearchEnabled,
                                              String clientToken) {
        return streamCreateWithClientToken(message, plannerAgentId, webSearchEnabled, clientToken, null);
    }

    @Override
    public Flux<ChatStreamEvent> streamCreateWithClientToken(String message, Long plannerAgentId,
                                                             Boolean webSearchEnabled, String clientToken,
                                                             List<Long> attachmentIds) {
        return Flux.defer(() -> runStreamCreate(message, plannerAgentId, webSearchEnabled, clientToken,
                attachmentIds))
                .onErrorResume(e -> {
                    log.warn("流式新建会话失败: err={}", e.getMessage());
                    return Flux.just(ChatStreamEvent.error(streamErrorMessage(e)));
                });
    }

    private Flux<ChatStreamEvent> runStreamCreate(String message, Long plannerAgentId, Boolean webSearchEnabled,
                                                  String clientToken, List<Long> attachmentIds) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty()) {
            throw new BizException("首条消息不能为空");
        }
        // 斜杠命令（/planner /task）与显式技能（[skill:name]）由同步链路处理：
        // 三者的后续流转都依赖同步返回值（切模式 / 建任务 / required_skills 落库）。
        if (commandSearch.isTaskCommand(trimmed) || commandSearch.isPlannerCommand(trimmed)
                || skillDirective.isSkillCommand(trimmed)) {
            throw new BizException("命令与技能暂不支持流式，请使用普通发送");
        }
        RequirementConversation conversation =
                orchestrator.createConversation(trimmed, plannerAgentId, webSearchEnabled, clientToken);
        // RC-1 每会话互斥：首轮前置段同样持锁（键 = chat:conversation:{id}:round，与老会话共锁一致），
        // 保证新建会话落首条消息 + round_count=1 的过程不被并发的首轮重复追加。
        roundLocker.runLocked(conversation.getId(), () -> {
            messageService.addMessage(conversation.getId(), ClarifyCommandSearchServiceImpl.ROLE_USER, trimmed, null);
            conversation.setRoundCount(1);
            conversationService.updateById(conversation);
            return null;
        });
        log.info("流式会话首轮开始: conversationId={}, mode={}, webSearchEnabled={}",
                conversation.getId(), conversation.getMode(), webSearchEnabled);
        // 首帧必须给前端会话 id（否则 done 后无从拉 detail / 后续消息发错会话）
        return Flux.concat(
                Flux.just(ChatStreamEvent.session(String.valueOf(conversation.getId()))),
                runChatStream(conversation, trimmed, null, 1, attachmentIds));
    }


    /**
     * CLARIFY 模式流式主回复：与同步澄清轮同语义（绕过决策、开关开启则规则搜索），仅 LLM 调用走
     * {@code executeStream} 增量流——token 转发给前端打字机展示，完整 output 收集后落库（追问卡/终稿）→ done。
     */
    private Flux<ChatStreamEvent> runClarifyStream(RequirementConversation conversation,
                                                   String userMessage, String userPayload) {
        Long conversationId = conversation.getId();
        // ★过程数据（2026-10-18）：思考全文 + 检索步骤随 payload 落库（done 后仍可回看）
        StringBuilder thinking = new StringBuilder();
        List<String> steps = new ArrayList<>();
        boolean willSearch = commandSearch.isWebSearchEnabled(conversation);
        String searchStep = willSearch
                ? "正在联网检索：" + abbreviate(userMessage, PROGRESS_TEXT_LIMIT)
                : "本轮未联网检索（会话搜索开关关闭）";
        steps.add(searchStep);
        // 分层 defer：先发「正在检索」进度帧，再把同步阻塞的检索放进 defer 执行
        // （否则进度帧要等检索结束才与结果帧一起到达，用户依旧干等）
        return Flux.concat(
                Flux.just(ChatStreamEvent.progress(searchStep)),
                Flux.defer(() -> {
                    WebSearchOutcome outcome = willSearch
                            ? webSearchOrchestrator.doWebSearch(
                                    commandSearch.resolveSearchSource(conversationId, userMessage, userPayload))
                            : null;
                    if (willSearch) {
                        steps.add(describeSearchResult(outcome));
                    }
                    return runClarifyMainReply(conversation, userMessage, outcome, steps, thinking);
                }));
    }

    /** CLARIFY 主回复段（检索完成后）：流式主回复 → 解析落库（含过程数据）→ done。 */
    private Flux<ChatStreamEvent> runClarifyMainReply(RequirementConversation conversation, String userMessage,
                                                      WebSearchOutcome outcome, List<String> steps,
                                                      StringBuilder thinking) {
        Long conversationId = conversation.getId();
        String webSearchContext = outcome != null ? outcome.toContextText() : "";
        AgentProfileSnapshot planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        String prompt = promptRenderService.renderClarifyPrompt(conversationId, webSearchContext);
        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(prompt)
                .context(Map.of("conversationId", conversationId, "scene", "requirement_clarify"))
                .requiredCapabilities(Map.of())
                .build();
        Flux<ChatStreamEvent> resultSteps = steps.size() > 1
                ? Flux.just(ChatStreamEvent.progress(steps.get(steps.size() - 1)))
                : Flux.empty();
        StringBuilder buffer = new StringBuilder();
        return Flux.concat(resultSteps, platformAgentExecutionService.executeStream(planner.id(), agentTask)
                .map(chunk -> {
                    // ★2026-10-18：思考过程走 reasoning 帧（前端实时展示），
                    // 绝不并入 buffer——buffer 参与 JSON 解析与落库，混入思考会毁掉解析
                    if (chunk.isReasoning()) {
                        thinking.append(chunk.text());
                        return ChatStreamEvent.reasoning(chunk.text());
                    }
                    buffer.append(chunk.text());
                    return ChatStreamEvent.token(chunk.text());
                })
                .concatWith(Flux.defer(() -> {
                    // 与 runLlmRound 同语义解析落库（描述守卫纠偏走同步 LLM，罕见路径可接受）
                    ClarifyReply reply = replyParser.parseReply(buffer.toString());
                    reply = promptRenderService.applyDescriptionSectionGuard(conversation, planner, prompt,
                            "requirement_clarify", reply);
                    orchestrator.persistClarifyOutcome(conversation, reply, outcome,
                            new RoundProcess(thinking.toString(), steps));
                    log.info("流式澄清轮完成并落库: conversationId={}, thinkingChars={}, steps={}",
                            conversationId, thinking.length(), steps.size());
                    return Flux.just(ChatStreamEvent.done());
                })));
    }

    /**
     * 确认卡流式播放（intent=clarify）：把<b>确认意图正文</b>按小块分片、小延时发射 token 帧，
     * 打字机效果；播放完成后落库确认卡并置待确认标记 → done。
     */
    private Flux<ChatStreamEvent> playClarifyQuestion(RequirementConversation conversation,
                                                      ChatRoundDecisionParser.ChatRoundDecision decision) {
        String text = confirmCardProtocol.buildAskText();
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += CLARIFY_PLAY_CHUNK_SIZE) {
            chunks.add(text.substring(i, Math.min(text.length(), i + CLARIFY_PLAY_CHUNK_SIZE)));
        }
        return Flux.fromIterable(chunks)
                .delayElements(Duration.ofMillis(CLARIFY_PLAY_DELAY_MS))
                .map(ChatStreamEvent::token)
                .concatWith(Flux.defer(() -> {
                    orchestrator.applyClarifyDecision(conversation, decision);
                    log.info("确认卡播放完成并落库: conversationId={}", conversation.getId());
                    return Flux.just(ChatStreamEvent.done());
                }));
    }

    /** 流式错误事件文本：取根因 message（BizException 友好信息），缺失时回落类名。 */
    private String streamErrorMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName() : root.getMessage();
    }
}
