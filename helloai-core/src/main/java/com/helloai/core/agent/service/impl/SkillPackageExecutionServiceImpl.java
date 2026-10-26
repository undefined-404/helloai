package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxArtifact;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.runtime.sandbox.impl.SkillExecutionSupport;
import com.helloai.core.agent.service.SkillPackageExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link SkillPackageExecutionService} 实现（2026-10-27）：把 {@code SkillExecutionSupport} 的
 * 「解析包 → 幂等注入 → 在技能根跑命令」与 {@code SandboxExecutionService} 的「回读 / 释放」
 * 拼成一个对 planner 域可用的契约。
 *
 * <p><b>本类不做业务判定</b>：不解释命令输出、不判断产物是否合格、不决定要不要重试——
 * 那些是调用方（对话技能执行器）的职责。这里只回答「跑成没跑成」「字节拿没拿到」。</p>
 *
 * <p><b>注入失败不抛异常而是转成失败结果</b>：与沙箱通道一致，让调用方用同一套分支处理
 * 「包没装进去」「命令报错」两类失败，而不是一个 try/catch 一个判返回值。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillPackageExecutionServiceImpl implements SkillPackageExecutionService {

    /** 默认命令超时（技能里最慢的是首次渲染，冷启动实测约 15s）。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private final SkillExecutionSupport skillExecutionSupport;

    private final SandboxExecutionService sandboxExecutionService;

    @Override
    public boolean isAvailable(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        return skillExecutionSupport.skillAvailable()
                && skillExecutionSupport.resolve(skillName) != null;
    }

    @Override
    public RunResult run(String skillName, Long scopeId, String command, Integer timeoutSeconds) {
        if (scopeId == null) {
            return new RunResult(false, ExecuteResponse.EXIT_CODE_ERROR,
                    "缺少工作区作用域（scopeId 为空）", false);
        }
        if (command == null || command.isBlank()) {
            return new RunResult(false, ExecuteResponse.EXIT_CODE_ERROR, "命令为空", false);
        }
        SkillExecutionSupport.SkillTarget target = skillExecutionSupport.resolve(skillName);
        if (target == null) {
            return new RunResult(false, ExecuteResponse.EXIT_CODE_ERROR,
                    "技能包未入库: " + skillName, false);
        }
        // 幂等：已装则 skipped（约 50ms），未装则批量写入（首次数百毫秒）
        var install = skillExecutionSupport.ensureInstalled(scopeId, target);
        if (install == null || !install.success()) {
            String reason = install == null ? "未知原因" : install.message();
            log.warn("技能包注入失败: skill={}, scope={}, reason={}", skillName, scopeId, reason);
            return new RunResult(false, ExecuteResponse.EXIT_CODE_ERROR, "技能包注入失败：" + reason, false);
        }
        ExecuteResponse response = skillExecutionSupport.run(scopeId, target, command,
                timeoutSeconds == null || timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds);
        boolean timedOut = response.exitCode() == ExecuteResponse.EXIT_CODE_TIMEOUT;
        return new RunResult(response.exitCode() == 0, response.exitCode(),
                response.output(), timedOut);
    }

    @Override
    public Artifact readArtifact(Long scopeId, String path, int maxBytes) {
        if (scopeId == null || path == null || !path.startsWith("/")) {
            return null;
        }
        SandboxArtifact artifact = sandboxExecutionService.readArtifact(scopeId, path, maxBytes);
        if (artifact == null) {
            return null;
        }
        return new Artifact(artifact.bytes(), artifact.sha256(), artifact.truncated());
    }

    @Override
    public void release(Long scopeId) {
        if (scopeId == null) {
            return;
        }
        sandboxExecutionService.release(scopeId);
    }
}
