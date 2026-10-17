# ADR-003：OpenSandbox 集成 —— 部署拓扑与网络隔离定稿

| 项目       | 内容 |
| -------- | ---- |
| **编号**   | ADR-003 |
| **状态**   | Accepted（2026-10-17，批次 3 落地；S-5 交付物） |
| **日期**   | 2026-10-17 |
| **关联文档** | [`plan/HelloAI OpenSandbox 沙箱集成计划.md`](../../plan/HelloAI%20OpenSandbox%20沙箱集成计划.md) §4.6（S-5 网络隔离）、[`HelloAI 项目基线文档.md`](../../HelloAI%20项目基线文档.md) §7/§10、`LOG-20261017-008` / `-009` / `-010` |
| **决策类型** | 部署拓扑 + 网络隔离（S-5 前置契约，先于 S-6 验证集） |

> **本 ADR 只定部署拓扑与网络隔离口径，不接业务执行链**（本轮集成目标 = 能力预研 + 契约落地，
> 用户 2026-10-17 裁定；沙箱内执行 agent 工具链属后置阶段，触发条件见 §6）。

---

## 1. 背景与问题

`S-3` 已把 `OpenSandboxSandboxProvider`（契约实现）与 Kotlin SDK 引入类路径，且 SDK 可行性 gate 实测通过
（`LOG-20261017-009`）。但「沙箱 server 如何部署、app 与沙箱的网络拓扑怎么摆」仍未定稿：

1. OpenSandbox server 需要挂宿主 `docker.sock`（创建/销毁沙箱容器），是**高权限容器**；
2. app 与沙箱容器**不能同网**（否则 app 可直连沙箱容器，攻击面扩大）；
3. `useServerProxy` 与否取决于拓扑（SDK 默认直连 execd 端口，跨网不可达）。

## 2. 决策一：`opensandbox-server` 独立容器 + 独立网络 `opensandbox-net`，**app 不加入**

**决策**：

```text
opensandbox-net（独立 bridge）
   └── opensandbox-server（挂 docker.sock，发布 8090）
app 容器 ── 不加入 opensandbox-net ── 只经 HTTP（宿主发布端口 / 独立别名）访问 server:8090
```

**理由**：

| 项 | 结论 | 依据 |
| ---- | ---- | ---- |
| 独立网络 | ✅ 采用 | server 高权限（docker.sock），与 app 同网会放大 app 被攻破后的横向移动面；独立网络 + 仅 8090 暴露 = 最小面 |
| app 加入网络 | ❌ 否决 | 与「app 与沙箱容器同网」红线冲突（计划 §4.6 裁定） |
| SDK 连接 | `useServerProxy(true)` | app 与沙箱 execd 不同网，execd 请求须经 server 代理（`LOG-20261017-009` 实测：默认直连 `host.docker.internal:<port>/proxy/<id>/ping` 不可达；`useServerProxy(true)` 后 GATE PASS） |

**实现**：`docker-compose.yml`（本机 dev）与 `docker-compose.server.yml`（生产）均新增
`opensandbox-server` 服务 + `configs.opensandbox-config`（config.toml 注入）+ `networks.opensandbox-net`；
**生产 compose 全部纯英文注释**（部署文件纯 ASCII 铁律，2026-10-07）。

## 3. 决策二：`api_key` 走 config.toml `[server]` 段，compose `OPEN_SANDBOX_API_KEY:?` 必填

**决策**：OpenSandbox server 的 `api_key` 必须写在 config.toml `[server]` 段
（`api_key = "${OPEN_SANDBOX_API_KEY:-dev-test-key}"`，compose 插值渲染）；compose `environment` 里的
`OPEN_SANDBOX_API_KEY` 只是 HTTP 头名，**非配置注入点**（`LOG-20261017-008` 实测：只设 environment 时
server 反复重启，报 `server.api_key is empty in non-interactive mode`）。

**生产**：`OPEN_SANDBOX_API_KEY:?` 必填校验（缺 env 时 `docker compose config` 直接报错）。

## 4. 决策三：沙箱镜像源走阿里云 ACR 镜像加速；`python:3.12` 预热服务

**决策**：

- execd / egress 两镜像与业务镜像均用**阿里云张家口 ACR**（`sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/...`），
  规避 Docker Hub 拉取超时（本机实测 `python:3.12` 直拉 26 分钟未完成）；
- compose 新增 `opensandbox-images` 预热服务（`image: python:3.12`、`pull_policy: always`、
  `restart: "no"`、`command: ["sleep", "infinity"]`），供 `docker compose pull opensandbox-images` 预拉
  （用户 2026-10-17 指定：python:3.12 写进 compose 由用户自行下载）。

## 5. 决策四：探针的失败路径也必须销毁沙箱

**决策**：`OpenSandboxProbe` 在 `finally` 中**无条件** `kill()` + `close()`。

**依据**（`LOG-20261017-009` / `-010` 实测）：SDK 的 `close()` 只关本地 HTTP 资源、**不销毁远端沙箱**，
`kill()` 才删；探针若只 try-with-resources 会在失败路径残留沙箱容器。两条路径（成功 + 故意失败）均已实测
验证容器零残留（`docker ps -a --filter name=sandbox` 仅剩 server 本体）。

## 6. 触发重新评估的条件

```text
① 沙箱内执行 agent 工具链（业务执行链接线）      ⇒ 届时评估 Credential Vault 双层隔离 + 资源配额 + 时间线登记
② 沙箱容量进入规模阈值（并发沙箱数 / 宿主资源）   ⇒ 评估 K8s / 独立沙箱集群
③ OpenSandbox server 升级破坏 SDK 契约           ⇒ 重跑 SDK gate + 探针（S-3/S-4 验证集）
```

## 7. 后果

- **正面**：S-5 部署拓扑定稿（独立网络 + 8090 最小暴露 + api_key 正确注入 + 镜像源加速）；
  探针可重复真实验收（成功/失败双路径销毁）。
- **代价**：① server 高权限容器（docker.sock）是新增攻击面——已用独立网络 + 必填 api_key 收敛，
  但**生产上线前须再过一遍部署安全审计**（差距表 `G-017` 相关）；② `python:3.12` 镜像体积大，
  预拉耗时（已用 ACR 加速 + 预热服务）。
