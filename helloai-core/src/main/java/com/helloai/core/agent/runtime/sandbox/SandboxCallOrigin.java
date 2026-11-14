package com.helloai.core.agent.runtime.sandbox;

/**
 * 沙箱调用的来源方（`SL-4`，2026-11-13）。
 *
 * <p><b>为什么需要</b>：任务级复用后同一子任务的台账里会混入执行者、核验者与平台内部
 * （{@code FinalReportFidelityGate}）三方的命令。自审证据只应接受<b>执行者本人</b>跑出来的
 * 条目——否则 reviewer 的验证命令可以顶替「交付方确实跑过」的证据（`SL-5` 的来源过滤判据）。</p>
 *
 * <p><b>归属由调用侧显式给</b>：沙箱层不做角色推断（它不持有 agent 表，也看不到调用场景）；
 * 由调用方在 {@code execute(..., origin, round)} 里声明。缺省 {@link #EXECUTOR}——内部工具
 * 通道的常规路径就是执行者。</p>
 */
public enum SandboxCallOrigin {

    /** 执行者（{@code internal_run_in_sandbox} / {@code internal_run_skill}）。 */
    EXECUTOR("executor"),

    /** 核验者（reviewer 的验证命令；`SL-10` 只读挂载后接入）。 */
    REVIEWER("reviewer"),

    /** 平台内部（{@code FinalReportFidelityGate} 等机械判定）。 */
    PLATFORM("platform");

    private final String code;

    SandboxCallOrigin(String code) {
        this.code = code;
    }

    /** 落库 / 事件用的稳定小写码（避免枚举名进台账后改名即断口径）。 */
    public String code() {
        return code;
    }
}
