package com.helloai.api.controller;

import com.helloai.api.dto.requirement.ClarifyMessageRequest;
import com.helloai.common.base.R;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementClarifyService.ChatStreamEvent;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyConversationDetail;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.task.entity.Task;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 对话式需求澄清（薄转发，编排收口在 {@link RequirementClarifyService}）。
 */
@Slf4j
@RestController
@RequestMapping("/api/requirement-conversations")
@RequiredArgsConstructor
public class RequirementConversationController {

    private final RequirementClarifyService requirementClarifyService;
    private final @Qualifier("chatStreamExecutor") ThreadPoolTaskExecutor chatStreamExecutor;

    /** SseEmitter 显式超时（与前端 120s 对话超时档位对齐，超时后连接自动关闭）。 */
    private static final long STREAM_EMITTER_TIMEOUT_MS = 120_000L;

    /** Chat SSE 流式发送（S1 最小闭环）：快速建立连接 → 线程池内执行
     *  「决策/搜索同步前置 + 主回复 token 流」，事件协议 token/done/error。 */
    @PostMapping(value = "/streamSendById/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSendById(@PathVariable("id") Long id,
                                     @Valid @RequestBody ClarifyMessageRequest req,
                                     HttpServletResponse response) {
        // 反代缓冲关闭（Nginx 需逐帧透传，X-Accel-Buffering 对 1.7.11+ 生效）：SSE 帧不被中间层攒批
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(STREAM_EMITTER_TIMEOUT_MS);
        emitter.onTimeout(() -> {
            log.warn("流式对话连接超时: conversationId={}", id);
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // 连接可能已断，忽略二次异常
            }
        });
        emitter.onError(e -> log.warn("流式对话连接异常: conversationId={}, err={}", id, e.toString()));
        StreamFrameAggregator aggregator = new StreamFrameAggregator(emitter);
        chatStreamExecutor.execute(() -> {
            requirementClarifyService.streamRound(id, req.getMessage(), req.getSelectedOptions())
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

    /** 订阅侧防御错误文本（正常路径不会触发）：取根因 message，缺失回落类名。 */
    private static String fallbackErrorMessage(Throwable e) {
        Throwable root = e;
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
     * 连接断开后置 dead 静默停止发送（LLM 流照常跑完并落库，前端刷新后可见）。
     */
    private static final class StreamFrameAggregator {

        private static final int FLUSH_CHAR_THRESHOLD = 50;
        private static final long FLUSH_INTERVAL_MS = 100L;

        private final SseEmitter emitter;
        private final StringBuilder pending = new StringBuilder();
        private long lastSendAt;
        private boolean alive = true;

        StreamFrameAggregator(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void onEvent(ChatStreamEvent event) {
            if (event.type() == ChatStreamEvent.Type.TOKEN) {
                pending.append(event.data());
                long now = System.currentTimeMillis();
                if (pending.length() >= FLUSH_CHAR_THRESHOLD
                        || now - lastSendAt >= FLUSH_INTERVAL_MS) {
                    flush();
                }
            } else {
                flush();
                send(event.type().name().toLowerCase(), event.data());
            }
        }

        void flush() {
            if (pending.length() > 0) {
                send("token", pending.toString());
                pending.setLength(0);
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
    @PostMapping
    public R<ClarifyConversationDetail> create(@Valid @RequestBody ClarifyMessageRequest req) {
        // 联网搜索开关透传：NULL 走默认开启语义（与老数据兼容）；
        // initialMode 已废弃，新会话始终 CHAT 模式
        return R.ok(requirementClarifyService.create(
                req.getMessage(), req.getPlannerAgentId(), req.getWebSearchEnabled()));
    }

    /** Planner 下拉选数据源（平台内 PLANNER 可选 + 在班外部 Agent 置灰）。 */
    @GetMapping("/listPlannerOptions")
    public R<List<PlannerAgentPicker.PlannerOption>> listPlannerOptions() {
        return R.ok(requirementClarifyService.listPlannerOptions());
    }

    /** 追加一条用户消息并走一轮 LLM 澄清（可附结构化选项回答快照）。 */
    @PostMapping("/sendMessageById/{id}")
    public R<ClarifyConversationDetail> sendMessageById(@PathVariable("id") Long id,
                                                    @Valid @RequestBody ClarifyMessageRequest req) {
        return R.ok(requirementClarifyService.sendMessage(id, req.getMessage(), req.getSelectedOptions()));
    }

    /** 重试上一轮 LLM（仅当最后一条是用户消息，即上轮 LLM 失败时可用）。 */
    @PostMapping("/retryById/{id}")
    public R<ClarifyConversationDetail> retryById(@PathVariable("id") Long id) {
        return R.ok(requirementClarifyService.retryRound(id));
    }

    /** 切换到方案澄清模式：置位落库 + 一轮 LLM 基于全量历史产终稿草案/结构化追问；
     *  支持可选 body.message（斜杠命令 /planner 附加文本，先落库进上下文再切）。 */
    @PostMapping("/toClarifyById/{id}")
    public R<ClarifyConversationDetail> toClarify(@PathVariable("id") Long id,
                                                  @RequestBody(required = false) ClarifyMessageRequest req) {
        String extraMessage = req != null ? req.getMessage() : null;
        return R.ok(requirementClarifyService.switchToClarify(id, extraMessage));
    }

    /** 切回自由对话模式：仅置位，不调用 LLM。 */
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
    @PostMapping("/finalizeById/{id}")
    public R<Task> finalizeById(@PathVariable("id") Long id) {
        return R.ok(requirementClarifyService.finalize(id));
    }

    /** 重新生成：FINALIZED 会话原任务已删除时，复用终稿重建任务并回填。 */
    @PostMapping("/regenerateById/{id}")
    public R<Task> regenerateById(@PathVariable("id") Long id) {
        return R.ok(requirementClarifyService.regenerate(id));
    }

    /** 放弃会话。 */
    @PostMapping("/abandonById/{id}")
    public R<Void> abandonById(@PathVariable("id") Long id) {
        requirementClarifyService.abandon(id);
        return R.ok(null);
    }

    /** 删除已放弃会话：仅 ABANDONED 可删（软删，列表自动隐藏；ACTIVE/FINALIZED 拒绝）。 */
    @PostMapping("/deleteById/{id}")
    public R<Void> deleteById(@PathVariable("id") Long id) {
        requirementClarifyService.delete(id);
        return R.ok(null);
    }
}
