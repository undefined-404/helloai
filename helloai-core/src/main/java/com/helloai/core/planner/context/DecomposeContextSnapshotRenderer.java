package com.helloai.core.planner.context;

import com.helloai.common.constant.ChatAttachmentParseStatus;
import com.helloai.core.knowledge.KnowledgeBaseService;
import com.helloai.core.knowledge.KnowledgeSearchResult;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.policy.TaskContextKeys;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 拆解期上下文快照渲染器（`TD-6`）。
 *
 * <p><b>解决的问题</b>：拆解时实际注入的信息只有 {@code title}（255 字）+ {@code description}
 * + 技能/难度/粒度 + 需求包，非代码任务等于<b>蒙着眼睛拆</b>——做「Q3 竞品分析」时它不知道
 * 有没有历史报告、附件里有什么、平台知识库里有没有相关材料。</p>
 *
 * <p><b>设计原则：快照只给「有什么」，不给「是什么」</b>（设计 §5.2）——</p>
 * <ul>
 *   <li>附件只给「名称 + 类型 + 可读性」，<b>不注入正文</b>；</li>
 *   <li>知识库只给「来源 + ≤100 字摘要」，<b>不注入全文</b>；</li>
 *   <li>历史任务只给「任务标题 + 子任务标题」，<b>给骨架不给内容</b>。</li>
 * </ul>
 * <p>理由有两条：全文注入会撑爆提示词预算；更关键的是会诱导 Planner <b>直接抄历史产出</b>
 * 而不是重新拆解——抄出来的计划看着合理，实际边界是别人任务的边界。</p>
 *
 * <p><b>需求包不在本渲染器重复注入</b>：它已由上文的 {@code {{REQUIREMENT_PACKAGE}}} 段承载。
 * 两处都注入会让同一事实双写、并重复占用预算（计划 §5.2 表格里把需求包列入快照，
 * 是为说明它的预算「沿用现预算」，不是要求重复渲染）。</p>
 *
 * <p><b>失败必须降级，不能击穿拆解</b>：整段 render 包在 try/catch 内，任何取数异常
 * 只让对应小节降级为占位文案（最坏情况整段为占位），绝不抛给拆解主链路——
 * 快照是「附加价值」，不是「前置条件」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecomposeContextSnapshotRenderer {

    /** 附件清单条数上限（名称 + 类型 + 可读性，不含正文）。 */
    static final int MAX_ATTACHMENTS = 20;

    /** 知识库命中条数上限。 */
    static final int MAX_KB_HITS = 5;

    /** 单条知识库摘要字符上限（超出截断并加省略号）。 */
    static final int MAX_KB_HIT_CHARS = 100;

    /** 历史任务条数上限。 */
    static final int MAX_HISTORY_TASKS = 5;

    /** 单个历史任务展示的子任务标题条数上限。 */
    static final int MAX_HISTORY_SUBTASK_TITLES = 6;

    /** 整段快照字符预算上限（超出即整体截断并显式标注，避免提示词膨胀）。 */
    static final int MAX_TOTAL_CHARS = 3000;

    private static final String EMPTY_ATTACHMENTS = "- （无与会话关联的附件）";
    private static final String EMPTY_KB = "- （知识库无命中，或知识库未启用）";
    private static final String EMPTY_HISTORY = "- （平台内暂无可参考的历史任务）";
    /**
     * 取数失败与「本来就没有」必须分开表述：前者对模型意味着「可能缺料，需要时可申报 `TO_LOOK_UP`」，
     * 后者意味着「确定没有」。合成一句会让 Planner 把「查不到」误读成「不存在」。
     */
    private static final String FAILED_KB = "- （知识库检索失败，按无命中处理；如需依赖外部资料请申报 TO_LOOK_UP）";
    private static final String FAILED_HISTORY = "- （历史任务取数失败，按无历史处理）";

    private final ChatAttachmentService chatAttachmentService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final TaskService taskService;
    private final SubTaskService subTaskService;

    /**
     * 渲染快照正文（Markdown 片段，供 {@code {{TASK_CONTEXT_SNAPSHOT}}} 占位符替换）。
     *
     * @param task 当前待拆解任务（只读快照）
     * @return 恒非空；任何小节取数失败只降级该小节
     */
    public String render(TaskView task) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 附件清单（会话输入，仅名称与可读性，不含正文）\n");
        sb.append(renderAttachments(task)).append('\n');
        sb.append("### 平台知识库命中（摘要，不是全文）\n");
        sb.append(renderKnowledge(task)).append('\n');
        sb.append("### 历史任务的子任务骨架（只作结构参考，不得照抄内容）\n");
        sb.append(renderHistory(task)).append('\n');
        sb.append("> 需求包见上文「需求包」小节，本快照不重复渲染。");
        return capTotal(sb.toString());
    }

    // ── 附件 ──────────────────────────────────────────────

    private String renderAttachments(TaskView task) {
        try {
            Long conversationId = conversationIdOf(task);
            if (conversationId == null) {
                return "- （该任务非澄清链路创建，无关联会话，故无会话附件）";
            }
            List<ChatAttachment> attachments = chatAttachmentService.listByConversation(conversationId);
            if (attachments == null || attachments.isEmpty()) {
                return EMPTY_ATTACHMENTS;
            }
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (ChatAttachment a : attachments) {
                if (a == null || a.getFileName() == null || a.getFileName().isBlank()) {
                    continue;
                }
                if (shown >= MAX_ATTACHMENTS) {
                    break;
                }
                sb.append("- ").append(a.getFileName())
                        .append("｜类型：").append(nullToDash(a.getFileType()))
                        .append("｜可读性：").append(readability(a.getParseStatus()))
                        .append('\n');
                shown++;
            }
            if (shown == 0) {
                return EMPTY_ATTACHMENTS;
            }
            if (attachments.size() > shown) {
                sb.append("- （共 ").append(attachments.size())
                        .append(" 件，仅展示前 ").append(shown).append(" 件）");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("拆解期快照：附件清单取数失败，已降级: err={}", e.getMessage());
            return "- （附件清单取数失败，按无附件处理）";
        }
    }

    /** 可读性口径与 {@link ChatAttachmentParseStatus} 一一对应；未知状态按「未知」如实标注。 */
    private static String readability(ChatAttachmentParseStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case READY -> "可读（正文可解析）";
            case PENDING, PARSING -> "解析中（正文暂不可用）";
            case FAILED -> "解析失败（正文不可用）";
            case SKIPPED -> "已跳过（读不出正文）";
        };
    }

    private static Long conversationIdOf(TaskView task) {
        Map<String, Object> context = task.context();
        if (context == null) {
            return null;
        }
        Object raw = context.get(TaskContextKeys.CONVERSATION_ID);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    // ── 知识库 ────────────────────────────────────────────

    private String renderKnowledge(TaskView task) {
        try {
            String query = (nullToEmpty(task.title()) + " " + nullToEmpty(task.description())).trim();
            if (query.isEmpty()) {
                return EMPTY_KB;
            }
            // 带作用域的重载：KB 未启用 / 嵌入失败均降级为空结果（不抛），正是快照所需语义
            KnowledgeSearchResult result = knowledgeBaseService.search(query, MAX_KB_HITS, null, null);
            if (result == null || result.hits() == null || result.hits().isEmpty()) {
                return EMPTY_KB;
            }
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (KnowledgeSearchResult.Hit hit : result.hits()) {
                if (hit == null || hit.content() == null || hit.content().isBlank()) {
                    continue;
                }
                if (shown >= MAX_KB_HITS) {
                    break;
                }
                sb.append("- ").append(nullToDash(hit.source()))
                        .append("：").append(summarize(hit.content()))
                        .append('\n');
                shown++;
            }
            return shown == 0 ? EMPTY_KB : sb.toString().trim();
        } catch (Exception e) {
            log.warn("拆解期快照：知识库检索失败，已降级: err={}", e.getMessage());
            return FAILED_KB;
        }
    }

    // ── 历史任务骨架 ──────────────────────────────────────

    private String renderHistory(TaskView task) {
        try {
            List<TaskView> recent = taskService.listRecentViews(MAX_HISTORY_TASKS + 1);
            if (recent == null || recent.isEmpty()) {
                return EMPTY_HISTORY;
            }
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (TaskView candidate : recent) {
                if (candidate == null || candidate.id() == null) {
                    continue;
                }
                // 排除自己：本次待拆解任务出现在「历史」里没有信息量，还会挤占预算
                if (task.id() != null && task.id().equals(candidate.id())) {
                    continue;
                }
                if (shown >= MAX_HISTORY_TASKS) {
                    break;
                }
                String titles = subtaskTitles(candidate.id());
                if (titles.isEmpty()) {
                    continue;
                }
                sb.append("- ").append(nullToDash(candidate.title())).append("：").append(titles).append('\n');
                shown++;
            }
            return shown == 0 ? EMPTY_HISTORY : sb.toString().trim();
        } catch (Exception e) {
            log.warn("拆解期快照：历史任务取数失败，已降级: err={}", e.getMessage());
            return FAILED_HISTORY;
        }
    }

    private String subtaskTitles(Long taskId) {
        try {
            List<SubTaskView> subTasks = subTaskService.listViewsByTaskId(taskId);
            if (subTasks == null || subTasks.isEmpty()) {
                return "";
            }
            List<String> titles = new ArrayList<>();
            for (SubTaskView st : subTasks) {
                if (st == null || st.title() == null || st.title().isBlank()) {
                    continue;
                }
                if (titles.size() >= MAX_HISTORY_SUBTASK_TITLES) {
                    titles.add("…");
                    break;
                }
                titles.add(st.title());
            }
            return String.join(" / ", titles);
        } catch (Exception e) {
            log.warn("拆解期快照：子任务骨架取数失败，已跳过: taskId={}, err={}", taskId, e.getMessage());
            return "";
        }
    }

    // ── 预算与字符串工具 ──────────────────────────────────

    private static String capTotal(String text) {
        if (text.length() <= MAX_TOTAL_CHARS) {
            return text;
        }
        return text.substring(0, MAX_TOTAL_CHARS)
                + "\n> （快照已按预算截断，共 " + text.length() + " 字，仅保留前 "
                + MAX_TOTAL_CHARS + " 字）";
    }

    private static String summarize(String content) {
        String oneLine = content.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_KB_HIT_CHARS ? oneLine : oneLine.substring(0, MAX_KB_HIT_CHARS) + "…";
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "未知" : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
