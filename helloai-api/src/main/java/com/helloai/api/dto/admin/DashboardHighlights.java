package com.helloai.api.dto.admin;

import lombok.Data;
import java.util.List;

@Data
public class DashboardHighlights {
    private List<BlockedTaskItem> blockedTasks;
    private List<PendingReviewItem> pendingReviews;
    private List<LowActivityAgent> lowActivityAgents;
    private int totalBlocked;
    private int totalPendingReview;
    private int totalLowActivity;

    @Data
    public static class BlockedTaskItem {
        private Long taskId;
        private Long subTaskId;
        private String taskTitle;
        private String subTaskTitle;
        private String priority;
    }

    @Data
    public static class PendingReviewItem {
        private Long subTaskId;
        private String subTaskTitle;
        private String assignedAgent;
        private String priority;
    }

    @Data
    public static class LowActivityAgent {
        private Long agentId;
        private String agentName;
        private String role;
        private long idleMinutes;
        private int taskCount;
    }
}
