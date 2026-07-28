package com.helloai.core.agent.mcp;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.observability.HeartbeatService;
import com.helloai.core.agent.mcp.McpToolService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * helloai MCP Server 业务工具集（v2.4 §3.1 / §9 路线 C 标准化）。
 * <p>
 * 暴露给外部 MCP Client（如 Qoder / Trae / MCP Inspector）的 8 个工具：
 * <ol>
 *   <li>{@code pullTasks} —— 拉取 Agent 待处理收件箱（v2.4 §9.1）</li>
 *   <li>{@code ack} —— 确认收件箱消息已处理（v2.4 §9.1）</li>
 *   <li>{@code claimSubTask} —— 原子认领子任务（v2.4 §9.1，并发互斥）</li>
 *   <li>{@code heartbeat} —— 心跳上报（v2.4 §9.1，refresh last_seen_at）</li>
 *   <li>{@code uploadArtifact} —— 上传产物附件元数据（v2.4 §9.1）</li>
 *   <li>{@code submitResult} —— 上交子任务执行结果（v2.5 补齐，进入统一回写入口）</li>
 *   <li>{@code reportBlocked} —— 上报任务阻塞（自动通知所有 PLANNER 排障）</li>
 *   <li>{@code getAgentStatus} —— 查询 Agent 自身状态（v2.4 §9.1 协议列，helloai 此前缺失，本类新增）</li>
 * </ol>
 * <p>
 * 设计原则：业务逻辑 <b>完全委托</b>给现有 {@link McpToolService}，本类只承担
 * <ol>
 *   <li>{@code @Tool} / {@code @ToolParam} 注解层（spring-ai 1.0 GA 标准注解；{@code @McpTool} 是 1.1.x+ 引入）</li>
 *   <li>{@code SKILL.md} 风格 description（含【何时使用】【Gotchas】段落）</li>
 *   <li>缺失工具（{@code getAgentStatus}）的补齐</li>
 * </ol>
 * <p>
 * 安全说明（待 M4 接入 auth context 后替换）：
 * 当前 {@code agentId} 字段由客户端通过 {@code @ToolParam} 传入。
 * M4 阶段会通过 spring-ai {@code McpSyncServerExchange} 从 Authorization 头提取真实 agentId，
 * 覆盖客户端传入值，确保不可伪造身份。
 * <p>
 * 兼容说明：spring-ai 1.0 GA 用 {@code @Tool}（{@code org.springframework.ai.tool.annotation.Tool}）。
 * 升级到 1.1.x+ 后应改用 {@code @McpTool}（{@code org.springframework.ai.mcp.annotation.McpTool}）。
 *
 * @author helloai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpMcpServer {

    private final McpToolService mcpToolService;
    private final AgentService agentService;
    private final HeartbeatService heartbeatService;

    /**
     * 从 _sessionId 参数直接拿 sessionId → 查 SESSION_AUTH → 返鉴权主体 ID。
     * v2.5 M4 路径 1：客户端显式透传 sessionId（spring-ai 1.1.0 不支持隐式注入）。
     */
    private Long requireAuthId(String sessionId, String _sessionId) {
        String sid = (_sessionId != null && !_sessionId.isBlank()) ? _sessionId : sessionId;
        return McpAuthContext.requireAuthId(sid);
    }

    // ================================================================
    // 1. pullTasks
    // ================================================================

    @Tool(name = "pullTasks", description = """
            【何时使用】Agent 需要查询分配给自己的待处理任务时调用。
            【调用频率】建议每 30 秒轮询一次，不要超过每 10 秒一次。
            【Gotchas】
            - 拉取后不会自动 ack，需要显式调用 ack 工具确认
            - max 参数上限 200（含 agent_mcp_server.param_constraints.max 二次约束），超出将截断
            - 返回空 messages 数组表示当前无待处理任务，不是错误
            - 不标记消息已读；客户端 pull 后崩溃不会丢失消息，下次 pull 仍能看到
            【相关工具】ack、claimSubTask、heartbeat
            """)
    public McpToolService.PullTasksResult pullTasks(
            @ToolParam(description = "Agent ID（v2.4 §9.1 协议字段；M4 鉴权接入后此值会被服务端覆盖为从 Authorization 解析的真实 agentId）", required = true) Long agentId,
            @ToolParam(description = "Agent 角色（如 EXECUTOR / PLANNER / REVIEWER / PATROL）。默认 EXECUTOR", required = false) String role,
            @ToolParam(description = "最多返回消息数。建议 20，最大 200", required = false) Integer max,
            @ToolParam(description = "MCP sessionId（推荐参数名 sessionId；旧客户端也可传 _sessionId）", required = false) String sessionId,
            @ToolParam(description = "兼容参数：MCP sessionId（旧字段名）", required = false) String _sessionId) {
        // M4 鉴权：强制用 token 解析的 agentId 覆盖客户端传值，防止越权
        Long authAgentId = requireAuthId(sessionId, _sessionId);
        if (agentId == null || !authAgentId.equals(agentId)) {
            log.warn("MCP pullTasks: 客户端传 agentId={} 被服务端覆盖为鉴权 agentId={}", agentId, authAgentId);
        }
        agentId = authAgentId;
        int effectiveMax = max == null ? 20 : Math.max(1, Math.min(max, 200));
        return mcpToolService.pullTasks(agentId, role == null || role.isBlank() ? "EXECUTOR" : role, effectiveMax);
    }

    // ================================================================
    // 2. ack
    // ================================================================

    @Tool(name = "ack", description = """
            【何时使用】Agent 确认消息（sub_task.assigned 等）已处理完毕。
            【调用频率】每条消息处理完成后调用一次。
            【Gotchas】
            - 幂等性：重复 ack 返回成功，agent_inbox.is_read 保持 1
            - messageId 格式为 "inbox-{数字}"（如 inbox-10001），源自 pullTasks 响应
            - 校验 Agent 状态：DISABLED Agent 的 ack 会抛 BizException
            【相关工具】pullTasks
            """)
    public McpToolService.AckResult ack(
            @ToolParam(description = "Agent ID（v2.4 §9.1 协议字段；M4 鉴权后会被服务端覆盖）", required = true) Long agentId,
            @ToolParam(description = "消息 ID，格式 'inbox-{id}'，来自 pullTasks 响应", required = true) String messageId,
            @ToolParam(description = "MCP sessionId（推荐参数名 sessionId；旧客户端也可传 _sessionId）", required = false) String sessionId,
            @ToolParam(description = "兼容参数：MCP sessionId（旧字段名）", required = false) String _sessionId) {
        // M4 鉴权：强制覆盖
        Long authAgentId = requireAuthId(sessionId, _sessionId);
        if (agentId == null || !authAgentId.equals(agentId)) {
            log.warn("MCP ack: 客户端传 agentId={} 被服务端覆盖为鉴权 agentId={}", agentId, authAgentId);
        }
        agentId = authAgentId;
        return mcpToolService.ack(agentId, messageId);
    }

    // ================================================================
    // 3. claimSubTask
    // ================================================================

    @Tool(name = "claimSubTask", description = """
            【何时使用】EXECUTOR 想主动领取一个 PENDING 状态的子任务（同角色竞争）。
            【调用频率】按需；每个候选子任务最多调一次。
            【Gotchas】
            - DB 原子条件更新保证并发安全：仅 status=PENDING 且 assigned_agent IS NULL 才成功
            - 重复 claim 同一子任务（已归属自己）：claimed=true 幂等成功
            - 已被他人抢走或状态已变：claimed=false（reason: already_claimed_by_other / invalid_status:xxx）
            - claimed=true 后应继续调用 start（REST POST /api/sub-tasks/start/{id}）推进到 IN_PROGRESS
            【相关工具】pullTasks
            """)
    public McpToolService.ClaimSubTaskResult claimSubTask(
            @ToolParam(description = "Agent ID（v2.4 §9.1 协议字段；M4 鉴权后会被服务端覆盖）", required = true) Long agentId,
            @ToolParam(description = "SubTask ID（要认领的子任务）", required = true) Long subTaskId,
            @ToolParam(description = "MCP sessionId（推荐参数名 sessionId；旧客户端也可传 _sessionId）", required = false) String sessionId,
            @ToolParam(description = "兼容参数：MCP sessionId（旧字段名）", required = false) String _sessionId) {
        // M4 鉴权：强制覆盖
        Long authAgentId = requireAuthId(sessionId, _sessionId);
        if (agentId == null || !authAgentId.equals(agentId)) {
            log.warn("MCP claimSubTask: 客户端传 agentId={} 被服务端覆盖为鉴权 agentId={}", agentId, authAgentId);
        }
        agentId = authAgentId;
        return mcpToolService.claimSubTask(agentId, subTaskId);
    }

    // ================================================================
    // 4. heartbeat
    // ================================================================

    @Tool(name = "heartbeat", description = """
            【何时使用】Agent 上报心跳，维持在线状态。
            【调用频率】建议每 30 秒一次，不要超过 60 秒（5 分钟无心跳会被判定 OFFLINE）。
            【效果】刷新 last_seen_at + Redis TTL（agent:heartbeat:{id} 续约 5 分钟）。
            【Gotchas】
            - SLEEPING Agent 也会响应心跳（仅刷新 last_seen_at，不覆盖 online_status=SLEEPING）
            - OFFLINE Agent 心跳后会被即时计算为 IDLE 或 ONLINE（取决于 last_active_at），并清 offline_reason / offline_at
            - 不影响 last_active_at（执行任务时该字段才更新）
            - 幂等，频繁调用无副作用
            【相关工具】getAgentStatus
            """)
    public McpToolService.HeartbeatResult heartbeat(
            @ToolParam(description = "Agent ID（v2.4 §9.1 协议字段；M4 鉴权后会被服务端覆盖）", required = true) Long agentId,
            @ToolParam(description = "MCP sessionId（推荐参数名 sessionId；旧客户端也可传 _sessionId）", required = false) String sessionId,
            @ToolParam(description = "兼容参数：MCP sessionId（旧字段名）", required = false) String _sessionId) {
        // M4 鉴权：强制覆盖
        Long authAgentId = requireAuthId(sessionId, _sessionId);
        if (agentId == null || !authAgentId.equals(agentId)) {
            log.warn("MCP heartbeat: 客户端传 agentId={} 被服务端覆盖为鉴权 agentId={}", agentId, authAgentId);
        }
        agentId = authAgentId;
        return mcpToolService.heartbeat(agentId);
    }

    // ================================================================
    // 5. uploadArtifact
    // ================================================================

    @Tool(name = "uploadArtifact", description = """
            【何时使用】EXECUTOR 执行完子任务后，注册产物附件元数据。
            【调用频率】每份产物调一次。
            【Gotchas】
            - 本工具只注册 DB 元数据记录（attachment 表）；实际文件请先 PUT 到 MinIO 再把 storageUrl 传进来
            - fileName 必填且非空；storageUrl 必填；mimeType / fileSize 选填
            - 只有自身分配的子任务（assigned_agent=agentId）才能成功上传
            - 参数约束（如 fileSize max）由 agent_mcp_server.param_constraints 强制
            【相关工具】pullTasks、claimSubTask
            """)
    public McpToolService.UploadArtifactResult uploadArtifact(
            @ToolParam(description = "Agent ID（v2.4 §9.1 协议字段；M4 鉴权后会被服务端覆盖）", required = true) Long agentId,
            @ToolParam(description = "子任务 ID（必填）", required = true) Long subTaskId,
            @ToolParam(description = "文件名（含扩展名）", required = true) String fileName,
            @ToolParam(description = "MIME 类型（如 application/json、image/png）", required = false) String mimeType,
            @ToolParam(description = "文件大小（字节），选填", required = false) Long fileSize,
            @ToolParam(description = "MinIO / S3 存储路径（已上传后），必填", required = true) String storageUrl,
            @ToolParam(description = "MCP sessionId（推荐参数名 sessionId；旧客户端也可传 _sessionId）", required = false) String sessionId,
            @ToolParam(description = "兼容参数：MCP sessionId（旧字段名）", required = false) String _sessionId) {
        // M4 鉴权：强制覆盖
        Long authAgentId = requireAuthId(sessionId, _sessionId);
        if (agentId == null || !authAgentId.equals(agentId)) {
            log.warn("MCP uploadArtifact: 客户端传 agentId={} 被服务端覆盖为鉴权 agentId={}", agentId, authAgentId);
        }
        agentId = authAgentId;
        return mcpToolService.uploadArtifact(agentId, subTaskId, fileName, mimeType, fileSize, storageUrl);
    }

    // ================================================================
    // 6. submitResult
    // ================================================================

    @Tool(name = "submitResult", description = """
            【何时使用】EXECUTOR 完成子任务后，上交执行结果（成功或失败）。
            【调用频率】每个子任务最多提交一次；重复提交必须带相同 resultId 以实现幂等。
            【Gotchas】
            - 只能提交自己名下子任务（assigned_agent 必须等于 agentId）
            - 如果子任务仍是 ASSIGNED，本工具会先推进到 IN_PROGRESS 再回写结果
            - success=true 时建议提供 output；success=false 时建议提供 error
            【相关工具】claimSubTask、uploadArtifact
            """)
    public McpToolService.SubmitResultResult submitResult(
            @ToolParam(description = "Agent ID（v2.4 §9.1 协议字段；M4 鉴权后会被服务端覆盖）", required = true) Long agentId,
            @ToolParam(description = "子任务 ID", required = true) Long subTaskId,
            @ToolParam(description = "结果幂等键（推荐必填，同一子任务重复提交需保持一致）", required = false) String resultId,
            @ToolParam(description = "是否成功（true=成功，false=失败）", required = true) Boolean success,
            @ToolParam(description = "成功输出（success=true 时建议填写）", required = false) String output,
            @ToolParam(description = "失败原因（success=false 时建议填写）", required = false) String error,
            @ToolParam(description = "结束原因（可选，如 completed/failed/timeout）", required = false) String finishReason,
            @ToolParam(description = "MCP sessionId（推荐参数名 sessionId；旧客户端也可传 _sessionId）", required = false) String sessionId,
            @ToolParam(description = "兼容参数：MCP sessionId（旧字段名）", required = false) String _sessionId) {
        Long authAgentId = requireAuthId(sessionId, _sessionId);
        if (agentId == null || !authAgentId.equals(agentId)) {
            log.warn("MCP submitResult: 客户端传 agentId={} 被服务端覆盖为鉴权 agentId={}", agentId, authAgentId);
        }
        agentId = authAgentId;
        return mcpToolService.submitResult(agentId, subTaskId, resultId, success, output, error, finishReason);
    }

    // ================================================================
    // 7. reportBlocked
    // ================================================================

    @Tool(name = "reportBlocked", description = """
            【何时使用】EXECUTOR 执行中遇到外部依赖不可用、环境缺失等无法自行解决的阻塞时调用。
            【调用频率】按需；一个子任务最多 block 一次（会进入 BLOCKED 状态）。
            【Gotchas】
            - 自动通知所有 PLANNER 角色 Agent（inbox.sub_task.blocked）排障
            - 只能 block 自己名下的子任务（assigned_agent 必须等于 agentId）
            - reason 必填，建议写明阻塞原因（如 "依赖外部 API timeout"、"测试环境未启动"）
            【相关工具】claimSubTask
            """)
    public McpToolService.ReportBlockedResult reportBlocked(
            @ToolParam(description = "Agent ID（v2.4 §9.1 协议字段；M4 鉴权后会被服务端覆盖）", required = true) Long agentId,
            @ToolParam(description = "子任务 ID", required = true) Long subTaskId,
            @ToolParam(description = "阻塞原因（必填，建议 50 字以内）", required = true) String reason,
            @ToolParam(description = "MCP sessionId（推荐参数名 sessionId；旧客户端也可传 _sessionId）", required = false) String sessionId,
            @ToolParam(description = "兼容参数：MCP sessionId（旧字段名）", required = false) String _sessionId) {
        // M4 鉴权：强制覆盖
        Long authAgentId = requireAuthId(sessionId, _sessionId);
        if (agentId == null || !authAgentId.equals(agentId)) {
            log.warn("MCP reportBlocked: 客户端传 agentId={} 被服务端覆盖为鉴权 agentId={}", agentId, authAgentId);
        }
        agentId = authAgentId;
        return mcpToolService.reportBlocked(agentId, subTaskId, reason);
    }

    // ================================================================
    // 8. getAgentStatus（v2.4 §9.1 协议列要求，helloai 此前缺失，本类新增）
    // ================================================================

    @Tool(name = "getAgentStatus", description = """
            【何时使用】Agent 启动后查询自身状态，确认鉴权 + 在线状态后再开始接活。
            【调用频率】通常 connect 后调一次；调试期间可重复调用。
            【效果】返回 Agent 的最新 onlineStatus（计算态，HeartbeatService 即时计算）。
            【Gotchas】
            - 返回 3 套字段：
              * 管理态 status（ACTIVE/DISABLED，鉴权只看这个）
              * DB 持久 onlineStatus（ONLINE/IDLE/OFFLINE/SLEEPING，计算态）
              * computedOnlineStatus（实时按 last_seen_at/last_active_at 推算）
            - DISABLED Agent 也会返回结果（仅看 status 字段判断可用性）
            - 客户端拿到的字段会反映当前心跳是否在线
            【相关工具】heartbeat
            """)
    public GetAgentStatusResult getAgentStatus(
            @ToolParam(description = "Agent ID（v2.4 §9.1 协议字段；M4 鉴权后会被服务端覆盖）", required = true) Long agentId,
            @ToolParam(description = "MCP sessionId（推荐参数名 sessionId；旧客户端也可传 _sessionId）", required = false) String sessionId,
            @ToolParam(description = "兼容参数：MCP sessionId（旧字段名）", required = false) String _sessionId) {
        // M4 鉴权：强制覆盖（与客户端传值无关，永远查 token 解析的 agent）
        Long authAgentId = requireAuthId(sessionId, _sessionId);
        if (agentId == null || !authAgentId.equals(agentId)) {
            log.warn("MCP getAgentStatus: 客户端传 agentId={} 被服务端覆盖为鉴权 agentId={}", agentId, authAgentId);
        }
        agentId = authAgentId;
        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }
        AgentOnlineStatus computed = heartbeatService.checkOnlineStatus(agent);

        GetAgentStatusResult r = new GetAgentStatusResult();
        r.setAgentId(agentId);
        r.setName(agent.getName());
        r.setRole(agent.getRole() != null ? agent.getRole().name() : null);
        r.setStatus(agent.getStatus() != null ? agent.getStatus().name() : null);
        r.setDbOnlineStatus(agent.getOnlineStatus() != null ? agent.getOnlineStatus().name() : null);
        r.setComputedOnlineStatus(computed != null ? computed.name() : null);
        r.setLastSeenAt(agent.getLastSeenTime() != null ? agent.getLastSeenTime().toString() : null);
        r.setLastActiveAt(agent.getLastActiveTime() != null ? agent.getLastActiveTime().toString() : null);
        r.setOfflineReason(agent.getOfflineReason());
        r.setOfflineAt(agent.getOfflineTime() != null ? agent.getOfflineTime().toString() : null);
        r.setServerTime(java.time.OffsetDateTime.now().toString());
        return r;
    }

    // ================================================================
    // 9. checkIn（AgentHub V1 P0-A）
    // ================================================================

    @Tool(name = "checkIn", description = """
            【何时使用】Agent 上线后声明“打卡上班”，获取一份打卡租约（需周期性 renew）。
            【调用频率】每个会话一次；重复 checkIn 安全（旧 ACTIVE 租约会先被关闭为 CLOSED）。
            【效果】写入 agent_duty_lease 一行 ACTIVE 记录，同时刷新心跳。
            【Gotchas】
            - 本工具 <b>不</b>改变 online_status / status 枚举，仅新增“在岗打卡态”事实。
            - AgentSelector 会将“当前是否在岗（已打卡）”作为软优先级最高一档（平手时已打卡 Agent 优先）。
            - ttlMinutes 建议与 Agent 自身 renew 周期匹配，默认 30 分钟；到期后会被 DutyLeaseExpirationTask
              自动翻为 EXPIRED，不会阀到商业逻辑。
            【相关工具】checkOut、heartbeat
            """)
    public McpToolService.CheckInResult checkIn(
            @ToolParam(description = "Agent ID（v2.4 §9.1 协议字段；M4 鉴权后会被服务端覆盖）", required = true) Long agentId,
            @ToolParam(description = "工作模式（如 AUTO），可为空", required = false) String workMode,
            @ToolParam(description = "最大并发子任务数，默认 1", required = false) Integer maxConcurrent,
            @ToolParam(description = "租约有效期（分钟），默认 30", required = false) Integer ttlMinutes,
            @ToolParam(description = "MCP sessionId（推荐参数名 sessionId；旧客户端也可传 _sessionId）", required = false) String sessionId,
            @ToolParam(description = "兼容参数：MCP sessionId（旧字段名）", required = false) String _sessionId) {
        Long authAgentId = requireAuthId(sessionId, _sessionId);
        if (agentId == null || !authAgentId.equals(agentId)) {
            log.warn("MCP checkIn: 客户端传 agentId={} 被服务端覆盖为鉴权 agentId={}", agentId, authAgentId);
        }
        agentId = authAgentId;
        return mcpToolService.checkIn(agentId, workMode, maxConcurrent, ttlMinutes);
    }

    // ================================================================
    // 10. checkOut（AgentHub V1 P0-A）
    // ================================================================

    @Tool(name = "checkOut", description = """
            【何时使用】Agent 主动下线 / 会话结束时声明“打卡下班”，关闭当前 ACTIVE 打卡租约。
            【调用频率】会话结束前一次；幂等（无 ACTIVE 租约时 closedCount=0）。
            【效果】agent_duty_lease 中相关 ACTIVE 行翻为 CLOSED，close_reason 记录传入值。
            【Gotchas】
            - 本工具不直接重分配已 ASSIGNED 子任务；离岗补偿仍由
              SubTaskDispatchService.redispatchAssignedTimeout 通过常规超时兜底完成。
            - checkOut 后仍可 checkIn 重新上班；两次会话不共享 sessionId。
            【相关工具】checkIn
            """)
    public McpToolService.CheckOutResult checkOut(
            @ToolParam(description = "Agent ID（v2.4 §9.1 协议字段；M4 鉴权后会被服务端覆盖）", required = true) Long agentId,
            @ToolParam(description = "关闭原因（推荐字段名 closeReason，如 shutdown / session_end / manual_close），可缺省", required = false) String closeReason,
            @ToolParam(description = "兼容参数：旧字段名 reason，等同 closeReason", required = false) String reason,
            @ToolParam(description = "MCP sessionId（推荐参数名 sessionId；旧客户端也可传 _sessionId）", required = false) String sessionId,
            @ToolParam(description = "兼容参数：MCP sessionId（旧字段名）", required = false) String _sessionId) {
        Long authAgentId = requireAuthId(sessionId, _sessionId);
        if (agentId == null || !authAgentId.equals(agentId)) {
            log.warn("MCP checkOut: 客户端传 agentId={} 被服务端覆盖为鉴权 agentId={}", agentId, authAgentId);
        }
        agentId = authAgentId;
        // 优先 closeReason（主字段名），缺失时回退 reason（兼容旧客户端）
        String effectiveReason = (closeReason != null && !closeReason.isBlank()) ? closeReason : reason;
        return mcpToolService.checkOut(agentId, effectiveReason);
    }

    @Data
    public static class GetAgentStatusResult {
        private Long agentId;
        private String name;
        /** AgentRole：PLANNER / EXECUTOR / REVIEWER / PATROL */
        private String role;
        /** AgentStatus（管理态）：ACTIVE / DISABLED */
        private String status;
        /** AgentOnlineStatus（DB 持久值，可能滞后）：ONLINE / IDLE / OFFLINE / SLEEPING */
        private String dbOnlineStatus;
        /** AgentOnlineStatus（实时按 last_seen_at/last_active_at 推算） */
        private String computedOnlineStatus;
        private String lastSeenAt;
        private String lastActiveAt;
        /** 仅 OFFLINE 时非空 */
        private String offlineReason;
        /** 仅 OFFLINE 时非空 */
        private String offlineAt;
        private String serverTime;
    }
}
