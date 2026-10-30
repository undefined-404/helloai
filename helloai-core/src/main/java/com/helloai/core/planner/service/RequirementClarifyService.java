package com.helloai.core.planner.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.task.port.TaskView;
import lombok.Data;
import reactor.core.publisher.Flux;

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

    /** 会话状态常量（与 CHECK 约束对齐）。 */
    String STATUS_ACTIVE = "ACTIVE";
    String STATUS_FINALIZED = "FINALIZED";
    String STATUS_ABANDONED = "ABANDONED";

    /** 对话模式常量（与 CHECK 约束对齐）：CHAT 自由对话 / CLARIFY 方案澄清。 */
    String MODE_CHAT = "CHAT";
    String MODE_CLARIFY = "CLARIFY";

    /** 意图词命中后的固定确认询问文案（已迁移至 {@link com.helloai.core.planner.clarify.ConfirmCardProtocol#CONFIRM_ASK_TEXT}）。 */
    @Deprecated
    String CONFIRM_ASK_MESSAGE = com.helloai.core.planner.clarify.ConfirmCardProtocol.CONFIRM_ASK_TEXT;

    /**
     * 新建澄清会话：首条用户消息截断为标题 → 存 user 消息 → 走一轮 LLM。
     *
     * @param plannerAgentId   手动指定的 Planner Agent ID（空=系统自动选择）；
     *                         指定时严格校验可选性，澄清与后续拆解均跟随该 Planner
     * @param webSearchEnabled 会话级联网搜索开关（NULL=默认开启）；
     *                         每轮 LLM 调用前若 true 服务端会预检索行业资料并注入
     *                         {@code {{WEB_SEARCH_CONTEXT}}} 占位符，失败降级跳过
     * @return 会话 + 全部消息
     */
    ClarifyConversationDetail create(String firstMessage, Long plannerAgentId, Boolean webSearchEnabled);

    /**
     * 新建会话（双模式入口，已废弃：新会话始终 CHAT，initialMode 参数不再生效）。
     *
     * @param initialMode 已废弃，新会话始终以 CHAT 模式创建
     * @return 会话 + 全部消息
     * @deprecated 使用 {@link #create(String, Long, Boolean)} 替代
     */
    @Deprecated
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
     * @param selections 结构化选项回答快照（可为 null/空=纯文本回答）；
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

    /**
     * 编辑已发送的用户消息并重新生成回复（2026-10-19，对齐 ChatGPT/DeepSeek/Kimi 的
     * 「编辑消息 → 回溯截断 → 从该点重答」语义）。
     *
     * <p>实现：校验会话 ACTIVE + 目标消息存在且为 user 角色 → 物理删除该消息<b>之后</b>
     * 的所有消息（含旧回复与后续追问，避免旧上下文污染重跑）→ 更新该消息 content →
     * 走 {@link #retryRound} 同语义的重跑（不新增 user 消息、不加轮数）。</p>
     *
     * <p>仅支持编辑「消息列表里最后一条 user 消息」：更早的中间消息编辑涉及分支语义
     * （Open WebUI 的 parentId/childrenIds 树），本项目会话线性结构暂不支持，编辑更早
     * 消息直接抛 BizException 拒绝（防误删后续对话）。</p>
     *
     * @param conversationId 会话 ID
     * @param messageId      目标 user 消息 ID
     * @param newContent     编辑后的消息正文（非空）
     * @return 会话 + 全部消息（含重跑后的新回复）
     */
    ClarifyConversationDetail editMessage(Long conversationId, Long messageId, String newContent);

    /**
     * 流式对话轮（S1 最小闭环，Chat SSE）：以事件流返回一轮 CHAT 主回复。
     *
     * <p>与 {@link #sendMessage(Long, String, List)} 同语义的前置：校验会话、落 user 消息
     * 并 round+1、同步完成意图决策与搜索（S1 决策/搜索不流式），仅主回复 LLM 调用走
     * token 增量流；主回复完成后一次性落库并发出 {@code done} 事件（前端收到后拉
     * 会话详情对齐权威内容）。任何前置/流式异常统一转 {@code error} 事件，不抛出。</p>
     *
     * <p>S1 支持范围（与前端分流约定一致，服务端同样防御）：仅 CHAT 模式普通消息；
     * CLARIFY 模式、斜杠命令、确认卡待回答场景一律 error 事件拒绝（不消费消息）。</p>
     *
     * @param conversationId 会话 ID
     * @param message        用户消息（非空）
     * @param selections     结构化选项回答快照（可为 null；S1 流式下通常为 null）
     * @return token 增量事件流，恒以 {@link ChatStreamEvent.Type#DONE} 或
     *         {@link ChatStreamEvent.Type#ERROR} 收尾
     */
    Flux<ChatStreamEvent> streamRound(Long conversationId, String message, List<ClarifySelection> selections);

    /**
     * 流式新建会话并跑首轮（新会话首条消息的 SSE 入口）。
     *
     * <p>事件序：首帧 {@link ChatStreamEvent.Type#SESSION}（data=会话 id）→ token/reasoning 增量
     * → done/error。语义与同步 {@link #create(String, Long, Boolean)} 同构（建会话 + 跑首轮），
     * 仅传输形态不同；斜杠命令仍走同步（命令需要同步返回值驱动后续流转）。</p>
     */
    Flux<ChatStreamEvent> streamCreate(String message, Long plannerAgentId, Boolean webSearchEnabled);

    /**
     * 新建会话并认领新会话首句前上传的附件（{@code clientToken} 名下临时批次）。
     *
     * <p>默认实现退化为不带认领的 {@link #create(String, Long, Boolean)}：老调用方与测试替身
     * 不需要改；需要附件归属的入口（HTTP 层）覆写本方法。</p>
     *
     * @param clientToken 前端生成的新会话临时标识（可空）
     */
    default ClarifyConversationDetail createWithClientToken(String firstMessage, Long plannerAgentId,
                                                            Boolean webSearchEnabled, String clientToken) {
        return create(firstMessage, plannerAgentId, webSearchEnabled);
    }

    /** 流式新建会话 + 附件认领，语义同 {@link #createWithClientToken}。 */
    default Flux<ChatStreamEvent> streamCreateWithClientToken(String message, Long plannerAgentId,
                                                              Boolean webSearchEnabled, String clientToken) {
        return streamCreateWithClientToken(message, plannerAgentId, webSearchEnabled, clientToken, null);
    }

    /**
     * 流式新建会话 + 附件认领 + **本轮参与的附件集**（`ATT-3` 前端勾选）。
     *
     * @param attachmentIds 用户勾选的附件 id；空/null = 未指定，回落模型判定
     */
    default Flux<ChatStreamEvent> streamCreateWithClientToken(String message, Long plannerAgentId,
                                                              Boolean webSearchEnabled, String clientToken,
                                                              List<Long> attachmentIds) {
        return streamCreate(message, plannerAgentId, webSearchEnabled);
    }

    /**
     * 流式轮次 + **本轮参与的附件集**（`ATT-3` 前端勾选）。
     *
     * <p>显式勾选优先于模型判定：非空时只处理这些附件，模型不再参与选择。</p>
     *
     * @param attachmentIds 用户勾选的附件 id；空/null = 未指定，回落模型判定
     */
    default Flux<ChatStreamEvent> streamRound(Long conversationId, String message,
                                              List<ClarifySelection> selections,
                                              List<Long> attachmentIds) {
        return streamRound(conversationId, message, selections);
    }

    /** 流式轮事件：type 决定事件名（token / done / error），data 为载荷文本。 */
    record ChatStreamEvent(Type type, String data) {

        public enum Type {
            /**
             * 会话首帧（{@code streamCreate} 专用，data = 会话 id）。
             *
             * <p>新会话流式时必须先告诉前端「会话已建成、id 是多少」，否则 done 后前端
             * 无从拉 detail 对齐、也无法把后续消息发到正确会话。</p>
             */
            SESSION,
            /** 主回复增量文本块。 */
            TOKEN,
            /**
             * 思考过程增量文本块（推理模型的 reasoning / thinking，2026-10-18 新增）。
             *
             * <p>与 {@link #TOKEN} 分离：思考内容<b>只用于前端实时展示</b>，不并入正文、
             * 不参与落库与 JSON 解析——否则会污染澄清回复的解析与最终交付物。</p>
             */
            REASONING,
            /**
             * 过程步骤事件（2026-10-18 新增，data = 一行人类可读文本）。
             *
             * <p>平台侧动作的「思考过程」：如「正在联网检索：xxx」「已检索 15 条 · 21.5s」。
             * 与 {@link #REASONING} 同属过程区、按到达顺序混排；同样**不并入正文**，
             * 但会与 reasoning 一起随消息 payload 落库（{@code process} 键），done 后仍可回看。</p>
             */
            PROGRESS,
            /** 本轮完成（data 为 [DONE]，前端随后拉 detail 对齐落库）。 */
            DONE,
            /** 本轮失败（data 为错误描述；消息可能已落库，可走 retryRound 重试）。 */
            ERROR
        }

        public static ChatStreamEvent token(String text) {
            return new ChatStreamEvent(Type.TOKEN, text);
        }

        /** 会话首帧（streamCreate 专用）：data = 会话 id 字符串。 */
        public static ChatStreamEvent session(String conversationId) {
            return new ChatStreamEvent(Type.SESSION, conversationId);
        }

        /** 思考过程增量帧（前端渲染「思考中…」区域，done 后通常折叠）。 */
        public static ChatStreamEvent reasoning(String text) {
            return new ChatStreamEvent(Type.REASONING, text);
        }

        /** 过程步骤帧（联网检索等平台动作）：data 为一整行可读文本，前端追加到过程区。 */
        public static ChatStreamEvent progress(String text) {
            return new ChatStreamEvent(Type.PROGRESS, text);
        }

        public static ChatStreamEvent done() {
            return new ChatStreamEvent(Type.DONE, "[DONE]");
        }

        public static ChatStreamEvent error(String message) {
            return new ChatStreamEvent(Type.ERROR, message);
        }
    }

    /** Planner 下拉选数据源（平台内 PLANNER 可选 + 在班外部 Agent 置灰）。 */
    List<PlannerAgentPicker.PlannerOption> listPlannerOptions();

    /**
     * 终稿确认：创建 Task（PENDING）→ best-effort 通知 PLANNER →
     * 会话回填 task_id、状态 FINALIZED → timeline 记录。
     *
     * @return 创建的任务
     */
    TaskView finalize(Long conversationId);

    /**
     * 重新生成任务：会话已 FINALIZED 且原任务已被删除时，复用会话终稿重建 PENDING Task，
     * 回填新的 task_id（会话保持 FINALIZED）。前端随后自动调 plan 拆解并打开草案审阅。
     *
     * <p>不放开 ACTIVE 校验，也不重跑 LLM：仅在“终稿仍在、任务已被清理”的悬挂场景下重建，
     * 避免误覆盖仍存活的任务。</p>
     *
     * @return 重新创建的任务
     */
    TaskView regenerate(Long conversationId);

    /** 放弃会话：ACTIVE → ABANDONED。 */
    void abandon(Long conversationId);

    /**
     * 删除已放弃会话：仅 ABANDONED 可删（ACTIVE/FINALIZED 拒绝，FINALIZED 承载任务追溯保留）。
     * 会话与全部消息逻辑删除（deleted=1），列表查询侧全局过滤自动隐藏；删除不可恢复。
     */
    void delete(Long conversationId);

    /**
     * 切换到方案澄清模式（斜杠命令路径）：先落库附加文本（用户消息，进 LLM 上下文），
     * 再切 CLARIFY 并跑一轮澄清（首轮强制 structured → 推荐卡片必出）。
     * 附加文本不走意图词/确认词判定、不设 payload。
     *
     * @param extraMessage 斜杠命令后的附加文本；空/空白则不加消息（与既有 switchToClarify 等价）
     */
    ClarifyConversationDetail switchToClarify(Long conversationId, String extraMessage);

    /**
     * 切换到方案澄清模式：置位落库 + 一轮 LLM 基于全量历史产终稿草案/结构化追问。
     *
     * @return 会话 + 全部消息
     */
    ClarifyConversationDetail switchToClarify(Long conversationId);

    /**
     * 切回自由对话模式：仅置位，不调用 LLM；
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

        /**
         * 本轮技能执行的运行记录 id（SKX-13）；非技能轮为 {@code null}。
         *
         * <p>技能执行已改为<b>提交即返回</b>（实测一次 377s，不能再挂在 HTTP 线程上），
         * 所以响应里不再有产物 —— 前端拿到这个 id 就知道「去轮询状态」。</p>
         */
        private Long skillRunId;

        /** 技能执行状态（{@code PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED}）；非技能轮为 {@code null}。 */
        private String skillRunStatus;

        /** 技能执行阶段（{@code SPEC/RENDER/CHECK/EXPORT/DONE}）；非技能轮为 {@code null}。 */
        private String skillRunPhase;
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
        /**
         * 终稿需求包（G-011，可选）：goal / scope / outOfScope / assumptions / openQuestions 五字段。
         *
         * <p>用 JsonNode 承接：Jackson 对任意 JSON 形态（对象 / 字符串 / 数组）都能容忍，
         * 避免 {@code "package"} 非法类型直接击穿整轮 JSON 解析（设计要求"缺失 / 非法 →
         * null 降级纯文本终稿"）；解析动作由 {@code ClarifyReplyParser.resolvePackage} 承担。
         * 键名 {@code package} 为 Java 关键字，以 {@link JsonProperty} 映射。</p>
         */
        @JsonProperty("package")
        private JsonNode packageNode;
    }

    /** 结构化追问单题。 */
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

    /** 结构化追问选项。 */
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
