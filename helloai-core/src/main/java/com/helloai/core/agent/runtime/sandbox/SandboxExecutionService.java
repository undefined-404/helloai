package com.helloai.core.agent.runtime.sandbox;

import java.util.List;

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
 * <p>实现契约（`SL-8` 起见 {@code SandboxRuntimeAdapter}：本接口是<b>薄适配层</b>，把老的
 * {@code Long} 参数映射成 {@link WorkspaceRef} 后交给 {@link SandboxRuntime}）：沙箱未启用 ⇒ 返回
 * {@code ExecuteResponse("sandbox execution disabled", EXIT_CODE_ERROR, false)}
 * （工具已摘除，正常情况下不会被调用；此处是纵深防御）。</p>
 */
public interface SandboxExecutionService {

    /**
     * 在沙箱内执行一次命令，返回统一执行结果。
     *
     * @param subTaskId      调用方子任务 ID（上下文日志定位；可为 null）
     * @param command        要在沙箱内执行的命令（bash -lc 语义）或脚本正文；null/空白 ⇒ 结构化失败
     * @param timeoutSeconds 命令超时秒数；null / <=0 ⇒ 默认 300；上限 300（超出钳制到上限）
     * @return 统一执行结果（超时 / 失败 / SDK 异常也结构化返回，不抛异常）
     */
    ExecuteResponse execute(Long subTaskId, String command, Integer timeoutSeconds);

    /**
     * 按<b>显式规格</b>执行一次命令（`SKX-5`）。
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
     * @param timeoutSeconds 超时秒数；null / <=0 ⇒ 默认 300，上限 300
     */
    default ExecuteResponse execute(Long subTaskId, SandboxSpec spec, String command, Integer timeoutSeconds) {
        return execute(subTaskId, command, timeoutSeconds);
    }

    /**
     * 带<b>来源与轮次</b>执行一次命令（`SL-4`）。
     *
     * <p>台账条目需标明「谁跑的、第几轮」：自审证据只接受执行者本人条目（`SL-5`），
     * 轮次用于旧格式证据的「本轮优先」。来源由<b>调用侧</b>声明——沙箱层不推断角色。</p>
     *
     * <p><b>默认实现忽略来源与轮次</b>：不支持台账的实现（未来内存沙箱）行为不变。</p>
     *
     * @param subTaskId      调用方子任务 ID（可为 null）
     * @param spec           沙箱规格；null ⇒ 全局默认
     * @param command        命令（bash -lc 语义）
     * @param timeoutSeconds 超时秒数；null / &lt;=0 ⇒ 默认 300，上限 300
     * @param origin         调用来源；null ⇒ 执行者
     * @param round          轮次（子任务执行轮次；无上下文记 0）
     */
    default ExecuteResponse execute(Long subTaskId, SandboxSpec spec, String command, Integer timeoutSeconds,
                                    SandboxCallOrigin origin, int round) {
        return execute(subTaskId, spec, command, timeoutSeconds);
    }

    /**
     * 释放某子任务作用域下的<b>复用</b>工作沙箱（`ADR-005` 生命周期出口）。
     *
     * <p><b>默认已不参与主链路</b>（`SL-2`）：任务级作用域下工作区须跨 turn /
     * 跨子任务保留，回收统一走 {@link #releaseByTask(Long)}（由任务终态事件触发）。本方法仅在
     * {@code helloai.sandbox.opensandbox.scope=subtask} 灰度回滚模式下由命令消费侧于 turn 末
     * 调用，用于恢复「一次执行尝试结束即释放」的旧语义（见 `LocalExecutionCommandConsumer`
     * 的 {@code isSubtaskScope()}）。</p>
     *
     * <p><b>幂等</b>：未登记 / {@code null} 键静默返回；实现若不支持复用可保留空实现。</p>
     *
     * <p><b>并发</b>：本方法按<b>解析后的作用域</b>删除槽，与 {@link #execute} 并发时不做互斥
     * （回滚模式下仍假设「同一子任务的消费串行」）；任务级批量回收的互斥语义在
     * {@link #releaseByTask(Long)}（逐槽 {@code tryLock}）。</p>
     *
     * @param subTaskId 子任务 ID；null 或未登记时静默返回
     */
    default void release(Long subTaskId) {
        // 默认空实现：不支持复用的实现无需关心释放语义
    }

