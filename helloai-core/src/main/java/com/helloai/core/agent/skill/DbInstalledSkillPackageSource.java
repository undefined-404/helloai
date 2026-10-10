package com.helloai.core.agent.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.core.agent.entity.InstalledSkillPackage;
import com.helloai.core.agent.mapper.InstalledSkillPackageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 已安装技能包的 DB 读取实现（REF-1.6）。
 *
 * <p>只查 {@code state=ACTIVE} 且未逻辑删除的行；{@code @TableLogic} 已把 {@code deleted=0}
 * 织入条件，无需显式写。按 name 升序返回，与内置源的排序口径一致。</p>
 *
 * <p>本类不做缓存——缓存归 {@link SkillPackageCatalog}（它是目录的进程内缓存壳，
 * {@code refresh()} 同时失效两源）。</p>
 */
@Component
@RequiredArgsConstructor
public class DbInstalledSkillPackageSource implements InstalledSkillPackageSource {

    private final InstalledSkillPackageMapper mapper;

    @Override
    public List<InstalledSkillPackage> activePackages() {
        List<InstalledSkillPackage> rows = mapper.selectList(
                new LambdaQueryWrapper<InstalledSkillPackage>()
                        .eq(InstalledSkillPackage::getState, InstalledSkillPackage.STATE_ACTIVE)
                        .orderByAsc(InstalledSkillPackage::getName));
        return rows == null ? List.of() : rows;
    }
}
