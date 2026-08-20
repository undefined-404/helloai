package com.helloai.core.shared.doorbell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DoorbellRegistry} 单元测试（AgentHub 门铃内核 PR-1）。
 *
 * <p>覆盖：注册/取用/计数、关旧建新（同 Agent 只保留一条连接）、
 * 值条件注销（旧连接回调不误删新连接）、空参数保护。</p>
 */
@DisplayName("DoorbellRegistry")
class DoorbellRegistryTest {

    @Test
    @DisplayName("注册后可取用且计入连接数")
    void shouldRegisterAndTrack() {
        DoorbellRegistry registry = new DoorbellRegistry();
        SseEmitter emitter = new SseEmitter();

        registry.register(1L, emitter);

        assertThat(registry.get(1L)).isSameAs(emitter);
        assertThat(registry.isConnected(1L)).isTrue();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一 Agent 二次建连关旧建新，注册表只保留新连接")
    void shouldReplaceOldConnectionOnReRegister() {
        DoorbellRegistry registry = new DoorbellRegistry();
        SseEmitter old = new SseEmitter();
        SseEmitter fresh = new SseEmitter();

        registry.register(2L, old);
        registry.register(2L, fresh);

        assertThat(registry.get(2L)).isSameAs(fresh);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("值条件注销：旧连接回调不会误删已换上的新连接")
    void shouldNotRemoveNewConnectionWhenOldCallbackFires() {
        DoorbellRegistry registry = new DoorbellRegistry();
        SseEmitter old = new SseEmitter();
        SseEmitter fresh = new SseEmitter();
        registry.register(3L, old);
        registry.register(3L, fresh);

        // 模拟旧连接的 onCompletion/onError 迟到回调
        registry.unregister(3L, old);

        assertThat(registry.get(3L)).isSameAs(fresh);
        assertThat(registry.isConnected(3L)).isTrue();
    }

    @Test
    @DisplayName("注销当前连接后计数归零")
    void shouldUnregisterCurrentConnection() {
        DoorbellRegistry registry = new DoorbellRegistry();
        SseEmitter emitter = new SseEmitter();
        registry.register(4L, emitter);

        registry.unregister(4L, emitter);

        assertThat(registry.get(4L)).isNull();
        assertThat(registry.isConnected(4L)).isFalse();
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("空参数保护：null 不抛异常")
    void shouldTolerateNullArguments() {
        DoorbellRegistry registry = new DoorbellRegistry();

        registry.unregister(null, null);
        registry.unregister(5L, null);

        assertThat(registry.get(null)).isNull();
        assertThat(registry.isConnected(null)).isFalse();
    }

    @Test
    @DisplayName("forEach 遍历访问到所有已登记连接")
    void shouldForEachOverAllConnections() {
        DoorbellRegistry registry = new DoorbellRegistry();
        SseEmitter e6 = new SseEmitter();
        SseEmitter e7 = new SseEmitter();
        registry.register(6L, e6);
        registry.register(7L, e7);

        Map<Long, SseEmitter> visited = new HashMap<>();
        registry.forEach(visited::put);

        assertThat(visited).hasSize(2);
        assertThat(visited.get(6L)).isSameAs(e6);
        assertThat(visited.get(7L)).isSameAs(e7);
    }

    @Test
    @DisplayName("forEach 对 null action 与空注册表均为安全 no-op")
    void shouldTolerateNullActionAndEmptyRegistry() {
        DoorbellRegistry registry = new DoorbellRegistry();

        registry.forEach(null);

        int[] count = {0};
        registry.forEach((id, emitter) -> count[0]++);
        assertThat(count[0]).isZero();
    }
}
