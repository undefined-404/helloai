package com.helloai.core.system.service;

import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.Rule;
import com.helloai.core.system.service.impl.RuleServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RuleServiceImpl} 写入口单测：入参校验 / 落库动作 / 不存在语义。
 *
 * <p>用 Spy 打桩父类 IService 的 save/updateById/removeById，避免依赖真实 baseMapper。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RuleServiceImpl 规则写入口（校验 + 落库 + 不存在语义）")
class RuleServiceImplTest {

    private static final Long RULE_ID = 1001L;

    @Spy
    @InjectMocks
    private RuleServiceImpl ruleService;

    private Rule rule(String name, String ruleType, String content) {
        Rule r = new Rule();
        r.setName(name);
        r.setRuleType(ruleType);
        r.setContent(content);
        r.setPriority(0);
        return r;
    }

    @Test
    @DisplayName("createRule：校验通过后落库，忽略请求体 id 由生成策略分配")
    void createRule_persistsAndNullsId() {
        Rule input = rule("全局规范", "global", "规则内容");
        input.setId(999L);
        doReturn(true).when(ruleService).save(any(Rule.class));

        Rule saved = ruleService.createRule(input);

        assertNull(saved.getId(), "新建应忽略请求体 id，交由 ID 生成策略分配");
        verify(ruleService).save(input);
    }

    @Test
    @DisplayName("createRule：名称为空 → 400 且不落库")
    void createRule_blankName_rejected() {
        BizException ex = assertThrows(BizException.class,
                () -> ruleService.createRule(rule("  ", "global", "内容")));

        assertEquals(400, ex.getCode().intValue());
        verify(ruleService, never()).save(any(Rule.class));
    }

    @Test
    @DisplayName("createRule：规则类型非法 → 400（与 rule 表 CHECK 约束同口径）")
    void createRule_invalidRuleType_rejected() {
        BizException ex = assertThrows(BizException.class,
                () -> ruleService.createRule(rule("名称", "unknown", "内容")));

        assertEquals(400, ex.getCode().intValue());
        verify(ruleService, never()).save(any(Rule.class));
    }

    @Test
    @DisplayName("createRule：内容为空 → 400 且不落库")
    void createRule_blankContent_rejected() {
        BizException ex = assertThrows(BizException.class,
                () -> ruleService.createRule(rule("名称", "module", "")));

        assertEquals(400, ex.getCode().intValue());
        verify(ruleService, never()).save(any(Rule.class));
    }

    @Test
    @DisplayName("updateRule：规则不存在 → 404 且不写库")
    void updateRule_notFound() {
        doReturn(null).when(ruleService).getById(RULE_ID);

        BizException ex = assertThrows(BizException.class,
                () -> ruleService.updateRule(RULE_ID, rule("名称", "global", "内容")));

        assertEquals(404, ex.getCode().intValue());
        verify(ruleService, never()).updateById(any(Rule.class));
    }

    @Test
    @DisplayName("updateRule：存在则按路径 id 覆盖写库")
    void updateRule_persistsWithPathId() {
        doReturn(rule("旧名称", "global", "旧内容")).when(ruleService).getById(RULE_ID);
        doReturn(true).when(ruleService).updateById(any(Rule.class));

        Rule input = rule("新名称", "agent", "新内容");
        input.setId(888L);
        Rule updated = ruleService.updateRule(RULE_ID, input);

        assertEquals(RULE_ID, updated.getId(), "应以路径 id 为准，忽略请求体 id");
        verify(ruleService).updateById(input);
    }

    @Test
    @DisplayName("deleteRule：规则不存在 → 404 且不删库")
    void deleteRule_notFound() {
        doReturn(null).when(ruleService).getById(RULE_ID);

        BizException ex = assertThrows(BizException.class, () -> ruleService.deleteRule(RULE_ID));

        assertEquals(404, ex.getCode().intValue());
        verify(ruleService, never()).removeById(RULE_ID);
    }

    @Test
    @DisplayName("deleteRule：存在则删除")
    void deleteRule_removes() {
        doReturn(rule("名称", "global", "内容")).when(ruleService).getById(RULE_ID);
        doReturn(true).when(ruleService).removeById(RULE_ID);

        ruleService.deleteRule(RULE_ID);

        verify(ruleService).removeById(RULE_ID);
    }
}
