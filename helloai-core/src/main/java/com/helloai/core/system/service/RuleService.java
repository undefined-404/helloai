package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.Rule;

import java.util.List;

/**
 * 规则服务接口。
 */
public interface RuleService extends IService<Rule> {

    String getMergedRules(Long taskId, Long subTaskId);

    /**
     * 按规则类型查询规则列表（类型可选，按优先级升序）。
     *
     * <p>按 §6.3 分层红线从 RulesController 收口。</p>
     */
    List<Rule> listByType(String ruleType);

    String getGlobalRuleContent();
}
