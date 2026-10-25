# HelloAI OpenSandbox 沙箱集成计划

> **Status**: `Active`（S-1~S-6 已全部落地 2026-10-17；**S-7/S-8/S-9 执行链已接线 2026-10-17**，见 `archive/implemented/HelloAI 沙箱执行链设计方案（2026-10-17）.md`；**S-10 危险命令守卫未实现**，登记不启动；本计划作为 OpenSandbox 集成唯一事实源保留在办，`REF-3` 预案未触发保持原状。**2026-10-23 复核：`S-1`~`S-9` 逐项在代码与测试中落地；`S-10` 属后置登记不启动项、非待办。同目标的执行链设计文档已于同日归档 `Done`（见 `archive/implemented/HelloAI 沙箱执行链设计方案（2026-10-17）.md`）；本计划因承担「集成事实源」职责继续留在 `plan/`。**）
> **依据**: 用户指令（2026-10-17）——「OpenSandbox 我已下载（`E:\workspace\OpenSandbox-main`），设计 Plan 将沙箱集成到当前项目」；用户裁定集成目标 = **能力预研 + 契约落地**（`S-1`~`S-6`，不触发 `G-005` 重新排期）。
> **调研支撑**: `doc/research/沙箱运作机制与HelloAI沙箱设计分析（2026-10-17）.md`（三仓源码实测 + 可引入开源沙箱评估，首选 OpenSandbox）。
> **最后更新**: 2026-10-25（新增 §4.4.1「按子任务复用 + 工作区回读」，`ADR-005` / `LOG-20261025-005`、`-010`）

---

## 0. 定位与边界（★先读）

**问题来源**：

- **用户指令（2026-10-17）**：「OpenSandbox 我已下载（`E:\workspace\OpenSandbox-main`），设计 Plan 将沙箱集成到当前项目」。
- **调研支撑**：`research/沙箱运作机制与HelloAI沙箱设计分析（2026-10-17）.md`（三仓源码实测 + 可引入开源沙箱评估）。
- **差距表锚点**：`G-005`（Sandbox Provider，三个触发条件均未命中）。
- **起点事实**：此前 `ExecutionPolicy` 全为 `NONE`/`PARTIAL`，平台没有任何真实隔离环境（见 `EnvironmentSandboxProvider.java:47-52`）。

**本计划做什么**：把 OpenSandbox 作为 HelloAI **首个真实沙箱提供方**接入 `SandboxProvider` 契约层——完成声明式 spec、诚实五边界策略、OpenSandboxProvider 实现、探针、部署拓扑。**结果是平台首次具备「可诚实标注 `ISOLATED`」的执行环境**（此前 `ExecutionPolicy` 全 `NONE`/`PARTIAL`，见 `EnvironmentSandboxProvider.java:47-52`）。

**本计划不做什么**（边界，防越界）：

1. **不触发 `G-005` 重新排期**——`G-005` 三个触发条件（① 碰宿主工具 / ② 技能包被执行 / ③ 平台自持浏览器）**均未命中**：本计划不接业务执行链（`RuntimeTurnExecutor.observeSandbox` 保持 best-effort 日志），不执行技能脚本，不改浏览器网关。
2. **不宣称「安全沙箱已完成」**（《协作规约》§22 + 《目标架构》§7 + 差距表 §6）：`ExecutionPolicy` 五边界**逐项诚实标注**（见 §4.3），不标全 `ISOLATED`。
3. **不把 `docker.sock` 挂进 app 容器**（`D-2026-10-09-4③` 裁定）：OpenSandbox **server** 独立容器挂 `docker.sock`，app 只经 HTTP API + `api_key` 访问，且**不加入**沙箱服务网络。
4. **不引入第二套审批/权限通道**：沙箱管理走既有管理端权限码体系；沙箱工具若未来开放，走 `McpMcpServer` 守卫（红线④）。

