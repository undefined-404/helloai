package com.helloai.core.system.backup;

import com.helloai.common.base.BizException;
import com.helloai.common.config.BackupProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code pg_dump} / {@code pg_restore} 的执行器（REF-2.3，`D-2026-10-10-2①`）。
 *
 * <p><b>这是本仓库第一处"应用执行宿主外部可执行文件"的能力</b>（此前 {@code ProcessBuilder} /
 * {@code Runtime.exec} 全仓零命中）。它**不构成** {@code G-005} 触发条件① —— 该条件的语境是
 * 「Agent 可调用工具面」，而备份是**管理端例程、非 agent 工具**（`D-2026-10-10-2②`）。</p>
 *
 * <p><b>三环境事实</b>（本类存在的理由）：app 镜像默认无客户端、本地 Windows 无、仅 PG 容器内有。
 * 故：① 生产由 Dockerfile 的 app 阶段补装 {@code postgresql-client}；② 本地由
 * {@code helloai.backup.pg-dump-path} 指向免安装 zip 版；③ <b>不可用时显式报错，绝不静默降级</b>。</p>
 *
 * <p><b>安全要点</b>：口令只经进程环境变量 {@code PGPASSWORD} 传递，**不进命令行**——
 * 否则会出现在 {@code ps} / 进程列表 / 日志里。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgDumpRunner {

    private final BackupProperties backupProperties;
    private final Environment environment;

    /** 相对路径向工作目录之上寻找的最大层数（IDEA 的工作目录是模块目录，而 .tools/ 在仓库根）。 */
    private static final int MAX_UPWARD_LEVELS = 6;

    private static final Pattern JDBC_PG = Pattern.compile(
            "^jdbc:postgresql://([^:/?]+)(?::(\\d+))?/([^?;/]+)");

    private static final Pattern DUMPED_FROM = Pattern.compile(
            "^;\\s*Dumped from database version:\\s*(\\S+)", Pattern.MULTILINE);
    private static final Pattern DUMPED_BY = Pattern.compile(
            "^;\\s*Dumped by pg_dump version:\\s*(\\S+)", Pattern.MULTILINE);
    private static final Pattern TOC_ENTRIES = Pattern.compile(
            "^;\\s*TOC Entries:\\s*(\\d+)", Pattern.MULTILINE);

    /** 前置检查结果（记忆化，避免每次备份都起一个进程探版本）。 */
    private volatile String cachedPgDumpVersion;
    private volatile String cachedPgRestoreVersion;

    // ────────────────────────────────────────────────────────────
    //  连接目标
    // ────────────────────────────────────────────────────────────

    /**
     * pg_dump / pg_restore 的连接目标。
     *
     * <p>{@code jdbcUrl} 保留原始 JDBC URL，仅用于错误消息（便于定位"连的是哪个库"）。</p>
     */
    public record DatabaseTarget(String host, int port, String database,
                                 String username, String password, String jdbcUrl) {

        /** 脱敏摘要（**不含口令**），用于日志与错误消息。 */
        public String describe() {
            return username + "@" + host + ":" + port + "/" + database;
        }
    }

    /**
     * 解析连接目标：{@code helloai.backup.*} 逐项优先；留空则从 {@code spring.datasource.url} 派生。
     *
     * <p>「独立配置 + 默认派生」的取舍（`D-2026-10-10-2⑥`）：数据源是 **JDBC URL**，
     * 而 pg_dump 要的是离散参数 —— 直接两处各配会漂移，故默认派生、需要时逐项覆盖。</p>
     */
    public DatabaseTarget resolveTarget() {
        String jdbcUrl = environment.getProperty("spring.datasource.url", "");
        String host = firstNonBlank(backupProperties.getHost(), null);
        int port = 0;
        String database = firstNonBlank(backupProperties.getDatabase(), null);

        if (host == null || port <= 0 || database == null) {
            Matcher m = JDBC_PG.matcher(jdbcUrl);
            if (!m.find()) {
                throw new BizException("无法从 spring.datasource.url 解析 PG 连接"
                        + "（请显式配置 helloai.backup.host/port/database）: " + jdbcUrl);
            }
            if (host == null) {
                host = m.group(1);
            }
            if (database == null) {
                database = m.group(3);
            }
            if (backupProperties.getPort() == null || backupProperties.getPort().isBlank()) {
                port = m.group(2) == null ? 5432 : parseInt(m.group(2), "端口");
            }
        }
        if (port <= 0) {
            port = parseInt(backupProperties.getPort(), "端口");
        }

        String username = firstNonBlank(backupProperties.getUsername(),
                environment.getProperty("spring.datasource.username", ""));
        String password = firstNonBlank(backupProperties.getPassword(),
                environment.getProperty("spring.datasource.password", ""));
        if (username == null || username.isBlank()) {
            throw new BizException("无法确定 PG 用户名（请配置 helloai.backup.username 或 spring.datasource.username）");
        }
        return new DatabaseTarget(host, port, database, username,
                password == null ? "" : password, jdbcUrl);
    }

    // ────────────────────────────────────────────────────────────
    //  前置检查
    // ────────────────────────────────────────────────────────────

    /**
     * 备份能力前置检查：开关打开、且 {@code pg_dump} 真的能执行。
     *
     * <p><b>不可用就显式报错</b>——本仓库对"静默失效"的容忍度极低（对照 REF-1.5 的
     * 「坏包不静默跳过」、REF-2 的「listObjects 为空必须显式失败」）。</p>
     *
     * @throws BizException 开关关闭 / 找不到可执行文件 / 无法运行
     */
    public void assertDumpAvailable() {
        if (!backupProperties.isEnabled()) {
            throw new BizException("备份功能已关闭（helloai.backup.enabled=false）");
        }
        if (cachedPgDumpVersion == null) {
            String exe = resolveExecutable(backupProperties.getPgDumpPath(), "pg_dump");
            cachedPgDumpVersion = runVersion(exe, "pg_dump");
        }
    }

    /** 恢复能力前置检查（同 {@link #assertDumpAvailable}）。 */
    public void assertRestoreAvailable() {
        if (!backupProperties.isEnabled()) {
            throw new BizException("备份功能已关闭（helloai.backup.enabled=false）");
        }
        if (cachedPgRestoreVersion == null) {
            String exe = resolveExecutable(backupProperties.getPgRestorePath(), "pg_restore");
            cachedPgRestoreVersion = runVersion(exe, "pg_restore");
        }
    }

    /** 探测到的 pg_dump 版本（如 {@code 16.4}）；未探测时为 null。 */
    public String pgDumpVersion() {
        return cachedPgDumpVersion;
    }

    // ────────────────────────────────────────────────────────────
    //  执行
    // ────────────────────────────────────────────────────────────

    /** 执行 {@code pg_dump -Fc} 产出归档文件。 */
    public void dumpToFile(DatabaseTarget target, Path out) {
        assertDumpAvailable();
        String exe = resolveExecutable(backupProperties.getPgDumpPath(), "pg_dump");
        List<String> cmd = List.of(exe,
                "-h", target.host(),
                "-p", String.valueOf(target.port()),
                "-U", target.username(),
                "-d", target.database(),
                "-Fc",                        // 自定义格式：支持 -l 不解档列举、支持并行恢复
                "-f", out.toAbsolutePath().toString());
        run(cmd, Map.of("PGPASSWORD", target.password()), "pg_dump");
        if (!Files.isRegularFile(out)) {
            throw new BizException("pg_dump 报成功但未产出归档文件: " + out);
        }
    }

    /**
     * 执行 {@code pg_restore} 恢复归档到目标库。
     *
     * <p>⚠️ **破坏性操作**。本方法不做任何安全判定 —— 三门校验由 {@link RestoreGate}
     * 在调用它**之前**完成（`D-2026-10-10-2⑦`）。</p>
     */
    public void restoreFromFile(DatabaseTarget target, Path dump) {
        assertRestoreAvailable();
        String exe = resolveExecutable(backupProperties.getPgRestorePath(), "pg_restore");
        List<String> cmd = List.of(exe,
                "-h", target.host(),
                "-p", String.valueOf(target.port()),
                "-U", target.username(),
                "-d", target.database(),
                "--no-owner",                 // 与 deploy/middleware/scripts/migrate.sh 同参
                "--no-privileges",
                "-j", "4",
                dump.toAbsolutePath().toString());
        run(cmd, Map.of("PGPASSWORD", target.password()), "pg_restore");
    }

    /**
     * 读归档头 —— **不解档**即可拿到跨引擎拒所需的版本。
     *
     * <p>实测依据：{@code pg_restore -l} 的输出头部直接给出
     * {@code Dumped from database version: 16.4} 与 {@code TOC Entries: 956}，
     * 只读目录、不展开数据。</p>
     */
    public DumpHeader peekHeader(Path dump) {
        assertRestoreAvailable();
        String exe = resolveExecutable(backupProperties.getPgRestorePath(), "pg_restore");
        CommandResult r = run(List.of(exe, "-l", dump.toAbsolutePath().toString()),
                Map.of(), "pg_restore -l");
        String out = r.output();
        return new DumpHeader(
                firstMatch(DUMPED_FROM, out),
                firstMatch(DUMPED_BY, out),
                parseToc(firstMatch(TOC_ENTRIES, out)));
    }

    /** 归档头信息。{@code pgVersion} 为 NULL 时表示不是合法归档（或格式不认识）。 */
    public record DumpHeader(String pgVersion, String pgDumpVersion, int tocEntries) {
    }

    /** PG **主版本号**（{@code 16.4} → {@code 16}）；解析不出返回 -1。 */
    public static int majorOf(String version) {
        if (version == null || version.isBlank()) {
            return -1;
        }
        int dot = version.indexOf('.');
        String major = dot > 0 ? version.substring(0, dot) : version;
        try {
            return Integer.parseInt(major.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ────────────────────────────────────────────────────────────
    //  内部
    // ────────────────────────────────────────────────────────────

    /**
     * 解析可执行文件路径。
     *
     * <p>配置为空 ⇒ 返回默认命令名，交给系统按 {@code PATH} 查找（生产镜像即此）。
     * 配置为**相对路径** ⇒ 自 {@code user.dir} **向上**逐层查找 ——
     * IDEA 跑 Spring Boot 时工作目录默认是模块目录（{@code helloai-start/}），
     * 而 {@code .tools/} 在仓库根；与 {@code scripts/powershell/*.ps1} 从 {@code $PSScriptRoot}
     * 上溯同源。都找不到则**显式报错**，不把相对路径丢给系统换来一句晦涩的"找不到文件"。</p>
     */
    private String resolveExecutable(String configured, String defaultName) {
        if (configured == null || configured.isBlank()) {
            return defaultName;
        }
        Path p = Path.of(configured);
        if (p.isAbsolute()) {
            if (!Files.isRegularFile(p)) {
                throw new BizException("配置的 " + defaultName + " 路径不存在: " + configured);
            }
            return p.toString();
        }
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < MAX_UPWARD_LEVELS && dir != null; i++) {
            Path candidate = dir.resolve(p);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
            dir = dir.getParent();
        }
        throw new BizException("找不到 " + defaultName + "：相对路径 " + configured
                + " 自 " + System.getProperty("user.dir") + " 向上 " + MAX_UPWARD_LEVELS + " 层未命中");
    }

    /** 执行 {@code <exe> --version} 并解析版本号。 */
    private String runVersion(String exe, String label) {
        CommandResult r = run(List.of(exe, "--version"), Map.of(), label + " --version");
        // 输出形如 "pg_dump (PostgreSQL) 16.4"
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)+)").matcher(r.output());
        if (!m.find()) {
            throw new BizException(label + " 版本无法解析: " + r.output().trim());
        }
        String version = m.group(1);
        log.info("{} 可用: version={}, path={}", label, version, exe);
        return version;
    }

    /** 命令执行结果（stdout+stderr 合并）。 */
    private record CommandResult(int exitCode, String output) {
    }

    /**
     * 执行外部命令。
     *
     * <p>口令经 {@code extraEnv} 传入（{@code PGPASSWORD}），**不进命令行**。
     * stdout/stderr 合并捕获（{@code redirectErrorStream}）：PG 工具的诊断信息多在 stderr，
     * 分开读会让错误消息丢失关键原因。</p>
     */
    private CommandResult run(List<String> cmd, Map<String, String> extraEnv, String label) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().putAll(extraEnv);
        try {
            Process process = pb.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(backupProperties.getCommandTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BizException(label + " 超时（" + backupProperties.getCommandTimeoutSeconds() + "s）已强制终止");
            }
            int code = process.exitValue();
            if (code != 0) {
                throw new BizException(label + " 失败（exit=" + code + "）: " + tail(output));
            }
            return new CommandResult(code, output);
        } catch (IOException e) {
            throw new BizException(label + " 无法启动（可执行文件不可用？）: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(label + " 被中断");
        }
    }

    private static String tail(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= 500 ? t : t.substring(t.length() - 500);
    }

    private static String firstMatch(Pattern p, String text) {
        if (text == null) {
            return null;
        }
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static int parseToc(String s) {
        if (s == null) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static int parseInt(String s, String what) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new BizException("配置的 PG " + what + " 不是合法数字: " + s);
        }
    }
}
