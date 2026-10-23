package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.api.dto.requirement.ClarifyMessageRequest;
import com.helloai.common.base.R;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementClarifyService.ChatStreamEvent;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyConversationDetail;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.task.port.TaskView;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.Disposable;

/**
 * 对话式需求澄清（薄转发，编排收口在 {@link RequirementClarifyService}）。
 */
@Slf4j
@RestController
@RequestMapping("/api/requirement-conversations")
public class RequirementConversationController {

    private final RequirementClarifyService requirementClarifyService;
    private final Executor chatStreamExecutor;

    /**
     * 手写构造器而非 {@code @RequiredArgsConstructor}：
     * 字段上的 {@code @Qualifier} <b>不会</b>被 Lombok 复制到构造参数（项目未配置
     * {@code lombok.config} 的 {@code copyableAnnotations}），容器里出现第二个
     * {@code ThreadPoolTaskExecutor} 时会退化成纯按类型注入而报
     * 「required a single bean, but 2 were found」。
     * 把 {@code @Qualifier} 直接写在构造参数上才真正生效。
     *
     * <p>注入接口 {@link Executor} 而非具体类：本处只用到 {@code execute()}，
     * 具体类型见 {@code ChatStreamConfig} 的说明（`CODE_STYLE` §7.4.2）。</p>
     */
    public RequirementConversationController(
            RequirementClarifyService requirementClarifyService,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.requirementClarifyService = requirementClarifyService;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    /** SseEmitter 显式超时（与前端 120s 对话超时档位对齐，超时后连接自动关闭）。 */
    private static final long STREAM_EMITTER_TIMEOUT_MS = 120_000L;

    /** Chat SSE 流式发送（S1 最小闭环）：快速建立连接 → 线程池内执行
     *  「决策/搜索同步前置 + 主回复 token 流」，事件协议 token/done/error。
     *
     *  <p><b>停止生成（2026-10-19）</b>：客户端主动中断（前端「停止」按钮 abort 连接）时，
     *  SseEmitter 触发 onCompletion/onError → dispose LLM 流订阅。Reactor 取消沿 Flux 链向上游
     *  传播（executeStream → generateStream → Spring AI stream），真实 LLM 调用被中断、
     *  未完成的回复<b>不落库</b>（user 消息已在前置段落库，前端刷新详情后走重试条续跑）。</p>
     */
    @SaCheckPermission("conversation:send")
    @PostMapping(value = "/streamSendById/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSendById(@PathVariable("id") Long id,
                                     @Valid @RequestBody ClarifyMessageRequest req,
                                     HttpServletResponse response) {
        // 反代缓冲关闭（Nginx 需逐帧透传，X-Accel-Buffering 对 1.7.11+ 生效）：SSE 帧不被中间层攒批
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(STREAM_EMITTER_TIMEOUT_MS);
        // ★停止生成核心：保存订阅句柄，连接断开（客户端 abort / 超时 / 异常）时 dispose 取消 LLM 流。
        // 正常 done 完成路径 completeQuietly 后 onCompletion 也会触发 dispose——此时订阅已结束，
        // dispose 为幂等 no-op，无副作用。
        // cancelled 标记覆盖「连接在订阅创建前就断开」的竞态（chatStreamExecutor 异步执行）：
        // 断开已发生则不再启动订阅，避免拉起一条注定无人接收的 LLM 流。
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Disposable[] subscription = new Disposable[1];
        Runnable cancelStream = () -> {
            cancelled.set(true);
            Disposable d = subscription[0];
            if (d != null) {
                d.dispose();
            }
        };
        emitter.onCompletion(cancelStream);
        emitter.onTimeout(() -> {
            log.warn("流式对话连接超时: conversationId={}", id);
            cancelStream.run();
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // 连接可能已断，忽略二次异常
            }
        });
        emitter.onError(e -> {
            log.warn("流式对话连接异常: conversationId={}, err={}", id, e.toString());
            cancelStream.run();
        });
        StreamFrameAggregator aggregator = new StreamFrameAggregator(emitter);
        chatStreamExecutor.execute(() -> {
            if (cancelled.get()) {
                // 连接已在订阅启动前断开：不再启动流（user 消息未落库，前端重发即可）
                return;
            }
            subscription[0] = requirementClarifyService.streamRound(id, req.getMessage(), req.getSelectedOptions(),
                            req.getAttachmentIds())
                    .subscribe(aggregator::onEvent,
                            // 服务层已将异常转 error 事件（onErrorResume），此处为订阅侧防御
                            error -> aggregator.onEvent(ChatStreamEvent.error(fallbackErrorMessage(error))),
                            () -> {
                                aggregator.flush();
                                completeQuietly(emitter);
                            });
        });
        return emitter;
    }

