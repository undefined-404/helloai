package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.service.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 「提交产出」活动日志的独立事务写入器。
 *
 * <p><b>为什么必须独立事务</b>（2026-10-21 实测定位）：
 * {@code McpToolServiceImpl.submitResult} 带 {@code @Transactional}，整条上报链路共用一个事务；
 * 活动日志此前以 {@code REQUIRED} 传播加入该事务，一旦写入失败（如 JSONB 参数类型不匹配），
 * PostgreSQL 会把事务标记为 aborted —— 外层 {@code catch} 虽吞掉异常并打印
 * 「不影响提交」，但提交时仍抛 {@code UnexpectedRollbackException: Transaction rolled back
 * because it has been marked as rollback-only}，<b>结果提交整体回滚</b>。
 * 即「best-effort 旁路」在 {@code REQUIRED} 下是假象。</p>
 *
 * <p><b>语义</b>：活动日志是观测数据，不应与业务共命运。本类用
 * {@link Propagation#REQUIRES_NEW} 挂起外层事务、开新连接写入，写入失败只回滚自身，
 * 绝不影响已完成的业务事务。</p>
 *
 * <p><b>不使用范围</b>：需要「与业务同生共死」的日志（如注册流程内的审计）仍应走
 * {@link ActivityLogService#record} 的默认 {@code REQUIRED} 传播。</p>
 *
 * <p><b>为什么不直接复用 / 扩展已有抽象</b>（CODE_STYLE §2.3 举证）：</p>
 * <ol>
 *   <li>不改 {@link ActivityLogService#record} 的传播行为 —— 它在注册等流程中需要与业务同事务回滚，
 *       全局改 {@code REQUIRES_NEW} 会留下「业务已回滚、日志却说成功」的假记录；</li>
 *   <li>不采用 self-injection（{@code @Lazy} 注入自身代理）—— 循环依赖且难以单测；
 *       本类只有单一职责，新建成本低于自注入的长期维护成本；</li>
 *   <li>不把调用点挪到 Controller —— 会上移事务边界，违反「事务边界优先放在 Service」（§14.1）。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubmissionActivityRecorder {

    private final ActivityLogService activityLogService;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordInNewTx(Long agentId, String action, String level, String source,
                              Long subTaskId, Map<String, Object> detail) {
        activityLogService.record(agentId, action, level, source, subTaskId, detail);
    }
}
