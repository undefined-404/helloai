package com.helloai.core.review.support;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 证据分片族识别（`RV-9` / `RV-10` 共用的唯一实现）。
 *
 * <p>真实批的分片命名出现过三种形态：{@code X.part-01.csv}、{@code X.csv.part-01}、
 * {@code X-part-01.csv}。核验侧要按族分配注入预算、机械侧要按族校验完整性，
 * 两处各写一份前缀规则必然在后续改动中分叉，故收敛为本纯函数。</p>
 *
 * <p><b>族键口径</b>：统一取「去掉扩展名 + 去掉分片标记」后的前缀，使三种形态归入同一族；
 * 例：{@code detail.part-01.csv} / {@code detail.csv.part-01} /
 * {@code detail-part-01.csv} ⇒ 族键 {@code detail}。非分片文件的主件标记为
 * {@link #PART_NONE}，族键为其去扩展名文件名。</p>
 */
public final class AttachmentFamilies {

    /** 非分片文件（主件/单文件）的分片序号。 */
    public static final int PART_NONE = 0;

    /** 形态一：{@code X.part-01.csv}（分片标记在扩展名前）。 */
    private static final Pattern BEFORE_EXT = Pattern.compile("^(.*?)[.]part-?(\\d+)[.]([^.]+)$");

    /** 形态二：{@code X.csv.part-01}（分片标记在末尾）。 */
    private static final Pattern AFTER_EXT = Pattern.compile("^(.*[.][^.]+)[.]part-?(\\d+)$");

    /** 形态三：{@code X-part-01.csv}（连字符形态）。 */
    private static final Pattern DASH_FORM = Pattern.compile("^(.*?)-part-?(\\d+)[.]([^.]+)$");

    private AttachmentFamilies() {
    }

    /**
     * 族信息。
     *
     * @param familyKey 族键（去扩展名、去分片标记）
     * @param partIndex 分片序号（从 1 开始）；主件/单文件为 {@link #PART_NONE}
     * @param main      是否为该族主件（无分片标记）
     */
    public record Membership(String familyKey, int partIndex, boolean main) {
    }

    /**
     * 解析文件名所属族。
     *
     * @param fileName 文件名（可含路径，取最后一段）
     * @return 族信息；{@code null} / 空文件名返回族键为空、非主件的占位
     */
    public static Membership parse(String fileName) {
        String name = baseName(fileName);
        if (name.isEmpty()) {
            return new Membership("", PART_NONE, false);
        }
        Matcher beforeExt = BEFORE_EXT.matcher(name);
        if (beforeExt.matches()) {
            return new Membership(trimKey(beforeExt.group(1)), partIndex(beforeExt.group(2)), false);
        }
        Matcher dashForm = DASH_FORM.matcher(name);
        if (dashForm.matches()) {
            return new Membership(trimKey(dashForm.group(1)), partIndex(dashForm.group(2)), false);
        }
        Matcher afterExt = AFTER_EXT.matcher(name);
        if (afterExt.matches()) {
            return new Membership(trimKey(stripExtension(afterExt.group(1))),
                    partIndex(afterExt.group(2)), false);
        }
        return new Membership(trimKey(stripExtension(name)), PART_NONE, true);
    }

    /** 族键（无分片标记视为单文件主件）。 */
    public static String familyKeyOf(String fileName) {
        return parse(fileName).familyKey();
    }

    /** 取最后一段路径，统一路径分隔符。 */
    private static String baseName(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    /** 去掉最后一个扩展名；无扩展名时原样返回。 */
    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String trimKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private static int partIndex(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return PART_NONE;
        }
    }
}
