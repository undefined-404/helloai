package com.helloai.common.constant;

/**
 * Task-Team 成员状态。
 *
 * <p>当前仅使用 {@link #ACTIVE}：按既定语义「<b>加入过即成员</b>」，改派不置 LEFT、
 * 不写 {@code leave_time}（符合"改派进来的也能看"的诉求）。{@link #LEFT} 为将来
 * 「离队即失权」预留，当前无任何代码路径写入。</p>
 */
public enum TaskMemberStatus {

    /** 在队（当前唯一实际写入值）。 */
    ACTIVE,

    /** 已离队（预留：将来若改为"离队即失权"，需在可见性判据中过滤本值）。 */
    LEFT
}
