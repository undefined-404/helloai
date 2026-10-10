package com.helloai.core.agent.skill;

import com.helloai.common.base.BizException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 技能包来源打戳器（REF-1.4）——<b>未接线的内部能力</b>。
 *
 * <p><b>为什么未接线</b>：REF-1.4 原文（转述自 Octop {@code skill/skill_transfer.py:54-72}）是
 * 「拷贝进 Agent 工作区时文本打戳」，但本仓库<b>不存在「把技能包拷贝进工作区」这个动作</b>
 * （全库检索：技能内容只被读取与注入，从不被写出）。按用户裁定（2026-10-10）本批<b>只落能力、
 * 不接路径</b>——先把「打戳 + 改写可识别」做成可用组件，等平台真的出现拷贝动作时直接调用，
 * 与 {@code AgentEventForkService}（未接线的内部能力，零调用方）同款处置。
 *
 * <p><b>触发条件</b>（登记于差距表 G-004 行）：平台出现「把技能包内容拷贝进 Agent 工作区 /
 * 交付物」的动作 ⇒ 该动作必须经由本器打戳，并补端到端用例。</p>
 *
 * <p><b>戳为什么落在正文尾部而不是 frontmatter</b>：技能包的 frontmatter 是
 * <b>8 键白名单</b>（{@link SkillPackageParser}），多一个未知键即判 corrupt——
 * 戳若写进 frontmatter 会把包解析坏。故用正文尾部的 HTML 注释承载（Markdown 渲染不可见，
 * 解析器的「执行速览」切分也不受影响）。</p>
 *
 * <p><b>「下游改写可识别」怎么做到</b>：戳里带<b>被戳内容的 SHA-256</b>；校验时重新计算
 * 「去掉戳之后的内容」摘要并比对——内容一旦被改，摘要对不上。这正是 REF-1.4 的验收项
 * 「带标技能被下游改写时能被识别」。</p>
 */
@Component
public class SkillPackageStamper {

    /** 拷贝策略，对齐 Octop {@code skill_package_store.py:32} 的 {@code copy_policy}。 */
    public enum CopyPolicy {
        /** 允许拷贝，盖来源戳（不锁）。 */
        SNAPSHOT,
        /** 允许拷贝，盖来源戳并置 {@code locked=1}（禁止下游改写）。 */
        LOCK,
        /** 禁止拷贝；{@link #stamp} 直接拒绝。 */
        DENY
    }

    /** 打戳结果：{@code text} 为可交付的带戳文本。 */
    public record Stamped(String text, CopyPolicy policy, String originRef, String contentSha256) {
    }

    /** 校验结果：{@code stamped}=是否带戳；{@code intact}=带戳且内容未被改写。 */
    public record Verification(boolean stamped, boolean intact, String originRef) {
        static Verification unstamped() {
            return new Verification(false, false, null);
        }
    }

    private static final String STAMP_MARK = "<!-- helloai-skill-source ";
    private static final String STAMP_END = " -->";
    private static final String SHA256_ALGORITHM = "SHA-256";

    /**
     * 给技能包内容打来源戳。
     *
     * @param content   技能包原始正文（含 frontmatter）
     * @param originRef 来源标识（建议 {@code name@version} 或安装记录 id）
     * @param policy    拷贝策略；{@link CopyPolicy#DENY} 时抛 403
     * @return 带戳文本 + 摘要素材
     * @throws BizException(403) 策略为 DENY
     */
    public Stamped stamp(String content, String originRef, CopyPolicy policy) {
        CopyPolicy effective = policy == null ? CopyPolicy.SNAPSHOT : policy;
        if (effective == CopyPolicy.DENY) {
            throw new BizException(403, "copy_policy=DENY：该技能包禁止拷贝");
        }
        String body = content == null ? "" : content;
        String hex = sha256Hex(body);
        String origin = sanitize(originRef);
        String stamp = STAMP_MARK
                + "origin=\"" + origin + "\""
                + " sha256=\"" + hex + "\""
                + " locked=\"" + (effective == CopyPolicy.LOCK ? "1" : "0") + "\""
                + STAMP_END;
        return new Stamped(body + "\n" + stamp, effective, origin, hex);
    }

    /**
     * 校验带戳文本：戳是否存在、内容是否被下游改写。
     *
     * @param text 待校验文本（可为纯正文，此时 {@code stamped=false}）
     * @return 校验结果；{@code intact=true} 表示戳在且摘要匹配
     */
    public Verification verify(String text) {
        if (text == null) {
            return Verification.unstamped();
        }
        int mark = text.lastIndexOf(STAMP_MARK);
        if (mark < 0) {
            return Verification.unstamped();
        }
        int end = text.indexOf(STAMP_END, mark);
        if (end < 0) {
            // 戳被截断（下游改写的典型形态之一）
            return new Verification(true, false, null);
        }
        String stamp = text.substring(mark + STAMP_MARK.length(), end);
        String origin = extractAttr(stamp, "origin");
        String expected = extractAttr(stamp, "sha256");
        if (expected == null || expected.isBlank()) {
            return new Verification(true, false, origin);
        }
        // 被戳内容 = 戳之前的部分去掉紧邻的一个换行
        String body = text.substring(0, mark);
        if (body.endsWith("\n")) {
            body = body.substring(0, body.length() - 1);
        }
        return new Verification(true, expected.equals(sha256Hex(body)), origin);
    }

    // ────────────────────────────────────────────────────────────
    //  内部
    // ────────────────────────────────────────────────────────────

    /** 从戳里取 {@code key="value"} 的值；缺失返回 null。 */
    private static String extractAttr(String stamp, String key) {
        String needle = key + "=\"";
        int i = stamp.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int j = stamp.indexOf('"', i + needle.length());
        return j < 0 ? null : stamp.substring(i + needle.length(), j);
    }

    /** 来源标识清洗：去掉引号与换行，避免破坏戳的可解析性。 */
    private static String sanitize(String originRef) {
        if (originRef == null) {
            return "";
        }
        return originRef.replaceAll("[\"\\r\\n]", "_");
    }

    private static String sha256Hex(String content) {
        try {
            byte[] digest = MessageDigest.getInstance(SHA256_ALGORITHM)
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2];
            final char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                hex[i * 2] = digits[v >>> 4];
                hex[i * 2 + 1] = digits[v & 0x0F];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，缺失属环境损坏
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
