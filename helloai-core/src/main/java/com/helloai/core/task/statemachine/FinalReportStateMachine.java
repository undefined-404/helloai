package com.helloai.core.task.statemachine;

import com.helloai.common.constant.FinalReportStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 最终整合报告状态机（§12.5 状态机收口）。
 *
 * <p><b>动机</b>：{@link FinalReportStatus} 此前是"字段枚举"——状态迁移散落在
 * {@code TaskFinalReportServiceImpl}（generate/rework/rollback/markFailed）、
 * {@code FinalReportReviewServiceImpl}（convergeToDone）、以及兜底任务里，各自手写
 * {@code lambdaUpdate().set(状态)}，没有任何单点校验。缺了守卫就会出现非法迁移
 * （例如 REVIEWING 被直接写成 GENERATING 而不经过报告写回），且新增调用方时无人知道
 * 哪些迁移合法。本类把「合法迁移表」显式建模，成为状态写入的唯一裁判。</p>
 *
 * <p><b>与目标架构 §5 对齐</b>：<i>Event 记录发生过什么；业务状态机记录当前是什么状态。</i>
 * 报告状态由本状态机的迁移表定义，事件（task_timeline）只做审计记录。</p>
 *
 * <p><b>迁移表</b>（from → 允许的 to）：</p>
 * <pre>
 *   NONE       → GENERATING                                 首次生成
 *   GENERATING → REVIEWING | DONE | FAILED                  生成成功（审查开/关）或生成失败
 *   REVIEWING  → GENERATING | DONE                          驳回返工重写 / 审查收敛
 *   DONE       → GENERATING | DONE                          重新生成 / 回滚上一版 / 幂等收敛
 *   FAILED     → GENERATING                                 手动重试
 * </pre>
 *
 * <p><b>为什么 REVIEWING → GENERATING 合法</b>：审查驳回触发返工时，报告正文会被
 * 新一版覆盖（写回 GENERATING 表示"正在重写"），这是 3A 闭环的正常路径，不是非法回退。</p>
 *
 * <p><b>为什么 DONE → DONE 合法</b>：§12.1 单槽回滚把上一版换回当前槽后状态恒为 DONE；
 * 审查收敛也是 REVIEWING → DONE。两者都需要「已经是 DONE 再写 DONE」的幂等语义。</p>
 */
public final class FinalReportStateMachine {

    /** 合法迁移表（键不存在 = 该来源状态不允许任何迁移）。 */
    private static final Map<FinalReportStatus, Set<FinalReportStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(FinalReportStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(FinalReportStatus.NONE,
                EnumSet.of(FinalReportStatus.GENERATING));
        ALLOWED_TRANSITIONS.put(FinalReportStatus.GENERATING,
                EnumSet.of(FinalReportStatus.REVIEWING, FinalReportStatus.DONE, FinalReportStatus.FAILED));
        ALLOWED_TRANSITIONS.put(FinalReportStatus.REVIEWING,
                EnumSet.of(FinalReportStatus.GENERATING, FinalReportStatus.DONE));
        ALLOWED_TRANSITIONS.put(FinalReportStatus.DONE,
                EnumSet.of(FinalReportStatus.GENERATING, FinalReportStatus.DONE));
        ALLOWED_TRANSITIONS.put(FinalReportStatus.FAILED,
                EnumSet.of(FinalReportStatus.GENERATING));
    }

    private FinalReportStateMachine() {
    }

    /**
     * 判断迁移是否合法。
     *
     * @param from 当前状态，null 视为非法（状态缺失不允许迁移）
     * @param to   目标状态，null 视为非法
     */
    public static boolean canTransit(FinalReportStatus from, FinalReportStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<FinalReportStatus> allowed = ALLOWED_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * 校验迁移合法性，非法直接抛 {@link IllegalStateException}。
     *
     * <p>刻意用 {@code IllegalStateException} 而非业务异常：非法迁移是<b>编程错误</b>
     * （调用方写错了迁移），不是可预期的业务失败，应当快速失败暴露。</p>
     *
     * @param from 当前状态
     * @param to   目标状态
     */
    public static void assertTransit(FinalReportStatus from, FinalReportStatus to) {
        if (!canTransit(from, to)) {
            throw new IllegalStateException(
                    "非法的最终报告状态迁移: " + from + " -> " + to
                            + "，允许的目标状态=" + ALLOWED_TRANSITIONS.get(from));
        }
    }
}
