package com.helloai.core.agent.runtime.sandbox;

import java.util.Objects;

/**
 * 沙箱工作区句柄（`SL-8`，2026-11-13）：把「调用方给的一个 {@code Long}」拆成<b>身份 + 隔离 + 访问 + 寿命</b>。
 *
 * <p><b>为什么需要</b>：此前同一个 {@code Long} 承载三种含义——子任务 ID（执行链）、会话 ID
 * （技能执行 {@code scope:{id}}）、{@code null}（一次性校验）。沙箱层只能靠「查库看它是不是
 * 某个子任务」来猜，猜测逻辑（{@code SandboxScopes}）因此被塞进运行时的执行路径里。句柄把
 * 语义<b>提前到调用侧</b>：运行时只按句柄行事，不再解释 ID，也不再查业务库。</p>
 *
 * <p><b>字段职责</b>：</p>
 * <ul>
 *   <li>{@link Kind} + {@code ownerId}：复用作用域（槽键的 {@code kind:ownerId} 部分）；
 *       {@link Kind#TASK} 表示同任务子任务共享，{@link Kind#SCOPE} 表示会话内技能，
 *       {@link Kind#SUBTASK} 是 {@code scope=subtask} 回滚模式，{@link Kind#ONESHOT} 不进池；</li>
 *   <li>{@code contextId}：<b>观测归属</b>（子任务 / 会话），只用于台账与日志，<b>不参与槽键</b>——
 *       工作区是任务级的，台账却是子任务级的，两者不能混成一个 ID；</li>
 *   <li>{@code specFingerprint}：可选的规格<b>收窄</b>条件（读 / 释放时限定某一个规格槽）；
 *       {@code null} 表示「该作用域的全部规格」；</li>
 *   <li>{@link Isolation}：是否进槽池（{@link Isolation#EPHEMERAL_VERIFY} = 创建→执行→销毁）；</li>
 *   <li>{@link Access}：平台层读写意图（容器级只读由 `SL-10` 的 volume 挂载承载，本字段<b>不冒充</b>）；</li>
 *   <li>{@link Lifetime}：工作区应活到什么时候（任务终态 / 本轮结束 / 本次调用）。</li>
 * </ul>
 *
 * <p><b>不变量</b>：{@link Kind#ONESHOT} 与 {@link Isolation#EPHEMERAL_VERIFY} 必须同时出现
 * （一次性句柄不可能常驻池）；每种 {@link Kind} 只对应一种 {@link Lifetime}。不满足即构造失败——
 * 非法组合在构造期暴露，比在槽池里表现为「键对不上、悄悄多一个容器」好排查。</p>
 *
 * @param kind            复用作用域类型
 * @param ownerId         作用域归属 ID（taskId / scopeId；一次性句柄用 {@value #NO_OWNER}）
 * @param contextId       观测归属 ID（子任务 / 会话；可为 {@code null}）
 * @param specFingerprint 规格收窄条件（{@code null} = 不限规格）
 * @param isolation       隔离形态
 * @param access          平台层访问意图
 * @param lifetime        工作区寿命
 */
