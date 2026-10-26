package com.helloai.core.agent.runtime.sandbox.impl;

import java.util.List;

/**
 * 从 classpath 读出的技能包<b>内容</b>（`SKX-6`，2026-10-26）。
 *
 * <p><b>为什么不叫 {@code SkillPackage}</b>：{@code com.helloai.core.agent.skill.SkillPackage}
 * 已是「技能目录登记项」（DB 侧 name / version / description / requiredTools）。同名两义会让
 * 「技能包」这个词在代码里失去指向——本类型是<b>待注入的字节</b>，那个是<b>目录元数据</b>。
 * 故用 {@code Loaded*} 前缀标明「已从 classpath 读出来」。</p>
 *
 * <p><b>为什么整包读进堆</b>：注入是「一次批量 HTTP 写」，SDK 的
 * {@code files().write(List<WriteEntry>)} 本来就要一次性拿到全部字节；`archify` 实测
 * 122 文件 / 4.13MB，加上 16MB 上限闸门，堆内一份副本完全可控。流式反而要拆批，
 * 而拆批会让「整包原子性」变差（一批成功一批失败 ⇒ 半装状态）。</p>
 *
 * <p><b>为什么不缓存</b>：每次完整读取 4MB + 算哈希约 30~50ms，与一次注入（146ms）同量级，
 * 缓存换不来可感知收益，却要背「运行中包变更不可见」的疑虑。包是随 jar 固化的，重读最诚实。
 * （注意：{@link SkillPackageLoader#bundledSkills()} 的<b>目录级</b>扫描有缓存，那是另一件事——
 * 它只列名字，不读内容。）</p>
 *
 * @param name       包名（已做路径安全校验，可安全拼进容器路径）
 * @param version    版本（同上；来自 {@code skill-release.json} 的语义版本）
 * @param entries    载荷文件（已按相对路径字节序排序；<b>不含</b> {@code MANIFEST.json}）
 * @param totalBytes 载荷总字节
 * @param sha256Tree 内容哈希树（算法见 {@code MANIFEST.json} 的 {@code sha256TreeAlgorithm}）
 */
record LoadedSkillPackage(String name, String version, List<Entry> entries, long totalBytes, String sha256Tree) {

    /**
     * 单个载荷文件。
     *
     * @param relativePath 相对包根的路径（正斜杠分隔，不含前导 {@code /}）
     * @param data         原始字节（<b>不</b>经文本编解码，避免 {@code \r\n} / BOM 被改）
     */
    record Entry(String relativePath, byte[] data) {
    }

    /** 载荷文件数（不含清单与幂等标记，与 {@code MANIFEST.json#fileCount} 同口径）。 */
    int fileCount() {
        return entries.size();
    }
}