    /**
     * Chat SSE 流式「新建会话 + 首轮」（2026-10-18）：
     *
     * <p><b>为什么需要</b>：前端新会话首条消息此前走同步 {@code POST /requirement-conversations}，
     * 该接口要跑完「意图决策 + 联网搜索（实测 21.5s）+ 完整 LLM 生成」才一次性返回——用户只看到
     * 「思考中」占位，没有打字机也没有思考过程。本端点把「建会话」与「跑首轮」解耦后，
     * 新会话与老会话走同一条流式通道。</p>
     *
     * <p>事件序：首帧 {@code session}（data=会话 id，前端据此在 done 后拉 detail）→
     * {@code reasoning}/{@code token} 增量 → {@code done}/{@code error}。
     *
     * <p>与 {@link #streamSendById} 同支持「停止生成」：客户端中断连接时 dispose 订阅，
     * 取消 LLM 流且不落库（建会话与 user 消息已落库，前端可用重试条续跑）。</p>
     */
    @SaCheckPermission("conversation:add")
    @PostMapping(value = "/streamCreate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCreate(@Valid @RequestBody ClarifyMessageRequest req,
                                   HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(STREAM_EMITTER_TIMEOUT_MS);
        // 停止生成 + 订阅前断开竞态守卫（语义与 streamSendById 一致）
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Disposable[] subscription = new Disposable[1];
        Runnable cancelStream = () -> {
            cancelled.set(true);
            Disposable d = subscription[0];
            if (d != null) {
                d.dispose();
            }
        };
        emitter.onCompletion(cancelStream);
        emitter.onTimeout(() -> {
            log.warn("流式新建会话连接超时");
            cancelStream.run();
            completeQuietly(emitter);
        });
        emitter.onError(e -> {
            log.warn("流式新建会话连接异常: {}", e.toString());
            cancelStream.run();
        });
        StreamFrameAggregator aggregator = new StreamFrameAggregator(emitter);
        chatStreamExecutor.execute(() -> {
            if (cancelled.get()) {
                // 连接已在订阅启动前断开：不再启动流（会话与消息未落库，前端重发即可）
                return;
            }
            subscription[0] = requirementClarifyService.streamCreateWithClientToken(req.getMessage(),
                            req.getPlannerAgentId(), req.getWebSearchEnabled(), req.getClientToken(),
                            req.getAttachmentIds())
                    .subscribe(aggregator::onEvent,
                            error -> aggregator.onEvent(ChatStreamEvent.error(fallbackErrorMessage(error))),
                            () -> {
                                aggregator.flush();
                                completeQuietly(emitter);
                            });
        });
        return emitter;
    }

