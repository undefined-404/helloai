package com.helloai.core.agent.runtime.tool;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.ManualInterventionReasons;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.SubTaskCommandPort;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxArtifact;
import com.helloai.core.agent.runtime.sandbox.SandboxCallLedger;
import com.helloai.core.agent.runtime.sandbox.SandboxCallOrigin;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.runtime.sandbox.SkillInstallResult;
import com.helloai.core.agent.runtime.sandbox.impl.SkillExecutionSupport;
import com.helloai.core.agent.service.McpToolService;
import com.helloai.core.agent.service.SkillArtifactExportService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.skill.SkillPackage;
import com.helloai.core.agent.tool.ToolInvocationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部 LLM 执行者（{@code API_KEY_LLM}）的<b>进程内工具通道</b>（REF-8.1 M1）。
 *
 * <p><b>为什么存在</b>：内部 LLM 执行子任务走进程内 {@code AgentLoop}，无 MCP 会话上下文
 * （{@code McpAuthContext} 无 sessionId）。若把 {@link McpMcpServer} 那 13 个 {@code @Tool}
 * 注入，模型调用必 401（2026-10-06 已实测修复的 L3 P1-1 根因）。本类提供一组
 * <b>不依赖 session 鉴权</b>的进程内 {@code @Tool}：复用 {@link McpToolService} 的纯
 * {@code agentId} 入参业务方法（无 session 依赖），语义受控，只暴露内部执行者需要的子集。</p>
 *
 * <p><b>边界（REF-8.1，与平台职责对齐）</b>：
 * <ul>
 *   <li>工具名带 {@code internal_} 前缀，与外部 MCP 工具（{@link McpMcpServer}）区分，避免命名冲突；</li>
 *   <li><b>读型</b>工具（detail / deps / status）复用 {@link McpToolService} 同名方法，返回值原生透传；</li>
 *   <li><b>交付登记</b>工具 {@code register_output_attachment} 走 {@link AttachmentPort#register}，
 *       仅登记产出附件，<b>不触发状态机流转</b>——内部执行链的最终结果由消费侧
 *       {@code ExecutionResultHandler}（source=INTERNAL）统一回写，故<b>不</b>暴露
 *       {@code submitResult}（避免与 INTERNAL 回写双写冲突）、<b>不</b>暴露
 *       {@code uploadArtifact}（避免与平台侧自动物化 {@code ExecutionArtifactServiceImpl} 重复）；</li>
 *   <li><b>不</b>暴露生命周期工具（{@code claimSubTask} / {@code heartbeat} / {@code checkIn} /
 *       {@code checkOut} / {@code pullTasks} 认领语义）——它们服务于外部轮询 Agent 上岗，与内部
 *       已被平台派单的执行者场景冲突。</li>
 * </ul></p>
 *
 * <p><b>接入方式</b>：经独立的 {@link ToolCallbackProvider} 注册进 spring-ai（与
 * {@code McpToolConfig.mcpToolCallbacks()} 并存），工具名进入 {@link ToolRegistry} 目录；
 * 内部 LLM 授权集（{@code AgentMcpServerServiceImpl} 对 {code API_KEY_LLM} 分支）返回这些名字，
 * 模型方可经 {@code RuntimeTurnExecutor} 的 {code resolve} 看到并调用。</p>
 *
 * <p><b>鉴权纪律（REF-8.1 A1 接线，2026-10-12 订正）</b>：本类方法<b>不</b>做
 * {@code McpAuthContext.requireAuthId}——{@code agentId / subTaskId} 由执行链
 * {@code RuntimeTurnExecutor} 在 {@code agentLoop.run()} 前经
 * {@link com.helloai.core.agent.tool.ToolInvocationContext} 绑定，并由
 * {@code ToolCallbackToolExecutor} 在调用本类工具前<b>带外注入</b>调用参数
 * （{@code internal_} 前缀门控 + schema 字段门控；模型无从自报，注入值覆盖模型所给值）。
 * 合法性由执行链（进程内可信边界）负责，非外部持牌通道。故本类 {@code agentId / subTaskId}
 * 的 {@code @ToolParam} 均标注 {@code required = false}（「平台自动注入，无需填写」）——
 * 保留字段位供注入落点，但不再要求模型提供。</p>
 *
 * <p><b>强制闸门（REF-8.1 A1，2026-10-12）</b>：本类工具<b>仅</b>可由平台内部执行链调用——
 * 每个方法入口先 {@link #requireInternalChain()}，<b>未绑定执行链上下文即拒绝</b>，
 * 且一律使用<b>绑定的</b> {@code agentId / subTaskId}（不信任调用方入参）。这是对
 * 「{@code internal_} 工具此前因单 provider 合并而进入外部 MCP {@code tools/list}」
 * 的兜底：即便外部持牌调用方仍能看到工具名，也<b>无法执行</b>（无绑定 ⇒ 拒绝），
 * 消除「跨 agent 读子任务 / 读任意附件 / 往他人子任务登记产物」的越权面。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalLlmExecutorTools {

    private final McpToolService mcpToolService;
    private final AttachmentPort attachmentPort;
    /** 技能解析服务（REF-8.3：全量目录 + 惰性加载）；走 {@code getPackage} / {@code loadBody}。 */
    private final AgentSkillSpecService agentSkillSpecService;
    /** 子任务命令端口（REF-8.1.1：自报阻塞 → {@code block} + 人工介入标记）。 */
    private final SubTaskCommandPort subTaskCommandPort;
    /** 沙箱执行通道（S-7，沙箱执行链设计方案 §3.3）：{@code internal_run_in_sandbox} 的底层执行。 */
    private final SandboxExecutionService sandboxExecutionService;
    /** 技能执行编排（`SKX-8`）：{@code internal_run_skill} 的「解析包 → 注入 → 在技能根执行」。 */
    private final SkillExecutionSupport skillExecutionSupport;
    /** 产物落地（`SKX-11`）：{@code internal_export_artifact} 的「沙箱字节 → 对象存储 → 附件登记」。 */
    private final SkillArtifactExportService skillArtifactExportService;
    /** 对话流写入（LOG-20261112）：自报阻塞后在执行对话流补一条结论，避免执行轮只挂请求、无结论。 */
    private final ConversationService conversationService;
    /** 沙箱调用台账（`SL-5`）：{@code internal_list_sandbox_calls} 的只读事实源。 */
    private final SandboxCallLedger sandboxCallLedger;

    /** 自报阻塞前置：必须先读过子任务详情（知情拒绝闸门，见 {@code internal_report_blocked}）。 */
    private static final String TOOL_GET_SUB_TASK_DETAIL = "internal_get_sub_task_detail";
    /** 阻塞原因最短长度（防敷衍式「做不到」）。 */
    private static final int MIN_BLOCKED_REASON_CHARS = 10;

    /** 台账查询默认条数（`SL-5`）：覆盖一个子任务的常规验证次数，又不至于一次拉爆上下文。 */
    private static final int DEFAULT_SANDBOX_CALL_LIST_LIMIT = 20;
    /** 台账查询条数上限（`SL-5`）：模型要求更多时钳制到本值。 */
    private static final int MAX_SANDBOX_CALL_LIST_LIMIT = 50;

    /**
     * 产物正文内联上限（<b>字符</b>，`SL-3` 起按字符计）：超过则只内联前 N 个字符并标 {@code truncated}。
     *
     * <p>取 8000 的理由：一次工具返回要留在模型上下文里，820KB 的 HTML 内联会直接吃掉预算；
     * 而 8000 字符足够覆盖回执 JSON、短日志、错误栈这些「模型确实需要读」的场景。</p>
     *
     * <p><b>为什么必须按字符而不是字节</b>：旧实现拿 {@code artifact.size()}（字节）比这个常量，
     * 中文按 UTF-8 3 字节/字 ⇒ 2666 字就被判「过大不内联」，而验收场景的正文是 1800~3000 汉字，
     * 正好卡在误伤区间。</p>
     */
    private static final int MAX_INLINE_ARTIFACT_CHARS = 8_000;

    /**
     * 人工介入原因码（写入 {@code context.manualIntervention}）。
     *
     * <p>取值收敛到 {@link ManualInterventionReasons#EXECUTOR_REPORTED_BLOCKED}（叶子域唯一事实源）：
     * 该码同时被 task 域 BLOCKED 自动重派巡检消费（据此区分「能力不足」与「等人工」），
     * 两处若各写字面量，任一处漂移都会让判定静默失效。</p>
     */
    private static final String BLOCKED_REASON_CODE = ManualInterventionReasons.EXECUTOR_REPORTED_BLOCKED;

    /**
     * 查询当前子任务详情（执行内容 / 交付物 / 验收标准 / 执行约束 / 不确定性申报 / 前置附件）。
     *
     * <p>这是内部 LLM 执行子任务的核心上下文工具——拿到完整子任务四要素后才能正确开工。
     * 等价于外部 MCP 的 {@code getSubTaskDetail}，但走进程内、无 session 鉴权。</p>
     *
     * @param agentId   内部执行链鉴定的执行者 Agent ID（进程内可信注入）
     * @param subTaskId 目标子任务 ID
     * @return 子任务详情（含 attachments 附件清单）
     */
    @Tool(name = "internal_get_sub_task_detail", description = """
            【何时使用】内部执行者开工 / 执行中需要查看当前子任务的完整内容时调用：拿执行内容、交付物要求、
            验收标准、执行约束、不确定性申报，以及前置产出附件清单。
            【Gotchas】
            - 必须是平台已派单给本执行者的子任务（状态 ASSIGNED / IN_PROGRESS）
            - 返回值不含附件字节，仅有附件标识；如需读附件正文用 internal_read_attachment
            - 与外部 get_sub_task_detail 同源，进程内直连，无需 MCP 会话
            【相关工具】internal_get_deps_summary / internal_read_attachment / internal_get_agent_status
            """)
    public Object internalGetSubTaskDetail(
            @ToolParam(description = "内部执行链鉴定的执行者 Agent ID（平台自动注入，无需填写）", required = false) Long agentId,
            @ToolParam(description = "目标子任务 ID（平台自动注入，无需填写）", required = false) Long subTaskId) {
        ToolInvocationContext.Ids ids = requireInternalChain();
        // REF-8.1.1：登记「本链读过任务详情」——internal_report_blocked 的知情拒绝闸门据此判定
        ToolInvocationContext.markCalled(TOOL_GET_SUB_TASK_DETAIL);
        return mcpToolService.getSubTaskDetail(ids.agentId(), ids.subTaskId());
    }

    /**
     * 查询当前任务的前置依赖产出摘要（谁未完成、哪些已产出、内容是否可读）。
     *
     * @param agentId   执行者 Agent ID
     * @param subTaskId 当前子任务 ID
     * @return 前置依赖汇总（ready / notReadyCount / deps 明细）
     */
    @Tool(name = "internal_get_deps_summary", description = """
            【何时使用】执行前需要确认前置依赖是否就绪、各前置产出了什么时调用。
            【Gotchas】
            - ready=false 且 degraded=false 表示前置尚未产出，content 为空是预期而非异常
            - 与外部 get_deps_summary 同源，进程内直连
            【相关工具】internal_get_sub_task_detail
            """)
    public Object internalGetDepsSummary(
            @ToolParam(description = "执行者 Agent ID（平台自动注入，无需填写）", required = false) Long agentId,
            @ToolParam(description = "当前子任务 ID（平台自动注入，无需填写）", required = false) Long subTaskId) {
        ToolInvocationContext.Ids ids = requireInternalChain();
        return mcpToolService.getDepsSummary(ids.agentId(), ids.subTaskId());
    }

    /**
     * 查询执行者自身状态（管理态 / 在线态）。
     *
     * @param agentId 执行者 Agent ID
     * @return Agent 状态摘要
     */
    @Tool(name = "internal_get_agent_status", description = """
            【何时使用】需要确认自身 Agent 状态（ACTIVE / DISABLED、在线态）时调用。
            【Gotchas】
            - 与外部 get_agent_status 同源，进程内直连
            """)
    public Object internalGetAgentStatus(
            @ToolParam(description = "执行者 Agent ID（平台自动注入，无需填写）", required = false) Long agentId) {
        return mcpToolService.getAgentStatus(requireInternalChain().agentId());
    }

    /**
     * 登记一个产出附件（受控交付登记，<b>不触发状态机流转</b>）。
     *
     * <p>内部执行者在循环内产出了<b>独立交付文件</b>时，用本工具登记为当前子任务的产物附件。
     * 仅登记元数据（走 {@link AttachmentPort#register} 的归属校验 + 地址白名单 + 同名字去活），
     * <b>不</b>推进子任务状态——最终提交由平台侧 {@code ExecutionResultHandler}（source=INTERNAL）
     * 在循环结束后统一回写。</p>
     *
     * <p><b>为什么有这个工具</b>：内部 LLM 需要"交付一个文件"的能力（REF-8.2 写产物目录的受控版）。
     * 但 <b>不</b>暴露外部 {@code uploadArtifact}（会与平台自动物化重复）与 {@code submitResult}
     * （会触发 EXTERNAL 回写、与 INTERNAL 双写冲突）。本工具只登记，状态由现有内部回写链接管。</p>
     *
     * @param agentId   执行者 Agent ID
     * @param subTaskId 目标子任务 ID
     * @param fileName  文件名
     * @param mimeType  文件 MIME 类型（空则回退 octet-stream）
     * @param fileSize  文件字节数（空则 0）
     * @param storageUrl 产物存储地址（受控白名单内的对象地址）
     * @return 新登记的附件 ID（不为空）
     */
    @Tool(name = "internal_register_output_attachment", description = """
            【何时使用】内部执行者在本轮循环中产出了一个独立交付文件，需要登记为当前子任务的产物附件时调用。
            【Gotchas】
            - 本工具只登记附件元数据，不会提交任务、不会触发状态流转；任务最终提交由平台在循环结束后统一处理
            - storageUrl 必须在平台受控存储地址白名单内，否则 400
            - 用于登记"正文之外"的独立交付文件；若交付物就是你的正文输出，无需调用本工具（平台会自动物化）
            【相关工具】internal_get_sub_task_detail
            """)
    public Object internalRegisterOutputAttachment(
            @ToolParam(description = "执行者 Agent ID（平台自动注入，无需填写）", required = false) Long agentId,
            @ToolParam(description = "目标子任务 ID（平台自动注入，无需填写）", required = false) Long subTaskId,
            @ToolParam(description = "文件名", required = true) String fileName,
            @ToolParam(description = "文件 MIME 类型（可空）", required = false) String mimeType,
            @ToolParam(description = "文件字节数（可空）", required = false) Long fileSize,
            @ToolParam(description = "产物存储地址（受控白名单内）", required = true) String storageUrl) {
        ToolInvocationContext.Ids ids = requireInternalChain();
        Long attachmentId = attachmentPort.register(
                ids.agentId(), ids.subTaskId(), fileName,
                mimeType != null ? mimeType : "application/octet-stream",
                fileSize != null ? fileSize : 0L,
                storageUrl);
        return java.util.Map.of("attachmentId", attachmentId, "subTaskId", ids.subTaskId());
    }

    /**
     * 自报阻塞（REF-8.1.1：「做不到」的结构化通道）。
     *
     * <p><b>为什么存在</b>：本类此前的工具面<b>没有任何「做不到」出口</b>——模型确认自己无法完成时，
     * 唯一表达方式是把「做不到」写进正文，而正文会被平台物化成交付物并进入评审 ⇒ 必然被打回
     * （`prompts/subtask-review.md:51-52` fail-close）⇒ 返工至死信（`LOG-20261012-007` 实测复现）。
     * 外部通道有 {@code reportBlocked}（13 工具之一），内部通道此前缺等价物。</p>
     *
     * <p><b>形状（借鉴 DSH {@code update_goal.blocked}，见研究文档 §1.5.3）</b>：
     * 结构化原因 → 落 ⓐ 子任务状态 {@code BLOCKED}（{@link SubTaskCommandPort#block}，内含 PLANNER 通知）
     * ⓑ 人工介入标记（{@link SubTaskCommandPort#markManualIntervention}，防兜底任务反复重派）。
     * 阻塞一旦生效，循环结束后的 INTERNAL 回写因「状态非 IN_PROGRESS」被
     * {@code ExecutionResultHandler} 丢弃 ⇒ <b>不再进入评审</b>。</p>
     *
     * <p><b>防滥用（「连续 N 轮门槛」在本域的等价物）</b>：DSH 数的是 goal 轮（同一目标反复尝试），
     * 本域一次执行只有一条执行链，故等价物取「<b>知情拒绝</b>」——必须先调用过
     * {@code internal_get_sub_task_detail}（看过任务要求）才允许报阻塞，且原因不得敷衍（&ge; 10 字）。
     * 不采用「先耗 N 轮再许拒绝」：那会把「一两轮就看清做不到」的诚实情形反过来拖长（与收敛意图相反）。</p>
     *
     * @param agentId 执行者 Agent ID（平台自动注入）
     * @param reason  阻塞原因：缺什么 / 已试过什么 / 需要什么才能继续
     * @return 阻塞结果（状态 + 提示）
     */
    @Tool(name = "internal_report_blocked", description = """
            【何时使用】确认自己**没有能力**完成本子任务时调用（最后一手）：所需输入 / 原始附件不可得、
            需要本机环境（shell/文件/服务）而你没有、验收要求的证据无论如何拿不到。
            【Gotchas】
            - 调用前**必须**先 internal_get_sub_task_detail 看过任务要求（未看过即调用会被拒绝）
            - 报阻塞后子任务转 BLOCKED 并标记人工介入，**不再进入评审**——不要再用正文硬写「做不到」充当交付物
            - reason 必须写清「缺什么 / 试过什么 / 需要什么才能继续」，敷衍或过短会被拒绝
            - 有能力尝试就先尝试：这是最后一手，不要一遇困难就报阻塞
            【相关工具】internal_get_sub_task_detail / internal_get_deps_summary
            """)
    public Object internalReportBlocked(
            @ToolParam(description = "执行者 Agent ID（平台自动注入，无需填写）", required = false) Long agentId,
            @ToolParam(description = "阻塞原因：缺什么 / 已试过什么 / 需要什么才能继续", required = true) String reason) {
        ToolInvocationContext.Ids ids = requireInternalChain();
        if (!ToolInvocationContext.hasCalled(TOOL_GET_SUB_TASK_DETAIL)) {
            throw new BizException("报阻塞前必须先调用 " + TOOL_GET_SUB_TASK_DETAIL
                    + " 了解任务要求（知情拒绝闸门：不清楚要求就不算「做不到」）");
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < MIN_BLOCKED_REASON_CHARS) {
            throw new BizException("阻塞原因过短（至少 " + MIN_BLOCKED_REASON_CHARS
                    + " 字）：请写清「缺什么 / 试过什么 / 需要什么才能继续」");
        }
        subTaskCommandPort.block(ids.subTaskId(), trimmed, ids.agentId());
        Map<String, Object> extra = new java.util.HashMap<>();
        extra.put("reason", trimmed);
        extra.put("agentId", ids.agentId());
        subTaskCommandPort.markManualIntervention(ids.subTaskId(), BLOCKED_REASON_CODE, extra);
        // LOG-20261112：补对话结论（sub_task_execute_blocked）。阻塞后状态非 IN_PROGRESS，
        // 循环结束的 INTERNAL 回写会被 ExecutionResultHandler 在 status 守卫处丢弃、不再写对话；
        // 故结论在此自报出口补写，避免执行轮只挂请求行悬空。best-effort + REQUIRES_NEW，失败不阻断。
        try {
            conversationService.addMessage(ids.subTaskId(), ids.agentId(), "assistant", "agent",
                    "执行阻塞（需人工介入）：" + trimmed, TimelineEventCode.SUB_TASK_EXECUTE_BLOCKED.code());
        } catch (Exception e) {
            log.warn("自报阻塞对话结论写入失败（不阻断阻塞主链路）: subTaskId={}, err={}",
                    ids.subTaskId(), e.getMessage());
        }
        log.info("内部执行者自报阻塞: agentId={}, subTaskId={}, reason={}",
                ids.agentId(), ids.subTaskId(), trimmed);
        return Map.of("subTaskId", ids.subTaskId(), "status", "BLOCKED", "manualIntervention", true,
                "note", "已转入人工介入，本轮产出不再进入评审");
    }

    /**
     * 读取一个平台可读产物的附件正文（字节 → UTF-8 文本）。
     *
     * <p>命中 {@code LOG-20261011-007} 事故根因：此前内部执行者无法读原始附件字段，
     * 只能吃平台预内联摘要，接错"需读原附件"的任务时无能为力。本工具走
     * {@link AttachmentPort#loadContent} 读取正文。</p>
     *
     * @param agentId      执行者 Agent ID
     * @param attachmentId 附件主键
     * @return 附件正文（UTF-8 解码）；不可读或不存在时抛异常
     */
    @Tool(name = "internal_read_attachment", description = """
            【何时使用】需要读取当前子任务或其前置产物的附件正文（原始字段内容）时调用。
            【Gotchas】
            - 只能读 platform 可读（loadable=true）的附件
            - 返回值是文本；二进制附件（图片等）读取可能失败或乱码
            - 建议先调 internal_get_sub_task_detail 拿到附件清单与 attachmentId
            【相关工具】internal_get_sub_task_detail / internal_get_deps_summary
            """)
    public Object internalReadAttachment(
            @ToolParam(description = "执行者 Agent ID（平台自动注入，无需填写）", required = false) Long agentId,
            @ToolParam(description = "附件主键（来自 internal_get_sub_task_detail 的 attachments）", required = true) Long attachmentId) {
        requireInternalChain();
        byte[] content = attachmentPort.loadContent(attachmentId);
        return new String(content, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 列出平台全部可调用技能（REF-8.3 全量目录注入）。
     *
     * <p>与 DSH {@code tool-skill} 的「全量目录 + 惰性加载」对齐：本工具返回
     * <b>全量健康技能目录</b>（{@code name + description} 封顶，不含正文），让模型感知
     * 「还有哪些技能可用」并自行判断哪些与当前任务相关；正文经 {@code internal_load_skill}
     * 惰性加载。区别于任务注入的「速览」（只看得到被要求命中的那几个），本工具是
     * <b>模型可见的全量视野</b>。</p>
     *
     * @return 技能目录列表（按技能 name 排序），每项含 name / version / description / requiredTools（缩写）
     */
    @Tool(name = "internal_list_skills", description = """
            【何时使用】需要了解平台有哪些可用技能，以判断当前任务该按哪个技能手段执行时调用。
            返回全量技能目录（名称 + 简介，不含正文）。
            【Gotchas】
            - 返回的是技能清单（name + description），任何一项的正文需调 internal_load_skill 按需加载
            - 只列健康技能；坏技能包不会出现
            【相关工具】internal_load_skill
            """)
    public Object internalListSkills() {
        requireInternalChain();
        List<SkillPackage> packages = agentSkillSpecService.listPackages();
        if (packages == null || packages.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> catalog = packages.stream()
                .sorted(Comparator.comparing(SkillPackage::name, Comparator.nullsLast(String::compareTo)))
                .map(pkg -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", pkg.name());
                    entry.put("version", pkg.version());
                    entry.put("description", truncateSkillDescription(pkg.description()));
                    // requiredTools 缩写（≤6 项，避免目录过长）：供模型判断该技能需哪个工具
                    List<String> tools = pkg.requiredTools();
                    entry.put("requiredTools", tools != null && tools.size() <= 6 ? tools
                            : (tools != null ? tools.subList(0, 6) : List.of()));
                    return entry;
                })
                .toList();
        return catalog;
    }

    /**
     * 惰性加载某个技能的完整正文（REF-8.3 progressive disclosure）。
     *
     * @param name 技能名（必须是 internal_list_skills 返回的准确 name）
     * @return 技能正文；技能不存在 / 不可读时返回 null
     */
    @Tool(name = "internal_load_skill", description = """
            【何时使用】在 internal_list_skills 看到技能后，需要读取某个技能的完整规范正文（怎么执行、边界、验收）时调用。
            【Gotchas】
            - name 必须是 internal_list_skills 返回的准确技能名（严格匹配，不归一化）
            - 返回正文可能较长；只读与当前任务相关的技能
            【相关工具】internal_list_skills
            """)
    public Object internalLoadSkill(
            @ToolParam(description = "技能名（来自 internal_list_skills）", required = true) String name) {
        requireInternalChain();
        return agentSkillSpecService.loadBody(name);
    }

    /**
     * 在 OpenSandbox 沙箱内执行验证命令或脚本（S-8，沙箱执行链设计方案 §3.2）。
     *
     * <p><b>为什么存在</b>：内部 LLM 自检（{@code REF-8.2.2} 验证证据围栏）与 Reviewer
     * 核验（{@code subtask-review.md}）此前的「验证」只能是<b>声称</b>——模型把
     * 「已跑通 / 无错误」写进报告，平台无从核实，只能依赖 {@code FinalReportFidelityChecker}
     * 的静态文本检查。本工具把 OpenSandbox 沙箱接成<b>受控执行通道</b>：模型可在容器内
     * 实际执行验证命令，拿到 {@link com.helloai.core.agent.runtime.sandbox.ExecuteResponse}
     * 的 <b>一手证据</b>（stdout/stderr + exitCode + truncated），自检证据围栏从声称变实证。</p>
     *
     * <p><b>安全边界（沙箱执行链设计方案 §3.4）</b>：命令在 OpenSandbox 容器内执行——
     * 默认禁网（egress 白名单外不可达）、零平台凭据 env（{@code credential=ISOLATED}）、
     * 文件系统/资源/进程受容器隔离；<b>不</b>执行技能包正文（只执行本调用传入的命令/脚本）；
     * 沙箱未启用（{@code helloai.sandbox.opensandbox.enabled=false}）时本工具经
     * {@code toolAvailability()} 从模型可见列表<b>摘除</b>（fail-closed，不静默降级）。</p>
     *
     * @param agentId        执行者 Agent ID（平台自动注入）
     * @param subTaskId      目标子任务 ID（平台自动注入）
     * @param command        要在沙箱内执行的命令（bash -lc 语义）或脚本正文
     * @param timeoutSeconds 命令超时秒数（默认 300，上限 300；超出钳制到上限）
     * @return 统一执行结果 {output, exitCode, truncated}；exitCode=124 表示命令超时
     */
    @Tool(name = "internal_run_in_sandbox", description = """
            【何时使用】需要执行一段命令或脚本以验证产出时调用——自检：验证脚本跑通、命令输出与结论一致；
            Reviewer：验证脚本确实能通过目标验收标准。
            【工作区语义（重要）】
            - 命令默认在 `/workspace` 下执行：`./x.py` 与 `/workspace/x.py` 是**同一个文件**（cwd 已固定）
            - **同一任务内复用同一工作区**（不只是本子任务）：**子任务之间文件互相可见**，
              上游子任务写好的产物你这里直接能读到；同一子任务内的多次调用自然也是**上一轮的文件这一轮还在**
            - 因此改稿请对**已有文件**做**定点修改**（`sed -i` / `python` 改写某一段），
              **不要每轮把整篇内容重新写一遍**——整篇重写既浪费轮次，也会让内容在版本间漂移
            - 需要多步操作（写文件 → 校验）时，**写在同一条命令里**（`cat > f && wc -c f`）最稳
            - 建议把草稿放在固定路径（如 `/workspace/draft.md`）跨轮沿用；工作区在**任务终态**
              （完成 / 取消 / 删除）才销毁，中间等待审核不会丢
            - 命令**串行执行**；平台进程重启后工作区丢失（届时重新建）
            - **沙箱内文件不是交付物**：平台只采集**对话正文**，沙箱文件不会被采集、不进评审
            【抓取约束（2026-10-25 MiniMax 事故后增补）】
            - **curl 抓取最多 6 轮**：联网检索 / 逐页抓取**最多 6 轮**；第 6 轮后无论数据是否齐，
              都必须停止抓取、开始写正文——数据缺口写进 VERIFICATION 的 NEXT_STEPS 里说明
            - **按域名缓存**：同一域名（如 clickup.com）的页面**只抓一次**，后续引用已抓内容；
              不要反复抓同一页面的变体 URL（tinyurl / r.jina.ai 代理 / www 前缀穷举）
            - **链接抽查即可**：验收要求「N 条真实可打开来源」时，**抽查 2~3 条验证可达即可**，
              不必逐条 curl 验证——逐条验证会耗尽轮次
            【Gotchas】
            - 命令在 OpenSandbox 容器内执行：默认禁网（egress 白名单外不可达）、零平台凭据 env、
              文件系统/资源/进程受容器隔离——执行不可信脚本的安全边界
            - 返回 {output, exitCode, truncated}；exitCode=124 表示命令超时（默认 300s，上限 300s），
              超时（124）与 SDK 异常（-1）都**不销毁容器**：工作区文件保留，可直接继续增量编辑
            - 同一沙箱内的命令**串行执行**，不会并发
            - 不能访问宿主文件系统 / 平台凭据 / 外部网络（白名单外）
            - 不执行技能包正文：只执行本调用传入的命令/脚本正文
            - 沙箱未启用时本工具不会出现（fail-closed，不静默降级）；输出超 100KB 会被截断并标注 truncated
            【相关工具】internal_get_sub_task_detail / internal_register_output_attachment
            """)
    public Object internalRunInSandbox(
            @ToolParam(description = "内部执行链鉴定的执行者 Agent ID（平台自动注入，无需填写）", required = false) Long agentId,
            @ToolParam(description = "目标子任务 ID（平台自动注入，无需填写）", required = false) Long subTaskId,
            @ToolParam(description = "要在沙箱内执行的命令（bash -lc 语义）或脚本正文", required = true) String command,
            @ToolParam(description = "命令超时秒数（默认 300，上限 300）", required = false) Integer timeoutSeconds) {
        ToolInvocationContext.Ids ids = requireInternalChain();
        // `SL-4`：来源 = 执行者；轮次由执行链绑定（证据轮次优先 / 台账条目可被引用）。
        // `SL-10`：审查者身份（核验/终报告审查循环带出）→ REVIEWER 来源，工作区静态检查
        // 落到只读卷（写 /workspace 在容器层被拒），台账按来源区分 executor/reviewer 证据链。
        SandboxCallOrigin origin = ToolInvocationContext.currentReviewer()
                ? SandboxCallOrigin.REVIEWER : SandboxCallOrigin.EXECUTOR;
        return sandboxExecutionService.execute(ids.subTaskId(), null, command, timeoutSeconds,
                origin, ids.round());
    }

    /**
     * 列出本子任务最近的沙箱调用记录（`SL-5`，只读）。
     *
     * <p><b>为什么需要它</b>：`SL-5` 起 {@code EVIDENCE} 引用台账条目 ID（{@code callId}），
     * 而模型在收尾轮未必记得历史轮返回的 ID。没有查询通道，模型只能凭记忆填或放弃证据——
     * 前者产生 {@code self_reported_mismatch}，后者让真实执行过的证据被判 MISSING。</p>
     *
     * <p><b>只读、零副作用</b>：读内存台账快照，不执行命令、不占沙箱槽、不消耗收尾轮预算。</p>
     *
     * @param agentId   执行者 Agent ID（平台自动注入）
     * @param subTaskId 目标子任务 ID（平台自动注入）
     * @param limit     返回最近条数（空 ⇒ 默认 20；钳制到 1~50）
     * @return {count, calls:[{callId, command, exitCode, truncated, origin, round, at}]}（按时间升序）
     */
    @Tool(name = "internal_list_sandbox_calls", description = """
            【何时使用】需要在 EVIDENCE 里引用一手证据，或需要回忆本子任务之前跑过哪些命令时调用。
            只读内存台账：不执行命令、不占沙箱、不消耗任何预算。
            【返回】{count, calls:[{callId, command, exitCode, truncated, origin, round, at}]}，按时间升序（最近的在后）。
            - callId：证据引用坐标——EVIDENCE 只填 {"kind":"sandbox_exec","callId":"c17"} 即可，
              命令 / 退出码 / 截断标志以平台台账为准，不要自行改写
            - origin：executor（执行者）/ reviewer（核验者）/ platform（平台内部）——自审证据应引用 executor 条目
            - round：该条命令所在的执行轮次
            【Gotchas】
            - 台账只存命令前缀（最多 200 字符），不存完整输出；要看输出请重跑或读产物
            - 台账是进程内内存，平台重启即清空；沙箱未启用时本工具不会出现（fail-closed）
            【相关工具】internal_run_in_sandbox / internal_run_skill
            """)
    public Object internalListSandboxCalls(
            @ToolParam(description = "内部执行链鉴定的执行者 Agent ID（平台自动注入，无需填写）", required = false) Long agentId,
            @ToolParam(description = "目标子任务 ID（平台自动注入，无需填写）", required = false) Long subTaskId,
            @ToolParam(description = "返回最近多少条（默认 20，上限 50）", required = false) Integer limit) {
        ToolInvocationContext.Ids ids = requireInternalChain();
        int effectiveLimit = limit == null
                ? DEFAULT_SANDBOX_CALL_LIST_LIMIT
                : Math.max(1, Math.min(limit, MAX_SANDBOX_CALL_LIST_LIMIT));
        List<SandboxCallLedger.Entry> entries = sandboxCallLedger.recent(ids.subTaskId(), effectiveLimit);
        List<Map<String, Object>> calls = new ArrayList<>(entries.size());
        for (SandboxCallLedger.Entry entry : entries) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("callId", entry.callId());
            call.put("command", entry.command());
            call.put("exitCode", entry.exitCode());
            call.put("truncated", entry.truncated());
            call.put("origin", entry.origin());
            call.put("round", entry.round());
            call.put("at", entry.at() == null ? null : entry.at().toString());
            calls.add(call);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", calls.size());
        result.put("calls", calls);
        return result;
    }

    /**
     * 在沙箱内按<b>已入库技能包</b>执行命令（`SKX-8`，2026-10-26）。
     *
     * <p><b>为什么需要它，而不是让模型自己 {@code npm install}</b>：沙箱默认禁出网（`SKX-5` 起
     * 显式 {@code deny}），模型拿不到包；即便能出网，运行时下游下载也不可复现——同一任务今天跑通、
     * 明天换个上游版本就可能失败。本工具把「包」变成平台的<b>构建产物</b>：随 jar 固化、版本可追溯、
     * 注入幂等（`SKX-6`）。</p>
     *
     * <p><b>触发口径自证（2026-10-26 裁定，本次调查 §4.1 候选 A）</b>：本工具新增了「agent 可调用
     * 工具」，但判据是<b>是否碰宿主</b>——命令在 OpenSandbox 容器内执行（{@code filesystem=ISOLATED}、
     * 零平台凭据 env、egress deny、与 app 不共享 env / 网络 / 文件系统），执行的是<b>平台自己的包</b>
     * 而非任意下载物 ⇒ <b>不碰宿主 ⇒ 不触发条件①</b>。勿因「新增了工具」就误判需重排期。</p>
     *
     * @param agentId        执行者 Agent ID（平台自动注入）
     * @param subTaskId      目标子任务 ID（平台自动注入）
     * @param skillName      技能包名（须为已入库包，见 internal_list_skills / 平台内置名）
     * @param command        在技能根目录下执行的命令（如 {@code node bin/archify.mjs doctor}）
     * @param timeoutSeconds 超时秒数（默认 300，上限 300）
     * @return {skill, skillDir, installed{files,bytes,skipped}, output, exitCode, truncated}
     */
    @Tool(name = "internal_run_skill", description = """
            【何时使用】需要用平台已入库的技能包（当前：archify，架构图/流程图等图表产出）做实际产出或自检时调用。
            本工具会自动完成「把技能包放进容器」这一步（幂等，已装则秒级跳过），然后在**技能根目录**下执行你的命令。
            【工作区与命令语义】
            - 命令等价于在技能根目录执行：`cd /workspace/skills/<技能名> && <你的命令>`，
              所以技能自带 CLI 按相对路径写即可，如 `node bin/archify.mjs validate sequence in.json --json`
            - 与 internal_run_in_sandbox **共用同一容器**：同一任务内（跨子任务）的文件互相可见、
              跨调用保留，命令串行执行
            - 平台已自动导出 `TMPDIR=/workspace/tmp`（技能工具的临时目录需要）
            - 返回 {skill, skillDir, installed, output, exitCode, truncated}；exitCode=124 表示超时
            【三条硬边界】
            - 只能跑**平台已入库的技能包**（构建产物），不接受任意脚本/二进制，也不联网下载
            - **沙箱内文件不是交付物**：平台只采集对话正文；需要把产物字节拿回来核对（sha256/大小/片段）
              用 internal_read_artifact，路径用绝对路径（如 /workspace/out/x.html）
            - 平台不自持浏览器：技能里的 `browser-check` / 截图类子命令**不可用**（不装 Chromium），
              视觉复核须由人做；`--no-browser` / 跳过浏览器检查的参数按技能文档使用
            【Gotchas】
            - 命令在 OpenSandbox 容器内执行：默认禁网、零平台凭据、文件系统受隔离
            - 首次调用会注入技能包（约 2s，含建容器）；同任务后续调用毫秒级
            - 输出超 100KB 会被截断并标注 truncated
            【相关工具】internal_read_artifact / internal_run_in_sandbox / internal_get_sub_task_detail
            """)
    public Object internalRunSkill(
            @ToolParam(description = "内部执行链鉴定的执行者 Agent ID（平台自动注入，无需填写）", required = false) Long agentId,
            @ToolParam(description = "目标子任务 ID（平台自动注入，无需填写）", required = false) Long subTaskId,
            @ToolParam(description = "技能包名（已入库的技能，如 archify）", required = true) String skillName,
            @ToolParam(description = "在技能根目录下执行的命令（bash -lc 语义）", required = true) String command,
            @ToolParam(description = "命令超时秒数（默认 300，上限 300）", required = false) Integer timeoutSeconds) {
        ToolInvocationContext.Ids ids = requireInternalChain();
        SkillExecutionSupport.SkillTarget target = skillExecutionSupport.resolve(skillName);
        if (target == null) {
            throw new BizException("技能未入库: " + skillName + "（可用: "
                    + skillExecutionSupport.availableSkillNames() + "）——技能包只能来自平台构建产物，不能现场下载");
        }
        SkillInstallResult install = skillExecutionSupport.ensureInstalled(ids.subTaskId(), target);
        if (!install.success()) {
            throw new BizException("技能包注入失败（" + target.label() + "）: " + install.message());
        }
        ExecuteResponse response = skillExecutionSupport.run(ids.subTaskId(), target, command,
                timeoutSeconds, ids.round());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", target.label());
        result.put("skillDir", install.targetDir());
        result.put("installed", Map.of(
                "files", install.fileCount(),
                "bytes", install.totalBytes(),
                "skipped", install.skipped()));
        result.put("output", response.output());
        result.put("exitCode", response.exitCode());
        result.put("truncated", response.truncated());
        result.put("callId", response.callId());
        return result;
    }

    /**
     * 按<b>指定路径</b>回读沙箱内的产物字节摘要（`SKX-7` / `SKX-8`，2026-10-26）。
     *
     * <p><b>为什么要有这条通道</b>：技能跑出来的产物在容器里，而平台只采集对话正文。此前模型能
     * 「声称」产物有多大、内容对不对，平台无从核实。本工具把「回读 + 算 sha256」接出来，
     * 让「回执里的 {@code artifact.sha256}」与「平台实际读到的字节」可以对账——这是技能执行
     * 从「声称」变「实证」的那一步。</p>
     *
     * <p><b>为什么默认只内联前 8000 字符</b>：一份 820KB 的 HTML 全文内联进对话会直接吃掉上下文预算。
     * 内联按<b>字符</b>计（`SL-3`）：中文正文 3000 字仍可完整内联；超出部分截断并置
     * {@code truncated=true}，要完整字节用 {@code size} / {@code sha256} 对账或传更小的 {@code maxBytes}。</p>
     *
     * @param agentId  执行者 Agent ID（平台自动注入）
     * @param subTaskId 目标子任务 ID（平台自动注入）
     * @param path     容器内绝对路径（如 {@code /workspace/out/archify.html}）
     * @param maxBytes 回读上限字节数（空/≤0 ⇒ 默认 4MB；超限读前 N 字节并标 truncated）
     * @return {path, mime, size, sha256, truncated, content}（content 至多 8000 字符，超出部分截断）
     */
    @Tool(name = "internal_read_artifact", description = """
            【何时使用】需要核对沙箱内某个产物文件的实际字节（大小 / sha256 / 小文件正文）时调用——
            例如技能 CLI 回执里给了 artifact.sha256，你要证明「回读到的确实是同一份」。
            【Gotchas】
            - path 必须是**容器内绝对路径**（如 /workspace/out/archify.html），不接受相对路径
            - 命令超时（exitCode=124）**不会**重建沙箱，工作区与产物都还在；只有传输层异常
              重建容器、或任务终态回收后才读不到旧文件（此时返回「产物不存在」）
            - 超过 maxBytes 会截断并标 truncated=true：**截断结果的 sha256 只代表片段**，
              不能拿去和完整文件的回执比对
            - 超过约 8000 **字符**的产物只内联前 8000 字符并标 truncated=true：**截断结果的 sha256
              只代表片段**，完整字节用 size / sha256 对账，或传更小的 maxBytes
            【相关工具】internal_run_skill / internal_run_in_sandbox
            """)
    public Object internalReadArtifact(
            @ToolParam(description = "内部执行链鉴定的执行者 Agent ID（平台自动注入，无需填写）", required = false) Long agentId,
            @ToolParam(description = "目标子任务 ID（平台自动注入，无需填写）", required = false) Long subTaskId,
            @ToolParam(description = "容器内绝对路径（如 /workspace/out/archify.html）", required = true) String path,
            @ToolParam(description = "回读上限字节数（空 ⇒ 默认 4MB）", required = false) Integer maxBytes) {
        ToolInvocationContext.Ids ids = requireInternalChain();
        SandboxArtifact artifact = sandboxExecutionService.readArtifact(
                ids.subTaskId(), path, maxBytes == null ? 0 : maxBytes);
        if (artifact == null) {
            throw new BizException("产物不存在或不可读: " + path
                    + "（须为容器内绝对路径；容器重建或任务回收后旧文件会消失）");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", artifact.path());
        result.put("mime", artifact.mime());
        result.put("size", artifact.size());
        result.put("sha256", artifact.sha256());
        String full = artifact.text();
        String text = inlineArtifactText(full);
        // truncated 合并两种来源：① 读字节时超 maxBytes 被截；② 本次内联按字符上限截断
        result.put("truncated", artifact.truncated() || text.length() < full.length());
        result.put("content", text);
        return result;
    }

    /**
     * 截出可内联的正文（`SL-3` 纯函数，便于单测）：按<b>字符</b>上限截断，不切断代理对。
     *
     * @param text 完整正文（{@code null} ⇒ 空串）
     * @return 不超过 {@value #MAX_INLINE_ARTIFACT_CHARS} 字符的正文
     */
    static String inlineArtifactText(String text) {
        if (text == null || text.length() <= MAX_INLINE_ARTIFACT_CHARS) {
            return text == null ? "" : text;
        }
        int end = MAX_INLINE_ARTIFACT_CHARS;
        // 截断点落在一对代理字符中间时退一格，避免产生半个码点（JSON 序列化后会变乱码）
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * 把沙箱内的产物导出为当前子任务的<b>可下载附件</b>（`SKX-11`，2026-10-26）。
     *
     * <p><b>为什么需要它</b>：{@link #internalReadArtifact} 只把字节读进<b>本次工具调用</b>里，
     * 平台能对账、模型能自检，但用户界面上什么都看不到——沙箱文件随**任务终态**被销毁，
     * 「产物」等于没交付。本工具补上最后一段：沙箱字节 → 对象存储 → 附件表，
     * 用户随后在子任务附件里直接下载。</p>
     *
     * <p><b>与 {@code internal_register_output_attachment} 的分工</b>：那个工具要求调用方
     * <b>自己给 storageUrl</b>（适用于外部执行者已经上传好的场景）；本工具<b>只吃容器路径</b>，
     * 由平台负责字节搬运。模型手里只有容器路径，所以技能产物必须走本工具。</p>
     *
     * @param agentId   执行者 Agent ID（平台自动注入）
     * @param subTaskId 目标子任务 ID（平台自动注入）
     * @param path      容器内绝对路径（如 {@code /workspace/out/archify.html}）
     * @param fileName  附件文件名（空 ⇒ 取路径的文件名部分）
     * @return {attachmentId, fileName, mime, size, sha256, note}
     */
    @Tool(name = "internal_export_artifact", description = """
            【何时使用】技能/脚本产出了**要给用户看的文件**（如 archify 生成的 HTML 图表）时调用：
            把沙箱内的产物落地成当前子任务的附件，用户即可在界面上直接下载。
            【关键区别】
            - internal_read_artifact 只是**你自己看**（核对大小/哈希/片段），产物仍在容器里、随任务终态销毁
            - internal_export_artifact 是**交付给用户**（写对象存储 + 登记附件），会出现在子任务附件列表
            - 两个都要时先 export 再 read（或反之），互相不影响
            【Gotchas】
            - path 必须是**容器内绝对路径**；沙箱在**任务终态**被回收，**必须在同一轮执行内导出**
            - 产物超过单文件上限（默认 5MB）会被**拒绝**（不会导出截断版）——请让技能输出更精简的产物
            - fileName 可省略，默认取路径的文件名；同名重复导出会把旧版本置为历史版本
            - 导出成功即表示平台已能读到该文件；返回值里的 sha256 可与技能 CLI 回执对账
            【相关工具】internal_run_skill / internal_read_artifact / internal_get_sub_task_detail
            """)
    public Object internalExportArtifact(
            @ToolParam(description = "内部执行链鉴定的执行者 Agent ID（平台自动注入，无需填写）", required = false) Long agentId,
            @ToolParam(description = "目标子任务 ID（平台自动注入，无需填写）", required = false) Long subTaskId,
            @ToolParam(description = "容器内绝对路径（如 /workspace/out/archify.html）", required = true) String path,
            @ToolParam(description = "附件文件名（可空，默认取路径的文件名）", required = false) String fileName) {
        ToolInvocationContext.Ids ids = requireInternalChain();
        SkillArtifactExportService.ExportResult result =
                skillArtifactExportService.export(ids.agentId(), ids.subTaskId(), path, fileName);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attachmentId", result.attachmentId());
        out.put("fileName", result.fileName());
        out.put("mime", result.mimeType());
        out.put("size", result.size());
        out.put("sha256", result.sha256());
        out.put("note", "已登记为子任务附件，用户可在附件列表直接下载");
        return out;
    }

    /**
     * 强制闸门（REF-8.1 A1）：本类工具<b>仅</b>可由平台内部执行链调用。
     *
     * <p>判定依据 = {@link ToolInvocationContext} 是否绑定（只有 {@code RuntimeTurnExecutor}
     * 在 {@code agentLoop.run()} 期间绑定）。外部 MCP 调用方（含经 {@code tools/call} 的
     * 持牌 agent）无绑定 ⇒ 一律拒绝，且<b>不使用</b>调用方传入的 {@code agentId / subTaskId}。</p>
     *
     * @return 绑定的执行者标识（agentId 非空）
     * @throws BizException 未绑定执行链上下文（外部 / 越权调用）
     */
    private static ToolInvocationContext.Ids requireInternalChain() {
        ToolInvocationContext.Ids ids = ToolInvocationContext.current();
        if (ids == null || ids.agentId() == null) {
            throw new BizException("internal 工具仅可由平台内部执行链调用（缺少执行链上下文）");
        }
        return ids;
    }

    /** 技能描述封顶（目录注入防刷屏，与 DSH 的 description 封顶同构）。 */
    private static String truncateSkillDescription(String description) {
        if (description == null) {
            return "";
        }
        return description.length() <= 300 ? description : description.substring(0, 300) + "…";
    }
}