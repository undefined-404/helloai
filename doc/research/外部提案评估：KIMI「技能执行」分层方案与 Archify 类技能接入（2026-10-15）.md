# 外部提案评估：KIMI「技能执行」分层方案与 Archify 类技能接入

> **状态：Analysis**
>
> 最后更新：2026-10-15
>
> **勘察口径**：`[实测]` = 本仓源码 / 配置实测（附 `path:line`）；`[外部]` = 对本仓**外部**材料的阅读（KIMI k3 的分层设计提案、`archify` 技能包内容），非本仓事实；`[推断]` = 未实测的判断，须先验证再当结论。
>
> **本文件不含排期与进度**（《文档体系分类与治理规则》§3.6）；可执行结论若进入实施，**另立 `design/` 设计文档或差距表条目**。
>
> **2026-10-15 复核订正（3 处，记录 `LOG-20261015-007`）**：① **§5 表行②与同节 bullet 自相矛盾**（表写「否（进程外隔离）」、bullet 写「②/③ 一律重新排期」）⇒ 归一到**机械判据**「平台侧是否出现一个 *agent 可调用* 且 *碰宿主* 的工具」，先例 `D-2026-10-10-2②`（`PgDumpRunner`）；② §5「`REF-8.4` 未落地」**已过期** ⇒ 四件套 **2026-10-13 已交付**（`09ecd747` / `3b5224a6` / `873bb559`），形态 = 循环自有 `LoopHook` 三档，**非** Spring AI Advisors；③ 「拦截链 = A+B **唯一挂点**」口径**已作废** ⇒ **B 收敛熔断挂拦截链；A 能力闸出列、落 `ResilientDispatcher`**（`plan §10.7.2`）。

---

## 1. 本文件回答什么

1. KIMI k3 提出的「技能执行」分层方案——**哪些判断成立、哪些是本仓已有组件的重复造轮子、哪个是致命缺陷**；
2. 「Archify 类技能」（Node CLI + 清单文件的混合技能）在本平台**该怎么接**才与既有架构一致；
3. 它与既有条目（`G-005` 沙箱 / `REF-3` / `REF-8.3` 技能加载）的**触发关系**——回答「是否与技能加载相关、是否该一起改」。

---

## 2. 先厘清：技能**加载**与技能**执行**是两个面

| 面 | 含义 | 本仓现状 |
|---|---|---|
| **技能加载** | 把技能目录与规范正文**给模型看/按需取** | ✅ **已交付**（`REF-8.3`：`internal_list_skills` 全量目录 + `internal_load_skill` 惰性加载正文） |
| **技能执行** | 跑技能**自带的脚本**（如 `bin/*.mjs`） | ❌ **无通道**（此前无活文档登记，本文件首次登记） |

`[实测]` **`requiredTools` 确实已被消费**：`AgentRuntimeContextAssembler.java:160` 与 `RuntimeTurnExecutor.java:115` 均执行 `mergeTools(tools, resolved.requiredTools())`（G-004 增量 A）——技能声明的**工具名**会并入本轮启用工具集。
`[实测]` **但工具实现只有两种来源**：平台内 Java `@Tool` 方法，或外部 MCP Server。`helloai-core/src/main/java/com/helloai/core/agent/**` 内 `ProcessBuilder` / `Runtime.getRuntime` **0 命中** ⇒ **平台当前不存在「按技能包内脚本执行」的通道**。

> 这与 `G-022` 未达成项③ 是同一事实的两个说法：缺口**不在**「工具名并集」，而在「**工具名的实现从哪来**」。

---

## 3. KIMI k3 分层方案：逐项裁决

`[外部]` KIMI 提案的组件：`./data/skills` 目录 + `SkillRegistry` 扫描 + 渐进披露 + `ProcessBuilder` 调 node + MinIO/RMQ 集群分发。

