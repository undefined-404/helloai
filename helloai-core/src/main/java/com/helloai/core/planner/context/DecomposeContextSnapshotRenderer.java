package com.helloai.core.planner.context;

import com.helloai.common.constant.ChatAttachmentParseStatus;
import com.helloai.core.knowledge.KnowledgeBaseService;
import com.helloai.core.knowledge.KnowledgeSearchResult;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.attachment.ChatAttachmentPromptRenderer;
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
import java.util.Collections;
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

    /**
     * 附件清单条数上限（名称 + 类型 + 可读性，不含正文）。
     *
     * <p><b>`G-046`：本常量不再是独立数字</b>，直接引用
     * {@link ChatAttachmentPromptRenderer#MAX_INJECTED_ATTACHMENTS}。理由：这里的清单与
     * 注入侧渲染的是<b>同一个「会话附件清单」</b>，两处上限不同就会出现「拆解期看到 20 件、
     * 执行期模型只看到 5 张」这种同一个问题两种答案的状态（判据 4：同一事实只能一个事实源）。</p>
     */
    static final int MAX_ATTACHMENTS = ChatAttachmentPromptRenderer.MAX_INJECTED_ATTACHMENTS;

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
        return render(task, null);
    }

    /**
     * 渲染快照正文，附件段使用**调用方已经渲染好的清单**（`G-042`）。
     *
     * <p><b>为什么要有这个重载</b>：拆解链路需要把清单序号解析回附件 ID，而序号与实体的对应关系
     * 必须与写进 prompt 的那一份<b>逐字相同</b>。若本方法自己再查一次库，就等于把同一件事算两遍
     * （判据 32），差值全落在「两次查询之间用户又传了一张图」上。</p>
     *
     * @param task    当前待拆解任务
     * @param listing 已渲染的附件清单；{@code null} = 自行渲染（行为与单参重载一致）
     * @return 恒非空；任何小节取数失败只降级该小节
     */
    public String render(TaskView task, AttachmentListing listing) {
        AttachmentListing effective = listing != null ? listing : renderAttachmentListing(task);
        StringBuilder sb = new StringBuilder();
        sb.append("### 附件清单（会话输入，仅名称与可读性，不含正文）\n");
        sb.append(effective.text()).append('\n');
        sb.append("### 平台知识库命中（摘要，不是全文）\n");
        sb.append(renderKnowledge(task)).append('\n');
        sb.append("### 历史任务的子任务骨架（只作结构参考，不得照抄内容）\n");
        sb.append(renderHistory(task)).append('\n');
        sb.append("> 需求包见上文「需求包」小节，本快照不重复渲染。");
        return capTotal(sb.toString());
    }

    // ── 附件 ──────────────────────────────────────────────

    /**
     * 拆解期附件清单的**一次渲染产物**（`G-042`）：清单正文 + 与之一一对应的附件实体。
     *
     * <p><b>为什么必须一次产出、不能分两次取</b>（判据 32）：清单正文进 prompt 后，Planner 要跑
     * 十几分钟才回结果；若回结果后再查一次附件把「序号 → 附件 ID」解析出来，这十几分钟里用户
     * 新传的附件会插进查询结果的<b>头部</b>（{@code listLatestByConversation} 是倒序取最新），
     * 于是第 1 号从「竞品截图」变成「刚传的发票」——LLM 说「本子任务用附件 2」，落到库里却是另一张图。
     * 这个错误不会报错、不会告警，只是默默送错图，正是最难查的一类。故清单与实体的对应关系
     * <b>在渲染那一刻就冻结</b>，随 prompt 一起传下去。</p>
     *
     * @param text        清单 Markdown 片段（恒非空）
     * @param attachments 与清单序号 1..N 对齐的附件（按上传正序）；清单为空时为空表
     */
    public record AttachmentListing(String text, List<ChatAttachment> attachments) {

        /** 无附件的常量形态（省掉调用方的 null 判断）。 */
        public static AttachmentListing empty(String text) {
            return new AttachmentListing(text, List.of());
        }
    }

    /**
     * 渲染附件清单并同时返回对应实体（`G-042`）。
     *
     * <p>序号口径与对话面 {@code ChatAttachmentPromptRenderer} 完全一致：<b>1-based、上传正序</b>，
     * 与用户在界面上看到的顺序、与 {@code attachment_targets} 的序号同一份语义。不另立序，
     * 否则同一个会话在两处（对话面 / 拆解面）的「附件 2」会指向不同文件。</p>
     *
     * @param task 当前待拆解任务
     * @return 清单正文 + 对齐的附件列表（绝不返回 null）
     */
    public AttachmentListing renderAttachmentListing(TaskView task) {
        try {
            Long conversationId = conversationIdOf(task);
            if (conversationId == null) {
                return AttachmentListing.empty("- （该任务非澄清链路创建，无关联会话，故无会话附件）");
            }
            // `G-046`：取「最新的 N 件」而不是「最早的 N 件」。原实现先全量查（ASC）再截前 N，
            // 超限时被丢弃的恰恰是用户最新上传的——而拆解要理解的正是最新那批需求材料。
            List<ChatAttachment> latest = chatAttachmentService
                    .listLatestByConversation(conversationId, MAX_ATTACHMENTS);
            if (latest == null || latest.isEmpty()) {
                return AttachmentListing.empty(EMPTY_ATTACHMENTS);
            }
            List<ChatAttachment> attachments = new ArrayList<>(latest);
            // 转回上传正序：清单顺序须与用户界面、与 attachment_targets 的 1-based 序号一致
            Collections.reverse(attachments);
            // 序号只在「真正列出来的条目」上递增：文件名缺失的条目下面会被 continue 掉，
            // 若用 for 下标当序号，被跳过的条目会让后面的序号整体前移，与返回的列表错位。
            List<ChatAttachment> listed = new ArrayList<>(attachments.size());
            StringBuilder sb = new StringBuilder();
            for (ChatAttachment a : attachments) {
                if (a == null || a.getFileName() == null || a.getFileName().isBlank()) {
                    continue;
                }
                listed.add(a);
                sb.append("- 附件 ").append(listed.size()).append("：").append(a.getFileName())
                        .append("｜类型：").append(nullToDash(a.getFileType()))
                        .append("｜可读性：").append(readability(a.getParseStatus()))
                        .append('\n');
            }
            if (listed.isEmpty()) {
                return AttachmentListing.empty(EMPTY_ATTACHMENTS);
            }
            if (latest.size() >= MAX_ATTACHMENTS) {
                // 达到上限即可能还有更早的未列出——用「至少」而非「共」，不编造准确总数
                sb.append("- （本次展示最新 ").append(listed.size())
                        .append(" 件；若会话附件更多，更早的未列出）");
            }
            return new AttachmentListing(sb.toString().trim(), List.copyOf(listed));
        } catch (Exception e) {
            log.warn("拆解期快照：附件清单取数失败，已降级: err={}", e.getMessage());
            return AttachmentListing.empty("- （附件清单取数失败，按无附件处理）");
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
