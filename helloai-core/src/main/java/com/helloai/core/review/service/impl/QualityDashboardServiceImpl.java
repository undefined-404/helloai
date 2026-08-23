package com.helloai.core.review.service.impl;

import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.review.dto.QualityDashboardResponse;
import com.helloai.core.review.service.QualityDashboardService;
import com.helloai.core.review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 质量看板聚合服务实现（Phase 5 质量度量看板）。
 *
 * <p>无环验证：本类只被 AdminQualityController（api 层）引用；依赖的
 * AgentQualityProfileServiceImpl（agent → system/shared）与 ReviewServiceImpl
 * （review → task/agent/system/shared）均不反向依赖本类。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityDashboardServiceImpl implements QualityDashboardService {

    /** 看板统计窗口兜底天数（days 缺省/非法时使用）。 */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final AgentQualityProfileService agentQualityProfileService;
    private final ReviewService reviewService;

    @Override
    public QualityDashboardResponse assemble(int days) {
        int window = days > 0 ? days : DEFAULT_WINDOW_DAYS;
        QualityDashboardResponse response = new QualityDashboardResponse(
                agentQualityProfileService.statsOverview(),
                reviewService.statsTrendSource(window),
                reviewService.statsDefectDistribution(window),
                reviewService.statsReworkDistribution(window),
                reviewService.statsReviewerLeniency(window));
        log.info("质量看板聚合完成: days={}, trends={}, defects={}, reworkRounds={}, reviewers={}",
                window, response.trends().size(), response.defectDistributions().size(),
                response.reworkRounds().size(), response.reviewers().size());
        return response;
    }
}
