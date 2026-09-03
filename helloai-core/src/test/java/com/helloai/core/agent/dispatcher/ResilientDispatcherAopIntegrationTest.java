package com.helloai.core.agent.dispatcher;

import com.helloai.common.base.AgentUnavailableException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.port.TaskDispatchPort;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ResilientDispatcher} Spring AOP 织入集成测试。
 *
 * <p>目的：验证 {@code @CircuitBreaker(name="agentDispatch", fallbackMethod="assignNextFallback")}
 * 在 Spring Boot 上下文中确实经过 Spring AOP 代理织入；fallback 在被保护方法抛异常时真实触发。</p>
 *
 * <p>本测试不复用 {@link ResilientDispatcherTest}（纯单元测试，直接 new 不触发拦截）。</p>
 *
 * <p>关键断言：
 * <ul>
 *   <li>{@link ResilientDispatcher} Bean 是 AOP 代理（AopUtils.isAopProxy）</li>
 *   <li>用 OFFLINE CLI_CLIENT 调用 {@link ResilientDispatcher#assignNext} 触发 fallback</li>
 *   <li>替代 Agent 是同角色健康 Agent</li>
 *   <li>{@link SubTaskService#assignNext} 被调用了两次：原 OFFLINE 一次 + 替代 Agent 一次</li>
 * </ul>
 * </p>
 */
@SpringBootTest(
        classes = ResilientDispatcherAopIntegrationTest.MinimalTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("ResilientDispatcher Spring AOP 织入")
class ResilientDispatcherAopIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ResilientDispatcher resilientDispatcher;

    @MockBean
    private SubTaskService subTaskService;

    @MockBean
    private AgentService agentService;

    @MockBean
    private AgentSelector agentSelector;

    @MockBean
    private com.helloai.core.agent.observability.CircuitBreakerEventRecorder circuitBreakerEventRecorder;

    @MockBean
    private AgentDispatchProperties agentDispatchProperties;

    @MockBean
    private TaskTimelineService taskTimelineService;

    @Test
    @DisplayName("Bean 是 Spring AOP 代理（不是原生对象）")
    void shouldBeAopProxiedBean() {
        // Resilience4j @CircuitBreaker 必须经过 Spring AOP 才能织入 fallback
        assertThat(AopUtils.isAopProxy(resilientDispatcher))
                .as("ResilientDispatcher 必须被 Spring AOP 代理")
                .isTrue();
        assertThat(AopUtils.isCglibProxy(resilientDispatcher)
                || AopUtils.isJdkDynamicProxy(resilientDispatcher))
                .as("必须是 CGLIB 或 JDK 动态代理之一")
                .isTrue();
    }

    @Test
    @DisplayName("OFFLINE CLI_CLIENT 调用 assignNext → 触发 fallback → 任务被分配给同角色健康替代 Agent")
    void shouldTriggerFallbackOnOfflineAgent() {
        // 准备：原 Agent = OFFLINE CLI_CLIENT EXECUTOR
        Agent offlineAgent = new Agent();
        offlineAgent.setId(101L);
        offlineAgent.setName("offline-cli-agent");
        offlineAgent.setRole(AgentRole.EXECUTOR);
        offlineAgent.setAccessType(AgentAccessType.CLI_CLIENT);
        offlineAgent.setStatus(AgentStatus.ACTIVE);
        offlineAgent.setOnlineStatus(AgentOnlineStatus.OFFLINE);
        offlineAgent.setLastSeenTime(OffsetDateTime.now().minusMinutes(15));

        // 准备：替代 Agent = ONLINE CLI_CLIENT EXECUTOR（同角色）
        Agent alternativeAgent = new Agent();
        alternativeAgent.setId(202L);
        alternativeAgent.setName("healthy-cli-agent");
        alternativeAgent.setRole(AgentRole.EXECUTOR);
        alternativeAgent.setAccessType(AgentAccessType.CLI_CLIENT);
        alternativeAgent.setStatus(AgentStatus.ACTIVE);
        alternativeAgent.setOnlineStatus(AgentOnlineStatus.ONLINE);
        alternativeAgent.setScore(80);
        alternativeAgent.setLastSeenTime(OffsetDateTime.now().minusMinutes(2));

        // 模拟：agentService.getById(101L) → 返回 offlineAgent
        //       agentService.getById(202L) → 返回 alternativeAgent
        when(agentService.getById(101L)).thenReturn(offlineAgent);
        when(agentService.getById(202L)).thenReturn(alternativeAgent);

        // 模拟：pickAlternative 排除原 Agent 后选出替代 Agent（为 3 参：约束贯穿 fallback）
        when(agentSelector.pickAlternative(eq(101L), any(), any())).thenReturn(alternativeAgent);

        // 调用受保护的方法：触发 fallback
        resilientDispatcher.assignNext(101L, 999L);

        // 断言 1：fallback 中调用的 subTaskService.assignNext 是替代 Agent ID
        verify(agentSelector, times(1)).pickAlternative(eq(101L), any(), any());
        verify(subTaskService, times(1)).assignNext(eq(202L), eq(999L));
        // 断言 2：原 OFFLINE Agent 的 subTaskService.assignNext 没有被调用
        //         （因为它在 fast-fail 阶段抛出 AgentUnavailableException，根本走不到 assignNext）
        verify(subTaskService, never()).assignNext(eq(101L), anyLong());
    }

    @Test
    @DisplayName("Advised.getTargetClass() 仍然指向 ResilientDispatcher（非 fallback）")
    void shouldExposeOriginalClassViaAdvised() throws Exception {
        // Advised 接口是 AOP 代理的标准特征；如果不是代理，这里会抛 ClassCastException
        assertThat(resilientDispatcher).isInstanceOf(Advised.class);
        Advised advised = (Advised) resilientDispatcher;
        Class<?> targetClass = advised.getTargetClass();
        assertThat(targetClass).isEqualTo(ResilientDispatcher.class);
    }

    @Test
    @DisplayName("fallback 契约：所有 @CircuitBreaker fallbackMethod 必须在同参签名（±Throwable 尾参），否则降级静默失效（锁定 2026-09-04 签名 500 回归）")
    void shouldMatchFallbackSignaturesForAllCircuitBreakerMethods() {
        for (Method protectedMethod : ResilientDispatcher.class.getDeclaredMethods()) {
            CircuitBreaker cb = protectedMethod.getAnnotation(CircuitBreaker.class);
            if (cb == null) {
                continue;
            }
            Class<?>[] originalParams = protectedMethod.getParameterTypes();
            Method fallback = Arrays.stream(ResilientDispatcher.class.getDeclaredMethods())
                    .filter(f -> f.getName().equals(cb.fallbackMethod()))
                    .filter(f -> {
                        Class<?>[] fp = f.getParameterTypes();
                        if (fp.length == originalParams.length) {
                            return Arrays.equals(fp, originalParams);
                        }
                        return fp.length == originalParams.length + 1
                                && fp[fp.length - 1] == Throwable.class
                                && Arrays.equals(Arrays.copyOf(fp, originalParams.length), originalParams);
                    })
                    .findFirst()
                    .orElse(null);
            assertThat(fallback)
                    .as("@CircuitBreaker(fallbackMethod=%s) 必须存在签名匹配的 fallback 方法", cb.fallbackMethod())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("首选不在任务级白名单 → 约束版 fallback 触发 → 同角色白名单内替代成功重分配（500 回归锁）")
    void shouldTriggerConstrainedFallbackWhenPreferredOutsideWhitelist() {
        // 首选：ONLINE API_KEY_LLM EXECUTOR（不在白名单，score 高但被任务级约束排除）
        Agent preferred = new Agent();
        preferred.setId(101L);
        preferred.setName("preferred-outside-whitelist");
        preferred.setRole(AgentRole.EXECUTOR);
        preferred.setAccessType(AgentAccessType.API_KEY_LLM);
        preferred.setStatus(AgentStatus.ACTIVE);
        preferred.setOnlineStatus(AgentOnlineStatus.ONLINE);
        preferred.setLastSeenTime(OffsetDateTime.now().minusMinutes(1));

        // 替代：白名单内健康 EXECUTOR
        Agent whitelisted = new Agent();
        whitelisted.setId(202L);
        whitelisted.setName("whitelisted-agent");
        whitelisted.setRole(AgentRole.EXECUTOR);
        whitelisted.setAccessType(AgentAccessType.API_KEY_LLM);
        whitelisted.setStatus(AgentStatus.ACTIVE);
        whitelisted.setOnlineStatus(AgentOnlineStatus.ONLINE);
        whitelisted.setScore(60);
        whitelisted.setLastSeenTime(OffsetDateTime.now().minusMinutes(1));

        when(agentService.getById(101L)).thenReturn(preferred);
        when(agentService.getById(202L)).thenReturn(whitelisted);
        when(agentSelector.pickAlternative(eq(101L), any(), any())).thenReturn(whitelisted);

        // 任务级白名单只允许 202L；首选 101L 不在其内 → fast-fail → fallback 选 202L
        TaskDispatchPort.DispatchConstraints constraints =
                TaskDispatchPort.DispatchConstraints.of(List.of(202L), null);
        resilientDispatcher.assignNext(101L, 999L, constraints);

        verify(agentSelector, times(1)).pickAlternative(eq(101L), any(), any());
        verify(subTaskService, times(1)).assignNext(eq(202L), eq(999L));
        verify(subTaskService, never()).assignNext(eq(101L), anyLong());
    }

    /**
     * 最小化 Spring Boot 集成上下文：仅加载 AOP + Resilience4j 必需 Bean，
     * 避免触发数据库 / Redis / MQ 等基础设施。
     *
     * <p>§4.1：使用 {@link SpringBootConfiguration}（非 @TestConfiguration），
     * 让 {@code SpringBootTestContextBootstrapper} 能识别入口配置类；通过
     * {@link ImportAutoConfiguration} 精确启用 {@link AopAutoConfiguration}（启用
     * {@link AnnotationAwareAspectJAutoProxyCreator}）与
     * {@link CircuitBreakerAutoConfiguration}（注册 Resilience4j 的
     * {@code CircuitBreakerAspect}），并显式 {@link EnableAspectJAutoProxy}
     * 兜底，使 {@code @CircuitBreaker} 能被 Spring AOP 织入。</p>
     */
    @SpringBootConfiguration
    @EnableAspectJAutoProxy
    @ImportAutoConfiguration({
            AopAutoConfiguration.class,
            CircuitBreakerAutoConfiguration.class
    })
    @Import(ResilientDispatcher.class)
    static class MinimalTestConfig {

        /**
         * 真实的 CircuitBreakerRegistry：测试可以验证 per-agent 熔断器被正确创建。
         */
        @Bean
        public CircuitBreakerRegistry circuitBreakerRegistry() {
            CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
                    CircuitBreakerConfig.ofDefaults());
            // 注册模板配置，让 ResilientDispatcher 的 resolvePerAgentCircuitBreaker
            // 走"按模板"分支（生产环境由 application.yml 提供）。
            registry.addConfiguration("agentDispatch", CircuitBreakerConfig.ofDefaults());
            return registry;
        }
    }
}