package com.helloai.core.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `M-7` 拆分器用例。
 *
 * <p>口径以 magpie {@code internal/provider/keys.go:165-178} 的 {@code SplitKeys} 为准，
 * 故用例直接按"magpie 会怎么拆"来断言，而不是按本项目实现反推。</p>
 */
class KeyTextSplitterTest {

    @Nested
    @DisplayName("分隔符覆盖（与 magpie FieldsFunc 谓词逐字对齐）")
    class Separators {

        @Test
        @DisplayName("八类分隔符混写：换行 / 回车 / 制表 / 半角空格 / 全角空格 / 半角逗号 / 全角逗号 / 半角分号 / 全角分号")
        void shouldSplitOnAllMagpieSeparators() {
            String mixed = "sk-a\nsk-b\rsk-c\tsk-d sk-e\u3000sk-f,sk-g，sk-h;sk-i；sk-j";

            assertThat(KeyTextSplitter.split(mixed))
                    .containsExactly("sk-a", "sk-b", "sk-c", "sk-d", "sk-e",
                            "sk-f", "sk-g", "sk-h", "sk-i", "sk-j");
        }

        @Test
        @DisplayName("连续分隔符与首尾分隔符不产生空段（等价于 FieldsFunc 合并分隔符）")
        void shouldDropEmptySegments() {
            assertThat(KeyTextSplitter.split(",,sk-a;;; ;sk-b,，；")).containsExactly("sk-a", "sk-b");
        }
    }

    @Nested
    @DisplayName("剥引号：只剥两端，不动段内部")
    class QuoteStripping {

        @Test
        @DisplayName("双引号 / 单引号 / 反引号均可剥，且两端可混用")
        void shouldStripSurroundingQuotes() {
            assertThat(KeyTextSplitter.split("\"sk-a\" 'sk-b' `sk-c` \"'sk-d'\"")).containsExactly(
                    "sk-a", "sk-b", "sk-c", "sk-d");
        }

        @Test
        @DisplayName("段内部的引号原样保留（不是 strip 全部）")
        void shouldKeepInnerQuotes() {
            assertThat(KeyTextSplitter.split("sk-'a-b sk-\"c")).containsExactly("sk-'a-b", "sk-\"c");
        }

        @Test
        @DisplayName("整体只有引号的段被剥成空串 ⇒ 丢弃（magpie Trim 后空串不入选）")
        void shouldDropQuoteOnlySegment() {
            assertThat(KeyTextSplitter.split("'' \"\" `` sk-a")).containsExactly("sk-a");
        }
    }

    @Nested
    @DisplayName("去重与保序")
    class Dedup {

        @Test
        @DisplayName("重复 Key 只留首次出现，顺序为首次出现顺序")
        void shouldDedupKeepingFirstOccurrenceOrder() {
            assertThat(KeyTextSplitter.split("sk-c sk-a sk-b sk-a sk-c"))
                    .containsExactly("sk-c", "sk-a", "sk-b");
        }

        @Test
        @DisplayName("★去重发生在剥引号之后：带引号与不带的同一把 Key 视为重复")
        void shouldDedupAfterQuoteStripping() {
            assertThat(KeyTextSplitter.split("\"sk-a\" sk-a 'sk-a'")).containsExactly("sk-a");
        }
    }

    @Nested
    @DisplayName("空输入与边界")
    class EdgeCases {

        @Test
        @DisplayName("null / 空串 / 全空白 / 全分隔符 ⇒ 空列表（不返回 null）")
        void shouldReturnEmptyListForBlank() {
            assertThat(KeyTextSplitter.split(null)).isEmpty();
            assertThat(KeyTextSplitter.split("")).isEmpty();
            assertThat(KeyTextSplitter.split("   \n\t\u3000")).isEmpty();
            assertThat(KeyTextSplitter.split(",,,;;;，，")).isEmpty();
        }

        @Test
        @DisplayName("单把 Key 原样返回（不做 trim / 不做大小写归一）")
        void shouldKeepSingleKeyUntouched() {
            assertThat(KeyTextSplitter.split("Sk-AbC_123-x")).containsExactly("Sk-AbC_123-x");
        }

        @Test
        @DisplayName("★不做归一：段内大小写与特殊字符一律保留（归一会让指纹与密文不一致）")
        void shouldNotNormalize() {
            List<String> result = KeyTextSplitter.split("sk-AbC.def/ghi+xyz==");
            assertThat(result).containsExactly("sk-AbC.def/ghi+xyz==");
        }
    }
}
