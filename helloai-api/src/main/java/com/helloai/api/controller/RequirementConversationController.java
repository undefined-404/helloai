package com.helloai.api.controller;

import com.helloai.api.dto.requirement.ClarifyMessageRequest;
import com.helloai.common.base.R;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyConversationDetail;
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

    /** 新建澄清会话（首条用户消息触发一轮 LLM；可选手动指定 Planner；可带联网搜索开关；可带初始对话模式）。 */
    @PostMapping
    public R<ClarifyConversationDetail> create(@Valid @RequestBody ClarifyMessageRequest req) {
        // 联网搜索开关透传：NULL 走默认开启语义（与老数据兼容）；
        // initialMode 透传：缺省 CHAT 自由对话，'CLARIFY' 快捷直达方案澄清
        return R.ok(requirementClarifyService.create(
                req.getMessage(), req.getPlannerAgentId(), req.getWebSearchEnabled(), req.getInitialMode()));
    }

    /** Planner 下拉选数据源（平台内 PLANNER 可选 + 在班外部 Agent 置灰）。 */
    @GetMapping("/listPlannerOptions")
    public R<List<PlannerAgentPicker.PlannerOption>> listPlannerOptions() {
        return R.ok(requirementClarifyService.listPlannerOptions());
    }

    /** 追加一条用户消息并走一轮 LLM 澄清（可附结构化选项回答快照）。 */
    @PostMapping("/sendMessageById/{id}")
    public R<ClarifyConversationDetail> sendMessageById(@PathVariable("id") Long id,
                                                    @Valid @RequestBody ClarifyMessageRequest req) {
        return R.ok(requirementClarifyService.sendMessage(id, req.getMessage(), req.getSelectedOptions()));
    }

    /** 重试上一轮 LLM（仅当最后一条是用户消息，即上轮 LLM 失败时可用）。 */
    @PostMapping("/retryById/{id}")
    public R<ClarifyConversationDetail> retryById(@PathVariable("id") Long id) {
        return R.ok(requirementClarifyService.retryRound(id));
    }

    /** 切换到方案澄清模式：置位落库 + 一轮 LLM 基于全量历史产终稿草案/结构化追问；
     *  支持可选 body.message（斜杠命令 /planner 附加文本，先落库进上下文再切）。 */
    @PostMapping("/toClarifyById/{id}")
    public R<ClarifyConversationDetail> toClarify(@PathVariable("id") Long id,
                                                  @RequestBody(required = false) ClarifyMessageRequest req) {
        String extraMessage = req != null ? req.getMessage() : null;
        return R.ok(requirementClarifyService.switchToClarify(id, extraMessage));
    }

    /** 切回自由对话模式：仅置位，不调用 LLM。 */
    @PostMapping("/toChatById/{id}")
    public R<ClarifyConversationDetail> toChat(@PathVariable("id") Long id) {
        return R.ok(requirementClarifyService.switchToChat(id));
    }

    /** 会话列表（按创建时间倒序，LIMIT 50）。 */
    @GetMapping
    public R<List<RequirementConversation>> list() {
        return R.ok(requirementClarifyService.listConversations());
    }

    /** 会话详情（含全部消息按 seq 升序）。 */
    @GetMapping("/getById/{id}")
    public R<ClarifyConversationDetail> getById(@PathVariable("id") Long id) {
        return R.ok(requirementClarifyService.detail(id));
    }

    /** 终稿确认：创建任务并回填会话。 */
    @PostMapping("/finalizeById/{id}")
    public R<Task> finalizeById(@PathVariable("id") Long id) {
        return R.ok(requirementClarifyService.finalize(id));
    }

    /** 重新生成：FINALIZED 会话原任务已删除时，复用终稿重建任务并回填。 */
    @PostMapping("/regenerateById/{id}")
    public R<Task> regenerateById(@PathVariable("id") Long id) {
        return R.ok(requirementClarifyService.regenerate(id));
    }

    /** 放弃会话。 */
    @PostMapping("/abandonById/{id}")
    public R<Void> abandonById(@PathVariable("id") Long id) {
        requirementClarifyService.abandon(id);
        return R.ok(null);
    }
}
