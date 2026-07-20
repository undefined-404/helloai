package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.core.system.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 前端主页面 Dashboard 统计入口（{@code /api/dashboard/stats}）。
 *
 * <p>Mapper 调用与聚合计算已全部下沉至 {@link DashboardService}，
 * Controller 仅做 Map → R.ok 透传（§6.7 聚合看板允许返回
 * {@code Map<String,Object>}）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * Dashboard 统计（totalTasks / activeSubTasks / pendingReviews /
     * blockedTasks / agentRanking / throughput）。
     */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(dashboardService.getStats());
    }
}