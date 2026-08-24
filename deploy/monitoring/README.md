# HelloAI 监控栈（阶段1：Prometheus + Grafana 指标监控）

参考 `E:\yhzx\1027\maticube\deploy\monitoring`（已在 maticube 项目验证可用的全套监控实现）落地到 HelloAI 的最小指标监控栈。

## 覆盖范围

| 对象 | 采集方式 | 看板面板 |
| --- | --- | --- |
| HelloAI app | Actuator `/actuator/prometheus`（micrometer-registry-prometheus） | JVM 堆/非堆、系统 CPU、活跃线程、接口 RT P50/P95/P99、QPS、GC 暂停、Top5 慢接口 |
| RabbitMQ | management 镜像自带 rabbitmq_prometheus 插件（15692，无需额外 exporter） | 队列就绪/未确认消息（积压监控） |
| PostgreSQL | postgres-exporter（只读监控账号 helloai_monitor） | 连接数、事务速率 |
| Redis | redis-exporter | 内存使用、连接数 |
| 抓取健康 | Prometheus `up` 指标 | 正常/异常 target 数、目标状态表 |

## 目录结构

```
deploy/monitoring/
├── docker-compose.monitoring.yml   # 监控栈（prometheus + grafana + redis-exporter + postgres-exporter）
├── prometheus/
│   ├── prometheus.yml              # 服务器版抓取配置（app:6565 服务名）
│   └── prometheus-local.yml        # 本地版抓取配置（host.docker.internal:6565）
└── grafana/
    ├── datasources/datasources.yml # Prometheus 数据源（provisioning 自动加载）
    └── dashboards/
        ├── dashboards.yml          # 看板 provider（provisioning 自动加载）
        └── helloai-overview.json   # 「HelloAI 监控总览」看板
```

## 首次部署

### 1. 创建 PostgreSQL 监控账号（只读，写操作请人工执行）

在 helloai 数据库执行（本地库与服务器库各执行一次；幂等，重复执行需先确认）：

```sql
CREATE ROLE helloai_monitor LOGIN PASSWORD 'HelloAI_monitor_2026';
GRANT CONNECT ON DATABASE helloai TO helloai_monitor;
GRANT pg_monitor TO helloai_monitor;
```

- `pg_monitor` 为 PostgreSQL 10+ 内置只读监控角色（可读 `pg_stat_*` 视图），无表数据读写权限。
- 如修改密码，需同步修改 `docker-compose.monitoring.yml` 中 `postgres-exporter` 的 `DATA_SOURCE_NAME`。

### 2. 启动监控栈

前置条件：主栈已启动（本地 `docker-compose.yml` 或服务器 `docker-compose.server.yml`），共享外部网络 `helloai_default` 已存在。

本地（app 在 IDEA 运行，默认抓 `host.docker.internal:6565`）：

```powershell
docker compose -f deploy/monitoring/docker-compose.monitoring.yml up -d
```

服务器（app 为 compose 容器，抓内网 `app:6565`）：

```bash
export PROMETHEUS_CONFIG=./prometheus/prometheus.yml
docker compose -f docker-compose.monitoring.yml up -d
```

### 3. 验证

- Prometheus：`http://localhost:9090/targets`（服务器上经 SSH 隧道访问，端口均绑定 127.0.0.1）
- Grafana：`http://localhost:3000`，账号 `admin/admin`，看板「HelloAI 监控总览」自动加载
- 命令行验证 app 指标：`curl http://localhost:6565/actuator/prometheus | Select-Object -First 20`
- RabbitMQ 插件确认：`curl http://localhost:25673/api/overview -u guest:guest` 或直接看 Prometheus rabbitmq job 是否 UP

## 运维说明

- 端口绑定：prometheus/grafana 均绑定 `127.0.0.1`，不暴露公网；阿里云安全组无需额外放行。
- 数据保留：Prometheus `--storage.tsdb.retention.time=30d`（约几十 MB/天，量级可忽略）。
- 停止监控栈不影响主栈：`docker compose -f deploy/monitoring/docker-compose.monitoring.yml down`
- 看板/数据源修改后：`docker compose -f deploy/monitoring/docker-compose.monitoring.yml restart grafana`（provisioning 30s 内自动生效）。

## 阶段2（预留，未实施）

- 日志聚合：Loki（轻量）或沿用 maticube 的 filebeat → logstash → ES/Kibana 链路（HelloAI 为单体单实例，日志量小，Loki 足够；完整 ELK 仅适合多实例分布式场景）。
- 告警：Grafana Alerting / Alertmanager + 钉钉或企业微信 webhook。
