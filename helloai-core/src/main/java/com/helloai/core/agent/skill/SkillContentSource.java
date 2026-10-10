package com.helloai.core.agent.skill;

/**
 * 内置技能包正文读取口（classpath）。
 *
 * <p><b>为什么抽出来</b>：正文读取原本内联在 {@code AgentSkillSpecServiceImpl#loadSpeedSummary}，
 * 且**硬编码** {@code new ClassPathResource("skills/plugins/" + fileName)}。技能包改为
 * <b>双源</b>（classpath 内置 + 受控存储安装，REF-1.6）后，读取要按来源分派，
 * 故下沉为独立端口。</p>
 *
 * <p><b>职责边界</b>：只负责"按包描述符取回<b>原始正文</b>"，<b>不做</b> frontmatter 剥离与
 * 速览渲染——那是调用方（{@code AgentSkillSpecServiceImpl}）的事，本端口因此可被单测替换。</p>
 *
 * <p><b>已安装包的正文不经过本端口</b>：已安装包的正文随目录行从 PG 载入、由
 * {@code SkillPackageCatalog} 缓存（`D-2026-10-10-1②⑩`）；本端口只服务内置源。</p>
 */
public interface SkillContentSource {

    /**
     * 读取内置技能包的原始正文（含 YAML frontmatter）。
     *
     * @param pkg 技能包描述符（内置源的物理定位用其 {@link SkillPackage#fileName()}）
     * @return 原始正文；文件缺失或读取失败返回 {@code null}——调用方按"技能不可用"处理，
     *         <b>不阻断执行链</b>（与改造前 best-effort 语义逐字一致）
     */
    String readBody(SkillPackage pkg);
}
