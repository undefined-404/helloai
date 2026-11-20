package com.helloai.core.shared.util;

import com.helloai.common.constant.SensitiveScanPoint;
import com.helloai.common.constant.SensitiveScanRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SensitiveContentScanner} 判据单测（`MA-8` 第一段）。
 *
 * <p>对应蓝图 §4 `MA-8` ⑧ 验收第 1、2 条：造命中样本必中、造形似样本不误报。</p>
 */
@DisplayName("MA-8 敏感内容扫描器")
class SensitiveContentScannerTest {

    /** 合法身份证号（校验位按 GB 11643 算对）。 */
    private static final String VALID_ID_CARD = "11010519491231002X";

    /** 合法银行卡号（过 Luhn）。 */
    private static final String VALID_BANK_CARD = "4111111111111111";

    // ══════════════════════════════════════════════════════════════
    //  命中样本（各规则各一条）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("身份证号：校验位正确才命中")
    void detectsIdCardWhenCheckDigitValid() {
        SensitiveContentScanner.Result result =
                SensitiveContentScanner.scan("我的证件是 " + VALID_ID_CARD + " 请登记", 0, null);

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.hitRules()).containsExactly(SensitiveScanRule.ID_CARD);
        SensitiveContentScanner.Hit hit = result.hits().get(0);
        // 偏移指向号码起点（不含前缀「我的证件是 」）
        assertThat(hit.offset()).isEqualTo("我的证件是 ".length());
        // 指纹存在且是 SHA-256 十六进制（64 字符）
        assertThat(hit.sha256()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("身份证号：校验位错误不命中（抗 18 位订单号误报）")
    void ignoresIdCardWhenCheckDigitInvalid() {
        // 与合法号码只差末位，校验位必然对不上
        String wrong = "110105194912310021";
        SensitiveContentScanner.Result result = SensitiveContentScanner.scan(wrong, 0, null);

        assertThat(result.hits())
                .noneMatch(hit -> hit.rule() == SensitiveScanRule.ID_CARD);
    }

    @Test
    @DisplayName("手机号：命中 11 位大陆手机号")
    void detectsMobile() {
        SensitiveContentScanner.Result result =
                SensitiveContentScanner.scan("联系我 13812345678 谢谢", 0, null);

        assertThat(result.hitRules()).contains(SensitiveScanRule.MOBILE);
    }

    @Test
    @DisplayName("银行卡号：过 Luhn 才命中")
    void detectsBankCardWhenLuhnValid() {
        SensitiveContentScanner.Result result =
                SensitiveContentScanner.scan("卡号 " + VALID_BANK_CARD, 0, null);

        assertThat(result.hitRules()).contains(SensitiveScanRule.BANK_CARD);
    }

    @Test
    @DisplayName("私钥头：命中 PEM 私钥")
    void detectsPrivateKeyHeader() {
        SensitiveContentScanner.Result result = SensitiveContentScanner.scan(
                "-----BEGIN RSA PRIVATE KEY-----\nMIIE...", 0, null);

        assertThat(result.hitRules()).containsExactly(SensitiveScanRule.PRIVATE_KEY);
    }

    @Test
    @DisplayName("云厂商 AK：命中 LTAI / AKID / AKIA 前缀")
    void detectsCloudAccessKey() {
        Set<SensitiveScanRule> rules = EnumSet.noneOf(SensitiveScanRule.class);
        rules.addAll(SensitiveContentScanner.scan("ak=LTAI5tAbCdEfGhIjKlMn", 0, null).hitRules());
        rules.addAll(SensitiveContentScanner.scan("ak=AKIDzAbCdEfGhIjKlMn", 0, null).hitRules());
        rules.addAll(SensitiveContentScanner.scan("ak=AKIAIOSFODNN7EXAMPLE", 0, null).hitRules());

        assertThat(rules).contains(SensitiveScanRule.CLOUD_AK);
    }

    @Test
    @DisplayName("令牌前缀：命中 sk- 与长前缀 sk-ant-")
    void detectsApiToken() {
        assertThat(SensitiveContentScanner.scan("k=sk-proj-AbCdEfGhIjKlMnOp", 0, null).hitRules())
                .contains(SensitiveScanRule.API_TOKEN);
        assertThat(SensitiveContentScanner.scan("k=sk-ant-AbCdEfGhIjKlMnOp", 0, null).hitRules())
                .contains(SensitiveScanRule.API_TOKEN);
        assertThat(SensitiveContentScanner.scan("k=ghp_AbCdEfGhIjKlMnOpQr", 0, null).hitRules())
                .contains(SensitiveScanRule.API_TOKEN);
    }

    // ══════════════════════════════════════════════════════════════
    //  不命中样本（形似规则的正常内容）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("形似样本：sk-learn 不算令牌（载荷不足 / 无合法前缀）")
    void ignoresSkLearn() {
        SensitiveContentScanner.Result result =
                SensitiveContentScanner.scan("我们用的是 scikit-learn 和 sklearn 库", 0, null);

        assertThat(result.hitRules()).doesNotContain(SensitiveScanRule.API_TOKEN);
    }

    @Test
    @DisplayName("形似样本：普通 16 位数字（不过 Luhn）不判银行卡")
    void ignoresPlain16DigitsWithoutLuhn() {
        // 1234567890123456 的 Luhn 校验不通过
        SensitiveContentScanner.Result result =
                SensitiveContentScanner.scan("订单号 1234567890123456", 0, null);

        assertThat(result.hitRules()).doesNotContain(SensitiveScanRule.BANK_CARD);
    }

    @Test
    @DisplayName("形似样本：时间戳 / 版本号不命中任何规则")
    void ignoresTimestampsAndVersions() {
        SensitiveContentScanner.Result result = SensitiveContentScanner.scan(
                "2026-11-20 08:59:20 v17.0.20 构建通过", 0, null);

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("形似样本：短前缀不算令牌（sk- 后载荷不足 10 位）")
    void ignoresShortTokenPayload() {
        SensitiveContentScanner.Result result =
                SensitiveContentScanner.scan("变量名 sk-1 只是个占位", 0, null);

        assertThat(result.hitRules()).doesNotContain(SensitiveScanRule.API_TOKEN);
    }

    // ══════════════════════════════════════════════════════════════
    //  边界行为
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("空文本 / null 直接返回空结果")
    void handlesEmptyInput() {
        assertThat(SensitiveContentScanner.scan(null, 0, null).isEmpty()).isTrue();
        assertThat(SensitiveContentScanner.scan("", 0, null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("单条规则可单独关闭")
    void respectsDisabledRules() {
        String text = "手机 13812345678 与证件 " + VALID_ID_CARD;
        SensitiveContentScanner.Result full = SensitiveContentScanner.scan(text, 0, null);
        assertThat(full.hitRules())
                .contains(SensitiveScanRule.MOBILE, SensitiveScanRule.ID_CARD);

        SensitiveContentScanner.Result onlyMobile = SensitiveContentScanner.scan(
                text, 0, EnumSet.of(SensitiveScanRule.ID_CARD));
        assertThat(onlyMobile.hitRules())
                .contains(SensitiveScanRule.MOBILE)
                .doesNotContain(SensitiveScanRule.ID_CARD);
    }

    @Test
    @DisplayName("超长文本按 maxScanChars 截断，不越界")
    void respectsMaxScanChars() {
        String padding = "a".repeat(200);
        // 命中片段在 200 字符之后，截断到 100 则扫不到
        SensitiveContentScanner.Result truncated =
                SensitiveContentScanner.scan(padding + "13812345678", 100, null);
        assertThat(truncated.isEmpty()).isTrue();

        SensitiveContentScanner.Result fullScan =
                SensitiveContentScanner.scan(padding + "13812345678", 0, null);
        assertThat(fullScan.hitRules()).contains(SensitiveScanRule.MOBILE);
    }

    @Test
    @DisplayName("同一规则命中数超上限时标记 truncated")
    void marksTruncatedWhenExceedingPerRuleLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SensitiveContentScanner.MAX_HITS_PER_RULE + 3; i++) {
            sb.append("1381234567").append(i % 10).append(" ");
        }
        SensitiveContentScanner.Result result = SensitiveContentScanner.scan(sb.toString(), 0, null);

        assertThat(result.truncated()).isTrue();
        assertThat(result.hits().size())
                .isEqualTo(SensitiveContentScanner.MAX_HITS_PER_RULE);
    }

    @Test
    @DisplayName("结果不含原文片段（只有规则 / 偏移 / 指纹）")
    void resultCarriesNoRawText() {
        SensitiveContentScanner.Result result =
                SensitiveContentScanner.scan("证件 " + VALID_ID_CARD, 0, null);

        SensitiveContentScanner.Hit hit = result.hits().get(0);
        // Hit 是 record，只有三个字段；序列化后的字符串里不应出现原文
        String rendered = hit.toString();
        assertThat(rendered).doesNotContain(VALID_ID_CARD);
        assertThat(rendered).doesNotContain("110105");
    }

    @Test
    @DisplayName("校验位算法本身：已知合法 / 非法样本")
    void validatesIdCardAlgorithm() {
        assertThat(SensitiveContentScanner.validateIdCard(VALID_ID_CARD)).isTrue();
        assertThat(SensitiveContentScanner.validateIdCard("110105194912310021")).isFalse();
        // 长度 / 非数字一律 false
        assertThat(SensitiveContentScanner.validateIdCard("1101051949123100")).isFalse();
        assertThat(SensitiveContentScanner.validateIdCard(null)).isFalse();
    }

    @Test
    @DisplayName("Luhn 算法本身：已知合法 / 非法样本")
    void validatesLuhnAlgorithm() {
        assertThat(SensitiveContentScanner.luhnValid(VALID_BANK_CARD)).isTrue();
        assertThat(SensitiveContentScanner.luhnValid("1234567890123456")).isFalse();
        assertThat(SensitiveContentScanner.luhnValid("4111111111111112")).isFalse();
    }

    @Test
    @DisplayName("挂点枚举 code 稳定（聚合 SQL 依赖它）")
    void scanPointCodesAreStable() {
        assertThat(SensitiveScanPoint.INPUT.code()).isEqualTo("input");
        assertThat(SensitiveScanPoint.ARTIFACT.code()).isEqualTo("artifact");
        assertThat(SensitiveScanPoint.LLM_OUTBOUND.code()).isEqualTo("llm_outbound");
        assertThat(SensitiveScanPoint.ATTACHMENT.code()).isEqualTo("attachment");
    }

    // ══════════════════════════════════════════════════════════════
    //  CONFIG_SECRET：配置文件密钥赋值（挂点④ 存在的前提）
    //
    //  这一组测的不是「字符串长得像密钥」，而是「普通值挂在敏感键名上」——
    //  后者是配置文件泄露最常见的形态，也是前 6 条规则一条都命中不了的形态。
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("配置文件：password 赋值命中（YAML / properties / JSON 三种形态）")
    void detectsConfigSecretAcrossFormats() {
        String yaml = "spring:\n  datasource:\n    password: X9k2mP4v\n    url: jdbc:postgresql://x\n";
        String properties = "db.password=Q7w8eR9t\n";
        String json = "{ \"api_key\": \"Zx8Cv7Bn\" }";

        assertThat(SensitiveContentScanner.scan(yaml, 0, null).hitRules())
                .containsExactly(SensitiveScanRule.CONFIG_SECRET);
        assertThat(SensitiveContentScanner.scan(properties, 0, null).hitRules())
                .containsExactly(SensitiveScanRule.CONFIG_SECRET);
        assertThat(SensitiveContentScanner.scan(json, 0, null).hitRules())
                .containsExactly(SensitiveScanRule.CONFIG_SECRET);
    }

    @Test
    @DisplayName("配置文件：占位值一律不命中（误报的主要来源）")
    void ignoresPlaceholderSecrets() {
        // 这些都是真实配置文件里极常见的「示例值」，报出来就是纯噪音
        String[] placeholders = {
                "password: changeme",
                "password: your_password_here",
                "api_key: <your-key-here>",
                "secret: ${SECRET_FROM_ENV}",
                "token: {{ token }}",
                "password: ********",
                "client_secret: null",
                "password: true",
                "access_key: $AK",
                "secret_key: dummy_value",
                "password: example1234",
        };
        for (String sample : placeholders) {
            assertThat(SensitiveContentScanner.scan(sample, 0, null).isEmpty())
                    .as("占位值不该命中: %s", sample)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("配置文件：无赋值符的说明文字不命中（文档里 password 这个词最多）")
    void ignoresProseWithoutAssignment() {
        String prose = "请在设置页修改你的 password 字段，并确保 secret 不要外泄。";
        assertThat(SensitiveContentScanner.scan(prose, 0, null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("配置文件：无关复合词不命中（max_tokens / token_limit 不是凭据）")
    void ignoresUnrelatedCompoundKeys() {
        String config = "max_tokens: 4096\ntoken_limit: 100000\ntokenizer: cl100k_base\n";
        assertThat(SensitiveContentScanner.scan(config, 0, null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("配置文件：短于 4 字符的值不命中（避免 password: 12 之类的噪音）")
    void ignoresTooShortValues() {
        assertThat(SensitiveContentScanner.scan("password: abc", 0, null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("配置文件：校验器本身（占位判断是纯函数，可独立测）")
    void placeholderValidatorIsPureFunction() {
        assertThat(SensitiveContentScanner.isNotPlaceholderSecret("X9k2mP4v")).isTrue();
        assertThat(SensitiveContentScanner.isNotPlaceholderSecret("changeme")).isFalse();
        assertThat(SensitiveContentScanner.isNotPlaceholderSecret("${VAR}")).isFalse();
        assertThat(SensitiveContentScanner.isNotPlaceholderSecret("***")).isFalse();
        assertThat(SensitiveContentScanner.isNotPlaceholderSecret("abc")).isFalse();
    }

    @Test
    @DisplayName("配置文件：规则可单独关闭（误报爆炸时不必整项下线）")
    void configSecretRuleCanBeDisabledAlone() {
        String config = "password: X9k2mP4v";

        SensitiveContentScanner.Result withRule =
                SensitiveContentScanner.scan(config, 0, EnumSet.noneOf(SensitiveScanRule.class));
        SensitiveContentScanner.Result withoutRule =
                SensitiveContentScanner.scan(config, 0, EnumSet.of(SensitiveScanRule.CONFIG_SECRET));

        assertThat(withRule.hitRules()).containsExactly(SensitiveScanRule.CONFIG_SECRET);
        assertThat(withoutRule.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("规则名解析：未知名字进 unknown，不静默丢弃")
    void parseDisabledReportsUnknownNames() {
        SensitiveScanRule.ParseResult parsed =
                SensitiveScanRule.parseDisabled(List.of("mobile", "not_a_rule", "id_card"));

        assertThat(parsed.disabled())
                .containsExactlyInAnyOrder(SensitiveScanRule.MOBILE, SensitiveScanRule.ID_CARD);
        assertThat(parsed.unknown()).containsExactly("not_a_rule");
    }
}
