package com.helloai.common.util;

/**
 * 子任务轮次口径（<b>全项目唯一推导点</b>）。
 *
 * <p>「第几轮」= {@code round_base + rework_count + 1}，首轮为 1。这个换算此前散落在多处裸写
 * （{@code TaskIterationServiceImpl} 的 {@code round_num}、附件轮次快照等），一旦某处写错
 * 就出现「同一子任务在不同视图里属于不同轮」的静默分叉——因此抽到这里，凡需要「当前轮」
 * 的地方一律委托本类（判据 4：同一事实只能一个事实源）。</p>
 *
 * <p><b>为什么不是只有一个 {@code rework_count}</b>（`G-054`）：{@code rework_count} 语义是
 * 「返工预算已用次数」，在人工驳回与死信重派两条路径上会被<b>清零</b>（与 {@code attempt_total}
 * 对称，那是预算语义，必须清）。若轮次直接等于 {@code rework_count + 1}，清零即意味着轮次
 * 倒退回 1，新旧两轮产物同号、彻底不可分（真机实证：同一子任务 5 份产物全为 round=1）。
 * 因此引入 {@code round_base} 承接「已被重置消耗掉的轮次」，清零时把它抬到下一轮，
 * 两条计数器各守一义：{@code rework_count} 管预算、{@code round_base} 管历史轮次。</p>
 *
 * <p><b>不做兜底猜测</b>：{@code null} 按 0 处理（等价首轮），负数同样收敛到 0；
 * 不识别「历史不可考」这类语义——历史行由各自的迁移注释说明，不在运行期猜。</p>
 */
public final class SubTaskRound {

    /** 首轮序号。 */
    public static final int FIRST = 1;

    private SubTaskRound() {
    }

    /**
     * 由历史轮次基数与返工次数推导当前轮次。
     *
     * @param roundBase   已被重置消耗掉的轮次基数（可空，空按 0）
     * @param reworkCount 本轮已发生返工次数（可空，空按 0）
     * @return 当前轮次，恒 ≥ 1
     */
    public static int currentOf(Integer roundBase, Integer reworkCount) {
        return nonNegative(roundBase) + nonNegative(reworkCount) + FIRST;
    }

    /**
     * 清零路径专用：算出「清零后应写入的 round_base」——即清零当刻恰好翻到下一轮。
     *
     * <p>调用点必须在把 {@code rework_count} 置 0 的<b>同一处</b>用本方法改写
     * {@code round_base}（判据 13：替换类兜底不能只改一半，否则两条计数器的换算当场脱节）。</p>
     *
     * @param roundBase   当前轮次基数（清零前）
     * @param reworkCount 当前返工次数（清零前）
     * @return 新的轮次基数 = 清零前的当前轮次
     */
    public static int baseAfterReset(Integer roundBase, Integer reworkCount) {
        return currentOf(roundBase, reworkCount);
    }

    private static int nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }
}