**诚实边界**（差距表 §6 口径）：本计划落地的 `S-3` `OpenSandboxProvider` 是**真实隔离**（容器），但它**尚未被任何执行链消费**——`SandboxProvider.resolve` 的消费方 `RuntimeTurnExecutor:324` 仅做观测日志。计划完成后须在差距表 `G-005` 行登记「能力预研已落地、未触发排期、执行链未接线」的事实状态。

---

## 1. 编号建号声明（防撞号）

> 参照 `M-x` 建号先例（`D-2026-10-13-11`：`REF-x` 号段已占满 1~8 不扩号段，另立号段）。本计划新建号段：

| 编号 | 含义 |
|---|---|
| `S-x` | **OpenSandbox 沙箱专项任务**（2026-10-17 建号，`x` 取 1~6）：实施见 `plan/HelloAI OpenSandbox 沙箱集成计划.md`。与 `REF-x.y` / `M-x` 并列，**不占 `REF-` 号段** |

**登记动作**：本表须同批登记进差距表 §0「编号口径」表（防与 `archive/**` 冻结号段撞号；实测 `doc/archive/**` 无 `S-` 号段 ⇒ 不撞号）。

---

## 2. 现状（勘察结论，`[实测]`）

### 2.1 OpenSandbox 能力速览（`E:\workspace\OpenSandbox-main`）

| 项 | 事实 |
|---|---|
| 仓库 | `github.com/opensandbox-group/OpenSandbox`（Apache-2.0，CNCF Landscape） |
| Server | `server/`，Docker 运行时（挂 `/var/run/docker.sock`），配置 `~/.sandbox.toml`（`[server]`/`[runtime]`/`[docker]`/`[egress]` 等） |
| 认证 | `OPEN-SANDBOX-API-KEY` 头（`server/opensandbox_server/middleware/auth.py:37`）；**不配 `api_key` 则启动要求显式风险确认** |
| Kotlin SDK | Maven Central **`com.alibaba.opensandbox:sandbox:1.1.1`**（7 天前发布；依赖 kotlin-stdlib 2.2.21 / OkHttp 4.12 / SLF4J 2.0.9 / OTel 1.51 —— 与 HelloAI Java17 栈兼容） |
| 生命周期 API | `POST /sandboxes`（image+entrypoint+resourceLimits+networkPolicy+timeout）、`GET/PATCH/DELETE /sandboxes/{id}`、`/pause`、`/resume`、`/renew-expiration`、`/snapshots`（`specs/sandbox-lifecycle.yml`） |
| 网络策略 | `networkPolicy.defaultAction`（`deny`/`allow`）+ `egress` 白名单/黑名单（`deny-with-allowlist` 示例：deny 默认 + 放行 `pypi.org`） |
| Credential Vault | **凭据侧车**：真凭据由 host 侧 SDK 写入 egress sidecar，沙箱进程只拿假/空值；需 `[egress].mode="dns+nft"` + `networkPolicy` + `credentialProxy.enabled`（`docs/guides/credential-vault.md`） |
| 资源限制 | `resourceLimits.cpu`（如 `"500m"`）/ `memory`（如 `"512Mi"`） |

### 2.2 HelloAI 接缝（`[实测]`）

