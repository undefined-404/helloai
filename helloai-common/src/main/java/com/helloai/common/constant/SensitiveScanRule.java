package com.helloai.common.constant;

import java.util.ArrayList;
import java.util.List;

/**
 * 敏感内容扫描规则集（`MA-8` 第一段：**只记不拦**）。
 *
 * <p><b>为什么规则 id 是枚举常量而不是散落字符串</b>：规则 id 会同时出现在
 * 事件 payload、观察期聚合 SQL、以及「单条规则关掉」的配置项里。三处各写一份字面量
 * 必然分叉（判据 5「改名/删除清理半径」），故此处为唯一事实源。</p>
 *
 * <p><b>为什么起步只有 6 条</b>：蓝图 §4 `MA-8` ⑦ / ⑤ 写死「起步就 5~8 条，别追求覆盖；
 * 规则多了误报会爆炸」。规则的价值是<b>先拿到真实误报率</b>，不是覆盖全品类。</p>
 *
 * <p><b>与「合规」的关系</b>：本规则集只提供<b>观测能力</b>，不提供防护能力。
 * log-only 不等于「已经合规」（蓝图 §4 `MA-8` ⑦ 最后一条风险）。</p>
 */
public enum SensitiveScanRule {

    /** 中国居民身份证号：18 位，末位可为 {@code X}；<b>必过校验位算法</b>才判命中（抗误报）。 */
    ID_CARD("id_card", "身份证号", "18 位身份证号（过 GB 11643 校验位）"),

    /** 中国大陆手机号：{@code 1[3-9]} + 9 位数字；前后须为词边界（避免命中更长数字串）。 */
    MOBILE("mobile", "手机号", "中国大陆 11 位手机号"),

    /** 银行卡号：16 或 19 位连续数字；<b>必过 Luhn 校验</b>才判命中。刻意避开 18 位以不与身份证重叠。 */
    BANK_CARD("bank_card", "银行卡号", "16/19 位银行卡号（过 Luhn 校验）"),

    /** 私钥头：{@code -----BEGIN ... PRIVATE KEY-----}（含 RSA / EC / OPENSSH / ENCRYPTED 等变体）。 */
    PRIVATE_KEY("private_key", "私钥头", "PEM 私钥头（BEGIN ... PRIVATE KEY）"),

    /** 云厂商访问密钥：{@code LTAI}（阿里云）/ {@code AKID}（腾讯云）/ {@code AKIA}·{@code ASIA}（AWS）。 */
    CLOUD_AK("cloud_ak", "云厂商访问密钥", "阿里云 LTAI / 腾讯云 AKID / AWS AKIA·ASIA 前缀"),

    /** 常见令牌前缀：{@code sk-}（OpenAI）/ {@code sk-ant-}（Anthropic）/ {@code ghp_}·{@code gho_} 等（GitHub）/ {@code glpat-}（GitLab）/ {@code xoxb-}·{@code xoxp-}（SiliconFlow）。 */
    API_TOKEN("api_token", "常见令牌前缀", "sk- / sk-ant- / ghp_ / glpat- / xoxb- 等前缀 + 足够长的载荷"),

    /**
     * 配置文件里的<b>密钥赋值</b>：{@code password: xxx} / {@code SECRET_KEY=xxx} / {@code "api_key": "xxx"}。
     *
     * <p><b>为什么必须有这一条</b>：上面 6 条全都只认「值的形状」（长成身份证样、长成 sk- 样），
     * 而配置文件泄露最常见的形态恰恰<b>不是</b>值的形状——是 {@code password: P@ssw0rd!} 这种
     * 普通字符串挂在一个敏感键名上。这类内容在现有规则下<b>一条都命中不了</b>，而这正是
     * 用户上传 {@code application.yml} / {@code .env} / {@code docker-compose.yml} 时最典型的样子。</p>
     *
     * <p><b>怎么压误报</b>（配置文件里 {\@code password} 这个词最多的场合其实是文档与示例）：</p>
     * <ol>
     *   <li>键名必须<b>整词</b>命中（{@code token} 不命中 {@code max_tokens} / {@code token_limit}，靠惰性边界 + {@code _}/{@code -} 一并排除）；</li>
     *   <li>必须有<b>赋值符</b>（{@code :} 或 {@code =}）——「请设置 password」这类说明文字不含赋值符；</li>
     *   <li>值必须<b>不是占位符</b>：{@code changeme} / {@code your_password} / {@code xxx} / {@code <...>} / {@code $VAR} / {@code {{var}}} / 全掩码 {@code ***} / {@code null} 一律排除；</li>
     *   <li>值长度 ≥ 4 且不含空白（书写型句子里的空格会自然截断，避免「一句话里恰好有 password: 这个词」被整段计入）。</li>
     * </ol>
     *
     * <p>仍会有误报（代码里 {@code String password = "abc123";} 会被命中）——但那本来就是硬编码密码，
     * 报出来不算错。本项 log-only，误报只污染观察期数据，不阻断业务。</p>
     */
    CONFIG_SECRET("config_secret", "配置文件密钥赋值", "password/secret/token/api_key 等键名 + 非占位值"),
    ;

    private final String code;
    private final String label;
    private final String desc;

    SensitiveScanRule(String code, String label, String desc) {
        this.code = code;
        this.label = label;
        this.desc = desc;
    }

    /** 事件 payload / 聚合 SQL 使用的稳定 id（snake_case）。 */
    public String code() {
        return code;
    }

    /** 人话短标签（进事件目录 / 日志）。 */
    public String label() {
        return label;
    }

    /** 详细说明。 */
    public String desc() {
        return desc;
    }

    /** 反向查：code → 枚举；未知返回 {@code null}（配置里写错规则名时用于告警，不静默吞掉）。 */
    public static SensitiveScanRule of(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (SensitiveScanRule rule : values()) {
            if (rule.code.equals(normalized)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 解析「要关掉的规则」配置项。
     *
     * <p>无法识别的名字<b>不静默忽略</b>，而是收集进返回值供调用方记一条 warn ——
     * 否则「配置写错 = 规则还在跑」与「配置生效」在库里长得一模一样（判据 10「显式声明」同类问题）。</p>
     *
     * @param codes 配置里的原始字符串（可空）
     * @return 解析结果：{@code disabled} 命中的规则；{@code unknown} 无法识别的名字
     */
    public static ParseResult parseDisabled(List<String> codes) {
        List<SensitiveScanRule> disabled = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        if (codes == null || codes.isEmpty()) {
            return new ParseResult(disabled, unknown);
        }
        for (String raw : codes) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            SensitiveScanRule rule = of(raw);
            if (rule == null) {
                unknown.add(raw.trim());
            } else {
                disabled.add(rule);
            }
        }
        return new ParseResult(disabled, unknown);
    }

    /** {@link #parseDisabled} 的解析结果。 */
    public record ParseResult(List<SensitiveScanRule> disabled, List<String> unknown) {
    }
}
