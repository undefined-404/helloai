package com.helloai.core.planner.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.config.SkillExecutionProperties;
import com.helloai.common.constant.SkillExecutionPhase;
import com.helloai.common.constant.SkillExecutionStatus;
import com.helloai.core.planner.clarify.SkillDirective;
import com.helloai.core.planner.entity.ChatSkillRun;
import com.helloai.core.planner.mapper.ChatSkillRunMapper;
import com.helloai.core.planner.service.RequirementMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话内技能执行 worker（SKX-13）：把一次技能执行从 HTTP 线程摘到后台。
 *
 * <p><b>为什么必须有它</b>：技能执行实测一次 377s（LLM 两轮 + 注入 + 三段门禁 + 回读 + 落库），
 * 全程占着 {@code http-nio-*-exec-N} 线程。请求生命周期 = 执行生命周期，
 * 客户端一超时断开，服务端还在跑，于是每次都刷一屏 {@code Broken pipe} ERROR，
 * 且线程池被长任务占满 → 整个应用变慢。前端把超时从 120s 抬到 300s 是止痛，不是解。</p>
 *
 * <p><b>状态推进</b>：{@code PENDING → RUNNING} 是条件更新（{@code markRunning}），
 * 是执行幂等的唯一闸门 —— 重复投递只有第一次能领到。</p>
 *
 * <p><b>失败不抛给调用方</b>：生成失败（模型抽不出输入 / CLI 校验不过）是业务上完全可能的
 * 一种<b>结果</b>，用户要能在对话里看到原因才谈得上改需求重试；抛出去只会变成红色 toast，
 * 前一句需求还留在历史里没解释。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillExecutionWorker {

    /** 失败文案前缀（与同步路径同口径，避免两条路径给用户两种说法）。 */
    private static final String FAIL_PREFIX = "生成失败：";

    /** 失败后的引导语（与同步路径同口径）。 */
    private static final String FAIL_HINT = "\n\n可以补充更具体的描述后重试（例如把节点、状态、事件说清）。";

    private final ChatSkillRunMapper chatSkillRunMapper;
    private final ConversationSkillRunner conversationSkillRunner;
    private final RequirementMessageService messageService;
    private final SkillExecutionProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 异步执行入口（提交后调用）。
     *
     * <p>用独立线程池而非默认 executor：技能执行是长任务，不能与 SSE 长连接抢线程
     * （见 {@link SkillExecutionAsyncConfig}）。</p>
     */
    @Async("skillExecutionExecutor")
    public void executeAsync(Long runId) {
        execute(runId);
    }

    /**
     * 执行主体（worker 主体；测试与运维重跑直接调这里，不经过代理）。
     *
     * @param runId 运行记录 id
     */
    public void execute(Long runId) {
        // 先取记录：领取失败时要拿 conversationId / skillName 才能把失败写进对话
        ChatSkillRun run = chatSkillRunMapper.selectById(runId);
        if (run == null) {
            log.warn("技能执行跳过（记录不存在或已删除）: runId={}", runId);
            return;
        }
        // 幂等闸门：条件更新 PENDING→RUNNING，抢不到说明已被处理或已在执行，直接返回
        int claimed;
        try {
            claimed = chatSkillRunMapper.markRunning(runId);
        } catch (Exception e) {
            // uk_skill_run_active 兜底命中：同会话的两次并发提交都落在 PENDING，
            // 领取时被数据库唯一索引拒掉一条。必须把它收尾成 FAILED —— 留在 PENDING
            // 会永久占住本会话的互斥额度，之后每次提交都被「已有技能在执行」挡回去。
            log.warn("技能执行领取被拒（同会话并发）: runId={}, conversationId={}, err={}",
                    runId, run.getConversationId(), e.getMessage());
            handleFailure(runId, run.getConversationId(), run.getSkillName(),
                    "本会话已有技能在执行，请等它结束再试", System.currentTimeMillis());
            return;
        }
        if (claimed != 1) {
            log.debug("技能执行跳过（非 PENDING 或已被领取）: runId={}", runId);
            return;
        }
        Long conversationId = run.getConversationId();
        String skillName = run.getSkillName();
        // 需求正文：执行时从会话里取「最近一条带技能的 user 消息」——
        // 运行记录里不重复存正文（正文会随消息编辑而变，存两处必然漂移）。
        String need = latestSkillNeed(conversationId, skillName);
        if (need == null) {
            chatSkillRunMapper.markFailed(runId, "找不到对应的技能请求内容（可能已被编辑）");
            log.warn("技能执行失败（找不到需求正文）: runId={}, conversationId={}, skill={}",
                    runId, conversationId, skillName);
            return;
        }
        long startedAt = System.currentTimeMillis();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<String> abortReason = new AtomicReference<>();
        try {
            ConversationSkillRunner.Result result = conversationSkillRunner.run(
                    conversationId, null, run.getUserId(), skillName, need,
                    new RunControl(runId, startedAt, cancelled, abortReason));
            if (abortReason.get() != null) {
                // 已被取消或超时：不再落成功消息（取消的语义是「这次不产出」）
                finishAborted(runId, conversationId, skillName, cancelled.get(), abortReason.get(), startedAt);
                return;
            }
            String content = successContent(result);
            messageService.addMessage(conversationId, ClarifyRoles.ASSISTANT, content,
                    conversationSkillRunner.payloadOf(result));
            chatSkillRunMapper.markSucceeded(runId, result.attachmentId(), conversationSkillRunner.payloadOf(result));
            log.info("对话内技能执行完成: runId={}, conversationId={}, skill={}, chartType={}, quality={}, "
                            + "degraded={}, attachmentId={}, costMs={}",
                    runId, conversationId, skillName, result.chartType(), result.qualityProfile(),
                    result.degraded(), result.attachmentId(), System.currentTimeMillis() - startedAt);
        } catch (BizException e) {
            if (abortReason.get() != null) {
                // 中止不是「失败」：取消不落对话消息（用户自己叫停的，不必再报一次失败）；
                // 超时要落（用户需要知道为什么没有产物）。
                finishAborted(runId, conversationId, skillName, cancelled.get(), abortReason.get(), startedAt);
                return;
            }
            handleFailure(runId, conversationId, skillName, e.getMessage(), startedAt);
        } catch (Exception e) {
            log.error("技能执行异常: runId={}, conversationId={}, skill={}", runId, conversationId, skillName, e);
            handleFailure(runId, conversationId, skillName, "内部错误：" + e.getMessage(), startedAt);
        }
    }

    /**
     * 中止收尾：用户取消 ⇒ 置 CANCELLED 且<b>不</b>落对话消息；
     * 超时 ⇒ 置 FAILED 并落一条说明（用户需要知道为什么没有产物）。
     */
    private void finishAborted(Long runId, Long conversationId, String skillName,
                               boolean cancelled, String reason, long startedAt) {
        if (cancelled) {
            chatSkillRunMapper.markCancelled(runId);
            log.info("对话内技能执行已取消: runId={}, conversationId={}, skill={}, costMs={}",
                    runId, conversationId, skillName, System.currentTimeMillis() - startedAt);
            return;
        }
        log.warn("对话内技能执行超时: runId={}, conversationId={}, skill={}, reason={}, costMs={}",
                runId, conversationId, skillName, reason, System.currentTimeMillis() - startedAt);
        messageService.addMessage(conversationId, ClarifyRoles.ASSISTANT, FAIL_PREFIX + reason + FAIL_HINT);
        chatSkillRunMapper.markFailed(runId, truncate(reason));
    }

    /** 失败落库：对话里一条失败消息 + 运行记录置 FAILED。 */
    private void handleFailure(Long runId, Long conversationId, String skillName, String reason, long startedAt) {
        String message = reason == null || reason.isBlank() ? "未知原因" : reason;
        log.warn("对话内技能执行失败: runId={}, conversationId={}, skill={}, reason={}, costMs={}",
                runId, conversationId, skillName, message, System.currentTimeMillis() - startedAt);
        messageService.addMessage(conversationId, ClarifyRoles.ASSISTANT, FAIL_PREFIX + message + FAIL_HINT);
        chatSkillRunMapper.markFailed(runId, truncate(message));
    }

    /** 成功文案（与同步路径同口径，含降档留痕）。 */
    private static String successContent(ConversationSkillRunner.Result result) {
        String content = "已生成 `" + result.chartType() + "` 图，产物见下方附件（"
                + readableSize(result.fileSize()) + "）。";
        if (result.degraded()) {
            String reason = result.degradeReason() == null ? "" : result.degradeReason();
            content += "\n\n> 严格布局门未通过，已用**兼容布局**产出（"
                    + (reason.length() > 120 ? reason.substring(0, 120) + "…" : reason)
                    + "）。图可用，但建议人工核对节点重叠与连线走向。";
        }
        return content;
    }

    /** 人类可读的文件大小（消息正文用；精确字节数在 payload 里）。 */
    private static String readableSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
    }

    private String truncate(String message) {
        int limit = Math.max(1, properties.getErrorMaxLength());
        return message.length() > limit ? message.substring(0, limit) + "…" : message;
    }

    /**
     * 取会话里最近一条「带该技能的 user 消息」正文。
     *
     * <p>为什么不在运行记录里存正文：正文会随消息编辑而变，两处存必然漂移；
     * 而编辑重发本身会重新提交一次执行记录，正文按「最近一条」取即可。</p>
     */
    private String latestSkillNeed(Long conversationId, String skillName) {
        var messages = messageService.listByConversation(conversationId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            if (!"user".equals(message.getRole())) {
                continue;
            }
            if (skillName.equals(skillOf(message.getPayload()))) {
                return message.getContent();
            }
        }
        return null;
    }

    /** 从消息 payload 里取技能名（payload 形如 {@code {"skill":"archify"}}）；解析不出返回 null。 */
    private String skillOf(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(payload).path(SkillDirective.PAYLOAD_KEY).asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 运行期控制实现：阶段写回运行记录 + 阶段边界自查取消/超时。
     *
     * <p>自查时机只在阶段边界（每个阶段一次查库），不在命令执行中间 ——
     * 中断语义由沙箱 SDK 决定，强行打断会引入不可控路径。</p>
     */
    private final class RunControl implements SkillRunControl {

        private final Long runId;
        private final long startedAt;
        private final AtomicBoolean cancelled;
        private final AtomicReference<String> abortReason;

        RunControl(Long runId, long startedAt, AtomicBoolean cancelled, AtomicReference<String> abortReason) {
            this.runId = runId;
            this.startedAt = startedAt;
            this.cancelled = cancelled;
            this.abortReason = abortReason;
        }

        @Override
        public void onPhase(SkillExecutionPhase phase) {
            chatSkillRunMapper.markPhase(runId, phase.name(), phase.label());
            log.info("技能执行阶段推进: runId={}, phase={}", runId, phase);
        }

        @Override
        public boolean shouldAbort() {
            ChatSkillRun current = chatSkillRunMapper.selectById(runId);
            if (current != null && SkillExecutionStatus.CANCELLED.name().equals(current.getStatus())) {
                cancelled.set(true);
                abortReason.set("已取消");
                return true;
            }
            int timeout = current != null && current.getTimeoutSeconds() != null
                    ? current.getTimeoutSeconds() : properties.getTimeoutSeconds();
            if (System.currentTimeMillis() - startedAt > timeout * 1000L) {
                abortReason.set("超过服务端时限（" + timeout + " 秒）");
                log.warn("技能执行超时: runId={}, timeoutSeconds={}", runId, timeout);
                return true;
            }
            return false;
        }
    }

    /**
     * 消息角色常量（与 {@code ClarifyCommandSearchServiceImpl} 同口径，避免跨类引用其私有常量）。
     */
    private static final class ClarifyRoles {
        static final String ASSISTANT = "assistant";
    }
}
