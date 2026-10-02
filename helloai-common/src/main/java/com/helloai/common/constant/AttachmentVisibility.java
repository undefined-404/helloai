package com.helloai.common.constant;

/**
 * 附件可见范围（授权作用域声明）。
 *
 * <p><b>为什么是"范围声明"而不是"归属外键"</b>：可见性不绑死到具体任务 / 子任务，而由
 * 「本枚举（声明范围）× 请求者的成员关系」共同判定，判定入口统一为
 * {@code AttachmentVisibilityPolicy}。附件仍保留 {@code sub_task_id} 作为<b>产出归属与溯源</b>，
 * 但权限判定<b>不依赖它</b>——只依赖"从附件解析出的根任务"这一抽象。</p>
 *
 * <p>设计依据：{@code doc/design/HelloAI Task-Team 与附件可见性设计.md} §4。
 * 这样未来「二次分解（子任务的子任务）」「一个需求拆多个主任务」等层级演进，
 * 只需扩展"根任务解析"，权限判定入口零改动。</p>
 *
 * <ul>
 *   <li>{@code PERSONAL}：仅上传者本人可读（上传者离队后仍可读自己上传的）</li>
 *   <li>{@code TASK}：上传者 + 附件所属<b>根任务</b>的 {@code task_agent_member} 在队成员（默认）</li>
 *   <li>{@code PUBLIC}：所有 ACTIVE agent。<b>当前仅预留枚举与 CHECK，不提供任何设置入口</b>，避免误用</li>
 * </ul>
 */
public enum AttachmentVisibility {

    /** 仅上传者本人可读。 */
    PERSONAL,

    /** 上传者 + 所属根任务的 Task-Team 在队成员（默认）。 */
    TASK,

    /** 所有 ACTIVE agent（预留，不开放设置入口）。 */
    PUBLIC
}
