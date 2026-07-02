package com.helloai.api.dto.admin;

import lombok.Data;
import java.util.List;

@Data
public class DashboardTrend {
    private List<String> dates;
    private List<Long> createdCounts;
    private List<Long> completedCounts;
    private List<Long> reviewedCounts;
}
