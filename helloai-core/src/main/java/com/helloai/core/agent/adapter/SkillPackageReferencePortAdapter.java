package com.helloai.core.agent.adapter;

import com.helloai.core.agent.entity.InstalledSkillPackage;
import com.helloai.core.agent.mapper.InstalledSkillPackageMapper;
import com.helloai.core.system.port.ArtifactReference;
import com.helloai.core.system.port.SkillPackageReferencePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link SkillPackageReferencePort} 适配实现（§6.146 端口反转配套）：system 域存储对账所需的
 * 「技能包 zip 地址全量读取（含逻辑删除）」经本类收口，不暴露 agent 域实体。
 *
 * <p><b>为什么实现落在 agent 域？</b>端口由消费方 system 域定义，实现必须放在提供方所在的域：
 * {@code skill_package} 归 agent 域（{@code SkillPackageInstallService} 写），而
 * {@code agent → system} 是 §6 允许的向下依赖。</p>
 *
 * <p><b>为什么直接依赖 Mapper 而不经 Service？</b>对账只要一条只读投影（一列），
 * {@code SkillPackageInstallService} 是安装编排入口，依赖它会顺带拖进 zip 解析、审计、
 * 目录刷新等整条写入链。这条查询连实体字段都只填一个，没有业务语义可被复用。</p>
 */
@Service
@RequiredArgsConstructor
public class SkillPackageReferencePortAdapter implements SkillPackageReferencePort {

    private final InstalledSkillPackageMapper installedSkillPackageMapper;

    @Override
    public List<ArtifactReference> listAllIncludingDeleted() {
        List<InstalledSkillPackage> rows = installedSkillPackageMapper.selectOriginZipRefIncludingDeleted();
        List<ArtifactReference> refs = new ArrayList<>(rows.size());
        for (InstalledSkillPackage row : rows) {
            // fileSize 恒为 null：skill_package 不记录 zip 字节数，对账侧据此跳过字节比对
            refs.add(new ArtifactReference(row.getOriginZipUrl(), null));
        }
        return refs;
    }
}
