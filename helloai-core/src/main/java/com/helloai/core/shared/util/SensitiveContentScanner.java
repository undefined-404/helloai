package com.helloai.core.shared.util;

import com.helloai.common.constant.SensitiveScanRule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感内容扫描器（`MA-8` 第一段：**只记不拦**）。
 *
 * <p><b>本类只做「判断有没有 + 定位 + 指纹」</b>，不做脱敏、不阻断、不打日志。
 * 谁扫、扫到后写什么事件，由调用方（挂点）决定——三者解耦才能单独测（与 `MA-4`
 * {@code DeliveryEvidenceEvaluator} 同一范式）。</p>
 *
 * <h3>两条铁律</h3>
 * <ol>
 *     <li><b>不返回原文片段</b>：{@link Hit} 只有规则、字符偏移、命中片段的 SHA-256。
 *         否则扫描器自己变成新的泄露源（蓝图 §4 `MA-8` ⑥「为什么不存原文」）。</li>
 *     <li><b>倾向于少报</b>：本项只观察，误报会污染观察期数据。身份证/银行卡<b>必过校验位</b>
 *         才判命中，手机号/令牌要求词边界 + 足够长的载荷。宁可漏，不可滥报。</li>
 * </ol>
 *
 * <h3>为什么放在 shared</h3>
 * 调用方横跨 planner 域（输入挂点）与 agent 域（产物 / 出站挂点）。按 CODE_STYLE §5.6，
 * 「真正跨域复用的 Util」归 {@code shared}；放在任一业务域都会造成另一域的跨域依赖。</p>
 */
public final class SensitiveContentScanner {

    /** 单条规则每次扫描最多记录多少条命中（防止 payload 爆炸；是否截断另有标记）。 */
    public static final int MAX_HITS_PER_RULE = 8;

    /** 身份证校验位权重（GB 11643-1999）。 */
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** 身份证校验位映射（sum % 11 → 校验字符）。 */
    private static final char[] ID_CHECK_CODES = "10X98765432".toCharArray();

    /**
     * 候选模式：只负责「找到形状对的位置」，真伪由 {@link #validateIdCard} / {@link #luhnValid} 判。
     *
     * <p>前缀按<b>长到短</b>排列（{@code sk-ant-} 先于 {@code sk-}），否则短前缀会先匹配、
     * 截走长前缀的前几个字符。</p>
     */
    private static final Pattern ID_CARD_CANDIDATE =
            Pattern.compile("(?<![0-9])([0-9]{17}[0-9Xx])(?![0-9])");

    private static final Pattern MOBILE_CANDIDATE =
            Pattern.compile("(?<![0-9])(1[3-9][0-9]{9})(?![0-9])");

    private static final Pattern BANK_CARD_CANDIDATE =
            Pattern.compile("(?<![0-9])((?:[0-9]{16})|(?:[0-9]{19}))(?![0-9])");

    private static final Pattern PRIVATE_KEY_HEADER =
            Pattern.compile("(-----BEGIN\\s?[A-Z0-9]{0,32}\\s?PRIVATE KEY-----)");

    private static final Pattern CLOUD_AK_CANDIDATE =
            Pattern.compile("(?<![A-Za-z0-9])((?:LTAI|AKID|AKIA|ASIA)[A-Za-z0-9]{10,})(?![A-Za-z0-9])");

    private static final Pattern API_TOKEN_CANDIDATE =
            Pattern.compile("(?<![A-Za-z0-9])"
                    + "((?:sk-ant-|sk-|ghp_|gho_|ghu_|glpat-|xoxb-|xoxp-)[A-Za-z0-9_\\-]{10,})"
                    + "(?![A-Za-z0-9])");

