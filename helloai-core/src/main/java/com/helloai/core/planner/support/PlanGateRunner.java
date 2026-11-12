package com.helloai.core.planner.support;

import com.helloai.common.config.PlannerDecomposeProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.constant.TaskArchetype;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.common.util.DraftGateChecker;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.support.SubTaskGateFindingsStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拆解域的门禁编排：**把已落库草案送进 {@link DraftGateChecker}，并把结论写回草案 + 时间线**。
 *
 * <p><b>为什么不放在 {@code PlannerDecomposeAsyncServiceImpl} 里</b>：同一段编排现在有三个调用方
 * （整批拆解、单条展开/重生成、确认前放行），而「读草案 → 转快照 → 判定 → 落结论 → 记时间线」
 * 一旦复制到三处，下一步就会出现「一处写 findings、一处只抛异常、一处忘了清」的分叉——
 * 这正是 `2026-11-07` 已经发生过的形态。</p>
 *
 * <p><b>判定作用域是「本任务当前全部待审草案」</b>，不是「本次新增的 N 条」：门 1 判的是整张
 * 依赖图是否闭合，只看新增的局部会漏掉跨批次引入的环。展开/重生成后新增的草案因此也会让
 * 既有草案的结论被重算——重算是幂等的（同一份输入得到同一份结论），且顺带修掉「上一轮修好了
 * 但 findings 还挂着」的陈旧状态。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanGateRunner {

    /** 上报到时间线的明细条数上限：payload 是排障线索，不是全量导出。 */
    private static final int TIMELINE_ITEM_LIMIT = 8;

    private final SubTaskGateFindingsStore subTaskGateFindingsStore;
    private final TaskTimelineService taskTimelineService;

    /**
     * 深度分档的唯一事实源（{@code MaxExpandDepth#limitOf}）。
     *
     * <p><b>为什么放在本类而不是各调用方各自算</b>：三处调用（整批拆解 / 单条重生成 / 确认前放行）
     * 若各写一份「按 archetype 取上限」，漏配某个档位或口径不一致时不会有任何报错——
     * 只是某条路径静默不判深度。判据只能有一个实现，调用方只负责把自己知道的
     * {@code task_archetype} 递进来。</p>
     */
    private final PlannerDecomposeProperties plannerDecomposeProperties;

    /** 草案视图 → 判定输入（中性结构，判据不绑定任何域的 DTO）。 */
    public static List<DraftGateChecker.DraftSnapshot> snapshots(List<SubTaskView> drafts) {
        List<DraftGateChecker.DraftSnapshot> result = new ArrayList<>();
        if (drafts == null) {
            return result;
        }
        for (SubTaskView d : drafts) {
            result.add(new DraftGateChecker.DraftSnapshot(
                    d.id(), d.draftCode(), d.title(), d.content(), d.deliverable(), d.acceptance(),
                    declaredKind(d), d.dependsOn()));
        }
        return result;
    }

    /** findings 定位身份（与 {@link DraftGateChecker.DraftSnapshot#ref()} 同口径）：code 优先，无 code 回落 id 字符串。 */
    public static String refOf(SubTaskView d) {
        return d.draftCode() != null && !d.draftCode().isBlank()
                ? d.draftCode() : String.valueOf(d.id());
    }

    /** 显式声明的 kind 原文由拆解侧落在 {@code context}，见 {@code SubTaskContextKeys.ACCEPTANCE_KIND_DECLARED}。 */
    private static String declaredKind(SubTaskView draft) {
        Object raw = draft.context() == null
                ? null : draft.context().get(SubTaskContextKeys.ACCEPTANCE_KIND_DECLARED);
        return raw == null ? null : String.valueOf(raw);
    }

    /**
     * 门 1 + 门 2 + 深度（整批范围）。
     *
     * <p><b>刻意没有单参重载</b>：深度判据依赖 {@code task_archetype}，留一个「不传 archetype」
     * 的入口就等于给后人一条静默跳过判据的路（「规则存在 ≠ 全路径强制」）。调用方必须把自己手上的
     * archetype 显式递进来；确实不知道时传 {@code null}，由 {@code limitOf} 按 {@code BUILD} 口径处理——
     * 那与 {@code TaskArchetype.parse} 的回落一致，不是「不判」。</p>
     */
    public DraftGateChecker.Result evaluate(List<SubTaskView> drafts, TaskArchetype archetype) {
        return DraftGateChecker.check(snapshots(drafts), maxDepthOf(archetype));
    }

    /** 按 archetype 取展开上限（{@code null} 走 {@code BUILD} 口径，与解析回落一致）。 */
    private int maxDepthOf(TaskArchetype archetype) {
        PlannerDecomposeProperties.MaxExpandDepth cfg = plannerDecomposeProperties == null
                ? null : plannerDecomposeProperties.getMaxExpandDepth();
        return cfg == null ? DraftGateChecker.NO_DEPTH_LIMIT : cfg.limitOf(archetype);
    }

    /**
     * 合并「非草案内容来源」的结论（如序号 → id 映射失败、依赖指向不存在）。
     *
     * <p>这些结论产生于解析/回写阶段，{@link DraftGateChecker} 看库时它们已不在字段里；
     * 但它们的性质与门 1 完全相同（结构不闭合），必须与门禁结论同处一条 findings，
     * 否则用户会看到「草案字段都对、却不能确认」而无从下手。</p>
     */
    public DraftGateChecker.Result merge(DraftGateChecker.Result result,
                                         List<DraftGateChecker.Finding> extra) {
        if (extra == null || extra.isEmpty()) {
            return result;
        }
        List<DraftGateChecker.Finding> all = new ArrayList<>(result.findings());
        all.addAll(extra);
        return new DraftGateChecker.Result(all);
    }

    /**
     * 把结论写回草案（命中的写 findings，未命中的写清零）。
     *
     * <p>批次级问题（依赖环）挂到**首条草案**上：一条环没有「单条」主体，但结论必须落在某个
     * 实体上才能被 UI 定位；挂首条是确定性的（不随加载顺序漂移的 id 最小者由调用方保证）。</p>
     *
     * <p>{@code B2} 后 {@code byDraft()} 的键是稳定 code（findings 内容以此暴露）；{@code Store}
     * 仍按 {@code sub_task.id} 落库，此处把 code 分组键翻译回 id（未命中的行在 {@code drafts} 内
     * 与 id 一一对应，翻译不到只可能是极端脏数据，其 findings 落为 {@code null} 键由首条承接）。</p>
     */
    public void persist(List<SubTaskView> drafts, DraftGateChecker.Result result) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        List<Long> ids = drafts.stream().map(SubTaskView::id).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return;
        }
        subTaskGateFindingsStore.applyBatch(ids, groupBySubTaskId(drafts, result), ids.get(0));
    }

    /**
     * 同 {@link #persist}，但结论与调用方共用事务（整批拆解的落库段专用）。
     *
     * <p>见 {@link SubTaskGateFindingsStore#applyBatchInTx}：草案若被外层回滚，结论必须一起消失，
     * 否则留下指向不存在草案的孤儿 findings。</p>
     */
    public void persistInTx(List<SubTaskView> drafts, DraftGateChecker.Result result) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        List<Long> ids = drafts.stream().map(SubTaskView::id).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return;
        }
        subTaskGateFindingsStore.applyBatchInTx(ids, groupBySubTaskId(drafts, result), ids.get(0));
    }

    /** code 分组键 → subTaskId 分组键（见 {@link #persist} javadoc）。 */
    private Map<Long, List<DraftGateChecker.Finding>> groupBySubTaskId(
            List<SubTaskView> drafts, DraftGateChecker.Result result) {
        Map<String, Long> refToId = new HashMap<>();
        for (SubTaskView d : drafts) {
            if (d.id() != null) {
                refToId.put(refOf(d), d.id());
            }
        }
        Map<Long, List<DraftGateChecker.Finding>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<DraftGateChecker.Finding>> e : result.byDraft().entrySet()) {
            Long id = e.getKey() == null ? null : refToId.get(e.getKey());
            out.put(id, e.getValue());
        }
        return out;
    }

    /** 草案离开待审态（确认转正 / 驳回作废）后清空其 findings。 */
    public void clear(List<SubTaskView> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        subTaskGateFindingsStore.clearAll(drafts.stream()
                .map(SubTaskView::id).filter(java.util.Objects::nonNull).toList());
    }

    /**
     * 记一条门禁时间线（阻断优先于告警；两者都有时只记阻断，避免同一事实两条事件）。
     *
     * <p>走 {@code recordEventInNewTx}：改造类操作（展开/重生成/编辑）会在判定后抛异常回滚，
     * 而「为什么被拦」正是排障唯一需要的证据，不能与业务共命运。</p>
     */
    public void recordTimeline(Long taskId, Long plannerAgentId, String phase,
                               List<SubTaskView> drafts, DraftGateChecker.Result result) {
        if (taskId == null || result == null || result.isEmpty()) {
            return;
        }
        boolean blocked = result.hasBlocking();
        Map<String, String> titles = new HashMap<>();
        for (SubTaskView d : drafts) {
            titles.put(refOf(d), d.title());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (DraftGateChecker.Finding f : result.findings()) {
            if (items.size() >= TIMELINE_ITEM_LIMIT) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("draftId", f.draftRef());
            item.put("title", f.draftRef() == null ? null : titles.get(f.draftRef()));
            item.put("ruleId", f.ruleId());
            item.put("severity", f.severity());
            item.put("evidence", f.evidence());
            items.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase);
        payload.put("ruleVersion", DraftGateChecker.RULE_VERSION);
        payload.put("blockCount", result.blocking().size());
        payload.put("warnCount", result.warnings().size());
        payload.put("items", items);
        try {
            taskTimelineService.recordEventInNewTx(taskId, null,
                    blocked ? TimelineEventCode.TASK_PLAN_GATE_BLOCKED.code()
                            : TimelineEventCode.TASK_PLAN_GATE_WARNED.code(),
                    AgentRole.PLANNER, plannerAgentId, payload);
        } catch (Exception e) {
            log.warn("记录门禁时间线失败（不改变结论）: taskId={}, phase={}, err={}",
                    taskId, phase, e.getMessage());
        }
    }

    /**
     * 阻断消息（面向用户）：逐条给「草案标题 + 缺什么 + 规则」。
     *
     * <p>只列前 5 条：一次拆解最多 10 条草案，全量拼接会让前端提示框不可读；剩余条数单独计数。</p>
     */
    public String describe(List<SubTaskView> drafts, List<DraftGateChecker.Finding> blocking) {
        Map<String, String> titles = new HashMap<>();
        if (drafts != null) {
            for (SubTaskView d : drafts) {
                titles.put(refOf(d), d.title());
            }
        }
        StringBuilder sb = new StringBuilder("草案未通过门禁：");
        int limit = Math.min(blocking.size(), 5);
        for (int i = 0; i < limit; i++) {
            DraftGateChecker.Finding f = blocking.get(i);
            if (i > 0) {
                sb.append("；");
            }
            String who = f.draftRef() == null ? "整批" : "《" + titles.getOrDefault(f.draftRef(), f.draftRef()) + "》";
            sb.append(who).append(f.evidence()).append("(").append(f.ruleId()).append(")");
        }
        if (blocking.size() > limit) {
            sb.append("；另有 ").append(blocking.size() - limit).append(" 条，见草案自检明细");
        }
        return sb.toString();
    }
}
