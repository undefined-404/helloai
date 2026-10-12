package com.helloai.core.agent.runtime.tool;

import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.service.McpToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

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
 * <p><b>鉴权纪律</b>：本类方法<b>不</b>做 {@code McpAuthContext.requireAuthId}——
 * {@code agentId} 由调用方（装配器）以内部执行链真实的执行者 ID 注入，调用方为其合法性负责
 * （进程内可信边界，非外部持牌通道）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalLlmExecutorTools {

    private final McpToolService mcpToolService;
    private final AttachmentPort attachmentPort;

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
            @ToolParam(description = "内部执行链鉴定的执行者 Agent ID", required = true) Long agentId,
            @ToolParam(description = "目标子任务 ID", required = true) Long subTaskId) {
        return mcpToolService.getSubTaskDetail(agentId, subTaskId);
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
            @ToolParam(description = "执行者 Agent ID", required = true) Long agentId,
            @ToolParam(description = "当前子任务 ID", required = true) Long subTaskId) {
        return mcpToolService.getDepsSummary(agentId, subTaskId);
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
            @ToolParam(description = "执行者 Agent ID", required = true) Long agentId) {
        return mcpToolService.getAgentStatus(agentId);
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
            @ToolParam(description = "执行者 Agent ID", required = true) Long agentId,
            @ToolParam(description = "目标子任务 ID", required = true) Long subTaskId,
            @ToolParam(description = "文件名", required = true) String fileName,
            @ToolParam(description = "文件 MIME 类型（可空）", required = false) String mimeType,
            @ToolParam(description = "文件字节数（可空）", required = false) Long fileSize,
            @ToolParam(description = "产物存储地址（受控白名单内）", required = true) String storageUrl) {
        Long attachmentId = attachmentPort.register(
                agentId, subTaskId, fileName,
                mimeType != null ? mimeType : "application/octet-stream",
                fileSize != null ? fileSize : 0L,
                storageUrl);
        return java.util.Map.of("attachmentId", attachmentId, "subTaskId", subTaskId);
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
            @ToolParam(description = "执行者 Agent ID", required = true) Long agentId,
            @ToolParam(description = "附件主键（来自 internal_get_sub_task_detail 的 attachments）", required = true) Long attachmentId) {
        byte[] content = attachmentPort.loadContent(attachmentId);
        return new String(content, java.nio.charset.StandardCharsets.UTF_8);
    }
}