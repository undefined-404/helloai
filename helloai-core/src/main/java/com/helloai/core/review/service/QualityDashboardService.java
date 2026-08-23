package com.helloai.core.review.service;

import com.helloai.core.review.dto.QualityDashboardResponse;

/**
 * 质量看板聚合服务（Phase 5 质量度量看板）。
 *
 * <p>聚合逻辑收口于 Service（§6.7 聚合看板语义延伸），Controller 零编排只透传；
 * 跨域只依赖两域 Service 接口（agent 域画像统计 + 本域 review_record 窗口统计），
 * 不直捅任何 Mapper，遵守 §3.x 跨域红线。</p>
 */
public interface QualityDashboardService {

    /**
     * 组装质量看板全量数据（一次请求渲染整屏，排行单独走 /agents 端点）。
     *
     * @param days 统计窗口（天）；&lt;=0 按 30 兜底
     * @return 聚合响应（窗口内无数据时各列表为空，overview 恒非 null）
     */
    QualityDashboardResponse assemble(int days);
}
