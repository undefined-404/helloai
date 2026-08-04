package com.helloai.core.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.system.entity.Rule;
import com.helloai.core.system.mapper.RuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleService extends ServiceImpl<RuleMapper, Rule> {

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
    public List<Rule> listByType(String ruleType) {
        return list(new LambdaQueryWrapper<Rule>()
                .eq(ruleType != null && !ruleType.isBlank(), Rule::getRuleType, ruleType)
                .orderByAsc(Rule::getPriority));
    }

    public String getGlobalRuleContent() {
        return list(new LambdaQueryWrapper<Rule>()
                .eq(Rule::getRuleType, "global")
                .orderByAsc(Rule::getPriority))
                .stream()
                .map(Rule::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
