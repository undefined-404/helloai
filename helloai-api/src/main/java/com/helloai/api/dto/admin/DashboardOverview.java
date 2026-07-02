package com.helloai.api.dto.admin;

import lombok.Data;

@Data
public class DashboardOverview {
    private long totalTasks;
    private long inProgressTasks;
    private long completedTasks;
    private long blockedTasks;
    private long totalAgents;
    private long activeAgents;
    private long pendingReviews;
    private long todayCompleted;
    private long todayCreated;
    private long totalUsers;
}
