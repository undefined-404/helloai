package com.helloai.common.constant;

/**
 * 活动日志 action → 中文名目录（**服务端单一事实源**，2026-10-18）。
 *
 * <p><b>取值来源</b>：与全局规则里「活动日志规范」的 action 白名单一致
 * （{@code coding / delivery / blocked / reflection / plan / review / patrol}），
 * 也是外部 Agent 手册要求上报的取值集合。</p>
 *
 * <p><b>为什么需要</b>：活动流列表直接显示 {@code coding} / {@code delivery} 这类英文码，
 * 非技术用户无法理解；且前端没有对应映射（用户反馈「前端没有枚举映射」）。</p>
 */
public enum ActivityAction {

    CODING("coding", "执行记录", "执行过程中的进展记录"),
    DELIVERY("delivery", "提交产出", "提交交付物"),
    BLOCKED("blocked", "遇到阻塞", "无法自行解决，请求协助"),
    REFLECTION("reflection", "自省笔记", "被扣分或返工后的反思"),
    PLAN("plan", "规划决策", "Planner 的规划 / 分配 / 排障记录"),
    REVIEW("review", "审查记录", "Reviewer 的审查过程"),
    PATROL("patrol", "巡查记录", "巡查任务执行情况"),
    ;

    private final String code;
    private final String label;
    private final String desc;

    ActivityAction(String code, String label, String desc) {
        this.code = code;
        this.label = label;
        this.desc = desc == null ? "" : desc;
    }

    public String code() {
        return code;
    }

    /** 人话短名（活动流「动作」列显示）。 */
    public String label() {
        return label;
    }

    public String desc() {
        return desc;
    }

    /** 反向查：未知返回 {@code null}（调用方回退显示原码）。 */
    public static String labelOf(String code) {
        if (code == null) {
            return null;
        }
        for (ActivityAction a : values()) {
            if (a.code.equals(code)) {
                return a.label;
            }
        }
        return null;
    }
}
