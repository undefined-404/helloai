package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.TaskRunningSpecService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端质量画像端点（反馈回路第 1 层，Phase 1 交付）。
 *
 * <p>Controller 零编排：薄透传端点均直接转发 service 方法，不承载任何
 * 条件/循环/聚合逻辑。用途：
 * <ul>
 *   <li>{@code POST /rebuild/{agentId}}：从 review_record 全量重算指定 Agent
 *       画像并覆盖落库，供 verify-quality-profile.ps1 S5 对账
 *       （验证 rebuild 与增量维护口径一致）；</li>
 *   <li>{@code POST /dispatch/{subTaskId}}：对 PENDING 子任务触发自动选人分发，
 *       供 verify-quality-profile.ps1 S3 断言 qualityRank 回灌调度选人
 *       （auto-assign-on-create 默认关闭，实测需显式触发入口）；</li>
 *   <li>{@code GET /spec-section/{taskId}}：返回 TaskRunningSpec 执行上下文
 *       Prompt 段（含契约先行拆解「## 任务契约」节），供
 *       verify-contract-first.ps1 S3/S4 断言契约节渲染（Phase 2）。</li>
 * </ul>
 *
 * <p>本端点仅面向管理侧实测验证；Phase 5 看板查询端点落地时再行扩展。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/quality")
@RequiredArgsConstructor
public class AdminQualityController {

    private final AgentQualityProfileService agentQualityProfileService;
    private final SubTaskDispatchService subTaskDispatchService;
    private final TaskRunningSpecService taskRunningSpecService;

    /**
     * 重算指定 Agent 的质量画像（rebuild 兜底）。
     *
     * @param agentId Agent ID
     */
    @PostMapping("/rebuild/{agentId}")
    public R<Void> rebuild(@PathVariable("agentId") Long agentId) {
        agentQualityProfileService.rebuild(agentId);
        log.info("管理员触发画像重算: agentId={}", agentId);
        return R.ok();
    }

    /**
     * 对 PENDING 子任务触发自动选人分发（EXECUTOR 角色）。
     *
     * @param subTaskId 子任务 ID
     */
    @PostMapping("/dispatch/{subTaskId}")
    public R<Long> dispatch(@PathVariable("subTaskId") Long subTaskId) {
        Long preferredAgentId = subTaskDispatchService.dispatchPendingSubTaskAuto(subTaskId, AgentRole.EXECUTOR);
        log.info("管理员触发子任务自动分发: subTaskId={}, preferredAgentId={}", subTaskId, preferredAgentId);
        return R.ok(preferredAgentId);
    }

    /**
     * 返回 Task Running Spec 执行上下文 Prompt 段（含契约节，契约先行拆解）。
     *
     * @param taskId 主任务 ID
     */
    @GetMapping("/spec-section/{taskId}")
    public R<String> specSection(@PathVariable("taskId") Long taskId) {
        return R.ok(taskRunningSpecService.buildExecutorPromptSection(taskId));
    }
}