| KIMI 提议 | 本仓已有 | 裁决 |
|---|---|---|
| `./data/skills/` 目录 + 扫描清单 | `SkillPackageScanner`（classpath）+ `DbInstalledSkillPackageSource`（PG）**双源** | **重复**：已有等价物，且双源是**有意设计** |
| `SkillRegistry` 启动扫描 + 注册表 | `SkillPackageCatalog`（懒加载 + 快照缓存） | **重复** |
| 「zip 解压放进去即可」 | `SkillPackageZipGate`（大小 / 条目数 / 路径穿越 / 符号链接 / UTF-8 五类闸）+ `SkillPackageAuditService`（供应链审计）+ 版本状态 `ACTIVE`/`HISTORICAL`/`DISABLED` + `activate` 回滚 | **会绕过整条供应链闸门** ⇒ 不可采纳 |
| 常驻清单 + 命中注入全文（渐进披露） | `internal_list_skills` + `internal_load_skill`（`REF-8.3`） | **重复**：已实现 |
| MinIO 分发 + RMQ 广播 + 本地缓存原子替换 | 技能元数据在 **PG** ⇒ 多节点天然共享 | **新增了不存在的问题**（本仓无需分发） |
| 产物 → MinIO → 签名 URL | `ExecutionArtifactService` / `AttachmentPort` → MinIO | **重复** |
| 执行失败把 stdout 诊断回灌模型，让模型按 `supportedFixes` 自修 | **无**（本仓执行链缺该能力） | **★值得采纳**（对应 archify 自带的「最多两轮修正」闭环） |
| 优雅降级：node 不在 ⇒ 技能不可用但其余功能正常（不 fail-fast） | **无** | **★值得采纳**（与平台「条件可用语义位」同向） |
| `skill_exec(...)` 用 `ProcessBuilder` 调 node | **无**（真缺口） | **真缺口，但必须按安全边界重做**——见 §4 |

**事实性小错（`[实测]`）**：KIMI 猜基础镜像为 alpine / `apk add`；本仓运行时是 `eclipse-temurin:17-jre-noble`（Debian，已 `apt-get install postgresql-client-16`）。另 `./data/` 在本仓已是**运行期产物目录**（`data/artifacts`，已 gitignore、0 文件入库）⇒ 放技能会混淆「长期资产」与「任务产物」。

---

## 4. ★致命缺陷（KIMI 漏项）：同容器 `ProcessBuilder` 跑第三方 Node = 交出凭据主密钥

`[实测]` app 容器**必须**注入 `HELLOAI_CREDENTIAL_AES_KEY_BASE64`：

- `docker-compose.server.yml:317` —— 该 env 以 `${HELLOAI_CREDENTIAL_AES_KEY_BASE64:?...}` 声明为**必填**，缺失即 compose 直接报错；同文件 `:45` 注明「REQUIRED，须与库内密文匹配」。
- `helloai-common/.../config/CredentialCryptoProperties.java:12` 绑定该值为 `aesKeyBase64`；`helloai-common/.../crypto/CredentialCryptoService.java:45,73,90` 用它做 AES-GCM 解密。
- 该密钥解密的是 `credential_vault` 内的**全量平台供应商 API Key**；agent 的 `apiKey` 亦为 AES-GCM 密文（`AgentApiKeyCipher`）。

`[外部]` `archify` 是**第三方代码**（`.workbuddy/skills/archify/package.json`：`license: MIT`、`bin/archify.mjs`）。
`[推断]`（可直接引用上列实测支撑）若在 app 容器内 `ProcessBuilder("node", "…/archify.mjs", …)`：该脚本可经 `process.env.HELLOAI_CREDENTIAL_AES_KEY_BASE64` 取到密钥 → 解密全部供应商密钥；且同 compose 网络可直连 PG / Redis / MinIO。

**KIMI 的类比不成立**：其把「Node 与 PostgreSQL / Redis 同性质」并列论证「装 node 无额外风险」——
但 PG / Redis 是**被动服务**（由你配置、不主动执行你的代码）；跑第三方脚本是**主动代码**，**继承容器的身份、环境变量与网络**。
**KIMI 自身亦矛盾**：既称 archify「纯本地程序，不联网」，又建议设 `ARCHIFY_UPDATE_CHECK_DISABLED=1`「避免它在容器里尝试联网检查版本」`[外部]` —— 后者等于自认脚本会联网。

**救法**：Node 执行必须挪到**独立 sidecar 容器**（不注入 app 的环境变量、不接 PG 网络）——设计本身可救，**隔离位置**不能错。这与 `G-005` 已裁定的沙箱形态（**独立沙箱服务**，不走 `docker.sock` 挂载路线，`D-2026-10-09-4③`）**同向**。

---

## 5. 接入路径三选（成本递增）与既有条目的触发关系

| 路径 | 内容 | 平台改造量 | 是否触发 `G-005` |
|---|---|---|---|
| ① **外部 `CLI_CLIENT` Agent 承载** | 由外部执行者在**它自己的终端**跑 node 技能 | 零 | **否** |
| ② **包成外部 MCP 工具** | 把技能执行器做成**进程外** MCP Server，平台只做工具调用 | 中（需 wrapper） | **否**，**以「与 app 容器真实隔离」为前置**（独立容器 + 不注入 app 的 env + 不接 app 网络）；**只把进程起在 app 容器内、共用同一份 env ⇒ 等同路径③** |
| ③ **补沙箱执行** | 平台代为执行 | 大 | **是**（触发条件① + ②） |