public record WorkspaceRef(Kind kind, Long ownerId, Long contextId, String specFingerprint,
                           Isolation isolation, Access access, Lifetime lifetime) {

    /** 一次性句柄无归属时的占位 ownerId（不进槽池，不参与任何键匹配）。 */
    public static final long NO_OWNER = 0L;

    /** 复用作用域类型（与 {@code SandboxScopes.Kind} 同义，独立定义使运行时不再依赖 legacy 解析器）。 */
    public enum Kind {
        /** 任务级共享（`SL-1`）：同任务子任务共用同一规格沙箱。 */
        TASK("task"),
        /** 子任务级（{@code scope=subtask} 回滚模式）：行为回到 2026-11-11 前。 */
        SUBTASK("subtask"),
        /** 直接作用域（会话技能 {@code scope:{conversationId}}）。 */
        SCOPE("scope"),
        /** 一次性（无复用上下文）：创建 → 执行 → 销毁。 */
        ONESHOT("oneshot");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        /** 槽键前缀码（日志 / 键匹配用）。 */
        public String code() {
            return code;
        }
    }

    /** 隔离形态。 */
    public enum Isolation {
        /** 进槽池，按寿命回收（任务终态 / 空闲回收器）。 */
        TASK_WORKSPACE,
        /** 不进槽池：创建 → 执行 → 立即销毁（对齐既有 {@code execute(null, ...)} 一次性语义）。 */
        EPHEMERAL_VERIFY
    }

    /** 平台层访问意图（容器级只读由 `SL-10` volume 挂载承载）。 */
    public enum Access {
        /** 读写。 */
        READ_WRITE,
        /** 只读意图（平台层声明；`SL-8` 阶段不阻止命令写文件）。 */
        READ_ONLY
    }

    /** 工作区寿命。 */
    public enum Lifetime {
        /** 活到任务终态（任务级共享）。 */
        TASK,
        /** 活到本轮结束（回滚模式的子任务级）。 */
        TURN,
        /** 只活到本次调用（一次性）。 */
        CALL
    }

    /** 构造期校验不变式（见类 javadoc）。 */
    public WorkspaceRef {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(isolation, "isolation must not be null");
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(lifetime, "lifetime must not be null");
        if ((kind == Kind.ONESHOT) != (isolation == Isolation.EPHEMERAL_VERIFY)) {
            throw new IllegalArgumentException("ONESHOT 与 EPHEMERAL_VERIFY 必须同时出现: kind="
                    + kind + ", isolation=" + isolation);
        }
        Lifetime expected = expectedLifetime(kind);
        if (lifetime != expected) {
            throw new IllegalArgumentException("kind=" + kind + " 的寿命必须是 " + expected
                    + "，实际 " + lifetime);
        }
    }

    /** 每种作用域类型唯一合法的寿命。 */
    private static Lifetime expectedLifetime(Kind kind) {
        return switch (kind) {
            case TASK, SCOPE -> Lifetime.TASK;
            case SUBTASK -> Lifetime.TURN;
            case ONESHOT -> Lifetime.CALL;
        };
    }

    /** 执行者工作区：任务级共享、读写、活到任务终态。 */
    public static WorkspaceRef task(Long taskId, Long contextId) {
        return new WorkspaceRef(Kind.TASK, taskId, contextId, null,
                Isolation.TASK_WORKSPACE, Access.READ_WRITE, Lifetime.TASK);
    }

    /** 核验者工作区：任务级共享、只读意图、活到任务终态（容器级只读随 `SL-10`）。 */
    public static WorkspaceRef taskReadOnly(Long taskId, Long contextId) {
        return new WorkspaceRef(Kind.TASK, taskId, contextId, null,
                Isolation.TASK_WORKSPACE, Access.READ_ONLY, Lifetime.TASK);
    }

    /** 回滚模式的子任务工作区（{@code scope=subtask}）：活到本轮结束。 */
    public static WorkspaceRef subtask(Long subTaskId) {
        return new WorkspaceRef(Kind.SUBTASK, subTaskId, subTaskId, null,
                Isolation.TASK_WORKSPACE, Access.READ_WRITE, Lifetime.TURN);
    }

    /** 会话技能工作区（{@code scope:{conversationId}}）。 */
    public static WorkspaceRef scope(Long scopeId, Long contextId) {
        return new WorkspaceRef(Kind.SCOPE, scopeId, contextId == null ? scopeId : contextId, null,
                Isolation.TASK_WORKSPACE, Access.READ_WRITE, Lifetime.TASK);
    }

    /** 一次性校验工作区：创建 → 执行 → 销毁，不占常驻资源。 */
    public static WorkspaceRef oneShot(Long contextId) {
        Long owner = contextId == null ? NO_OWNER : contextId;
        return new WorkspaceRef(Kind.ONESHOT, owner, contextId, null,
                Isolation.EPHEMERAL_VERIFY, Access.READ_ONLY, Lifetime.CALL);
    }

    /** 追加 / 收窄规格指纹（{@code null} 表示不限规格；返回新实例）。 */
    public WorkspaceRef withSpecFingerprint(String fingerprint) {
        return new WorkspaceRef(kind, ownerId, contextId, fingerprint, isolation, access, lifetime);
    }

    /** 槽键前缀 {@code {kind}:{ownerId}}（规格指纹另拼）。 */
    public String key() {
        return kind.code() + ":" + ownerId;
    }

    /** 是否进槽池复用。 */
    public boolean pooled() {
        return isolation == Isolation.TASK_WORKSPACE;
    }

    /** 是否为只读意图句柄。 */
    public boolean readOnly() {
        return access == Access.READ_ONLY;
    }
}
