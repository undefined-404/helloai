package com.helloai.common.constant;

/**
 * 已安装技能包的状态（REF-1.6，表 {@code skill_package.state}）。
 *
 * <p><b>为什么是枚举而非常量类</b>：CODE_STYLE §12.2「状态用枚举，禁止 {@code if (status == 3)}」+
 * §64 提交前 Checklist「状态使用枚举」。状态参与**决策语义**（目录选版：
 * 同 name 至多一行 {@code ACTIVE}，其余按本枚举分流），故与 {@code SubTaskStatus} /
 * {@code TaskStatus} 同类。</p>
 *
 * <p>对比：**动作码 / 结果码**（如技能包审计的 action）按仓库既有取舍用**常量类 +
 * 字符串列**，理由见 {@code CredentialAuditAction} 的 javadoc（「动作集随管理操作演进」）。</p>
 *
 * <p>持久化为 {@code name()}（MyBatis 默认 {@code EnumTypeHandler}），与 V104 的
 * {@code CHECK (state IN ('ACTIVE','HISTORICAL','DISABLED'))} 逐字对齐。</p>
 */
public enum SkillPackageState {

    /** 当前生效。同 name 至多一行（DB partial unique index 保证）。 */
    ACTIVE,

    /** 历史版本。保留供回滚（{@code activate} 把它翻回 {@link #ACTIVE}）。 */
    HISTORICAL,

    /** 已卸载。目录不读，也不再被回滚命中。 */
    DISABLED
}
