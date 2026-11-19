package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.entity.SubTaskSkillRun;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxCallOrigin;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.runtime.sandbox.SandboxSpec;
import com.helloai.core.agent.runtime.sandbox.SkillInstallResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 技能执行的编排门面（`SKX-8`）：把「解析技能包 → 确保已注入 → 在技能根目录执行」三步
 * 收在一处，供进程内工具（{@code internal_run_skill}）与将来的其它入口共用。
 *
 * <p><b>为什么单独一层而不是写进工具方法</b>：工具类（{@code InternalLlmExecutorTools}）的职责是
 * 「对外形状与鉴权闸门」，不该管「哪个规格建沙箱 / 包在不在 / 命令怎么拼」。分开之后，
 * 可用性判定（{@link #skillAvailable()}）与执行编排共用同一份事实，不会各自漂移。</p>
 *
 * <p><b>规格来源</b>：{@code helloai.sandbox.opensandbox.skill-spec-preset}（默认 {@code node}）
 * 指向 {@code spec-presets.<name>}。技能执行必须用带目标运行时的镜像——`SKX-1` 定的是 node 镜像，
 * 而全局默认规格是 python；用错规格的症状是「命令报 {@code node: not found}」，看起来像技能包坏了。
 * 预设缺失时<b>打 warn 并回落全局默认</b>（不静默：日志里能看见），而不是直接失败——
 * 回落至少能让 python 类技能跑起来，且失败信息会指向真实原因。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillExecutionSupport {

    /** 默认技能规格名（与 {@code application.yml} 的 {@code spec-presets} 键一致）。 */
    public static final String DEFAULT_SKILL_SPEC_PRESET = "node";

    /** 台账里命令前缀的字符上限（与 {@code SandboxCallLedger} 同口径：完整命令可能含密钥）。 */
    static final int COMMAND_PREFIX_LIMIT = 200;

    private final OpenSandboxProperties properties;

    private final SkillPackageLoader skillPackageLoader;

    private final SandboxExecutionService sandboxExecutionService;

    private final SkillRunLedgerRecorder skillRunLedgerRecorder;

    /**
     * 技能执行的<b>身份四元组</b> + 沙箱作用域键（`MA-3`）。
     *
     * <p><b>为什么显式入参而不是读 {@code ToolInvocationContext} 的 ThreadLocal</b>：
     * 本类是编排门面，不该知道「工具调用上下文」这种东西；隐式依赖会让它在非工具场景
     * （业务面技能执行）无法复用，且 ThreadLocal 在线程切换时会静默失效——那时台账只会
     * 少记几行，谁也不会发现。</p>
     *
     * <p><b>为什么 {@code scopeId} 与 {@code subTaskId} 分开</b>：同一套执行编排有两个入口，
     * 两个入口的「沙箱作用域键」语义不同——内部执行链是 {@code subTaskId}（按子任务复用沙箱），
     * 业务面技能执行是<b>会话级</b> {@code scopeId}，它<b>不属于任何子任务</b>。
     * 若把两者塞进同一个字段，台账就会写出一批「子任务 ID = 会话 ID」的假行。</p>
     *
     * @param scopeId   沙箱复用槽键（内部执行链 = subTaskId）
     * @param subTaskId 子任务 ID；<b>{@code null} ⇒ 不落台账</b>（业务面技能执行，它的记录在
     *                  {@code chat_skill_run}）
     * @param taskId    所属任务 ID（可为 null）
     * @param agentId   执行者 Agent ID（可为 null）
     * @param round     执行轮次（无轮次语义传 0）
     */
    public record SkillRunContext(Long scopeId, Long subTaskId, Long taskId, Long agentId, int round) {

        /** 内部执行链（{@code internal_run_skill}）：作用域即子任务，落台账。 */
        public static SkillRunContext ofSubTask(Long subTaskId, Long taskId, Long agentId, int round) {
            return new SkillRunContext(subTaskId, subTaskId, taskId, agentId, Math.max(round, 0));
        }

        /**
         * 业务面技能执行（对话技能）：{@code scopeId} 是会话级工作区键，不是子任务。
         *
         * <p><b>不落本台账</b>：那张表是「子任务维度」，混进来会让「技能成功率」被两种
         * 触发者稀释。该路径的记录在 {@code chat_skill_run}（已有）。</p>
         */
        public static SkillRunContext ofConversationScope(Long scopeId) {
            return new SkillRunContext(scopeId, null, null, null, 0);
        }
    }

    /**
     * 解析后的技能执行目标。
     *
     * @param name    包名（= 容器内 {@code /workspace/skills/<name>}）
     * @param version 版本目录名
     * @param spec    建沙箱用的规格（{@code null} ⇒ 全局默认；预设缺失时回落）
     */
    public record SkillTarget(String name, String version, SandboxSpec spec) {

        /** 人读标识（日志 / 工具返回值用）。 */
        public String label() {
            return name + "@" + version;
        }

        /** 容器内技能根目录。 */
        public String rootDir() {
            return "/workspace/skills/" + name;
        }
    }

    /** 沙箱通道是否启用（产物回读等「不依赖具体技能包」的能力只看这一条）。 */
    public boolean sandboxEnabled() {
        return properties.isEnabled();
    }

    /**
     * 技能执行是否可用 = 沙箱启用 <b>且</b> 至少一个技能包已入库。
     *
     * <p>用于工具可见性（fail-closed）：没有包时把 {@code internal_run_skill} 从模型可见列表摘除，
     * 避免模型看到一个必然失败的入口却无从判断「是没包还是坏了」。</p>
     */
    public boolean skillAvailable() {
        return properties.isEnabled() && skillPackageLoader.hasBundledSkills();
    }

    /** 已入库技能包名（错误信息里列给模型看，比「未找到」三个字有用）。 */
    public List<String> availableSkillNames() {
        return skillPackageLoader.bundledSkills().stream()
                .map(SkillPackageLoader.BundledSkill::name)
                .distinct()
                .toList();
    }

    /**
     * 按名字解析技能（同时确定规格）。
     *
     * @param skillName 技能包名（严格匹配，不做大小写归一）
     * @return 解析结果；未入库 ⇒ {@code null}（由调用方决定措辞）
     */
    public SkillTarget resolve(String skillName) {
        SkillPackageLoader.BundledSkill bundled = skillPackageLoader.resolve(skillName);
        if (bundled == null) {
            return null;
        }
        return new SkillTarget(bundled.name(), bundled.version(), resolveSpec(bundled.name()));
    }

    /**
     * 确保技能包已在目标子任务的容器里（幂等：已装则 {@code skipped=true}，约 50ms）。
     *
     * @param subTaskId 子任务 ID（复用槽键的一部分）
     * @param target    解析后的技能目标
     * @return 注入结果（失败也返回，不抛异常）
     */
    public SkillInstallResult ensureInstalled(Long subTaskId, SkillTarget target) {
        return sandboxExecutionService.installSkillPackage(
                subTaskId, target.spec(), target.name(), target.version());
    }

    /**
     * 在技能根目录执行命令（**唯一入口**，`MA-3` 收口）。
     *
     * <p><b>两处命令包装都是必需的</b>：① {@code cd <skillRoot>}——技能 CLI 都以自身根目录为基准
     * （{@code node bin/archify.mjs}），不 cd 就得让模型自己拼绝对路径，多一个出错点；
     * ② {@code TMPDIR=/workspace/tmp}——archify 的临时目录身份校验在部分容器形态下敏感
     * （计划 §2.4 第 24 条：{@code docker run} 上必挂、真实沙箱不复现），这条是零成本的防御。</p>
     *
     * <p><b>★ 旧的两参/三参 {@code run(...)} 重载已删</b>：保留一个「不落台账」的入口，
     * 等于给台账开了条绕行道——判据 1「规则存在 ≠ 全路径强制」正是这么破的。
     * 现在所有调用方都必须交 {@link SkillRunContext}。</p>
     *
     * <p><b>台账是 best-effort</b>：写失败只 {@code log.warn}，返回值与异常语义<b>逐字不变</b>——
     * 「记录把执行弄坏了」比「记录不全」严重得多。</p>
     *
     * @param ctx            执行身份（作用域 / 子任务 / 任务 / 执行者 / 轮次）
     * @param target         解析后的技能目标
     * @param command        模型给的命令（相对技能根目录）
     * @param timeoutSeconds 超时秒数（null/&lt;=0 ⇒ 默认 30，上限 300）
     */
    public ExecuteResponse run(SkillRunContext ctx, SkillTarget target, String command,
                               Integer timeoutSeconds) {
        String wrapped = "mkdir -p /workspace/tmp >/dev/null 2>&1; export TMPDIR=/workspace/tmp; cd "
                + target.rootDir() + " && " + command;
        long startedNanos = System.nanoTime();
        ExecuteResponse response = null;
        try {
            response = sandboxExecutionService.execute(ctx.scopeId(), target.spec(), wrapped,
                    timeoutSeconds, SandboxCallOrigin.EXECUTOR, ctx.round());
            return response;
        } finally {
            // 成功 / 非零退出 / 超时 / SDK 异常 / 抛异常 —— 全部留痕：只记成功的话，
            // 失败率永远算不出来，而失败率才是「这个技能能不能用」的核心指标
            recordRun(ctx, target, command, startedNanos, response);
        }
    }

    /**
     * 落一行台账（`MA-3`，best-effort）。
     *
     * @param response 执行结果；{@code null} 表示 {@code execute} 直接抛了异常
     *                 ⇒ {@code exit_code} 记 {@link ExecuteResponse#EXIT_CODE_ERROR}（-1）
     */
    private void recordRun(SkillRunContext ctx, SkillTarget target, String command,
                           long startedNanos, ExecuteResponse response) {
        if (ctx == null || ctx.subTaskId() == null) {
            return;   // 业务面技能执行（不属于任何子任务）⇒ 不写子任务维度台账
        }
        try {
            SubTaskSkillRun row = new SubTaskSkillRun();
            row.setSubTaskId(ctx.subTaskId());
            row.setTaskId(ctx.taskId());
            row.setAgentId(ctx.agentId());
            row.setRound(ctx.round());
            row.setSkillName(target.name());
            row.setSkillVersion(target.version());
            row.setCommandPrefix(prefix(command));
            row.setExitCode(response != null ? response.exitCode() : ExecuteResponse.EXIT_CODE_ERROR);
            row.setTruncated(response != null && response.truncated() ? 1 : 0);
            row.setDurationMs((System.nanoTime() - startedNanos) / 1_000_000L);
            row.setCallId(response != null ? response.callId() : null);
            skillRunLedgerRecorder.record(row);
        } catch (Exception e) {
            log.warn("技能执行台账写入失败（不影响执行结果）: subTaskId={}, skill={}, err={}",
                    ctx.subTaskId(), target.label(), e.getMessage());
        }
    }

    /** 命令前缀（≤ {@link #COMMAND_PREFIX_LIMIT} 字符）：完整命令可能含密钥，不整段落库。 */
    private static String prefix(String command) {
        if (command == null) {
            return null;
        }
        return command.length() <= COMMAND_PREFIX_LIMIT
                ? command : command.substring(0, COMMAND_PREFIX_LIMIT);
    }

    /**
     * 取技能规格：命名预设优先，缺失则 warn + 回落全局默认。
     *
     * @param skillName 技能名（仅用于日志定位）
     */
    private SandboxSpec resolveSpec(String skillName) {
        String presetName = properties.getSkillSpecPreset();
        String effective = presetName == null || presetName.isBlank()
                ? DEFAULT_SKILL_SPEC_PRESET : presetName.trim();
        OpenSandboxProperties.Spec preset = properties.findSpecPreset(effective);
        if (preset == null) {
            log.warn("SkillExecutionSupport: 未找到技能规格预设 '{}'（技能 {}），回落全局默认规格——"
                            + "若全局默认镜像不含目标运行时，命令会以「command not found」失败",
                    effective, skillName);
            return null;
        }
        return SandboxSpecMapper.toDomain(preset);
    }
}
