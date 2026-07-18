package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.admin.AgentCreateRequest;
import com.helloai.api.dto.admin.AgentUpdateRequest;
import com.helloai.api.dto.admin.SleepBatchRequest;
import com.helloai.api.dto.agent.*;
import com.helloai.common.base.R;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.config.AgentConfigProperties;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.ActivityLog;
import com.helloai.core.task.entity.RewardLog;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.system.service.PromptTemplateService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final PromptTemplateService promptTemplateService;
    private final AgentConfigProperties agentConfigProperties;

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
            vo.setApiKey(a.getApiKey());
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
            // v2.5.x #9 enrichment 字段映射补齐：last_seen_at/last_active_at → lastRequestAt/lastActivityAt
            vo.setLastRequestAt(a.getLastSeenAt());
            vo.setLastActivityAt(a.getLastActiveAt());

            return vo;
        }));
    }

    // ══════════════════════════════════════════════════════════════
    //  详情（enrichment）
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/{id}")
    public R<AgentDetailVO> getById(@PathVariable("id") Long id) {
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
        // v2.5.x #9 enrichment 字段映射补齐：last_seen_at/last_active_at → lastRequestAt/lastActivityAt
        vo.setLastRequestAt(agent.getLastSeenAt());
        vo.setLastActivityAt(agent.getLastActiveAt());

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
    public R<Void> update(@PathVariable("id") Long id, @RequestBody AgentUpdateRequest req) {
        log.info("更新 Agent 请求: id={}, body={}", id, req);
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

    @PutMapping("/status/{id}")
    public R<Void> updateStatus(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        AgentStatus status = AgentStatus.valueOf(body.get("status").toUpperCase());
        agentService.updateStatus(id, status);
        return R.ok();
    }

    // ══════════════════════════════════════════════════════════════
    //  阶段 4.3 SLEEPING 状态管理（v2.4 §4.3）
    //  - sleep/wake 只切 online_status，不动 AgentStatus（管理态/计算态分离）
    //  - 系统不自动 SLEEPING（HeartbeatService + AgentHealthCheckTask + AgentMapper 已防护）
    // ══════════════════════════════════════════════════════════════

    /**
     * 管理员手动暂停 Agent（v2.4 §4.3）。
     * <p>设 online_status=SLEEPING，不动 AgentStatus/oldline_reason/offline_at。
     * <br>仅 X-Admin-Token 鉴权的管理员可调用（AuthInterceptor 已拦截）。
     */
    @PutMapping("/sleep/{id}")
    public R<Map<String, Object>> sleep(@PathVariable("id") Long id,
                                         @RequestBody(required = false) Map<String, String> body,
                                         HttpServletRequest request) {
        String operator = operatorOf(request);
        String reason = body == null ? null : body.get("reason");
        Agent agent = agentService.sleepAgent(id, operator, reason);
        return R.ok(toSleepWakeVO(agent, "sleep"));
    }

    /**
     * 管理员手动恢复 Agent（v2.4 §4.3）。
     * <p>设 online_status=OFFLINE（不强行 ONLINE，让系统心跳自然计算 IDLE/ONLINE）。
     * <br>仅 X-Admin-Token 鉴权的管理员可调用。
     */
    @PutMapping("/wake/{id}")
    public R<Map<String, Object>> wake(@PathVariable("id") Long id,
                                        @RequestBody(required = false) Map<String, String> body,
                                        HttpServletRequest request) {
        String operator = operatorOf(request);
        String reason = body == null ? null : body.get("reason");
        Agent agent = agentService.wakeAgent(id, operator, reason);
        return R.ok(toSleepWakeVO(agent, "wake"));
    }

    /**
     * 批量暂停 Agent（v2.4 §4.3 批次 3）。
     *
     * <p>支持部分成功/失败：返回结构见 {@code AgentService.sleepAgentBatch}。
     * <br>仅 X-Admin-Token 鉴权的管理员可调用。
     */
    @PostMapping("/sleep-batch")
    public R<Map<String, Object>> sleepBatch(@RequestBody SleepBatchRequest req,
                                              HttpServletRequest request) {
        if (req == null || req.getAgentIds() == null || req.getAgentIds().isEmpty()) {
            return R.fail("agentIds 不能为空");
        }
        String operator = operatorOf(request);
        Map<String, Object> result = agentService.sleepAgentBatch(
                req.getAgentIds(), operator, req.getReason());
        return R.ok(result);
    }

    /**
     * 查询 SLEEPING 状态的 Agent 列表（v2.4 §4.3 批次 3）。
     *
     * @param role 可选；为空时返回所有角色的 SLEEPING Agent
     * <br>仅 X-Admin-Token 鉴权的管理员可调用。
     */
    @GetMapping("/sleeping")
    public R<List<SleepingAgentVO>> sleeping(
            @RequestParam(value = "role", required = false) String role) {
        AgentRole roleFilter = (role != null && !role.isBlank())
                ? AgentRole.valueOf(role.toUpperCase()) : null;
        List<Agent> agents = agentService.findSleepingByRole(roleFilter);
        List<SleepingAgentVO> vos = new ArrayList<>(agents.size());
        for (Agent a : agents) {
            SleepingAgentVO vo = new SleepingAgentVO();
            vo.setId(a.getId());
            vo.setName(a.getName());
            vo.setRole(a.getRole());
            vo.setOnlineStatus(a.getOnlineStatus());
            vo.setUpdateBy(a.getUpdateBy());
            vo.setUpdateTime(a.getUpdateTime());
            vos.add(vo);
        }
        log.debug("查询 SLEEPING Agent: role={}, count={}", roleFilter, vos.size());
        return R.ok(vos);
    }

    /**
     * 从 request 属性中取操作人（AuthInterceptor 注入的 _authName），
     * 未拿到时回退 "admin"（与 Service 层的默认一致）。
     */
    private String operatorOf(HttpServletRequest request) {
        Object name = request.getAttribute("_authName");
        return name != null ? String.valueOf(name) : "admin";
    }

    /**
     * sleep/wake 响应 VO：返回 agent 关键状态变更信息。
     */
    private Map<String, Object> toSleepWakeVO(Agent agent, String action) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("agentId", agent.getId());
        vo.put("agentName", agent.getName());
        vo.put("action", action);
        vo.put("onlineStatus", agent.getOnlineStatus());
        vo.put("updateBy", agent.getUpdateBy());
        vo.put("updateTime", agent.getUpdateTime());
        return vo;
    }

    // ══════════════════════════════════════════════════════════════
    //  重置 Key
    // ══════════════════════════════════════════════════════════════

    @PostMapping("/reset-key/{id}")
    public R<ApiKeyResponse> resetKey(@PathVariable("id") Long id) {
        String newKey = agentService.resetApiKey(id);
        ApiKeyResponse response = new ApiKeyResponse();
        response.setApiKey(newKey);
        return R.ok(response);
    }

    // ══════════════════════════════════════════════════════════════
    //  接入内容生成（管理员视角一键生成 Agent onboarding 文本）
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/{id}/onboarding-content")
    public R<AgentOnboardingResponse> onboardingContent(@PathVariable("id") Long id,
                                                        HttpServletRequest request) {
        Agent agent = agentService.getById(id);
        if (agent == null) {
            return R.fail("Agent 不存在");
        }

        // 1. 解析 baseUrl（优先配置，否则从请求推导）
        String baseUrl = agentConfigProperties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = request.getScheme() + "://"
                    + request.getServerName() + ":"
                    + request.getServerPort();
        }

        // 2. 获取纯 SKILL 内容（变量已替换）
        String skillContent = promptTemplateService.getSkillForAgent(
                agent.getRole().name(), agent.getApiKey(), baseUrl, agent.getName(), agent.getId());

        // 3. 拼装完整接入内容
        String content = promptTemplateService.buildOnboardingContent(
                agent.getRole().name(), agent.getApiKey(), baseUrl, agent.getName(), agent.getId());

        // 4. 组装响应
        AgentOnboardingResponse resp = new AgentOnboardingResponse();
        resp.setAgentId(agent.getId());
        resp.setAgentName(agent.getName());
        resp.setRole(agent.getRole().name());
        resp.setApiKey(agent.getApiKey());
        resp.setBaseUrl(baseUrl);
        resp.setTitle("复制到 Trae / Qoder 的接入内容");
        resp.setContent(content);
        resp.setSkillContent(skillContent);

        return R.ok(resp);
    }

    // ══════════════════════════════════════════════════════════════
    //  关联数据统计（删除前风险提示）
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/{id}/related-counts")
    public R<AgentRelatedCounts> relatedCounts(@PathVariable("id") Long id) {
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
    public R<AgentDeleteResult> delete(@PathVariable("id") Long id,
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
            @PathVariable("id") Long id,
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
            @PathVariable("id") Long id,
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
