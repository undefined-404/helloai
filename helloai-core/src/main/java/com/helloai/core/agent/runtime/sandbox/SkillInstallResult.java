package com.helloai.core.agent.runtime.sandbox;

/**
 * 技能包注入结果（`SKX-6`，2026-10-26）。
 *
 * <p><b>为什么需要独立结果类型</b>：注入不是「一条命令」，{@link ExecuteResponse} 的三个字段
 * （output / exitCode / truncated）描述不了「装了几个文件、多大、装到哪、是不是幂等跳过」。
 * 上层（`SKX-8` 生效开关、`SKX-9` 串联）要依据这些值判断「能不能开始跑技能」，以及
 * 「这次是首次安装还是命中幂等标记」——后者决定了日志里该不该报耗时。</p>
 *
 * <p><b>失败也是返回而非抛出</b>：与 {@link ExecuteResponse} 同一条容错哲学——注入失败时
 * 调用方应当<b>降级到「没有技能可用」</b>（回退普通执行路径），而不是让整条执行链崩掉。</p>
 *
 * @param success   是否可用（true = 目标目录已是该版本的完整副本）
 * @param fileCount 载荷文件数（不含 {@code MANIFEST.json} 与幂等标记；失败时为 0）
 * @param totalBytes 载荷总字节（同上；失败时为 0）
 * @param targetDir 目标目录（失败时为 {@code null}）
 * @param skipped   是否因「已存在同版本同内容」而<b>跳过实际写入</b>（幂等命中）
 * @param message   人读说明（成功时记耗时与校验结果，失败时记原因；**不得**含平台凭据）
 */
public record SkillInstallResult(boolean success, int fileCount, long totalBytes, String targetDir,
                                 boolean skipped, String message) {

    /** 成功结果。 */
    public static SkillInstallResult ok(int fileCount, long totalBytes, String targetDir,
                                        boolean skipped, String message) {
        return new SkillInstallResult(true, fileCount, totalBytes, targetDir, skipped, message);
    }

    /** 失败结果（计数与目标目录留空，避免调用方误读半成品状态）。 */
    public static SkillInstallResult fail(String message) {
        return new SkillInstallResult(false, 0, 0L, null, false, message);
    }
}
