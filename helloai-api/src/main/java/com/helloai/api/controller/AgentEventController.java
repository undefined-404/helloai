package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.event.AgentEventItem;
import com.helloai.api.dto.event.RecoveryPointItem;
import com.helloai.common.base.R;
import com.helloai.core.agent.event.AgentEventQueryService;
import com.helloai.core.agent.event.AgentEventTraceItem;
import com.helloai.core.agent.event.RecoveryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 事件流读侧端点（Replay / Audit 暴露层）。
 *
 * <p>只读端点：从 append-only 的 {@code agent_event} 重建执行轨迹 / 分页审计事实
 * （G-001 验收「一个 Run 可以按 sequence 重建轨迹」的 API 面）；Replay 仅读取历史，
 * 不代表再次产生副作用。Controller 只做参数归一 + 调用 service + DTO 映射，不含编排。</p>
 *
 * <p>所属端点：
 * <ul>
 *   <li>GET /api/agent-events/traceByRunId/{runId}</li>
 *   <li>GET /api/agent-events/traceByTaskId/{taskId}</li>
 *   <li>GET /api/agent-events/traceBySubTaskId/{subTaskId}</li>
 *   <li>GET /api/agent-events/pageAuditByTaskId/{taskId}</li>
 *   <li>GET /api/agent-events/recoveryPointBySubTaskId/{subTaskId}</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/agent-events")
@RequiredArgsConstructor
public class AgentEventController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final int MAX_PAGE_SIZE = 100;

    private final AgentEventQueryService agentEventQueryService;

    /**
     * Replay：按 Run 读取完整执行轨迹（run-{taskId}-{roundNum}）。
     *
     * <p>按 {@code createTime ASC, id ASC} 有序返回 Turn / Step 全量轨迹投影；
     * runId 为空或空白时返回空列表（service 短路）。</p>
     */
    @GetMapping("/traceByRunId/{runId}")
    public R<List<AgentEventItem>> traceByRunId(@PathVariable("runId") String runId) {
        List<AgentEventTraceItem> trace = agentEventQueryService.traceByRunId(runId);
        List<AgentEventItem> items = trace.stream().map(this::toItem).toList();
        return R.ok(items);
    }

    /**
     * Replay：按 Task 读取 Run 级完整轨迹（任务维度入口，免传 runId）。
     *
     * <p>由 service 按 ADR-001 §3.1 标识规则内部推导 runId，语义等价
     * {@link #traceByRunId(String)}；任务列表 / 子任务详情页深链与工作台任务选择器均走此端点。</p>
     */
    @GetMapping("/traceByTaskId/{taskId}")
    public R<List<AgentEventItem>> traceByTaskId(@PathVariable("taskId") Long taskId) {
        List<AgentEventTraceItem> trace = agentEventQueryService.traceByTaskId(taskId);
        List<AgentEventItem> items = trace.stream().map(this::toItem).toList();
        return R.ok(items);
    }

    /**
     * Replay：按子任务读取有序执行轨迹（子任务维度聚焦过滤）。
     *
     * <p>透传 service 已有的 {@code traceBySubTaskId} 读侧投影（A6），供工作台
     * 子任务选择器聚焦查看单个子任务的 Turn / Step 轨迹。</p>
     */
    @GetMapping("/traceBySubTaskId/{subTaskId}")
    public R<List<AgentEventItem>> traceBySubTaskId(@PathVariable("subTaskId") Long subTaskId) {
        List<AgentEventTraceItem> trace = agentEventQueryService.traceBySubTaskId(subTaskId);
        List<AgentEventItem> items = trace.stream().map(this::toItem).toList();
        return R.ok(items);
    }

    /**
     * Audit：按 Task 分页读取事件审计列表，可选事件类型 + 时间范围过滤，按写入时序正序。
     *
     * <p>page/pageSize 做最小归一（非正回默认值、pageSize 上限 100），
     * 不限制事件类型集合（由消费方按需传入，service 层空白透传不过滤）。
     * {@code timeStart}/{@code timeEnd} 接受 {@code yyyy-MM-dd HH:mm:ss} 形态字符串，
     * 与实体 {@code createTime} 字典序比较语义一致（包含两端边界），空 / 空白 = 该方向不限。</p>
     */
    @GetMapping("/pageAuditByTaskId/{taskId}")
    public R<PageResult<AgentEventItem>> pageAuditByTaskId(
            @PathVariable("taskId") Long taskId,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "timeStart", required = false) String timeStart,
            @RequestParam(value = "timeEnd", required = false) String timeEnd,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "pageSize", defaultValue = "20") long pageSize) {
        long safePage = Math.max(page, 1L);
        long safePageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        IPage<AgentEventTraceItem> result =
                agentEventQueryService.pageAuditByTaskId(taskId, eventType, timeStart, timeEnd, safePage, safePageSize);
        return R.ok(PageResult.of(result, this::toItem));
    }

    /**
     * Recovery：按子任务读取执行恢复点（第四个事件流消费面，只读）。
     *
     * <p>从 append-only 的 {@code agent_event} 推定「上一次执行尝试停在哪」（未闭合轮次 +
     * 中断点工具 + 该轮已完成工具）。与 Replay 的区别：Replay 重建<b>发生了什么</b>，
     * 本端点回答<b>从哪里继续</b>。仅读，不产生副作用、不改业务状态。</p>
     *
     * <p>无事件 / 事件流已闭合时返回 {@code unfinished=false} 的空投影（非报错），
     * 供前端直接渲染「无需恢复」。</p>
     */
    @GetMapping("/recoveryPointBySubTaskId/{subTaskId}")
    public R<RecoveryPointItem> recoveryPointBySubTaskId(@PathVariable("subTaskId") Long subTaskId) {
        return R.ok(toRecoveryPointItem(agentEventQueryService.projectRecoveryPoint(subTaskId)));
    }

    /** core 恢复点投影 → API 响应 DTO。 */
    private RecoveryPointItem toRecoveryPointItem(RecoveryPoint p) {
        RecoveryPointItem it = new RecoveryPointItem();
        it.setUnfinished(p.isUnfinished());
        it.setUnfinishedTurn(p.getUnfinishedTurn());
        it.setDanglingTool(p.getDanglingTool());
        it.setCompletedTools(p.getCompletedTools());
        it.setLastEventType(p.getLastEventType());
        it.setLastEventSequence(p.getLastEventSequence());
        it.setLastEventAt(p.getLastEventAt());
        it.setTerminalEventSeen(p.isTerminalEventSeen());
        return it;
    }

    /** core 读侧投影 → API 响应 DTO。 */
    private AgentEventItem toItem(AgentEventTraceItem e) {
        AgentEventItem it = new AgentEventItem();
        it.setId(e.getId());
        it.setEventId(e.getEventId());
        it.setRunId(e.getRunId());
        it.setTaskId(e.getTaskId());
        it.setSubTaskId(e.getSubTaskId());
        it.setTurn(e.getTurn());
        it.setStep(e.getStep());
        it.setEventType(e.getEventType());
        it.setAgentId(e.getAgentId());
        it.setPayload(e.getPayload());
        it.setCreateTime(e.getCreateTime());
        return it;
    }
}