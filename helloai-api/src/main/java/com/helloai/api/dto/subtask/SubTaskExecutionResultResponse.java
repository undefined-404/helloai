package com.helloai.api.dto.subtask;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.helloai.core.agent.command.ExecutionPart;
import lombok.Data;

import java.util.List;

/**
 * 子任务执行结果的公开读取契约（{@code MA1-2}）。
 *
 * <p><b>为什么存在</b>：{@code sub_task.context.lastExecution} 是平台内部状态，客户端不得直接依赖；
 * 本 DTO 是唯一对外读执行结果的形状——由 {@link SubTaskExecutionResultAssembler} 组装，只做「选择 + 打包」，
 * 不重新投影（读时按 parts 重算文本会与写时冻结的 output 分叉，见蓝图 §4 MA-1 ⑤）。</p>
 *
 * <p><b>契约</b>：{@code format} 二选一——有 {@code parts} 时为 {@code "parts"}，否则为 {@code "markdown"}；
 * 两者只出一个实测字段；{@code sections} 恒 {@code null}（演进位，本期不实现，任何客户端不得据此判断
 * 「支持分块渲染」）；{@code truncated} 恒 {@code false}（本批读模型不截断，DTO 预留位）。</p>
 *
 * <p>字段口径：{@code markdown} 直取已冻结的 {@code lastExecution.output} 并经 EXECUTION_RECORD 剥离；
 * {@code parts} 保原序（有序数组，不按 type 重排）；两者均缺时（无 lastExecution）为 null，
 * 由客户端按字段缺失兜底。</p>
 */
@Data
public class SubTaskExecutionResultResponse {

    /** 格式声明：{@code "markdown"} 或 {@code "parts"}，不存在第三种值。 */
    private String format;

    /** 已声明的格式为 {@code "markdown"} 时输出展示正文（EXECUTION_RECORD 机器块已剥离）；无产出时为 null。 */
    private String markdown;

    /** 已声明的格式为 {@code "parts"} 时输出有序分片（复用 core 的 ExecutionPart，不建平行 DTO）；无分片时为 null。 */
    private List<ExecutionPart> parts;

    /** 演进位，本期恒 null；填它时不改 format/markdown/parts 三个已有字段语义。 */
    private String sections;

    /** 预留位，本批读模型不截断，恒 false。 */
    private boolean truncated;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long subTaskId;
}