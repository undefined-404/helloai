package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.api.dto.PageResult;
import com.helloai.common.base.R;
import com.helloai.core.task.entity.ActivityLog;
import com.helloai.core.task.service.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    /**
     * 活动列表（与前端 activityApi 对齐）。
     *
     * <p>{@code page <= 0} 时返回全量列表（IPage 中只填 records），
     * 否则按分页参数返回（PageResult 包含 total/pages/current）。</p>
     */
    @GetMapping("/list")
    public R<?> list(
            @RequestParam(value = "agentId", required = false) Long agentId,
            @RequestParam(value = "subTaskId", required = false) Long subTaskId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "30") int pageSize) {
        IPage<ActivityLog> result = activityLogService.list(agentId, subTaskId, page, pageSize);
        if (page <= 0) {
            return R.ok(result.getRecords());
        }
        return R.ok(PageResult.of(result));
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