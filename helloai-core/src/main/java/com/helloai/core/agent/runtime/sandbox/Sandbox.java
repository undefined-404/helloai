package com.helloai.core.agent.runtime.sandbox;

import com.helloai.core.agent.runtime.ExecutionEnvironment;

/**
 * 沙箱解析结果（P0-C Phase 4）。
 *
 * <p>{@code environment} 表达「在哪执行」（场所，ExecutionEnvironment 既有抽象）；
 * {@code policy} 表达「隔离到什么程度」（安全边界事实）。二者分离：Environment 是
 * 场所标签，Policy 是隔离事实——避免把「执行环境」误等同「安全沙箱」（差距表 §6）。</p>
 *
 * @param providerName 沙箱提供方标识（轻量路由标识，与 AgentExecutor.getName() 同风格）
 * @param environment  执行环境（永不为 null）
 * @param policy       隔离策略（诚实标注，当前无 {@link ExecutionPolicy.IsolationLevel#ISOLATED}）
 */
public record Sandbox(String providerName, ExecutionEnvironment environment, ExecutionPolicy policy) {
}
