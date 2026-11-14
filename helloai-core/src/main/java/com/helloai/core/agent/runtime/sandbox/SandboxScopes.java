package com.helloai.core.agent.runtime.sandbox;

import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 沙箱作用域解析（`SL-1`，2026-11-12）：把「调用方给的上下文 ID」解析成槽池作用域键。
 *
 * <p><b>为什么需要</b>（`SL-1` 任务级作用域）：既有复用槽以 {@code subTaskId} 为键，导致
 * 同一任务的两个子任务各占一个沙箱、工作区互不可见，返工轮还会读到上一轮的残留。按任务级
 * 共享（{@code task:{taskId}}）后，调用方传入的 ID 既可能是真正的子任务 ID，也可能是会话内
 * 技能执行的 {@code scopeId}（{@code conversationId}，`SKX-8`）——两者必须路由到不同的键。</p>
 *
 * <p><b>解析规则</b>：</p>
 * <ol>
 *     <li>{@link SubTaskQueryPort#findById} 命中 ⇒ {@code task:{taskId}}（该子任务所属任务级共享）；</li>
 *     <li>未命中（如会话技能 scopeId）/ 查询异常 ⇒ {@code scope:{id}}（直接作用域，保持 `SKX-8` 既有语义）；</li>
 * </ol>
 *
 * <p><b>缓存</b>：解析结果做小容量 LRU（{@value #CACHE_CAPACITY}），避免每条命令都查一次库；
 * 任务终态时由外部调用 {@link #invalidateTask(Long)} 失效，防「任务已销毁、键仍指向旧作用域」。</p>
 *
 * <p><b>边界（诚实）</b>：{@code findById} 返回 null 与抛异常统一回落 {@code scope:{id}}——
 * 宁可少共享（直接作用域）也不阻断执行；DB 抖动期间的回落结果会进缓存，
 * 但作用域语义只影响复用面、不影响正确性（同一 id 所有调用路径解析一致）。</p>
 */
@Component
public class SandboxScopes {

    /** 作用域类型。 */
    public enum Kind {
        /** 任务级共享（{@code task:{taskId}}）：同一任务下的子任务共用同一规格沙箱（`SL-1`）。 */
        TASK,
        /** 直接作用域（{@code scope:{id}}）：调用方 ID 直接作键（会话技能 / 子任务级回滚语义）。 */
        SCOPE
    }

    /**
     * 解析结果（作用域 <b>类型 + 数值 ID</b>，槽键 = {@code {kind}:{id}} + 规格指纹）。
     *
     * @param kind 作用域类型
     * @param id   taskId（{@link Kind#TASK}）或调用方原始 ID（{@link Kind#SCOPE}）
     */
    public record Resolved(Kind kind, Long id) {

        public static Resolved task(Long taskId) {
            return new Resolved(Kind.TASK, taskId);
        }

        public static Resolved scope(Long scopeId) {
            return new Resolved(Kind.SCOPE, scopeId);
        }
    }

    /** 解析结果缓存上限（子任务数，进程内）。 */
    static final int CACHE_CAPACITY = 1024;

    private final SubTaskQueryPort subTaskQueryPort;

    /** 解析结果 LRU：{@code 上下文ID → Resolved}，accessOrder 模式，超容量淘汰最久未访问。 */
    private final Map<Long, Resolved> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Resolved> eldest) {
                    return size() > CACHE_CAPACITY;
                }
            });

    public SandboxScopes(SubTaskQueryPort subTaskQueryPort) {
        this.subTaskQueryPort = subTaskQueryPort;
    }

    /**
     * 解析上下文 ID 的作用域（带缓存）。
     *
     * @param contextId 调用方上下文 ID（子任务 ID 或会话技能 scopeId；{@code null} ⇒ {@code null}）
     * @return 解析结果；{@code contextId == null} 返回 {@code null}
     */
    public Resolved resolve(Long contextId) {
        if (contextId == null) {
            return null;
        }
        Resolved cached = cache.get(contextId);
        if (cached != null) {
            return cached;
        }
        Resolved resolved = lookup(contextId);
        cache.put(contextId, resolved);
        return resolved;
    }

    /**
     * 任务终态时失效该任务下全部子任务的解析缓存（`SL-2` 监听器接线）。
     *
     * <p>防「任务已销毁 / 已重建，缓存仍指向旧 {@code task:{taskId}}」——失效后再次解析会
     * 重新查库；对 {@code scope:{id}}（非子任务）条目无影响。</p>
     *
     * @param taskId 任务 ID；{@code null} 静默忽略
     */
    public void invalidateTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        synchronized (cache) {
            cache.entrySet().removeIf(entry -> entry.getValue() != null
                    && entry.getValue().kind() == Kind.TASK
                    && Objects.equals(entry.getValue().id(), taskId));
        }
    }

    /** 实际解析（不缓存）：查询失败与未命中同待遇，回落直接作用域。 */
    private Resolved lookup(Long contextId) {
        try {
            SubTaskSnapshot snapshot = subTaskQueryPort.findById(contextId);
            if (snapshot != null && snapshot.taskId() != null) {
                return Resolved.task(snapshot.taskId());
            }
        } catch (Exception e) {
            // 查询异常保守回落：不阻断执行（与「未命中」同待遇，见类注释边界）
        }
        return Resolved.scope(contextId);
    }
}