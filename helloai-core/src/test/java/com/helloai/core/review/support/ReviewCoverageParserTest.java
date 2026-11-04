package com.helloai.core.review.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.review.support.ReviewCoverageParser.CoverageItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewCoverageParser} 单测（`RV-3` 覆盖清单防御承接）。
 *
 * <p>核心口径：<b>畸形输出只降级、不击穿判定</b>（与 {@code missingEvidence} 同一策略）；
 * 「是否构成覆盖」三条件缺一不可——有 {@code criterionRef}、{@code status=covered}、
 * 证据方式落在 {@code attachment|sandbox} 白名单。自述类 {@code method} 一律不计入覆盖。</p>
 */
@DisplayName("ReviewCoverageParser（RV-3 覆盖清单解析）")
class ReviewCoverageParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("测试数据非法: " + raw, e);
        }
    }

    private static CoverageItem onlyItem(String raw) {
        List<CoverageItem> items = ReviewCoverageParser.parse(json(raw));
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    @ParameterizedTest
    @DisplayName("非数组 / 空数组 / null 一律降级为空清单")
    @ValueSource(strings = {"[]", "{}", "\"covered\"", "3", "null"})
    void malformedRootYieldsEmptyList(String raw) {
        assertThat(ReviewCoverageParser.parse(json(raw))).isEmpty();
        assertThat(ReviewCoverageParser.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("正常条目：五键齐全、状态与方式归一为小写")
    void parsesWellFormedItem() {
        CoverageItem item = onlyItem("""
                [{"criterionRef":"接口清单覆盖全部端点","status":" COVERED ",
                  "method":"Attachment","evidenceRef":"api.md","note":"正文含 12 个端点"}]
                """);

        assertThat(item.criterionRef()).isEqualTo("接口清单覆盖全部端点");
        assertThat(item.status()).isEqualTo(ReviewCoverageParser.STATUS_COVERED);
        assertThat(item.method()).isEqualTo("attachment");
        assertThat(item.evidenceRef()).isEqualTo("api.md");
        assertThat(item.note()).isEqualTo("正文含 12 个端点");
        assertThat(item.covers()).isTrue();
    }

    @ParameterizedTest
    @DisplayName("status 非 covered（failed / not_verifiable / 空 / 非法值）不构成覆盖")
    @ValueSource(strings = {"failed", "not_verifiable", "", "pass", "未知"})
    void nonCoveredStatusDoesNotCountAsCoverage(String status) {
        CoverageItem item = onlyItem(
                "[{\"criterionRef\":\"接口清单\",\"status\":\"" + status + "\",\"method\":\"attachment\"}]");

        assertThat(item.claimsCovered()).isFalse();
        assertThat(item.covers()).isFalse();
    }

    @ParameterizedTest
    @DisplayName("自述类证据方式不在白名单：不计入覆盖（提示词第 12 条禁自报值当证据）")
    @ValueSource(strings = {"text", "output", "source", "self_report", "产出文本", ""})
    void inadmissibleMethodDoesNotCountAsCoverage(String method) {
        CoverageItem item = onlyItem(
                "[{\"criterionRef\":\"接口清单\",\"status\":\"covered\",\"method\":\"" + method + "\"}]");

        assertThat(item.claimsCovered()).isTrue();
        assertThat(item.hasAdmissibleMethod()).isFalse();
        assertThat(item.covers()).isFalse();
    }

    @Test
    @DisplayName("criterionRef 空白：即便 status/method 合格也不构成覆盖（无处归属）")
    void blankRefCannotCover() {
        CoverageItem item = onlyItem(
                "[{\"criterionRef\":\"   \",\"status\":\"covered\",\"method\":\"sandbox\"}]");

        assertThat(item.hasRef()).isFalse();
        assertThat(item.covers()).isFalse();
    }

    @Test
    @DisplayName("attachment 与 sandbox 两种合格方式均计入覆盖")
    void bothAdmissibleMethodsCount() {
        List<CoverageItem> items = ReviewCoverageParser.parse(json("""
                [{"criterionRef":"a","status":"covered","method":"attachment"},
                 {"criterionRef":"b","status":"covered","method":"sandbox"}]
                """));

        assertThat(items).hasSize(2).allSatisfy(item -> assertThat(item.covers()).isTrue());
    }

    @ParameterizedTest
    @DisplayName("元素非对象（字符串 / 数组 / null）逐条跳过，不连带丢弃合法条目")
    @ValueSource(strings = {"\"just a string\"", "[1,2]", "null", "5"})
    void nonObjectElementsAreSkipped(String badElement) {
        List<CoverageItem> items = ReviewCoverageParser.parse(json(
                "[" + badElement + ",{\"criterionRef\":\"接口清单\",\"status\":\"covered\"}]"));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).criterionRef()).isEqualTo("接口清单");
    }

    @Test
    @DisplayName("五键全空的对象丢弃；缺键条目降级为空串而非异常")
    void emptyObjectDroppedAndMissingKeysBecomeBlank() {
        assertThat(ReviewCoverageParser.parse(json("[{\"foo\":\"bar\"},{}]"))).isEmpty();

        CoverageItem partial = onlyItem("[{\"criterionRef\":\"接口清单\"}]");
        assertThat(partial.status()).isEmpty();
        assertThat(partial.method()).isEmpty();
        assertThat(partial.evidenceRef()).isEmpty();
        assertThat(partial.note()).isEmpty();
    }

    @Test
    @DisplayName("键值为对象/数组时视为空串（不因嵌套结构抛异常）")
    void containerValuesDegradeToBlank() {
        CoverageItem item = onlyItem("""
                [{"criterionRef":"接口清单","status":{"a":1},"method":["sandbox"],"note":"ok"}]
                """);

        assertThat(item.status()).isEmpty();
        assertThat(item.method()).isEmpty();
        assertThat(item.note()).isEqualTo("ok");
    }

    @Test
    @DisplayName("数值/布尔值容忍 toString 归一（模型把 exitCode 写成数字一类的情形）")
    void scalarValuesAreStringified() {
        CoverageItem item = onlyItem(
                "[{\"criterionRef\":\"接口清单\",\"status\":\"covered\",\"method\":\"sandbox\","
                        + "\"evidenceRef\":3,\"note\":false}]");

        assertThat(item.evidenceRef()).isEqualTo("3");
        assertThat(item.note()).isEqualTo("false");
    }

    @Test
    @DisplayName("条目上限 20：超出截断，不丢前 20 条")
    void capsAtTwentyItems() {
        StringBuilder array = new StringBuilder("[");
        for (int i = 1; i <= 25; i++) {
            if (i > 1) {
                array.append(',');
            }
            array.append("{\"criterionRef\":\"第").append(i).append("条\",\"status\":\"covered\","
                    + "\"method\":\"attachment\"}");
        }

        List<CoverageItem> items = ReviewCoverageParser.parse(json(array.append(']').toString()));

        assertThat(items).hasSize(ReviewCoverageParser.MAX_COVERAGE_ITEMS);
        assertThat(items.get(0).criterionRef()).isEqualTo("第1条");
        assertThat(items.get(19).criterionRef()).isEqualTo("第20条");
    }

    @Test
    @DisplayName("toMaps：保留五键与顺序，供 review_record.coverage 落库与前端渲染")
    void toMapsKeepsFiveKeysInOrder() {
        List<Map<String, String>> maps = ReviewCoverageParser.toMaps(
                ReviewCoverageParser.parse(json("""
                        [{"criterionRef":"接口清单","status":"covered","method":"sandbox",
                          "evidenceRef":"台账#3","note":"实测通过"}]
                        """)));

        assertThat(maps).hasSize(1);
        assertThat(maps.get(0)).containsOnlyKeys(
                "criterionRef", "status", "method", "evidenceRef", "note");
        assertThat(maps.get(0)).containsEntry("evidenceRef", "台账#3");
        assertThat(ReviewCoverageParser.toMaps(List.of())).isEmpty();
    }
}
