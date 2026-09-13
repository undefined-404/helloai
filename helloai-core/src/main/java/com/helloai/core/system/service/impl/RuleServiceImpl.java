package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.Rule;
import com.helloai.core.system.mapper.RuleMapper;
import com.helloai.core.system.service.RuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleServiceImpl extends ServiceImpl<RuleMapper, Rule> implements RuleService {

    /** 规则类型受控枚举（与 rule 表 chk_rule_type 约束一致）。 */
    private static final Set<String> ALLOWED_RULE_TYPES = Set.of("global", "module", "agent");

    @Override
    public String getMergedRules(Long taskId, Long subTaskId) {
        StringBuilder merged = new StringBuilder();

        var globalRules = list(new LambdaQueryWrapper<Rule>()
                .eq(Rule::getRuleType, "global")
                .orderByAsc(Rule::getPriority));
        for (Rule r : globalRules) {
            if (merged.length() > 0) merged.append("\n\n---\n\n");
            merged.append(r.getContent());
        }

        if (taskId != null) {
            var moduleRules = list(new LambdaQueryWrapper<Rule>()
                    .eq(Rule::getRuleType, "module")
                    .orderByAsc(Rule::getPriority));
            for (Rule r : moduleRules) {
                if (merged.length() > 0) merged.append("\n\n---\n\n");
                merged.append(r.getContent());
            }
        }

        if (subTaskId != null) {
            var agentRules = list(new LambdaQueryWrapper<Rule>()
                    .eq(Rule::getRuleType, "agent")
                    .orderByAsc(Rule::getPriority));
            for (Rule r : agentRules) {
                if (merged.length() > 0) merged.append("\n\n---\n\n");
                merged.append(r.getContent());
            }
        }

        return merged.toString();
    }

    /**
     * 按规则类型查询规则列表（类型可选，按优先级升序）。
     *
     * <p>按 §6.3 分层红线从 RulesController 收口。</p>
     */
    @Override
    public List<Rule> listByType(String ruleType) {
        return list(new LambdaQueryWrapper<Rule>()
                .eq(ruleType != null && !ruleType.isBlank(), Rule::getRuleType, ruleType)
                .orderByAsc(Rule::getPriority));
    }

    @Override
    public String getGlobalRuleContent() {
        return list(new LambdaQueryWrapper<Rule>()
                .eq(Rule::getRuleType, "global")
                .orderByAsc(Rule::getPriority))
                .stream()
                .map(Rule::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Rule createRule(Rule rule) {
        validate(rule);
        // 新建一律由 ID 生成策略分配，忽略请求体传入的 id，避免覆盖存量行
        rule.setId(null);
        save(rule);
        log.info("规则创建: id={}, name={}, ruleType={}", rule.getId(), rule.getName(), rule.getRuleType());
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Rule updateRule(Long id, Rule rule) {
        if (getById(id) == null) {
            throw new BizException(404, "规则不存在: " + id);
        }
        validate(rule);
        rule.setId(id);
        updateById(rule);
        log.info("规则更新: id={}, name={}, ruleType={}", id, rule.getName(), rule.getRuleType());
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deleteRule(Long id) {
        if (getById(id) == null) {
            throw new BizException(404, "规则不存在: " + id);
        }
        removeById(id);
        log.info("规则删除: id={}", id);
    }

    /**
     * 写入口校验：名称/类型/内容非空，类型受控。
     *
     * <p>前置校验可让非法入参返回 400，而非落到 DB 约束失败被兜底成 500
     * （rule 表 name/rule_type/content 均为 NOT NULL，且 rule_type 有 CHECK 约束）。</p>
     */
    private void validate(Rule rule) {
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new BizException(400, "规则名称不能为空");
        }
        if (rule.getRuleType() == null || !ALLOWED_RULE_TYPES.contains(rule.getRuleType())) {
            throw new BizException(400, "规则类型非法，仅支持 global / module / agent");
        }
        if (rule.getContent() == null || rule.getContent().isBlank()) {
            throw new BizException(400, "规则内容不能为空");
        }
    }
}
