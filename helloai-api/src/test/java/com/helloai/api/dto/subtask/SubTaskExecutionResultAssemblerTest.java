package com.helloai.api.dto.subtask;

import com.helloai.core.agent.command.ExecutionPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SubTaskExecutionResultAssembler 单测（MA1-5）——读模型四条装配规则 + 缺失兜底 + EXECUTION_RECORD 剥离。
 *
 * <p>覆盖：有 parts / 无 parts / parts 为空数组 / lastExecution 缺失 / output 含尾部回填块 /
 * output 为 null / parts 为非法结构（回退 markdown 分支，不抛错）/(format 恒二选一)。</p>
 */
@DisplayName("SubTaskExecutionResultAssembler")
class SubTaskExecutionResultAssemblerTest {

    private static final Long SUB_TASK_ID = 123L;

    private static ExecutionPart textPart(String text) {
        ExecutionPart p = new ExecutionPart();
        p.setType(ExecutionPart.TYPE_TEXT);
        p.setText(text);
        return p;
    }

    private static ExecutionPart dataPart(String json) {
        ExecutionPart p = new ExecutionPart();
        p.setType(ExecutionPart.TYPE_DATA);
        p.setData(json);
        return p;
    }

    private static Map<String, Object> contextWithLast(List<ExecutionPart> parts, String output) {
        Map<String, Object> last = new LinkedHashMap<>();
        if (parts != null) {
            last.put("parts", parts);
        }
        if (output != null) {
            last.put("output", output);
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("lastExecution", last);
        return ctx;
    }

    @Test
    @DisplayName("有 parts：format=parts，parts 保持原提交顺序且不可重排")
    void assembleShouldCarryPartsWhenPresent() {
        Map<String, Object> ctx = contextWithLast(List.of(textPart("第一段"), dataPart("{\"a\":1}")), "拍平投影");
        SubTaskExecutionResultResponse r = SubTaskExecutionResultAssembler.assemble(ctx, SUB_TASK_ID);

        assertThat(r.getFormat()).isEqualTo("parts");
        assertThat(r.getParts()).hasSize(2);
        assertThat(r.getParts().get(0).getType()).isEqualTo("text");
        assertThat(r.getParts().get(0).getText()).isEqualTo("第一段");
        assertThat(r.getParts().get(1).getType()).isEqualTo("data");
        assertThat(r.getParts().get(1).getData()).isEqualTo("{\"a\":1}");
        assertThat(r.getMarkdown()).isNull();
        assertThat(r.getSubTaskId()).isEqualTo(SUB_TASK_ID);
        assertThat(r.getSections()).isNull();
        assertThat(r.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("无 parts：format=markdown，直取已冻结 output 且默认无回填块改动")
    void assembleShouldUseMarkdownWhenNoParts() {
        Map<String, Object> ctx = contextWithLast(null, "# 标题\n\n正文");
        SubTaskExecutionResultResponse r = SubTaskExecutionResultAssembler.assemble(ctx, SUB_TASK_ID);

        assertThat(r.getFormat()).isEqualTo("markdown");
        assertThat(r.getMarkdown()).isEqualTo("# 标题\n\n正文");
        assertThat(r.getParts()).isNull();
    }

    @Test
    @DisplayName("parts 为空数组：视为无 parts，落 markdown 分支")
    void assembleShouldFallToMarkdownWhenPartsEmpty() {
        Map<String, Object> ctx = contextWithLast(List.of(), "纯文本");
        SubTaskExecutionResultResponse r = SubTaskExecutionResultAssembler.assemble(ctx, SUB_TASK_ID);

        assertThat(r.getFormat()).isEqualTo("markdown");
        assertThat(r.getMarkdown()).isEqualTo("纯文本");
    }

    @Test
    @DisplayName("lastExecution 缺失：format=markdown + markdown=null，不抛异常")
    void assembleShouldHandleMissingLastExecution() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("someKey", "value");
        SubTaskExecutionResultResponse r = SubTaskExecutionResultAssembler.assemble(ctx, SUB_TASK_ID);

        assertThat(r.getFormat()).isEqualTo("markdown");
        assertThat(r.getMarkdown()).isNull();
        assertThat(r.getParts()).isNull();
    }

    @Test
    @DisplayName("context 整个为 null：同上兜底")
    void assembleShouldHandleNullContext() {
        SubTaskExecutionResultResponse r = SubTaskExecutionResultAssembler.assemble(null, SUB_TASK_ID);
        assertThat(r.getFormat()).isEqualTo("markdown");
        assertThat(r.getMarkdown()).isNull();
    }

    @Test
    @DisplayName("output 含尾部 EXECUTION_RECORD 回填块：剥离块，正文保留")
    void assembleShouldStripExecutionRecordTail() {
        String output = "实现了接口。\n\n## EXECUTION_RECORD\nSUMMARY: 说明\n";
        Map<String, Object> ctx = contextWithLast(null, output);
        SubTaskExecutionResultResponse r = SubTaskExecutionResultAssembler.assemble(ctx, SUB_TASK_ID);

        assertThat(r.getFormat()).isEqualTo("markdown");
        assertThat(r.getMarkdown()).isEqualTo("实现了接口。");
        assertThat(r.getMarkdown()).doesNotContain("EXECUTION_RECORD");
    }

    @Test
    @DisplayName("output 为 null：format=markdown + markdown=null")
    void assembleShouldHandleNullOutput() {
        Map<String, Object> ctx = contextWithLast(null, null);
        SubTaskExecutionResultResponse r = SubTaskExecutionResultAssembler.assemble(ctx, SUB_TASK_ID);

        assertThat(r.getFormat()).isEqualTo("markdown");
        assertThat(r.getMarkdown()).isNull();
    }

    @Test
    @DisplayName("parts 为 JSONB 读出的 List<Map>（线上真实形态）：仍按原序规整为分片")
    void assembleShouldAcceptJsonbMapParts() {
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("type", "text");
        p1.put("text", "第一段");
        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("type", "data");
        p2.put("data", "{\"a\":1}");
        // 线上路径：sub_task.context 由 JSONB 反序列化而来，parts 是 List<Map> 而非 POJO 列表
        List<Map<String, Object>> rawParts = List.of(p1, p2);
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("parts", rawParts);
        last.put("output", "拍平投影");
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("lastExecution", last);

        SubTaskExecutionResultResponse r = SubTaskExecutionResultAssembler.assemble(ctx, SUB_TASK_ID);

        assertThat(r.getFormat()).isEqualTo("parts");
        assertThat(r.getParts()).hasSize(2);
        assertThat(r.getParts().get(0).getType()).isEqualTo("text");
        assertThat(r.getParts().get(0).getText()).isEqualTo("第一段");
        assertThat(r.getParts().get(1).getType()).isEqualTo("data");
        assertThat(r.getParts().get(1).getData()).isEqualTo("{\"a\":1}");
        assertThat(r.getMarkdown()).isNull();
    }

    @Test
    @DisplayName("parts 为非法结构：不抛错，整体落 markdown 分支读 output")
    void assembleShouldFallbackOnMalformedParts() {
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("parts", "不是列表");
        last.put("output", "合法正文");
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("lastExecution", last);

        SubTaskExecutionResultResponse r = SubTaskExecutionResultAssembler.assemble(ctx, SUB_TASK_ID);
        assertThat(r.getFormat()).isEqualTo("markdown");
        assertThat(r.getMarkdown()).isEqualTo("合法正文");
    }
}