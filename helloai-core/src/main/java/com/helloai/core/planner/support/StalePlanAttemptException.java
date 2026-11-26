package com.helloai.core.planner.support;

import com.helloai.common.base.BizException;

/**
 * 写段授权失败：本次执行持有的代次在<b>落库那一刻</b>已不是当前有效代次（`G-053` L2）。
 *
 * <p><b>为什么必须是独立类型而不是 {@code BizException}</b>：上层 catch 的处置完全相反——
 *
 * <ul>
 *   <li>普通失败：回退 `PENDING` + 作废本批草案 + 记 `task_plan_failed` + 标 attempt 失败
 *       ⇒ 让用户能重拆，也让残缺批次不再被列表读到；</li>
 *   <li>本异常：<b>一个都不能做</b>。它不是失败，是「我这一代已被取代，本批写入已随事务回滚」。
 *       回退 `PENDING` 会打回新代正在跑的状态；作废本批会按 `batch_key` 全删掉新代的草案；
 *       记 `task_plan_failed` 会把正常的迟到丢弃污染成失败率；标 attempt 失败会去改
 *       一行已经属于新代的记录。</li>
 * </ul>
 *
 * <p>继承 {@code BizException} 是为了落进统一的业务异常口径（不被当成未预期错误打 ERROR 栈），
 * {@code code = 409} 表达「状态冲突」而非「服务端错误」。<b>识别必须用类型</b>，
 * 不能用 code 判——{@code BizException} 的 409 还能来自别处。</p>
 */
public class StalePlanAttemptException extends BizException {

    private final String attemptId;
    private final int myAttemptNo;
    private final Integer currentAttemptNo;
    private final String stage;

    /**
     * @param attemptId        本次执行的幂等键
     * @param myAttemptNo      提交点钉死的代次
     * @param currentAttemptNo 落库时刻库里的真实代次（{@code null} = 行已不存在）
     * @param stage            被拒的位置（`lock` / `status` / `attempt-no`）
     */
    public StalePlanAttemptException(String attemptId, int myAttemptNo, Integer currentAttemptNo, String stage) {
        super(409, "拆解写段授权失败（本次代次已失效）: attemptId=" + attemptId
                + ", myAttemptNo=" + myAttemptNo + ", currentAttemptNo=" + currentAttemptNo
                + ", stage=" + stage);
        this.attemptId = attemptId;
        this.myAttemptNo = myAttemptNo;
        this.currentAttemptNo = currentAttemptNo;
        this.stage = stage;
    }

    public String attemptId() {
        return attemptId;
    }

    public int myAttemptNo() {
        return myAttemptNo;
    }

    public Integer currentAttemptNo() {
        return currentAttemptNo;
    }

    public String stage() {
        return stage;
    }
}