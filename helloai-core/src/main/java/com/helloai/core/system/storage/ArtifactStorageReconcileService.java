package com.helloai.core.system.storage;

/**
 * 存储对账巡检服务（DB {@code attachment} ↔ 对象存储真实对象）。
 *
 * <p>存在动因：dev 库是共享的（多环境连同一个 PostgreSQL），而对象存储历史上
 * 每个环境各一份，且 {@code attachment} 表不记录对象属于哪个存储实例。一换运行环境，
 * 历史附件集体变成悬空指针——表现为"DB 有记录但桶中查不到"、附件预览 500。
 * 这类问题只能靠"定期把两侧真实数据摊开对账"发现，靠业务日志发现不了。</p>
 *
 * <p><b>纪律</b>：对账默认只报告、不修数据（与 {@code EventReconciliationService}
 * 同构）。唯一的破坏性动作是孤儿清理，且受三重保险约束——显式开关（默认关闭）、
 * 对象最后修改时间必须早于时间窗、单轮删除上限；被任何 attachment 行（含逻辑删除）
 * 引用的对象永不删除。</p>
 *
 * @see ArtifactReconcileReport
 */
public interface ArtifactStorageReconcileService {

    /**
     * 执行一轮对账：枚举主存储桶内全部对象，与 attachment 全量行双向比对，
     * 按配置决定是否清理孤儿。不抛业务异常以外的任何异常给调用方（由任务层兜底）。
     *
     * @return 本轮对账结果快照
     */
    ArtifactReconcileReport reconcile();
}