**★触发判据（钉死口径，2026-10-15 订正）**：看**「平台侧是否出现一个 *agent 可调用* 且 *碰宿主* 的工具」**，**不看「是否进程外」**。

本仓已有判据先例：`core/system/backup/PgDumpRunner.java:25-27` 自述为「本仓库第一处应用执行宿主外部可执行文件的能力」，并明文裁定它**不触发** `G-005` 条件①（`D-2026-10-10-2②`）——依据是该条件的语境是 **Agent 可调用工具面**（判据逐主体分析的是「agent 能碰什么」），而备份是**管理端例程、非 agent 工具**。据此：

- **路径①** —— 平台侧零变化，**不触发**（最贴合既有定位「平台是派单方 ≠ 执行方」）。
- **路径②** —— **不触发**，但**「进程外」不是形态描述、而是必须举证的安全断言**（须能举证「独立容器 + 不注入 app 的环境变量 + 不接 app 网络」）；**举证不出来即按路径③ 判**。
- **路径③** —— 触发条件① + ②（`REF-3` 从「条件触发」转排期并出 ADR）。

**★与既有条目的关系（回答「是否和技能加载相关」）**：

- **与 `REF-8.3`（技能加载）无关**：加载已交付，且**不涉及**脚本执行。两者是相邻但不同的面（§2）。
- **与 `G-005`（Sandbox Provider）直接相关**：`G-005` 的**触发条件②** 原文即「**技能包要被执行**」（见差距表 `G-005` 行）——本项正是该条件的**具体候选**。按上列判据：落**路径① / ② 不触发**（② 须举证隔离）；**落路径③（或路径② 举证不出隔离）** ⇒ `G-005` 应**重新排期**（形态与预案见 `plan/HelloAI 借鉴落地实施计划.md` §5）。
- **与 `REF-8.4`（拦截链）间接相关**：若技能执行成为工具，则工具面变更须经 `REF-8.4` 的挂点做审计。**该口径已于 2026-10-15 订正两处**：① `REF-8.4` **已交付**（2026-10-13 四件套）——形态 = 循环自有 `LoopHook` 三档（`beforeRequest` / `onRequestError` / `beforeTerminate`），**不是** Spring AI Advisors（原「未落地」表述已过期）；② **「拦截链 = A+B 唯一挂点」不再成立** —— **B 收敛熔断挂拦截链，A 能力闸出列、落 `ResilientDispatcher`**（`plan §10.7.2`，判据 = A 天然不在链上：它须在 `assignNext` 提交点之前否决）。

⇒ **结论：不应「和技能加载一起改」**（加载已完成、无改动项），而应：**先按 §5 裁定路径**（①/②/③），**若落 ③ 则与 `G-005` 沙箱同批排期**。

---

## 6. 结论

1. **KIMI 方案不按原样执行**：约 6 类组件本仓**已有且形态更优**（双源目录 / 供应链闸门 / 渐进披露 / PG 免分发 / 产物通道），照抄会**降级**现有能力并绕过安全闸门。
2. **其中 2 条值得采纳**：执行失败 stdout 回灌模型自修；node 缺失时优雅降级（技能不可用但平台其余功能正常）。
3. **必须先解决安全边界**：任何「平台代跑第三方脚本」的实现，**不得**与 app 容器共享环境变量与网络（§4）。
4. **正确次序**：先**裁定路径**（§5 三选）→ 若落「平台代执行」则**并入 `G-005` 沙箱专项**（条件②已被具体化）→ 再立 `design/` 与差距表条目。
5. **本文件是 Analysis 层**：不含排期；落地动作须另立 `design/` 或差距表条目（治理规则 §3.6）。

---

## 7. 关联

- 上游材料：会话内对 KIMI k3 提案的评估（`doc/log/` 未登记，本文为**首次活文档登记**）· `.workbuddy/skills/archify/`（技能包本体）
- 相关差距项：`G-005`（Sandbox Provider，条件触发）· `G-021`（内部 LLM 能力面）· `G-022` 未达成项③（`eng-*` 装进工具授权集）
- 相关计划：`plan/HelloAI 借鉴落地实施计划.md` §5（沙箱预案，形态 = 独立沙箱服务）
- 相关决策：`D-2026-10-09-4③`（沙箱形态）· `D-2026-10-09-6③`（REF-3 降级为条件触发）· `D-2026-10-10-1`（技能包落受控存储，不碰宿主文件系统）· `D-2026-10-10-2②`（`ProcessBuilder` 判为不触发 `G-005` 条件①，即本次钉死判据的先例）· `D-2026-10-15-6`（`REF-8.4` 交付回填 + A/B 挂点订正）
- 订正留痕：`LOG-20261015-007`（本文档 3 处口径订正）
