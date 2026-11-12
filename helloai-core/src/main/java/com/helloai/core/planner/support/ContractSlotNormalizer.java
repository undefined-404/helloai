package com.helloai.core.planner.support;

import com.helloai.core.task.port.SubTaskView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 契约位（{@code is_contract}）的确定性归一：**同一任务至多 1 条**。
 *
 * <p><b>为什么需要</b>：任务契约是单值资源（{@code TaskRunningSpec#updateContract} 后被写覆盖），
 * 而契约位由模型按草案声明（{@code PlanDraftItem.contract}）。模型多打一条时，契约内容会随
 * 「哪条先完成」漂移——同一份计划两次执行得到不同的契约，下游装载的输入因此不可复现。
 * 这是数据不变量问题，不是质量问题，所以用**归一**（代码兜底）而不是**门禁**（拒绝产出）：
 * 多打一条不该让用户拿不到草案。</p>
 *
 * <p><b>保留哪一条</b>：优先「无上游依赖」的草案（契约定义本应是计划的起点），其次 id 最小。
 * 两条依据都是确定性的，不依赖加载顺序，重跑得到同一结果。</p>
 *
 * <p><b>0 条时不做兜底</b>：模型没声明契约位，说明这份计划没有契约定义子任务——这在本项目是
 * 合法形态（{@code TaskRunningSpec#contract} 允许为 {@code null}，下游按「无契约」处理）。
 * 代码凭空指定一条会把普通子任务变成契约定义者，反而制造错误语义。</p>
 */
public final class ContractSlotNormalizer {

    private ContractSlotNormalizer() {
    }

    /**
     * 返回需要**降级**（置 0）的草案 id：即声明了契约位但不是被保留那一条。
     *
     * @return 空列表 = 无需变更（0 条或恰好 1 条）
     */
    public static List<Long> demotions(List<SubTaskView> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        List<SubTaskView> declared = drafts.stream()
                .filter(d -> d.id() != null && Integer.valueOf(1).equals(d.isContract()))
                .sorted(Comparator
                        .comparingInt((SubTaskView d) -> hasUpstream(d) ? 1 : 0)
                        .thenComparing(SubTaskView::id))
                .toList();
        if (declared.size() <= 1) {
            return List.of();
        }
        List<Long> demote = new ArrayList<>();
        for (int i = 1; i < declared.size(); i++) {
            demote.add(declared.get(i).id());
        }
        return demote;
    }

    private static boolean hasUpstream(SubTaskView draft) {
        return draft.dependsOn() != null && !draft.dependsOn().isEmpty();
    }
}
