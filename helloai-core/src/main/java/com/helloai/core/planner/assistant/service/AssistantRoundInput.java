package com.helloai.core.planner.assistant.service;

import java.util.List;

/**
 * 助理对话一轮的附加入参（PA-11 v2）。
 *
 * <p>助理链路此前只收「一句话 + 可选规划者」，与需求澄清链路的联网搜索 / 技能 / 附件三件能力脱节，
 * 界面上挂上去的开关与附件无处落地。本载体把这三项统一带到轮次执行里：</p>
 *
 * <ul>
 *   <li>{@code webSearchEnabled} — 会话级联网搜索开关；null = 沿用会话已存值（新建会话则按关闭处理），
 *       非 null 时以本次请求为准并回写会话；</li>
 *   <li>{@code skill} — 本轮选中的技能：{@code planner}（方案整理）/ {@code task}（直接拆解）/
 *       {@code pkg:<技能包名>}（产出型技能包）；</li>
 *   <li>{@code attachmentIds} — 本轮参与的附件 id（空 = 会话内全部附件参与）；</li>
 *   <li>{@code clientToken} — 新会话首句前上传附件的临时归属（仅新建会话生效，建会后按 token 认领）。</li>
 * </ul>
 *
 * @param webSearchEnabled 联网搜索开关（null=不覆盖会话既有值）
 * @param skill            本轮技能标识（null=本轮不加技能约束）
 * @param attachmentIds    本轮参与的附件 id（null/空=全部）
 * @param clientToken      新会话附件临时归属 token（仅新建会话生效）
 */
public record AssistantRoundInput(Boolean webSearchEnabled,
                                   String skill,
                                   List<Long> attachmentIds,
                                   String clientToken) {

    /** 空入参（等价于「什么都不额外指定」）。 */
    public static AssistantRoundInput empty() {
        return new AssistantRoundInput(null, null, null, null);
    }
}
