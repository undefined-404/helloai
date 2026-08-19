package com.helloai.core.planner.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.task.entity.Task;
import lombok.Data;

import java.util.List;

/**
 * 对话式需求澄清编排服务（需求模糊 → 多轮追问 → 终稿 → 创建任务）。
 *
 * <p>复用 {@link PlannerAnalysisService} 的五段式范式：选 Agent → classpath 模板渲染 →
 * executeSync → strip fence 容错解析 → BizException。类不加事务：LLM 调用耗时较长，
 * 不能占用数据库事务；单条消息落库走 ServiceImpl 自带事务。</p>
 *
 * <p>LLM/解析失败时：user 消息保留（round_count 已加），抛 BizException，
 * 前端弹错后可重发一条消息重试；会话本身无状态回退需求（始终 ACTIVE）。</p>
 */
public interface RequirementClarifyService {

    /** 会话状态常量（与 V29 CHECK 约束对齐）。 */
    String STATUS_ACTIVE = "ACTIVE";
    String STATUS_FINALIZED = "FINALIZED";
    String STATUS_ABANDONED = "ABANDONED";

    /** 对话模式常量（与 V39 CHECK 约束对齐）：CHAT 自由对话 / CLARIFY 方案澄清。 */
    String MODE_CHAT = "CHAT";
    String MODE_CLARIFY = "CLARIFY";

    /** V40 意图词命中后的固定确认询问文案（服务端直发，不调 LLM、不消耗轮数）。 */
    String CONFIRM_ASK_MESSAGE =
            "我注意到你想把这段对话整理成方案。回复「确认」将进入方案澄清模式，"
                    + "我会基于全部对话内容梳理需求并产出方案草案；回复其他内容则继续自由对话。";

    /**
     * 新建澄清会话：首条用户消息截断为标题 → 存 user 消息 → 走一轮 LLM。
     *
     * @param plannerAgentId   手动指定的 Planner Agent ID（空=系统自动选择）；
     *                         指定时严格校验可选性，澄清与后续拆解均跟随该 Planner
     * @param webSearchEnabled 会话级联网搜索开关（V34 新增；NULL=默认开启）；
     *                         每轮 LLM 调用前若 true 服务端会预检索行业资料并注入
     *                         {@code {{WEB_SEARCH_CONTEXT}}} 占位符，失败降级跳过
     * @return 会话 + 全部消息
     */
    ClarifyConversationDetail create(String firstMessage, Long plannerAgentId, Boolean webSearchEnabled);

    /**
     * 新建会话（V39 双模式入口）。
     *
     * @param initialMode 初始对话模式（V39）：'CHAT'=自由对话（缺省）/ 'CLARIFY'=方案澄清快捷直达；
     *                    非法值抛 BizException
     * @return 会话 + 全部消息
     */
    ClarifyConversationDetail create(String firstMessage, Long plannerAgentId, Boolean webSearchEnabled,
                                     String initialMode);

    /** 兼容重载：旧调用方未传开关时默认开启（NULL 代表默认开启，与老数据语义一致）。 */
    ClarifyConversationDetail create(String firstMessage, Long plannerAgentId);

    /**
     * 向会话追加一条用户消息并走一轮 LLM 澄清（纯文本，无选项快照）。
     *
     * @return 会话 + 全部消息
     */
    ClarifyConversationDetail sendMessage(Long conversationId, String message);

    /**
     * 向会话追加一条用户消息并走一轮 LLM 澄清。
     *
     * @param selections 结构化选项回答快照（V33，可为 null/空=纯文本回答）；
     *                   序列化为 {@code {"selections":[...]}} 存入 user 消息 payload，
     *                   仅作前端回显快照，LLM 上下文仍用 content 可读文本
     * @return 会话 + 全部消息
     */
    ClarifyConversationDetail sendMessage(Long conversationId, String message,
                                          List<ClarifySelection> selections);

    /**
     * 重试上一轮 LLM：仅当最后一条消息是 user（即上轮 LLM 失败、助手回复缺失）时可用；
     * 不新增 user 消息、不加轮数（失败那轮已计入 round_count）。
     *
     * @return 会话 + 全部消息
     */
    ClarifyConversationDetail retryRound(Long conversationId);

    /** Planner 下拉选数据源（平台内 PLANNER 可选 + 在班外部 Agent 置灰）。 */
    List<PlannerAgentPicker.PlannerOption> listPlannerOptions();

