# HelloAI Phase 2 B2：Credential Vault 收口（N-004）执行方案

> 主轴：把差距表 N-004（Credential Vault，PARTIAL P1）收口为 **审计 + Secret 生命周期 + 管理操作面补齐 + 口径登记**。对应 P2-B（P1 PARTIAL 清账批）B2 项（B1 = N-007 已收口，LOG-20260905-009）。
>
> 文档约定：§0 事实基线（2026-09-05 代码盘点）；§1 任务定位与拍板；§2 范围边界；§3 设计（审计 / 生命周期 / 失效 / 迁移口径）；§4 Step 分解与验收口径；§5 工程规范适配；§6 提交约定。
>
> **状态**：已实施（2026-09-05，LOG-20260905-011；核心交付：审计表 V67 + 过期扫描 + revoke 失效 + 管理端点补齐 + 口径登记；差距表 N-004 已按完成规则移除）。

---

## §0 事实基线（2026-09-05 代码盘点）

### 0.1 现状链路

```text
【存储】credential_vault（V14 建表 / V15 防多 ACTIVE 唯一索引 / V45 放开 PLATFORM owner）
        ownerType(AGENT|PLATFORM) + ownerId + provider + credentialType(API_KEY)
        + encryptedValue(AES-GCM) / secretRef + status(ACTIVE|DISABLED|EXPIRED) + expireTime

【加解密】CredentialCryptoService：AES/GCM/NoPadding，nonce 前置，密钥 16/24/32 字节
        （helloai.security.credential.aes-key-base64 或 HELLOAI_CREDENTIAL_AES_KEY_BASE64）

【服务】CredentialVaultService（AGENT/PLATFORM 双级）
        - 查询：getActiveAgentApiKey / getActivePlatformApiKey / listPlatformCredentials
                / listAgentCredentials / hasActive*
        - 保存：saveAgentApiKeyCredential / savePlatformApiKeyCredential（旧 ACTIVE→DISABLED）
        - 轮换：rotateAgentApiKey / rotatePlatformApiKey（旧 ACTIVE→EXPIRED + rotated_from_id 审计链）

【绑定】CredentialVaultBindingService
        bindAgentApiKey（明文→加密落库）/ getAgentApiKeyPlaintext（secretRef 优先→解密兜底）
        / rotateAgentApiKey

【API】CredentialController（/api/credentials，requireAdmin）
        bindApiKeyByAgentId / listByAgentId  —— 无 rotate / revoke / 审计 端点

【消费链】AgentLlmCredentialResolver（平台级 ProviderConfigService 优先 > Agent 级凭证兜底）
        PlatformProviderConfigServiceImpl.getApiKey：vault PLATFORM ACTIVE > yml 兜底（双活已实现）

【双活过渡】vault > yml 已落地：PlatformProviderConfigServiceTest「过渡期双活优先级」测试组；
        ProviderConfigItem.apiKeyFromVault 管理端可标注来源
```

### 0.2 关键事实

1. **`agent.api_key` 不是 LLM 凭证**：V14/V53 注释明示——`agent.api_key` 是外部 Agent 接入鉴权工牌（consumerToken），credential_vault 才是真实 LLM Provider 凭证库。「旧字段下线」不指向它，只做口径登记。
2. **轮换语义已存在但 API 未暴露**：`rotateAgentApiKey`（旧→EXPIRED + rotated_from_id）已实现，`CredentialController` 却只暴露 bind（旧→DISABLED 假轮换）与 list，无 rotate/revoke 管理端点。
3. **审计仅靠 remark**：rotate 时在 remark 累积 `rotated_from_id`，无独立审计表——「异常情况下如何恢复」无审计支撑。
4. **expireTime 死字段**：实体/表有 `expireTime`，写入方支持 expiresAt，但**无任何过期扫描任务**——到期凭证永不被标 EXPIRED，`ACTIVE` 过期后仍在路由（Secret 生命周期治理缺口实案）。
5. **无失效（revoke）操作**：DISABLED 状态存在且被 bind 用作「旧凭证停用」，但无显式 `revoke` 管理入口——「谁可以失效」无落点。
6. **删除策略空白**：EXPIRED/DISABLED 凭证永不清理——「旧 Key 什么时候删除」无口径。
7. **约束**：基线 §17.4「状态避免重复建模」——新增状态必须先确认可否用既有状态推导；§4.3 job 负责定时任务（core 不自行 @Scheduled）；§6.3 Controller 分层红线（编排在 core）；数据库变更必须 Flyway。

