package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.quality.dto.AgentQualityRank;
import com.helloai.core.agent.quality.dto.QualityOverview;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.review.dto.QualityDashboardResponse;
import com.helloai.core.review.service.QualityDashboardService;
import com.helloai.core.system.service.SysConfigService;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.TaskRunningSpecService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端质量画像端点（反馈回路第 1 层，Phase 1 交付）。
 *
 * <p>Controller 零编排：薄透传端点均直接转发 service 方法，不承载任何
 * 条件/循环/聚合逻辑。用途：
 * <ul>
 *   <li>{@code POST /rebuildById/{agentId}}：从 review_record 全量重算指定 Agent
 *       画像并覆盖落库，供 verify-quality-profile.ps1 S5 对账
 *       （验证 rebuild 与增量维护口径一致）；</li>
 *   <li>{@code POST /dispatchById/{subTaskId}}：对 PENDING 子任务触发自动选人分发，
 *       供 verify-quality-profile.ps1 S3 断言 qualityRank 回灌调度选人
 *       （auto-assign-on-create 默认关闭，实测需显式触发入口）；</li>
 *   <li>{@code GET /findSpecSectionByTaskId/{taskId}}：返回 TaskRunningSpec 执行上下文
 *       Prompt 段（含契约先行拆解「## 任务契约」节），供
 *       verify-contract-first.ps1 S3/S4 断言契约节渲染（Phase 2）；</li>
 *   <li>{@code GET /overview}、{@code GET /agents?limit=}、{@code GET /dashboard?days=}：
 *       质量度量看板三查询端点（Phase 5），薄透传两域统计 Service，供
 *       verify-quality-dashboard.ps1 字段断言（聚合在 review 域 QualityDashboardService）。</li>
 * </ul>
 *
 * <p>本端点仅面向管理侧实测验证。</p>
 *
 * <p>配置门控：全部端点受 sys_config 键 {@code admin.quality.enabled}
 * （值 "true" 开放）控制，生产默认关闭；实测脚本
 * （verify-quality-profile.ps1 / verify-contract-first.ps1）登录后会先
 * {@code PUT /api/admin/config/updateByKey/admin.quality.enabled} 开启。
 * 关闭时返回业务码 403，避免内部 Prompt 段/重算/派发入口在生产无门槛暴露。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/quality")
@RequiredArgsConstructor
public class AdminQualityController {

    /** 门控配置键：sys_config 中置 "true" 才开放本控制器全部端点。 */
    private static final String ENABLED_CONFIG_KEY = "admin.quality.enabled";

    private final AgentQualityProfileService agentQualityProfileService;
    private final SubTaskDispatchService subTaskDispatchService;
    private final TaskRunningSpecService taskRunningSpecService;
    private final SysConfigService sysConfigService;
    /** Phase 5：看板聚合（review 域，Controller 零编排只透传）。 */
    private final QualityDashboardService qualityDashboardService;

    /**
     * 重算指定 Agent 的质量画像（rebuild 兜底）。
     *
     * @param agentId Agent ID
     */
    @PostMapping("/rebuildById/{agentId}")
    public R<Void> rebuild(@PathVariable("agentId") Long agentId) {
        if (!isEnabled()) {
            return gateDenied();
        }
        agentQualityProfileService.rebuild(agentId);
        log.info("管理员触发画像重算: agentId={}", agentId);
        return R.ok();
    }

    /**
     * 对 PENDING 子任务触发自动选人分发（EXECUTOR 角色）。
     *
     * @param subTaskId 子任务 ID
     */
    @PostMapping("/dispatchById/{subTaskId}")
    public R<Long> dispatch(@PathVariable("subTaskId") Long subTaskId) {
        if (!isEnabled()) {
            return gateDenied();
        }
        Long preferredAgentId = subTaskDispatchService.dispatchPendingSubTaskAuto(subTaskId, AgentRole.EXECUTOR);
        log.info("管理员触发子任务自动分发: subTaskId={}, preferredAgentId={}", subTaskId, preferredAgentId);
        return R.ok(preferredAgentId);
    }

    /**
     * 返回 Task Running Spec 执行上下文 Prompt 段（含契约节，契约先行拆解）。
     *
     * @param taskId 主任务 ID
     */
    @GetMapping("/findSpecSectionByTaskId/{taskId}")
    public R<String> specSection(@PathVariable("taskId") Long taskId) {
        if (!isEnabled()) {
            return gateDenied();
        }
        return R.ok(taskRunningSpecService.buildExecutorPromptSection(taskId));
    }

    /**
     * 全局质量概览（Phase 5 看板 overview 卡片）。
     *
     * @return 画像表存量聚合；空表返回全 0 概览
     */
    @GetMapping("/overview")
    public R<QualityOverview> getOverview() {
        if (!isEnabled()) {
            return gateDenied();
        }
        return R.ok(agentQualityProfileService.statsOverview());
    }

    /**
     * Agent 质量排行（Phase 5 看板 agents 排行）。
     *
     * @param limit 返回条数上限；缺省/&lt;=0 返回全部
     * @return 一次通过率降序排行（含质量分与补名）
     */
    @GetMapping("/agents")
    public R<List<AgentQualityRank>> agentRankings(@RequestParam(value = "limit", required = false) Integer limit) {
        if (!isEnabled()) {
            return gateDenied();
        }
        return R.ok(agentQualityProfileService.statsAgentRankings(limit));
    }

    /**
     * 质量看板全量数据（Phase 5：趋势/驳回原因/返工轮次/放水率 + 概览）。
     *
     * @param days 统计窗口（天）；缺省/&lt;=0 按 30 兜底
     * @return 聚合响应；排行单独走 {@code /agents} 端点
     */
    @GetMapping("/dashboard")
    public R<QualityDashboardResponse> dashboard(@RequestParam(value = "days", required = false) Integer days) {
        if (!isEnabled()) {
            return gateDenied();
        }
        return R.ok(qualityDashboardService.assemble(days != null ? days : 0));
    }

    private boolean isEnabled() {
        return "true".equalsIgnoreCase(sysConfigService.getValue(ENABLED_CONFIG_KEY));
    }

    private <T> R<T> gateDenied() {
        return R.fail(403, "管理侧质量实测端点未开启（sys config " + ENABLED_CONFIG_KEY + "=true）");
    }
}