    /**
     * 释放某<b>任务</b>的全部复用工作沙箱（`SL-1`/`SL-2`）。
     *
     * <p><b>为什么需要按任务释放</b>：`SL-1` 起复用槽键为 {@code task:{taskId}} + 规格指纹，
     * 同一任务的多个子任务共享同一规格的沙箱。任务进入终态（{@code DONE}/{@code CANCELLED}/
     * 删除）时，必须经本方法一次性销毁该任务下全部规格的沙箱——只按子任务释放会漏掉
     * 「共享给其它子任务」的那些槽，靠空闲 TTL 兜底回收。</p>
     *
     * <p><b>接线</b>：由 {@code SandboxTaskTerminalListener}（{@code @Async} +
     * {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)}）在任务终态事件后调用。
     * 幂等：未登记 ⇒ 零操作；重复事件 ⇒ 槽已不在册，静默返回。</p>
     *
     * <p><b>并发</b>：逐槽 {@code tryLock} 后再销毁——拿不到锁说明该槽仍有命令在跑，
     * 跳过并交给空闲 TTL，<b>不误杀</b>在执行的工作区（`ADR-005` §6 风险 4）。</p>
     *
     * <p><b>仅解耦管理面</b>：本方法只做「槽池摘除 + 销毁 + 作用域缓存失效」，不校验任务状态——
     * 状态机语义（什么状态算终态）由事件发布侧决定，实现侧不复制判据。</p>
     *
     * @param taskId 任务 ID；null 或未登记时静默返回
     */
    default void releaseByTask(Long taskId) {
        // 默认空实现：不支持任务级作用域的实现无需关心
    }

    /**
     * 列出工作区内某目录的条目（`SL-9`，；<b>不创建</b>沙箱）。
     *
     * <p><b>为什么在通道上</b>：{@code SL-9} 把「哪一份文件算交付物」的策略（最新 {@code .md} 优先、
     * 体积兜底）移到了消费侧 {@code DeliverableReader}，但消费侧仍需要一眼看见目录里有什么。
     * 本方法只提供这份机制，排序与筛选由调用方决定。</p>
     *
     * <p><b>默认实现返回空列表</b>：不支持目录读取的实现（未来可能的内存沙箱）不必假装有文件；
     * 消费侧据此按「无候选」保守处理。</p>
     *
     * @param subTaskId 子任务 ID（null ⇒ 空列表）
     * @param dir       容器内绝对路径（通常为 {@link SandboxRuntime#WORKSPACE_DIR}）
     * @return 两层深度的条目快照；无工作区 / 路径非法 / 失败 ⇒ 空列表
     */
    default List<WorkspaceEntry> listWorkspaceFiles(Long subTaskId, String dir) {
        return List.of();
    }

    /**
     * 把 classpath 里的技能包注入该子任务的复用工作区（`SKX-6`）。
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
     * 按<b>指定路径</b>回读沙箱内的一份产物（`SKX-7`）。
     *
     * <p><b>与 {@link #listWorkspaceFiles} 的分工</b>：列举只回答「目录里有什么」；本方法按
     * <b>调用方给定的确切路径</b>取字节（如 {@code /workspace/out/x.html}），且必须是字节级原样
     * ——回读要能算 {@code sha256} 与工具回执对账。选择哪一份文件不是本通道的职责。</p>
     *
     * <p><b>为什么走 SDK 文件通道而不是命令</b>：命令输出有 100,000 字符全局截断
     * （{@code MAX_OUTPUT_CHARS}），834KB HTML 的 base64 必然被截断；且单次
     * {@code commands().run()} 固定开销 ≈1s，而 {@code readByteArray} 读回 823KB 实测 34ms。
     * 任一理由都足以否掉命令通路（`SKX-3` 实测，计划 §2.4 第 30/33 条）。</p>
     *
     * <p><b>只读语义</b>：无活跃槽 ⇒ {@code null}（不为读文件新建沙箱）；路径不存在 ⇒ {@code null}；
     * 失败一律降级为 {@code null}，不抛异常（与 {@link #execute} 同一条容错哲学）。</p>
     *
     * @param subTaskId 子任务 ID（定位复用槽；{@code null} ⇒ {@code null}）
     * @param path      容器内<b>绝对路径</b>（如 {@code /workspace/out/archify.html}）；含 {@code ..} 或非绝对 ⇒ {@code null}
     * @param maxBytes  单次回读上限（字节）；{@code <=0} ⇒ 默认 4MB。超限时读前 N 字节并置 {@code truncated=true}
     * @return 回读结果；不可读 ⇒ {@code null}
     */
    default SandboxArtifact readArtifact(Long subTaskId, String path, int maxBytes) {
        return null;
    }

