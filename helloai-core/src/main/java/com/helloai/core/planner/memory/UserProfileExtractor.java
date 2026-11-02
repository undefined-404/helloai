package com.helloai.core.planner.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;

/**
 * 用户画像规则抽取器（PA-2b，纯函数可单测）。
 *
 * <p><b>首版确定性实现</b>：只认<b>用户显式说出的偏好</b>（「记住我…」「我偏好…」
 * 「请用…风格」等句式），抽成结构化字段放 {@code profile_json}。对齐
 * {@link ConversationMemorySummarizer}「规则抽取打底、逻辑替换点」范式——接口留
 * {@link #extract(String)}，后续可换 LLM Provider 而调用方不变。</p>
 *
 * <p><b>为什么只抽显式句式</b>：画像抽错比不抽更伤用户信任。隐含偏好（「我每次都…」）
 * 需要全文语义理解，交给后续 LLM Provider，不在此首版强求。</p>
 *
 * <p><b>字段约定</b>（profile_json 键）：{@code name}=用户称呼、
 * {@code role}=身份/角色、{@code preferences}=偏好列表（String[]）、
 * {@code style}=沟通风格、{@code note}=其他备注。无命中任何句式时返回空 Map（调用方跳过）。</p>
 */
public final class UserProfileExtractor {

    private UserProfileExtractor() {
    }

    /** 结果字段键（与 {@code profile_json} 约定一致）。 */
    public static final String KEY_NAME = "name";
    public static final String KEY_ROLE = "role";
    public static final String KEY_PREFERENCES = "preferences";
    public static final String KEY_STYLE = "style";
    public static final String KEY_NOTE = "note";

    /** 「记住我」族句式：后面跟的是一个要长期记住的事实/偏好。 */
    private static final Pattern[] REMEMBER_PATTERNS = {
            Pattern.compile("记住(?:我)?[，,:：]?\\s*(.+)"),
            Pattern.compile("请记住[，,:：]?\\s*(.+)"),
            Pattern.compile("(?:我)?希望?你记住[，,:：]?\\s*(.+)")
    };

    /** 「我偏好/我喜欢」族：抽出偏好内容。 */
    private static final Pattern[] PREFERENCE_PATTERNS = {
            Pattern.compile("(?:我)?偏好[，,:：]?\\s*(.+)"),
            Pattern.compile("(?:我)?喜欢[，,:：]?\\s*(.+)"),
            Pattern.compile("(?:我)?更喜欢[，,:：]?\\s*(.+)")
    };

    /** 「请用 X 风格/语气」：沟通风格。 */
    private static final Pattern STYLE_PATTERN =
            Pattern.compile("(?:请)?(?:用|以|采用)([^，。！？，,]+)(风格|语气|口吻|方式)");

    /** 结构化偏好条目（一次抽取产物）。 */
    public record ExtractedFact(String key, String value) {
    }

    /**
     * 从一段 user 消息抽取画像事实（去重：同一 key 保留首次值）。
     *
     * @param text 用户消息原文（可为 null/空白 → 空列表）
     * @return 命中的画像字段；无命中返回空 Map
     */
    public static Map<String, Object> extract(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        String t = text.trim();
        Map<String, Object> out = new LinkedHashMap<>();

        List<String> preferences = new ArrayList<>();
        for (Pattern p : REMEMBER_PATTERNS) {
            Matcher m = p.matcher(t);
            if (m.matches() && m.group(1) != null) {
                String fact = m.group(1).trim();
                if (!fact.isBlank()) {
                    // 「记住我」偏向自由备注；先落入 note，后续字段化可由 LLM Provider 细分
                    putOnce(out, KEY_NOTE, fact);
                }
            }
        }
        for (Pattern p : PREFERENCE_PATTERNS) {
            Matcher m = p.matcher(t);
            if (m.matches() && m.group(1) != null) {
                String pref = m.group(1).trim();
                if (!pref.isBlank()) {
                    preferences.add(pref);
                }
            }
        }
        if (!preferences.isEmpty()) {
            out.put(KEY_PREFERENCES, preferences);
        }
        Matcher style = STYLE_PATTERN.matcher(t);
        if (style.find() && style.group(1) != null) {
            String s = style.group(1).trim();
            if (!s.isBlank()) {
                putOnce(out, KEY_STYLE, s);
            }
        }
        return out;
    }

    /** 只在键尚未存在时写入（同段消息多个匹配取首个，简单确定性）。 */
    private static void putOnce(Map<String, Object> map, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        map.putIfAbsent(key, value);
    }
}