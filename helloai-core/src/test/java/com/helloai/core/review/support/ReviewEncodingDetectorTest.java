package com.helloai.core.review.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewEncodingDetector}（`RV-7`）单测：三类信号（替换字符 / 私用区 / 双重解码回推）
 * 与「正常中文不误伤」的反证。
 */
@DisplayName("ReviewEncodingDetector（RV-7 编码损坏检测）")
class ReviewEncodingDetectorTest {

    private static final Charset GBK = Charset.forName("GBK");
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    @Test
    @DisplayName("正常中文 + ASCII ⇒ 零命中（不误伤常规交付正文）")
    void normalChineseIsClean() {
        ReviewEncodingDetector.Result result = ReviewEncodingDetector.detect(
                "接口清单覆盖全部端点；每个端点标注请求参数。getMySkill=200，list=200。");

        assertThat(result.corrupted()).isFalse();
        assertThat(result.replacementChars()).isZero();
        assertThat(result.privateUseChars()).isZero();
        assertThat(result.doubleDecodeRuns()).isZero();
    }

    @Test
    @DisplayName("空文本 / null ⇒ 零命中")
    void nullOrEmptyIsClean() {
        assertThat(ReviewEncodingDetector.detect(null).corrupted()).isFalse();
        assertThat(ReviewEncodingDetector.detect("").corrupted()).isFalse();
    }

    @Test
    @DisplayName("U+FFFD 替换字符 ⇒ 计入 replacementChars")
    void replacementCharIsDetected() {
        ReviewEncodingDetector.Result result = ReviewEncodingDetector.detect("正文损坏：\uFFFD\uFFFD");

        assertThat(result.corrupted()).isTrue();
        assertThat(result.replacementChars()).isEqualTo(2);
        assertThat(result.detail()).contains("U+FFFD").contains("2 处");
    }

    @Test
    @DisplayName("私用区字符（真机样本 U+E045）⇒ 计入 privateUseChars")
    void privateUseCharIsDetected() {
        ReviewEncodingDetector.Result result = ReviewEncodingDetector.detect("合计数\uE045");

        assertThat(result.corrupted()).isTrue();
        assertThat(result.privateUseChars()).isEqualTo(1);
        assertThat(result.detail()).contains("私用区");
    }

    @Test
    @DisplayName("GBK 双重解码串（瀹炴祴）⇒ 回推为「实测」并计入双重解码片段")
    void gbkDoubleDecodeIsDetected() {
        // 真机样本：UTF-8「实测」的 6 字节按 GBK 解码即「瀹炴祴」（3 字，达到最短汉字段长度）
        assertThat("瀹炴祴".getBytes(GBK)).isEqualTo("实测".getBytes(StandardCharsets.UTF_8));

        ReviewEncodingDetector.Result result = ReviewEncodingDetector.detect("EXECUTION_RECORD 段：瀹炴祴");

        assertThat(result.corrupted()).isTrue();
        assertThat(result.doubleDecodeRuns()).isGreaterThanOrEqualTo(1);
        assertThat(result.samples()).anySatisfy(sample -> assertThat(sample).contains("实测"));
    }

    @Test
    @DisplayName("Windows-1252 双重解码串（ä¸æ–‡ 类）⇒ 回推为「中文」")
    void cp1252DoubleDecodeIsDetected() {
        String mojibake = new String("中文".getBytes(StandardCharsets.UTF_8), WINDOWS_1252);

        ReviewEncodingDetector.Result result = ReviewEncodingDetector.detect(mojibake);

        assertThat(result.corrupted()).isTrue();
        assertThat(result.doubleDecodeRuns()).isGreaterThanOrEqualTo(1);
        assertThat(result.samples()).anySatisfy(sample -> assertThat(sample).contains("中文"));
    }

    @Test
    @DisplayName("正常西文重音（Málaga）⇒ 不误判为双重解码")
    void normalLatinTextIsClean() {
        ReviewEncodingDetector.Result result = ReviewEncodingDetector.detect("Málaga, Zürich, São Paulo");

        assertThat(result.corrupted()).isFalse();
    }

    @Test
    @DisplayName("detail 截断在 200 字符内，避免 timeline payload 被撑大")
    void detailIsTruncated() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            text.append("\uFFFD");
        }
        ReviewEncodingDetector.Result result = ReviewEncodingDetector.detect(text.toString());

        assertThat(result.detail().length()).isLessThanOrEqualTo(200);
    }
}