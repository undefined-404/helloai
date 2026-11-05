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

    /**
     * 钉住的 LLM 模型（{@code provider:model}，仅助理对话接口生效，2026-11-05）。
     *
     * <p><b>与 {@link #plannerAgentId} 的分工</b>：plannerAgentId 决定「谁来扮演助理」
     * （角色面，需求对话页的入口）；本字段只决定「用哪个模型答」（个人工作台的入口）。
     * 两者可并存：角色给提示词与技能，模型给这次回答。</p>
     *
     * <p>空 = 不覆盖，沿用会话已存值（新会话则回落到 Planner Agent 自身绑定的模型），
     * 老调用方不传即行为不变。取值须来自 {@code /listLlmModelOptions}；
     * 服务端<b>不信任前端</b>——落库前用
     * {@code PlannerAgentPicker#validateModelUsable} 复检「模型启用 + 供应商可用」，
     * 不满足即抛业务异常（与 plannerAgentId 的 {@code validateSelectable} 同一口径）。</p>
     */
    private String modelType;

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
     * 本轮选中的技能（仅助理对话接口生效；需求澄清链路走消息前缀 {@code [skill:name]}）。
     *
     * <p>取值：{@code planner}（方案整理）/ {@code task}（直接拆解）/ {@code pkg:<技能包名>}
     * （产出型技能包，如 archify）。空 = 本轮不加技能约束。</p>
     */
    private String skill;

    /**
     * 新会话首句前上传附件的临时归属（仅新建会话生效；前端生成 UUID）。
     *
     * <p>新会话是「先传文件、再发第一句」的顺序，此刻还没有 {@code conversationId}，
     * 故先用本 token 占位；建会时按 token 认领并回填会话 id，附件随后进入
     * {@code {{ATTACHMENT_MANIFEST}}}。老调用方不传即为无附件，行为不变。</p>
     */
    private String clientToken;

    /**
     * 初始对话模式（仅新建会话生效；append 消息接口忽略）。
     * <p>已废弃：新会话始终 CHAT 模式，LLM auto 意图路由 + /planner 命令触发转方案。</p>
     * @deprecated 使用 /planner 命令替代
     */
    @Deprecated
    private String initialMode;

    /**
     * 本轮参与的附件 id 列表（`ATT-3`：前端勾选）。
     *
     * <p>非空时**优先于模型判定**：只注入/检索这些附件的正文与分片。
     * 空或 null 表示「用户没指定」——回落到模型根据提问内容的判定
     * （{@code attachment_targets}），两者是「显式信号优先、推断信号兜底」的关系。</p>
     */
    private java.util.List<Long> attachmentIds;
}
