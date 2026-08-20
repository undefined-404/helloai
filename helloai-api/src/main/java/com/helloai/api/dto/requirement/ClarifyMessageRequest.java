package com.helloai.api.dto.requirement;

import com.helloai.core.planner.service.RequirementClarifyService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ClarifyMessageRequest {
    @NotBlank(message = "消息内容不能为空")
    private String message;

    /** 手动指定的 Planner Agent ID（仅新建会话时生效；空=系统自动选择） */
    private Long plannerAgentId;

    /** 结构化选项回答快照（仅追加消息时生效；空=纯文本回答） */
    private List<RequirementClarifyService.ClarifySelection> selectedOptions;

    /**
     * 会话级联网搜索开关（仅新建会话生效；append 消息接口忽略）。
     * <p>前端仿腾讯 ima copilot：输入框旁的轻量开关，默认为开启。</p>
     * <p>后端语义：NULL 或 true 视为开启，每轮 LLM 调用前预检索行业资料注入 Prompt；
     * false 关闭；失败一律降级跳过，不阻断对话流程。</p>
     */
    private Boolean webSearchEnabled;

    /**
     * 初始对话模式（仅新建会话生效；append 消息接口忽略）。
     * <p>'CHAT'=自由对话（缺省）/ 'CLARIFY'=方案澄清快捷直达；非法值后端抛 BizException。</p>
     */
    private String initialMode;
}