    /** 订阅侧防御错误文本（正常路径不会触发）：取根因 message，缺失回落类名。 */
    private static String fallbackErrorMessage(Throwable e) {        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignore) {
            // 连接已不可用，忽略
        }
    }

    /**
     * SseEmitter 帧聚合器：token 增量按「长度 ≥ 50 字符或距上次发送 ≥ 100ms」聚合发射，
     * 防止真实 provider 高频小帧打爆连接；done/error 终帧前先 flush 剩余 token。
     * 连接断开后置 dead 静默停止发送（2026-10-19 起连接断开由外层 dispose 订阅，
     * LLM 流被取消不再跑完、不落库）。
     */
    private static final class StreamFrameAggregator {

        private static final int FLUSH_CHAR_THRESHOLD = 50;
        private static final long FLUSH_INTERVAL_MS = 100L;

        private final SseEmitter emitter;
        private final StringBuilder pending = new StringBuilder();
        /** 思考过程独立缓冲（2026-10-18）：高频小帧若不聚合会打爆连接，且与正文缓冲分开
         *  才能保证两条流各自的顺序（前端分区渲染，思考在前、正文在正文区）。 */
        private final StringBuilder pendingReasoning = new StringBuilder();
        private long lastSendAt;
        private boolean alive = true;

        StreamFrameAggregator(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void onEvent(ChatStreamEvent event) {
            if (event.type() == ChatStreamEvent.Type.TOKEN) {
                pending.append(event.data());
                if (shouldFlush(pending)) {
                    flushContent();
                }
            } else if (event.type() == ChatStreamEvent.Type.REASONING) {
                pendingReasoning.append(event.data());
                if (shouldFlush(pendingReasoning)) {
                    flushReasoning();
                }
            } else {
                flush();
                send(event.type().name().toLowerCase(), event.data());
            }
        }

        /** 聚合判据：缓冲够长或距上次发送够久（两个通道共用同一口径，避免各写一套）。 */
        private boolean shouldFlush(StringBuilder buffer) {
            return buffer.length() >= FLUSH_CHAR_THRESHOLD
                    || System.currentTimeMillis() - lastSendAt >= FLUSH_INTERVAL_MS;
        }

        void flush() {
            flushReasoning();
            flushContent();
        }

        private void flushContent() {
            if (pending.length() > 0) {
                send("token", pending.toString());
                pending.setLength(0);
            }
        }

        private void flushReasoning() {
            if (pendingReasoning.length() > 0) {
                send("reasoning", pendingReasoning.toString());
                pendingReasoning.setLength(0);
            }
        }

        private void send(String eventName, String data) {
            if (!alive) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
                lastSendAt = System.currentTimeMillis();
            } catch (Exception e) {
                alive = false;
            }
        }
    }

    /** 新建澄清会话（首条用户消息触发一轮 LLM；可选手动指定 Planner；可带联网搜索开关；新会话始终 CHAT 模式）。 */
    @SaCheckPermission("conversation:add")
    @PostMapping
    public R<ClarifyConversationDetail> create(@Valid @RequestBody ClarifyMessageRequest req) {
        // 联网搜索开关透传：NULL 走默认开启语义（与老数据兼容）；
        // initialMode 已废弃，新会话始终 CHAT 模式
        return R.ok(requirementClarifyService.createWithClientToken(
                req.getMessage(), req.getPlannerAgentId(), req.getWebSearchEnabled(), req.getClientToken(),
                            req.getAttachmentIds()));
    }

    /** Planner 下拉选数据源（平台内 PLANNER 可选 + 在班外部 Agent 置灰）。 */
    @GetMapping("/listPlannerOptions")
    public R<List<PlannerAgentPicker.PlannerOption>> listPlannerOptions() {
        return R.ok(requirementClarifyService.listPlannerOptions());
    }

    /** 追加一条用户消息并走一轮 LLM 澄清（可附结构化选项回答快照）。 */
    @SaCheckPermission("conversation:send")
    @PostMapping("/sendMessageById/{id}")
    public R<ClarifyConversationDetail> sendMessageById(@PathVariable("id") Long id,
                                                    @Valid @RequestBody ClarifyMessageRequest req) {
        return R.ok(requirementClarifyService.sendMessage(id, req.getMessage(), req.getSelectedOptions()));
    }

    /** 重试上一轮 LLM（仅当最后一条是用户消息，即上轮 LLM 失败时可用）。 */
    @SaCheckPermission("conversation:retry")
    @PostMapping("/retryById/{id}")
    public R<ClarifyConversationDetail> retryById(@PathVariable("id") Long id) {
        return R.ok(requirementClarifyService.retryRound(id));
    }

    /**
     * 编辑已发送的用户消息并重跑（2026-10-19，对齐 ChatGPT/DeepSeek/Kimi「编辑消息」交互）。
     *
     * <p>事务内：物理删除目标消息之后的旧回复 → 更新内容 → 从该点重跑一轮 LLM。
     * 仅支持编辑最后一条 user 消息；更早消息返回业务错误。
     * 权限复用 {@code conversation:retry}（编辑=改消息+重跑，与重试同权，避免新增 RBAC 码）。</p>
     */
    @SaCheckPermission("conversation:retry")
    @PostMapping("/editMessageById/{id}/{messageId}")
    public R<ClarifyConversationDetail> editMessage(@PathVariable("id") Long id,
                                                    @PathVariable("messageId") Long messageId,
                                                    @RequestBody ClarifyMessageRequest req) {
        String newContent = req != null ? req.getMessage() : null;
        return R.ok(requirementClarifyService.editMessage(id, messageId, newContent));
    }

    /** 切换到方案澄清模式：置位落库 + 一轮 LLM 基于全量历史产终稿草案/结构化追问；
     *  支持可选 body.message（斜杠命令 /planner 附加文本，先落库进上下文再切）。 */
    @SaCheckPermission("conversation:mode")
    @PostMapping("/toClarifyById/{id}")
    public R<ClarifyConversationDetail> toClarify(@PathVariable("id") Long id,
                                                  @RequestBody(required = false) ClarifyMessageRequest req) {
        String extraMessage = req != null ? req.getMessage() : null;
        return R.ok(requirementClarifyService.switchToClarify(id, extraMessage));
    }

    /** 切回自由对话模式：仅置位，不调用 LLM。 */
    @SaCheckPermission("conversation:mode")
    @PostMapping("/toChatById/{id}")
    public R<ClarifyConversationDetail> toChat(@PathVariable("id") Long id) {
        return R.ok(requirementClarifyService.switchToChat(id));
    }

    /** 会话列表（按创建时间倒序，LIMIT 50）。 */
    @GetMapping
    public R<List<RequirementConversation>> list() {
        return R.ok(requirementClarifyService.listConversations());
    }

    /** 会话详情（含全部消息按 seq 升序）。 */
    @GetMapping("/getById/{id}")
    public R<ClarifyConversationDetail> getById(@PathVariable("id") Long id) {
        return R.ok(requirementClarifyService.detail(id));
    }

    /** 终稿确认：创建任务并回填会话。 */
    @SaCheckPermission("conversation:finalize")
    @PostMapping("/finalizeById/{id}")
    public R<TaskView> finalizeById(@PathVariable("id") Long id) {
        return R.ok(requirementClarifyService.finalize(id));
    }

    /** 重新生成：FINALIZED 会话原任务已删除时，复用终稿重建任务并回填。 */
    @SaCheckPermission("conversation:regenerate")
    @PostMapping("/regenerateById/{id}")
    public R<TaskView> regenerateById(@PathVariable("id") Long id) {
        return R.ok(requirementClarifyService.regenerate(id));
    }

    /** 放弃会话。 */
    @SaCheckPermission("conversation:abandon")
    @PostMapping("/abandonById/{id}")
    public R<Void> abandonById(@PathVariable("id") Long id) {
        requirementClarifyService.abandon(id);
        return R.ok(null);
    }

    /** 删除已放弃会话：仅 ABANDONED 可删（软删，列表自动隐藏；ACTIVE/FINALIZED 拒绝）。 */
    @SaCheckPermission("conversation:delete")
    @PostMapping("/deleteById/{id}")
    public R<Void> deleteById(@PathVariable("id") Long id) {
        requirementClarifyService.delete(id);
        return R.ok(null);
    }
}
