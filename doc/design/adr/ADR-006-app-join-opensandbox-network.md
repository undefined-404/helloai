# ADR-006：app 加入 `opensandbox-net`（取代 ADR-003 §2「app 不加入」）

| 项目       | 内容 |
| -------- | ---- |
| **编号**   | ADR-006 |
| **状态**   | Accepted（2026-10-27） |
| **日期**   | 2026-10-27（现象与修复均发生在同日） |
| **取代**   | [`ADR-003`](ADR-003-opensandbox-deployment-topology.md) §2「`opensandbox-server` 独立容器 + 独立网络 `opensandbox-net`，**app 不加入**」；《`HelloAI OpenSandbox 沙箱集成计划`》§4.6 同口径裁定 |
| **关联文档** | [`ADR-003`](ADR-003-opensandbox-deployment-topology.md)（部署拓扑，其余决策继续有效）、`doc/plan/HelloAI 技能执行面接入实施计划（2026-10-26）.md` `SKX-15`（部署面收口）、`LOG-20261027`（现象取证与逐层剥离） |
| **决策类型** | 生产拓扑 / 容器网络可达性 |

---

## 1. 背景与问题

`SKX-12` 上线到服务器后，技能执行全部失败，错误是 `sandbox create failed (opener returned null)`。
逐层剥离出**三层断裂**（`LOG-20261027` 问题域 D）：

1. **配置层**：`application-dev.yml` 只有 `enabled`，没有 `url` / `api-key` 占位符 ⇒ compose 注入的
   `SANDBOX_*` 是**白注入**（yml 里没有 `${...}` 消费点；relaxed binding 要
   `HELLOAI_SANDBOX_OPENSANDBOX_*` 前缀才会绑）。
2. **地址层**：compose 默认 `SANDBOX_URL=http://127.0.0.1:8090`，而**容器内的 `127.0.0.1` 是容器自身**。
3. **网络层（本条 ADR 的核心）**：改 `host.docker.internal` 也**无效**——`opensandbox-server` 的端口
   映射是 `"127.0.0.1:8090:8090"`，**刻意只绑宿主回环**。Docker 对回环发布生成的 DNAT 规则钉在
   `-d 127.0.0.1/32`，**任何容器（含 `host.docker.internal` 路径）都连不上**。

修完前两层仍失败，说明**问题不在地址字符串，在「回环发布 = 只给宿主的进程看」这条机制上**。
改地址救不了它。

## 2. 候选方案与取舍

| 方案 | 做法 | 结论 |
|---|---|---|
| **A. 把发布改成 `0.0.0.0:8090`** | compose 端口去掉 `127.0.0.1` 前缀 | **否决**。`opensandbox-server` 挂 `docker.sock`，等价宿主 root；把它的 API 暴露到所有网卡 = 把 root 等价的建容器能力交给内网任意主机 |
| **B. 加一个反代容器（socat/nginx）** | 反代容器同时连 `default` 与 `opensandbox-net` | 可行但**多一跳、多一个容器、多一处失效点**；本质是把「谁能进这个网」这件事挪到另一个进程上，没减少攻击面 |
| **C. app 自己加入 `opensandbox-net`，用服务名寻址** | `SANDBOX_URL=http://opensandbox-server:8090`，app `networks: [default, opensandbox-net]` | **采纳**。少一跳、无新增容器、compose 原生能力 |
| **D. server 加第二张网卡 + app 只连该网** | server 双网络、app 只加入第二张 | 等价 C（同样让 app 进到能看见 server 的网），但配置更绕、排障更难 |

**选 C 的理由**：方案 A 才是真正的红线（暴露 root 等价接口），B/D 都在做同一件事的冗长版本。
C 的代价是「app 与沙箱容器同网」，这一条必须正视——见下。

## 3. 决策

**app 加入 `opensandbox-net`，以服务名访问 `opensandbox-server:8090`。**

1. `deploy/app/docker-compose.yml`：app 服务 `networks: [default, opensandbox-net]`；
   `SANDBOX_URL=http://opensandbox-server:8090`。
2. `opensandbox-server` 的端口发布**保持 `127.0.0.1:8090:8090` 不变**——宿主侧仍只有本机可访问，
   对外暴露面零变化。
3. **`ADR-003` 的红线继续有效**：`docker.sock` 仍**不**挂进 app 容器；app 仍只经 HTTP API + `api_key`
   访问 server。本条只改「app 在哪个网里」，不改「app 怎么调 server」。

## 4. 代价与缓解（必须随代码一起记住的副作用）

**代价**：app 与沙箱容器同网 ⇒ 沙箱容器理论上可达 app:6565 与同网其他服务。
这是原先「app 不加入」要挡的东西，现在挡不住了，只能靠别的层挡：

1. **沙箱默认拒绝出网**（`SKX-5` 已收紧，实测 `HTTP 200` → `ENOTFOUND`）：沙箱内进程主动外联默认不可能；
   需要出网的规格必须显式加 `network-policy.egress` 白名单。
2. **沙箱内不注入任何凭据**：技能包只有代码，`api_key` 由平台侧持有；`HELLOAI_CREDENTIAL_AES_KEY_BASE64`
   等主密钥不进沙箱 env。
3. **待办（未做，记为风险而非已解）**：app 的 6565 是否可只绑 `default` 网络、`postgres`/`minio`
   是否需从 `opensandbox-net` 侧再收敛，均未验证。当前状态是「默认拒绝出网 + 不带凭据」两道防线，
   不是「网络隔离」。

## 5. 后果

- `ADR-003` §2 与《OpenSandbox 沙箱集成计划》§4.6 的「app 不加入 `opensandbox-net`」**作废**，
  改为本条口径；两份文档均加指针，避免后人照旧口径改回去（改了就连不上，且症状是同一条
  `opener returned null`，极难定位）。
- 服务器实测：修完后沙箱建起（3 个 node 沙箱 + 3 个 egress sidecar），archify 出图成功。
- 遗留风险见 §4 第 3 条，`SKX-15` 的剩余动作里不含这项（它属安全加固，另议）。