---

## §1 任务定位与拍板

**preflight 四问**：改什么——审计（新表 + 写入）+ Secret 生命周期（过期扫描 + 失效操作）+ 管理操作面补齐（rotate/revoke/审计查询端点）+ 迁移/下线/删除口径登记；为什么——N-004 P1 PARTIAL，完成标准五问（谁读/谁轮换/谁失效/旧 Key 何时删/异常如何恢复）中后四问无代码或口径落点，expireTime 死字段与轮换 API 未暴露是实案；类型——补功能（小闭环）+ 文档口径登记；不做什么——不引入 admin 内部分角色权限体系（当前鉴权只有 admin/agent 两级，细粒度权限收口为 admin-only 口径 + 管理端点补齐）、不做密钥物理清理（只状态隔离）、不改 agent.api_key 工牌语义、不新增明文读取 API（最小暴露）。

### 决策记录

| # | 决策点 | 拍板 | 理由 |
|---|---|---|---|
| D-B2-1 | 审计载体 | **新建 `credential_audit_log` 表（V67，本轮唯一 DDL）+ `CredentialAuditService`**（append-only，同事务写入，best-effort 降级日志告警） | `agent_event` 绑定 Run/Turn/Step 执行语义（run_id 必填），塞凭证操作语义不合；remark 255 字符不足承载审计；等保「谁做了什么」需独立可查台账 |
| D-B2-2 | 失效（revoke）语义 | **一律置 `DISABLED`（人为停用语义，与 bind 旧凭证一致），不新增 `REVOKED` 状态** | 基线 §17.4：既有状态可表达，避免状态体系膨胀；DISABLED 已语义明确「人为停用，保留记录不再路由」 |
| D-B2-3 | 生命周期扫描位置 | **helloai-job 定时任务 `CredentialExpireTask`**（ShedLock + fixedRate + enabled 开关 + 批 100，参照 `InboxExpireCleanupTask`） | CODE_STYLE §4.3：定时任务归 job；core 不自行 @Scheduled |
| D-B2-4 | 过期/停用清理策略 | **不物理删除，仅状态隔离（ACTIVE→EXPIRED）+ 审计保留**；物理清理口径关闭（留未来容量治理） | fail-close + 审计可追溯（A3 归档软删同哲学）；删除不可逆且破坏审计链 |
| D-B2-5 | 明文读取 | **不新增明文查看 API**；明文仅执行链内部解析（`getAgentApiKeyPlaintext` / `resolveApiKey`），管理端只见脱敏标志（hasEncryptedValue/hasSecretRef/apiKeyFromVault） | 最小暴露：明文 API 面每多一处即多一个泄漏面；「谁可以读取」收口为 admin 可读元数据 + 执行链内部解明文的既成事实 |

---

## §2 范围边界

**做**：

