package com.helloai.api.controller;

import com.helloai.api.dto.admin.DashboardHighlights;
import com.helloai.api.dto.admin.DashboardOverview;
import com.helloai.api.dto.admin.DashboardTrend;
import com.helloai.common.base.R;
import com.helloai.core.task.observability.service.AdminDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理后台 Dashboard 接口。
 *
 * <p>本 Controller 已按 §6.3 分层红线收口：Mapper 调用、条件构造全部下移至
 * {@link AdminDashboardService}，Controller 仅做参数接收、DTO 装配与返回封装。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    /**
     * 概览统计。
     */
    @GetMapping("/getOverview")
    public R<DashboardOverview> getOverview() {
        Map<String, Long> raw = adminDashboardService.getOverview();
        DashboardOverview dto = new DashboardOverview();
        dto.setTotalTasks(raw.getOrDefault("totalTasks", 0L));
        dto.setInProgressTasks(raw.getOrDefault("inProgressTasks", 0L));
        dto.setCompletedTasks(raw.getOrDefault("completedTasks", 0L));
        dto.setBlockedTasks(raw.getOrDefault("blockedTasks", 0L));
        dto.setTotalAgents(raw.getOrDefault("totalAgents", 0L));
        dto.setActiveAgents(raw.getOrDefault("activeAgents", 0L));
        dto.setPendingReviews(raw.getOrDefault("pendingReviews", 0L));
        dto.setTodayCompleted(raw.getOrDefault("todayCompleted", 0L));
        dto.setTodayCreated(raw.getOrDefault("todayCreated", 0L));
        dto.setTotalUsers(raw.getOrDefault("totalUsers", 0L));
        return R.ok(dto);
    }

    /**
     * 高亮信息（阻塞 / 待审 / 低活跃）。
     */
    @GetMapping("/getHighlights")
    public R<DashboardHighlights> getHighlights() {
        DashboardHighlights result = new DashboardHighlights();

        // 阻塞
        List<Map<String, Object>> blocked = adminDashboardService.listBlockedHighlight();
        List<DashboardHighlights.BlockedTaskItem> blockedItems = blocked.stream().map(m -> {
            DashboardHighlights.BlockedTaskItem item = new DashboardHighlights.BlockedTaskItem();
            item.setSubTaskId(asLong(m.get("subTaskId")));
            item.setSubTaskTitle((String) m.get("subTaskTitle"));
            item.setPriority((String) m.get("priority"));
            item.setTaskId(asLong(m.get("taskId")));
            item.setTaskTitle((String) m.get("taskTitle"));
            return item;
        }).toList();
        result.setBlockedTasks(blockedItems);
        result.setTotalBlocked(blocked.size());

        // 待审
        List<Map<String, Object>> review = adminDashboardService.listReviewHighlight();
        List<DashboardHighlights.PendingReviewItem> reviewItems = review.stream().map(m -> {
            DashboardHighlights.PendingReviewItem item = new DashboardHighlights.PendingReviewItem();
            item.setSubTaskId(asLong(m.get("subTaskId")));
            item.setSubTaskTitle((String) m.get("subTaskTitle"));
            item.setPriority((String) m.get("priority"));
            item.setAssignedAgent((String) m.get("assignedAgent"));
            return item;
        }).toList();
        result.setPendingReviews(reviewItems);
        result.setTotalPendingReview(review.size());

        // 低活跃
        List<Map<String, Object>> lowActive = adminDashboardService.listLowActivityAgents();
        List<DashboardHighlights.LowActivityAgent> lowActiveItems = lowActive.stream().map(m -> {
            DashboardHighlights.LowActivityAgent item = new DashboardHighlights.LowActivityAgent();
            item.setAgentId(asLong(m.get("agentId")));
            item.setAgentName((String) m.get("agentName"));
            item.setRole((String) m.get("role"));
            item.setIdleMinutes(asLong(m.get("idleMinutes")));
            item.setTaskCount(asInt(m.get("taskCount")));
            return item;
        }).toList();
        result.setLowActivityAgents(lowActiveItems);
        result.setTotalLowActivity(lowActiveItems.size());

        return R.ok(result);
    }

    /**
     * 趋势数据（近 N 天）。
     */
    @GetMapping("/getTrends")
    public R<DashboardTrend> getTrends(@RequestParam(value = "days", defaultValue = "7") int days) {
        Map<String, List<?>> raw = adminDashboardService.getTrends(days);
        DashboardTrend dto = new DashboardTrend();
        dto.setDates(asStringList(raw.get("dates")));
        dto.setCreatedCounts(asLongList(raw.get("createdCounts")));
        dto.setCompletedCounts(asLongList(raw.get("completedCounts")));
        dto.setReviewedCounts(asLongList(raw.get("reviewedCounts")));
        return R.ok(dto);
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.valueOf(v.toString());
    }

    private static Integer asInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        return Integer.valueOf(v.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(List<?> raw) {
        return raw == null ? List.of() : (List<String>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> asLongList(List<?> raw) {
        return raw == null ? List.of() : (List<Long>) raw;
    }
}