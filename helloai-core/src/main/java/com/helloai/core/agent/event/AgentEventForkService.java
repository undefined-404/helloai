package com.helloai.core.agent.event;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.entity.AgentEvent;
import com.helloai.core.agent.mapper.AgentEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Run 事件快照 Fork 服务（ADR-001 §2 Fork 能力，Claude Code/Codex 同款最朴素形态）。
 *
 * <p><b>状态：未接线的内部能力（2026-10-09 起）</b>——本类是当前唯一无生产调用方的平台服务。
 * 「触发入口 / 原 Run 冻结 / 驱动新 Run 执行」三项已由用户裁定 <b>WONTFIX</b>
 * （{@code D-2026-10-09-5}，见《HelloAI 实现差距表》§7.1.2）：其用法已被 Return（REWORK→驳回→
 * 改派→重开工）与 Replay 工作台覆盖，且驱动执行须改 ADR-001 的 Run 标识模型与
 * execution command 载荷。本类保留不删、不接线；<b>新增调用方前须先回到该裁定</b>。</p>
 *
 * <p>把某 Run 的 {@code agent_event} 全部事件复制到新 {@code run_id}，生成只读快照。
 * 新 {@code run_id} = {@code run-{taskId}-1-fork-{seq}}（seq = 该 task 已有 fork 数 + 1）。</p>
 *
 * <p><b>关键约束</b>：
 * <ul>
 *   <li>每条事件生成新雪花 {@code id}（不 set，由 {@code @TableId(ASSIGN_ID)} 在 insert 时填充）
 *       + 新 {@code event_id}（UUID，UNIQUE 约束）+ 新 {@code run_id}；
 *       其余字段（task_id / sub_task_id / turn / step / event_type / agent_id / payload / create_time）
 *       原样保留，<b>不修改读出的原事件对象</b>（new 新对象复制字段）；</li>
 *   <li><b>不复制 {@code agent_outbox_event}</b>——fork 是只读快照，不重新投递 MQ
 *       （否则消费者会重复处理）。</li>
 * </ul></p>
 *
 * <p><b>本轮边界（Claude Code/Codex 同款最朴素形态）</b>：
 * <ul>
 *   <li>仅快照复制，<b>不暴露 API</b>（Service + 单测；REST 端点已于 2026-10-09 裁定不再接线）；</li>
 *   <li><b>原 Run 不冻结</b>——task 执行链仍派生原 {@code run-{taskId}-1}，原 Run 可继续写；
 *       冻结需在 {@code agent_event} 加 {@code frozen} 列 + Recorder 写入前校验，该方案不实施；</li>
 *   <li><b>不驱动新 Run 执行</b>——「fork 后让新 Run 跑起来」需 run_id 上下文接线
 *       （{@code sub_task} / execution command 带 fork run_id），属 Agent Governance，
 *       该接线不实施。</li>
 * </ul></p>
 *
 * <p><b>对称性（历史口径）</b>：Resume 先做成 Prompt 级（Step 级续跑后置）；
 * Fork 先做成快照复制（驱动执行后置）。Resume 侧已推进；<b>Fork 侧的「做好」已于 2026-10-09 裁定不做</b>。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventForkService {

    private final AgentEventMapper agentEventMapper;

    /**
     * Fork 指定 task 的当前 Run 事件快照，返回新 run_id。
     *
     * <p>读取 {@code run-{taskId}-1} 的全部事件（按写入时序），逐条复制为新对象
     * （新 id / 新 event_id / 新 run_id，其余字段原样），循环 insert 回 {@code agent_event}。</p>
     *
     * @param taskId 主任务 ID（不可空）
     * @return 新 run_id（{@code run-{taskId}-1-fork-{seq}}）
     * @throws BizException          原 Run 无事件可 fork
     * @throws IllegalArgumentException taskId 为 null
     */
    @Transactional(rollbackFor = Exception.class)
    public String forkRun(Long taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId must not be null");
        }
        String originRunId = AgentEventContextResolver.resolveRunId(taskId);
        List<AgentEvent> origin = agentEventMapper.selectByRunIdOrdered(originRunId);
        if (origin == null || origin.isEmpty()) {
            throw new BizException("无事件可 fork: runId=" + originRunId);
        }
        String newRunId = resolveForkRunId(taskId, originRunId);

        for (AgentEvent src : origin) {
            AgentEvent copy = new AgentEvent();
            // 新 id 由 @TableId(ASSIGN_ID) 在 insert 时填充（不 set）
            copy.setEventId(UUID.randomUUID().toString().replace("-", ""));
            copy.setRunId(newRunId);
            copy.setTaskId(src.getTaskId());
            copy.setSubTaskId(src.getSubTaskId());
            copy.setTurn(src.getTurn());
            copy.setStep(src.getStep());
            copy.setEventType(src.getEventType());
            copy.setAgentId(src.getAgentId());
            // payload 防御性拷贝（fork 是只读快照，不应共享可变引用）
            Map<String, Object> srcPayload = src.getPayload();
            copy.setPayload(srcPayload == null ? new HashMap<>() : new HashMap<>(srcPayload));
            copy.setRemark("forked from " + originRunId);
            // create_time / create_by 等审计列由 BaseEntity 默认值/自动填充处理
            agentEventMapper.insert(copy);
        }
        log.info("Run forked: {} -> {} (events={})", originRunId, newRunId, origin.size());
        return newRunId;
    }

    /**
     * 解析新 fork run_id：{@code run-{taskId}-1-fork-{seq}}，seq = 已有 fork 数 + 1。
     *
     * <p>查 {@code agent_event} 中该 task 已有的 {@code run-{taskId}-1-fork-{seq}} 最大 seq；
     * 无任何 fork 时 seq=1。</p>
     */
    private String resolveForkRunId(Long taskId, String originRunId) {
        String prefix = originRunId + "-fork-";
        int maxSeq = agentEventMapper.selectMaxForkSeq(taskId, prefix);
        return prefix + (maxSeq + 1);
    }
}
