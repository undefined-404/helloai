package com.helloai.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.helloai.core.system.entity.SysUser;
import com.helloai.core.system.mapper.SysUserMapper;
import com.helloai.core.system.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitializer implements CommandLineRunner {

    private final SysUserMapper sysUserMapper;
    private final AuthService authService;

    @Override
    public void run(String... args) {
        long count = sysUserMapper.selectCount(Wrappers.emptyWrapper());
        if (count > 0) {
            log.info("系统用户表已有 {} 条记录，跳过初始化", count);
            return;
        }

        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setPassword(authService.encodePassword("helloai123"));
        admin.setNickname("系统管理员");
        admin.setRole("SUPER_ADMIN");
        admin.setStatus("ACTIVE");
        admin.setRemark("默认超级管理员，首次启动自动创建");
        sysUserMapper.insert(admin);

        log.info("默认管理员已创建: username=admin, password=helloai123");
    }
}
