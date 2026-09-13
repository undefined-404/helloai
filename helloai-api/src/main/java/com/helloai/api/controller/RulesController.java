package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.common.base.R;
import com.helloai.core.system.entity.Rule;
import com.helloai.core.system.service.RuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 规则配置接口（列表 / 详情 / 合并 / 写操作）。
 *
 * <p>位于业务路径 {@code /api/rules}（非 {@code /api/admin/**}），登录即可访问；
 * 授权由动作级权限码承担（CODE_STYLE §43）：写接口 rule:add / rule:edit / rule:delete，
 * 读接口保持「登录即可读」不补码（BASE-4.4 口径）。GUEST 不绑写码，写请求一律 403。</p>
 */
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
        return R.ok(ruleService.listByType(ruleType));
    }

    /**
     * 获取单个规则
     */
    @GetMapping("/getById/{id}")
    public R<Rule> getById(@PathVariable("id") Long id) {
        Rule rule = ruleService.getById(id);
        if (rule == null) return R.fail("规则不存在");
        return R.ok(rule);
    }

    /**
     * 新建规则
     */
    @SaCheckPermission("rule:add")
    @PostMapping
    public R<Rule> create(@RequestBody Rule rule) {
        return R.ok(ruleService.createRule(rule));
    }

    /**
     * 更新规则
     */
    @SaCheckPermission("rule:edit")
    @PutMapping("/updateById/{id}")
    public R<Rule> updateById(@PathVariable("id") Long id, @RequestBody Rule rule) {
        return R.ok(ruleService.updateRule(id, rule));
    }

    /**
     * 删除规则
     */
    @SaCheckPermission("rule:delete")
    @DeleteMapping("/deleteById/{id}")
    public R<Void> deleteById(@PathVariable("id") Long id) {
        ruleService.deleteRule(id);
        return R.ok();
    }

    /**
     * 合并规则内容（原有端点保留兼容）
     */
    @GetMapping("/getMergedRules")
    public R<Map<String, Object>> getMergedRules(
            @RequestParam(value = "taskId", required = false) Long taskId,
            @RequestParam(value = "subTaskId", required = false) Long subTaskId) {
        String content = ruleService.getMergedRules(taskId, subTaskId);
        return R.ok(Map.of("content", content));
    }
}
