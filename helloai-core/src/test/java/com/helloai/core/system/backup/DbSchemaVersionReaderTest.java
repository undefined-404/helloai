package com.helloai.core.system.backup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REF-2.3 回归：库内 schema 版本求最大。
 *
 * <p><b>为什么值得单独立测</b>：这个值写进 manifest、被恢复侧门② 与代码基线比较。
 * 一旦算错（尤其是取小了），门② 会误判"备份比代码基线更旧"而**放行本该拒绝的更新备份**
 * —— 安全守卫上不能靠"上线看一眼"。</p>
 */
@DisplayName("库内 schema 版本读取（REF-2.3）")
class DbSchemaVersionReaderTest {

    @Test
    @DisplayName("★回归：字典序陷阱 —— \"99\" 与 \"105\" 混存时必须取 105 而非 99")
    void lexicographicTrapIsAvoided() {
        // 触发真实 bug 的正是这一组：SQL 的 max(version) 对 VARCHAR 走字典序，
        // '99' > '105'（因为 '9' > '1'），实测在 schema 已到 V105 的库上得到 "99"。
        assertThat(DbSchemaVersionReader.maxLeadingInteger(List.of("99", "105"))).isEqualTo(105);
    }

    @Test
    @DisplayName("常规：取数值最大，而非最后写入的那条")
    void takesNumericMax() {
        assertThat(DbSchemaVersionReader.maxLeadingInteger(List.of("1", "9", "10", "100"))).isEqualTo(100);
    }

    @Test
    @DisplayName("带小数点的版本按前导整数解析（12.3 -> 12）")
    void dottedVersion() {
        assertThat(DbSchemaVersionReader.maxLeadingInteger(List.of("12.3", "12.10"))).isEqualTo(12);
        assertThat(DbSchemaVersionReader.leadingInteger("12.3")).isEqualTo(12);
    }

    @Test
    @DisplayName("无法解析的版本被跳过，不污染最大值；全不可解析返回 -1")
    void unparsableVersionsAreSkipped() {
        assertThat(DbSchemaVersionReader.maxLeadingInteger(List.of("abc", "7", ""))).isEqualTo(7);
        assertThat(DbSchemaVersionReader.maxLeadingInteger(List.of("abc", ""))).isEqualTo(-1);
        assertThat(DbSchemaVersionReader.maxLeadingInteger(List.of())).isEqualTo(-1);
        assertThat(DbSchemaVersionReader.maxLeadingInteger(null)).isEqualTo(-1);
    }

    @Test
    @DisplayName("leadingInteger：null / 空白 / 无前导数字均返回 -1")
    void leadingIntegerEdgeCases() {
        assertThat(DbSchemaVersionReader.leadingInteger(null)).isEqualTo(-1);
        assertThat(DbSchemaVersionReader.leadingInteger("   ")).isEqualTo(-1);
        assertThat(DbSchemaVersionReader.leadingInteger("V105")).isEqualTo(-1);
        assertThat(DbSchemaVersionReader.leadingInteger(" 105 ")).isEqualTo(105);
    }
}