1. 审计：`credential_audit_log`（V67）+ `CredentialAuditService.record(...)`；`bind / rotate / revoke / expire` 四类动作写入（bind/rotate 内嵌，revoke/expire 显式）；`listAudits(credentialId/owner, page, size)` 查询
2. 生命周期：`CredentialVaultService.expireOverdue(batchLimit)`（ACTIVE 且 `expire_time < now` → EXPIRED，CAS 防并发 + 审计）；`CredentialExpireTask`（helloai-job，ShedLock + fixedRate 5min + `helloai.credential.lifecycle.expire-scan-enabled` 开关 + 批 100）
3. 失效：`CredentialVaultService.revokeCredential(id, operator)`（ACTIVE/DISABLED 状态行 → DISABLED，CAS + 审计；EXPIRED 行拒绝——轮换淘汰不可逆）
4. 管理 API 补齐（`CredentialController`，全部 requireAdmin）：`rotateByAgentId`（绑定服务已有语义）/ `revoke/{id}` / `listByAgentId` 已有 / `audits?credentialId=&ownerType=&ownerId=`（审计查询）
5. 口径登记：迁移（双活 vault>yml 已闭环，迁移完成 = vault ACTIVE PLATFORM 存在）/ 旧字段下线（agent.api_key 工牌语义，不指向）/ 删除策略（不物理删）——入本方案 §3.5 与差距表收口说明
6. 定向单测 + core/api/job 全量回归 + 差距表 N-004 收口 + LOG-20260905-011

**不做**（明文边界）：

- 不引入 admin 内部分角色权限体系（权限差距收口为口径登记 + 管理端点补齐）
- 不新增明文查看 API（D-B2-5）
- 不做密钥物理清理（D-B2-4）
- 不改 `agent.api_key` 工牌语义 / 不做工牌迁移
- 不动 `CredentialCryptoService` 加密算法与密钥轮换（独立主题，超出本轮）
- 不把 `agent_event` 当凭证审计载体

---

## §3 设计

### 3.1 审计（S1）

```text
新表 credential_audit_log（V67）
  id, credential_id, owner_type, owner_id, provider,
  action(BIND|ROTATE|REVOKE|EXPIRE), operator, detail,
  create_by/update_by/create_time/update_time/deleted/remark（BaseEntity 同款通用审计列）
  索引：idx_credential_audit_log_credential_id(credential_id, create_time)
        idx_credential_audit_log_owner(owner_type, owner_id, create_time)

写入：CredentialAuditService.record(credentialId, ownerType, ownerId, provider, action, operator, detail)
  - 与调用方事务合并（同线程传播），任一遍写失败整体回滚
  - 调用方按审计 write-only 纪律 try-catch 降级（失败 log.error 告警，不阻断 bind/rotate/revoke 主操作）
  - detail 记录关键事实：如 rotated_from_id、过期前 status、expiresAt
```

- 审计动作枚举：`BIND`（绑定/保存）/ `ROTATE`（轮换，旧→EXPIRED 新→ACTIVE）/ `REVOKE`（人为停用）/ `EXPIRE`（过期扫描自动失效）——action 存 snake_case 字符串，硬编码常量即可，不进枚举类（避免枚举膨胀，与 AgentEventType 风格区分）。
- operator：Admin 操作传 `admin`（当前无登录用户模型，鉴权面只有 admin/agent 两类身份）；定时任务传 `system`。

### 3.2 Secret 生命周期（S2）

```text
CredentialExpireTask（helloai-job，5min fixedRate + ShedLock + enabled 开关 + 批 100）
   │  调 credentialVaultService.expireOverdue(batch)
   ▼
expireOverdue(batchLimit)
   │  1) 查询 ACTIVE 且 expire_time IS NOT NULL AND expire_time < now（LIMIT batch）
   │  2) 逐行 CAS UPDATE SET status='EXPIRED' WHERE id=? AND status='ACTIVE'（防并发）
   │  3) 命中 → CredentialAuditService.record(..., EXPIRE, 'system', 'expire_time elapsed')
   │  4) 返回处理行数，>0 记日志
```

- `expireTime` 死字段激活：写入方（save/bind 的 expiresAt）已有，扫描兜底标 EXPIRED，`getActive*` 查询天然不再命中（只查 ACTIVE）。
- 与人工 revoke 互斥：CAS `WHERE status='ACTIVE'` 保证同一行不会同时被扫描与 revoke 双写。

### 3.3 失效操作（S3）

