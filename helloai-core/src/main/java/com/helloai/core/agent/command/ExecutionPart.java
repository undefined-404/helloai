package com.helloai.core.agent.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import lombok.Data;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 执行产物分片（{@code submitResult.parts} 元素）——ADR-009 §8.6 / {@code AQ-7} 方案 A。
 *
 * <p><b>为什么存在</b>：A2A 的产物是 {@code Artifact{artifactId, name, parts[]}}，而本仓
 * {@code submitResult.output} 是纯 {@code String}。第一批只补「带类型的分片」这一维：
 * {@code parts} 落 {@code sub_task.context.lastExecution.parts}（JSONB，不加表不加列），
 * 存在时 {@code output} 退化为其纯文本投影（唯一投影实现见 {@link #projectToText(List)}）。</p>
 *
 * <p><b>第一批只收 {@link #TYPE_TEXT} / {@link #TYPE_DATA}</b>：{@link #TYPE_FILE} 在
 * 协议适配层（{@code McpToolServiceImpl#submitResult}）显式 {@code BizException(400)} 拒收
 * ——外部提交了文件分片而平台不知该放哪个桶时，静默丢弃比报错更糟；文件分片统一走既有
 * {@code uploadArtifact}（只收平台桶内 {@code minio://} 引用），物化成 attachment 推第二批。</p>
 *
 * <p><b>体积口径</b>：单分片正文上限复用 {@link AttachmentContentPolicy#OUTPUT_SUMMARY_LIMIT}
 * （64k，不造第二常量）；分片数上限 {@link #MAX_PARTS} ⇒ 单行 JSONB 上界约 1.28M 字符。
 * <b>超限一律 400 拒收，不静默截断</b>——这是外部协议输入，静默截断会让调用方以为提交完整。</p>
 *
 * <p>字段与 A2A 分片对齐但只取第一批所需：{@code type} 必填；{@code text} 用于 text 分片；
 * {@code data} 为 JSON 文本（data 分片，避免嵌套结构在 MCP 工具 schema 里难以描述）；
 * {@code mimeType} / {@code fileName} 为可选标注（file 分片第二批启用）。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecutionPart {

    /** 纯文本分片：{@link #text} 必填。 */
    public static final String TYPE_TEXT = "text";
    /** 结构化数据分片：{@link #data} 为 JSON 文本，投影时包成 ```json 代码块。 */
    public static final String TYPE_DATA = "data";
    /** 文件分片：第一批显式拒收（见类注释），第二批物化成 attachment。 */
    public static final String TYPE_FILE = "file";

    /**
     * 分片数量上限。
     *
     * <p>单分片正文 ≤ {@link AttachmentContentPolicy#OUTPUT_SUMMARY_LIMIT} 时，
     * 20 片对应 JSONB 单行约 1.28M 字符——已远超正常交付物，留作防失控上界而非能力边界。</p>
     */
    public static final int MAX_PARTS = 20;

    /**
     * 仅用于 REST / JSON-RPC 通道把请求体里的原生 {@code List<Map>} 转成本类型。
     *
     * <p>本类型只有字符串字段（无日期 / 枚举 / 数值），故用私有 mapper 而非注入全局
     * {@code ObjectMapper}：避免为一个纯字符串转换把 Jackson 配置耦合进协议 DTO；
     * 关闭「未知字段报错」以允许调用方多传字段（前向兼容）。</p>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** text / data / file（第一批只收 text、data）。 */
    private String type;
    /**
     * text 分片正文。
     *
     * <p>{@link Nullable} 不是装饰：spring-ai 生成工具入参 JSON Schema 时把「无 @Nullable
     * 标注的字段」一律写进 {@code required}——不加会让 {@code [{type:"text",text:"..."}]}
     * 这种合法分片被客户端按 schema 判定为「缺 data/fileName/mimeType」而拒绝。</p>
     */
    @Nullable
    private String text;
    /** data 分片的 JSON 文本（{@link Nullable}：与 {@link #text} 互斥，见其注释）。 */
    @Nullable
    private String data;
    /** 可选 MIME 标注（file 分片第二批启用）。 */
    @Nullable
    private String mimeType;
    /** 可选文件名标注（file 分片第二批启用）。 */
    @Nullable
    private String fileName;

    /**
     * 把请求体里的原生结构（{@code List<Map<String,Object>>}）转成分片列表。
     *
     * <p>供 REST / JSON-RPC 两条通道复用；SSE 工具面由框架直接绑定强类型，不走本方法。
     * 结构非法时显式 400（用户可纠正的入参错误），不静默丢字段。</p>
     *
     * @param raw 请求体里的 {@code parts} 原值；null / 空集合返回 null（= 旧协议，无分片）
     */
    public static List<ExecutionPart> fromRaw(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return MAPPER.convertValue(raw, new TypeReference<List<ExecutionPart>>() {
            });
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "parts 结构非法：" + e.getMessage());
        }
    }

    /**
     * 分片 → 纯文本投影（<b>唯一实现</b>，写侧单点 {@code ExecutionResultHandler} 调用）。
     *
     * <p>投影规则：text 分片原样拼接；data 分片包成 ```json 代码块；file 分片不产出内容
     * （协议层已拒收，此处仅防御历史/异常数据）。分片之间用空行分隔。</p>
     *
     * <p><b>为什么投影必须只有一份</b>：{@code output} 是 {@code parts} 的派生值，若读侧
     * 再写一份投影，同一事实就有了两个事实源（双写必分叉）。读侧统一读 {@code output}。</p>
     *
     * @return 投影文本；无任何可投影分片时返回 null（调用方回落到 {@code report.output}）
     */
    public static String projectToText(List<ExecutionPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ExecutionPart part : parts) {
            if (part == null || part.getType() == null) {
                continue;
            }
            String block = switch (part.getType().trim()) {
                case TYPE_TEXT -> part.getText();
                case TYPE_DATA -> part.getData() == null ? null : "```json\n" + part.getData() + "\n```";
                default -> null;
            };
            if (block == null || block.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(block);
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
