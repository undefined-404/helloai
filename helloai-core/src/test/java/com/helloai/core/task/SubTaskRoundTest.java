package com.helloai.core.task;

import com.helloai.common.util.SubTaskRound;
import com.helloai.core.task.entity.SubTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轮次口径单源（{@link SubTaskRound}）单测。
 *
 * <p><b>为什么值得单独测</b>：这个换算被三处消费（迭代视图 {@code round_num}、
 * {@code SubTask#currentRound()}、附件轮次快照 {@code attachment.round}）。三处各写一遍时
 * 错一处不会报错，只会让「同一子任务在不同视图里属于不同轮」——典型静默分叉。
 * 收敛成一个函数后，边界（{@code null} / 负数 / 0）必须有明确口径，否则回落默认值又会各自发挥。</p>
 *
 * <p>口径（`G-054` 起）：{@code round_base + rework_count + 1}，首轮为 1。
 * <b>两个计数器缺一不可</b>：只传 {@code rework_count} 会在人工驳回/死信重派后让轮次倒退。</p>
 */
@DisplayName("子任务轮次口径（SubTaskRound）")
class SubTaskRoundTest {

    @Test
    @DisplayName("无返工、无重置 ⇒ 第 1 轮")
    void zeroReworkShouldBeFirstRound() {
        assertThat(SubTaskRound.currentOf(0, 0)).isEqualTo(1);
    }

    @Test
    @DisplayName("返工一次 ⇒ 第 2 轮")
    void oneReworkShouldBeSecondRound() {
        assertThat(SubTaskRound.currentOf(0, 1)).isEqualTo(2);
    }

    @Test
    @DisplayName("null ⇒ 首轮（不按 0 之外的任何数猜）")
    void nullShouldFallbackToFirstRound() {
        assertThat(SubTaskRound.currentOf(null, null)).isEqualTo(SubTaskRound.FIRST);
    }

    @Test
    @DisplayName("负数（脏数据） ⇒ 按 0 计，不外扩")
    void negativeShouldFallbackToZero() {
        assertThat(SubTaskRound.currentOf(-3, -5)).isEqualTo(SubTaskRound.FIRST);
    }

    @Test
    @DisplayName("实体派生方法委托同一换算")
    void entityShouldDelegateToSameSource() {
        SubTask subTask = new SubTask();
        subTask.setReworkCount(2);
        assertThat(subTask.currentRound()).isEqualTo(SubTaskRound.currentOf(0, 2)).isEqualTo(3);

        subTask.setReworkCount(null);
        assertThat(subTask.currentRound()).isEqualTo(1);
    }

    // ── G-054 核心：清零路径不得让轮次倒退 ──────────────────────────────────

    @Test
    @DisplayName("G-054：清零 rework_count 后轮次仍前进（不是回到 1）")
    void clearingReworkCountShouldNotRewindRound() {
        SubTask subTask = new SubTask();
        subTask.setRoundBase(0);
        subTask.setReworkCount(3);
        assertThat(subTask.currentRound()).isEqualTo(4); // 第 4 轮，此时返工 3 次

        // 人工驳回：清零前把当前轮抬进 round_base，再清 rework_count
        subTask.setRoundBase(SubTaskRound.baseAfterReset(subTask.getRoundBase(), subTask.getReworkCount()));
        subTask.setReworkCount(0);

        // 关键：必须是 5 而不是 1
        assertThat(subTask.currentRound()).isEqualTo(5);
    }

    @Test
    @DisplayName("G-054：连续多次清零，轮次每次恰好前进一位（不倒退、不跳号）")
    void repeatedResetsShouldAdvanceByOne() {
        SubTask subTask = new SubTask();
        subTask.setRoundBase(0);
        subTask.setReworkCount(0);
        assertThat(subTask.currentRound()).isEqualTo(1);

        // 每次「人工驳回」= 清零（把当前轮抬进 round_base）+ 新产出一轮 ⇒ 轮次 +1。
        // 之所以必须 +1 而不是保持不变：清零后的产物属于**新一轮**，若沿用旧轮号
        // 就与清零前的产物同号，轮次又失去区分能力（这正是 G-054 要消灭的症状）。
        for (int expected = 2; expected <= 5; expected++) {
            subTask.setRoundBase(SubTaskRound.baseAfterReset(subTask.getRoundBase(), subTask.getReworkCount()));
            subTask.setReworkCount(0);
            assertThat(subTask.currentRound()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("G-054：清零与返工是两个独立增量（返工 +1，清零再 +1）")
    void resetAndReworkShouldAddUp() {
        SubTask subTask = new SubTask();
        subTask.setRoundBase(0);
        subTask.setReworkCount(0);

        // 返工一次 ⇒ 第 2 轮
        subTask.setReworkCount(1);
        assertThat(subTask.currentRound()).isEqualTo(2);

        // 再人工驳回 ⇒ 第 3 轮（驳回前是第 2 轮，新产物进第 3 轮）
        subTask.setRoundBase(SubTaskRound.baseAfterReset(subTask.getRoundBase(), subTask.getReworkCount()));
        subTask.setReworkCount(0);
        assertThat(subTask.currentRound()).isEqualTo(3);
    }

    @Test
    @DisplayName("G-054：baseAfterReset 取的是清零前的当前轮，不是当前轮 +1")
    void baseAfterResetShouldBeCurrentRoundBefore() {
        assertThat(SubTaskRound.baseAfterReset(0, 0)).isEqualTo(1);
        assertThat(SubTaskRound.baseAfterReset(2, 1)).isEqualTo(4);
        assertThat(SubTaskRound.baseAfterReset(null, null)).isEqualTo(1);
    }
}