package com.helloai.core.system.quota;

import com.helloai.core.system.quota.impl.BochaBalanceProvider;
import com.helloai.core.system.quota.impl.BalanceCredentialResolverRegistry;
import com.helloai.core.system.quota.impl.DeepSeekBalanceProvider;
import com.helloai.core.system.quota.impl.MiniMaxBalanceProvider;
import com.helloai.core.system.quota.impl.MoonshotBalanceProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * G-026 余额查询 Bean 装配。
 *
 * <p>4 个 provider 是无状态协议实现（非 Spring 组件），统一在此装配；
 * {@link BalanceProviderRegistry} 以**显式有序**列表构造，锁定 overview 返回顺序
 * = deepseek / moonshot / minimax / bocha（设计 §4.2），不受容器注入顺序影响。</p>
 */
@Configuration
public class QuotaConfig {

    @Bean
    public DeepSeekBalanceProvider deepSeekBalanceProvider(BalanceCredentialResolverRegistry resolverRegistry) {
        return new DeepSeekBalanceProvider(resolverRegistry);
    }

    @Bean
    public MoonshotBalanceProvider moonshotBalanceProvider(BalanceCredentialResolverRegistry resolverRegistry) {
        return new MoonshotBalanceProvider(resolverRegistry);
    }

    @Bean
    public MiniMaxBalanceProvider miniMaxBalanceProvider(BalanceCredentialResolverRegistry resolverRegistry) {
        return new MiniMaxBalanceProvider(resolverRegistry);
    }

    @Bean
    public BochaBalanceProvider bochaBalanceProvider(BalanceCredentialResolverRegistry resolverRegistry) {
        return new BochaBalanceProvider(resolverRegistry);
    }

    @Bean
    public BalanceProviderRegistry balanceProviderRegistry(
            DeepSeekBalanceProvider deepSeek,
            MoonshotBalanceProvider moonshot,
            MiniMaxBalanceProvider miniMax,
            BochaBalanceProvider bocha) {
        return new BalanceProviderRegistry(List.of(deepSeek, moonshot, miniMax, bocha));
    }
}
