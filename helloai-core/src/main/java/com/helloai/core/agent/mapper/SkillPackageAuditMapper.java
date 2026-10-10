package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.entity.SkillPackageAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 技能包安装审计 Mapper（REF-1.6）。
 *
 * <p>只追加、不修改：审计行由安装 / 激活 / 卸载 / 拒绝四条路径写入
 * （见 {@code SkillPackageInstallService}），查询按 {@code name + createTime DESC} 走
 * 部分索引 {@code idx_skill_package_audit_name}。</p>
 */
@Mapper
public interface SkillPackageAuditMapper extends BaseMapper<SkillPackageAudit> {
}