```text
revokeCredential(id, operator)
   │  CAS UPDATE SET status='DISABLED' WHERE id=? AND status IN ('ACTIVE','DISABLED')
   │    └─ 影响 0 行（不存在 或 已 EXPIRED）→ 抛 BizException「仅 ACTIVE/DISABLED 可停用，EXPIRED 不可逆」（fail-close）
   ▼
   CredentialAuditService.record(..., REVOKE, operator, 'manual revoke')
```

- 语义对齐 bind：bind 旧凭证也是置 DISABLED（人为停用），revoke 复用同一语义，状态机不新增。

### 3.4 管理 API 补齐（S3）

```text
CredentialController（/api/credentials，全 requireAdmin）
  POST /api/credentials/rotateByAgentId/{agentId}   → bindingService.rotateAgentApiKey（旧→EXPIRED）
  POST /api/credentials/revoke/{id}                 → credentialVaultService.revokeCredential(id, "admin")
  GET  /api/credentials/audits?credentialId=&ownerType=&ownerId=&page=&size=
       → credentialVaultService.listAudits(...)     → 分页审计列表
  既有：bindApiKeyByAgentId / listByAgentId 不变
```

- Controller 仅参数接收 + DTO 装配 + `R` 封装，编排全在 core 服务（§6.3 红线）。

### 3.5 口径登记（差距表 N-004 收口口径）

| 差距子项 | 收口口径 |
|---|---|
| 完整迁移 | 已闭环（双活 vault>yml，`PlatformProviderConfigServiceImpl.getApiKey` 优先级 + 测试覆盖）；迁移完成 = 目标 provider 存在 vault PLATFORM ACTIVE 记录（`isApiKeyFromVault=true`）；yml 仅作老环境兜底，不反向迁出 |
| 旧字段下线 | `agent.api_key` 是外部 Agent 接入工牌（consumerToken），非 LLM 凭证，**不可下线**（V14/V53 注释既定语义）；credential_vault 自身无冗余旧字段，无需下线迁移 |
| 双活过渡策略 | 已实现（vault > yml，管理端 `apiKeyFromVault` 标注来源）；登记为既成事实 |
| 更细粒度权限 | 收口为 admin-only 口径（AuthInterceptor 两级鉴权现状）+ 管理操作端点补齐（rotate/revoke/审计）；不引入 admin 内部分角色（避免过度设计） |
| 审计 | 本方案 §3.1 落地（`credential_audit_log` + 四动作写入 + 可查询） |
| Secret 生命周期治理 | 本方案 §3.2/§3.3 落地（过期扫描→EXPIRED + 人工 revoke→DISABLED）；清理策略：不物理删除，状态隔离 + 审计保留（D-B2-4） |

---

## §4 Step 分解与验收口径

| Step | 内容 | 依赖 | 验收口径 |
|---|---|---|---|
| S0 | 本方案文档定稿 + 用户拍板 D-B2-1~5 | — | 决策记录齐备；§3 覆盖审计/生命周期/失效/口径 |
| S1 | V67 `credential_audit_log` + `CredentialAuditService`（record 同事务 + listAudits 分页）+ `CredentialVaultService` 扩展（revokeCredential / expireOverdue）+ bind/rotate 审计落点 + 单测 | S0 | 单测：audit 落点（bind/rotate 内嵌 + revoke/expire 显式）；revoke CAS（ACTIVE→DISABLED；EXPIRED 冲突抛错）；expireOverdue（过期→EXPIRED + 审计 + 无过期零动作 + batch 截断 + CAS 并发只生效一次）；审计查询分页 |
| S2 | `CredentialExpireTask`（helloai-job，ShedLock + 5min + 开关 + 批 100）+ `helloai.credential.lifecycle.expire-scan-enabled` 配置 + 单测 | S1 | 单测：开关关闭 noop；正常调 expireOverdue(100)；service 异常捕获不外抛（参照 InboxExpireCleanupTaskTest） |
| S3 | `CredentialController` 补 rotateByAgentId / revoke / audits 端点 + DTO + 单测 | S1 | api 单测：3 端点转发与返回封装正确；controller 无编排（只调 core/binding 服务）；requireAdmin 生效 |
| S4 | 全量回归 | S1~S3 | core 基线 1118 + 新增全绿；job 66 + 新增全绿；api 27 + 新增全绿；job 零意外改动 |
| S5 | 文档回填：差距表 N-004 收口 + LOG-20260905-011 + 本方案 R1 | S4 | 差距表无 N-004 残留；LOG 完整记录决策与验证；基线 §16 能力边界同步 |

