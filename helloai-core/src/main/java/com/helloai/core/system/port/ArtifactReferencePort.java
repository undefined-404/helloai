package com.helloai.core.system.port;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 产物引用读取端口（§6.146 端口反转）：system 域存储对账巡检不依赖 task 域
 * service/entity，一切「被引用产物元数据」诉求经本端口收口。
 *
 * <p>由 task 域 {@code ArtifactReferencePortAdapter} 独立实现
 * （{@code task → system} 属合法向下依赖，且 task 域本已依赖
 * {@code system.storage.ArtifactStorage}）；独立实现而非挂在业务服务上，
 * 避免与 task 域构成构造器依赖环。</p>
 *
 * <p>返回值只暴露基本类型值对象 {@link ArtifactReference}，不泄漏 task 域实体，
 * 保证 {@code system → task} 依赖方向零反向。</p>
 */
public interface ArtifactReferencePort {

    /**
     * 全量读取产物引用行，<b>包含逻辑删除（{@code deleted=1}）的记录</b>。
     *
     * <p>对账口径必须保守：只要还有任意一行（任意状态、含已逻辑删除）指向某个对象，
     * 该对象就不算孤儿，不得被清理。若只取 {@code deleted=0} 的行，
     * 任务级联删除（逻辑删除路径）留下的对象会被误判成孤儿而删除。</p>
     *
     * @return 全部产物引用（绝不返回 null）
     */
    List<ArtifactReference> listAllIncludingDeleted();

    /**
     * <b>跨表对象回收护栏</b>：在给定候选 {@code objectKey} 集合中，返回仍被本表
     * <b>活跃行（{@code deleted = 0}）</b>引用的子集。
     *
     * <p><b>为什么需要它</b>：会话附件（{@code chat_attachment}）与子任务产物（{@code attachment}）
     * 共用同一个桶，仅 {@code objectKey} 前缀不同。一旦按「同 {@code objectKey} 双行」把主任务附件
     * 引用给子任务，同一个对象就同时被两张表的行指向——此时任何一侧单独回收对象都会打断另一侧
     * 的引用（会话侧删一张图，子任务正在用的那份字节随之消失）。</p>
     *
     * <p><b>谁调它</b>：planner 域回收会话附件对象前，查本表是否仍有活跃行指向同一
     * {@code objectKey}。planner(0) → system(5) 属合法向下依赖，且不跨域直捅 Mapper。</p>
     *
     * <p><b>口径</b>：只认 {@code deleted = 0}——已逻辑删除的行不再构成「仍被使用」，
     * 否则会话侧永远等不到回收时机。返回结果<b>可能含重复</b>（同一 key 多条活跃行），
     * 调用方自行去重或直接用 {@code contains} 判定。</p>
     *
     * @param keys 候选 objectKey 集合（用方保证非空、已去重）
     * @return 其中仍存在活跃行引用的 objectKey（可能重复；绝不返回 null）
     */
    Set<String> listActiveObjectKeysIn(Collection<String> keys);
}
