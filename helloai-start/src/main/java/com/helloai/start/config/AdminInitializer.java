package com.helloai.start.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.helloai.core.system.mapper.SysUserMapper;
import com.helloai.core.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 默认管理员兜底初始化（表内无任何系统用户时创建 admin）。
 *
 * <p>BASE-4.2 起委托 {@link SysUserService#create} 建号——与初始化向导
 * （{@code SetupController}）共用同一路径，保证「用户落库 + {@code sys_user_role}
 * 角色签发」在同一事务内完成（身份单事实源，不再写已退场的 {@code sys_user.role}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitializer implements CommandLineRunner {

    private final SysUserMapper sysUserMapper;
    private final SysUserService sysUserService;

    @Override
    public void run(String... args) {
        long count = sysUserMapper.selectCount(Wrappers.emptyWrapper());
        if (count > 0) {
            log.info("系统用户表已有 {} 条记录，跳过初始化", count);
            return;
        }

        sysUserService.create("admin", "helloai123", "系统管理员", "SUPER_ADMIN",
                "默认超级管理员，首次启动自动创建");

        log.info("默认管理员已创建: username=admin, password=helloai123");
    }
}
