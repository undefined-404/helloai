package com.helloai.core.planner.assistant;

import com.helloai.core.planner.assistant.service.AssistantProfileService.AssistantSnapshot;
import com.helloai.core.planner.assistant.service.ConversationHistorySearchService.HistoryHit;
import com.helloai.core.planner.memory.entity.LongTermMemory;

import java.util.List;

/**
 * 助理对话 system prompt 拼装（PA-11，纯函数，无 IO/DB）。
 *
 * <p><b>职责分层</b>（对齐 QwenPaw）：检索到的历史 = 事实依据；长期记忆/画像 = 精选参考；
 * 二者冲突以历史为准。助理不产出任务需求草案——建任务由显式入口触发。</p>
 */
public final class AssistantPromptBuilder {

    private static final int MEMORY_SNIPPET_MAX = 300;

    private AssistantPromptBuilder() {
    }

    public static String build(AssistantSnapshot profile,
                               List<LongTermMemory> memories,
                               List<HistoryHit> recentWork,
                               List<HistoryHit> related,
                               String conversationSummary,
                               List<String> recentConversation) {
        return build(profile, memories, recentWork, related, conversationSummary, recentConversation,
                null, null, null);
    }

    /**
     * 全量版本：额外注入「联网资料」「会话附件」「本轮技能」三段。
     *
     * @param webSearchContext 联网检索到的资料文本（空 = 本轮未联网或无命中）
     * @param attachmentContext 会话附件的清单 + 正文（空 = 本会话无附件）
     * @param skillInstruction 本轮技能约束段（空 = 本轮无技能）
     */
    public static String build(AssistantSnapshot profile,
                               List<LongTermMemory> memories,
                               List<HistoryHit> recentWork,
                               List<HistoryHit> related,
                               String conversationSummary,
                               List<String> recentConversation,
                               String webSearchContext,
                               String attachmentContext,
                               String skillInstruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是「").append(nullSafe(profile.name())).append("」，")
                .append(nullSafe(profile.identity())).append("。\n");
        sb.append("这是你的个人助理对话。请用中文简洁回答，并基于下面检索到的历史信息作答：\n");
        sb.append("- 【历史记录】是事实依据；【用户记忆】是精选参考，二者冲突时以历史记录为准。\n");
        sb.append("- 不要臆造未出现的信息；不确定时直接说明。\n");
        sb.append("- 除非用户明确要求，不要生成任务需求草案或替用户建任务。\n");
        if (conversationSummary != null && !conversationSummary.isBlank()) {
            sb.append("\n【本会话此前对话摘要】\n").append(truncate(conversationSummary, 1500)).append('\n');
        }
        if (recentConversation != null && !recentConversation.isEmpty()) {
            sb.append("\n【本会话最近对话】\n");
            for (String line : recentConversation) {
                sb.append(line).append('\n');
            }
        }
        appendMemories(sb, memories);
        appendHits(sb, "最近处理过的工作", recentWork);
        appendHits(sb, "与本次问题相关的历史对话", related);
        appendWebSearch(sb, webSearchContext);
        appendAttachments(sb, attachmentContext);
        if (skillInstruction != null && !skillInstruction.isBlank()) {
            sb.append('\n').append(skillInstruction.trim()).append('\n');
        }
        return sb.toString().trim();
    }

    /** 联网资料段：只在开关开启且有命中时注入，避免出现空标题误导模型「搜过但没结果」。 */
    private static void appendWebSearch(StringBuilder sb, String webSearchContext) {
        if (webSearchContext == null || webSearchContext.isBlank()) {
            return;
        }
        sb.append("\n【联网检索到的资料】\n");
        sb.append(webSearchContext.trim()).append('\n');
    }

    /** 会话附件段：清单 + 正文一体注入（正文由附件渲染器按预算裁剪）。 */
    private static void appendAttachments(StringBuilder sb, String attachmentContext) {
        if (attachmentContext == null || attachmentContext.isBlank()) {
            return;
        }
        sb.append("\n【本会话附件】\n");
        sb.append(attachmentContext.trim()).append('\n');
    }

    private static void appendMemories(StringBuilder sb, List<LongTermMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return;
        }
        sb.append("\n【用户记忆（精选参考）】\n");
        for (LongTermMemory memory : memories) {
            sb.append("- ").append(nullSafe(memory.getTitle())).append("：")
                    .append(truncate(memory.getContent(), MEMORY_SNIPPET_MAX)).append('\n');
        }
    }

    private static void appendHits(StringBuilder sb, String title, List<HistoryHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        sb.append("\n【").append(title).append("】\n");
        for (HistoryHit hit : hits) {
            sb.append("- [").append(nullSafe(hit.conversationTitle())).append("] ")
                    .append(nullSafe(hit.role())).append("：")
                    .append(nullSafe(hit.snippet())).append('\n');
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}