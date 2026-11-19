package com.helloai.api.dto.subtask;

import com.helloai.core.agent.command.ExecutionPart;
import com.helloai.core.agent.output.ExecutionRecordProtocol;

import java.util.List;
import java.util.Map;

/**
 * 子任务执行结果读模型组装器（{@code MA1-3}）——只做「选择 + 打包」，不重新投影。
 *
 * <p><b>装配规则固定四条，不留解释空间</b>：
 * <ol>
 *   <li>{@code format} = {@code lastExecution.parts} 非空 ? {@code "parts"} : {@code "markdown"}，不存在第三种判断；</li>
 *   <li>{@code markdown} 直接取 {@code lastExecution.output}，不重新拼装、不二次截断，仅剥离
 *       {@code EXECUTION_RECORD} 机器块（回填侧解析用的同一协议常量见 {@link ExecutionRecordProtocol}）；</li>
 *   <li>{@code parts} 直接取 {@code lastExecution.parts}，保持原序（有序数组，不按 type 重排）；</li>
 *   <li>{@code sections} 本期恒 {@code null}，字段保留作演进位。</li>
 * </ol></p>
 *
 * <p><b>为什么读时不重新按 parts 重算文本</b>：{@code output} 是写时算好并<b>冻结</b>的（
 * {@code ExecutionResultHandler} 单写点）；读时重算 = 同一事实两个算法，投影规则一改老数据就漂。
 * 本类只决定「用哪份事实」，不决定「怎么算」。</p>
 *
 * <p>纯函数：无 Spring 依赖、不查库。{@code lastExecution} 缺失 ⇒ {@code format:"markdown"} +
 * {@code markdown:null}，不得抛异常（由客户端按字段缺失兜底）。</p>
 */
public final class SubTaskExecutionResultAssembler {

    public static final String FORMAT_MARKDOWN = "markdown";
    public static final String FORMAT_PARTS = "parts";

    private SubTaskExecutionResultAssembler() {
    }

    /** @param context   子任务原始 context（{@code sub_task.context}，可空）
     *  @param subTaskId 子任务 ID（仅作回显，供前端定位） */
    public static SubTaskExecutionResultResponse assemble(Map<String, Object> context, Long subTaskId) {
        SubTaskExecutionResultResponse r = new SubTaskExecutionResultResponse();
        r.setSubTaskId(subTaskId);
        r.setSections(null);
        r.setTruncated(false);

        Map<String, Object> last = lastExecution(context);
        if (last == null) {
            r.setFormat(FORMAT_MARKDOWN);
            r.setMarkdown(null);
            r.setParts(null);
            return r;
        }

        Object partsObj = last.get("parts");
        List<ExecutionPart> parts = safeParts(partsObj);
        if (parts != null && !parts.isEmpty()) {
            r.setFormat(FORMAT_PARTS);
            r.setParts(parts);
            r.setMarkdown(null);
            return r;
        }

        r.setFormat(FORMAT_MARKDOWN);
        r.setParts(null);
        Object output = last.get("output");
        r.setMarkdown(output instanceof String s ? ExecutionRecordProtocol.strip(s) : null);
        return r;
    }

    private static Map<String, Object> lastExecution(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object last = context.get("lastExecution");
        return last instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /**
     * 把 {@code lastExecution.parts} 的存储原值（JSONB 读出的 {@code List<Map>} 或已泛化列表）规整为执行分片。
     *
     * <p>复用 {@link ExecutionPart#fromRaw(Object)}（与写侧同一 OOM 映射），避免读侧再自建一份反序列化；
     * <b>读取侧不抛错</b>——结构非法或元素无法识别时整体回退 null（由调用方落 markdown 分支），
     * 绝不因一条坏分片让整个端点 500。`null` / 空集合同样返回 null / 空，语义与写侧一致。</p>
     */
    private static List<ExecutionPart> safeParts(Object partsObj) {
        if (partsObj == null) {
            return null;
        }
        try {
            return ExecutionPart.fromRaw(partsObj);
        } catch (RuntimeException e) {
            return null;
        }
    }
}