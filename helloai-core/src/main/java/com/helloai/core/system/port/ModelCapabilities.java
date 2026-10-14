package com.helloai.core.system.port;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型能力谓词只读快照（system 域对外契约，`M-9`）。
 *
 * <p>对应 {@code llm_provider_model.capabilities}(JSONB) 的 Java 形态：
 * <pre>{"input":[模态],"output":[模态],"reasoning":bool,"toolCalling":bool,"webSearch":bool}</pre></p>
 *
 * <p><b>解析归一（保守面）</b>：{@code input}/{@code output} 缺键 / 类型不符 / 空数组
 * ⇒ {@code ["text"]}；三个 bool 宽松解析（Boolean / Number / String，其余 false）；
 * <b>未知模态 token 原样保留</b>（词表不封闭 —— `V110` 注释声明的封闭词表
 * {@code {text,image,audio,video}} 与实测不符：{@code input} 含 {@code pdf} 共 44 行
 * ⇒ 解析侧必须开放，未知 token 保留、判定忽略、<b>不得抛错</b>）。</p>
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 {@code agent} 域高于提供方 {@code system} 域
 * ⇒ 契约落【提供方】{@code system.port}，与 {@link LlmProviderModelProfile} 同处。</p>
 */
public record ModelCapabilities(
        List<String> inputModalities,
        List<String> outputModalities,
        boolean reasoning,
        boolean toolCalling,
        boolean webSearch
) {

    /** 保守面模态（缺键 / 非法 / 空数组时的回退；对齐 `V110` 列默认）。 */
    private static final List<String> CONSERVATIVE_MODALITY = List.of("text");

    /** 由 JSONB 反序列化出的原始 Map 解析归一（模型不存在时由调用方返回 null）。 */
    public static ModelCapabilities fromRaw(Map<String, Object> raw) {
        Map<String, Object> src = raw == null ? Map.of() : raw;
        return new ModelCapabilities(
                normalizeModalities(src.get("input")),
                normalizeModalities(src.get("output")),
                looseBoolean(src.get("reasoning")),
                looseBoolean(src.get("toolCalling")),
                looseBoolean(src.get("webSearch")));
    }

    /**
     * 保守面谓词（新增模型未回填时的显式落库值，对齐 `V110` 列默认值）。
     *
     * <p>摘掉列默认值后（`V112`），任何 {@code INSERT} 都必须显式写谓词，否则 NOT NULL 违约。</p>
     */
    public static ModelCapabilities conservative() {
        return new ModelCapabilities(
                CONSERVATIVE_MODALITY, CONSERVATIVE_MODALITY, false, false, false);
    }

    /** 保守面的 JSONB 直写形态（供实体 set 落库）。 */
    public Map<String, Object> toRawMap() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("input", inputModalities);
        raw.put("output", outputModalities);
        raw.put("reasoning", reasoning);
        raw.put("toolCalling", toolCalling);
        raw.put("webSearch", webSearch);
        return raw;
    }

    /** 是否具备指定输入模态（未知 token 不影响判定）。 */
    public boolean supportsInput(String modality) {
        return inputModalities != null && inputModalities.contains(modality);
    }

    private static List<String> normalizeModalities(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return CONSERVATIVE_MODALITY;
        }
        List<String> tokens = new ArrayList<>();
        for (Object element : list) {
            if (element == null) {
                continue;
            }
            String token = String.valueOf(element).trim();
            if (!token.isEmpty()) {
                tokens.add(token);   // 未知 token 原样保留（不校验词表、不抛错）
            }
        }
        return tokens.isEmpty() ? CONSERVATIVE_MODALITY : tokens;
    }

    private static boolean looseBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return false;
    }
}
