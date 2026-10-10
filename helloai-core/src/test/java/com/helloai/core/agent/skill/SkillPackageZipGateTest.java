package com.helloai.core.agent.skill;

import com.helloai.common.base.BizException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REF-1.5 验收：摄入闸门——<b>每类攻击各一个「必失败」用例</b>。
 *
 * <p>只测"必失败"与"正常包必通过"两侧：闸门是安全件，**误放行**比误拦危险得多，
 * 所以每个拒绝分支都要有一条把它钉死的用例。</p>
 */
@DisplayName("技能包摄入闸门（REF-1.5）")
class SkillPackageZipGateTest {

    private static final String MANIFEST = SkillPackageZipGate.MANIFEST_NAME;

    private static final byte[] VALID_MANIFEST = """
            ---
            name: demo-skill
            version: 1.0.0
            description: 演示技能包
            ---

            ## 执行速览

            1. 只做演示。
            """.getBytes(StandardCharsets.UTF_8);

    private final SkillPackageZipGate gate = new SkillPackageZipGate();

    // ────────────────────────────────────────────────────────────
    //  正常路径
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("正常包通过，返回清单字节")
    void validPackagePasses() throws Exception {
        byte[] zip = zip(entries(MANIFEST, VALID_MANIFEST, "scripts/run.sh", "echo hi".getBytes()));

        byte[] manifest = gate.inspectAndExtractManifest(zip);

        assertThat(new String(manifest, StandardCharsets.UTF_8)).contains("name: demo-skill");
    }

    // ────────────────────────────────────────────────────────────
    //  攻击用例：每类一个「必失败」
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("① 空上传 → 拒")
    void emptyRejected() {
        assertRejected(new byte[0], "EMPTY");
    }

    @Test
    @DisplayName("② 上传体超限（>32MB）→ 拒")
    void oversizedRejected() {
        byte[] huge = new byte[(int) SkillPackageZipGate.MAX_UPLOAD_BYTES + 1];
        assertRejected(huge, "TOO_LARGE");
    }

    @Test
    @DisplayName("③ 非 zip（中央目录不可解析）→ 拒")
    void nonZipRejected() {
        assertRejected("这不是一个 zip 文件".getBytes(StandardCharsets.UTF_8), "BAD_ARCHIVE");
    }

    @Test
    @DisplayName("④ 缺清单文件 → 拒")
    void missingManifestRejected() throws Exception {
        byte[] zip = zip(entries("readme.md", "no manifest here".getBytes()));
        assertRejected(zip, "NO_MANIFEST");
    }

    @Test
    @DisplayName("⑤ zip-slip：条目名含 '../' → 拒")
    void pathTraversalRejected() throws Exception {
        byte[] zip = zip(entries(MANIFEST, VALID_MANIFEST, "../../etc/passwd", "x".getBytes()));
        assertRejected(zip, "BAD_PATH");
    }

    @Test
    @DisplayName("⑥ 条目名为绝对路径 → 拒")
    void absolutePathRejected() throws Exception {
        byte[] zip = zip(entries(MANIFEST, VALID_MANIFEST, "/etc/passwd", "x".getBytes()));
        assertRejected(zip, "BAD_PATH");
    }

    @Test
    @DisplayName("⑦ 反斜杠分隔的路径穿越 → 拒（Windows 打包工具的典型形态）")
    void backslashTraversalRejected() throws Exception {
        // 用 JDK 的 ZipOutputStream 造夹具：commons-compress 的 ZipArchiveEntry 会把 '\' 规范化成 '/'，
        // 压根生成不出反斜杠条目名。
        //
        // 断言刻意只要求「被拒」而不锁定具体拒绝码：读取侧若把 '..\..\evil.bat' 规范化成
        // '../../evil.bat'，就由上跳组件检查（BAD_PATH）抓住；若原样保留，就由反斜杠检查抓住。
        // **两种规范化行为下都必须被拒** —— 这才是要守的安全属性。
        byte[] zip = zipJdk(entries(MANIFEST, VALID_MANIFEST, "..\\..\\evil.bat", "x".getBytes()));
        assertRejected(zip, "BAD_PATH");
    }

    @Test
    @DisplayName("⑧ 条目名含盘符前缀 → 拒")
    void driveLetterRejected() throws Exception {
        byte[] zip = zip(entries(MANIFEST, VALID_MANIFEST, "C:/windows/system32/x.dll", "x".getBytes()));
        assertRejected(zip, "BAD_PATH");
    }