| 接缝 | 位置 | 现状 |
|---|---|---|
| 契约接口 | `SandboxProvider.java` | `resolve(SandboxContext) → Sandbox`，null 语义（不抛异常） |
| 策略模型 | `ExecutionPolicy.java` | 五边界 record（filesystem/network/process/resource/credential）+ `IsolationLevel`（ISOLATED/PARTIAL/NONE）+ `noIsolation()`/`remoteTerminal()` 工厂 |
| 唯一实现 | `impl/EnvironmentSandboxProvider.java` | 按 `AgentAccessType` 路由：`RemoteAgentEnvironment` → `remoteTerminal()`，其余 → `noIsolation()`；全 `NONE`/`PARTIAL`，**无 `ISOLATED`** |
| 消费方 | `RuntimeTurnExecutor.java:324` | `observeSandbox`：resolve 后仅 `log.info`，**best-effort 不阻断、不参与业务决策** |
| 上下文 | `SandboxContext.java` | `(accessType, taskId, subTaskId, agentId)` |
| 部署拓扑 | `docker-compose.server.yml` | 服务 = postgres/redis/rabbitmq/minio/app/web；app 端口 `127.0.0.1:6565` |
| 凭据红线 | `HELLOAI_CREDENTIAL_AES_KEY_BASE64`（compose:317 必填） | 可解 `credential_vault` 全量凭据 ⇒ **沙箱容器绝不注入 app env** |
| 工具面 | `McpMcpServer` 13 个 `@Tool` | 全订单生命周期操作，无 shell/文件写（`G-005` 现状） |
| SSRF 守卫 | `REF-5.4`（`OutboundUrlGuard`） | 平台自发出站已两层防线；沙箱出站走 OpenSandbox `networkPolicy`，同向 |

### 2.3 触发条件核对（`G-005` 三个条件均未命中）

| 条件 | 核对 | 结论 |
|---|---|---|
| ① 平台增加碰宿主工具 | 本计划不新增任何 agent 可调用工具；`ProcessBuilder` 全仓仅 `PgDumpRunner`（备份例程，判不触发，`D-2026-10-10-2②`） | 未命中 |
| ② 技能包被执行 | 平台不执行技能脚本（`REF-1.6` 只做安装/解析/校验）；本计划不接 | 未命中 |
| ③ 平台自持浏览器 | 浏览器走 `BrowserAgentGateway` 外部网关；本计划不改 | 未命中 |

---

## 3. 任务表（S-1 ~ S-6）

| 项 | 动作 | 验收 | 状态 |
|---|---|---|---|
| **S-1 声明式 spec** | 定义 `SandboxSpec`（纯声明）：`type=opensandbox` + `image` + `entrypoint` + `resourceLimits(cpu,memory)` + `networkPolicy(defaultAction,egress[])` + `scope(agent/user/fixed)` + `timeoutSeconds`。**纯声明，不得内嵌 Docker API 调用参数**（对齐 `REF-3.2` 预案；否则将来换独立服务要返工） | 同一份 spec 能渲染出 OpenSandbox `POST /sandboxes` 请求体；单测断言字段映射 | ✅ 落地（`c34c6742`，`LOG-20261017-008`） |
| **S-2 诚实五边界策略** | `ExecutionPolicy` 补 `openSandbox()` 静态工厂，五边界逐项诚实标注（见 §4.3）；**不得**标全 `ISOLATED` | 单测：`openSandbox()` 返回五边界与 §4.3 一致 | ✅ 落地（`c34c6742`，`LOG-20261017-008`） |
| **S-3 OpenSandboxProvider** | 新增 `impl/OpenSandboxSandboxProvider`（`SandboxProvider` 实现）：引入 Kotlin SDK；`resolve(SandboxContext)` 在 `SandboxSpec` 配置存在且 `enabled=true` 时返回带 `openSandbox()` 策略的 `Sandbox`，否则返回 `null`（沿既有 null 契约）；**scope=agent 命名复用**（同 Agent 复用同一沙箱，对齐 `REF-3.5`）；**显式回收、不自动销毁** | 单测：`SandboxContext` → 返回 `providerName="opensandbox"`、五边界非 `NONE` 的 `Sandbox`；`enabled=false` → `null`；同 Agent 复用断言 | ✅ 落地（`f16191df` + SDK gate PASS `d09ab20c`，`LOG-20261017-009`） |
| **S-4 探针** | 照抄 `octop-harness probe_backend`：写→读→删真实往返（`.helloai-probe-<uuid>.txt`），**失败路径也 `destroy()`**；结果落 timeline（可观测登记，对齐 `REF-3.4`） | 探针用例：真实 server 环境写读删回环 `ok`；失败路径容器已销毁 | ✅ 落地（`330d2e6c`；真实探针双路径验收，`LOG-20261017-010`） |
| **S-5 部署拓扑** | `docker-compose.server.yml` 增 `opensandbox-server` 服务（独立网络 `opensandbox-net`，**app 不加入**；挂 `docker.sock`；`api_key` 必配）；app 侧新增配置 `helloai.sandbox.opensandbox.{url,api-key,enabled}`（**默认 `enabled=false`**，对齐 `REF-6.13` 新能力默认关闭）；`deploy/*.sh` 与基线技术栈表同步 | 部署拓扑变更清单落 ADR；compose 起服后 `GET /health` 可达；`enabled=false` 时 app 零行为变化 | ✅ 落地（`330d2e6c` + `ADR-003`；compose `/health` 200 实测） |
| **S-6 验证集** | 单测（spec 渲染 / 五边界 / scope 复用）+ 探针用例（含失败路径回收）+ `verify-agent-execution-preview.ps1` / `verify-c3-events.ps1`（`ENVIRONMENT_RESOLVED` 语义不变）+ `bash scripts/ci/ci-gate.sh` | 全套绿；回归断言既有沙箱观测日志语义不变 | ✅ 落地（`verify-opensandbox-contract.sh` 静态守卫入 ci-gate 门禁 3；单测 16 例 + 探针双路径全绿） |

