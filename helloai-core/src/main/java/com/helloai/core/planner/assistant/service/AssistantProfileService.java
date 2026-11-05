package com.helloai.core.planner.assistant.service;

/**
 * 助理身份读取服务（PA-3）。
 *
 * <p>对话页头部身份卡后端。首期只做<b>单助理展示</b>（不做人格市场），且无写入入口
 * （表多为空）——服务给「默认值兜底」，无记录/读取失败都返回一个可渲染的默认身份，前端
 * 永远能画身份卡，不因表空而失败。</p>
 */
public interface AssistantProfileService {

    /** 无任何记录的默认身份（数据库为空 / 读取异常时的渲染兜底）。 */
    AssistantSnapshot DEFAULT = new AssistantSnapshot(
            "AI 助手", "你的个人助理", "deepseek-v4-flash",
            java.util.List.of(), "你好，我是你的 AI 助理，有什么可以帮你？");

    /** 对话页顶部要渲染的助理身份快照（只读，不暴露实体形态）。 */
    record AssistantSnapshot(String name, String identity, String model,
                             java.util.List<Long> skillIds, String greeting) {
    }

    /**
     * 取当前用户的助理身份（用户级优先，回退平台预置）。
     *
     * @param userId 当前登录用户 id；null = 未登录（仅取平台预置或默认兜底）
     * @return 用户级 / 平台预置 / 默认值；永不返回 null
     */
    AssistantSnapshot getActiveByUser(Long userId);
}