package com.helloai.core.planner.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 草案的**逻辑位置身份**（`TD-16`）：回答「这是本任务的第几号子任务」。
 *
 * <p><b>为什么不直接用 {@code sub_task.id}</b>：{@code id} 是**存储身份**，每重建一次就变。
 * 用户的心智模型是位置——「2 号子任务太粗，拆成 3 条」之后，下游仍然依赖「2 号」这个概念，
 * 而 2 号那行 `id` 已经换了三次。位置不变、行会变，所以引用锚点必须是位置。</p>
 *
 * <p><b>格式</b>（ADR-008 §19.2，单段定宽）：</p>
 * <pre>
 * 草案 code：yyyyMMdd + 任务内 4 位序号    202611080002
 * </pre>
 *
 * <p><b>为什么不再生成子层段</b>：派生（{@code AD-3}）走「另起独立任务 Y」，Y 的草案编号
 * 由 Y 自己的拆解从 1 起算；本任务的草案只剩根层一种形态。{@link #parse} 仍容忍历史
 * 多段 code（V147 之前展开留下的存量行），但新写入一律单段。</p>
 *
 * <p><b>定宽为什么重要</b>：定宽才能让 {@code ORDER BY code} 直接等于草案的书写顺序。
 * 若用不定宽（{@code 2} / {@code 10}），字典序会把 {@code 10} 排在 {@code 2} 前面，
 * 排序就得再引入一个专用字段，那又多一处「同一事实两个来源」。</p>
 *
 * <p><b>日期前缀取 {@code task.create_time} 而非 {@code now()}</b>：任务跨天重拆时，
 * 用 {@code now()} 会让同一任务的编号前缀漂移，旧引用全部对不上。编号属于任务，不属于某一天。</p>
 *
 * <p><b>跨轮不重置</b>：整体废除后的新一轮沿用 {@code max(seq)+1}，不从 1 重新开始。
 * 整轮都已作废，没有人会引用旧轮的「2 号」；延续序号天然不撞唯一索引，也不需要处理
 * 「同 code 跨轮共存」。</p>
 */
public record DraftCode(String value) implements Comparable<DraftCode> {

    /** 根编号的日期前缀位数。 */
    private static final int DATE_LEN = 8;
    /** 根序号位数。 */
    private static final int ROOT_SEQ_LEN = 4;
    /** 子层序号位数。 */
    private static final int CHILD_SEQ_LEN = 3;
    private static final int ROOT_LEN = DATE_LEN + ROOT_SEQ_LEN;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 根序号上限（4 位）。实际受 {@code SUBTASK_COUNT_RULE} 限制在 20 上下，不会触发。 */
    public static final int MAX_ROOT_SEQ = 9999;

    public DraftCode {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("草案 code 不能为空");
        }
        List<String> parts = split(value);
        // 首段必须恰好 12 位纯数字
        String first = parts.get(0);
        if (first.length() != ROOT_LEN || !isDigits(first)) {
            throw new IllegalArgumentException("草案 code 根段必须是 " + ROOT_LEN + " 位数字: " + value);
        }
        for (int i = 1; i < parts.size(); i++) {
            String seg = parts.get(i);
            if (seg.length() != CHILD_SEQ_LEN || !isDigits(seg)) {
                throw new IllegalArgumentException(
                        "草案 code 第 " + i + " 层必须是 " + CHILD_SEQ_LEN + " 位数字: " + value);
            }
        }
    }

    /**
     * 任务创建时刻 → 任务所属日期（用于 {@code code} 的日期前缀）。
     *
     * <p><b>为什么要这个方法而不是各处自行 {@code toLocalDate()}</b>：日期前缀的时区口径
     * 必须唯一。用 UTC 会让晚上 8 点后创建的任务落到「第二天」，用系统时区才符合
     * 「11 月 8 日那批」的人眼认知。两处各写一遍就必然有一天两边不一致，
     * 而不一致的表现是暂存区/执行行定位失败（同一个任务算出两个前缀）。</p>
     */
    public static LocalDate dateOf(java.time.OffsetDateTime taskCreateTime) {
        if (taskCreateTime == null) {
            // 兜底：历史行 create_time 为空。用当天至少保证格式合法，
            // 这类行不会参与正常拆解（入口必然写 create_time）。
            return LocalDate.now();
        }
        return taskCreateTime.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 生成根编号。
     *
     * @param taskDate 任务创建日期（调用方从 {@code task.create_time} 取，**不要传 {@code now()}**）
     * @param seq      任务内序号，1-based
     */
    public static DraftCode root(LocalDate taskDate, int seq) {
        if (taskDate == null) {
            throw new IllegalArgumentException("生成根编号必须给任务创建日期");
        }
        if (seq < 1 || seq > MAX_ROOT_SEQ) {
            throw new IllegalArgumentException("根序号越界（1~" + MAX_ROOT_SEQ + "）: " + seq);
        }
        return new DraftCode(taskDate.format(DATE_FMT) + pad(seq, ROOT_SEQ_LEN));
    }

    /** 解析并校验格式；不合法抛 {@link IllegalArgumentException}。 */
    public static DraftCode parse(String raw) {
        return new DraftCode(raw);
    }

    /** 宽松解析：非法返回 {@code Optional.empty()}，用于读存量数据。 */
    public static Optional<DraftCode> tryParse(String raw) {
        try {
            return Optional.of(new DraftCode(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 展示号（给用户看）：根 `2`、一层 `2.1`、二层 `2.1.1`。
     *
     * <p><b>为什么不让用户直接看完整 code</b>：{@code 202611080002-001} 有 16 个字符，
     * 用户的心智表述是「2 号」「2.1」。完整 code 只在存储、接口、依赖引用里用；
     * 一旦展示层也用它，用户的「卡住 2.1」和界面的「202611080002-001」就对不上了。</p>
     *
     * <p><b>换算只在这一处</b>：散落到前端或别处各写一遍，格式一变就两处不一致。</p>
     */
    public String display() {
        List<String> parts = split(value);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String seg = parts.get(i);
            // 根段是「日期8位 + 序号4位」，展示时只取序号部分；
            // 若对整段去前导零，`202611080002` 开头是 2、去不掉任何东西，会原样吐出 12 位。
            String seqPart = (i == 0) ? seg.substring(DATE_LEN) : seg;
            // 各段去前导零：0002 → 2、001 → 1、010 → 10
            String stripped = seqPart.replaceFirst("^0+(?=\\d)", "");
            if (i > 0) {
                sb.append('.');
            }
            sb.append(stripped);
        }
        return sb.toString();
    }

    /** 根编号（去掉所有子层后缀）；本身为根时返回自己。 */
    public DraftCode rootCode() {
        return new DraftCode(split(value).get(0));
    }

    /** 是否为本编号的后代（不含自身）。 */
    public boolean isAncestorOf(DraftCode other) {
        if (other == null) {
            return false;
        }
        return other.value.startsWith(this.value + "-");
    }

    /** 字典序比较（定宽 ⇒ 等价于树的前序）。 */
    @Override
    public int compareTo(DraftCode o) {
        return this.value.compareTo(o.value);
    }

    @Override
    public String toString() {
        return value;
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private static List<String> split(String raw) {
        List<String> parts = new ArrayList<>();
        for (String s : raw.split("-")) {
            parts.add(s);
        }
        // 最多 4 段 = 根 + 3 层。上限取 3 层而不是 2，是因为 `BUILD` 类任务按 archetype
        // 分档允许展开 3 层（设计 §7：BUILD 3 / DOCUMENT 2 / RESEARCH 2 / DECIDE 1）。
        // 若这里写死 2 层，BUILD 的第 3 层展开会直接抛「层级过深」，而业务上它是合法的。
        // 具体档位限制由调用方按 `task.task_archetype` 判，值对象只负责格式上界。
        if (parts.size() > 4) {
            throw new IllegalArgumentException("草案 code 层级过深（最多根 + 3 层）: " + raw);
        }
        return parts;
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static String pad(int v, int len) {
        String s = Integer.toString(v);
        if (s.length() > len) {
            throw new IllegalArgumentException("序号超出 " + len + " 位: " + v);
        }
        StringBuilder sb = new StringBuilder(len);
        for (int i = s.length(); i < len; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }
}