> **REF-3 预案对照**：S-1 ↔ REF-3.2（spec）、S-2 ↔ REF-3.1（策略工厂）、S-3 ↔ REF-3.3（独立服务形态）+ REF-3.5（scope 生命周期）、S-4 ↔ REF-3.4（探针）。REF-3 预案为「触发后执行」，本计划在**未触发**状态下以「能力预研 + 契约落地」先行落地 S-1~S-6（用户裁定），`REF-3` 整组状态**保持不变**。

---

## 4. 关键设计决策

### 4.1 Kotlin SDK vs 自研 HTTP 客户端

**选 Kotlin SDK**（`com.alibaba.opensandbox:sandbox:1.1.1`，Maven Central 官方发布）：

- 理由：① 官方维护、协议演进跟随（Credential Vault / snapshot / pause-resume 等新能力零成本获得）；② 依赖栈（OkHttp 4.12 / SLF4J / JDK17）与 HelloAI 完全一致；③ Kotlin/Java 互操作无障碍（`Sandbox.builder()...build()` 可直接在 Java 调用）。
- **注意点（待实测）**：README 示例面向云（`domain=api.opensandbox.io` + `apiKey`）；**自托管场景**需验证 `ConnectionConfig` 支持 `http://localhost:8090`（对照 Python SDK 有 `protocol` 字段）。S-3 落地第一步 = 本地起 server 后用 Java 最小示例实测连接。
- **备选**：若 Kotlin SDK 自托管适配有障碍，退化为**自研轻量 HTTP 客户端**（OkHttp 直连 `POST /sandboxes` / `POST /sandboxes/{id}/executions` / files API，按 `specs/sandbox-lifecycle.yml` + `execd-api.yaml` 实现）——成本约 1 人日，接口面更可控。S-3 内做一次「SDK 可行性实测」gate 决定。

### 4.2 声明式 spec 形状（S-1）

```yaml
helloai:
  sandbox:
    opensandbox:
      enabled: false            # 默认关闭（REF-6.13）；true 时 Provider 才生效
      url: http://opensandbox-server:8090
      api-key: ${OPEN_SANDBOX_API_KEY:}   # 必配（server 不配则拒绝启动）
      spec:                     # SandboxSpec（纯声明）
        type: opensandbox
        image: python:3.12
        entrypoint: ["tail", "-f", "/dev/null"]
        resourceLimits:
          cpu: "500m"
          memory: "512Mi"
        networkPolicy:
          defaultAction: deny   # 默认禁出；白名单按需放行（与 REF-5.4 同向）
          egress: []
        scope: agent            # agent/user/fixed
        timeoutSeconds: 3600
```

