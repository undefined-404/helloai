package com.helloai.core.agent.tool.impl;

import com.helloai.core.agent.tool.ToolCallbackContributor;
import com.helloai.core.agent.tool.ToolContext;
import com.helloai.core.agent.tool.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具注册表单元测试：
 * 懒加载目录（首次 resolve 触发）+ 按启用工具名解析命中元数据（启用/匹配契约）
 * + REF-1.3 两个语义位（条件可用摘除 / 按上下文动态描述）与降级 fail-open 契约。
 * 纯 Mockito 测试，spring-ai ToolCallback / ToolDefinition 均为接口可 mock。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolRegistryImpl")
class ToolRegistryImplTest {

    @Mock
    private ToolCallbackProvider toolCallbackProvider;

    @Mock
    private ToolCallback pullTasks;

    @Mock
    private ToolCallback submitResult;

    @Mock
    private ToolCallback broken;

    // #region 基础契约（REF-1.3 前既有）

    @Test
    @DisplayName("should resolve enabled tool names to matched definitions in input order")
    void shouldResolveKnownToolsInInputOrder() {
        stubCatalog();

        List<ToolDefinition> resolved = registry()
                .resolve(List.of("pullTasks", "submitResult"), ToolContext.empty());

        assertThat(resolved).hasSize(2);
        assertThat(resolved.get(0).name()).isEqualTo("pullTasks");
        assertThat(resolved.get(0).description()).isEqualTo("拉取待处理收件箱");
        assertThat(resolved.get(1).name()).isEqualTo("submitResult");
        assertThat(resolved.get(1).description()).isEqualTo("上交执行结果");
    }

    @Test
    @DisplayName("should skip unknown tool names and keep known ones (启用/匹配契约)")
    void shouldSkipUnknownToolNames() {
        stubCatalog();

        List<ToolDefinition> resolved = registry()
                .resolve(List.of("pullTasks", "not-a-tool", "submitResult"), ToolContext.empty());

        assertThat(resolved).extracting(ToolDefinition::name)
                .containsExactly("pullTasks", "submitResult");
    }

    @Test
    @DisplayName("should return empty when enabledToolNames is null or empty (best-effort)")
    void shouldReturnEmptyForNullOrEmptyInput() {
        // 不 stub 目录：null/空入参直接短路，不触发目录加载（避免 UnnecessaryStubbing）
        ToolRegistryImpl registry = new ToolRegistryImpl(toolCallbackProvider, contributors());

        assertThat(registry.resolve(null, ToolContext.empty())).isEmpty();
        assertThat(registry.resolve(List.of(), ToolContext.empty())).isEmpty();
    }

    @Test
    @DisplayName("should degrade to empty catalog when getToolCallbacks returns null (懒加载防御)")
    void shouldDegradeWhenProviderReturnsNull() {
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(null);

        ToolRegistryImpl registry = new ToolRegistryImpl(toolCallbackProvider, contributors());

        assertThat(registry.resolve(List.of("pullTasks"), ToolContext.empty())).isEmpty();
    }

    @Test
    @DisplayName("should skip callbacks with null toolDefinition (装配防御)")
    void shouldSkipCallbackWithNullDefinition() {
        // broken 无有效 toolDefinition；pullTasks 正常——目录只含 pullTasks
        // 先构造定义再 stub（避免 thenReturn 参数内嵌套 when 触发 UnfinishedStubbing）
        org.springframework.ai.tool.definition.ToolDefinition pullDef =
                springDefinition("pullTasks", "拉取待处理收件箱");
        when(broken.getToolDefinition()).thenReturn(null);
        when(pullTasks.getToolDefinition()).thenReturn(pullDef);
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{broken, pullTasks});

        List<ToolDefinition> resolved = registry()
                .resolve(List.of("pullTasks", "broken"), ToolContext.empty());

