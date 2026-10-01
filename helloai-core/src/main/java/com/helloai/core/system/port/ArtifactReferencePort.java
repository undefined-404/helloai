package com.helloai.core.system.port;

import java.util.List;

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
}