    /**
     * 配置文件密钥赋值（{@code password: xxx} / {@code SECRET_KEY=xxx} / {@code "api_key": "xxx"}）。
     *
     * <p><b>只有 1 个捕获组，且捕获的是「值」</b>——键名用前缀 lookbehind 约束、引号用非捕获组吃掉，
     * 因此可以直接复用 {@link #collect} 的单组约定（校验器作用于值）。</p>
     *
     * <p><b>键名前的边界为什么把 {@code _} 与 {@code -} 也算进「词内」</b>：这样
     * {@code max_tokens} / {@code token_limit} 这类无关复合词不会命中（{@code token} 前面是
     * {@code _}，被判为词内）。反过来 {@code spring.datasource.password} 前面是 {@code .}
     * ——点号不在词内集合，故正常命中。</p>
     */
    private static final Pattern CONFIG_SECRET_CANDIDATE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_-])"
                    + "(?:password|passwd|pwd|"
                    + "secret|secret[_-]?key|"
                    + "token|auth[_-]?token|"
                    + "api[_-]?key|apikey|"
                    + "access[_-]?key|accesskey|"
                    + "private[_-]?key|privatekey|"
                    + "credential|client[_-]?secret)"
                    // 键名后可能有闭合引号：JSON 的 {\"api_key\": \"...\"} 必须能命中
                    + "[\"']?\\s*[=:]\\s*"
                    + "(?:[\"'])?"
                    + "([^\\s\"'`,;{}\\[\\]]{4,})");

    private SensitiveContentScanner() {
    }

    /**
     * 扫描一段文本。
     *
     * @param text        待扫文本（{@code null} / 空串直接返回空结果）
     * @param maxScanChars 字符上限（超出只扫前 N 字符；{@code <=0} 视为不限）
     * @param disabled     本次要跳过的规则（可空）
     * @return 命中结果；{@link Result#truncated()} 表示有规则命中数被 {@link #MAX_HITS_PER_RULE} 截断
     */
    public static Result scan(String text, int maxScanChars, Set<SensitiveScanRule> disabled) {
        if (text == null || text.isEmpty()) {
            return Result.empty();
        }
        String subject = (maxScanChars > 0 && text.length() > maxScanChars)
                ? text.substring(0, maxScanChars)
                : text;
        Set<SensitiveScanRule> skip = disabled == null ? EnumSet.noneOf(SensitiveScanRule.class) : disabled;

        List<Hit> hits = new ArrayList<>();
        boolean truncated = false;

        if (!skip.contains(SensitiveScanRule.ID_CARD)) {
            CollectOutcome out = collect(subject, ID_CARD_CANDIDATE,
                    SensitiveScanRule.ID_CARD, SensitiveContentScanner::validateIdCard);
            hits.addAll(out.hits());
            truncated |= out.truncated();
        }
        if (!skip.contains(SensitiveScanRule.MOBILE)) {
            CollectOutcome out = collect(subject, MOBILE_CANDIDATE, SensitiveScanRule.MOBILE, null);
            hits.addAll(out.hits());
            truncated |= out.truncated();
        }
        if (!skip.contains(SensitiveScanRule.BANK_CARD)) {
            CollectOutcome out = collect(subject, BANK_CARD_CANDIDATE,
                    SensitiveScanRule.BANK_CARD, SensitiveContentScanner::luhnValid);
            hits.addAll(out.hits());
            truncated |= out.truncated();
        }
        if (!skip.contains(SensitiveScanRule.PRIVATE_KEY)) {
            CollectOutcome out = collect(subject, PRIVATE_KEY_HEADER, SensitiveScanRule.PRIVATE_KEY, null);
            hits.addAll(out.hits());
            truncated |= out.truncated();
        }
        if (!skip.contains(SensitiveScanRule.CLOUD_AK)) {
            CollectOutcome out = collect(subject, CLOUD_AK_CANDIDATE, SensitiveScanRule.CLOUD_AK, null);
            hits.addAll(out.hits());
            truncated |= out.truncated();
        }
        if (!skip.contains(SensitiveScanRule.API_TOKEN)) {
            CollectOutcome out = collect(subject, API_TOKEN_CANDIDATE, SensitiveScanRule.API_TOKEN, null);
            hits.addAll(out.hits());
            truncated |= out.truncated();
        }
        if (!skip.contains(SensitiveScanRule.CONFIG_SECRET)) {
            CollectOutcome out = collect(subject, CONFIG_SECRET_CANDIDATE,
                    SensitiveScanRule.CONFIG_SECRET, SensitiveContentScanner::isNotPlaceholderSecret);
            hits.addAll(out.hits());
            truncated |= out.truncated();
        }
        if (hits.isEmpty()) {
            return Result.empty();
        }
        return new Result(hits, truncated);
    }

    /**
     * 单条规则的收集：找到候选 → 可选的语义校验 → 记偏移与指纹 → 按上限截断。
     *
     * @param validator {@code null} = 候选形状即命中；否则须返回 {@code true} 才计入
     */
    private static CollectOutcome collect(String subject, Pattern pattern,
                                          SensitiveScanRule rule, Validator validator) {
        Matcher matcher = pattern.matcher(subject);
        // 约定：所有规则模式必须带捕获组 1；万一漏了则回落到整段匹配，
        // 让「少写一对括号」退化成精度损失，而不是运行期崩掉整条扫描（判据：观测不得伤主链）。
        int group = matcher.groupCount() >= 1 ? 1 : 0;
        List<Hit> hits = new ArrayList<>();
        boolean truncated = false;
        while (matcher.find()) {
            String candidate = matcher.group(group);
            if (candidate == null) {
                continue;
            }
            if (validator != null && !validator.test(candidate)) {
                continue;
            }
            if (hits.size() >= MAX_HITS_PER_RULE) {
                truncated = true;
                break;
            }
            hits.add(new Hit(rule, matcher.start(group), sha256Hex(candidate)));
        }
        return new CollectOutcome(hits, truncated);
    }

    /**
     * 身份证校验（GB 11643-1999 第 6 位校验位）。
     *
     * <p>不做这一步的话，18 位订单号、快递单号都会被命中 —— 误报会立刻淹掉观察期数据。</p>
     */
    static boolean validateIdCard(String candidate) {
        if (candidate == null || candidate.length() != 18) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < ID_WEIGHTS.length; i++) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            sum += (c - '0') * ID_WEIGHTS[i];
        }
        char expected = ID_CHECK_CODES[sum % 11];
        char actual = Character.toUpperCase(candidate.charAt(17));
        return expected == actual;
    }

    /**
     * 银行卡 Luhn 校验。
     *
     * <p>16/19 位纯数字在日志、哈希、时间戳里大量出现；不过 Luhn 会把绝大多数误报掉。</p>
     */
    static boolean luhnValid(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        int sum = 0;
        boolean doubling = false;
        for (int i = candidate.length() - 1; i >= 0; i--) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            int digit = c - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }

    /**
     * 配置文件密钥赋值：判断「这个值是不是真的像凭据，而不是占位符」。
     *
     * <p><b>为什么这条规则必须带校验器</b>：{@link SensitiveScanRule#CONFIG_SECRET} 的判据是
     * 「敏感键名 + 有赋值」，而配置文件里这类行绝大多数是<b>模板与示例</b>——
     * {@code password: changeme} / {@code api_key: your_api_key_here} / {@code secret=$SECRET}。
     * 不过滤这些会让该规则的误报率接近 100%，直接毁掉观察期数据（蓝图 §4 `MA-8` ⑦
     * 「规则多了误报会爆炸」的同一条判据）。</p>
     *
     * <p>判据全部为「否定式」（命中就要像真的），宁可漏也不滥报：漏掉的只是「值恰好叫
     * {@code secret}」这种本就该由人工判断的情形。</p>
     */
    static boolean isNotPlaceholderSecret(String value) {
        if (value == null || value.length() < 4) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);

        // 1) 变量 / 模板引用：$VAR、${VAR}、{{ var }}、<your-key>、%ENV%
        if (value.indexOf('$') >= 0 || value.indexOf('{') >= 0
                || value.indexOf('<') >= 0 || value.indexOf('%') >= 0) {
            return false;
        }
        // 2) 全掩码：*** / xxx / ... / ----（示例里表示「这里应该有值」）
        if (value.matches("[*xX.\\-]+")) {
            return false;
        }
        // 3) 常见占位词（含子串即排除：your_password / my_password / password_here 都属此类）
        for (String placeholder : SECRET_PLACEHOLDERS) {
            if (lower.contains(placeholder)) {
                return false;
            }
        }
        // 4) 布尔 / 空值字面量：true/false/null/none/on/off（配置项本身而非凭据）
        for (String literal : SECRET_LITERALS) {
            if (lower.equals(literal)) {
                return false;
            }
        }
        return true;
    }

    /** 占位词表：值里出现这些词一律视作示例而非真凭据（含子串匹配）。 */
    private static final List<String> SECRET_PLACEHOLDERS = List.of(
            "changeme", "change_me", "change-me", "placeholder", "example", "sample",
            "your_", "your-", "yourpassword", "your_own", "todo", "fixme",
            "insert_", "replace_", "dummy", "fake", "test_only", "notasecret");

    /** 配置项字面量（本身语义不是凭据）。 */
    private static final List<String> SECRET_LITERALS = List.of(
            "true", "false", "null", "none", "nil", "on", "off", "yes", "no", "0", "1");

    /** 命中片段的 SHA-256（十六进制小写）—— 事件里<b>只存这个</b>，不存原文。 */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必带 SHA-256；走到这里说明运行环境被裁剪，返回空串而非抛异常（扫描不得影响主链）
            return "";
        }
    }

    /** 语义校验器（身份证 / 银行卡用）。 */
    @FunctionalInterface
    private interface Validator {
        boolean test(String candidate);
    }

    /** 单条规则的收集结果。 */
    private record CollectOutcome(List<Hit> hits, boolean truncated) {
    }

    /**
     * 一条命中。<b>刻意不含原文片段</b>——只有规则、偏移、指纹。
     *
     * @param rule         命中的规则
     * @param offset       命中片段在（截断后的）扫描文本中的起始字符偏移
     * @param sha256       命中片段的 SHA-256（十六进制小写，空串表示摘要不可用）
     */
    public record Hit(SensitiveScanRule rule, int offset, String sha256) {
    }

    /** 一次扫描的结果。 */
    public record Result(List<Hit> hits, boolean truncated) {

        private static final Result EMPTY = new Result(Collections.emptyList(), false);

        static Result empty() {
            return EMPTY;
        }

        /** 是否什么都没命中。 */
        public boolean isEmpty() {
            return hits.isEmpty();
        }

        /** 命中的规则集合（用于聚合「这个挂点命中过哪几类」）。 */
        public Set<SensitiveScanRule> hitRules() {
            if (hits.isEmpty()) {
                return EnumSet.noneOf(SensitiveScanRule.class);
            }
            Set<SensitiveScanRule> rules = EnumSet.noneOf(SensitiveScanRule.class);
            for (Hit hit : hits) {
                rules.add(hit.rule());
            }
            return rules;
        }
    }
}
