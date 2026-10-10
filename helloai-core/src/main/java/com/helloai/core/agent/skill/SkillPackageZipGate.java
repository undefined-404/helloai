package com.helloai.core.agent.skill;

import com.helloai.common.base.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * 技能包 zip 摄入安全闸门（REF-1.5）。
 *
 * <p><b>为什么用 commons-compress 而不是 JDK 的 zip 类</b>：JDK {@code java.util.zip.ZipEntry}
 * <b>不暴露</b> unix mode（external attributes 在中央目录里，JDK 不给），因此查不了 symlink；
 * 也不暴露加密位。commons-compress 提供 {@link ZipArchiveEntry#getUnixMode()} 与
 * {@link ZipFile#canReadEntryData}，这两项是闸门硬要求。</p>
 *
 * <p><b>依赖声明</b>：commons-compress 本是传递依赖（1.26.0，compile 作用域），
 * 按既有规矩（`D-2026-10-09-6③-2` 对 OkHttp 的处理）**已在 {@code helloai-core/pom.xml} 显式声明**。</p>
 *
 * <p><b>阈值来源</b>：借鉴 Octop（MIT）的 zip 安全清单与 AgentTeams（Apache-2.0）的摄取清单，
 * 仅借鉴「检查项」，未复制其代码；阈值集中在本类的常量，便于 REF-1.5 后续精化。</p>
 *
 * <p><b>两段式</b>：段 1 只读中央目录（不解压）——文件数 / 解压总量 / 压缩比 / 路径 / symlink /
 * 非普通文件 / 加密位；段 2 解出清单文件并做严格 UTF-8 解码。任一段失败抛
 * {@link BizException}(400, 含拒绝码)，不落任何数据。</p>
 *
 * <p><b>职责边界</b>：本类只做「结构安全 + 清单可解码」。<b>不做</b> frontmatter 语义解析与
 * {@code requiredTools ⊆ 已注册 @Tool} 校验——那是安装服务的事（它才有解析器与工具注册表）。</p>
 */
@Slf4j
@Component
public class SkillPackageZipGate {

    /** 包内清单文件名（根级）。术语红线见《文档体系分类与治理规则》§3.7：禁止用 SKILL.md。 */
    public static final String MANIFEST_NAME = "skill-package-manifest.md";

    /** 上传体上限（32MB）。 */
    static final long MAX_UPLOAD_BYTES = 32L * 1024 * 1024;

    /** 文件数上限。 */
    static final int MAX_ENTRIES = 2000;

    /** 解压总量上限（64MB）。 */
    static final long MAX_TOTAL_UNCOMPRESSED = 64L * 1024 * 1024;

    /** 压缩比上限。注意：**仅对超过 {@link #RATIO_CHECK_MIN_BYTES} 的条目判定**。 */
    static final long MAX_COMPRESSION_RATIO = 100L;

    /** 压缩比判定的最小条目体积（1MiB）——小文件高压缩比（如全零填充）属正常，照抄会误伤。 */
    static final long RATIO_CHECK_MIN_BYTES = 1L * 1024 * 1024;

    /** POSIX 文件类型掩码与符号链接 / 普通文件 / 目录的类型位。 */
    private static final int UNIX_TYPE_MASK = 0170000;
    private static final int UNIX_TYPE_SYMLINK = 0120000;
    private static final int UNIX_TYPE_REGULAR = 0100000;
    private static final int UNIX_TYPE_DIRECTORY = 0040000;

    /**
     * 过闸并取出清单正文。
     *
     * @param zipBytes 上传的技能包 zip 原始字节
     * @return 清单文件的原始字节（UTF-8 已校验可解码，但**未**做语义解析）
     * @throws BizException(400) 任一段校验失败；消息形如 {@code [拒绝码] 人可读原因}
     */
    public byte[] inspectAndExtractManifest(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw reject("EMPTY", "上传内容为空");
        }
        if (zipBytes.length > MAX_UPLOAD_BYTES) {
            throw reject("TOO_LARGE", "上传体超过上限 " + (MAX_UPLOAD_BYTES / 1024 / 1024) + "MB");
        }

        try (ZipFile zip = new ZipFile(new SeekableInMemoryByteChannel(zipBytes))) {
            // ── 段 1：只读中央目录，不解压 ──────────────────────────────
            int entryCount = 0;
            long totalUncompressed = 0;
            boolean manifestSeen = false;

            Enumeration<ZipArchiveEntry> it = zip.getEntries();
            while (it.hasMoreElements()) {
                ZipArchiveEntry entry = it.nextElement();
                if (++entryCount > MAX_ENTRIES) {
                    throw reject("TOO_MANY_ENTRIES", "文件数超过上限 " + MAX_ENTRIES);
                }
                validateEntryName(entry.getName());

                if (entry.isDirectory()) {
                    continue;
                }
                validateEntryType(entry);
                validateNotEncrypted(zip, entry);

                long size = entry.getSize();
                if (size < 0) {
                    throw reject("UNKNOWN_SIZE", "条目大小不可知（中央目录缺失 size）: " + entry.getName());
                }
                totalUncompressed += size;
                if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED) {
                    throw reject("TOO_MUCH_UNCOMPRESSED",
                            "解压总量超过上限 " + (MAX_TOTAL_UNCOMPRESSED / 1024 / 1024) + "MB");
                }
                validateCompressionRatio(entry, size);

                if (MANIFEST_NAME.equals(entry.getName())) {
                    manifestSeen = true;
                }
            }

            if (!manifestSeen) {
                throw reject("NO_MANIFEST", "包内根级缺少清单文件 " + MANIFEST_NAME);
            }

            // ── 段 2：只解清单一个条目 ─────────────────────────────────
            ZipArchiveEntry manifest = zip.getEntry(MANIFEST_NAME);
            if (manifest == null) {
                throw reject("NO_MANIFEST", "包内根级缺少清单文件 " + MANIFEST_NAME);
            }
            byte[] content = readEntry(zip, manifest);
            requireStrictUtf8(content, MANIFEST_NAME);
            log.info("技能包过闸: entries={}, uncompressed={}B, manifest={}B",
                    entryCount, totalUncompressed, content.length);
            return content;

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 中央目录损坏 / 非 zip / 引擎不支持的特性，统一按「非法包」拒绝并保留可读原因
            throw reject("BAD_ARCHIVE", "无法解析为合法 zip: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────
    //  段 1 细则
    // ────────────────────────────────────────────────────────────

    /** 路径安全：绝对路径 / 盘符 / 反斜杠 / `..` 组件一律拒（zip-slip）。 */
    private void validateEntryName(String name) {
        if (name == null || name.isBlank()) {
            throw reject("BAD_PATH", "存在空白条目名");
        }
        if (name.indexOf('\\') >= 0) {
            throw reject("BAD_PATH", "条目名含反斜杠: " + name);
        }
        if (name.startsWith("/") || name.startsWith("~")) {
            throw reject("BAD_PATH", "条目名为绝对路径: " + name);
        }
        // 盘符前缀，如 C:/...
        if (name.length() >= 2 && name.charAt(1) == ':' && Character.isLetter(name.charAt(0))) {
            throw reject("BAD_PATH", "条目名含盘符前缀: " + name);
        }
        for (String seg : name.split("/")) {
            if ("..".equals(seg)) {
                throw reject("BAD_PATH", "条目名含上跳组件 '..': " + name);
            }
        }
    }

    /** 非普通文件拒：symlink 与其它特殊类型（FIFO / 设备 / socket）一律拒；目录已在上层跳过。 */
    private void validateEntryType(ZipArchiveEntry entry) {
        int mode = entry.getUnixMode();
        if (mode == 0) {
            // 打包工具未写 unix 属性（如 Windows 打包）：无法判定，按普通文件放行——
            // symlink 需要有 unix 属性才可能表达，缺失该属性时不构成 symlink 通道。
            return;
        }
        int type = mode & UNIX_TYPE_MASK;
        if (type == UNIX_TYPE_SYMLINK) {
            throw reject("SYMLINK", "包内含符号链接: " + entry.getName());
        }
        if (type != UNIX_TYPE_REGULAR && type != UNIX_TYPE_DIRECTORY) {
            throw reject("NOT_REGULAR_FILE", "包内含非普通文件: " + entry.getName());
        }
    }

    /** 加密 zip 拒：commons-compress 对加密条目 canReadEntryData 为 false。 */
    private void validateNotEncrypted(ZipFile zip, ZipArchiveEntry entry) {
        if (!zip.canReadEntryData(entry)) {
            throw reject("ENCRYPTED", "包内含加密条目（或引擎不支持的特性）: " + entry.getName());
        }
    }

    /**
     * 压缩比（zip bomb）：**仅对超过 1MiB 的条目判定**。
     *
     * <p>小文件（如少量字符重复到几 KB）天然高压缩比，无条件套用阈值会误伤正常包。</p>
     */
    private void validateCompressionRatio(ZipArchiveEntry entry, long uncompressedSize) {
        if (uncompressedSize <= RATIO_CHECK_MIN_BYTES) {
            return;
        }
        long compressed = entry.getCompressedSize();
        if (compressed <= 0) {
            throw reject("BAD_ARCHIVE", "条目压缩后大小不可知: " + entry.getName());
        }
        long ratio = uncompressedSize / compressed;
        if (ratio > MAX_COMPRESSION_RATIO) {
            throw reject("ZIP_BOMB", "条目压缩比 " + ratio + " 超过上限 " + MAX_COMPRESSION_RATIO
                    + "（仅对 >1MiB 条目判定）: " + entry.getName());
        }
    }

    // ────────────────────────────────────────────────────────────
    //  段 2 细则
    // ────────────────────────────────────────────────────────────

    private byte[] readEntry(ZipFile zip, ZipArchiveEntry entry) throws Exception {
        long size = entry.getSize();
        if (size == 0) {
            throw reject("EMPTY_MANIFEST", MANIFEST_NAME + " 为空文件");
        }
        try (InputStream in = zip.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(size, 1 << 20))) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    /** 严格 UTF-8 解码：非法字节序列即拒（不静默替换成 U+FFFD）。 */
    private void requireStrictUtf8(byte[] content, String fileName) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
        } catch (CharacterCodingException e) {
            throw reject("NOT_UTF8", fileName + " 不是合法 UTF-8 文本: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────
    //  工具
    // ────────────────────────────────────────────────────────────

    private static BizException reject(String code, String reason) {
        return new BizException(400, "[" + code + "] " + reason);
    }
}
