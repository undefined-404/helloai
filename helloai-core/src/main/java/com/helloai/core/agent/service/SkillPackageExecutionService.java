package com.helloai.core.agent.service;

/**
 * 技能包执行通道（2026-10-27）：把「注入技能包 → 在技能根目录跑 CLI → 回读产物 → 释放工作区」
 * 收敛成一个对外契约。
 *
 * <p><b>为什么需要这层接口</b>：需求对话要在<b>对话轮</b>里直接跑技能（用户选了 archify 就当场出图），
 * 而沙箱执行的具体实现住在 {@code agent.runtime.sandbox.impl}——planner 域直接引用它是跨域引用实现类
 * （CODE_STYLE 分层红线）。故在 {@code agent.service} 暴露接口，planner 只依赖契约。</p>
 *
 * <p><b>与 {@code internal_run_skill} 工具的关系</b>：两者共用同一套注入与执行编排
 * （{@code SkillExecutionSupport}），差别只在<b>触发者</b>：工具是模型在子任务里自己决定调，
 * 本接口是平台在对话轮里按用户显式选择调。共用实现保证「技能怎么装、命令怎么包」只有一份。</p>
 *
 * <p><b>作用域</b>：{@code scopeId} 是复用沙箱的槽键（生产上子任务用 {@code subTaskId}）。
 * 对话轮没有子任务，调用方传一个不与该子任务 ID 空间冲突的值（如会话 ID 取负），
 * 用完必须 {@link #release(Long)} 释放，否则容器会活到空闲超时。</p>
 */
public interface SkillPackageExecutionService {

    /**
     * 命令执行结果（结构化，不抛异常——与 {@code SandboxExecutionService} 同一条容错哲学）。
     *
     * @param ok       退出码为 0
     * @param exitCode 退出码（失败时可能为 {@code -1} 表示未执行）
     * @param output   合并后的 stdout / stderr（已按平台上限截断）
     * @param timedOut 是否超时
     */
    record RunResult(boolean ok, int exitCode, String output, boolean timedOut) {
    }

    /**
     * 回读的产物。
     *
     * @param bytes     字节内容
     * @param sha256    内容哈希（供对账）
     * @param truncated 是否因超过上限被截断（截断的产物<b>不可</b>当交付物，调用方应拒绝）
     */
    record Artifact(byte[] bytes, String sha256, boolean truncated) {
    }

    /**
     * 技能是否可用（沙箱启用 且 该包已入库）。
     *
     * @param skillName 技能包名
     */
    boolean isAvailable(String skillName);

    /**
     * 在技能根目录执行一条命令（内部会先幂等注入技能包）。
     *
     * @param skillName      技能包名
     * @param scopeId        复用工作区键（非 null）
     * @param command        命令（相对技能根目录语义；实现会自动 {@code cd} 并设 {@code TMPDIR}）
     * @param timeoutSeconds 超时秒数（null/&le;0 ⇒ 默认；实现侧有上限）
     */
    RunResult run(String skillName, Long scopeId, String command, Integer timeoutSeconds);

    /**
     * 按绝对路径回读产物（只读，不会为读文件新建沙箱）。
     *
     * @param scopeId  复用工作区键
     * @param path     容器内绝对路径（对话内执行形如 {@code /workspace/runs/<runId>/artifact.html}；
     *                 子任务侧由模型自选，通常 {@code /workspace/out/<name>.html}）
     * @param maxBytes 上限字节；超出时返回 {@code truncated=true}
     * @return 产物；工作区不存在 / 文件不可读 ⇒ {@code null}
     */
    Artifact readArtifact(Long scopeId, String path, int maxBytes);

    /**
     * 释放该作用域的全部工作区（幂等）。
     *
     * @param scopeId 复用工作区键
     */
    void release(Long scopeId);
}
