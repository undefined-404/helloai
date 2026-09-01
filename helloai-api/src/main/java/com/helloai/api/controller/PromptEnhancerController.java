package com.helloai.api.controller;

import com.helloai.api.dto.prompt.PromptEnhanceRequest;
import com.helloai.common.base.R;
import com.helloai.core.planner.prompt.PromptEnhanceResult;
import com.helloai.core.planner.prompt.PromptEnhancerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Planner 输入优化（PromptEnhancer）：薄转发，编排在 {@link PromptEnhancerService}。
 *
 * <p>独立辅助接口：不经过会话/任务链路，不触发任何执行编排（CODE_STYLE V2 §32）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/planner/prompt")
@RequiredArgsConstructor
public class PromptEnhancerController {

    private final PromptEnhancerService promptEnhancerService;

    /** 优化用户当前输入，返回原文 + 优化后版本（前端预览后由用户自行回填）。 */
    @PostMapping("/enhance")
    public R<PromptEnhanceResult> enhance(@Valid @RequestBody PromptEnhanceRequest req) {
        return R.ok(promptEnhancerService.enhance(req.getPrompt()));
    }
}
