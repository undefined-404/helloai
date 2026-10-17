package com.helloai.core.agent.runtime.sandbox;

/**
 * 执行隔离策略（P0-C，Sandbox Provider 契约核心）。
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

    /**
     * OpenSandbox 容器沙箱的诚实五边界（S-2，沙箱集成计划 §4.3）。
     *
     * <p>这是平台首次可诚实标注「平台强制隔离」的边界，但<b>逐项诚实标注、不标全
     * ISOLATED</b>（《协作规约》§22 + 《目标架构》§7 + 差距表 §6：不得在无实际
     * 隔离能力时宣称「安全沙箱已完成」）：</p>
     *
     * <ul>
     *   <li>filesystem = {@link IsolationLevel#ISOLATED}：沙箱容器独立文件系统，工作区随容器生命周期（OpenSandbox server 强制）；</li>
     *   <li>network = {@link IsolationLevel#PARTIAL}：容器与 app 网络物理隔离 + egress 白名单控制，但默认 egress 为空（deny），实际连通性取决于配置，平台未强制「全禁」；</li>
     *   <li>process = {@link IsolationLevel#PARTIAL}：容器内进程隔离 + pids 限制，但共享宿主内核（非 microVM）；</li>
     *   <li>resource = {@link IsolationLevel#ISOLATED}：cpu/memory 资源限制由 server 强制；</li>
     *   <li>credential = {@link IsolationLevel#ISOLATED}：沙箱容器零平台凭据 env（不注入 app env；平台凭据密钥绝不进沙箱）——「执行环境接触不到凭据」是真实隔离事实。</li>
     * </ul>
     */
    public static ExecutionPolicy openSandbox() {
        return new ExecutionPolicy(IsolationLevel.ISOLATED, IsolationLevel.PARTIAL,
                IsolationLevel.PARTIAL, IsolationLevel.ISOLATED, IsolationLevel.ISOLATED);
    }
}
