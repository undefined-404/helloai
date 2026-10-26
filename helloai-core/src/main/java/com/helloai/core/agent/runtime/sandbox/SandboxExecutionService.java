package com.helloai.core.agent.runtime.sandbox;

/**
 * 沙箱执行通道（S-7，沙箱执行链设计方案 §3.3）。
 *
 * <p>单一执行通道——「把命令交给沙箱执行并返回统一结果」。屏蔽 OpenSandbox SDK 细节，
 * 供 {@code InternalLlmExecutorTools}（{@code internal_run_in_sandbox} 工具）与
 * {@code FinalReportFidelityGate}（S-9 机械判定）调用。</p>
 *
 * <p>边界：只回答「命令在沙箱内执行得怎样」，不承载判定（pass/fail 由消费方负责）；
 * 失败/超时/异常都返回结构化 {@link ExecuteResponse}，不抛异常（与
 * {@code SandboxProvider.resolve} 的 null 语义一致的容错哲学）。</p>
 *
 * <p>实现契约（见 {@code OpenSandboxExecutionService}）：沙箱未启用 ⇒ 返回
 * {@code ExecuteResponse("sandbox execution disabled", EXIT_CODE_ERROR, false)}
 * （工具已摘除，正常情况下不会被调用；此处是纵深防御）。</p>
 */
public interface SandboxExecutionService {

    /**
     * 在沙箱内执行一次命令，返回统一执行结果。
     *
     * @param subTaskId      调用方子任务 ID（上下文日志定位；可为 null）
     * @param command        要在沙箱内执行的命令（bash -lc 语义）或脚本正文；null/空白 ⇒ 结构化失败
     * @param timeoutSeconds 命令超时秒数；null / <=0 ⇒ 默认 30；上限 300（超出钳制到上限）
     * @return 统一执行结果（超时 / 失败 / SDK 异常也结构化返回，不抛异常）
     */
    ExecuteResponse execute(Long subTaskId, String command, Integer timeoutSeconds);

    /**
     * 按<b>显式规格</b>执行一次命令（`SKX-5`，2026-10-26）。
     *
     * <p>与三参版本的唯一差别是「用哪个规格建沙箱」：{@code spec == null} ⇒ 全局默认
     * （{@code helloai.sandbox.opensandbox.spec}）；非 null ⇒ 按该规格建，且复用槽的键带上
     * <b>规格指纹</b>，使同一子任务的 python 槽与 node 槽互不串用（否则第二次调用会拿到
     * 没有目标运行时的旧容器）。</p>
     *
     * <p><b>默认实现忽略规格并委托三参版本</b>：不支持分层的实现（未来可能的内存 / 环境沙箱）
     * 行为不变，调用方无需按实现类型分支。</p>
     *
     * @param subTaskId      调用方子任务 ID（可为 null）
     * @param spec           沙箱规格；null ⇒ 全局默认
     * @param command        命令（bash -lc 语义）
     * @param timeoutSeconds 超时秒数；null / <=0 ⇒ 默认 30，上限 300
     */
    default ExecuteResponse execute(Long subTaskId, SandboxSpec spec, String command, Integer timeoutSeconds) {
        return execute(subTaskId, command, timeoutSeconds);
    }

    /**
     * 释放某子任务的<b>复用</b>工作沙箱（`ADR-005` 生命周期出口，2026-10-25）。
     *
     * <p>对齐 deepseek-harness `dsh-tool-bash-persistent` 的口径：同一子任务内的多次调用
     * 复用同一沙箱（写入的文件跨调用保留，模型可 `sed -i` 定点修改而非整篇重写）；
     * 但<b>子任务执行结束即释放</b>——避免「上一次尝试的残留文件影响下一次尝试」，
     * 也保证「不跨子任务共享」。</p>
     *
     * <p>由 {@code LocalExecutionCommandConsumer#runViaRuntime} 在 turn 结束时调用；
     * 幂等（未登记 / null 键静默返回）。实现若不支持复用可置为空实现。</p>
     *
     * <p><b>并发假设</b>：调用方须保证与 {@link #execute} <b>不并发</b>（当前实现假设
     * 「同一子任务的消费是串行的」，`release` 落在同一消费线程的 `finally` 里）。
     * 若将来出现同子任务并发消费，实现需先取槽锁再销毁，否则可能杀掉在跑命令的工作区。</p>
     *
     * @param subTaskId 子任务 ID；null 或未登记时静默返回
     */
    default void release(Long subTaskId) {
        // 默认空实现：不支持复用的实现无需关心释放语义
    }

    /**
     * 回读该子任务工作沙箱里<b>篇幅最大的一份 {@code .md} 正文</b>（2026-10-25，`LOG-20261025-010`）。
     *
     * <p><b>为什么需要这条通道</b>：平台只采集<b>对话正文</b>（{@code AgentExecutionResult.output}），
     * 沙箱文件从不自动上传。实测 MiniMax-M3 两次执行都是「20 轮全在沙箱里写 {@code /workspace/draft.md}、
     * 对话区只剩 604 字节写作过程叙述」⇒ 终态 {@code STOP} + 围栏齐全 ⇒ 假成功 ⇒ 评审 3 轮全驳回 ⇒ 死信。
     * 正文其实早就写出来了，只是没交出去。本方法就是在 turn 末把它「捞回来」的通道。</p>
     *
     * <p><b>只读语义</b>：<b>不创建</b>沙箱（无活跃工作区直接返回 {@code null}）——
     * 为读一个文件白建沙箱要 3 秒 + 一份资源，且必然读到空目录。</p>
     *
     * <p><b>不承载判定</b>：方法只回答「工作区里最大那份 .md 是什么」，回传与否、是否构成
     * 可交付正文由调用方按 {@code DeliverableDraft} 判定（与 {@link #execute} 一致的边界）。</p>
     *
     * @param subTaskId 子任务 ID（null ⇒ 返回 null）
     * @return 文件正文；无工作区 / 无 {@code .md} / 读取失败 ⇒ {@code null}
     */
    default String readWorkspaceFile(Long subTaskId) {
        return null;
    }

