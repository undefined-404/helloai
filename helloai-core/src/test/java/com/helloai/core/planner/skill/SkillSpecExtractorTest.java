package com.helloai.core.planner.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillSpecExtractor} 单测（2026-10-27）。
 *
 * <p>被测的是「对话内直接出图」最容易崩的一环：模型从不老实只输出 JSON。
 * 断言口径：能剥掉说明与围栏、能配平嵌套花括号、抽不出时给的是<b>可读原因</b>而不是空指针。</p>
 */
class SkillSpecExtractorTest {

    private SkillSpecExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new SkillSpecExtractor(new ObjectMapper());
    }

    @Test
    @DisplayName("纯信封 JSON 直接可用")
    void plainEnvelope() {
        String raw = "{\"chartType\":\"lifecycle\",\"spec\":{\"states\":[{\"id\":\"a\"}]}}";
        SkillSpecExtractor.ChartSpec spec = extractor.extract(raw);
        assertEquals("lifecycle", spec.chartType());
        assertTrue(spec.specJson().contains("\"states\""));
        // 输出必须是纯 spec（不含信封字段），否则 CLI 会把它当成未知字段校验失败
        assertTrue(spec.specJson().startsWith("{\"states\""));
    }

    @Test
    @DisplayName("剥离 ```json 围栏与前后说明")
    void stripFenceAndPreamble() {
        String raw = """
                好的，我先确认一下类型：这是状态机，用 lifecycle。

                ```json
                {"chartType":"lifecycle","spec":{"nodes":[]}}
                ```

                需要我调整节点吗？
                """;
        assertEquals("lifecycle", extractor.extract(raw).chartType());
    }

    @Test
    @DisplayName("裸 JSON 后跟说明：按花括号配对截断，不被后面的括号带偏")
    void bareJsonWithTrailingProse() {
        String raw = "{\"chartType\":\"workflow\",\"spec\":{\"steps\":[\"a\"]}} 以上是输入（如需调整请告诉我）。";
        SkillSpecExtractor.ChartSpec spec = extractor.extract(raw);
        assertEquals("workflow", spec.chartType());
        assertTrue(spec.specJson().contains("\"steps\""));
    }

    @Test
    @DisplayName("字符串值里含花括号时不破坏配对")
    void bracesInsideStringAreIgnored() {
        String raw = "{\"chartType\":\"tree\",\"spec\":{\"label\":\"a{b}c}\",\"children\":[]}}";
        assertTrue(extractor.extract(raw).specJson().contains("a{b}c}"));
    }

    @Test
    @DisplayName("chart_type 下划线别名同样认")
    void snakeCaseAlias() {
        assertEquals("erd", extractor.extract("{\"chart_type\":\"erd\",\"spec\":{\"tables\":[]}}").chartType());
    }

    @Test
    @DisplayName("用 data 代替 spec 也接受（模型的常见变体）")
    void acceptsDataField() {
        assertEquals("sequence", extractor.extract("{\"chartType\":\"sequence\",\"data\":{\"msgs\":[]}}").chartType());
    }

    @Test
    @DisplayName("类型大小写不敏感，归一化为小写")
    void chartTypeIsNormalized() {
        assertEquals("lifecycle", extractor.extract("{\"chartType\":\"Lifecycle\",\"spec\":{}}").chartType());
    }

    @Test
    @DisplayName("未知类型：报错里带上支持的类型清单")
    void unknownTypeIsRejected() {
        SkillSpecExtractor.SpecExtractException e = assertThrows(
                SkillSpecExtractor.SpecExtractException.class,
                () -> extractor.extract("{\"chartType\":\"mindmap\",\"spec\":{}}"));
        assertTrue(e.getMessage().contains("mindmap"));
        assertTrue(e.getMessage().contains("lifecycle"));
    }

    @Test
    @DisplayName("缺 chartType / 缺 spec / 空输入：三种失败都有可读原因")
    void missingPiecesAreExplained() {
        assertTrue(assertThrows(SkillSpecExtractor.SpecExtractException.class,
                () -> extractor.extract("{\"spec\":{}}")).getMessage().contains("chartType"));
        assertTrue(assertThrows(SkillSpecExtractor.SpecExtractException.class,
                () -> extractor.extract("{\"chartType\":\"tree\"}")).getMessage().contains("spec"));
        assertTrue(assertThrows(SkillSpecExtractor.SpecExtractException.class,
                () -> extractor.extract("   ")).getMessage().contains("没有返回"));
    }

    @Test
    @DisplayName("括号不配对（输出被截断）：明确说被截断，而不是泛泛的解析失败")
    void truncatedJsonIsExplained() {
        SkillSpecExtractor.SpecExtractException e = assertThrows(
                SkillSpecExtractor.SpecExtractException.class,
                () -> extractor.extract("{\"chartType\":\"tree\",\"spec\":{\"a\":1"));
        assertTrue(e.getMessage().contains("括号不配对") || e.getMessage().contains("截断"));
    }

    @Test
    @DisplayName("spec 不是对象（模型给了数组）：拒绝而不是让 CLI 去炸")
    void specMustBeObject() {
        assertTrue(assertThrows(SkillSpecExtractor.SpecExtractException.class,
                () -> extractor.extract("{\"chartType\":\"tree\",\"spec\":[1,2]}"))
                .getMessage().contains("spec"));
    }
}
