package com.helloai.core.agent.runtime.sandbox;

/**
 * 执行隔离策略（P0-C Phase 4，Sandbox Provider 契约核心）。
 *
 * <p>表达一次执行在五个边界上的隔离事实（真正安全沙箱需覆盖的维度）：
 * 文件系统 / 网络 / 进程 / 资源 / 凭证。当前实现必须诚实标注——平台尚未提供
 * 任何真实安全沙箱，环境策略一律 {@link IsolationLevel#NONE} 或天然
 * {@link IsolationLevel#PARTIAL}，不得标记 {@link IsolationLevel#ISOLATED}
 * （差距表 §6 口径：不得在无实际隔离能力时宣称安全沙箱已完成）。</p>
 *
 * @param filesystem 文件系统边界
 * @param network    网络边界
 * @param process    进程边界
 * @param resource   资源限制边界
 * @param credential 凭证边界
 */
public record ExecutionPolicy(
        IsolationLevel filesystem,
        IsolationLevel network,
        IsolationLevel process,
        IsolationLevel resource,
        IsolationLevel credential) {

    /** 边界隔离等级。 */
    public enum IsolationLevel {
        /** 平台强制隔离（当前无任何环境达到；Docker / K8s 后置）。 */
        ISOLATED,
        /** 部分 / 天然边界（非平台强制，如外部终端自有网络边界）。 */
        PARTIAL,
        /** 无隔离（本地进程直接运行在平台主机）。 */
        NONE
    }

    /** 无任何隔离（local-process 等平台内直跑环境的诚实事实）。 */
    public static ExecutionPolicy noIsolation() {
        return new ExecutionPolicy(IsolationLevel.NONE, IsolationLevel.NONE,
                IsolationLevel.NONE, IsolationLevel.NONE, IsolationLevel.NONE);
    }

    /** 远程终端环境：网络存在天然边界（外部终端自有网络，非平台强制），其余无平台级隔离。 */
    public static ExecutionPolicy remoteTerminal() {
        return new ExecutionPolicy(IsolationLevel.NONE, IsolationLevel.PARTIAL,
                IsolationLevel.NONE, IsolationLevel.NONE, IsolationLevel.NONE);
    }
}