    /**
     * 把 classpath 里的技能包注入该子任务的复用工作区（`SKX-6`，2026-10-26）。
     *
     * <p><b>为什么是「注入」而不是「让模型自己下载」</b>：沙箱默认禁出网（`SKX-5` 起显式
     * {@code deny}），模型无法 {@code npm install} / {@code curl} 取包；且执行依赖必须
     * <b>可复现</b>——同一版本镜像 + 同一版本包，才能让「昨天跑通的东西今天还跑得通」。</p>
     *
     * <p><b>落点</b>：{@code /workspace/skills/<packageName>/}，保持包内相对结构不变
     * （容器内默认<b>没有</b> {@code /workspace}，实现须自行建目录）。</p>
     *
     * <p><b>幂等</b>：目标目录已有同版本同内容的标记文件 ⇒ 直接返回
     * {@code skipped=true} 的成功结果，不重写 122 个文件；内容不同 ⇒ 覆盖写（写是覆盖语义，
     * 天然可重入）。</p>
     *
     * <p><b>规格</b>：注入按 {@code spec} 建/取槽（与 {@link #execute} 同一把键：子任务 + 规格指纹），
     * 因此「先装包、再跑命令」两次调用会命中<b>同一个</b>容器——这正是技能执行的前提。
     * 传 {@code null} ⇒ 全局默认规格（python），此时装进去的 node 技能包<b>跑不起来</b>，
     * 调用方应显式传技能规格。</p>
     *
     * <p><b>默认实现返回失败</b>：不支持文件注入的实现（未来可能的内存沙箱）不必假装成功——
     * 让上层看到「没有技能可用」并降级，比「装了个空包然后命令报 not found」好排查。</p>
     *
     * @param subTaskId   子任务 ID（复用槽键的一部分；{@code null} ⇒ 失败，一次性沙箱装完即销毁无意义）
     * @param spec        建槽用的规格（{@code null} ⇒ 全局默认）
     * @param packageName 包名（对应 {@code classpath:skills/packages/<packageName>/}）
     * @param version     版本（对应包目录名）
     * @return 注入结果（失败也结构化返回，不抛异常）
     */
    default SkillInstallResult installSkillPackage(Long subTaskId, SandboxSpec spec,
                                                   String packageName, String version) {
        return SkillInstallResult.fail("skill install unsupported by "
                + getClass().getSimpleName());
    }

    /**
     * {@link #installSkillPackage(Long, SandboxSpec, String, String)} 的默认规格重载。
     *
     * @param subTaskId   子任务 ID
     * @param packageName 包名
     * @param version     版本
     */
    default SkillInstallResult installSkillPackage(Long subTaskId, String packageName, String version) {
        return installSkillPackage(subTaskId, null, packageName, version);
    }

    /**
     * 按<b>指定路径</b>回读沙箱内的一份产物（`SKX-7`，2026-10-26）。
     *
     * <p><b>为什么不能复用 {@link #readWorkspaceFile}</b>：那条通道是「挑工作区里最大的 .md」，
     * 只服务「对话正文兜底捞回」一个场景。技能产物是<b>调用方知道确切路径</b>的（{@code /workspace/out/x.html}），
     * 且必须是<b>字节级原样</b>——回读要能算 {@code sha256} 与工具回执对账。</p>
     *
     * <p><b>为什么走 SDK 文件通道而不是命令</b>：命令输出有 100,000 字符全局截断
     * （{@code MAX_OUTPUT_CHARS}），834KB HTML 的 base64 必然被截断；且单次
     * {@code commands().run()} 固定开销 ≈1s，而 {@code readByteArray} 读回 823KB 实测 34ms。
     * 任一理由都足以否掉命令通路（`SKX-3` 实测，计划 §2.4 第 30/33 条）。</p>
     *
     * <p><b>只读语义</b>：无活跃槽 ⇒ {@code null}（不为读文件新建沙箱）；路径不存在 ⇒ {@code null}；
     * 失败一律降级为 {@code null}，不抛异常（与 {@link #readWorkspaceFile} 同一条容错哲学）。</p>
     *
     * @param subTaskId 子任务 ID（定位复用槽；{@code null} ⇒ {@code null}）
     * @param path      容器内<b>绝对路径</b>（如 {@code /workspace/out/archify.html}）；含 {@code ..} 或非绝对 ⇒ {@code null}
     * @param maxBytes  单次回读上限（字节）；{@code <=0} ⇒ 默认 4MB。超限时读前 N 字节并置 {@code truncated=true}
     * @return 回读结果；不可读 ⇒ {@code null}
     */
    default SandboxArtifact readArtifact(Long subTaskId, String path, int maxBytes) {
        return null;
    }
}
