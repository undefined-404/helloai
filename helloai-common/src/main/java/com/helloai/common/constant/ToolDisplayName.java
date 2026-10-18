package com.helloai.common.constant;

/**
 * 工具名 → 中文展示名目录（**服务端单一事实源**，2026-10-18）。
 *
 * <p><b>为什么需要</b>：时间线/时序图里直接展示 {@code internal_run_in_sandbox}、
 * {@code internal_get_sub_task_detail} 这类英文标识，非技术用户无法理解其含义。
 * 这类「代码 → 人话」的映射必须只有一处维护（参照 {@link TimelineEventCode} 的做法：
 * 前端不保留硬编码副本，经 {@code /api/tools/catalog} 拉取），否则新增工具时
 * 前后端必然漂移。</p>
 *
 * <p><b>收录口径</b>：只收<b>会出现在执行链事件里的工具</b>（内部执行链 {@code internal_*}
 * 与对外 MCP 工具）。label 取 4~6 字的动作短语（放进时间线标题不换行）；
 * desc 一句话说明「做什么」，供需要更详细解释的场景使用。</p>
 *
 * <p><b>与工具描述的关系</b>：工具自身的 {@code @Tool(description=…)} 是<b>给模型看</b>的
 * （含何时使用 / Gotchas / 相关工具），冗长且是提示词的一部分；本目录是<b>给人看</b>的短名，
 * 两者用途不同，不可互相替代。</p>
 */
public enum ToolDisplayName {

    // ── 内部执行链专用（internal_ 前缀，仅进程内 Runtime 可见） ──
    INTERNAL_GET_SUB_TASK_DETAIL("internal_get_sub_task_detail", "读子任务详情", "读取当前子任务的执行内容、交付物、验收标准与附件清单"),
    INTERNAL_GET_DEPS_SUMMARY("internal_get_deps_summary", "读前置产出", "查看前置依赖是否就绪、各自产出了什么"),
    INTERNAL_GET_AGENT_STATUS("internal_get_agent_status", "查自身状态", "确认当前 Agent 的启用状态与在线态"),
    INTERNAL_REGISTER_OUTPUT_ATTACHMENT("internal_register_output_attachment", "登记产出附件", "把本轮产出的独立文件登记为子任务产物"),
    INTERNAL_READ_ATTACHMENT("internal_read_attachment", "读附件正文", "读取子任务或其前置产物的附件文本内容"),
    INTERNAL_LIST_SKILLS("internal_list_skills", "列技能目录", "列出平台全部可用技能（名称与简介）"),
    INTERNAL_LOAD_SKILL("internal_load_skill", "加载技能", "按名称读取某技能的完整规范正文"),
    INTERNAL_REPORT_BLOCKED("internal_report_blocked", "上报阻塞", "确认无能力完成时上报阻塞，转人工介入"),
    INTERNAL_RUN_IN_SANDBOX("internal_run_in_sandbox", "沙箱执行", "在隔离沙箱容器内执行命令/脚本以验证产出"),

    // ── 对外 MCP 工具（执行 Agent 与外部系统共用） ──
    WEB_SEARCH("web_search", "联网搜索", "检索外部实时资料（行业、竞品、技术方案、新闻）"),
    GET_SUB_TASK_DETAIL("getSubTaskDetail", "读子任务详情", "读取子任务内容与附件清单（外部面）"),
    GET_DEPS_SUMMARY("getDepsSummary", "读前置产出", "查看前置依赖产出摘要（外部面）"),
    GET_AGENT_STATUS("getAgentStatus", "查自身状态", "查询 Agent 状态（外部面）"),
    CLAIM_SUB_TASK("claimSubTask", "认领子任务", "把待执行子任务认领到自己名下"),
    START_SUB_TASK("startSubTask", "开始子任务", "标记子任务进入执行中"),
    SUBMIT_RESULT("submitResult", "提交产出", "提交本轮执行结果"),
    REPORT_BLOCKED("reportBlocked", "上报阻塞", "上报无法完成并请求人工介入（外部面）"),
    UPLOAD_ARTIFACT("uploadArtifact", "上传产出文件", "把产出文件上传为附件"),
    PULL_TASKS("pullTasks", "拉取待办任务", "拉取分配给自己的待执行任务"),
    ACK("ack", "确认指令", "确认已收到执行指令"),
    HEARTBEAT("heartbeat", "心跳上报", "上报在线心跳"),
    CHECK_IN("checkIn", "打卡上班", "Agent 打卡进入在岗状态"),
    CHECK_OUT("checkOut", "打卡下班", "Agent 打卡进入非在岗状态"),
    SEARCH_KNOWLEDGE("search_knowledge", "知识库检索", "在平台知识库中检索资料"),
    ;

    private final String toolName;
    private final String label;
    private final String desc;

    ToolDisplayName(String toolName, String label, String desc) {
        this.toolName = toolName;
        this.label = label;
        this.desc = desc == null ? "" : desc;
    }

    /** 工具原始标识（与 {@code @Tool(name=…)} 逐字一致）。 */
    public String toolName() {
        return toolName;
    }

    /** 人话短名（4~6 字，用于时间线标题/时序图节点）。 */
    public String label() {
        return label;
    }

    /** 一句话说明（用于展开详情或 tooltip）。 */
    public String desc() {
        return desc;
    }

    /**
     * 反向查：工具名 → 中文短名；未知工具返回 {@code null}
     * （调用方回退显示原英文名，绝不显示「未知工具」掩盖事实）。
     */
    public static String labelOf(String toolName) {
        if (toolName == null) {
            return null;
        }
        for (ToolDisplayName n : values()) {
            if (n.toolName.equals(toolName)) {
                return n.label;
            }
        }
        return null;
    }

    /** 反向查：工具名 → 说明；未知返回 {@code null}。 */
    public static String descOf(String toolName) {
        if (toolName == null) {
            return null;
        }
        for (ToolDisplayName n : values()) {
            if (n.toolName.equals(toolName)) {
                return n.desc;
            }
        }
        return null;
    }
}
