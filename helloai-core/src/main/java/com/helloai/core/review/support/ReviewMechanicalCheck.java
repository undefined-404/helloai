package com.helloai.core.review.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表格类机械预检（`RV-10`，纯函数、零 token、零 Spring 依赖）。
 *
 * <p>对 CSV / TSV 附件由平台做确定性计算：物理行数、CSV 记录数、数据行数、列数、
 * 列数不齐、族完整性。机械侧读<b>全文</b>，不受注入预算约束——这是把「平台没看清」
 * 与「执行方算错」分开的唯一事实源。</p>
 *
 * <p><b>v1 只输出实测值，不输出 {@code claim} 行</b>：声称值来自 reviewer 的输出，
 * 而机械段在 LLM 调用之前装配（见实施计划 `RV-10` M2）。声称值比对由
 * {@link ReviewClaimComparison} 在解析 verdict 后完成。</p>
 *
 * <p><b>确定性</b>：同一输入必得同一输出；无时间/随机/环境依赖。非表格文件在入口被
 * {@link #isTabular} 拦掉，根本不产生 {@code file} 行（「本就不是表格」≠「表格算不出来」）。</p>
 */
public final class ReviewMechanicalCheck {

    /** 机械段上限（超出按 MISMATCH > UNAVAILABLE > file > family 截断并标 omitted）。 */
    public static final int MAX_OUTPUT_CHARS = 1_200;

    /** 单文件参与机械统计的字符上限（约 50 MB 的 UTF-8 文本量级）。 */
    public static final int MAX_FILE_CHARS = 50_000_000 / 8;

    /** 单文件物理行上限（防病态输入）。 */
    public static final int MAX_PHYSICAL_LINES = 5_000_000;

    /** {@code missing=} 最多列出的缺片编号数。 */
    private static final int MAX_MISSING_LIST = 10;

    public static final String STATUS_MATCH = "MATCH";
    public static final String STATUS_MISMATCH = "MISMATCH";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    public static final String REASON_PARSE_ERROR = "parse_error";
    public static final String REASON_RAGGED_ROWS = "ragged_rows";
    public static final String REASON_PART_COLS_MISMATCH = "part_cols_mismatch";
    public static final String REASON_ENCODING_DAMAGED = "encoding_damaged";
    public static final String REASON_TOO_LARGE = "too_large";

    /** 数值单元格判据：整数或小数（含正负号）。 */
    private static final Pattern NUMERIC_CELL = Pattern.compile("^[-+]?\\d+([.,]\\d+)?$");

    /** {@code file} 行解析（供声称值比对复用同一事实源）。 */
    private static final Pattern FILE_LINE = Pattern.compile(
            "file id=(\\d+) name=[^\\s]* lines=(\\d+) rows=(\\d+) data=(\\d+)");

    private ReviewMechanicalCheck() {
    }

    /**
     * 机械预检输入（已读全文的附件）。
     *
     * @param id   附件主键（输出行以 {@code id=} 开头，供 coverage 引用）
     * @param name 文件名（族识别与表格判据）
     * @param text 全文（截断前；null 视为不可读）
     */
    public record Source(Long id, String name, String text) {
    }

    /** 文件级结论。{@code available=false} 时 {@code reason} 必非空。 */
    public record FileReport(Long id, String name, boolean available, String reason,
                             int lines, int rows, int data, int cols, int ragged,
                             int multiline, boolean header, boolean encodingDamaged) {
    }

    /** 实测三元组（`lines` 物理行 / `rows` CSV 记录 / `data` 数据行）。 */
    public record Measure(long lines, long rows, long data) {
    }

    /** 是否表格类：按扩展名或 MIME 判定（v1 只 CSV / TSV）。 */
    public static boolean isTabular(String fileName, String mimeType) {
        String name = fileName == null ? "" : fileName.trim().toLowerCase();
        if (name.endsWith(".csv") || name.endsWith(".tsv")) {
            return true;
        }
        String mime = mimeType == null ? "" : mimeType.trim().toLowerCase();
        return mime.equals("text/csv") || mime.equals("text/tab-separated-values");
    }

    /**
     * 构建机械预检文本。
     *
     * @param sources 全部可直读文本附件（调用方在注入截断前取得全文）
     * @return {@code [MECHANICAL_CHECK]} 段；无表格类附件时返回空串
     */
    public static String build(List<Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        List<FileReport> reports = new ArrayList<>();
        for (Source source : sources) {
            if (source == null || !isTabular(source.name(), null)) {
                continue;
            }
            reports.add(analyze(source));
        }
        if (reports.isEmpty()) {
            return "";
        }
        List<String> fileLines = new ArrayList<>();
        for (FileReport report : reports) {
            fileLines.add(fileLine(report));
        }
        List<String> familyLines = familyLines(reports);
        return assemble(fileLines, familyLines);
    }

    /** 单文件分析（包可见，供单测直接打靶）。 */
    static FileReport analyze(Source source) {
        String text = source.text();
        if (text == null) {
            return unavailable(source, REASON_PARSE_ERROR);
        }
        if (text.length() > MAX_FILE_CHARS) {
            return unavailable(source, REASON_TOO_LARGE);
        }
        int physicalLines = countPhysicalLines(text);
        if (physicalLines > MAX_PHYSICAL_LINES) {
            return unavailable(source, REASON_TOO_LARGE);
        }
        if (ReviewEncodingDetector.detect(text).corrupted()) {
            return new FileReport(source.id(), source.name(), false, REASON_ENCODING_DAMAGED,
                    physicalLines, 0, 0, 0, 0, 0, false, true);
        }
        CsvParse parsed = parseCsv(text);
        if (parsed.error()) {
            return new FileReport(source.id(), source.name(), false, REASON_PARSE_ERROR,
                    physicalLines, parsed.records(), 0, parsed.cols(), parsed.ragged(),
                    parsed.multiline(), false, false);
        }
        int data = Math.max(0, parsed.records() - (parsed.header() ? 1 : 0));
        if (parsed.ragged() > 0) {
            return new FileReport(source.id(), source.name(), false, REASON_RAGGED_ROWS,
                    physicalLines, parsed.records(), data, parsed.cols(), parsed.ragged(),
                    parsed.multiline(), parsed.header(), false);
        }
        return new FileReport(source.id(), source.name(), true, "",
                physicalLines, parsed.records(), data, parsed.cols(), parsed.ragged(),
                parsed.multiline(), parsed.header(), false);
    }

    /**
     * 从机械段解析声称值比对所需的实测值。
     *
     * @param mechanicalText {@code [MECHANICAL_CHECK]} 文本（可空）
     * @param attachmentId   指定附件 id；null 表示「仅当机械段恰好一行时取该行」
     * @return 实测值；无法定位唯一文件时返回 null
     */
    public static Measure measureOf(String mechanicalText, Long attachmentId) {
        if (mechanicalText == null || mechanicalText.isEmpty()) {
            return null;
        }
        List<Measure> all = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (String line : mechanicalText.split("\n")) {
            Matcher matcher = FILE_LINE.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            Long id;
            try {
                id = Long.valueOf(matcher.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            ids.add(id);
            all.add(new Measure(parseLong(matcher.group(2)), parseLong(matcher.group(3)),
                    parseLong(matcher.group(4))));
        }
        if (attachmentId == null) {
            return all.size() == 1 ? all.get(0) : null;
        }
        for (int i = 0; i < ids.size(); i++) {
            if (attachmentId.equals(ids.get(i))) {
                return all.get(i);
            }
        }
        return null;
    }

    /**
     * 机械段里是否有该附件的**可测量**结论（`file id=… lines=… rows=… data=…`）。
     *
     * <p>供 `RV-10` 的逐条命中判据使用：只有该附件真的被算过，`method=mechanical` 才算覆盖。
     * 仅出现在 `UNAVAILABLE` 行（`parse_error` / `too_large` 等）不算——「算不出来」不是证据。</p>
     *
     * @param mechanicalText {@code [MECHANICAL_CHECK]} 文本（可空）
     * @param attachmentId   附件 id
     * @return 该附件在机械段中有可测量结论时为 {@code true}
     */
    public static boolean hasMeasure(String mechanicalText, long attachmentId) {
        return measureOf(mechanicalText, attachmentId) != null;
    }

    private static FileReport unavailable(Source source, String reason) {
        return new FileReport(source.id(), source.name(), false, reason, 0, 0, 0, 0, 0, 0, false, false);
    }

    private static String fileLine(FileReport report) {
        StringBuilder sb = new StringBuilder("file id=")
                .append(report.id() == null ? "-" : report.id())
                .append(" name=").append(safeName(report.name()));
        if (!report.available()) {
            sb.append(" status=UNAVAILABLE reason=").append(report.reason());
            if (report.ragged() > 0) {
                sb.append(" ragged=").append(report.ragged());
            }
            return sb.toString();
        }
        sb.append(" lines=").append(report.lines())
                .append(" rows=").append(report.rows())
                .append(" data=").append(report.data())
                .append(" cols=").append(report.cols())
                .append(report.encodingDamaged() ? " encoding=damaged" : " encoding=utf-8")
                .append(" ragged=").append(report.ragged())
                .append(" multiline=").append(report.multiline())
                .append(" header=").append(report.header() ? "present" : "absent");
        return sb.toString();
    }

    private static List<String> familyLines(List<FileReport> reports) {
        Map<String, List<FileReport>> groups = new LinkedHashMap<>();
        for (FileReport report : reports) {
            String key = AttachmentFamilies.familyKeyOf(report.name());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(report);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<FileReport>> group : groups.entrySet()) {
            FileReport main = null;
            List<Integer> partIndexes = new ArrayList<>();
            List<FileReport> parts = new ArrayList<>();
            for (FileReport report : group.getValue()) {
                AttachmentFamilies.Membership membership = AttachmentFamilies.parse(report.name());
                if (membership.main()) {
                    main = report;
                } else {
                    parts.add(report);
                    partIndexes.add(membership.partIndex());
                }
            }
            if (parts.isEmpty()) {
                continue;
            }
            lines.add(familyLine(group.getKey(), main, parts, partIndexes));
        }
        return lines;
    }

    private static String familyLine(String key, FileReport main, List<FileReport> parts,
                                     List<Integer> partIndexes) {
        String displayName = main != null ? main.name() : parts.get(0).name();
        int max = 0;
        for (int index : partIndexes) {
            max = Math.max(max, index);
        }
        List<Integer> missing = new ArrayList<>();
        int present = 0;
        for (int index = 1; index <= max; index++) {
            if (partIndexes.contains(index)) {
                present++;
            } else {
                missing.add(index);
            }
        }
        StringBuilder sb = new StringBuilder("family name=").append(safeName(displayName))
                .append(" parts=").append(parts.size())
                .append(" present=").append(present)
                .append(" missing=").append(missingText(missing));
        String unavailableReason = firstUnavailableReason(parts);
        if (unavailableReason != null) {
            return sb.append(" status=UNAVAILABLE reason=").append(unavailableReason).toString();
        }
        int cols = parts.get(0).cols();
        for (FileReport part : parts) {
            if (part.cols() != cols) {
                return sb.append(" status=UNAVAILABLE reason=").append(REASON_PART_COLS_MISMATCH).toString();
            }
        }
        int sumData = 0;
        for (FileReport part : parts) {
            sumData += part.data();
        }
        sb.append(" sum_data=").append(sumData);
        if (main == null) {
            return sb.append(" main_data=-").toString();
        }
        sb.append(" main_data=").append(main.data());
        if (!main.available()) {
            return sb.append(" status=UNAVAILABLE reason=").append(main.reason()).toString();
        }
        long delta = (long) sumData - main.data();
        if (delta == 0) {
            return sb.append(' ').append(STATUS_MATCH).toString();
        }
        return sb.append(' ').append(STATUS_MISMATCH).append(" delta=").append(signed(delta)).toString();
    }

    private static String firstUnavailableReason(List<FileReport> parts) {
        for (FileReport part : parts) {
            if (!part.available()) {
                return part.reason();
            }
        }
        return null;
    }

    private static String missingText(List<Integer> missing) {
        if (missing.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < missing.size() && i < MAX_MISSING_LIST; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("%02d", missing.get(i)));
        }
        if (missing.size() > MAX_MISSING_LIST) {
            sb.append(",…");
        }
        return sb.toString();
    }

    /** 按优先级截断：MISMATCH 族行 > UNAVAILABLE 行 > 其余 file 行 > 其余族行；超出标 omitted。 */
    private static String assemble(List<String> fileLines, List<String> familyLines) {
        List<String> ordered = new ArrayList<>();
        for (String line : familyLines) {
            if (line.contains(STATUS_MISMATCH)) {
                ordered.add(line);
            }
        }
        for (String line : fileLines) {
            if (line.contains(STATUS_UNAVAILABLE)) {
                ordered.add(line);
            }
        }
        for (String line : familyLines) {
            if (line.contains(STATUS_UNAVAILABLE)) {
                ordered.add(line);
            }
        }
        for (String line : fileLines) {
            if (!line.contains(STATUS_UNAVAILABLE)) {
                ordered.add(line);
            }
        }
        for (String line : familyLines) {
            if (!line.contains(STATUS_MISMATCH) && !line.contains(STATUS_UNAVAILABLE)) {
                ordered.add(line);
            }
        }
        String header = "[MECHANICAL_CHECK] v1";
        StringBuilder sb = new StringBuilder(header).append('\n');
        int total = ordered.size();
        int included = 0;
        for (String line : ordered) {
            int reserve = included == total ? 0 : 12;
            if (sb.length() + line.length() + 1 + reserve > MAX_OUTPUT_CHARS) {
                break;
            }
            sb.append(line).append('\n');
            included++;
        }
        int omitted = total - included;
        if (omitted > 0) {
            sb.append("omitted=").append(omitted);
        }
        return sb.toString().trim();
    }

    private static String safeName(String name) {
        return name == null || name.isBlank() ? "unknown" : name.trim().replaceAll("\\s+", "_");
    }

    private static String signed(long value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private static int countPhysicalLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return text.charAt(text.length() - 1) == '\n' ? lines : lines + 1;
    }

    /** 受限 CSV 解析：逗号/制表符 + 双引号转义；不支持引号内换行时降级 parse_error。 */
    private static CsvParse parseCsv(String text) {
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        char delimiter = detectDelimiter(body);
        int records = 0;
        int cols = 0;
        int ragged = 0;
        int multiline = 0;
        int fields = 1;
        int linesInRecord = 0;
        boolean inQuotes = false;
        boolean content = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < body.length() && body.charAt(i + 1) == '"') {
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                content = true;
            } else if (c == delimiter && !inQuotes) {
                fields++;
                content = true;
            } else if (c == '\n') {
                if (inQuotes) {
                    linesInRecord++;
                } else if (content || fields > 1) {
                    records++;
                    if (cols == 0) {
                        cols = fields;
                    } else if (fields != cols) {
                        ragged++;
                    }
                    multiline += Math.max(0, linesInRecord);
                    fields = 1;
                    linesInRecord = 0;
                    content = false;
                }
            } else if (c != '\r') {
                content = true;
            }
        }
        if (inQuotes) {
            return new CsvParse(records, cols, ragged, multiline, false, true);
        }
        if (content || fields > 1) {
            records++;
            if (cols == 0) {
                cols = fields;
            } else if (fields != cols) {
                ragged++;
            }
            multiline += Math.max(0, linesInRecord);
        }
        if (records == 0) {
            return new CsvParse(0, 0, 0, 0, false, false);
        }
        boolean header = isHeader(firstRecord(body, delimiter));
        return new CsvParse(records, cols, ragged, multiline, header, false);
    }

    private static char detectDelimiter(String text) {
        int commas = 0;
        int tabs = 0;
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '\n' && !inQuotes) {
                break;
            } else if (!inQuotes && c == ',') {
                commas++;
            } else if (!inQuotes && c == '\t') {
                tabs++;
            }
        }
        return tabs > commas ? '\t' : ',';
    }

    private static List<String> firstRecord(String text, char delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimiter && !inQuotes) {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else if (c == '\n' && !inQuotes) {
                break;
            } else if (c != '\r' || inQuotes) {
                cell.append(c);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    private static boolean isHeader(List<String> cells) {
        if (cells.isEmpty()) {
            return false;
        }
        boolean anyText = false;
        for (String cell : cells) {
            if (cell.isEmpty()) {
                continue;
            }
            if (NUMERIC_CELL.matcher(cell).matches()) {
                return false;
            }
            anyText = true;
        }
        return anyText;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private record CsvParse(int records, int cols, int ragged, int multiline,
                            boolean header, boolean error) {
    }
}