### 4.3 诚实五边界（S-2，`ExecutionPolicy.openSandbox()`）

| 边界 | 等级 | 依据 |
|---|---|---|
| filesystem | `ISOLATED` | 沙箱容器独立文件系统，工作区随容器生命周期（OpenSandbox server 强制） |
| network | `PARTIAL` | 容器与 app 网络物理隔离 + `networkPolicy` egress 控制，**但** egress 白名单默认空（`deny`），实际连通性取决于配置；平台未强制「全禁」 |
| process | `PARTIAL` | 容器内进程隔离 + pids 限制，**但**共享宿主内核（非 microVM） |
| resource | `ISOLATED` | `resourceLimits(cpu,memory)` 由 server 强制 |
| credential | `ISOLATED` | 沙箱容器**零平台凭据 env**（不注入 app env；`HELLOAI_CREDENTIAL_AES_KEY_BASE64` 绝不进沙箱）——「执行环境接触不到凭据」是真实隔离事实 |

> **诚实边界**：`ISOLATED` 只标注「该边界由平台强制隔离」——filesystem/resource/credential 三项确实由 OpenSandbox server 强制；network/process 两项存在真实限制但有共享面，标 `PARTIAL`。**不标全 `ISOLATED`**，防「安全沙箱已完成」误读（§0 边界 2）。

### 4.4 生命周期与 scope（S-3）

- `scope=agent`：同一 Agent（`SandboxContext.agentId`）复用同一沙箱（命名容器复用，参照 octop-harness `sandbox_scope` 语义）；`user` 按用户隔离；`fixed` 固定实例。
- **显式回收、不自动销毁**（对齐 `REF-3.5`）：`delete` 时才 `destroy()`；不依赖沙箱自过期兜底。
- **探针（S-4）**：创建沙箱用独立探针 id（参照 Octop `probe.py` 的 `_PROBE_TEST_ID`），探针沙箱 `auto_remove` 语义，失败路径也 `destroy()`。

### 4.4.1 生命周期修订：按子任务复用 + 工作区回读（2026-10-25）

> 依据：`ADR-005`（复用取代「即建即毁」）+ `LOG-20261025-005`（复用落地）/ `-010`（回读落地）。
> 本节**部分取代** §4.4 的「显式回收、不自动销毁」口径：回收时机从「一次命令」改为「一次子任务 turn」。

- **按 `subTaskId` 复用**：`SandboxSlotPool` 按 key 缓存沙箱、槽内命令串行、成功即 `renew` 续期；
  **turn 结束**由 `LocalExecutionCommandConsumer.runViaRuntime` 的 `finally` 调 `release` 销毁；
  超时（`124`）/ SDK 异常（`-1`）丢弃重建，普通非零退出**不丢**（那正是「校验上一轮产物」的正常用法）。
- **★工作区回读**（`SandboxExecutionService#readWorkspaceFile`）：turn 末把工作区里
  「**含 Markdown 标题且最大**」的 `.md` 以 **base64** 回读；对话正文不构成可交付正文时顶替它。
  判据三连：对话正文不可交付 + 工作区正文可交付 + **工作区正文至少是对话正文的 2 倍**。
  理由：平台只采集**对话正文**，工具给了写文件能力却不给回读路径 ⇒ 正文写出来也交不出去
  （`LOG-20261025-009` 实测：3008 字正文留在 `/workspace/draft.md`，对话区只有 604 字节过程独白）。
- **回读是只读探测**：无活跃槽直接返回 `null`，**不为读文件新建沙箱**（3 秒 + 一份资源，且必读空目录）。

### 4.5 凭据红线（贯穿全程，最高优先级）

