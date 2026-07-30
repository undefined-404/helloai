package com.helloai.api.controller;

import com.helloai.api.dto.requirement.ClarifyMessageRequest;
import com.helloai.common.base.R;
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

    /** 新建澄清会话（首条用户消息触发一轮 LLM）。 */
    @PostMapping
    public R<ClarifyConversationDetail> create(@Valid @RequestBody ClarifyMessageRequest req) {
        return R.ok(requirementClarifyService.create(req.getMessage()));
    }

    /** 追加一条用户消息并走一轮 LLM 澄清。 */
    @PostMapping("/{id}/messages")
    public R<ClarifyConversationDetail> sendMessage(@PathVariable Long id,
                                                    @Valid @RequestBody ClarifyMessageRequest req) {
        return R.ok(requirementClarifyService.sendMessage(id, req.getMessage()));
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

    /** 放弃会话。 */
    @PostMapping("/{id}/abandon")
    public R<Void> abandon(@PathVariable Long id) {
        requirementClarifyService.abandon(id);
        return R.ok(null);
    }
}
