package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.duty.DutyLeaseResponse;
import com.helloai.api.dto.duty.DutyOverviewResponse;
import com.helloai.common.base.R;
import com.helloai.common.constant.AgentDutyLeaseStatus;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 值班租约只读报表入口（AgentHub V1 P1）。
 *
 * <p>为运营看板提供值班租约的分页列表与状态概览。纯只读，
 * 写入语义（checkIn/checkOut/续约/过期扫描）仍分别归属
 * MCP 工具、{@code AgentDutyLeaseService} 与 {@code DutyLeaseExpirationTask}。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/duty-leases")
@RequiredArgsConstructor
public class AgentDutyLeaseController {

    private final AgentDutyLeaseService agentDutyLeaseService;

    /**
     * 分页查询值班租约，可按 Agent、状态过滤。
     */
    @GetMapping
    public R<PageResult<DutyLeaseResponse>> list(
            @RequestParam(value = "agentId", required = false) Long agentId,
            @RequestParam(value = "status", required = false) AgentDutyLeaseStatus status,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        IPage<AgentDutyLease> result = agentDutyLeaseService.listLeases(agentId, status, page, size);
        // 一次性查询本页涉及的 Agent 名称（避免 N+1）
        Map<Long, String> nameMap = agentDutyLeaseService.getAgentNamesByIds(
                result.getRecords().stream()
                        .map(AgentDutyLease::getAgentId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()));
        return R.ok(PageResult.of(result, lease -> toResponse(lease, nameMap)));
    }

    /**
     * 值班租约状态概览（看板顶部卡片数据源）。
     */
    @GetMapping("/overview")
    public R<DutyOverviewResponse> overview() {
        Map<AgentDutyLeaseStatus, Long> counts = agentDutyLeaseService.countByStatus();
        long active = counts.getOrDefault(AgentDutyLeaseStatus.ACTIVE, 0L);
        long closed = counts.getOrDefault(AgentDutyLeaseStatus.CLOSED, 0L);
        long expired = counts.getOrDefault(AgentDutyLeaseStatus.EXPIRED, 0L);

        DutyOverviewResponse resp = new DutyOverviewResponse();
        resp.setActiveCount(active);
        resp.setClosedCount(closed);
        resp.setExpiredCount(expired);
        resp.setTotalCount(active + closed + expired);
        return R.ok(resp);
    }

    private DutyLeaseResponse toResponse(AgentDutyLease lease, Map<Long, String> nameMap) {
        DutyLeaseResponse resp = new DutyLeaseResponse();
        resp.setId(lease.getId());
        resp.setAgentId(lease.getAgentId());
        resp.setSessionId(lease.getSessionId());
        resp.setWorkMode(lease.getWorkMode());
        resp.setMaxConcurrent(lease.getMaxConcurrent());
        resp.setStatus(lease.getStatus());
        resp.setStartedAt(lease.getStartTime());
        resp.setLastRenewedAt(lease.getLastRenewTime());
        resp.setExpiresAt(lease.getExpireTime());
        resp.setCloseReason(lease.getCloseReason());
        if (lease.getAgentId() != null) {
            String name = nameMap.get(lease.getAgentId());
            if (name != null) {
                resp.setAgentName(name);
            }
        }
        return resp;
    }
}