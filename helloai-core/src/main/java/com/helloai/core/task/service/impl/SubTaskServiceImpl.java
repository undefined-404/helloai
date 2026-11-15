package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.constant.UncertaintyKind;
import com.helloai.common.util.DraftGateChecker;
import com.helloai.common.util.RuntimeContractGate;
import com.helloai.core.agent.port.SubTaskClaimConstraint;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Uncertainty;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.port.SubTaskDraft;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.projection.SubTaskProjection;
import com.helloai.core.task.service.RuntimeContractGateService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.SubTaskLeaseService;
import com.helloai.core.task.service.SubTaskNotificationService;
import com.helloai.core.task.service.SubTaskQueryService;
import com.helloai.core.task.service.SubTaskStateMachineService;
import com.helloai.core.task.service.TaskDerivationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 子任务领域服务<b>薄门面</b>（RM9-b：原 1592 行上帝类 → 门面 + 4 职责类）。
 *
 * <p><b>本类保留类名与 {@code implements SubTaskService} 不变</b> ⇒ 外部调用方与 Spring 注入点
 * <b>零改动</b>，本笔可<b>单笔 revert</b>。类内只剩两类内容：</p>
 * <ol>
 *     <li><b>领域写入原语</b>（{@code updateDraft} / {@code create} / {@code updateDependsOn} /
 *         {@code createBatch} / {@code confirmDrafts} / {@code updateContext}
 *         / {@code saveDrafts} / {@code unlinkByAssignedAgent} / {@code physicalDeleteByTaskId}）—— 均为
 *         「单实体单次原子写 + 无状态迁移语义」，属 §4.3.2 门面行所定的「MyBatis-Plus 基类能力 +
 *         草稿/技能等配置面（低频写）」；例外是 {@code createBatch} / {@code confirmDrafts}
 *         两个<b>批</b>原语，其「全成或全败」由方法自身的事务保证（跨域编排不得逐条调用
 *         代替它们，否则失败会留下不可恢复的半状态，见 {@code confirmDrafts} 注释）；</li>
 *     <li><b>逐方法委托</b>给 4 个职责类（零业务分支）。</li>
 * </ol>
 *
 * <p><b>拆分后的类结构（4 职责类，方向为有向无环图）</b>：</p>
 * <pre>
 * SubTaskServiceImpl（门面：CRUD 原语 + 委托）
 *   ├── SubTaskQueryService       只读查询与视图投影（叶子）
 *   ├── SubTaskNotificationService 出站副作用（inbox / 摘要 / 领域事件） → Query
 *   ├── SubTaskStateMachineService 状态迁移唯一入口（含 claimAtomic / 租约回收状态半段） → Notification
 *   └── SubTaskLeaseService        租约 / 回收 / 巡检 → StateMachine（仅回收状态半段）
 * </pre>
 *
 * <p><b>对 「§7.8 选项二：书面声明不继续拆分」的正面回应</b>（历史声明<b>保留</b>如下，
 * 其顾虑已被逐条消解，而非被无视）：</p>
 * <ul>
 *     <li>已剥离：状态机校验（{@code SubTaskStateMachine}）、核验编排（{@code SubTaskReviewServiceImpl}）、
 *         执行编排（{@code SubTaskExecutionServiceImpl}）、评分计算（{@code ImplicitScoreCalculator}）、
 *         结果回写（{@code ExecutionResultHandler}）；</li>
 *     <li>剩余职责：子任务 CRUD / 依赖 / 状态流转（{@code changeStatus} 及
 *         claim/start/submit/complete/pause/resume/block/rework 收敛入口）+ 事件发布
 *         （outbox / 收件箱 / 交接）+ 评分联动 + 心跳 / 并发配额 + 跨域统计与级联删除收口；</li>
 *     <li>不拆理由：状态流转各入口共享同一状态机与事件发布链，拆分会造成事件序与事务边界在类间漂移；
 *         已外置的均为无状态工具或独立引擎，剩余部分无独立可测职责可继续剥离。</li>
 * </ul>
 * <p><b>顾虑①「事件序与事务边界在类间漂移」</b>：本笔把<b>整条状态流转链原样整体搬迁</b>到
 * {@link SubTaskStateMachineService}（{@code changeStatus} 的
 * 「读现状 → 校验 → 写上下文/租约 → 落库 → outbox → 收件箱 → 交接 → 领域事件 → 心跳」顺序与事务边界
 * 一字未改）⇒ 事件序与事务边界<b>仍在同一个类内</b>，漂移风险未被引入；门面只在外部入口
 * 保留 {@code @Transactional} 以维持原事务开启位置。</p>
 * <p><b>顾虑②「剩余部分无独立可测职责」</b>：实测该判断不再成立 —— 被剥离的三簇<b>各自已有独立单测</b>
 * （{@code SubTaskServiceIsReadyTest} / {@code SubTaskServiceMergeSkillsTest} /
 * {@code SubTaskServiceLeaseReclaimTest} / {@code SubTaskServiceQuotaTest} /
 * {@code SubTaskServiceReworkBudgetTest} / {@code SubTaskServiceHandoverTest}），
 * 即它们本就具备「独立可测职责」，只是此前与状态机同处一文件。</p>
 * <p><b>顾虑③「按行数机械拆」</b>：本笔<b>不按方法数量</b>拆，按 §9.1 业务职责切分（只读查询 /
 * 出站副作用 / 租约调度 / 状态迁移），故不落 §9.2 所禁的
 * {@code FooHelper/FooManager} 互相转发形态（4 类互不转发、方向无环）。</p>
 *
 * <p><b>写入纪律（拆分未放松）</b>：{@code sub_task.status} 的全部迁移仍只经
 * {@link SubTaskStateMachineService}（§4.3.2 验收口径 ii）；本类自己的写操作<b>均不改 status</b>，
 * 唯一例外是 {@link #create(SubTask, Long)} 在<b>插入新行</b>时落初始态 {@code PENDING}（插入非迁移）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskServiceImpl extends ServiceImpl<SubTaskMapper, SubTask>
        implements SubTaskService {

    /** 同域 Mapper 直捅（CODE_STYLE §7.1：同域合规）；显式持有以便手动装配场景可测（见原类注释）。 */
    private final SubTaskMapper subTaskMapper;
    private final SubTaskQueryService queryService;
    private final SubTaskStateMachineService stateMachineService;
    private final SubTaskLeaseService leaseService;
    private final TaskDerivationService taskDerivationService;
    private final RuntimeContractGateService runtimeContractGateService;

    /** 显式枚举值解析：非法值返回 null（交给 {@link AcceptanceKind#parse} 做既有回落）。 */
    private static AcceptanceKind parseExplicitKind(String raw) {
        try {
            return AcceptanceKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 记录「显式声明的 kind 原文」到 {@code context}（见 {@code SubTaskContextKeys.ACCEPTANCE_KIND_DECLARED}）。
     *
     * <p>为什么落库：{@code acceptance_kind} 列经 {@code parse} 后无法区分「显式声明 AUTOMATED」
     * 与「未声明」，而门禁对两者的处置不同（前者写法不符是 block，后者只是 warn）。</p>
     *
     * @param raw 合法枚举名；{@code null} = 本次声明为「未声明」以外的非法值，保留原值不动
     */
    private static void putDeclaredKind(SubTask subTask, String raw) {
        if (raw == null) {
            return;
        }
        Map<String, Object> context = subTask.getContext() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(subTask.getContext());
        context.put(SubTaskContextKeys.ACCEPTANCE_KIND_DECLARED, raw);
        subTask.setContext(context);
    }


    // ══════════════════════════════════════════════════════════════
    //  执行期重构（ADR-008 §11~§12）：入口 / 编辑 / 回链
    // ══════════════════════════════════════════════════════════════

    /** 允许转入人工重构的入口状态：共同语义是「自动链路已停，必须人看一眼」。 */
    private static final Set<SubTaskStatus> RESTRUCTURE_ENTRY_STATUSES = Set.of(
            SubTaskStatus.BLOCKED, SubTaskStatus.DEAD_LETTER, SubTaskStatus.REWORK, SubTaskStatus.PAUSED);

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enterRestructuring(Long subTaskId, String reason) {
        SubTask subTask = subTaskMapper.selectById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (!RESTRUCTURE_ENTRY_STATUSES.contains(subTask.getStatus())) {
            throw new BizException("仅 BLOCKED / DEAD_LETTER / REWORK / PAUSED 可转入重构，当前状态: "
                    + subTask.getStatus());
        }
        Object rawRound = subTask.getContext() == null
                ? null : subTask.getContext().get(SubTaskContextKeys.RESTRUCTURE_ROUND);
        int round = asInt(rawRound) + 1;
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(SubTaskContextKeys.RESTRUCTURE_ROUND, round);
        patch.put(SubTaskContextKeys.RESTRUCTURE_ENTERED_AT, OffsetDateTime.now().toString());
        if (reason != null && !reason.isBlank()) {
            patch.put(SubTaskContextKeys.RESTRUCTURE_REASON, reason.trim());
        }
        stateMachineService.changeStatus(subTaskId, SubTaskStatus.RESTRUCTURING, null, patch);
        log.info("子任务转入人工重构: id={}, round={}, from={}", subTaskId, round, subTask.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubTaskView updateRestructureContent(Long subTaskId, String title, String content, String deliverable,
                                                String acceptance, String constraints, String acceptanceKind,
                                                Long editorId) {
        SubTask subTask = subTaskMapper.selectById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        // fail-close：只有人工接管态可改要求；其余状态的执行者仍在跑，改要求会与在途产出冲突
        if (subTask.getStatus() != SubTaskStatus.RESTRUCTURING) {
            throw new BizException("仅重构中状态可编辑，当前状态: " + subTask.getStatus());
        }
        Map<String, Object> context = subTask.getContext() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(subTask.getContext());
        List<Map<String, Object>> history = readRestructureHistory(
                context.get(SubTaskContextKeys.RESTRUCTURE_HISTORY));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", subTask.getTitle());
        snapshot.put("content", subTask.getContent());
        snapshot.put("deliverable", subTask.getDeliverable());
        snapshot.put("acceptance", subTask.getAcceptance());
        snapshot.put("constraints", subTask.getConstraints());
        snapshot.put("editedAt", OffsetDateTime.now().toString());
        snapshot.put("editorId", editorId);
        history.add(snapshot);
        context.put(SubTaskContextKeys.RESTRUCTURE_HISTORY, trimRestructureHistory(history));

        if (title != null && !title.isBlank()) {
            subTask.setTitle(title.trim());
        }
        if (content != null) {
            subTask.setContent(content);
        }
        if (deliverable != null) {
            subTask.setDeliverable(deliverable);
        }
        if (acceptance != null) {
            subTask.setAcceptance(acceptance);
        }
        if (constraints != null) {
            subTask.setConstraints(constraints);
        }
        subTask.setContext(context);
        if (acceptanceKind != null && !acceptanceKind.isBlank()) {
            AcceptanceKind declared = parseExplicitKind(acceptanceKind);
            if (declared != null) {
                subTask.setAcceptanceKind(declared);
            } else {
                subTask.setAcceptanceKind(AcceptanceKind.parse(acceptanceKind));
            }
            putDeclaredKind(subTask, declared == null ? null : declared.name());
        }
        subTaskMapper.updateById(subTask);
        log.info("重构要求已修订: id={}, titleEdited={}, contentEdited={}, acceptanceEdited={}, editorId={}",
                subTaskId, title != null, content != null || deliverable != null || acceptance != null,
                acceptance != null, editorId);
        return getView(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubTaskView applyRestructure(Long subTaskId) {
        SubTask subTask = subTaskMapper.selectById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (subTask.getStatus() != SubTaskStatus.RESTRUCTURING) {
            throw new BizException("仅重构中状态可取回派发，当前状态: " + subTask.getStatus());
        }
        List<Map<String, Object>> history = readRestructureHistory(subTask.getContext() == null
                ? null : subTask.getContext().get(SubTaskContextKeys.RESTRUCTURE_HISTORY));
        // 首条 = 进入重构前的原始要求（下游当初依赖的产出基线）；无编辑记录时用当前值，等价「未改动」
        Map<String, Object> baseline = history.isEmpty() ? null : history.get(0);
        String oldDeliverable = baseline == null ? subTask.getDeliverable() : asString(baseline.get("deliverable"));
        String oldAcceptance = baseline == null ? subTask.getAcceptance() : asString(baseline.get("acceptance"));
        List<SubTask> siblings = loadSiblings(subTask);
        RuntimeContractGate.Result gate = runtimeContractGateService.check(
                oldDeliverable, oldAcceptance, subTask.getDeliverable(), subTask.getAcceptance(),
                toDownstreams(subTask, siblings), toUpstreams(subTask, siblings),
                plannerAgentIdOf(subTask));
        if (gate.hasBlocking()) {
            RuntimeContractGate.Finding first = gate.findings().stream()
                    .filter(f -> "block".equals(f.severity()))
                    .findFirst()
                    .orElseThrow();
            log.warn("整改未通过 G5 运行期契约校验: subTaskId={}, findings={}", subTaskId, gate.findings());
            throw new BizException("整改未通过 G5 运行期契约校验：" + first.evidence()
                    + "（" + first.ruleId() + "）");
        }
        String now = OffsetDateTime.now().toString();
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(SubTaskContextKeys.RESTRUCTURE_GATE_FINDINGS, RuntimeContractGate.payload(gate, now));
        patch.put(SubTaskContextKeys.RESTRUCTURE_APPLIED_AT, now);
        stateMachineService.changeStatus(subTaskId, SubTaskStatus.PENDING, null, patch);
        log.info("重构整改完成回派发: id={}, findings={}", subTaskId, gate.findings().size());
        return getView(subTaskId);
    }

    /**
     * 同任务下的其它子任务（含 `DONE`：上游产出正是从已完成行里取的）。
     * 逻辑删除由 `@TableLogic` 自动过滤。
     */
    private List<SubTask> loadSiblings(SubTask current) {
        return subTaskMapper.selectList(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getTaskId, current.getTaskId())
                .ne(SubTask::getId, current.getId()));
    }

    /**
     * 依赖本子任务且未终态的下游（`DONE` / `CANCELLED` 不参与：
     * 下游已经交付或放弃，整改不再改变它的契约）。
     */
    private static List<RuntimeContractGate.Downstream> toDownstreams(SubTask current, List<SubTask> siblings) {
        List<RuntimeContractGate.Downstream> downstreams = new ArrayList<>();
        for (SubTask sibling : siblings) {
            if (sibling.getStatus() == SubTaskStatus.DONE || sibling.getStatus() == SubTaskStatus.CANCELLED) {
                continue;
            }
            List<Long> deps = sibling.dependsOnIdList();
            if (deps == null || !deps.contains(current.getId())) {
                continue;
            }
            downstreams.add(new RuntimeContractGate.Downstream(sibling.getId(), sibling.getTitle(),
                    asString(sibling.getContent()) + "\n" + asString(sibling.getAcceptance())));
        }
        return downstreams;
    }

    /** 直接上游的产出与约束（语义层判断「新要求是否与上游已提供的事实冲突」）。 */
    private static List<RuntimeContractGate.Upstream> toUpstreams(SubTask current, List<SubTask> siblings) {
        List<Long> deps = current.dependsOnIdList();
        if (deps == null || deps.isEmpty()) {
            return List.of();
        }
        Map<Long, SubTask> byId = new LinkedHashMap<>();
        for (SubTask sibling : siblings) {
            byId.put(sibling.getId(), sibling);
        }
        List<RuntimeContractGate.Upstream> upstreams = new ArrayList<>();
        for (Long depId : deps) {
            SubTask up = byId.get(depId);
            if (up == null) {
                continue;
            }
            String output = SubTaskOutputExtractor.extractExecutionOutput(up.getContext());
            upstreams.add(new RuntimeContractGate.Upstream(up.getId(), up.getTitle(),
                    asString(up.getContent()) + "\n" + asString(up.getAcceptance()) + "\n"
                            + asString(up.getConstraints()) + "\n" + asString(output)));
        }
        return upstreams;
    }

    /** 语义门借 planner agent 调模型（拆解时写入 `context.plannerAgentId`）。 */
    private static Long plannerAgentIdOf(SubTask subTask) {
        Object raw = subTask.getContext() == null ? null : subTask.getContext().get("plannerAgentId");
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static List<Map<String, Object>> readRestructureHistory(Object raw) {
        List<Map<String, Object>> history = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return history;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            history.add(copy);
        }
        return history;
    }

    /** 超限时保留首条（原始基线）与最近条目——基线一丢，{@code G5} 就失去比较对象。 */
    private static List<Map<String, Object>> trimRestructureHistory(List<Map<String, Object>> history) {
        int limit = SubTaskContextKeys.RESTRUCTURE_HISTORY_LIMIT;
        if (history.size() <= limit) {
            return history;
        }
        List<Map<String, Object>> trimmed = new ArrayList<>(limit);
        trimmed.add(history.get(0));
        trimmed.addAll(history.subList(history.size() - (limit - 1), history.size()));
        return trimmed;
    }

    private static int asInt(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? 0 : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String asString(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubTask create(SubTask subTask, Long assignedAgentId) {
        subTask.setAssignedAgentId(null);
        subTask.setStatus(SubTaskStatus.PENDING);
        save(subTask);
        if (assignedAgentId != null) {
            // 状态写入走唯一入口（PENDING → ASSIGNED），不在门面另开写路径
            stateMachineService.claim(subTask.getId(), assignedAgentId);
        }
        return getById(subTask.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<SubTask> createBatch(List<BatchCreateItem> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        List<SubTask> created = new ArrayList<>(items.size());
        int failCount = 0;
        for (BatchCreateItem item : items) {
            if (item == null || item.getSubTask() == null) {
                failCount++;
                continue;
            }
            try {
                SubTask subTask = create(item.getSubTask(), item.getAssignedAgentId());
                created.add(subTask);
            } catch (Exception e) {
                failCount++;
                log.warn("子任务批量派发单项失败: title={}, agentId={}, err={}",
                        item.getSubTask().getTitle(), item.getAssignedAgentId(), e.getMessage());
            }
        }
        log.info("子任务批量派发: total={}, success={}, failed={}",
                items.size(), created.size(), failCount);
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDependsOn(Long subTaskId, List<Long> dependsOnIds) {
        List<Long> ids = dependsOnIds != null ? dependsOnIds : Collections.emptyList();
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(ids.get(i));
        }
        json.append(']');
        int updated = subTaskMapper.updateDependsOn(subTaskId, json.toString(), OffsetDateTime.now());
        if (updated == 0) {
            throw new BizException("子任务不存在或已删除，无法写入依赖: " + subTaskId);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>读改写回写整体不透明</b>：取<b>最新行</b>而非消费方快照，既保留同行互斥
     * （{@code @Version} CAS 仍生效），又避免消费方携带的陈旧 {@code version} 让写入静默丢失。
     * 与 {@code SubTaskCommandPortAdapter#updateContext} 同源（适配器现委托本方法）。</p>
     */
    @Override
    public void updateContext(Long subTaskId, Map<String, Object> context) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) {
            return;
        }
        subTask.setContext(context);
        updateById(subTask);
    }

    /** 按键合并 context：先取最新行再逐键写入，避免旧快照整段覆盖第三方刚写的键。 */
    private void mergeContext(Long subTaskId, Map<String, Object> patch) {
        if (subTaskId == null || patch == null || patch.isEmpty()) {
            return;
        }
        SubTask latest = getById(subTaskId);
        if (latest == null) {
            return;
        }
        Map<String, Object> context = new LinkedHashMap<>(
                latest.getContext() != null ? latest.getContext() : Map.of());
        context.putAll(patch);
        latest.setContext(context);
        if (!updateById(latest)) {
            log.warn("子任务 context 合并未命中（并发删除？）: subTaskId={}", subTaskId);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>独立事务 + 按键合并。与 {@code TaskService#updateContextInNewTx} 同一手法：
     * 先取最新行再逐键写入（{@code @Version} 只保护版本号、不保护内容，旧快照整段提交会
     * 覆盖掉两次写入之间第三方改动的键）。{@code updateById} 返回 false 时告警留痕，不静默丢失。</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void updateContextInNewTx(Long subTaskId, Map<String, Object> patch) {
        mergeContext(subTaskId, patch);
    }

    /**
     * {@inheritDoc}
     *
     * <p>合并逻辑与 {@link #updateContextInNewTx} 共用 {@link #mergeContext}，差别只在事务边界：
     * 本方法<b>不加</b> {@code @Transactional}，跟随调用方事务（判据是「结论描述的主体还在不在」——
     * 主体与结论同事务，才不会留下孤儿 findings）。</p>
     */
    @Override
    public void mergeContextInTx(Long subTaskId, Map<String, Object> patch) {
        mergeContext(subTaskId, patch);
    }


    @Override
    public SubTaskView getView(Long subTaskId) {
        return queryService.getView(subTaskId);
    }

    @Override
    public List<SubTaskView> listViewsByTaskIdAndStatus(Long taskId, SubTaskStatus status) {
        return queryService.listViewsByTaskIdAndStatus(taskId, status);
    }

    @Override
    public long countByTaskIdExcludingStatus(Long taskId, SubTaskStatus excluded) {
        return queryService.countByTaskIdExcludingStatus(taskId, excluded);
    }

    @Override
    public long countByTaskIdAndStatus(Long taskId, SubTaskStatus status) {
        return queryService.countByTaskIdAndStatus(taskId, status);
    }

    @Override
    public List<SubTaskView> listViewsByIds(Collection<Long> ids) {
        return queryService.listViewsByIds(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<SubTaskView> materializeFromStaging(Long taskId, List<SubTaskDraft> drafts,
                                                    Map<String, List<String>> dependsOnCodes, Integer slaMinutes) {
        if (taskId == null || drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        OffsetDateTime deadline = (slaMinutes != null && slaMinutes > 0)
                ? OffsetDateTime.now().plusMinutes(slaMinutes)
                : null;
        String confirmedAt = OffsetDateTime.now().toString();

        // 插入前定稿：状态 / deadline / 确认标记 / 派生锚一次算好，避免「先插待审再逐条转正」
        // 的中间态与额外状态迁移事件（合并基线 V146 起 PENDING_PLAN_REVIEW 不再是合法落库状态）。
        Map<String, DerivationTarget> targets = new LinkedHashMap<>();
        List<SubTask> entities = new ArrayList<>(drafts.size());
        for (SubTaskDraft draft : drafts) {
            DerivationTarget target = resolveMaterializeTarget(taskId, draft.draftCode());
            targets.put(draft.draftCode(), target);

            SubTask entity = SubTaskProjection.toEntity(draft);
            entity.setStatus(target.status());
            if (target.status() == SubTaskStatus.PENDING && deadline != null) {
                entity.setDeadline(deadline);
            }
            Map<String, Object> context = entity.getContext() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(entity.getContext());
            context.put("planConfirmedAt", confirmedAt);
            if (target.derivedTaskId() != null) {
                context.put("derivedTaskId", target.derivedTaskId());
            }
            entity.setContext(context);
            entities.add(entity);
        }
        saveBatch(entities);

        Map<String, Long> codeToId = new LinkedHashMap<>();
        for (SubTask entity : entities) {
            if (entity.getDraftCode() != null) {
                codeToId.put(entity.getDraftCode(), entity.getId());
            }
        }
        for (SubTask entity : entities) {
            List<String> codes = dependsOnCodes == null ? null : dependsOnCodes.get(entity.getDraftCode());
            if (codes == null) {
                continue;
            }
            List<Long> ids = new ArrayList<>(codes.size());
            for (String code : codes) {
                Long depId = codeToId.get(code);
                if (depId == null) {
                    log.warn("物化依赖翻译失败，丢弃该边: taskId={}, code={}, depCode={}",
                            taskId, entity.getDraftCode(), code);
                    continue;
                }
                ids.add(depId);
            }
            updateDependsOn(entity.getId(), ids);
        }

        // 派生锚回填必须在插入拿到真实 id 之后（Y 的 parent_sub_task_id 指向本次物化行）
        for (SubTask entity : entities) {
            DerivationTarget target = targets.get(entity.getDraftCode());
            if (target != null && target.derivedTaskId() != null) {
                taskDerivationService.bindParentSubTask(target.derivedTaskId(), entity.getId());
            }
        }

        log.info("暂存区物化完成（最终状态直插）: taskId={}, count={}", taskId, entities.size());
        return listViewsByIds(entities.stream().map(SubTask::getId).toList());
    }

    /**
     * 确认时的物化目标（`ADR-008` §19.3 S5）。
     *
     * <p>只在插入前按 `(taskId, draftCode)` 判定，不依赖已落库行：派生 Task 已完成 ⇒ `DONE`；
     * 未完成 ⇒ `AWAITING_DERIVED`；未派生 ⇒ `PENDING`。派生锚在插入后由调用方回填。</p>
     */
    private DerivationTarget resolveMaterializeTarget(Long taskId, String code) {
        if (code == null || code.isBlank()) {
            return new DerivationTarget(SubTaskStatus.PENDING, null);
        }
        TaskView derived = taskDerivationService.findActivePlanDerivation(taskId, code);
        if (derived == null) {
            return new DerivationTarget(SubTaskStatus.PENDING, null);
        }
        SubTaskStatus target = derived.status() == TaskStatus.DONE
                ? SubTaskStatus.DONE : SubTaskStatus.AWAITING_DERIVED;
        log.info("草案确认物化：已派生草案不进入分发链: taskId={}, code={}, derivedTaskId={}, target={}",
                taskId, code, derived.id(), target);
        return new DerivationTarget(target, derived.id());
    }

    private record DerivationTarget(SubTaskStatus status, Long derivedTaskId) {
    }

    public void unlinkByAssignedAgent(Long agentId) {
        subTaskMapper.update(null, new LambdaUpdateWrapper<SubTask>()
                .eq(SubTask::getAssignedAgentId, agentId)
                .set(SubTask::getAssignedAgentId, null));
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int physicalDeleteNeverExecutedCancelled(Long taskId) {
        return subTaskMapper.physicalDeleteNeverExecutedCancelled(taskId);
    }

    public int physicalDeleteByTaskId(Long taskId) {
        // 同域 Mapper 直捅（CODE_STYLE §7.1：同域合规）
        return subTaskMapper.physicalDeleteByTaskId(taskId);
    }
    public List<String> requiredSkillsOf(Long taskId) {
        return queryService.requiredSkillsOf(taskId);
    }
    @Override
    public void demoteContractFlags(List<Long> subTaskIds) {
        if (subTaskIds == null || subTaskIds.isEmpty()) {
            return;
        }
        lambdaUpdate()
                .in(SubTask::getId, subTaskIds)
                .set(SubTask::getIsContract, 0)
                .update();
    }

    @Override
    public List<String> mergeSkills(SubTask subTask) {
        return queryService.mergeSkills(subTask);
    }

    public List<String> mergeSkills(Long subTaskId) {
        return queryService.mergeSkills(subTaskId);
    }
    public SubTaskClaimConstraint claimConstraint(Long subTaskId) {
        return queryService.claimConstraint(subTaskId);
    }
    public IPage<SubTask> list(Long taskId, SubTaskStatus status, Long assignedAgentId, Integer page, int pageSize) {
        return queryService.list(taskId, status, assignedAgentId, page, pageSize);
    }
    public List<SubTask> listAvailable() {
        return queryService.listAvailable();
    }
    public List<SubTask> listMine(Long assignedAgentId) {
        return queryService.listMine(assignedAgentId);
    }
    public List<SubTask> listRecentlyChanged(OffsetDateTime since, int limit) {
        return queryService.listRecentlyChanged(since, limit);
    }
    public SubTask getByIdForUpdate(Long subTaskId) {
        return queryService.getByIdForUpdate(subTaskId);
    }
    public boolean isReady(SubTask subTask) {
        return queryService.isReady(subTask);
    }
    public Map<String, Integer> countByStatusForAgent(Long agentId) {
        return queryService.countByStatusForAgent(agentId);
    }
    public long countByAssignedAgent(Long agentId) {
        return queryService.countByAssignedAgent(agentId);
    }
    public long countReviewByReviewerAgent(Long agentId) {
        return queryService.countReviewByReviewerAgent(agentId);
    }
    public List<SubTask> selectInFlightByAgent(Long agentId, int limit) {
        return queryService.selectInFlightByAgent(agentId, limit);
    }
    public int countInFlightByAgent(Long agentId) {
        return queryService.countInFlightByAgent(agentId);
    }
    public int countInFlightAll() {
        return queryService.countInFlightAll();
    }
    public boolean existsInFlightAssignedOrInProgress(Long agentId) {
        return queryService.existsInFlightAssignedOrInProgress(agentId);
    }
    public List<SubTask> listInFlightAssignedOrInProgress(Long agentId) {
        return queryService.listInFlightAssignedOrInProgress(agentId);
    }
    public List<Long> listPendingUnassignedWithoutActiveExecutionRecord(int limit) {
        return queryService.listPendingUnassignedWithoutActiveExecutionRecord(limit);
    }
    public List<Long> listStalePendingWithoutExecutionRecord(OffsetDateTime cutoff, int limit) {
        return queryService.listStalePendingWithoutExecutionRecord(cutoff, limit);
    }
    public List<Long> listStaleBlockedSubTaskIds(OffsetDateTime cutoff, int limit) {
        return queryService.listStaleBlockedSubTaskIds(cutoff, limit);
    }
    public List<SubTaskView> listViewsByTaskId(Long taskId) {
        return queryService.listViewsByTaskId(taskId);
    }
    // ══════════════════════════════════════════════════════════════
    //  委托：状态迁移唯一入口 → SubTaskStateMachineService
    // ══════════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId) {
        stateMachineService.changeStatus(subTaskId, newStatus, agentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId, Map<String, Object> contextPatch) {
        stateMachineService.changeStatus(subTaskId, newStatus, agentId, contextPatch);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claim(Long subTaskId, Long agentId) {
        stateMachineService.claim(subTaskId, agentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void start(Long subTaskId) {
        stateMachineService.start(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long subTaskId) {
        stateMachineService.submit(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long subTaskId) {
        stateMachineService.complete(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pause(Long subTaskId) {
        stateMachineService.pause(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resume(Long subTaskId) {
        stateMachineService.resume(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long subTaskId) {
        stateMachineService.cancel(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void block(Long subTaskId) {
        stateMachineService.block(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void block(Long subTaskId, String reason, Long reporterAgentId) {
        stateMachineService.block(subTaskId, reason, reporterAgentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean rework(Long subTaskId, Long reworkAgentId) {
        return stateMachineService.rework(subTaskId, reworkAgentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reworkFresh(Long subTaskId, Long reworkAgentId) {
        stateMachineService.reworkFresh(subTaskId, reworkAgentId);
    }

    @Override
    public void markManualIntervention(Long subTaskId, String reason, Map<String, Object> extra) {
        stateMachineService.markManualIntervention(subTaskId, reason, extra);
    }

    @Override
    public void clearManualIntervention(Long subTaskId) {
        stateMachineService.clearManualIntervention(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignNext(Long agentId, Long subTaskId) {
        stateMachineService.assignNext(agentId, subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubTask resetToPendingForDispatch(Long subTaskId, Set<SubTaskStatus> allowedStatuses) {
        return stateMachineService.resetToPendingForDispatch(subTaskId, allowedStatuses);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reassign(Long subTaskId, Long newAgentId) {
        stateMachineService.reassign(subTaskId, newAgentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean claimAtomic(Long subTaskId, Long agentId) {
        return stateMachineService.claimAtomic(subTaskId, agentId);
    }

    // ══════════════════════════════════════════════════════════════
    //  委托：租约 / 回收 / 巡检 → SubTaskLeaseService
    // ══════════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int renewCurrentNodeLeases(OffsetDateTime newLeaseUntil, int limit) {
        return leaseService.renewCurrentNodeLeases(newLeaseUntil, limit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int reclaimExpiredLeases(int limit) {
        return leaseService.reclaimExpiredLeases(limit);
    }

    @Override
    public List<SubTask> listReviewOrphans(int thresholdSeconds, int limit) {
        return leaseService.listReviewOrphans(thresholdSeconds, limit);
    }

    @Override
    public List<SubTaskView> listReviewOrphanViews(int thresholdSeconds, int limit) {
        return leaseService.listReviewOrphanViews(thresholdSeconds, limit);
    }

    @Override
    public List<SubTask> listPausedBefore(Long agentId, OffsetDateTime expiredBefore) {
        return leaseService.listPausedBefore(agentId, expiredBefore);
    }

    @Override
    public List<SubTask> listTimedOutAssigned(OffsetDateTime deadline, int limit) {
        return leaseService.listTimedOutAssigned(deadline, limit);
    }

    @Override
    public int incrementExternalFallbackCount(Long subTaskId, OffsetDateTime now) {
        return leaseService.incrementExternalFallbackCount(subTaskId, now);
    }
}
