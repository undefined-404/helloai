package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.runtime.sandbox.SandboxSpec;
import com.helloai.core.agent.runtime.sandbox.SkillInstallResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 技能执行的编排门面（`SKX-8`，2026-10-26）：把「解析技能包 → 确保已注入 → 在技能根目录执行」三步
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

    private final OpenSandboxProperties properties;

    private final SkillPackageLoader skillPackageLoader;

    private final SandboxExecutionService sandboxExecutionService;

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
     * 在技能根目录执行命令。
     *
     * <p><b>两处命令包装都是必需的</b>：① {@code cd <skillRoot>}——技能 CLI 都以自身根目录为基准
     * （{@code node bin/archify.mjs}），不 cd 就得让模型自己拼绝对路径，多一个出错点；
     * ② {@code TMPDIR=/workspace/tmp}——archify 的临时目录身份校验在部分容器形态下敏感
     * （计划 §2.4 第 24 条：{@code docker run} 上必挂、真实沙箱不复现），这条是零成本的防御。</p>
     *
     * @param subTaskId      子任务 ID
     * @param target         解析后的技能目标
     * @param command        模型给的命令（相对技能根目录）
     * @param timeoutSeconds 超时秒数（null/&lt;=0 ⇒ 默认 30，上限 300）
     */
    public ExecuteResponse run(Long subTaskId, SkillTarget target, String command, Integer timeoutSeconds) {
        String wrapped = "mkdir -p /workspace/tmp >/dev/null 2>&1; export TMPDIR=/workspace/tmp; cd "
                + target.rootDir() + " && " + command;
        return sandboxExecutionService.execute(subTaskId, target.spec(), wrapped, timeoutSeconds);
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
