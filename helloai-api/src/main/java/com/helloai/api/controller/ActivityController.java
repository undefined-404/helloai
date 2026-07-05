package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.common.base.R;
import com.helloai.core.entity.ActivityLog;
import com.helloai.core.mapper.ActivityLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityLogMapper activityLogMapper;

    /**
     * 活动列表（与前端 activityApi 对齐）
     */
    @GetMapping
    public R<?> list(
            @RequestParam(value = "agentId", required = false) Long agentId,
            @RequestParam(value = "subTaskId", required = false) Long subTaskId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "30") int pageSize) {
        var wrapper = new LambdaQueryWrapper<ActivityLog>()
                .eq(agentId != null, ActivityLog::getAgentId, agentId)
                .eq(subTaskId != null, ActivityLog::getSubTaskId, subTaskId)
                .orderByDesc(ActivityLog::getCreateTime);

        if (page <= 0) {
            return R.ok(activityLogMapper.selectList(wrapper));
        }
        Page<ActivityLog> result = activityLogMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return R.ok(PageResult.of(result));
    }

    /**
     * Agent 写入活动日志。
     * 认证由 AuthInterceptor 处理（从 _authId 获取 agentId）。
     */
    @PostMapping
    public R<ActivityLog> create(@RequestBody Map<String, Object> body,
                                  @RequestAttribute("_authId") Long agentId) {
        ActivityLog entity = new ActivityLog();
        entity.setAgentId(agentId);
        entity.setAction((String) body.get("action"));
        entity.setLevel((String) body.getOrDefault("level", "INFO"));
        entity.setSource((String) body.getOrDefault("source", "agent"));
        if (body.get("subTaskId") != null) {
            entity.setSubTaskId(Long.valueOf(body.get("subTaskId").toString()));
        }
        if (body.get("detail") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> detail = (Map<String, Object>) body.get("detail");
            entity.setDetail(detail);
        }
        activityLogMapper.insert(entity);
        log.info("Agent 活动日志写入: agentId={}, action={}, subTaskId={}", agentId, entity.getAction(), entity.getSubTaskId());
        return R.ok(entity);
    }
}
