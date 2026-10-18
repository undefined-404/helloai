package com.helloai.common.constant;

/**
 * 收件箱事件类型 → 中文名目录（**服务端单一事实源**，2026-10-18）。
 *
 * <p><b>为什么需要</b>：{@code agent_inbox.event_type} 存的是 {@code sub_task.approved}
 * 这类点分字符串，前端原先在 {@code types/dialog.ts} 里硬编码了 8 个键，
 * 但后端实际会发 10 种 —— 缺 {@code sub_task.approved} / {@code sub_task.reassigned} /
 * {@code sub_task.review} / {@code sub_task.unassigned} / {@code task.created} /
 * {@code task.republished}，于是页面上直接裸露英文码。</p>
 *
 * <p><b>收录口径</b>：以「后端实际发送的取值」为准（{@code grep agentInboxService.send(}），
 * 不收录前端臆造的键（原映射里的 {@code sub_task.submitted} / {@code task.completed} 等
 * 后端从未发送，属历史残留）。新增发送点必须同步补此枚举 —— 契约测试会锁。</p>
 */
public enum InboxEventType {

    SUB_TASK_ASSIGNED("sub_task.assigned", "新任务", "有子任务分配给你"),
    SUB_TASK_REASSIGNED("sub_task.reassigned", "任务改派", "子任务被改派（原执行者视角）"),
    SUB_TASK_UNASSIGNED("sub_task.unassigned", "取消指派", "子任务被从你名下撤走"),
    SUB_TASK_REVIEW("sub_task.review", "待审查", "有子任务提交待你审查"),
    SUB_TASK_APPROVED("sub_task.approved", "审查通过", "你提交的子任务已通过审查"),
    SUB_TASK_REJECTED("sub_task.rejected", "需返工", "你提交的子任务被驳回，需返工"),
    SUB_TASK_BLOCKED("sub_task.blocked", "任务阻塞", "子任务执行受阻，需要排障"),
    SUB_TASK_PAUSED("sub_task.paused", "已暂停", "子任务被平台暂停"),
    TASK_CREATED("task.created", "任务创建", "有新的主任务已创建"),
    TASK_REPUBLISHED("task.republished", "任务重发布", "主任务被重新发布"),
    ;

    private final String code;
    private final String label;
    private final String desc;

    InboxEventType(String code, String label, String desc) {
        this.code = code;
        this.label = label;
        this.desc = desc == null ? "" : desc;
    }

    /** 数据库存储值（点分小写，与 {@code agent_inbox.event_type} 逐字一致）。 */
    public String code() {
        return code;
    }

    /** 人话短名（收件箱列表「类型」列显示）。 */
    public String label() {
        return label;
    }

    /** 一句话说明（tooltip / 详情）。 */
    public String desc() {
        return desc;
    }

    /** 反向查：事件码 → 枚举；未知返回 {@code null}（调用方回退显示原码，不显示「未知」）。 */
    public static InboxEventType of(String code) {
        if (code == null) {
            return null;
        }
        for (InboxEventType e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return null;
    }

    /** 反向查：事件码 → 中文短名；未知返回 {@code null}。 */
    public static String labelOf(String code) {
        InboxEventType e = of(code);
        return e != null ? e.label : null;
    }
}
