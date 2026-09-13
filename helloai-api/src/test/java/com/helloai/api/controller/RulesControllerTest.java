package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.core.system.entity.Rule;
import com.helloai.core.system.service.RuleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RulesController} 单测：读写端点转发与 R 封装。
 *
 * <p>写端点的动作级权限码（rule:add / rule:edit / rule:delete）由 @SaCheckPermission
 * 注解声明，运行期由 Sa-Token 拦截；本单测只覆盖控制器转发与返回封装。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RulesController 规则配置端点")
class RulesControllerTest {

    private static final Long RULE_ID = 1001L;

    @Mock
    private RuleService ruleService;

    @InjectMocks
    private RulesController controller;

    private Rule rule(String name, String ruleType) {
        Rule r = new Rule();
        r.setId(RULE_ID);
        r.setName(name);
        r.setRuleType(ruleType);
        r.setPriority(10);
        r.setContent("规则内容");
        return r;
    }

    @Test
    @DisplayName("list：按类型透传并返回数组")
    void list_forwardsRuleType() {
        when(ruleService.listByType("global")).thenReturn(List.of(rule("全局规范", "global")));

        R<List<Rule>> res = controller.list("global");

        assertThat(res.getCode()).isEqualTo(200);
        assertThat(res.getData()).hasSize(1);
        verify(ruleService).listByType("global");
        verifyNoMoreInteractions(ruleService);
    }

    @Test
    @DisplayName("create：转发 service 并返回新建实体")
    void create_returnsCreated() {
        Rule input = rule("新规则", "module");
        when(ruleService.createRule(any(Rule.class))).thenReturn(input);

        R<Rule> res = controller.create(input);

        assertThat(res.getCode()).isEqualTo(200);
        assertThat(res.getData().getName()).isEqualTo("新规则");
        verify(ruleService).createRule(input);
        verifyNoMoreInteractions(ruleService);
    }

    @Test
    @DisplayName("updateById：路径 id 作为第一参数透传（不信任请求体 id）")
    void updateById_forwardsPathId() {
        Rule input = rule("改名", "agent");
        when(ruleService.updateRule(any(), any(Rule.class))).thenReturn(input);

        R<Rule> res = controller.updateById(RULE_ID, input);

        assertThat(res.getCode()).isEqualTo(200);
        verify(ruleService).updateRule(RULE_ID, input);
        verifyNoMoreInteractions(ruleService);
    }

    @Test
    @DisplayName("deleteById：转发 service 并返回空 data")
    void deleteById_forwards() {
        R<Void> res = controller.deleteById(RULE_ID);

        assertThat(res.getCode()).isEqualTo(200);
        verify(ruleService).deleteRule(RULE_ID);
        verifyNoMoreInteractions(ruleService);
    }
}
