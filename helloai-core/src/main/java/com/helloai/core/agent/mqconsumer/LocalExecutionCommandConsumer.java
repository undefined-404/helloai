package com.helloai.core.agent.mqconsumer;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.event.AgentEventContextResolver;
import com.helloai.core.agent.runtime.AgentContext;
import com.helloai.core.agent.runtime.AgentExecutionResult;
import com.helloai.core.agent.runtime.AgentRuntime;
import com.helloai.core.agent.runtime.ExecutionEnvironment;
import com.helloai.core.agent.runtime.ExecutionEnvironmentProvider;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.event.ExecutionCommandCreatedEvent;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentMcpServerService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.port.SubTaskCommandPort;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.agent.service.AgentRuntimeContextAssembler;
import com.helloai.core.shared.util.DeliverableDraft;
import com.helloai.core.shared.util.VerificationFence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台内执行命令消费者。
 *
 * <p>{@link #consume(ExecutionCommand)} 是与命令来源无关的实际消费入口，供 DB Poller 直接调用；
 * {@link #onCommandCreated(ExecutionCommandCreatedEvent)} 仅作为 EVENT / BOTH 模式的本地事务事件适配器。
 * POLLER 模式下 {@code ExecutionCommandService} 不发布事件，因此不会进入事件适配器，
 * 但本 Bean 必须保留，供 Poller 完成实际消费。</p>
 *
 * <p>G-002 单轨（2026-09-30）：旧链（executeCommand / executeOnce / TurnLlmCaller）与双轨路由
 * （RuntimeAgentRuntimeRouter / LegacyExecutorAdapter）已删除，本类为唯一执行入口，分层编排：</p>
 * <ol>
 *     <li>加载 subTask / agent，做一致性校验</li>
 *     <li>{@link AgentExecutionRecordService#markRunning} CAS 执行记录 PENDING→RUNNING
 *         （<b>先占执行记录</b>；双消费下输家在此被拒即软跳过，2026-10-05 修 804 假阻塞）</li>
 *     <li>{@link SubTaskCommandPort#startIfNeeded} 幂等状态推进（ASSIGNED / REWORK / PAUSED → IN_PROGRESS）</li>
 *     <li>记录消费阶段 timeline（route 观察点，route=agent_runtime）</li>
 *     <li>{@link AgentRuntimeContextAssembler#assemble} 装配真身上下文（prompt / chatModel / 会话）</li>
 *     <li>{@link AgentRuntime#execute} 真身执行（唯一实现 RuntimeTurnExecutor）+ {@link AgentRuntimeContextAssembler#afterTurn} 会话推进</li>
 *     <li>{@link AgentExecutionRecordService#markSuccess} / {@link AgentExecutionRecordService#markFailed} CAS 执行记录</li>
 *     <li>{@link ExecutionResultHandler#handleSuccess} / {@link ExecutionResultHandler#handleFailure} 结果回写</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalExecutionCommandConsumer implements ExecutionCommandConsumer {

    private final AgentExecutionRecordService agentExecutionRecordService;
    private final TaskTimelinePort taskTimelinePort;
    /** 子任务只读端口（原 {@code SubTaskService}；W11 端口化，读快照 {@link SubTaskSnapshot}）。 */
    private final SubTaskQueryPort subTaskQueryPort;
    private final AgentService agentService;
    /** 启用工具为 agent 域数据（agent_mcp_server），消费侧 agent 域内直读注入 ctx.tools。 */
    private final AgentMcpServerService agentMcpServerService;
    /** 执行环境为 agent 域数据（agent.accessType），消费侧 agent 域内解析注入 ctx.environment。 */
    private final ExecutionEnvironmentProvider executionEnvironmentProvider;
    /** G-002 单轨：状态推进（agent 域端口的不透明命令入口，判定在 task 域）。 */
    private final SubTaskCommandPort subTaskCommandPort;
    /** G-002 单轨：Runtime 真身上下文装配（prompt / chatModel / 会话；事件骨架归真身防双写）。 */
    private final AgentRuntimeContextAssembler contextAssembler;
    /** G-002 单轨：结果回写（成功 submit → REVIEW / 失败 block，含 Task Running Spec 回填与物化）。 */
    private final ExecutionResultHandler executionResultHandler;

    /**
     * Runtime 实现列表（G-002 单轨）：唯一实现为 {@code RuntimeTurnExecutor}（Router / Legacy
     * 适配器已删除）；列表为空仅发生在装配异常，防御跳过。
     */
    private final List<AgentRuntime> agentRuntimes;

    /**
     * 沙箱执行通道（`ADR-005`，2026-10-25）：仅用于 turn 结束时释放复用工作沙箱。
     *
     * <p>放在命令消费边界而非 Runtime 内部，是因为「一次消费 = 一次执行尝试」正是
     * 「工作区该不该保留」的天然分界：同一次尝试内的多轮工具调用共享沙箱（文件跨调用保留），
     * 尝试结束即释放（防上一次尝试的残留文件影响下一次尝试）。</p>
     */
    private final SandboxExecutionService sandboxExecutionService;

    @Async("executionCommandExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCreated(ExecutionCommandCreatedEvent event) {
        consume(event.getCommand());
    }

    @Override
    public void consume(ExecutionCommand command) {
        if (command == null) {
            log.warn("执行命令消费跳过：command 为空");
            return;
        }
        if (command.getSubTaskId() == null) {
            log.warn("执行命令消费跳过：subTaskId 为空");
            return;
        }
        if (command.getAgentId() == null) {
            log.warn("执行命令消费跳过：agentId 为空");
            return;
        }

        // 1. 加载 subTask + agent，做一致性校验（快照只读，零 task 实体泄漏）
        SubTaskSnapshot subTask = subTaskQueryPort.findById(command.getSubTaskId());
        if (subTask == null) {
            log.warn("执行命令消费跳过：subTask 不存在 subTaskId={}", command.getSubTaskId());
            return;
        }
        if (subTask.assignedAgentId() == null) {
            log.warn("执行命令消费跳过：subTask 未分配 Agent subTaskId={}", command.getSubTaskId());
            return;
        }
        if (!command.getAgentId().equals(subTask.assignedAgentId())) {
            log.warn("执行命令消费跳过：command.agentId={} 与 subTask.assignedAgent={} 不匹配",
                    command.getAgentId(), subTask.assignedAgentId());
            return;
        }
        Agent agent = agentService.getById(subTask.assignedAgentId());
        if (agent == null) {
            log.warn("执行命令消费跳过：Agent 不存在 agentId={}", subTask.assignedAgentId());
            return;
        }

        // G-002 单轨：消费统一经 Runtime 真身（唯一 AgentRuntime 实现 RuntimeTurnExecutor）。
        // 一致性校验已通过；状态推进 / 装配 / 执行 / 回写在本层分层编排（见 runViaRuntime）。
        if (agentRuntimes == null || agentRuntimes.isEmpty()) {
            log.error("无 AgentRuntime 实现，执行被跳过（单轨下理论不可达）: subTaskId={}",
                    command.getSubTaskId());
            return;
        }
        runViaRuntime(command, subTask, agent);
    }

    /**
     * Runtime 真身路径执行（G-002 单轨后唯一执行入口，分层编排）：
     * record CAS（先占执行记录）→ startIfNeeded 状态推进 → 消费 timeline → 上下文装配 →
     * 真身 execute + afterTurn 会话推进 → record CAS 终态 → ExecutionResultHandler 回写。
     */
    /**
     * Turn 边界包装：执行完成后<b>释放该子任务的复用工作沙箱</b>（`ADR-005`）。
     *
     * <p>无论成功 / 失败 / 异常 / 取消都释放（{@code finally}）——保证「一次执行尝试的
     * 工作区不跨尝试残留」，也保证不跨子任务共享。释放失败不影响执行结果（实现内部幂等且吞异常）。</p>
     */
    private void runViaRuntime(ExecutionCommand command, SubTaskSnapshot subTask, Agent agent) {
        try {
            runViaRuntimeInner(command, subTask, agent);
        } finally {
            try {
                sandboxExecutionService.release(command.getSubTaskId());
            } catch (Exception e) {
                log.warn("释放复用沙箱失败（不阻断）: subTaskId={}, cause={}",
                        command.getSubTaskId(), e.getMessage());
            }
        }
    }

    private void runViaRuntimeInner(ExecutionCommand command, SubTaskSnapshot subTask, Agent agent) {
        // 1. 执行记录 CAS：PENDING → RUNNING（**先占执行记录，再推进子任务状态**）。
        //    ⚠ 顺序契约（2026-10-05 修 804 双消费假阻塞）：MQ 主路径与 DB Poller 兜底路径
        //    可能同时消费同一命令（同一 recordId），两者都读到 sub_task(ASSIGNED) 快照。
        //      · 旧顺序（先 startIfNeeded 后 markRunning）下，输家在 startIfNeeded 的
        //        ASSIGNED→IN_PROGRESS 乐观锁 CAS 上抛「并发修改，请重试」→ 进 catch →
        //        假失败 → 把子任务误打成 BLOCKED（而赢家已成功接管、产出被丢弃）。
        //      · 新顺序先做 record CAS：输家在此即被拒（记录已非 PENDING），直接**软跳过返回**
        //        ——不推进状态、不起运行时、不回写失败、不 block（非本消费持有，非执行失败）。
        if (command.getRecordId() != null && !agentExecutionRecordService.markRunning(command.getRecordId())) {
            log.debug("跳过执行(记录已非 PENDING, 判为他路已接管, route=agent_runtime): subTaskId={}, recordId={}",
                    command.getSubTaskId(), command.getRecordId());
            return;
        }

        // 2. 状态推进（幂等：IN_PROGRESS 恒过；ASSIGNED / REWORK / PAUSED → IN_PROGRESS）。
        //    失败契约化：回写失败（含归属校验，见 ExecutionResultHandler#handleFailure 四参重载）
        //    + record CAS 终态，不进入执行阶段。
        //    注意顺序：handleFailure 先于 markFailed —— 归属校验要求此刻记录仍为 RUNNING
        //    （若先 markFailed 把记录置为 FAILED，归属校验将无法区分「真失败」与「双消费输家」）。
        try {
            subTaskCommandPort.startIfNeeded(command.getSubTaskId(), subTask.status());
        } catch (Exception e) {
            log.error("子任务状态推进失败: subTaskId={}, agentId={}, err={}",
                    command.getSubTaskId(), command.getAgentId(), e.getMessage());
            executionResultHandler.handleFailure(command.getSubTaskId(), command.getAgentId(),
                    command.getRecordId(), e);
            if (command.getRecordId() != null) {
                agentExecutionRecordService.markFailed(command.getRecordId(), e.getMessage());
            }
            return;
        }

        // 3. 消费阶段 timeline（route 观察点：灰度脚本据此区分路径）
        taskTimelinePort.recordEvent(
                subTask.taskId(),
                command.getSubTaskId(),
                TimelineEventCode.SUB_TASK_EXECUTION_COMMAND_CONSUME.code(),
                AgentRole.EXECUTOR,
                command.getAgentId(),
                safeMap(
                        "trigger", command.getTrigger(),
                        "recordId", command.getRecordId(),
                        "eventId", command.getEventId(),
                        "accessType", command.getAccessType() != null ? command.getAccessType().name() : "UNKNOWN",
                        "route", "agent_runtime"));
        taskTimelinePort.recordEvent(
                subTask.taskId(),
                command.getSubTaskId(),
                TimelineEventCode.SUB_TASK_EXECUTE_START.code(),
                AgentRole.EXECUTOR,
                command.getAgentId(),
                Map.of("executor", "agent_runtime"));

        // 4. 装配 + 真身执行 + 会话推进（事件骨架由真身内部记录，防双写见 AgentRuntimeContextAssembler）
        AgentExecutionResult result;
        try {
            // LOG-20260904-009：requiredSkills 由命令装箱传入，
            // 本层不再反向查询 task（§6 依赖方向红线）；command.requiredSkills 恒非 null，
            // 这里仅保留防御
            List<String> skills = command.getRequiredSkills() != null
                    ? command.getRequiredSkills() : Collections.emptyList();
            // 启用工具为 agent 域数据（agent_mcp_server），消费侧 agent 域内
            // 直读注入 ctx.tools（与 skills 的 task 域装箱不同，无 §6 跨域问题）；恒非 null 仅防御。
            // L3 P1-1（2026-10-06）：按 accessType 过滤 —— 内部 LLM 执行者（API_KEY_LLM）进程内
            // 无 MCP 会话，注入 13 个 MCP 生命周期工具必然 401；外部 Agent（CLI_CLIENT）原样注入。
            List<String> tools = agentMcpServerService.getEnabledToolsForAccess(
                    command.getAgentId(), command.getAccessType());
            if (tools == null) {
                tools = Collections.emptyList();
            }
            // 执行环境为 agent 域数据（agent.accessType 随命令透传），
            // 消费侧 agent 域内解析注入；accessType 为 null 或无命中时保持 null（语义兼容）
            ExecutionEnvironment environment = executionEnvironmentProvider.resolve(command.getAccessType());
            AgentContext ctx = contextAssembler.assemble(command, subTask, agent, tools, environment);
            result = agentRuntimes.get(0).execute(ctx);
            // ★①沙箱正文回传（2026-10-25，`LOG-20261025-010`）：必须在 release 之前、在
            //   结果落地之前——前者决定工作区还在不在，后者决定交出去的是哪份正文。
            result = recoverDraftFromWorkspace(command.getSubTaskId(), result);
            contextAssembler.afterTurn(subTask, agent, ctx.getTurn(), result);
        } catch (Exception e) {
            // 装配 / 真身违约异常契约化：回写失败（含归属校验）+ record CAS 终态
            // （与旧链 executeCommand catch 等价）
            log.error("Runtime 真身执行异常: subTaskId={}, agentId={}, recordId={}",
                    command.getSubTaskId(), command.getAgentId(), command.getRecordId(), e);
            executionResultHandler.handleFailure(command.getSubTaskId(), command.getAgentId(),
                    command.getRecordId(), e);
            if (command.getRecordId() != null) {
                agentExecutionRecordService.markFailed(command.getRecordId(), e.getMessage());
            }
            return;
        }

        // 5+6. 结果原子化落地（`HIGH-2` 修复，2026-10-15）：执行记录终态化 与 结果回写
        //      收进**同一事务**。
        //      · 修复前：第 5 步 markSuccess/markFailed 与第 6 步 handleSuccess/handleFailure
        //        是两个独立事务，且顺序「先终态、后回写」⇒ 两者之间崩溃会留下
        //        「记录 = SUCCESS 但子任务仍 IN_PROGRESS」的僵尸态，而超时补偿只扫
        //        PENDING/RUNNING、Poller 只扫 PENDING ⇒ **成功产出被静默丢弃且无重放**。
        //      · 修复后：任一步失败 ⇒ 整体回滚 ⇒ 记录退回 RUNNING ⇒ 由
        //        ExecutionCompensationTask 超时判定并落 timeline + 转 BLOCKED
        //        ⇒ 失败可见且可经既有改派链恢复。
        executionResultHandler.applyResultAtomically(command.getRecordId(), command.getSubTaskId(),
                command.getAgentId(), toAgentResult(result));
        log.info("执行命令消费成功(route=agent_runtime): subTaskId={}, agentId={}, recordId={}, status={}",
                command.getSubTaskId(), command.getAgentId(), command.getRecordId(), result.getStatus());
    }

    /**
     * turn 末「沙箱正文回传」：对话正文不是交付物时，用工作区里最大的 {@code .md} 顶替它。
     *
     * <p><b>为什么需要</b>（`LOG-20261025-009` 实测）：MiniMax-M3 两次执行都是
     * {@code STOP} + 20 轮全在 {@code internal_run_in_sandbox} 里写 {@code /workspace/draft.md}，
     * 对话正文只剩 604 字节<b>写作过程叙述</b>（「先写第一段…」「已补足到 1800 字」）。
     * 平台只采集对话正文 ⇒ 真正文（3008 字）一个字没交出去 ⇒ 评审连驳 3 轮 ⇒ 死信。
     * <b>正文本来就写出来了</b>，缺的只是「交出来」这一步。</p>
     *
     * <p><b>判据（三个都过才替换）</b>：</p>
     * <ol>
     *   <li>对话正文<b>不是</b>可交付正文（{@link DeliverableDraft#isDeliverable}：剔除自检围栏后
     *       无骨架且不足 {@value DeliverableDraft#MIN_BODY_CHARS} 字符）——已经是好正文就一个字都不动；</li>
     *   <li>工作区文件的正文<b>是</b>可交付正文——不回传比现状更差的东西；</li>
     *   <li>工作区正文<b>至少是对话正文的 2 倍</b>——防「短交付类验收」被误伤：本事故同批任务的
     *       验收就是 300~400 字，一份 350 字的合格短答在判据 ① 下同样算「不可交付」
     *       （350 &lt; 1500），若无本条，模型顺手写在沙箱里的 400 字草稿就会把合格短答顶掉。
     *       「写得多」才值得替换，「差不多」就以对话正文为准。</li>
     * </ol>
     *
     * <p><b>零额外 LLM 成本</b>：只是一次沙箱内 {@code find + base64}（<20ms），不追加模型轮。
     * 「不回传」侧一律保守：{@code status != SUCCESS}、回读异常、解码失败都原样返回。</p>
     *
     * <p><b>围栏拼接</b>：原对话正文若带 {@code VERIFICATION:} 自审围栏，把围栏段接在回传正文末尾——
     * 否则评审侧看到的是一份「没有自证声明」的交付物（证据信号缺失，可能换一种方式被驳回）。</p>
     *
     * @param subTaskId 子任务 ID（沙箱工作区键）
     * @param result    真身结果（可为 null）
     * @return 回传后的结果（不需要 / 不成功 回传时原样返回同一对象）
     */
    private AgentExecutionResult recoverDraftFromWorkspace(Long subTaskId, AgentExecutionResult result) {
        if (result == null || result.getStatus() != ExecutionStatus.SUCCESS) {
            return result;
        }
        String conversational = result.getOutput();
        if (DeliverableDraft.isDeliverable(conversational)) {
            return result;
        }
        String fromWorkspace;
        try {
            fromWorkspace = sandboxExecutionService.readWorkspaceFile(subTaskId);
        } catch (Exception e) {
            // 回读是锦上添花：它失败绝不能影响执行结果
            log.warn("沙箱正文回传异常（忽略，按原对话正文提交）: subTaskId={}, cause={}",
                    subTaskId, e.getMessage());
            return result;
        }
        if (!DeliverableDraft.isDeliverable(fromWorkspace)) {
            return result;
        }
        int bodyChars = DeliverableDraft.bodyChars(conversational);
        int workspaceChars = DeliverableDraft.bodyChars(fromWorkspace);
        if (workspaceChars < bodyChars * 2L) {
            log.debug("沙箱正文回传跳过：工作区正文（{} 字）未显著多于对话正文（{} 字）subTaskId={}",
                    workspaceChars, bodyChars, subTaskId);
            return result;
        }
        String fence = VerificationFence.fenceBlock(conversational);
        String submitted = fence.isBlank() ? fromWorkspace : fromWorkspace + "\n\n" + fence;
        log.warn("沙箱正文回传：对话正文非交付物（{} 字）→ 改用工作区文件（{} 字）"
                        + "subTaskId={}, finishReason={}, 围栏={}",
                bodyChars, submitted.length(), subTaskId, result.getFinishReason(),
                fence.isBlank() ? "无" : "已拼接");
        return AgentExecutionResult.builder()
                .status(result.getStatus())
                .output(submitted)
                .thinking(result.getThinking())
                .finishReason(result.getFinishReason())
                .tokenUsage(result.getTokenUsage())
                .eventTypes(result.getEventTypes())
                .artifacts(result.getArtifacts())
                .build();
    }

    /** Runtime 真身结果 → 旧 AgentResult 契约映射（成功带正文/思考/结束原因；失败带错误正文）。 */
    private AgentResult toAgentResult(AgentExecutionResult result) {        if (result.getStatus() == ExecutionStatus.SUCCESS) {
            return AgentResult.success(result.getOutput(), result.getThinking(), result.getFinishReason(),
                    "RuntimeTurnExecutor", result.getTokenUsage());
        }
        return AgentResult.failure(result.getOutput() != null ? result.getOutput() : "agent_runtime_failed",
                result.getFinishReason(), "RuntimeTurnExecutor");
    }

    private static Map<String, Object> safeMap(Object... keyValues) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key instanceof String keyString) {
                result.put(keyString, keyValues[i + 1]);
            }
        }
        return result;
    }
}
