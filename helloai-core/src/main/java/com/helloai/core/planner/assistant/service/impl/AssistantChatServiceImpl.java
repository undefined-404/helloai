package com.helloai.core.planner.assistant.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.assistant.AssistantPromptBuilder;
import com.helloai.core.planner.assistant.AssistantSearchQueryPlanner;
import com.helloai.core.planner.assistant.AssistantSearchQueryPlanner.AssistantSearchPlan;
import com.helloai.core.planner.assistant.AssistantSkillDirective;
import com.helloai.core.planner.assistant.entity.ConversationContextState;
import com.helloai.core.planner.assistant.service.AssistantChatService;
import com.helloai.core.planner.assistant.service.AssistantProfileService;
import com.helloai.core.planner.assistant.service.AssistantRoundInput;
import com.helloai.core.planner.assistant.service.ContextCompactor;
import com.helloai.core.planner.assistant.service.ConversationContextStateService;
import com.helloai.core.planner.assistant.service.ConversationHistorySearchService;
import com.helloai.core.planner.assistant.service.ConversationHistorySearchService.HistoryHit;
import com.helloai.core.planner.attachment.ChatAttachmentPromptRenderer;
import com.helloai.core.planner.clarify.ClarifyReplyParser;
import com.helloai.core.planner.clarify.ClarifyWebSearchOrchestrator;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.memory.entity.LongTermMemory;
import com.helloai.core.planner.memory.service.LongTermMemoryService;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.search.WebSearchOutcome;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementClarifyService.ChatStreamEvent;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyConversationDetail;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.planner.service.RoundProcess;
import com.helloai.core.planner.support.AssistantRoundLockTtlResolver;
import com.helloai.core.planner.support.ConversationRoundLocker;
import com.helloai.core.planner.support.RoundProgressText;
import com.helloai.core.shared.util.CurrentUser;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link AssistantChatService} 实现（PA-11 步骤2 v1 / PA-11 v2 增补）。
 *
 * <p><b>检索增强预注入</b>：调用 LLM 前先按当前用户检索「最近工作 + 相关历史 + 精选记忆」，
 * 拼进 system prompt（对齐 QwenPaw auto-recall），而非依赖工具绑定层——复用
 * {@link PlatformAgentExecutionService#executeSync}，不新造执行链。</p>
 *
 * <p><b>联网搜索 / 技能 / 附件</b>（v2）：三项能力此前只存在于需求澄清链路，
 * 助理链路一律不支持，界面挂上去的开关无处落地。v2 把它们接进来：联网搜索复用
 * {@link ClarifyWebSearchOrchestrator}（同一套检索编排与降级语义），技能复用
 * {@link AssistantSkillDirective}（产出型技能包正文与澄清链路同源），附件复用
 * {@link ChatAttachmentPromptRenderer}（清单 + 正文按预算注入）。</p>
 *
 * <p><b>并发 / 一致性</b>：整轮（含同步 LLM）持 {@link ConversationRoundLocker} 会话锁，
 * 与澄清链路共用同一键，避免消息交错与 roundCount 竞态；助手消息 + roundCount 在
 * {@link TransactionTemplate} 内原子写入（LLM 调用本身不进 DB 事务）。首轮失败会清理
 * 新建会话；同一句重试不重复落 user 消息（轻量幂等）。</p>
 *
 * <p><b>建任务显式化</b>：{@link #createTaskFromContext} 新建 {@code kind=REQUIREMENT}
 * 会话并写血缘（{@code parent_conversation_id}/{@code forked_from_message_id}），
 * 不静默转模式。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantChatServiceImpl implements AssistantChatService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private static final int TITLE_LIMIT = 60;
    private static final int MEMORY_LIMIT = 5;
    private static final int RECENT_LIMIT = 5;
    private static final int HIT_LIMIT = 5;
    private static final int SEED_MESSAGE_LIMIT = 6;
    private static final int SEED_SNIPPET_MAX = 300;

    private final RequirementConversationService conversationService;
    private final RequirementMessageService messageService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final AssistantProfileService assistantProfileService;
    private final LongTermMemoryService longTermMemoryService;
    private final ConversationHistorySearchService historySearchService;
    private final ContextCompactor contextCompactor;
    private final ConversationContextStateService contextStateService;
    private final ConversationRoundLocker roundLocker;
    private final AssistantRoundLockTtlResolver roundLockTtlResolver;
    private final PlatformTransactionManager transactionManager;
    /** 联网搜索编排（与需求澄清链路同一套检索与降级语义）。 */
    private final ClarifyWebSearchOrchestrator webSearchOrchestrator;
    /** 助理检索决策（历史 + 附件 + 时间 → 独立搜索词 / 附件优先跳过）。 */
    private final AssistantSearchQueryPlanner searchQueryPlanner;
    /** 会话附件 → prompt 文本（清单 + 正文按预算注入）。 */
    private final ChatAttachmentPromptRenderer attachmentPromptRenderer;
    /** 会话附件查询（判断本会话是否挂了附件，避免把占位符塞进 prompt）。 */
    private final ChatAttachmentService chatAttachmentService;
    /** 本轮技能约束渲染（方案整理 / 直接拆解 / 产出型技能包）。 */
    private final AssistantSkillDirective skillDirective;
    /** 消息 payload 构造（webSearch / process），与澄清链路同源，避免两套序列化口径。 */
    private final ClarifyReplyParser replyParser;

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ClarifyConversationDetail createConversation(String firstMessage, Long plannerAgentId,
                                                        AssistantRoundInput input) {
        if (firstMessage == null || firstMessage.isBlank()) {
            throw new BizException("消息不能为空");
        }
        if (plannerAgentId != null) {
            plannerAgentPicker.validateSelectable(plannerAgentId);
        }
        Long userId = requireUser();
        AssistantRoundInput roundInput = input == null ? AssistantRoundInput.empty() : input;
        String trimmed = firstMessage.trim();
        RequirementConversation conversation = newConversation(trimmed, plannerAgentId, roundInput, userId);
        try {
            return doRound(conversation, trimmed, userId, roundInput);
        } catch (RuntimeException e) {
            // 首轮失败：清理刚建的会话（含已落的 user 消息），不留空/半截会话
            cleanupCreatedConversation(conversation.getId());
            throw e;
        }
    }

    @Override
    public Flux<ChatStreamEvent> streamCreateConversation(String firstMessage, Long plannerAgentId,
                                                          AssistantRoundInput input) {
        return Flux.defer(() -> {
            if (firstMessage == null || firstMessage.isBlank()) {
                throw new BizException("消息不能为空");
            }
            if (plannerAgentId != null) {
                plannerAgentPicker.validateSelectable(plannerAgentId);
            }
            Long userId = requireUser();
            AssistantRoundInput roundInput = input == null ? AssistantRoundInput.empty() : input;
            String trimmed = firstMessage.trim();
            RequirementConversation conversation = newConversation(trimmed, plannerAgentId, roundInput, userId);
            // 首帧必须是会话 id：done 后前端据此拉 detail、后续消息才能发到正确会话
            return Flux.concat(
                    Flux.just(ChatStreamEvent.session(String.valueOf(conversation.getId()))),
                    runAssistantRound(conversation, trimmed, userId, roundInput));
        }).onErrorResume(e -> {
            log.warn("助理流式新建会话失败: err={}", e.getMessage());
            return Flux.just(ChatStreamEvent.error(streamErrorMessage(e)));
        });
    }

    @Override
    public ClarifyConversationDetail sendMessage(Long conversationId, String message, AssistantRoundInput input) {
        if (message == null || message.isBlank()) {
            throw new BizException("消息不能为空");
        }
        Long userId = requireUser();
        RequirementConversation conversation = requireAssistantConversation(conversationId, userId);
        AssistantRoundInput roundInput = input == null ? AssistantRoundInput.empty() : input;
        applyWebSearchFlag(conversation, roundInput);
        return doRound(conversation, message.trim(), userId, roundInput);
    }

    @Override
    public Flux<ChatStreamEvent> streamSendMessage(Long conversationId, String message, AssistantRoundInput input) {
        return Flux.defer(() -> {
            if (message == null || message.isBlank()) {
                throw new BizException("消息不能为空");
            }
            Long userId = requireUser();
            RequirementConversation conversation = requireAssistantConversation(conversationId, userId);
            AssistantRoundInput roundInput = input == null ? AssistantRoundInput.empty() : input;
            applyWebSearchFlag(conversation, roundInput);
            String trimmed = message.trim();
            log.info("助理流式对话轮开始: conversationId={}, round={}, webSearchEnabled={}",
                    conversationId, conversation.getRoundCount(), conversation.getWebSearchEnabled());
            return runAssistantRound(conversation, trimmed, userId, roundInput);
        }).onErrorResume(e -> {
            log.warn("助理流式对话轮失败: conversationId={}, err={}", conversationId, e.getMessage());
            return Flux.just(ChatStreamEvent.error(streamErrorMessage(e)));
        });
    }

    /** 建会话实体并落库（同步/流式首轮共用），含 clientToken 附件认领。 */
    private RequirementConversation newConversation(String trimmed, Long plannerAgentId,
                                                    AssistantRoundInput roundInput, Long userId) {
        RequirementConversation conversation = new RequirementConversation();
        conversation.setTitle(trimmed.length() <= TITLE_LIMIT ? trimmed : trimmed.substring(0, TITLE_LIMIT));
        conversation.setStatus(RequirementClarifyService.STATUS_ACTIVE);
        conversation.setRoundCount(0);
        conversation.setPlannerAgentId(plannerAgentId);
        // 联网搜索开关：助理侧默认关闭（与设计图一致），请求显式带值时以请求为准
        conversation.setWebSearchEnabled(roundInput.webSearchEnabled() != null
                ? roundInput.webSearchEnabled() : Boolean.FALSE);
        conversation.setMode(RequirementClarifyService.MODE_CHAT);
        conversation.setKind(RequirementClarifyService.KIND_ASSISTANT);
        conversation.setOwnerId(userId);
        conversationService.save(conversation);
        // 认领新会话首句前上传的临时附件（clientToken → conversationId），必须在建会之后
        if (roundInput.clientToken() != null && !roundInput.clientToken().isBlank()) {
            chatAttachmentService.claimByClientToken(roundInput.clientToken(), conversation.getId());
        }
        log.info("助理会话创建: id={}, title={}, plannerAgentId={}, ownerId={}, webSearchEnabled={}",
                conversation.getId(), conversation.getTitle(), plannerAgentId, userId,
                conversation.getWebSearchEnabled());
        return conversation;
    }

    /** 联网搜索开关可中途切换：请求带值时定点回写会话，后续轮次沿用（不整行 updateById，避免覆盖并发自增的轮数）。 */
    private void applyWebSearchFlag(RequirementConversation conversation, AssistantRoundInput input) {
        Boolean enabled = input.webSearchEnabled();
        if (enabled == null || enabled.equals(conversation.getWebSearchEnabled())) {
            return;
        }
        conversationService.lambdaUpdate()
                .set(RequirementConversation::getWebSearchEnabled, enabled)
                .eq(RequirementConversation::getId, conversation.getId())
                .update();
        conversation.setWebSearchEnabled(enabled);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTaskFromContext(Long conversationId, Long forkedFromMessageId) {
        Long userId = requireUser();
        RequirementConversation source = requireAssistantConversation(conversationId, userId);
        List<RequirementMessage> messages = messageService.listByConversation(conversationId);
        String seed = buildSeed(messages);
        String title = "从助理对话创建：" + (source.getTitle() == null ? "" : source.getTitle());
        RequirementConversation target = new RequirementConversation();
        target.setTitle(title.length() <= TITLE_LIMIT ? title : title.substring(0, TITLE_LIMIT));
        target.setStatus(RequirementClarifyService.STATUS_ACTIVE);
        target.setRoundCount(0);
        target.setPlannerAgentId(source.getPlannerAgentId());
        target.setMode(RequirementClarifyService.MODE_CHAT);
        target.setKind(RequirementClarifyService.KIND_REQUIREMENT);
        target.setOwnerId(userId);
        target.setParentConversationId(conversationId);
        target.setForkedFromMessageId(forkedFromMessageId);
        conversationService.save(target);
        messageService.addMessage(target.getId(), ROLE_USER, seed);
        log.info("从助理对话创建需求会话: sourceId={}, targetId={}, forkedFromMessageId={}",
                conversationId, target.getId(), forkedFromMessageId);
        return target.getId();
    }

    /** 整轮（含同步 LLM）持会话锁执行，与澄清链路共用同一键。 */
    private ClarifyConversationDetail doRound(RequirementConversation conversation, String message, Long userId,
                                              AssistantRoundInput input) {
        // 租期取「配置下限 vs provider 读超时上界」的较大值（N-1）：锁不得短于其守护的 LLM 调用
        return roundLocker.runLocked(conversation.getId(), roundLockTtlResolver.effectiveTtlSeconds(),
                () -> doRoundLocked(conversation, message, userId, input));
    }

    /** 一轮助理问答：幂等落 user 消息 → 自动压缩 → 检索拼 prompt → LLM → 事务内落 assistant + roundCount。 */
    private ClarifyConversationDetail doRoundLocked(RequirementConversation conversation,
                                                    String message, Long userId,
                                                    AssistantRoundInput input) {
        Long conversationId = conversation.getId();
        RoundSeed seed = prepareRound(conversation, message, userId);
        PromptContext context = loadPromptContext(conversationId, message, userId, input, seed);

        AssistantSearchPlan searchPlan = resolveSearchPlan(conversation, message, context.hasAttachments());
        String webSearchContext = resolveWebSearchContext(conversation, message, searchPlan);
        String systemPrompt = buildSystemPrompt(context, webSearchContext);
        AgentProfileSnapshot planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        AgentTask task = buildAgentTask(conversationId, message, systemPrompt);
        AgentResult result = platformAgentExecutionService.executeSync(planner.id(), task);
        if (result == null || !result.isSuccess()) {
            String reason = result == null ? "空响应" : result.getErrorMessage();
            throw new BizException("助理回复生成失败: " + reason);
        }
        String output = result.getOutput() == null ? "" : result.getOutput().trim();

        // LLM 不进 DB 事务；仅把「助手消息 + roundCount」两个写原子化
        List<RequirementMessage> transcript = seed.transcript();
        transactionTemplate.executeWithoutResult(status -> {
            transcript.add(messageService.addMessage(conversationId, ROLE_ASSISTANT, output));
            conversation.setRoundCount(
                    (conversation.getRoundCount() == null ? 0 : conversation.getRoundCount()) + 1);
            conversationService.updateById(conversation);
        });
        log.info("助理问答完成: conversationId={}, ownerId={}, recentHits={}, relatedHits={}, memories={}, webSearch={}",
                conversationId, userId, context.recentWork().size(), context.related().size(),
                context.memories().size(),
                webSearchContext == null || webSearchContext.isBlank() ? "未启用" : "已注入");
        return new ClarifyConversationDetail(conversation, transcript);
    }

    /**
     * 流式一轮助理问答：前置段（落 user 消息 / 自动压缩）持会话锁串行化，
     * 解锁后跑检索与 LLM 流——与澄清链路同口径，避免「停止生成」时仍占着会话锁。
     */
    private Flux<ChatStreamEvent> runAssistantRound(RequirementConversation conversation, String message,
                                                    Long userId, AssistantRoundInput input) {
        Long conversationId = conversation.getId();
        // 过程数据随消息 payload 落库：done 后/刷新后仍可回看（对齐澄清链路）
        StringBuilder thinking = new StringBuilder();
        List<String> steps = new ArrayList<>();
        // 先发进度帧，再 defer 执行耗时前置与检索（否则用户要等前置跑完才看到任何反馈）
        return Flux.concat(
                Flux.just(ChatStreamEvent.progress("正在检索历史与记忆…")),
                Flux.defer(() -> {
                    RoundSeed seed = roundLocker.runLocked(conversationId,
                            roundLockTtlResolver.effectiveTtlSeconds(),
                            () -> prepareRound(conversation, message, userId));
                    PromptContext context = loadPromptContext(conversationId, message, userId, input, seed);
                    if (!Boolean.TRUE.equals(conversation.getWebSearchEnabled())) {
                        return runStreamMainReply(conversation, context, null, steps, thinking);
                    }
                    AssistantSearchPlan searchPlan = resolveSearchPlan(conversation, message,
                            context.hasAttachments());
                    if (searchPlan.skipSearch()) {
                        String skipStep = "附件内容可回答，本轮跳过联网检索";
                        steps.add(skipStep);
                        return Flux.concat(
                                Flux.just(ChatStreamEvent.progress(skipStep)),
                                runStreamMainReply(conversation, context, null, steps, thinking));
                    }
                    String searchStep = RoundProgressText.searching(message);
                    steps.add(searchStep);
                    return Flux.concat(
                            Flux.just(ChatStreamEvent.progress(searchStep)),
                            Flux.defer(() -> {
                                WebSearchOutcome outcome = doWebSearch(conversationId, message, searchPlan);
                                steps.add(RoundProgressText.searchResult(outcome));
                                return runStreamMainReply(conversation, context, outcome, steps, thinking);
                            }));
                }));
    }

    /** 流式主回复段：检索完成后执行 LLM 流，token/reasoning 增量转发，终态落库并 done。 */
    private Flux<ChatStreamEvent> runStreamMainReply(RequirementConversation conversation,
                                                     PromptContext context, WebSearchOutcome outcome,
                                                     List<String> steps, StringBuilder thinking) {
        Long conversationId = conversation.getId();
        String webSearchContext = outcome == null ? "" : outcome.toContextText();
        String systemPrompt = buildSystemPrompt(context, webSearchContext);
        AgentProfileSnapshot planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        AgentTask task = buildAgentTask(conversationId, context.message(), systemPrompt);
        // 检索完成后补发结果步骤（此时 outcome 已确定），再进主回复流
        Flux<ChatStreamEvent> resultSteps = steps.size() > 1
                ? Flux.just(ChatStreamEvent.progress(steps.get(steps.size() - 1)))
                : Flux.empty();
        StringBuilder buffer = new StringBuilder();
        return Flux.concat(resultSteps,
                platformAgentExecutionService.executeStream(planner.id(), task)
                        .map(chunk -> {
                            // 思考只走 reasoning 帧，不并入正文与落库正文（避免污染最终回复）
                            if (chunk.isReasoning()) {
                                thinking.append(chunk.text());
                                return ChatStreamEvent.reasoning(chunk.text());
                            }
                            buffer.append(chunk.text());
                            return ChatStreamEvent.token(chunk.text());
                        })
                        .concatWith(Flux.defer(() -> {
                            // 停止生成 = 下游 dispose，本 defer 不执行 ⇒ 不落库（user 消息已落库，可重发）
                            persistStreamOutcome(conversationId, buffer.toString(), outcome, thinking, steps);
                            return Flux.just(ChatStreamEvent.done());
                        })));
    }

    /** 流式终态落库：assistant 消息（含 webSearch/process payload）+ round_count 原子自增。 */
    private void persistStreamOutcome(Long conversationId, String output, WebSearchOutcome outcome,
                                      StringBuilder thinking, List<String> steps) {
        String content = output == null ? "" : output.trim();
        String payload = replyParser.buildWebSearchOnlyPayload(outcome,
                new RoundProcess(thinking.toString(), steps));
        transactionTemplate.executeWithoutResult(status -> {
            messageService.addMessage(conversationId, ROLE_ASSISTANT, content, payload);
            // 流式轮不持锁跨 LLM，轮数用库内自增，避免基于锁外快照回写覆盖并发轮次
            conversationService.lambdaUpdate()
                    .setSql("round_count = COALESCE(round_count, 0) + 1")
                    .eq(RequirementConversation::getId, conversationId)
                    .update();
        });
        log.info("助理流式轮完成并落库: conversationId={}, thinkingChars={}, steps={}, webSearch={}",
                conversationId, thinking.length(), steps.size(),
                outcome == null ? "未启用" : "已注入");
    }

    /**
     * 同步前置段：幂等落 user 消息 → 自动压缩 → 取上下文状态与最近对话。
     * 调用方负责加锁（同步整轮 / 流式仅前置段）。
     */
    private RoundSeed prepareRound(RequirementConversation conversation, String message, Long userId) {
        Long conversationId = conversation.getId();
        // 一次查询复用：末条同句 user 消息（上轮失败重试）不重复落库，保证轻量幂等
        List<RequirementMessage> transcript = new ArrayList<>(messageService.listByConversation(conversationId));
        RequirementMessage last = transcript.isEmpty() ? null : transcript.get(transcript.size() - 1);
        if (last == null || !ROLE_USER.equals(last.getRole()) || !message.equals(last.getContent())) {
            transcript.add(messageService.addMessage(conversationId, ROLE_USER, message));
        }

        // PA-12：超过阈值时自动压缩（失败不阻断对话）
        try {
            contextCompactor.compact(conversationId, false);
        } catch (Exception e) {
            log.warn("自动上下文压缩失败（不阻断对话）: conversationId={}, err={}", conversationId, e.getMessage());
        }
        ConversationContextState contextState = contextStateService.getOrInit(conversationId, userId);
        List<String> recentConversation = recentConversationLines(transcript, contextState.getCompactedUntilSeq());
        return new RoundSeed(transcript, contextState, recentConversation);
    }

    /** 轮前检索：画像 / 记忆 / 近期工作 / 相关历史 / 附件 / 技能（不含联网搜索，搜索单独控进度）。 */
    private PromptContext loadPromptContext(Long conversationId, String message, Long userId,
                                            AssistantRoundInput input, RoundSeed seed) {
        AssistantProfileService.AssistantSnapshot profile = assistantProfileService.getActiveByUser(userId);
        List<LongTermMemory> memories = longTermMemoryService.searchForRecall(message, MEMORY_LIMIT);
        List<HistoryHit> recentWork = historySearchService.recentWork(userId, RECENT_LIMIT);
        List<HistoryHit> related = historySearchService.search(userId, message, HIT_LIMIT);
        AttachmentBundle attachment = resolveAttachmentBundle(conversationId, message, input.attachmentIds());
        String skillInstruction = skillDirective.render(input.skill());
        return new PromptContext(message, profile, memories, recentWork, related, seed,
                attachment.context(), attachment.present(), skillInstruction);
    }

    private static String buildSystemPrompt(PromptContext context, String webSearchContext) {
        return AssistantPromptBuilder.build(context.profile(), context.memories(), context.recentWork(),
                context.related(), context.seed().contextState().getSummary(),
                context.seed().recentConversation(), webSearchContext,
                context.attachmentContext(), context.skillInstruction());
    }

    private static AgentTask buildAgentTask(Long conversationId, String message, String systemPrompt) {
        return AgentTask.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(message)
                .context(Map.of("conversationId", conversationId, "scene", "assistant_chat"))
                .requiredCapabilities(Map.of())
                .build();
    }

    /**
     * 联网检索：开关关闭时直接跳过；开启时先做本轮检索决策，LLM 优化词作为优先候选，
     * 规则词兜底。任何失败一律降级为 null（不阻断本轮问答）。
     */
    private WebSearchOutcome doWebSearch(Long conversationId, String message, AssistantSearchPlan plan) {
        try {
            List<String> priority = (plan == null || plan.priorityQuery() == null
                    || plan.priorityQuery().isBlank()) ? null : List.of(plan.priorityQuery());
            return webSearchOrchestrator.doWebSearch(message, priority);
        } catch (Exception e) {
            log.warn("助理联网搜索失败（降级为不注入资料）: conversationId={}, err={}",
                    conversationId, e.getMessage());
            return null;
        }
    }

    private String resolveWebSearchContext(RequirementConversation conversation, String message,
                                           AssistantSearchPlan plan) {
        if (!Boolean.TRUE.equals(conversation.getWebSearchEnabled())) {
            return "";
        }
        if (plan != null && plan.skipSearch()) {
            log.info("助理联网搜索跳过（附件优先）: conversationId={}, attachmentIntent={}",
                    conversation.getId(), plan.attachmentIntent());
            return "";
        }
        WebSearchOutcome outcome = doWebSearch(conversation.getId(), message, plan);
        return outcome == null ? "" : outcome.toContextText();
    }

    /** 本轮检索决策；开关关闭时不调用 LLM，直接返回 disabled（行为与改造前一致）。 */
    private AssistantSearchPlan resolveSearchPlan(RequirementConversation conversation, String message,
                                                  boolean hasAttachments) {
        if (!Boolean.TRUE.equals(conversation.getWebSearchEnabled())) {
            return AssistantSearchPlan.disabledPlan();
        }
        return searchQueryPlanner.plan(conversation, message, hasAttachments);
    }

    /** 流式错误事件文本：取根因 message（BizException 友好信息），缺失时回落类名。 */
    private static String streamErrorMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName() : root.getMessage();
    }

    /** 前置段产物（锁内计算，供后续检索与落库复用）。 */
    private record RoundSeed(List<RequirementMessage> transcript,
                             ConversationContextState contextState,
                             List<String> recentConversation) {
    }

    /** 轮前检索产物（prompt 装配输入）。 */
    private record PromptContext(String message,
                                 AssistantProfileService.AssistantSnapshot profile,
                                 List<LongTermMemory> memories,
                                 List<HistoryHit> recentWork,
                                 List<HistoryHit> related,
                                 RoundSeed seed,
                                 String attachmentContext,
                                 boolean hasAttachments,
                                 String skillInstruction) {
    }

    /** 附件段产物：注入文本 + 会话是否确有附件（后者决定 answerable 能否跳过联网）。 */
    private record AttachmentBundle(String context, boolean present) {
    }

    /**
     * 会话附件段：清单 + 正文一体注入（正文按上下文预算裁剪）。
     *
     * <p>本会话没有附件时不注入任何占位文本——助理的 prompt 以「检索历史 + 记忆」为主，
     * 塞一段「（本轮无附件）」只会稀释注意力。</p>
     *
     * <p>{@code present} 与会话附件存在性同源，供检索决策判断 {@code answerable}
     * 是否真的能跳过联网（渲染失败但附件存在时仍视为有附件）。</p>
     */
    private AttachmentBundle resolveAttachmentBundle(Long conversationId, String message, List<Long> attachmentIds) {
        boolean present;
        try {
            present = !chatAttachmentService.listByConversation(conversationId).isEmpty();
        } catch (Exception e) {
            log.warn("助理附件清单读取失败（降级为无附件）: conversationId={}, err={}",
                    conversationId, e.getMessage());
            return new AttachmentBundle("", false);
        }
        if (!present) {
            return new AttachmentBundle("", false);
        }
        try {
            ChatAttachmentPromptRenderer.Rendered rendered =
                    attachmentPromptRenderer.render(conversationId, message, attachmentIds, null);
            return new AttachmentBundle(rendered.manifest() + "\n" + rendered.context(), true);
        } catch (Exception e) {
            log.warn("助理附件注入失败（降级为不注入附件）: conversationId={}, err={}",
                    conversationId, e.getMessage());
            return new AttachmentBundle("", true);
        }
    }

    /** 首轮失败清理刚建的会话（逻辑删消息 + 会话），避免留下空/半截会话。 */
    private void cleanupCreatedConversation(Long conversationId) {
        try {
            messageService.removeByConversation(conversationId);
            conversationService.removeById(conversationId);
            log.warn("助理会话首轮失败，已清理: conversationId={}", conversationId);
        } catch (Exception cleanupError) {
            log.warn("助理会话首轮失败且清理失败，需人工核验: conversationId={}, err={}",
                    conversationId, cleanupError.getMessage());
        }
    }

    private RequirementConversation requireAssistantConversation(Long conversationId, Long userId) {
        RequirementConversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new BizException("会话不存在: " + conversationId);
        }
        if (!RequirementClarifyService.KIND_ASSISTANT.equals(conversation.getKind())) {
            throw new BizException("非助理会话，不能使用助理对话接口: " + conversationId);
        }
        if (!Objects.equals(conversation.getOwnerId(), userId)) {
            throw new BizException(403, "无权访问该助理会话");
        }
        return conversation;
    }

    private static Long requireUser() {
        Long userId = CurrentUser.userId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        return userId;
    }

    private static String buildSeed(List<RequirementMessage> messages) {
        StringBuilder sb = new StringBuilder("以下是我和助理此前的对话，请据此帮我创建一个任务需求：\n");
        int start = Math.max(0, messages.size() - SEED_MESSAGE_LIMIT);
        for (int i = start; i < messages.size(); i++) {
            RequirementMessage m = messages.get(i);
            sb.append("- ").append(m.getRole()).append("：")
                    .append(truncate(m.getContent(), SEED_SNIPPET_MAX)).append('\n');
        }
        return sb.toString().trim();
    }

    /** 本会话最近对话（排除当前最后一条用户消息；最多 10 条，供 prompt 多轮上下文）。 */
    private static List<String> recentConversationLines(List<RequirementMessage> messages,
                                                        Integer compactedUntilSeq) {
        int cutoff = compactedUntilSeq == null ? 0 : compactedUntilSeq;
        List<RequirementMessage> live = messages.stream()
                .filter(m -> m.getSeq() != null && m.getSeq() > cutoff)
                .sorted(Comparator.comparing(RequirementMessage::getSeq))
                .toList();
        int end = Math.max(0, live.size() - 1);
        int start = Math.max(0, end - 10);
        List<String> lines = new ArrayList<>();
        for (int i = start; i < end; i++) {
            RequirementMessage m = live.get(i);
            lines.add(m.getRole() + "：" + (m.getContent() == null ? "" : m.getContent()));
        }
        return lines;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}