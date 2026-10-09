package com.helloai.core.agent.tool.impl;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 动态描述委托包装（REF-1.3）：ToolCallback 本体不变，仅覆盖
 * {@code getToolDefinition().description()}——使<b>模型可见 schema</b> 反映本轮生效描述。
 *
 * <p><b>为什么自写</b>：spring-ai <b>1.1.8</b>（{@code pom.xml} 的
 * {@code spring-ai.version}）的 {@link ToolCallback} 无装饰 / 复制入口，
 * {@link ToolDefinition#builder()} 也不提供 {@code from(ToolDefinition)} / {@code toBuilder()}
 * （已核对 {@code spring-ai-model-1.1.8-sources.jar}）。故按「接口最小实现 + 全量委托」自写。</p>
 *
 * <p><b>执行语义零变化</b>：{@code call} / {@code getToolMetadata} 全量委托给 {@code delegate}。
 * 生产链路中循环已 {@code setInternalToolExecutionEnabled(false)}，工具执行走
 * {@code ToolExecutor}，本包装仅用于「模型可见 schema」。</p>
 *
 * <p><b>★ 前置条件：{@code description} 必须非空白</b>。{@code DefaultToolDefinition.Builder.build()}
 * 在 description 为空白时会 fallback 到 {@code ParsingUtils.reConcatenateCamelCase(name)}，
 * 把工具名拆成「Pull Tasks」之类的伪描述——调用方（{@code RuntimeTurnExecutor}）已在
 * 上游保证「空白 ⇒ 不包装」。</p>
 *
 * <p><b>命名空间提示</b>：本类是包内<b>唯一</b>同时看到 spring-ai
 * {@code org.springframework.ai.chat.model.ToolContext}（工具<b>执行</b>期上下文）
 * 与 {@code com.helloai.core.agent.tool.ToolContext}（工具<b>解析</b>期上下文，REF-1.3）的地方——
 * 后者在本类中不出现，其 import 收敛于此，避免二者在其它文件里混用。</p>
 *
 * @param delegate    原始工具回调（执行语义由其承载，本类不改写）
 * @param description 本轮生效描述（非空白）
 */
public record DescribedToolCallback(ToolCallback delegate, String description) implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
        ToolDefinition origin = delegate.getToolDefinition();
        return ToolDefinition.builder()
                .name(origin.name())
                .description(description)
                .inputSchema(origin.inputSchema())
                .build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }
}
