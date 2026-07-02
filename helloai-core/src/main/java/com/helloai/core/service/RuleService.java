package com.helloai.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.entity.Rule;
import com.helloai.core.mapper.RuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    public String getGlobalRuleContent() {
        return list(new LambdaQueryWrapper<Rule>()
                .eq(Rule::getRuleType, "global")
                .orderByAsc(Rule::getPriority))
                .stream()
                .map(Rule::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
