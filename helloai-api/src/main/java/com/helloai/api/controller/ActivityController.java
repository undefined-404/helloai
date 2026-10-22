package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.api.dto.PageResult;
import com.helloai.common.base.R;
import com.helloai.core.agent.entity.ActivityLog;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.ActivityLogService;
import com.helloai.core.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 活动日志接口（与前端 activityApi 对齐）。
 *
 * <p>本 Controller 已按 §6.3 分层红线收口：仅做参数接收、Service 调用与返回封装，
 * 查询条件构造、Mapper 调用均下移至 {@link ActivityLogService}。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityLogService activityLogService;
    private final AgentService agentService;

    /**
     * 活动列表（与前端 activityApi 对齐）。
     *
     * <p>{@code page <= 0} 时返回全量列表（IPage 中只填 records），
     * 否则按分页参数返回（PageResult 包含 total/pages/current）。</p>
     *
     * <p>2026-10-22：列表增强——每条记录附带 {@code agentName} 与 {@code agentAccessType}
     * （活动流页面展示「哪个 Agent 干的 + 接入类型标签」）。用 {@code listProfilesByIds}
     * 批量投影，避免逐条 N+1。</p>
     */
    @GetMapping("/list")
    public R<?> list(
            @RequestParam(value = "agentId", required = false) Long agentId,
            @RequestParam(value = "subTaskId", required = false) Long subTaskId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "30") int pageSize) {
        IPage<ActivityLog> result = activityLogService.list(agentId, subTaskId, page, pageSize);
        if (page <= 0) {
            return R.ok(decorate(result.getRecords()));
        }
        Map<Long, AgentProfileSnapshot> profileMap = profilesOf(result.getRecords());
        return R.ok(PageResult.of(result, log -> toView(log, profileMap)));
    }

    /** 给活动日志补 agent 名与接入类型标签（批量投影，避免 N+1）。 */
    private List<Map<String, Object>> decorate(List<ActivityLog> records) {
        Map<Long, AgentProfileSnapshot> profileMap = profilesOf(records);
        return records.stream().map(log -> toView(log, profileMap)).collect(Collectors.toList());
    }

    /** 一次取全部涉及 Agent 的画像快照（id → 快照）。 */
    private Map<Long, AgentProfileSnapshot> profilesOf(List<ActivityLog> records) {
        Set<Long> agentIds = records.stream()
                .map(ActivityLog::getAgentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (agentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return agentService.listProfilesByIds(agentIds).stream()
                .collect(Collectors.toMap(AgentProfileSnapshot::id, p -> p, (a, b) -> a));
    }

    /** 单条活动日志 → 视图 Map（保留原字段，追加 agentName / agentAccessType）。 */
    private Map<String, Object> toView(ActivityLog log, Map<Long, AgentProfileSnapshot> profileMap) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", log.getId());
        view.put("agentId", log.getAgentId());
        view.put("subTaskId", log.getSubTaskId());
        view.put("action", log.getAction());
        view.put("level", log.getLevel());
        view.put("source", log.getSource());
        view.put("detail", log.getDetail());
        view.put("createTime", log.getCreateTime());
        AgentProfileSnapshot profile = profileMap.get(log.getAgentId());
        view.put("agentName", profile == null ? null : profile.name());
        view.put("agentAccessType", profile == null ? null : profile.accessType());
        return view;
    }

    /**
     * Agent 写入活动日志。
     *
     * <p>认证由 AuthInterceptor 处理（从 _authId 获取 agentId）。</p>
     */
    @PostMapping
    public R<ActivityLog> create(@RequestBody Map<String, Object> body,
                                  @RequestAttribute("_authId") Long agentId) {
        String action = (String) body.get("action");
        String level = (String) body.getOrDefault("level", "INFO");
        String source = (String) body.getOrDefault("source", "agent");
        Long subTaskId = null;
        if (body.get("subTaskId") != null) {
            subTaskId = Long.valueOf(body.get("subTaskId").toString());
        }
        Map<String, Object> detail = null;
        if (body.get("detail") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) body.get("detail");
            detail = raw;
        }
        ActivityLog entity = activityLogService.record(agentId, action, level, source, subTaskId, detail);
        return R.ok(entity);
    }
}