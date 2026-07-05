package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.base.R;
import com.helloai.core.entity.Rule;
import com.helloai.core.service.RuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/rules")
@RequiredArgsConstructor
public class RulesController {

    private final RuleService ruleService;

    /**
     * 获取规则列表（前端需要 Rule[] 数组格式）
     */
    @GetMapping
    public R<List<Rule>> list(@RequestParam(value = "ruleType", required = false) String ruleType) {
        var wrapper = new LambdaQueryWrapper<Rule>()
                .eq(ruleType != null && !ruleType.isBlank(), Rule::getRuleType, ruleType)
                .orderByAsc(Rule::getPriority);
        return R.ok(ruleService.list(wrapper));
    }

    /**
     * 获取单个规则
     */
    @GetMapping("/{id}")
    public R<Rule> getById(@PathVariable("id") Long id) {
        Rule rule = ruleService.getById(id);
        if (rule == null) return R.fail("规则不存在");
        return R.ok(rule);
    }

    /**
     * 合并规则内容（原有端点保留兼容）
     */
    @GetMapping("/merged")
    public R<Map<String, Object>> getMergedRules(
            @RequestParam(value = "taskId", required = false) Long taskId,
            @RequestParam(value = "subTaskId", required = false) Long subTaskId) {
        String content = ruleService.getMergedRules(taskId, subTaskId);
        return R.ok(Map.of("content", content));
    }
}
