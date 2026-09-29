package com.helloai.it;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * B 级集成测试容器单例（2026-09-29 引入，审计建议 #5：Testcontainers）。
 *
 * <p><b>职责</b>：持有 PG / Redis / RabbitMQ 三个 Testcontainers 容器，供
 * {@link AbstractItTestBase} 的 {@code @DynamicPropertySource} 注入端点，
 * 使 B 级集成测试在真实中间件上验证 Flyway / 幂等消费 / Outbox / 状态机 CAS。</p>
 *
 * <p><b>镜像口径</b>：与 {@code docker-compose.yml}（postgres:16.4-alpine /
 * redis:7.2.5-alpine / rabbitmq:3.12.14-management-alpine）逐一对齐（CODE_STYLE §3 技术栈），
 * 生产与测试同版本，杜绝「测试绿、生产红」的版本漂移。</p>
 *
 * <p><b>生命周期</b>：static 单例 + JVM shutdown hook —— 全部 IT 类共享一套容器，
 * 只启动一次（约 10~20s），避免每个测试类都重启容器；进程退出时由 hook 显式 stop，
 * 残留容器由 Testcontainers 的 Ryuk 机制兜底回收。无 Docker 环境在 static 块 start()
 * 时直接抛异常——这是有意为之：门禁脚本先 {@code docker info} 探测，不可用时不进入
 * 本类（协作规约 §27 NOT RUN 语义），可用而失败则必须暴露。</p>
 */
final class ItContainers {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16.4-alpine"))
            .withDatabaseName("helloai_it")
            .withUsername("postgres")
            .withPassword("postgres");

    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2.5-alpine"))
            .withExposedPorts(6379);

    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.12.14-management-alpine"));

    static {
        POSTGRES.start();
        REDIS.start();
        RABBITMQ.start();
        Runtime.getRuntime().addShutdownHook(new Thread(ItContainers::stopAll, "it-containers-shutdown"));
    }

    private ItContainers() {
    }

    private static void stopAll() {
        RABBITMQ.stop();
        REDIS.stop();
        POSTGRES.stop();
    }
}