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

    // ===== SL-8（2026-11-13）：taskId 透传（任务级沙箱句柄用，消除热路径查库） =====

    @Test
    @DisplayName("SL-8 绑定 taskId ⇒ 句柄侧可取到；未绑定 / 旧重载 ⇒ null")
    void taskIdShouldBeOptionalAndReadable() {
        ToolInvocationContext.bind(11L, 77L, 22L, 0);
        assertThat(ToolInvocationContext.currentTaskId()).isEqualTo(77L);
        assertThat(ToolInvocationContext.current().taskId()).isEqualTo(77L);

        ToolInvocationContext.bind(11L, 22L);   // 旧重载：不绑定 taskId
        assertThat(ToolInvocationContext.currentTaskId()).isNull();

        ToolInvocationContext.unbind();
        assertThat(ToolInvocationContext.currentTaskId()).isNull();
    }

    @Test
    @DisplayName("SL-8 预留支：schema 声明 taskId ⇒ 注入平台值；未声明则不注入（合成 schema，无真实工具声明）")
    void taskIdShouldFollowSchemaGating() throws Exception {
        // 注意：下面的 schema 是**合成**的——当前没有任何内部工具声明 `@ToolParam taskId`
        // （2026-11-14 审计确认），本用例只验证「预留支若被启用，仍受 schema 门控约束」，
        // 不构成 taskId 存在参数面消费方的证据。taskId 的真实去向是 currentTaskId() 直读。
        String schemaWithTaskId =
                "{\"type\":\"object\",\"properties\":{\"agentId\":{\"type\":\"integer\"},"
                        + "\"taskId\":{\"type\":\"integer\"},\"subTaskId\":{\"type\":\"integer\"}}}";
        ToolInvocationContext.bind(11L, 77L, 22L, 0);

        String out = ToolInvocationContext.inject("internal_run_in_sandbox", schemaWithTaskId, "{}");
        assertThat(MAPPER.readTree(out).get("taskId").asLong()).isEqualTo(77L);

        // 真实工具 schema（get_sub_task_detail 风格，只声明 agentId + subTaskId）⇒ 不注入 taskId
        String withoutDeclaration = ToolInvocationContext.inject(
                "internal_get_sub_task_detail", SCHEMA_WITH_BOTH, "{}");
        assertThat(MAPPER.readTree(withoutDeclaration).has("taskId")).isFalse();
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

    @Test
    @DisplayName("REF-8.1.1 调用登记：绑定后 markCalled 生效、解绑即清零（知情类闸门判据）")
    void shouldTrackCalledToolsPerChain() {
        assertThat(ToolInvocationContext.hasCalled("internal_get_sub_task_detail")).isFalse();

        ToolInvocationContext.bind(11L, 22L);
        ToolInvocationContext.markCalled("internal_get_sub_task_detail");
        assertThat(ToolInvocationContext.hasCalled("internal_get_sub_task_detail")).isTrue();
        assertThat(ToolInvocationContext.hasCalled("internal_read_attachment")).isFalse();

        ToolInvocationContext.unbind();
        assertThat(ToolInvocationContext.hasCalled("internal_get_sub_task_detail")).isFalse();
    }

    @Test
    @DisplayName("REF-8.1.1 未绑定时 markCalled 为空操作（外部/无链调用不产生误判）")
    void shouldIgnoreMarkCalledWhenUnbound() {
        ToolInvocationContext.markCalled("internal_get_sub_task_detail");
        assertThat(ToolInvocationContext.hasCalled("internal_get_sub_task_detail")).isFalse();
    }

    @Test
    @DisplayName("SL-4 轮次：带轮次绑定可读；缺省绑定为 0；解绑后归零（防线程复用串味）")
    void shouldCarryRoundWhenBound() {
        assertThat(ToolInvocationContext.currentRound()).isZero();

        ToolInvocationContext.bind(11L, 22L, 3);
        assertThat(ToolInvocationContext.currentRound()).isEqualTo(3);

        ToolInvocationContext.bind(11L, 22L);
        assertThat(ToolInvocationContext.currentRound()).isZero();

        ToolInvocationContext.bind(11L, 22L, 2);
        ToolInvocationContext.unbind();
        assertThat(ToolInvocationContext.currentRound()).isZero();
    }

    // ===== SL-10（2026-11-13）：reviewer 标志（审核隔离身份，只读工作区 + REVIEWER origin） =====

    @Test
    @DisplayName("SL-10 reviewer 标志：5 参绑定 true 可读；其余重载缺省 false；解绑归位")
    void reviewerFlagShouldFollowBindOverloads() {
        assertThat(ToolInvocationContext.currentReviewer()).isFalse();

        ToolInvocationContext.bind(11L, 77L, 22L, 0, true);
        assertThat(ToolInvocationContext.currentReviewer()).isTrue();
        assertThat(ToolInvocationContext.current().reviewer()).isTrue();

        ToolInvocationContext.bind(11L, 22L);   // 旧重载：reviewer 缺省 false
        assertThat(ToolInvocationContext.currentReviewer()).isFalse();

        ToolInvocationContext.unbind();
        assertThat(ToolInvocationContext.currentReviewer()).isFalse();
    }

    @Test
    @DisplayName("SL-10 跨线程快照保留 reviewer 标志——不可跨线程丢失（Scope record 组件）")
    void reviewerFlagShouldSurviveCrossThreadScope() {
        ToolInvocationContext.bind(11L, 77L, 22L, 0, true);
        ToolInvocationContext.Scope scope = ToolInvocationContext.capture();
        assertThat(scope).isNotNull();
        assertThat(scope.ids().reviewer()).isTrue();

        ToolInvocationContext.unbind();
        assertThat(ToolInvocationContext.currentReviewer()).isFalse();

        Boolean carried = ToolInvocationContext.callWith(scope, () ->
                ToolInvocationContext.currentReviewer() && ToolInvocationContext.current().reviewer());
        assertThat(carried).isTrue();
        // callWith 退出后宿主线程还原为「未绑定」
        assertThat(ToolInvocationContext.currentReviewer()).isFalse();
    }

    // ===== MA-5（2026-11-19）：turn 级工具描述缓存（internal_load_tool 用） =====

    @Test
    @DisplayName("MA-5 工具描述缓存：绑定后可写可读；解绑 / 二次绑定即清空（不跨 turn 泄漏）")
    void toolCacheShouldFollowChainLifecycle() {
        assertThat(ToolInvocationContext.cachedTool("internal_a")).isNull();

        ToolInvocationContext.bind(11L, 22L);
        ToolInvocationContext.cacheTool("internal_a", "desc-a");
        assertThat(ToolInvocationContext.cachedTool("internal_a")).isEqualTo("desc-a");
        assertThat(ToolInvocationContext.cachedTool("internal_b")).isNull();

        ToolInvocationContext.unbind();
        assertThat(ToolInvocationContext.cachedTool("internal_a")).isNull();

        // 二次绑定 = 新执行链：缓存一并清零（即便同线程复用也不串味）
        ToolInvocationContext.bind(11L, 22L);
        assertThat(ToolInvocationContext.cachedTool("internal_a")).isNull();
    }

    @Test
    @DisplayName("MA-5 工具描述缓存：null 描述不缓存（未知工具不占位）；未绑定时缓存为空操作")
    void toolCacheShouldIgnoreNullAndUnbound() {
        ToolInvocationContext.cacheTool("internal_a", "desc-a");
        assertThat(ToolInvocationContext.cachedTool("internal_a")).isNull();

        ToolInvocationContext.bind(11L, 22L);
        ToolInvocationContext.cacheTool("internal_a", null);
        assertThat(ToolInvocationContext.cachedTool("internal_a")).isNull();
        ToolInvocationContext.cacheTool(null, "desc-a");
        assertThat(ToolInvocationContext.cachedTool(null)).isNull();
    }

    @Test
    @DisplayName("MA-5 工具描述缓存：cross-thread 快照携带缓存 + 子线程写入对共享实例可见 + 退出严格还原")
    void toolCacheShouldSurviveCrossThreadScope() {
        ToolInvocationContext.bind(11L, 22L);
        ToolInvocationContext.cacheTool("internal_a", "desc-a");
        ToolInvocationContext.Scope scope = ToolInvocationContext.capture();
        ToolInvocationContext.unbind();

        // 宿主线程（未绑定）读不到
        assertThat(ToolInvocationContext.cachedTool("internal_a")).isNull();

        callWithInSubThread(scope);
        // callWith 退出后宿主线程还原为「未绑定」，不因子线程写入而污染
        assertThat(ToolInvocationContext.cachedTool("internal_a")).isNull();
        assertThat(ToolInvocationContext.cachedTool("internal_b")).isNull();
    }

    /** 模拟跨线程调用（同线程顺序执行即可——快照的共享实例语义与线程无关）。 */
    private static void callWithInSubThread(ToolInvocationContext.Scope scope) {
        ToolInvocationContext.callWith(scope, () -> {
            assertThat(ToolInvocationContext.cachedTool("internal_a")).isEqualTo("desc-a");
            ToolInvocationContext.cacheTool("internal_b", "desc-b");
            return null;
        });
    }
}
