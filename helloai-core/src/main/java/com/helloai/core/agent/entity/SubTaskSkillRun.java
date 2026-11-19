package com.helloai.core.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 子任务维度的技能执行台账行（`MA-3`）：<b>每一次</b>技能执行（成功 / 非零退出 / 超时 /
 * SDK 异常）留一行。
 *
 * <p><b>为什么新建表而不是给 {@code chat_skill_run} 加列</b>：那张表在 planner 澄清域，
 * 语义是「用户在对话里点了一个技能」；往上加 {@code sub_task_id} 就是「一列两义」——
 * 同一列在不同行代表两种不同的事实。新表的成本只是「一条迁移 + entity + mapper」。</p>
 *
 * <p><b>为什么不复用 {@code SandboxCallLedger}</b>：那是进程内内存台账（重启即清空），
 * 服务的是「本子任务内的引用证据」；本表是「跨会话的统计事实」。两者并存、互不替代。</p>
 *
 * <p><b>★ 不记完整命令</b>：只落前 {@code 200} 字符前缀（与 {@code SandboxCallLedger} 同口径），
 * 完整命令可能含密钥 / 令牌。也因此 {@link #commandPrefix} <b>不是命令指纹</b>——
 * 长命令的差异可能落在截断之后，判重必须用 {@code exit_code + skill_name + round} 组合。</p>
 *
 * <p><b>为什么 {@link #taskId} 是冗余列</b>：从 {@code sub_task_id} 可 join 得到。刻意的反范式
 * 取舍（报表按任务聚合时省一次大表 join），代价是两处可能不一致——写入时从同一份事实取，
 * 不做二次查询。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sub_task_skill_run")
public class SubTaskSkillRun extends BaseEntity {

    /** 所属子任务 ID（唯一必需的身份维度；本表存在的理由就是能按它 join）。 */
    private Long subTaskId;

    /** 所属任务 ID（冗余列，见类注释；报表按任务聚合用）。 */
    private Long taskId;

    /** 执行者 Agent ID（`MA-3` 之前这个字段在调用链上被丢弃，见 `ToolInvocationContext.Ids`）。 */
    private Long agentId;

    /** 执行轮次（与 {@code SandboxCallLedger.round} 同源；无轮次语义的调用方传 0）。 */
    private Integer round;

    /** 技能包名（容器内 {@code /workspace/skills/<name>}）。 */
    private String skillName;

    /** 技能包版本目录名。 */
    private String skillVersion;

    /** 命令前 200 字符（<b>不是指纹</b>，见类注释）。 */
    private String commandPrefix;

    /** 真实退出码；{@code 124} = 超时，{@code -1} = SDK 异常（结构化失败，不是命令退出码）。 */
    private Integer exitCode;

    /** 输出是否被截断（0/1）：{@code 1} 时「成功但输出不完整」，统计必须能把它与完整成功分开。 */
    private Integer truncated;

    /** 端到端耗时（毫秒，含建容器 / 注入 / 执行）。 */
    private Long durationMs;

    /** 沙箱调用台账条目 ID（与 {@code SandboxCallLedger} 互查用；未登记时为空）。 */
    private String callId;
}
