package com.helloai.core.agent.runtime.tool;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.SubTaskCommandPort;
import com.helloai.core.agent.service.McpToolService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.skill.SkillPackage;
import com.helloai.core.agent.tool.ToolInvocationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

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

    /** 自报阻塞前置：必须先读过子任务详情（知情拒绝闸门，见 {@code internal_report_blocked}）。 */
    private static final String TOOL_GET_SUB_TASK_DETAIL = "internal_get_sub_task_detail";
    /** 阻塞原因最短长度（防敷衍式「做不到」）。 */
    private static final int MIN_BLOCKED_REASON_CHARS = 10;
    /** 人工介入原因码（写入 {@code context.manualIntervention}，供兜底任务识别）。 */
    private static final String BLOCKED_REASON_CODE = "executor_reported_blocked";

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