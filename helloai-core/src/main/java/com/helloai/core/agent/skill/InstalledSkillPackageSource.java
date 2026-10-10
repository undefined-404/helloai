package com.helloai.core.agent.skill;

import com.helloai.core.agent.entity.InstalledSkillPackage;

import java.util.List;

/**
 * 已安装技能包读取口（REF-1.6）。
 *
 * <p>目录 {@link SkillPackageCatalog} 用它取"当前生效"的已安装包，与 classpath 内置源合并为双源。
 * 抽成接口而非直连 Mapper 的两个理由：① 目录的既有构造器有两个（生产无参 / 测试注 scanner），
 * 直连 Mapper 会让测试必须造 DB；② 单测要能以替身验证"内置 + 已安装"的合并与重名处理。</p>
 *
 * <p><b>只返回 ACTIVE 行</b>：同 name 至多一行 ACTIVE 由 V104 的 partial unique index 保证，
 * 故"当前生效哪一版"恒为单值（`D-2026-10-10-1⑧`）。</p>
 */
public interface InstalledSkillPackageSource {

    /**
     * 取全部生效（{@code state=ACTIVE}）的已安装技能包，含 {@code body} 正文。
     *
     * <p>正文随行返回是有意的：目录加载时一次性取回并缓存，使 {@code resolve()} 的
     * 每轮装配热路径零 DB 往返（`D-2026-10-10-1②`）。</p>
     *
     * @return 包列表（按 name 升序）；无数据返回空列表，绝不返回 null
     */
    List<InstalledSkillPackage> activePackages();
}
