package com.helloai.core.agent.command;

import com.helloai.common.base.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ExecutionPart} 单测：分片投影（唯一实现）与 REST / JSON-RPC 通道的入参转换。
 */
@DisplayName("ExecutionPart 分片投影与转换")
class ExecutionPartTest {

    private static ExecutionPart text(String body) {
        ExecutionPart part = new ExecutionPart();
        part.setType(ExecutionPart.TYPE_TEXT);
        part.setText(body);
        return part;
    }

    private static ExecutionPart data(String json) {
        ExecutionPart part = new ExecutionPart();
        part.setType(ExecutionPart.TYPE_DATA);
        part.setData(json);
        return part;
    }

    @Test
    @DisplayName("投影：text 与 data 分片按空行拼接，data 包 ```json 代码块")
    void shouldProjectTextAndDataParts() {
        String projected = ExecutionPart.projectToText(List.of(text("第一段"), data("{\"k\":1}")));

        assertThat(projected).isEqualTo("第一段\n\n```json\n{\"k\":1}\n```");
    }

    @Test
    @DisplayName("投影：无分片 / 空列表 → null（调用方回落到 output 原文）")
    void shouldReturnNullForEmptyParts() {
        assertThat(ExecutionPart.projectToText(null)).isNull();
        assertThat(ExecutionPart.projectToText(List.of())).isNull();
        assertThat(ExecutionPart.projectToText(List.of(text("  ")))).isNull();
    }

    @Test
    @DisplayName("投影：file 分片不产出内容（协议层已拒收，此处仅防御历史数据）")
    void shouldSkipFilePart() {
        ExecutionPart file = new ExecutionPart();
        file.setType(ExecutionPart.TYPE_FILE);
        file.setFileName("a.png");

        assertThat(ExecutionPart.projectToText(List.of(file))).isNull();
        assertThat(ExecutionPart.projectToText(List.of(file, text("正文")))).isEqualTo("正文");
        assertThat(ExecutionPart.projectToText(List.of(file, text("正文")))).doesNotContain("a.png");
    }

    @Test
    @DisplayName("投影：空元素 / 未知 type 被跳过，不抛错")
    void shouldSkipNullAndUnknownParts() {
        ExecutionPart unknown = new ExecutionPart();
        unknown.setType("video");
        List<ExecutionPart> parts = new ArrayList<>();
        parts.add(null);
        parts.add(unknown);
        parts.add(text("正文"));

        assertThat(ExecutionPart.projectToText(parts)).isEqualTo("正文");
    }

    @Test
    @DisplayName("fromRaw：REST / JSON-RPC 的 List<Map> 转成分片；null → null")
    void shouldConvertRawList() {
        Map<String, Object> textPart = new HashMap<>();
        textPart.put("type", "text");
        textPart.put("text", "正文");
        Map<String, Object> dataPart = new HashMap<>();
        dataPart.put("type", "data");
        dataPart.put("data", "{\"k\":1}");
        dataPart.put("mimeType", "application/json");
        // 未知字段不得报错（前向兼容）
        dataPart.put("futureField", "ignored");

        List<ExecutionPart> parts = ExecutionPart.fromRaw(List.of(textPart, dataPart));

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).getType()).isEqualTo("text");
        assertThat(parts.get(0).getText()).isEqualTo("正文");
        assertThat(parts.get(1).getData()).isEqualTo("{\"k\":1}");
        assertThat(parts.get(1).getMimeType()).isEqualTo("application/json");
        assertThat(ExecutionPart.fromRaw(null)).isNull();
    }

    @Test
    @DisplayName("fromRaw：结构非法 → 400（用户可纠正的入参错误，不许 500）")
    void shouldRejectIllegalRawStructure() {
        assertThatThrownBy(() -> ExecutionPart.fromRaw("not-a-list"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("parts 结构非法");
    }
}