    /**
     * 把字节写入沙箱内指定路径（`RV-14` stage-in：附件注入沙箱供实测执行）。
     *
     * <p><b>与 {@link #readArtifact} 互为逆操作、同一条通道哲学</b>：走 SDK 文件通道而非命令
     * （命令通路的 100KB 输出截断与 ≈1s 固定开销对写入同样成立）；句柄解析复用
     * {@code toRef} 的身份分面——reviewer 落任务级只读句柄对应的容器、executor 落可写容器，
     * 写进的是<b>调用者自己随后要跑命令的那个沙箱</b>。</p>
     *
     * <p><b>失败语义</b>：沙箱未启用 / 句柄非法 / 写入失败一律降级为 {@code false}，
     * 不抛异常（与 {@link #execute} / {@link #readArtifact} 同一条容错哲学）；
     * 写入是覆盖语义，天然可重入。</p>
     *
     * @param subTaskId 子任务 ID（定位复用槽；{@code null} ⇒ {@code false}）
     * @param path      容器内<b>绝对路径</b>（如 {@code /tmp/staged/12-data.csv}）；非绝对 ⇒ {@code false}
     * @param content   文件字节（{@code null} ⇒ {@code false}）
     * @return 写入成功为 true
     */
    default boolean writeArtifact(Long subTaskId, String path, byte[] content) {
        return false;
    }

    /**
     * 按<b>显式 reviewer 身份</b>把字节写入审核工作区（`RV-15`，：审核证据自动物化）。
     *
     * <p><b>与 {@link #writeArtifact} 的分工（为什么需要显式身份）</b>：
     * {@code writeArtifact} 经 {@code toRef} 的<b>身份分面</b>解析句柄——reviewer 场景靠
     * {@code ToolInvocationContext.currentReviewer()}（工具循环入口注入）落到任务级只读槽；
     * 而平台编排层（{@code ReviewExecutionEngine} 等）<b>不在工具循环里</b>，ThreadLocal 未绑定，
     * 此时 {@code toRef} 会把句柄解析成 {@code task()}（执行者可写槽）——证据会被写进
     * <b>executor 的沙箱</b>而不是 reviewer 的。本方法由编排层<b>显式传 {@code taskId}</b>，
     * 绕开 ThreadLocal 分面，恒定落到 reviewer 的任务级只读槽。</p>
     *
     * <p><b>★不变量（`F2`，与 {@code internal_run_in_sandbox} 同槽）</b>：reviewer 执行命令走
     * {@code run_in_sandbox}（传 {@code spec=null} ⇒ 全局默认规格 + 默认规格指纹），本方法写文件
     * 同样使用<b>默认规格</b>（{@code OpenSandboxRuntime.writeFile} 内部取 {@code properties.getSpec()}、
     * 不加指纹）——两路同落 {@code task:{taskId}} 默认指纹槽，物化后的文件 reviewer 的后续命令
     * 一定能看见。<b>任何一侧若改用显式规格/指纹，本不变量即被打破、物化静默失效</b>
     * （症状正是 RV-14 消灭过的「find 白跑」）。由单测钉死（{@code ReviewEvidenceMaterializerTest}
     * 断言「物化后 run_in_sandbox 能 ls 到该路径」）。</p>
     *
     * <p><b>F1 修正口径（不支持时不假装能写）</b>：{@code reuse=false}（一次性语义）时
     * {@code EPHEMERAL_VERIFY} 句柄不 {@code pooled()}，{@code writeFile} 第一道闸即返回
     * {@code false}——本方法<b>直接返回 false</b>，不构造必然失败的写入；物化器侧在调用前
     * 自行判 {@code reuse-enabled} 并把「不支持」表达进证据清单（enabled=false + 原因），
     * 引导模型退回 {@code internal_read_attachment_range} 分段核对。</p>
     *
     * <p><b>失败语义</b>：沙箱未启用 / 句柄非法 / 写入失败一律降级为 {@code false} 不抛异常
     * （与 {@link #writeArtifact} 同一条容错哲学）；写入是覆盖语义，天然可重入（物化一次够
     * 首轮 + 不可解析重试轮共用）。</p>
     *
     * @param taskId    任务 ID（锁定 reviewer 任务级只读槽；{@code null} ⇒ {@code false}）
     * @param subTaskId 子任务 ID（观测归属；{@code null} ⇒ {@code false}）
     * @param path      容器内<b>绝对路径</b>（如 {@code /tmp/staged/12-data.csv}）；非绝对 ⇒ {@code false}
     * @param content   文件字节（{@code null} ⇒ {@code false}）
     * @return 写入成功为 true
     */
    default boolean writeReviewerArtifact(Long taskId, Long subTaskId, String path, byte[] content) {
        return false;
    }
}
