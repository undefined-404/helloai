package com.helloai.api.controller;

import com.helloai.api.dto.requirement.ClarifyMessageRequest;
import com.helloai.common.base.R;
import com.helloai.core.planner.PlannerAgentPicker;
import com.helloai.core.planner.RequirementClarifyService;
import com.helloai.core.planner.RequirementClarifyService.ClarifyConversationDetail;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.task.entity.Task;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 对话式需求澄清（薄转发，编排收口在 {@link RequirementClarifyService}）。
 */
@Slf4j
@RestController
@RequestMapping("/api/requirement-conversations")
@RequiredArgsConstructor
public class RequirementConversationController {

    private final RequirementClarifyService requirementClarifyService;

    /** 新建澄清会话（首条用户消息触发一轮 LLM；可选手动指定 Planner）。 */
    @PostMapping
    public R<ClarifyConversationDetail> create(@Valid @RequestBody ClarifyMessageRequest req) {
        return R.ok(requirementClarifyService.create(req.getMessage(), req.getPlannerAgentId()));
    }

    /** Planner 下拉选数据源（平台内 PLANNER 可选 + 在班外部 Agent 置灰）。 */
    @GetMapping("/planner-options")
    public R<List<PlannerAgentPicker.PlannerOption>> plannerOptions() {
        return R.ok(requirementClarifyService.listPlannerOptions());
    }

    /** 追加一条用户消息并走一轮 LLM 澄清。 */
    @PostMapping("/{id}/messages")
    public R<ClarifyConversationDetail> sendMessage(@PathVariable Long id,
                                                    @Valid @RequestBody ClarifyMessageRequest req) {
        return R.ok(requirementClarifyService.sendMessage(id, req.getMessage()));
    }

    /** 重试上一轮 LLM（仅当最后一条是用户消息，即上轮 LLM 失败时可用）。 */
    @PostMapping("/{id}/retry")
    public R<ClarifyConversationDetail> retry(@PathVariable Long id) {
        return R.ok(requirementClarifyService.retryRound(id));
    }

    /** 会话列表（按创建时间倒序，LIMIT 50）。 */
    @GetMapping
    public R<List<RequirementConversation>> list() {
        return R.ok(requirementClarifyService.listConversations());
    }

    /** 会话详情（含全部消息按 seq 升序）。 */
    @GetMapping("/{id}")
    public R<ClarifyConversationDetail> detail(@PathVariable Long id) {
        return R.ok(requirementClarifyService.detail(id));
    }

    /** 终稿确认：创建任务并回填会话。 */
    @PostMapping("/{id}/finalize")
    public R<Task> finalizeConversation(@PathVariable Long id) {
        return R.ok(requirementClarifyService.finalize(id));
    }

    /** 重新生成：FINALIZED 会话原任务已删除时，复用终稿重建任务并回填。 */
    @PostMapping("/{id}/regenerate")
    public R<Task> regenerate(@PathVariable Long id) {
        return R.ok(requirementClarifyService.regenerate(id));
    }

    /** 放弃会话。 */
    @PostMapping("/{id}/abandon")
    public R<Void> abandon(@PathVariable Long id) {
        requirementClarifyService.abandon(id);
        return R.ok(null);
    }
}
