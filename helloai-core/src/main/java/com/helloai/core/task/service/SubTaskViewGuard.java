package com.helloai.core.task.service;

import com.helloai.common.base.BizException;
import com.helloai.core.task.policy.AttachmentVisibilityPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 子任务**视图**读权限的收口（时间线 / 对话流 / 详情）—— 「agent 能否看这个子任务」的唯一入口。
 *
 * <p><b>为什么要有这个类（而不是 Controller 直接判）</b>：判据必须单源。本类**不做任何判定**，
 * 一律委托 {@link AttachmentVisibilityPolicy#canReadTaskScoped}（与附件读侧同一条「根任务 Task-Team 成员」
 * 判据），自己只做「判定 → 403 异常」的收口 —— 与 {@code AttachmentService.assertReadable} 同型
 * （那里的注释写得很明白：「任何在本方法内"顺手补一条 if"的写法都会造成判据分散，属禁止项」）。</p>
 *
 * <p><b>为什么不放在 policy 包</b>：Controller 侧约定「不直接 import {@code task.policy}」
 * （判据类属 core 内部实现），故收口方法落在 service 包，供 api 层依赖。</p>
 *
 * <p><b>统一背景（2026-10-10 实测）</b>：此前三个视图端点两套判据且都有问题 ——</p>
 * <ul>
 *   <li>{@code listTimelineBySubTaskId} / {@code listConversationBySubTaskId}：只认
 *       {@code sub_task.assigned_agent_id}，实测**把合法团队成员也拒了**（同一 Agent 加入 team 后
 *       读附件 200、读时间线仍 403）；</li>
 *   <li>{@code getById}：**无任何校验**（任何有效 Agent API Key 可读任意子任务详情）。</li>
 * </ul>
 * <p>三者现同批收敛到本判据；平台账号 / 无主体通道照旧由管理侧鉴权覆盖（通道判定属 HTTP 关注点，
 * 留在 Controller）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubTaskViewGuard {

    private final AttachmentVisibilityPolicy visibilityPolicy;

    /**
     * 校验该 agent 可读该子任务的视图；不可读抛 {@link BizException}(403)。
     *
     * <p><b>调用方约定</b>：仅 agent 通道需要调用（{@code agentId} 为 {@code null} 时恒拒 ——
     * 平台账号通道不得走进来，否则会把"平台读取"误判成越权）。</p>
     */
    public void assertAgentCanView(Long subTaskId, Long agentId) {
        if (!visibilityPolicy.canReadTaskScoped(agentId, subTaskId)) {
            throw new BizException(403, "无权访问该子任务（不在该任务的团队成员范围内）");
        }
    }
}
