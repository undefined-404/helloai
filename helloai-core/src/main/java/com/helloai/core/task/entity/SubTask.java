package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.SubTaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
// autoResultMap：让 @TableField(typeHandler) 参与 SELECT 结果映射。uncertainties 强类型读取依赖
// （全局 List→JacksonTypeHandler 注册会把 JSON 数组读成 List<LinkedHashMap>，见该字段 Javadoc）
@TableName(value = "sub_task", autoResultMap = true)
public class SubTask extends BaseEntity {

    private Long taskId;
    private Long moduleId;
    private String title;
    private String deliverable;
    private String acceptance;
    private String priority;

    @TableField("status")
    private SubTaskStatus status;

    private Long assignedAgentId;
    private String content;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> context;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> scoreFactors;

    private Integer compositeScore;
    private String scoreGrade;
    private OffsetDateTime deadline;
    private Integer reworkCount;
    private OffsetDateTime completeTime;

    @Version
    private Integer version;

    /**
     * 当前执行 Worker 节点标识（Phase 0 A2.2）。
     *
     * <p>子任务进入 IN_PROGRESS 时由 {@code changeStatus} 写入（与
     * {@code agent_execution_record.worker_node} 同源，取自 {@code HostNameUtils.getHostName()}），
     * 租约到期被 {@code LeaseReconcilerTask} 回收时清空。
     * NULL 表示未持有租约（存量数据 / 非执行态）。</p>
     */
    private String owner;

    /**
     * 执行租约到期时间（Phase 0 A2.2）。
     *
     * <p>进入 IN_PROGRESS 时写入 {@code now + watchdog.ttl-seconds}，由
     * {@code WatchdogLeaseRenewTask} 周期续期；过期未续视为 Worker 崩溃，由
     * {@code LeaseReconcilerTask} 回收为 PENDING 重派。回收时清空，正常流转完成不清空（保留审计）。</p>
     */
    private OffsetDateTime leaseUntil;

    private Integer timeoutCount;

    /**
     * 契约定义子任务标记（契约先行拆解模式，V56）：1=契约定义子任务，
     * 完成（DONE）后其产出回流 {@code task_running_spec.contract}，
     * 全局注入所有下游子任务执行 Prompt；0=普通子任务。
     *
     * <p>SMALLINT 列按规范 §9.3 用 Integer 映射（与 deleted 同款），
     * 由 PlannerDecomposeAsyncServiceImpl 解析 {@code "contract": true} 落库。</p>
     */
    private Integer isContract;

    /**
     * N11 阈值回退：当前子任务已发生的"外部→LLM"回退次数。
     *
     * <p>每次 ExternalAgentFallbackTask 触发对当前子任务的重新分发，
     * 都会把该值 +1；可用于监控 / 限流（如回退 3 次后直接放弃或转人工）。</p>
     */
    private Integer externalFallbackCount;

    /**
     * 重分配尝试次数：所有类型的重分配（离线重派、超时回收、
     * N11回退、阻塞重试）都计数。
     *
     * <p>达到 {@code helloai.dispatch.max-reassign-attempts}（默认 5）后，
     * 子任务将被直接标记为 CANCELLED，不再进入重分配链，防止无限重试死循环。</p>
     *
     * <p><b>Phase 0 A3 起不再由业务读写</b>：重分配熔断已切换读取 {@link #attemptTotal}，
     * 本字段保留仅供存量数据审计（V64 已一次性搬迁到 attempt_total）。</p>
     */
    private Integer reassignAttemptCount;

    /**
     * 全局共享重试计数器（Phase 0 A3，坑点 3「单一权威」）。
     *
     * <p>同一子任务所有重试（重分配 / 自动驳回返工，LOG-20260904-007 返工已并入）共享一个预算：
     * 任何一层重试前用 {@code RetryPolicy.exceedsMax(attemptTotal, max)} 判定达上限即停，
     * 杜绝多层独立计数叠加导致实际执行次数失控。上限复用
     * {@code helloai.dispatch.max-reassign-attempts}（默认 5，不新建配置）。
     * 人工裁定开启新一轮（人工驳回 reworkFresh / 死信重派 redispatchDeadLetter）时清零重计。</p>
     */
    private Integer attemptTotal;

    /**
     * 依赖的子任务 id 数组：同 Task 内的前置子任务，
     * 全部 DONE 后本任务才可被分发（ready 语义）；空数组=无依赖。
     *
     * <p>注意：JacksonTypeHandler 反序列化 JSON 数组数字默认是 Integer，
     * 读取依赖 id 时必须走 {@link #dependsOnIdList()} 做 Long 归一化，
     * 不要直接遍历本字段强转 Long。</p>
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> dependsOn;

    /**
     * 子任务级技能标签（Planner 能力感知 G-010）。
     *
     * <p>拆解侧 LLM 按注入的技能目录指派，落库前经目录命中过滤；装箱时
     * 与 task.required_skills 取并集（子任务级在前，去重保序），保持
     * 任务级全局约束语义不变。空数组=无子任务级指派，行为与现状一致。</p>
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> requiredSkills;

    /**
     * 执行约束（不许改的事，Planner 能力感知 G-010 COARSE 粒度必填）。
     *
     * <p>COARSE 粒度拆解的一等公民字段：用户可在草案确认时编辑，
     * 审查侧可核验约束是否被遵守；FINE/STANDARD 档为 NULL。</p>
     */
    private String constraints;

    /**
     * 不确定性申报（需求准入与不确定性显式管理 G-011，V75 显式 JSONB 列）。
     *
     * <p>拆解侧指派，kind 二值分级：ASSUMPTION 已申报假设（执行者可自行验证 / 推翻，
     * 审查不因假设存在而驳回）/ UNCONFIRMED 待确认缺口（执行者须先验证再动手，
     * 验证不了走既有 BLOCKED 链上报）。空数组（默认）=无申报，执行/审查侧零注入，
     * 存量数据行为与现状完全一致。</p>
     *
     * <p>读取必须走 {@link UncertaintyListTypeHandler}（而非内置 JacksonTypeHandler）：
     * MyBatisPlusConfig 全局把 {@code List.class} 注册为 JacksonTypeHandler，自动映射
     * 按擦除后的 List 读取会把 JSON 数组反序列化为 {@code List<LinkedHashMap>}，
     * 强类型消费点（sendInboxNotification 统计 UNCONFIRMED 等）ClassCastException，
     * 导致派单 inbox 通知失败、调度链死锁（2026-09-10 排查修复）。</p>
     */
    @TableField(typeHandler = UncertaintyListTypeHandler.class)
    private List<Uncertainty> uncertainties;

    /**
     * 依赖 id 归一化读取：把 Jackson 反序列化出的 Integer/Long/String 统一转为 Long。
     * 永不返回 null（空依赖返回空列表）。
     *
     * <p>String 分支的来源：全局 ObjectMapper 注册了 Long→String 序列化
     * （JacksonConfig，防前端精度丢失），历史数据的 depends_on 可能存成
     * 字符串数组，必须兼容读取，否则 ready 守卫会把有依赖节点误判为就绪。</p>
     */
    public List<Long> dependsOnIdList() {
        if (dependsOn == null || dependsOn.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = new ArrayList<>(dependsOn.size());
        for (Object item : (List<?>) (List<?>) dependsOn) {
            if (item instanceof Number) {
                ids.add(((Number) item).longValue());
            } else if (item instanceof String str && !str.isBlank()) {
                ids.add(Long.parseLong(str.trim()));
            }
        }
        return ids;
    }
}