        assertThat(resolved).extracting(ToolDefinition::name).containsExactly("pullTasks");
    }

    @Test
    @DisplayName("should load catalog once and reuse cache (懒加载只触发一次)")
    void shouldLoadCatalogOnce() {
        stubCatalog();
        ToolRegistryImpl registry = registry();

        registry.resolve(List.of("pullTasks"), ToolContext.empty());
        registry.resolve(List.of("pullTasks", "submitResult"), ToolContext.empty());

        // 两次 resolve 只触发一次目录加载
        org.mockito.Mockito.verify(toolCallbackProvider, org.mockito.Mockito.times(1)).getToolCallbacks();
    }

    // #endregion

    // #region REF-1.3 语义位

    @Test
    @DisplayName("★ 等价性回归：无任何声明者时，resolve 结果与 REF-1.3 前「目录命中集」逐字相同")
    void shouldBeEquivalentToLegacyFilteringWhenNoDeclarants() {
        stubCatalog();

        // 故意打乱入参顺序 + 混入未知名：旧口径 = 按入参顺序保留目录命中项、跳过未知项
        List<ToolDefinition> resolved = registry()
                .resolve(List.of("submitResult", "not-a-tool", "pullTasks"), ToolContext.empty());

        assertThat(resolved).containsExactly(
                new ToolDefinition("submitResult", "上交执行结果"),
                new ToolDefinition("pullTasks", "拉取待处理收件箱"));
    }

    @Test
    @DisplayName("条件可用：声明 false ⇒ 该工具摘除，同批其它工具的描述不受影响")
    void shouldRemoveToolDeclaredUnavailable() {
        stubCatalog();

        List<ToolDefinition> resolved = registry(declaring(Map.of("pullTasks", ctx -> false)))
                .resolve(List.of("pullTasks", "submitResult"), ToolContext.empty());

        assertThat(resolved).containsExactly(new ToolDefinition("submitResult", "上交执行结果"));
    }

    @Test
    @DisplayName("动态描述：声明优先；声明返回 null 时回落目录静态描述")
    void shouldPreferDeclaredDescriptionAndFallBackToCatalog() {
        stubCatalog();
        ToolCallbackContributor declarant = describing(Map.of(
                "pullTasks", ctx -> ctx.turn() == 3 ? "T3 动态描述" : null));

        ToolRegistryImpl registry = registry(declarant);

        assertThat(registry.resolve(List.of("pullTasks"), new ToolContext(1L, 2L, 3L, 3, null, List.of())))
                .containsExactly(new ToolDefinition("pullTasks", "T3 动态描述"));
        // turn != 3 ⇒ 声明返回 null ⇒ 回落目录静态描述
        assertThat(registry.resolve(List.of("pullTasks"), new ToolContext(1L, 2L, 3L, 1, null, List.of())))
                .containsExactly(new ToolDefinition("pullTasks", "拉取待处理收件箱"));
    }

    @Test
    @DisplayName("目录降级 fail-open：加载抛异常时未知名字保留，但条件可用声明照常生效")
    void shouldFailOpenOnDegradedCatalog() {
        when(toolCallbackProvider.getToolCallbacks()).thenThrow(new IllegalStateException("boom"));

        // 目录不可用 ⇒ 未知名保留（「未知」不构成摘除理由）；声明摘除照常生效（不依赖目录）
        List<ToolDefinition> resolved = registry(declaring(Map.of("pullTasks", ctx -> false)))
                .resolve(List.of("pullTasks", "unknown_tool"), ToolContext.empty());

        assertThat(resolved).extracting(ToolDefinition::name).containsExactly("unknown_tool");
    }

    @Test
    @DisplayName("声明违约 fail-open：可用性判定抛异常 ⇒ 保留；描述声明抛异常 ⇒ 回落目录静态描述")
    void shouldFailOpenOnDeclarantViolation() {
        stubCatalog();

        List<ToolDefinition> resolved = registry(
                declaring(Map.of("pullTasks", ctx -> {
                    throw new IllegalStateException("availability boom");
                })),
                describing(Map.of("submitResult", ctx -> {
                    throw new IllegalStateException("description boom");
                })))
                .resolve(List.of("pullTasks", "submitResult"), ToolContext.empty());

        assertThat(resolved).containsExactly(
                new ToolDefinition("pullTasks", "拉取待处理收件箱"),
                new ToolDefinition("submitResult", "上交执行结果"));
    }

    @Test
    @DisplayName("空上下文：resolve(names, null) 不抛，且以 ToolContext.empty() 传入声明者")
    void shouldPassEmptyContextWhenContextIsNull() {
        stubCatalog();
        AtomicReference<ToolContext> captured = new AtomicReference<>();
        ToolCallbackContributor declarant = declaring(Map.of("pullTasks", ctx -> {
            captured.set(ctx);
            return true;
        }));

        List<ToolDefinition> resolved = registry(declarant).resolve(List.of("pullTasks"), null);

        assertThat(resolved).extracting(ToolDefinition::name).containsExactly("pullTasks");
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().agentId()).isNull();
        assertThat(captured.get().taskId()).isNull();
        assertThat(captured.get().subTaskId()).isNull();
        assertThat(captured.get().turn()).isZero();
        assertThat(captured.get().accessType()).isNull();
        assertThat(captured.get().requiredSkills()).isEmpty();
    }

    @Test
    @DisplayName("重复声明：同名工具被两个贡献者声明 ⇒ 先注册者胜（确定性 + 可观测）")
    void shouldKeepFirstDeclarantOnDuplicateKeys() {
        stubCatalog();
        ToolCallbackContributor first = describing(Map.of("pullTasks", ctx -> "先注册者的描述"));
        ToolCallbackContributor second = describing(Map.of("pullTasks", ctx -> "后注册者的描述"));

        List<ToolDefinition> resolved = registry(first, second)
                .resolve(List.of("pullTasks"), ToolContext.empty());

        assertThat(resolved).containsExactly(new ToolDefinition("pullTasks", "先注册者的描述"));
    }

    @Test
    @DisplayName("去重去空：入参重复 / null / 空白名不产生重复项，也不误摘")
    void shouldDeduplicateAndSkipBlankNames() {
        stubCatalog();

        List<ToolDefinition> resolved = registry().resolve(
                Arrays.asList("pullTasks", null, "  ", "pullTasks", "submitResult"),
                ToolContext.empty());

        assertThat(resolved).extracting(ToolDefinition::name).containsExactly("pullTasks", "submitResult");
    }

    // #endregion

    // #region 测试装配

    /** 无声明者的注册表（等价于 REF-1.3 前的纯目录行为）。 */
    private ToolRegistryImpl registry(ToolCallbackContributor... declarants) {
        return new ToolRegistryImpl(toolCallbackProvider, contributors(declarants));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ToolCallbackContributor> contributors(ToolCallbackContributor... values) {
        ObjectProvider<ToolCallbackContributor> provider = mock(ObjectProvider.class);
        // lenient：null/空入参短路路径不触发声明面装配，用不到本桩
        lenient().when(provider.orderedStream()).thenReturn(Arrays.stream(values));
        return provider;
    }

    private static ToolCallbackContributor declaring(Map<String, Predicate<ToolContext>> availability) {
        return new DeclaringContributor(availability, Map.of());
    }

    private static ToolCallbackContributor describing(Map<String, Function<ToolContext, String>> description) {
        return new DeclaringContributor(Map.of(), description);
    }

    /**
     * 声明面测试替身：只承载两个声明方法（registry 只用这两个），
     * {@code toolObject()} 无实际用途。
     */
    private record DeclaringContributor(Map<String, Predicate<ToolContext>> availability,
                                        Map<String, Function<ToolContext, String>> description)
            implements ToolCallbackContributor {

        @Override
        public Object toolObject() {
            return this;
        }

        @Override
        public Map<String, Predicate<ToolContext>> toolAvailability() {
            return availability;
        }

        @Override
        public Map<String, Function<ToolContext, String>> toolDescription() {
            return description;
        }
    }

    private void stubCatalog() {
        // 先构造定义再 stub（避免 thenReturn 参数内嵌套 when 触发 UnfinishedStubbing）
        org.springframework.ai.tool.definition.ToolDefinition pullDef =
                springDefinition("pullTasks", "拉取待处理收件箱");
        org.springframework.ai.tool.definition.ToolDefinition submitDef =
                springDefinition("submitResult", "上交执行结果");
        when(pullTasks.getToolDefinition()).thenReturn(pullDef);
        when(submitResult.getToolDefinition()).thenReturn(submitDef);
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{pullTasks, submitResult});
    }

    private static org.springframework.ai.tool.definition.ToolDefinition springDefinition(String name, String description) {
        org.springframework.ai.tool.definition.ToolDefinition definition =
                mock(org.springframework.ai.tool.definition.ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(definition.description()).thenReturn(description);
        return definition;
    }

    // #endregion
}