    /**
     * 终稿确认：创建 Task（PENDING）→ best-effort 通知 PLANNER →
     * 会话回填 task_id、状态 FINALIZED → timeline 记录。
     *
     * @return 创建的任务
     */
    Task finalize(Long conversationId);

    /**
     * 重新生成任务：会话已 FINALIZED 且原任务已被删除时，复用会话终稿重建 PENDING Task，
     * 回填新的 task_id（会话保持 FINALIZED）。前端随后自动调 plan 拆解并打开草案审阅。
     *
     * <p>不放开 ACTIVE 校验，也不重跑 LLM：仅在“终稿仍在、任务已被清理”的悬挂场景下重建，
     * 避免误覆盖仍存活的任务。</p>
     *
     * @return 重新创建的任务
     */
    Task regenerate(Long conversationId);

    /** 放弃会话：ACTIVE → ABANDONED。 */
    void abandon(Long conversationId);

    /**
     * 切换到方案澄清模式（V40.2 斜杠命令路径）：先落库附加文本（用户消息，进 LLM 上下文），
     * 再切 CLARIFY 并跑一轮澄清（V40.1 首轮强制 structured → 推荐卡片必出）。
     * 附加文本不走意图词/确认词判定、不设 payload。
     *
     * @param extraMessage 斜杠命令后的附加文本；空/空白则不加消息（与既有 switchToClarify 等价）
     */
    ClarifyConversationDetail switchToClarify(Long conversationId, String extraMessage);

    /**
     * 切换到方案澄清模式（V39）：置位落库 + 一轮 LLM 基于全量历史产终稿草案/结构化追问。
     *
     * @return 会话 + 全部消息
     */
    ClarifyConversationDetail switchToClarify(Long conversationId);

    /**
     * 切回自由对话模式（V39）：仅置位，不调用 LLM；
     * 历史消息全部保留，后续切回 CLARIFY 时作为全量澄清上下文。
     *
     * @return 会话 + 全部消息
     */
    ClarifyConversationDetail switchToChat(Long conversationId);

    /** 会话列表（按创建时间倒序，LIMIT 50，首期不分页）。 */
    List<RequirementConversation> listConversations();

    /** 会话详情：会话 + 消息按 seq 升序。 */
    ClarifyConversationDetail detail(Long conversationId);

    /** 会话 + 全部消息的组合视图（create/sendMessage/detail 统一返回）。 */
    @Data
    class ClarifyConversationDetail {
        private final RequirementConversation conversation;
        private final List<RequirementMessage> messages;
        /** 会话关联任务是否仍存在（仅 detail 计算填充）；前端据此判断 FINALIZED 会话能否重新生成。 */
        private Boolean taskExists;
    }

    /** LLM 结构化输出（未知字段容忍）：type=question 追问（双模） / type=final 终稿。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    class ClarifyReply {
        private String type;
        /** 追问模式：structured 结构化选项式 / freeform 自由文本（缺省按 freeform） */
        private String mode;
        /** LLM 对需求澄清程度的 0~100 自评（仅展示，不做任何业务分支） */
        private Integer progress;
        private String message;
        private String title;
        private String description;
        /** mode=structured 时的问题列表 */
        private List<ClarifyQuestion> questions;
    }

    /** 结构化追问单题（V33）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    class ClarifyQuestion {
        private String id;
        private String text;
        /** 是否多选 */
        private Boolean multiple;
        /** 是否允许自定义文本补充 */
        private Boolean allowCustom;
        private String customPlaceholder;
        private List<ClarifyOption> options;
    }

    /** 结构化追问选项（V33）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    class ClarifyOption {
        private String label;
        private String value;
        /** 权重预留字段：schema 容忍透传，当前无业务消费 */
        private Integer weight;
        /** LLM 推荐选项标记（每题最多一个） */
        private Boolean recommended;
    }

    /** 用户结构化选项回答快照（user 消息 payload 的 selections 元素）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    class ClarifySelection {
        private String questionId;
        private String questionText;
        /** 选中选项的 value 列表 */
        private List<String> values;
        /** 选中选项的 label 列表（回显用） */
        private List<String> labels;
        /** 是否含自定义补充 */
        private Boolean custom;
        private String customText;
    }
}