---

## §5 工程规范适配

- **DDL**：V67 唯一新表（BaseEntity 通用审计列 + payload-free 明细列 + 双索引）；不破坏 V14/V15/V45 既有约束
- **api 分层红线（§6.3）**：Controller 仅参数接收 + DTO 装配 + `R` 封装；编排（CAS/审计/扫描）全在 core 服务接口
- **job 职责（§4.3）**：定时任务归 helloai-job，ShedLock + enabled 开关 + 批上限 + 异常只告警不抛出（InboxExpireCleanupTask 同模式）
- **状态避免重复建模（基线 §17.4）**：revoke 复用 DISABLED，不新增 REVOKED；过期复用 EXPIRED
- **审计 write-only 纪律（B2 埋点）**：record 同事务，调用方 try-catch 降级，失败 log.error 不阻断主链路
- **CAS 幂等**：revoke（WHERE status IN ACTIVE/DISABLED）、expireOverdue（逐行 WHERE status='ACTIVE'）——重复触发/并发零副作用
- **测试**：S1 纯 Mockito + lambda 查询（TableInfo 预热先例：`CredentialVaultBindingServiceTest` / `AgentCommandOutboxServiceImplTest`）；S2 参照 `InboxExpireCleanupTaskTest`；S3 参照 `AdminMqRecoveryControllerTest`

---

## §6 提交约定

- 代码笔（core + job + api + yml + V67 + 测试）与文档笔（本方案 + 差距表 + LOG）分离提交
- commit message 走 UTF-8 文件 + `git commit -F`；git push 由用户执行

---

## 修订记录

### R1（2026-09-05）：B2 执行方案定稿

- **背景**：P2-B 清账批 B2（B1 = N-007 已收口）。差距表 N-004（PARTIAL P1）完成标准五问中后四问无代码或口径落点。
- **拍板**：D-B2-1=新建审计表 / D-B2-2=revoke 复用 DISABLED / D-B2-3=job 定时扫描 / D-B2-4=不物理清理 / D-B2-5=不新增明文 API。
- **范围**：审计 + 生命周期 + 失效 + 管理端点补齐 + 口径登记；V67 唯一 DDL。

### R2（2026-09-05）：实施完成

- **实施**：V67 `credential_audit_log` + `CredentialAuditService`（record 同事务 / listAudits 分页）+ `CredentialVaultService` 扩展（revokeCredential CAS / expireOverdue 扫描 / listAudits）+ bind/rotate 审计落点；`CredentialExpireTask`（helloai-job，5min + ShedLock + `helloai.security.credential.lifecycle.expire-scan-enabled` 开关 + 批 100）；`CredentialController` 补 rotateByAgentId / revoke / audits 端点（全 requireAdmin）；§3.5 口径登记；V67 唯一 DDL。
- **验证**：core **1131**（基线 1118 + 13：audit 3 + vault 10）全绿；api **32**（+5）全绿；job **69**（+3）全绿；Failures/Errors=0。
- **工程坑**：MP 3.5.9 `lambdaQuery()` chain 在 mock 环境不可用（走 MybatisMapperProxy 解析），新增查询改显式 `baseMapper + LambdaQueryWrapper`；本项目无 `conditions.Wrappers` 类（在 `core.toolkit`），统一 LambdaQueryWrapper。
- **回填**：差距表 N-004 按完成规则移除（总览行 + §9 章节，后续重排编号，结论清单去「Credential Vault 完整化」，最后更新 2026-09-05）；基线文档 §14/§16 同步；LOG-20260905-011。
