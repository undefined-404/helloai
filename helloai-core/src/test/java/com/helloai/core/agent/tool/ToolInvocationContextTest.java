package com.helloai.core.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolInvocationContext} 单测（REF-8.1 A1 接线）。
 *
 * <p>验证带外标识注入的两道门控（前缀 + schema）与覆盖语义：内部工具在绑定上下文后
 * 拿到执行链真实 {@code agentId / subTaskId}；外部工具与非绑定线程逐字不变。</p>
 */
@DisplayName("ToolInvocationContext 内部工具标识注入（REF-8.1 A1）")
class ToolInvocationContextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** get_sub_task_detail 风格 schema：声明 agentId + subTaskId。 */
    private static final String SCHEMA_WITH_BOTH =
            "{\"type\":\"object\",\"properties\":{\"agentId\":{\"type\":\"integer\"},"
                    + "\"subTaskId\":{\"type\":\"integer\"}},\"required\":[\"agentId\",\"subTaskId\"]}";

    /** list_skills 风格 schema：无参。 */
    private static final String SCHEMA_EMPTY = "{\"type\":\"object\",\"properties\":{}}";

    /** read_attachment 风格 schema：声明 agentId + attachmentId（无 subTaskId）。 */
    private static final String SCHEMA_AGENT_AND_ATTACHMENT =
            "{\"type\":\"object\",\"properties\":{\"agentId\":{\"type\":\"integer\"},"
                    + "\"attachmentId\":{\"type\":\"integer\"}}}";

    @AfterEach
    void tearDown() {
        ToolInvocationContext.unbind();
    }

    @Test
    @DisplayName("未绑定时不注入——参数逐字原样透传")
    void shouldNotInjectWhenUnbound() {
        String input = "{\"subTaskId\":999}";
        assertThat(ToolInvocationContext.inject("internal_get_sub_task_detail", SCHEMA_WITH_BOTH, input))
                .isEqualTo(input);
    }

    @Test
    @DisplayName("非 internal_ 前缀工具不注入——即使已绑定（外部 MCP 工具零影响）")
    void shouldNotInjectForExternalTool() {
        ToolInvocationContext.bind(11L, 22L);
        String input = "{\"query\":\"x\"}";
        assertThat(ToolInvocationContext.inject("web_search", SCHEMA_WITH_BOTH, input))
                .isEqualTo(input);
        assertThat(ToolInvocationContext.inject("get_sub_task_detail", SCHEMA_WITH_BOTH, input))
                .isEqualTo(input);
    }

    @Test
    @DisplayName("内部工具 + schema 已声明字段——注入执行链真实标识")
    void shouldInjectDeclaredIds() throws Exception {
        ToolInvocationContext.bind(11L, 22L);
        String out = ToolInvocationContext.inject("internal_get_sub_task_detail", SCHEMA_WITH_BOTH, "{}");
        assertThat(MAPPER.readTree(out).get("agentId").asLong()).isEqualTo(11L);
        assertThat(MAPPER.readTree(out).get("subTaskId").asLong()).isEqualTo(22L);
    }

    @Test
    @DisplayName("平台注入值覆盖模型所给值（执行链标识为权威）")
    void shouldOverrideModelProvidedIds() throws Exception {
        ToolInvocationContext.bind(11L, 22L);
        String out = ToolInvocationContext.inject("internal_get_sub_task_detail", SCHEMA_WITH_BOTH,
                "{\"agentId\":777,\"subTaskId\":888}");
        assertThat(MAPPER.readTree(out).get("agentId").asLong()).isEqualTo(11L);
        assertThat(MAPPER.readTree(out).get("subTaskId").asLong()).isEqualTo(22L);
    }

    @Test
    @DisplayName("schema 未声明的字段不注入——无参工具（internal_list_skills）保持空对象")
    void shouldNotInjectUndeclaredField() throws Exception {
        ToolInvocationContext.bind(11L, 22L);
        String out = ToolInvocationContext.inject("internal_list_skills", SCHEMA_EMPTY, "{}");
        assertThat(MAPPER.readTree(out).size()).isZero();
    }

    @Test
    @DisplayName("仅注入 schema 声明的子集——read_attachment 只注入 agentId，不注入 subTaskId")
    void shouldInjectOnlyDeclaredSubset() throws Exception {
        ToolInvocationContext.bind(11L, 22L);
        String out = ToolInvocationContext.inject("internal_read_attachment", SCHEMA_AGENT_AND_ATTACHMENT,
                "{\"attachmentId\":50}");
        assertThat(MAPPER.readTree(out).get("agentId").asLong()).isEqualTo(11L);
        assertThat(MAPPER.readTree(out).has("subTaskId")).isFalse();
        assertThat(MAPPER.readTree(out).get("attachmentId").asLong()).isEqualTo(50L);
    }

    @Test
    @DisplayName("模型参数为空 / 非合法 JSON——退化为仅平台标识对象")
    void shouldHandleBlankOrInvalidArguments() throws Exception {
        ToolInvocationContext.bind(11L, 22L);
        assertThat(MAPPER.readTree(ToolInvocationContext.inject(
                "internal_get_sub_task_detail", SCHEMA_WITH_BOTH, "")).get("agentId").asLong()).isEqualTo(11L);
        assertThat(MAPPER.readTree(ToolInvocationContext.inject(
                "internal_get_sub_task_detail", SCHEMA_WITH_BOTH, "not-json")).get("subTaskId").asLong()).isEqualTo(22L);
    }

    @Test
    @DisplayName("schema 不可得（null）——放行注入（已受前缀门控约束）")
    void shouldInjectWhenSchemaUnavailable() throws Exception {
        ToolInvocationContext.bind(11L, 22L);
        String out = ToolInvocationContext.inject("internal_get_agent_status", null, "{}");
        assertThat(MAPPER.readTree(out).get("agentId").asLong()).isEqualTo(11L);
    }

    @Test
    @DisplayName("解绑后不再注入——防线程复用串味")
    void shouldNotInjectAfterUnbind() {
        ToolInvocationContext.bind(11L, 22L);
        ToolInvocationContext.unbind();
        String input = "{}";
        assertThat(ToolInvocationContext.inject("internal_get_sub_task_detail", SCHEMA_WITH_BOTH, input))
                .isEqualTo(input);
    }
}
