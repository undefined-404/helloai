package com.helloai.core.agent.runtime.sandbox;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.impl.SkillPackageInstaller;
import com.helloai.core.agent.tool.ToolInvocationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 沙箱执行适配层（`SL-8`，2026-11-13）：把老的「一个 {@code Long}」签名映射成 {@link WorkspaceRef}。
 *
 * <p><b>为什么存在</b>：{@link SandboxExecutionService} 的消费者传的是子任务 ID / 会话 ID /
 * {@code null}，语义由上下文决定。把「ID → 作用域」的猜测收拢到这一层，运行时实现
 * （{@code OpenSandboxRuntime}）就不再需要 {@code SubTaskQueryPort}——这是去查库依赖的
 * 唯一正确落点：<b>只有适配层允许查库</b>。</p>
 *
 * <p><b>也是唯一的实现装配点（`SL-9`）</b>：本类按接口拿 {@link SandboxRuntime}，技能注入则直接
 * 委托 {@link SkillPackageInstaller}（SDK 专属步骤，没有第二个实现）。除本类外，上层只认
 * {@link SandboxExecutionService}。</p>
 *
 * <p><b>解析顺序（{@link #toRef}）</b>：</p>
 * <ol>
 *   <li>{@code contextId == null} ⇒ 一次性句柄（{@link WorkspaceRef#oneShot}）；</li>
 *   <li>复用关闭 ⇒ 一次性句柄（保持「关闭复用即回落一次性语义」的既有配置口径）；</li>
 *   <li>执行链已绑定 {@code taskId}（`SL-8` 透传）⇒ 直接构造任务级句柄，<b>不查库</b>——
 *       绑定的值来自执行链自身，比二次查库更准；</li>
 *   <li>其余（turn 末释放 / 无绑定调用）⇒ {@link SandboxScopes} 解析（带 LRU，唯一查库点）。</li>
 * </ol>
 *
 * <p><b>作用域回滚</b>：{@code helloai.sandbox.opensandbox.scope=subtask} ⇒ 子任务级句柄，不查库
 * （行为回到 2026-11-11 前）。</p>
 *
 * <p><b>为什么 {@code taskId} 优先于查库</b>：`SL-1` 起槽键是任务级，若每条命令都查一次
 * 「子任务属于哪个任务」，一次 20 轮执行就是 20 次额外查询；执行链本来就知道 {@code taskId}
 * （{@code RuntimeTurnExecutor} / {@code BusinessSurfaceLoopRunner} 都持有），透传一次即可。</p>
 */
@Slf4j
@Component
public class SandboxRuntimeAdapter implements SandboxExecutionService {

    /** 列目录深度：{@code /workspace/x.md} 与 {@code /workspace/out/x.md} 都要看见（对齐原 maxdepth 2）。 */
    private static final int LIST_DEPTH = 2;

    private final SandboxRuntime runtime;
    private final SkillPackageInstaller skillInstaller;
    private final SandboxScopes scopes;
    private final OpenSandboxProperties properties;

    public SandboxRuntimeAdapter(SandboxRuntime runtime, SkillPackageInstaller skillInstaller,
                                 SandboxScopes scopes, OpenSandboxProperties properties) {
        this.runtime = runtime;
        this.skillInstaller = skillInstaller;
        this.scopes = scopes;
        this.properties = properties;
    }

    @Override
    public ExecuteResponse execute(Long subTaskId, String command, Integer timeoutSeconds) {
        return execute(subTaskId, null, command, timeoutSeconds, SandboxCallOrigin.EXECUTOR, 0);
    }

    @Override
    public ExecuteResponse execute(Long subTaskId, SandboxSpec spec, String command,
                                   Integer timeoutSeconds) {
        return execute(subTaskId, spec, command, timeoutSeconds, SandboxCallOrigin.EXECUTOR, 0);
    }

    @Override
    public ExecuteResponse execute(Long subTaskId, SandboxSpec spec, String command,
                                   Integer timeoutSeconds, SandboxCallOrigin origin, int round) {
        return runtime.exec(toRef(subTaskId), spec, command, timeoutSeconds, origin, round);
    }

    @Override
    public void release(Long subTaskId) {
        if (subTaskId == null) {
            return;
        }
        // 释放路径按「作用域的全部规格」清（不带指纹），与既有 release(subTaskId) 语义一致
        runtime.release(toRef(subTaskId));
    }

    @Override
    public void releaseByTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        runtime.release(WorkspaceRef.task(taskId, null));
        // 作用域缓存同步失效：任务已终结，「子任务 ID ⇒ task:{taskId}」的旧解析不应再命中
        scopes.invalidateTask(taskId);
    }

    @Override
    public List<WorkspaceEntry> listWorkspaceFiles(Long subTaskId, String dir) {
        if (subTaskId == null) {
            return List.of();
        }
        return runtime.listFiles(toRef(subTaskId), dir, LIST_DEPTH);
    }

    @Override
    public SkillInstallResult installSkillPackage(Long subTaskId, SandboxSpec spec,
                                                  String packageName, String version) {
        if (subTaskId == null) {
            return SkillInstallResult.fail("subTaskId must not be null when installing a skill package");
        }
        return skillInstaller.install(toRef(subTaskId), spec, packageName, version);
    }

    @Override
    public SandboxArtifact readArtifact(Long subTaskId, String path, int maxBytes) {
        if (subTaskId == null) {
            return null;
        }
        return runtime.readFile(toRef(subTaskId), path, maxBytes);
    }

    /**
     * 上下文 ID → 工作区句柄（解析顺序见类 javadoc）。
     *
     * @param contextId 子任务 ID / 会话技能 scopeId / {@code null}
     * @return 句柄；{@code contextId == null} ⇒ 一次性句柄
     */
    WorkspaceRef toRef(Long contextId) {
        if (contextId == null) {
            return WorkspaceRef.oneShot(null);
        }
        if (!properties.isReuseEnabled()) {
            return WorkspaceRef.oneShot(contextId);
        }
        if (!taskScopeEnabled()) {
            return WorkspaceRef.subtask(contextId);
        }
        // `SL-10` 审查者身份（核验/终报告审查循环带出的 reviewer 标志）→ 任务级只读句柄，
        // 静态检查在容器层以只读卷落底，与 EPHEMERAL_VERIFY 一次性路径的只读一致。
        boolean reviewer = ToolInvocationContext.currentReviewer();
        Long boundTaskId = ToolInvocationContext.currentTaskId();
        if (boundTaskId != null) {
            return reviewer
                    ? WorkspaceRef.taskReadOnly(boundTaskId, contextId)
                    : WorkspaceRef.task(boundTaskId, contextId);
        }
        SandboxScopes.Resolved resolved = scopes.resolve(contextId);
        return switch (resolved.kind()) {
            case TASK -> reviewer
                    ? WorkspaceRef.taskReadOnly(resolved.id(), contextId)
                    : WorkspaceRef.task(resolved.id(), contextId);
            case SCOPE -> WorkspaceRef.scope(resolved.id(), contextId);
        };
    }

    /** 任务级作用域是否启用（{@link OpenSandboxProperties#getScope()}；null 按缺省 task）。 */
    private boolean taskScopeEnabled() {
        String scope = properties.getScope();
        return scope == null || scope.isBlank() || "task".equalsIgnoreCase(scope.trim());
    }
}