1. **沙箱容器绝不注入 app env**——`HELLOAI_CREDENTIAL_AES_KEY_BASE64` 及任何平台凭据**不进**沙箱容器（KIMI 评估 §4 红线；`docker-compose.server.yml:317` 的密钥只属 app）。
2. app → OpenSandbox server 的认证 = **独立 `api_key`**（`OPEN-SANDBOX-API-KEY` 头），与平台凭据体系**分离**；`api_key` 存配置（.env / secret），不进 `credential_vault`（那是平台供应商凭据）。
3. **Credential Vault（后置增强，本计划不落地）**：将来沙箱内 agent 需调外部服务时，用 OpenSandbox Credential Vault（真凭据在 host 侧 SDK 写入 egress sidecar，沙箱进程只拿假值）——**与平台 `credential_vault` 双层隔离**，不在本计划范围。

### 4.6 网络隔离（S-5）

- **拓扑**：`opensandbox-server` 独立容器 + 独立网络 `opensandbox-net`；**app 容器不加入**该网络（app 只经 HTTP API 访问 server 的 `8090`，走 compose 服务名解析需显式加入——**裁定：app 不加入 `opensandbox-net`，经宿主发布端口或独立 network 别名访问**，避免「app 与沙箱容器同网」扩大攻击面）。
- **沙箱容器网络**：`network_mode=bridge`（OpenSandbox server 默认）+ `networkPolicy.defaultAction=deny`（默认禁出）+ egress 白名单按需放行。

### 4.7 配置与开关

| 项 | 值 | 说明 |
|---|---|---|
| `helloai.sandbox.opensandbox.enabled` | `false`（默认） | 对齐 `REF-6.13`：新能力默认关闭、通过注入启用 |
| `helloai.sandbox.opensandbox.url` | `http://localhost:8090`（dev）/ compose 内地址（server） | |
| `helloai.sandbox.opensandbox.api-key` | 环境变量注入 | 生产必配 |
| `helloai.sandbox.opensandbox.spec` | 见 §4.2 | `SandboxSpec` 纯声明 |

---

## 5. 依赖与实施次序

```text
S-1（spec 纯声明）→ S-2（诚实策略工厂）→ S-3（Provider + SDK 可行性实测 gate）
  → S-4（探针，依赖真实 server）→ S-5（部署拓扑 + 配置）→ S-6（验证集 + 门禁）
```

- **S-1 / S-2 无外部依赖**（纯契约层），可先行。
- **S-3 的 gate**：本地起 `opensandbox-server`（Docker）→ Java 最小示例用 Kotlin SDK 连接（`http://localhost:8090`）实测创建/执行/销毁 → 通过则用 SDK，失败则退化自研 HTTP 客户端。
- **S-4 / S-5 依赖真实 server**：单测用 mock（不依赖 Docker）；探针用例标注「需真实 server 环境」（对齐 `REF-3.3`：「Testcontainers 只证明本机能起容器，不证明部署形态可行」）。

## 6. 引用材料

- `[实测]` `E:\workspace\OpenSandbox-main\`：`README.md` / `server/docker-compose.example.yaml` / `server/configuration.md` / `docs/guides/credential-vault.md` / `specs/sandbox-lifecycle.yml` / `sdks/sandbox/kotlin/README.md` / `sdks/sandbox/kotlin/sandbox/build.gradle.kts` / `server/opensandbox_server/middleware/auth.py`
- `[实测]` HelloAI：`ExecutionPolicy.java` / `SandboxProvider.java` / `Sandbox.java` / `SandboxContext.java` / `impl/EnvironmentSandboxProvider.java` / `RuntimeTurnExecutor.java:318-333` / `docker-compose.server.yml`
- `[实测]` `doc/HelloAI 实现差距表.md`（§0 编号口径 / §2 G-005 / §6 诚实边界）
- `[实测]` `doc/plan/HelloAI 借鉴落地实施计划.md` §5（REF-3 沙箱预案）
- `[外部]` Maven Central `com.alibaba.opensandbox:sandbox:1.1.1`（2026-10-17 检索）
- 调研支撑：`doc/research/沙箱运作机制与HelloAI沙箱设计分析（2026-10-17）.md`
