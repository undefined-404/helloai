package com.helloai.core.shared.doorbell;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;

/**
 * 门铃连接注册表（AgentHub V3 门铃内核 PR-1）。
 *
 * <p>维护 {@code agentId → SseEmitter} 的进程内映射，是门铃能"定位某个 Agent 的长连接
 * 并主动响铃"的核心——这正是 spring-ai MCP 传输层封装掉、业务侧拿不到的能力
 * （见 {@code doc/HelloAI_门铃通知通道设计.md} §4.3）。</p>
 *
 * <p>设计取舍，仿 {@link com.helloai.core.agent.mcp.McpAuthContext}：</p>
 * <ul>
 *   <li>纯进程内 {@link ConcurrentMap}，连接态本就不该持久化，故不落库；</li>
 *   <li>同一 Agent 只保留一条门铃连接：新连接到来时关旧建新，防止连接泄漏；</li>
 *   <li>注销用<b>值条件删除</b>（{@link ConcurrentMap#remove(Object, Object)}），
 *       避免旧连接的异步清理回调误删刚建立的新连接。</li>
 * </ul>
 *
 * <p>单实例假设：若 Agent 的连接落在实例 B、而收件箱写入发生在实例 A，
 * A 侧响铃找不到连接，该次由 pullTasks 轮询兜底。多副本实时性优化为后续演进。</p>
 */
@Slf4j
@Component
public class DoorbellRegistry {

    /** agentId → 该 Agent 当前唯一的门铃 SSE 连接。 */
    private final ConcurrentMap<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 注册一条门铃连接。若该 Agent 已有旧连接，先关旧再建新（关旧建新，仿 startLease）。
     *
     * @param agentId Agent ID
     * @param emitter 新建立的 SSE 连接
     */
    public void register(Long agentId, SseEmitter emitter) {
        SseEmitter old = emitters.put(agentId, emitter);
        if (old != null && old != emitter) {
            log.info("门铃关旧建新: agentId={}", agentId);
            try {
                old.complete();
            } catch (Exception ignore) {
                // 旧连接可能已断，忽略
            }
        }
    }

    /**
     * 注销门铃连接（值条件删除）。仅当当前注册的正是该 emitter 时才移除，
     * 避免旧连接的 onCompletion/onError 回调误删新连接。
     */
    public void unregister(Long agentId, SseEmitter emitter) {
        if (agentId == null || emitter == null) {
            return;
        }
        if (emitters.remove(agentId, emitter)) {
            log.debug("门铃连接已注销: agentId={}", agentId);
        }
    }

    /** 取指定 Agent 的当前门铃连接，未连返回 null。 */
    public SseEmitter get(Long agentId) {
        return agentId == null ? null : emitters.get(agentId);
    }

    /** 指定 Agent 当前是否已连门铃。 */
    public boolean isConnected(Long agentId) {
        return get(agentId) != null;
    }

    /** 当前门铃连接总数（≈ 在线且已连门铃的外部 Agent 数）。 */
    public int size() {
        return emitters.size();
    }

    /**
     * 遍历当前所有活跃连接（供服务端定时保活广播使用）。
     *
     * <p>基于 {@link ConcurrentHashMap#forEach} 的弱一致遍历：遍历期间允许并发
     * {@code register}/{@code unregister}（包括 {@code action} 内部因发送失败触发的
     * 自注销），不会抛 {@code ConcurrentModificationException}。</p>
     *
     * @param action 对每一条 {@code (agentId, emitter)} 执行的动作
     */
    public void forEach(BiConsumer<Long, SseEmitter> action) {
        if (action == null) {
            return;
        }
        emitters.forEach(action);
    }
}
