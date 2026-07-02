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
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) Long subTaskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int pageSize) {
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
}
