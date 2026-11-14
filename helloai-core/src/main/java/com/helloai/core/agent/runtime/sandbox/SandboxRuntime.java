package com.helloai.core.agent.runtime.sandbox;

import java.util.List;

/**
 * 沙箱运行时（`SL-8`，2026-11-13）：以 {@link WorkspaceRef} 为唯一入口的执行面。
 *
 * <p><b>与 {@link SandboxExecutionService} 的分工</b>：本接口是<b>运行时</b>——只认句柄，不解释
 * 「这个 ID 是谁」，不查业务库；{@code SandboxExecutionService} 退为<b>适配层</b>，负责把老的
 * 「一个 {@code Long}」参数映射成句柄（见 {@code SandboxRuntimeAdapter}）。</p>
 *
 * <p><b>角色无关</b>：执行者 / 核验者 / 平台自检的差异全部由句柄承载（{@link WorkspaceRef.Access} /
 * {@link WorkspaceRef.Isolation}）。运行时不知道调用方是谁，也不应该知道——否则每次新增角色都要
 * 改沙箱实现。</p>
 *
 * <p><b>失败也结构化</b>：{@link #exec} 沿既有口径返回 {@link ExecuteResponse}（超时 / 异常都不抛）；
 * 读 / 写 / 探测失败降级为 {@code null} / {@code false}，不抛异常（与 {@code SandboxProvider.resolve}
 * 的 null 语义一致）。</p>
 */
public interface SandboxRuntime {

    /**
     * 工作区根（`SL-3`）：容器创建后立即预建，所有命令以此作为 {@code workingDirectory}。
     *
     * <p>模型写 {@code ./x.py} 与 {@code /workspace/x.py} 必须落到同一个文件；容器内默认
     * <b>没有</b>该目录（`SKX-1` 实测 {@code pwd=/}），故它是运行时契约而不是实现细节。</p>
     */
    String WORKSPACE_DIR = "/workspace";

    /**
     * 在句柄指向的工作区内执行一次命令。
     *
     * @param ref            工作区句柄（非 null；{@link WorkspaceRef#pooled()} 为 false ⇒ 一次性语义）
     * @param spec           沙箱规格；{@code null} ⇒ 全局默认
     * @param command        命令（bash -lc 语义）；null / 空白 ⇒ 结构化失败
     * @param timeoutSeconds 命令超时秒数；null / &lt;=0 ⇒ 默认 300，上限 300
     * @param origin         调用来源（台账用；null ⇒ 执行者）
     * @param round          执行轮次（台账用；无上下文记 0）
     * @return 统一执行结果
     */
    ExecuteResponse exec(WorkspaceRef ref, SandboxSpec spec, String command, Integer timeoutSeconds,
                         SandboxCallOrigin origin, int round);

    /** 默认规格 / 默认来源 / 轮次 0 的执行重载。 */
    default ExecuteResponse exec(WorkspaceRef ref, String command, Integer timeoutSeconds) {
        return exec(ref, null, command, timeoutSeconds, SandboxCallOrigin.EXECUTOR, 0);
    }

    /**
     * 按绝对路径读取工作区内的一份文件（<b>不创建</b>沙箱）。
     *
     * @param ref      工作区句柄
     * @param path     容器内绝对路径（含 {@code ..} / 非绝对 ⇒ {@code null}）
     * @param maxBytes 单次回读上限；{@code <=0} ⇒ 默认 4MB（超限截断并置 {@code truncated=true}）
     * @return 产物（含 sha256 / MIME）；无活跃工作区 / 路径不可读 ⇒ {@code null}
     */
    SandboxArtifact readFile(WorkspaceRef ref, String path, int maxBytes);

    /**
     * 判断工作区内某路径是否存在（<b>不创建</b>沙箱）。
     *
     * @param ref  工作区句柄
     * @param path 容器内绝对路径
     * @return 存在为 true；无活跃工作区 / 路径非法 / 探测失败为 false
     */
    boolean stat(WorkspaceRef ref, String path);

    /**
     * 列出工作区内某目录的条目（<b>不创建</b>沙箱）。
     *
     * <p><b>为什么单独一个动词</b>：`SL-9` 把「挑哪一份文件算交付物」的策略移出运行时后，
     * 消费侧仍需要看见目录内容。运行时只提供这份机制（路径 / 大小 / 修改时间 / 类型），
     * 排序与筛选全部留给调用方。</p>
     *
     * @param ref   工作区句柄
     * @param dir   容器内绝对路径
     * @param depth 递归深度（{@code <=0} ⇒ 服务端默认）；`SL-9` 的调用方用 2 层
     * @return 条目快照；无活跃工作区 / 路径非法 / 探测失败 ⇒ 空列表（不抛异常）
     */
    List<WorkspaceEntry> listFiles(WorkspaceRef ref, String dir, int depth);

    /**
     * 写入工作区内的一个文件（必要时创建沙箱）。
     *
     * <p><b>只写已知绝对路径</b>：不做「相对路径按 /workspace 补全」——那会把写错路径变成
     * 写到另一个位置。写入是覆盖语义，天然可重入。</p>
     *
     * @param ref     工作区句柄
     * @param path    容器内绝对路径
     * @param content 文件字节（null ⇒ false）
     * @return 写入成功为 true；失败降级为 false
     */
    boolean writeFile(WorkspaceRef ref, String path, byte[] content);

    /**
     * 释放句柄作用域下的复用工作区。
     *
     * <p><b>收窄语义</b>：{@code specFingerprint == null} ⇒ 释放该 {@code kind:ownerId} 下<b>全部规格</b>
     * 的槽（任务终态回收用）；非 null ⇒ 只释放该规格。一次性句柄（不进池）静默返回。</p>
     *
     * @param ref 工作区句柄；null ⇒ 静默返回
     */
    void release(WorkspaceRef ref);
}
