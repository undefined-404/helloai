package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.core.system.entity.CredentialAuditLog;
import com.helloai.core.system.mapper.CredentialAuditLogMapper;
import com.helloai.core.system.service.CredentialAuditService;
import org.springframework.stereotype.Service;

/**
 * 凭证操作审计服务实现。
 */
@Service
public class CredentialAuditServiceImpl
        extends ServiceImpl<CredentialAuditLogMapper, CredentialAuditLog>
        implements CredentialAuditService {

    @Override
    public void record(Long credentialId, CredentialOwnerType ownerType, Long ownerId,
                       String provider, String action, String operator, String detail) {
        CredentialAuditLog log = new CredentialAuditLog();
        log.setCredentialId(credentialId);
        log.setOwnerType(ownerType);
        log.setOwnerId(ownerId);
        log.setProvider(provider);
        log.setAction(action);
        log.setOperator(operator);
        log.setDetail(detail);
        save(log);
    }

    @Override
    public IPage<CredentialAuditLog> listAudits(Long credentialId, CredentialOwnerType ownerType,
                                                Long ownerId, long page, long size) {
        // 显式 baseMapper + LambdaQueryWrapper：规避 lambdaQuery() chain 在隔离测试环境下
        // 的 MybatisMapperProxy 解析限制（MP 3.5.9）
        return baseMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<CredentialAuditLog>()
                        .eq(credentialId != null, CredentialAuditLog::getCredentialId, credentialId)
                        .eq(ownerType != null, CredentialAuditLog::getOwnerType, ownerType)
                        .eq(ownerId != null, CredentialAuditLog::getOwnerId, ownerId)
                        .orderByDesc(CredentialAuditLog::getCreateTime));
    }
}