    @Test
    @DisplayName("⑨ symlink 条目 → 拒")
    void symlinkRejected() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(bos)) {
            zos.putArchiveEntry(newZipEntry(MANIFEST));
            zos.write(VALID_MANIFEST);
            zos.closeArchiveEntry();

            ZipArchiveEntry link = newZipEntry("link-to-etc");
            link.setUnixMode(0120000 | 0777);     // S_IFLNK
            zos.putArchiveEntry(link);
            zos.write("/etc/passwd".getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();
        }
        assertRejected(bos.toByteArray(), "SYMLINK");
    }

    @Test
    @DisplayName("⑩ 非普通文件（FIFO）→ 拒")
    void nonRegularFileRejected() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(bos)) {
            zos.putArchiveEntry(newZipEntry(MANIFEST));
            zos.write(VALID_MANIFEST);
            zos.closeArchiveEntry();

            ZipArchiveEntry fifo = newZipEntry("pipe");
            fifo.setUnixMode(0010000 | 0644);      // S_IFIFO
            zos.putArchiveEntry(fifo);
            zos.write(new byte[0]);
            zos.closeArchiveEntry();
        }
        assertRejected(bos.toByteArray(), "NOT_REGULAR_FILE");
    }

    @Test
    @DisplayName("⑪ zip bomb：>1MiB 条目压缩比 >100 → 拒")
    void zipBombRejected() throws Exception {
        byte[] zeros = new byte[2 * 1024 * 1024];          // 2MiB 全零 → deflate 后极小
        byte[] zip = zip(entries(MANIFEST, VALID_MANIFEST, "bomb.bin", zeros));
        assertRejected(zip, "ZIP_BOMB");
    }

    @Test
    @DisplayName("⑫ 解压总量超 64MB → 拒（条目均在 1MiB 以下，故不触发压缩比检查）")
    void totalUncompressedRejected() throws Exception {
        // 每条 ≈1MiB−1 字节（**刚好低于**压缩比判定门槛，故不触发压缩比检查），高可压缩。
        // 条数须使累计 > 64MiB：64×(1MiB−1) = 67,108,800 **小于** 64MiB(=67,108,864)，差 64 字节不触发，
        // 故取 65 条。
        byte[] chunk = new byte[(int) SkillPackageZipGate.RATIO_CHECK_MIN_BYTES - 1];
        Arrays.fill(chunk, (byte) 'a');

        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put(MANIFEST, VALID_MANIFEST);
        for (int i = 0; i < 65; i++) {
            map.put("blob-" + i + ".txt", chunk);
        }
        assertRejected(zip(map), "TOO_MUCH_UNCOMPRESSED");
    }

    @Test
    @DisplayName("⑬ 文件数超 2000 → 拒")
    void tooManyEntriesRejected() throws Exception {
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put(MANIFEST, VALID_MANIFEST);
        for (int i = 0; i < SkillPackageZipGate.MAX_ENTRIES; i++) {
            map.put("f" + i, new byte[0]);
        }
        assertRejected(zip(map), "TOO_MANY_ENTRIES");
    }

    @Test
    @DisplayName("⑭ 加密 zip（置通用目的位 bit0）→ 拒")
    void encryptedRejected() throws Exception {
        byte[] zip = zip(entries(MANIFEST, VALID_MANIFEST));
        assertRejected(flipEncryptionBit(zip), "ENCRYPTED");
    }

    @Test
    @DisplayName("⑮ 清单非 UTF-8 → 拒（不静默替换 U+FFFD）")
    void nonUtf8ManifestRejected() throws Exception {
        byte[] bad = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x41, (byte) 0x80};
        assertRejected(zip(entries(MANIFEST, bad)), "NOT_UTF8");
    }

    // ────────────────────────────────────────────────────────────
    //  夹具
    // ────────────────────────────────────────────────────────────

    private static void assertRejected(byte[] zip, String expectedCode) {
        assertThatThrownBy(() -> new SkillPackageZipGate().inspectAndExtractManifest(zip))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("[" + expectedCode + "]");
    }

    private static ZipArchiveEntry newZipEntry(String name) {
        ZipArchiveEntry e = new ZipArchiveEntry(name);
        e.setUnixMode(0100644);          // S_IFREG | 0644：走"普通文件"分支
        return e;
    }

    private static Map<String, byte[]> entries(Object... kv) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], (byte[]) kv[i + 1]);
        }
        return map;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putArchiveEntry(newZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeArchiveEntry();
            }
        }
        return bos.toByteArray();
    }

    /** JDK 打包：**不**规范化条目名（用于造 commons-compress 造不出的夹具，如反斜杠）。 */
    private static byte[] zipJdk(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /**
     * 手工置「通用目的位」bit0（加密位）——commons-compress 不支持**写**加密 zip，
     * 故直接改字节：本地文件头偏移 6、中央目录项偏移 8 各 2 字节小端标志位。
     */
    private static byte[] flipEncryptionBit(byte[] zip) {
        byte[] out = zip.clone();
        for (int i = 0; i + 4 < out.length; i++) {
            boolean local = out[i] == 'P' && out[i + 1] == 'K' && out[i + 2] == 3 && out[i + 3] == 4;
            boolean central = out[i] == 'P' && out[i + 1] == 'K' && out[i + 2] == 1 && out[i + 3] == 2;
            if (local) {
                out[i + 6] |= 1;
            } else if (central) {
                out[i + 8] |= 1;
            }
        }
        return out;
    }
}
