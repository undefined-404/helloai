package com.helloai.core.system.port;

import java.util.List;

/**
 * 技能包原始 zip 引用读取端口（§6.146 端口反转），供存储对账巡检使用。
 *
 * <p>这是对账的<strong>第三类</strong>引用源，前两类是 {@link ArtifactReferencePort}
 * （{@code attachment}，task 域）与 {@link ChatAttachmentReferencePort}
 * （{@code chat_attachment}，planner 域）。</p>
 *
 * <p><b>它为什么会被漏掉</b>：技能包安装时把上传的 zip 落到受控存储（{@code skill-packages}
 * 前缀）用于溯源与再分发，地址记在 {@code skill_package.origin_zip_url}——这个列
 * <b>不叫</b> {@code storage_url} / {@code object_key}，所以按「路径类列名」检索引用源时
 * 扫不到它，只有读过 {@code SkillPackageInstallService} 的写入代码才看得见。</p>
 *
 * <p><b>漏掉的后果不是「多报孤儿」而是「误删真数据」</b>：对账只要开启孤儿清理，已安装技能包的
 * zip 就会整批被删，此后审计与再分发全部失效，而且删除发生在定时任务里、无人察觉。该缺陷在
 * 2026-10-23 清理历史残留时暴露——当时 {@code skill_package} 表恰好为空，那 24 个残留 zip
 * 才确实无主；表非空时会直接删错。</p>
 *
 * <p>由 agent 域 {@code SkillPackageReferencePortAdapter} 实现：{@code agent → system}
 * 属合法向下依赖。</p>
 */
public interface SkillPackageReferencePort {

    /**
     * 全量读取技能包 zip 引用，<b>包含逻辑删除（{@code deleted=1}）的记录</b>。
     *
     * <p>口径与前两个端口一致：宁可漏判孤儿，也不误删。只取未删除行会让卸载后尚未回收的
     * zip 被误判成孤儿。</p>
     *
     * <p>返回的 {@link ArtifactReference#fileSize()} 恒为 {@code null}：{@code skill_package}
     * 不记录 zip 字节数。对账侧对 {@code null} 的处理是「跳过字节比对」，只做存在性判定，
     * 因此不会产生大小误报。</p>
     *
     * @return 全部技能包 zip 引用（绝不返回 null）
     */
    List<ArtifactReference> listAllIncludingDeleted();
}
