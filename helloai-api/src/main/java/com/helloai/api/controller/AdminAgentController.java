package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.admin.AgentCreateRequest;
import com.helloai.api.dto.admin.AgentUpdateRequest;
import com.helloai.api.dto.agent.*;
import com.helloai.common.base.R;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.ActivityLog;
import com.helloai.core.entity.RewardLog;
import com.helloai.core.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理端 Agent 控制器。
 * 提供 Agent 分页列表（含 workload enrichment）、详情、CRUD、状态切换、
 * 重置 API Key、级联删除、积分明细、活动日志等管理功能。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/agents")
@RequiredArgsConstructor
public class AdminAgentController {

    private final AgentService agentService;

    // ══════════════════════════════════════════════════════════════
    //  列表（分页 + 筛选 + enrichment）
    // ══════════════════════════════════════════════════════════════

    @GetMapping
    public R<PageResult<AgentListItemVO>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "sortBy", defaultValue = "createdAt") String sortBy,
            @RequestParam(value = "sortOrder", defaultValue = "desc") String sortOrder) {

        AgentRole roleFilter = (role != null && !role.isBlank())
                ? AgentRole.valueOf(role.toUpperCase()) : null;
        AgentStatus statusFilter = (status != null && !status.isBlank())
                ? AgentStatus.valueOf(status.toUpperCase()) : null;

        Page<Agent> result = agentService.listAgentsPaged(
                page, pageSize, roleFilter, statusFilter, keyword, sortBy, sortOrder);

        return R.ok(PageResult.of(result, a -> {
            AgentListItemVO vo = new AgentListItemVO();
            vo.setId(a.getId());
            vo.setName(a.getName());
            vo.setRole(a.getRole());
            vo.setDescription(a.getRemark());
            vo.setStatus(a.getStatus());
            vo.setTotalScore(a.getScore());

            // enrichment
            Map<String, Integer> wl = agentService.workloadStats(a.getId());
            vo.setAssignedCount(wl.getOrDefault("assignedCount", 0));
            vo.setInProgressCount(wl.getOrDefault("inProgressCount", 0));
            vo.setDoneCount(wl.getOrDefault("doneCount", 0));
            vo.setBlockedCount(wl.getOrDefault("blockedCount", 0));
            vo.setReviewCount(wl.getOrDefault("reviewCount", 0));
            vo.setRank(agentService.scoreRank(a.getId()));
            vo.setCreatedAt(a.getCreateTime());

            return vo;
        }));
    }

    // ══════════════════════════════════════════════════════════════
    //  详情（enrichment）
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/{id}")
    public R<AgentDetailVO> getById(@PathVariable Long id) {
        Agent agent = agentService.getAgentDetail(id);
        if (agent == null) return R.fail("Agent 不存在");

        AgentDetailVO vo = new AgentDetailVO();
        vo.setId(agent.getId());
        vo.setName(agent.getName());
        vo.setRole(agent.getRole());
        vo.setDescription(agent.getRemark());
        vo.setStatus(agent.getStatus());
        vo.setTotalScore(agent.getScore());
        vo.setApiKey(agent.getApiKey());
        vo.setModelType(agent.getModelType());
        vo.setSpecializationSlug(agent.getSpecializationSlug());

        Map<String, Integer> wl = agentService.workloadStats(id);
        vo.setAssignedCount(wl.getOrDefault("assignedCount", 0));
        vo.setInProgressCount(wl.getOrDefault("inProgressCount", 0));
        vo.setDoneCount(wl.getOrDefault("doneCount", 0));
        vo.setBlockedCount(wl.getOrDefault("blockedCount", 0));
        vo.setReviewCount(wl.getOrDefault("reviewCount", 0));
        vo.setRank(agentService.scoreRank(id));
        vo.setCreatedAt(agent.getCreateTime());

        // 统计奖励/惩罚次数
        vo.setTotalAgents( Integer.parseInt(agentService.lambdaQuery().count().toString()));
        vo.setTotalRewardRecords((int) agentService.getScoreLogs(id, 1, 1).getTotal());
        vo.setRewardCount((int) agentService.getScoreLogs(id, 1, 99999)
                .getRecords().stream().filter(r -> r.getDelta() > 0).count());
        vo.setPenaltyCount((int) agentService.getScoreLogs(id, 1, 99999)
                .getRecords().stream().filter(r -> r.getDelta() < 0).count());

        return R.ok(vo);
    }

    // ══════════════════════════════════════════════════════════════
    //  创建
    // ══════════════════════════════════════════════════════════════

    @PostMapping
    public R<AgentRegistrationResponse> create(@RequestBody AgentCreateRequest req) {
        AgentRole role = AgentRole.valueOf(req.getRole().toUpperCase());
        Agent agent = agentService.register(req.getName(), role, req.getRemark());
        agent.setModelType(req.getModelType());
        if (req.getModelConfig() != null) agent.setModelConfig(req.getModelConfig());
        if (req.getSpecializationSlug() != null) agent.setSpecializationSlug(req.getSpecializationSlug());
        agentService.updateById(agent);

        AgentRegistrationResponse response = new AgentRegistrationResponse();
        response.setId(agent.getId());
        response.setName(agent.getName());
        response.setRole(agent.getRole().name());
        response.setApiKey(agent.getApiKey());
        return R.ok(response);
    }

    // ══════════════════════════════════════════════════════════════
    //  更新
    // ══════════════════════════════════════════════════════════════

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody AgentUpdateRequest req) {
        Agent agent = agentService.getById(id);
        if (agent == null) return R.fail("Agent 不存在");

        if (req.getName() != null) agent.setName(req.getName());
        if (req.getModelType() != null) agent.setModelType(req.getModelType());
        if (req.getModelConfig() != null) agent.setModelConfig(req.getModelConfig());
        if (req.getSpecializationSlug() != null) agent.setSpecializationSlug(req.getSpecializationSlug());
        if (req.getRemark() != null) agent.setRemark(req.getRemark());
        agentService.updateById(agent);
        return R.ok();
    }

    // ══════════════════════════════════════════════════════════════
    //  状态
    // ══════════════════════════════════════════════════════════════

    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        AgentStatus status = AgentStatus.valueOf(body.get("status").toUpperCase());
        agentService.updateStatus(id, status);
        return R.ok();
    }

    // ══════════════════════════════════════════════════════════════
    //  重置 Key
    // ══════════════════════════════════════════════════════════════

    @PostMapping("/{id}/reset-key")
    public R<ApiKeyResponse> resetKey(@PathVariable Long id) {
        String newKey = agentService.resetApiKey(id);
        ApiKeyResponse response = new ApiKeyResponse();
        response.setApiKey(newKey);
        return R.ok(response);
    }

    // ══════════════════════════════════════════════════════════════
    //  关联数据统计（删除前风险提示）
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/{id}/related-counts")
    public R<AgentRelatedCounts> relatedCounts(@PathVariable Long id) {
        Map<String, Object> counts = agentService.getRelatedCounts(id);
        AgentRelatedCounts vo = new AgentRelatedCounts();
        vo.setAgentId((Long) counts.get("agentId"));
        vo.setAgentName((String) counts.get("agentName"));
        vo.setSubTaskCount((Integer) counts.get("subTaskCount"));
        vo.setReviewCount((Integer) counts.get("reviewCount"));
        vo.setRewardCount((Integer) counts.get("rewardCount"));
        vo.setActivityCount((Integer) counts.get("activityCount"));
        vo.setPatrolCount((Integer) counts.get("patrolCount"));
        return R.ok(vo);
    }

    // ══════════════════════════════════════════════════════════════
    //  级联删除
    // ══════════════════════════════════════════════════════════════

    @DeleteMapping("/{id}")
    public R<AgentDeleteResult> delete(@PathVariable Long id,
                                        @RequestBody Map<String, String> body) {
        String confirmName = body.get("confirmName");
        if (confirmName == null || confirmName.isBlank()) {
            return R.fail("请输入 Agent 名称以确认删除");
        }
        Map<String, Object> result = agentService.deleteAgentCascade(id, confirmName);
        AgentDeleteResult vo = new AgentDeleteResult();
        vo.setAgentName((String) result.get("agentName"));
        vo.setSubTaskCount((Integer) result.get("subTaskCount"));
        vo.setReviewCount((Integer) result.get("reviewCount"));
        vo.setRewardCount((Integer) result.get("rewardCount"));
        vo.setActivityCount((Integer) result.get("activityCount"));
        vo.setPatrolCount((Integer) result.get("patrolCount"));
        return R.ok(vo);
    }

    // ══════════════════════════════════════════════════════════════
    //  积分明细（分页）
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/{id}/score-logs")
    public R<PageResult<ScoreLogItem>> scoreLogs(
            @PathVariable Long id,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        Page<RewardLog> result = agentService.getScoreLogs(id, page, pageSize);
        return R.ok(PageResult.of(result, r -> {
            ScoreLogItem item = new ScoreLogItem();
            item.setId(r.getId());
            item.setAgentId(r.getAgentId());
            item.setSubTaskId(r.getSubTaskId());
            item.setReason(r.getReason());
            item.setDelta(r.getDelta());
            item.setBalance(r.getBalance());
            item.setCreateTime(r.getCreateTime());
            return item;
        }));
    }

    // ══════════════════════════════════════════════════════════════
    //  活动日志（分页）
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/{id}/activity-logs")
    public R<PageResult<ActivityLogItem>> activityLogs(
            @PathVariable Long id,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "action", required = false) String action) {
        Page<ActivityLog> result = agentService.getActivityLogs(id, page, pageSize, action);
        return R.ok(PageResult.of(result, r -> {
            ActivityLogItem item = new ActivityLogItem();
            item.setId(r.getId());
            item.setAgentId(r.getAgentId());
            item.setSubTaskId(r.getSubTaskId());
            item.setAction(r.getAction());
            item.setSummary(r.getAction());
            item.setLevel(r.getLevel());
            item.setCreateTime(r.getCreateTime());
            return item;
        }));
    }
}
