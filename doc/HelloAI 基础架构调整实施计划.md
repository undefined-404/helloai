# HelloAI 基础架构调整实施计划

> **状态：ACTIVE**
>
> **定位**：平台基础架构（底座）专项调整，独立于《HelloAI 重构实施计划》的
> 业务主线（Event Stream → Dual Executor → AgentRuntime → Capability）。本专项聚焦
> 「用户 / 角色 / 权限 / 菜单」平台底座，与目标架构的业务五层**正交不冲突**，
> 作为平台治理的支撑底座纳入目标架构（见《HelloAI 目标架构》§12）。
>
> **编号体系：`BASE-xxx`**（批次.序号），与既有编号体系完全错开：
> 差距表 `G-xxx`、重构实施计划 `P0~P3 / A1~A7 / S1~S8`、迁移 `V70+`、
> 迭代记录按日期——**互不混用，不沿用任何既有编号**。
>
> 最后更新：2026-09-12（**三批次（BASE-1.x / 2.x / 3.x）全部落地 PASS**：V79~V83 迁移 + 动作级权限码 + SaInterceptor 注册修复 + 前端动态路由 + v-auth + 菜单管理页 + 授权差异更新 + 路由渲染增强 + 部门 + 数据权限（受控枚举）；**授权补充 V84**：ADMIN 角色绑定部门管理权限；**收口 V85 / V86**：岗位能力彻底移除（含遗留数据物理清理）+ 拆出独立「菜单管理」入口。core 1437 用例 0 失败 / UI build / Docker + 浏览器实测通过，实测修复 4 个缺陷。详见 `log/2026-09.md`。合规基线见 §4）
>
> 最后更新：2026-09-13（**批次四（BASE-4.x）立项**——三批次落地后代码核查暴露三处缺口：
> ① Sa-Token 认证未收口（全仓 0 处 `StpUtil.checkLogin()`，`active-timeout` 滑动续期实际失效）；
> ② 身份数据双写不一致（`sys_user.role` 死字段 + `create()` 漏写权威关联表）；
> ③ 接口授权覆盖极低（`@SaCheckPermission` 仅 24 / 234，业务面 125 接口全无授权，只读角色无法落地）。
> 批次四任务明细见 §9（权限矩阵见 §9.4），全批次红线见 §10，验收见 §11）
>
> 最后更新：2026-09-13（**勘误**：Sa-Token 会话 Redis 键前缀为 `X-Admin-Token:`——取自配置项
> `sa-token.token-name`，非 Sa-Token 库默认的 `satoken:`；§2 S2、差距表 G-012、`log/2026-09.md`
> 同步更正，证据与影响见 §12）

# 1. 背景与定位

## 1.1 起因

用户诉求：完善系统底层架构（用户登录 / 菜单权限 / 角色授权）。原登录为自建实现
（AuthService 自建 Redis Token 会话 + AdminOnlyInterceptor 的 `/api/admin/**` 前缀二元判断），
无统一鉴权体系。已于 2026-09-12 完成第一阶段（Sa-Token 底座 + RBAC 四表 + 三管理页 +
菜单树 DB 化 + 存量会话无缝迁移，见 §3 已完成基线）。

## 1.2 本专项范围

参考同级目录标杆项目 **JeecgBoot-main**（`/Users/shihang/IdeaProjects/JeecgBoot-main`）的
菜单 / 用户 / 角色 / 权限实现，对齐其成熟设计，分批次补齐 HelloAI 基础架构差距：

```text
批次一（BASE-1.x）：前端动态路由 + 按钮级权限（RBAC 闭环关键）
批次二（BASE-2.x）：菜单管理页 + 角色授权差异更新（可运维）
批次三（BASE-3.x）：扩展能力（隐藏路由/外链/数据权限，按需）
```

## 1.3 与目标架构的关系（融合点）

本专项属平台**基础架构（底座）**，非业务层新能力，融合进目标架构即新增 §12「基础架构
（平台底座）」：RBAC 是 Governance 层之下、所有业务模块（任务 / 子任务 / Agent / 质量 /
事件流…）共用的「用户-角色-权限-菜单」支撑底座。业务五层（Planning / Orchestration /
Runtime / Capability / Provider）不做任何改造，本专项**不触碰**：

- Agent Event Stream / 业务状态机（Event 契约不变）
- Scheduler / Workflow Runtime / Review Runtime（不新增第二套）
- 外部 Agent（CLI_CLIENT）契约（API Key / MCP 不变，不进入 Sa-Token 会话体系）

# 2. 已完成基线（G-012 S1~S8，2026-09-12，本专项不重复实施）

> 以下能力已落地并 Docker 实测通过，作为本专项起点（编号沿用 G-012 阶段号，仅作历史引用）。

- **S1 数据层**：V77 迁移四表（sys_role / sys_permission / sys_user_role / sys_role_permission）
  + 内置种子（SUPER_ADMIN 全权限 `"*"` / ADMIN 显式权限码）+ 存量 sys_user.role 单字段迁移关联表；
  V78 扩展 sys_permission 增加 parent_id / path / icon（菜单树 DB 化数据底座）。
- **S2 会话层**：AuthServiceImpl 切 Sa-Token（StpUtil.login / getLoginIdByToken / logoutByTokenValue，
  X-Admin-Token 头，active-timeout=28800s 滑动续期，Redis 会话键前缀 = `sa-token.token-name`
  （本项目为 `X-Admin-Token:`，**非** Sa-Token 库默认的 `satoken:`——勘误见文末 §12）；
  **存量会话无缝迁移**：Sa-Token 未命中时回退旧 Redis key（`auth:admin:token:{token}`），
  以原 token 值重建会话并删旧 key（前端零改动）。
- **S3 授权层**：StpInterfaceImpl（用户 → 角色码 + 权限码，SUPER_ADMIN 返回 `"*"`）；
  SaInterceptor 注册 /api/** 注解鉴权；GlobalExceptionHandler 补 NotLoginException→401 /
  NotPermissionException→403。
- **S4 前端菜单过滤**：登录响应携带 permissions/roles；auth store 持久化权限码 + hasPermission（"*" 通配）。
- **S5 三管理页**：UserList.vue / RoleList.vue / PermissionList.vue（用户分页+搜索+编辑+分配角色+
  重置密码 / 角色 CRUD+权限绑定 / 权限码列表+搜索）；路由 system/users|roles|permissions；
  rbac.ts + paths.rbac + types/system.ts。
- **S6 菜单树 DB 化**：MenuController `/api/admin/menus/tree` + SysPermissionQueryService.listMenuTree
  （type=MENU + 权限码过滤 + parent 挂接 + 空父剔除）；MainLayout 改调菜单树接口动态渲染
  （icon 按 @element-plus/icons-vue 组件名映射）。
- **S7 基础设施修复**：分页 total 恒 0 根因二连（mybatis-plus-jsqlparser-4.9 显式引入 +
  mybatis-plus 3.5.9→3.5.12）。
- **S8 验证**：core 全量单测 1399 用例 0 失败；api 58 用例 0 失败；UI type-check + 构建通过；
  Docker 实测全链路 200（登录 /me、菜单树 18 根节点、角色 CRUD+权限绑定、用户分页 total=2、
  testuser1 角色分配、旧会话无缝迁移）。

## 2.1 现状已知差距（相对 JeecgBoot 标杆，本专项要补齐的）

| 维度 | HelloAI 现状 | 标杆（JeecgBoot） | 归属批次 |
|---|---|---|---|
| 前端路由 | 静态全量注册，菜单树只管侧边栏显隐；无权限 URL 仍可达页面（靠后端 401/403 兜底） | 后端菜单动态 addRoute，无权限 URL 404 | 批次一 |
| 按钮级权限 | 仅 store.hasPermission 方法，全仓无一处使用 | v-auth 指令 + codeList 按钮权限码 | 批次一 |
| 权限码粒度 | 资源级（user:manage / role:manage） | 动作级（system:user:add/edit/delete） | 批次一 |
| 菜单树字段 | 仅 path/icon，无 component 等路由渲染信息 | component/url/keepAlive/hidden/leaf 全量 | 批次一 |
| 菜单管理 | PermissionList 只读权限码列表；菜单树只能 SQL 维护 | 可视化菜单树 CRUD（add/edit/delete/queryTreeList） | 批次二 |
| 角色授权 | 全量删插（assignPermissions） | 差集增量更新（lastPermissionIds） | 批次二 |
| 扩展渲染 | 无 | 隐藏路由/外链/iframe/keep-alive | 批次三 |
| 部门/数据权限 | 无 | 部门角色 + 数据规则表（ruleFlag） | 批次三（按需） |

# 3. 借鉴标杆设计要点（JeecgBoot，2026-09-12 代码核查）

详见同级目录 `/Users/shihang/IdeaProjects/JeecgBoot-main`。核心结论：

1. **权限模型一分三**：`menuType` 0 一级菜单 / 1 子菜单 / 2 按钮权限；`perms` 权限码支持逗号分隔多码、
   粒度到动作级（`sys:user:add`）；`permsType`(1显示2禁用)+`status` 控制按钮可用性。
   （对应文件：`jeecg-module-system/jeecg-system-biz/.../entity/SysPermission.java`）
2. **后端一次下发「可直接生成路由的菜单树 + 按钮权限码」**：`getUserPermissionByToken` 返回
   menu（含 component/path/name/meta）+ auth + codeList + allAuth。
3. **前端动态路由**：菜单树 → transformObjToRoute → asyncImportRoute（dynamicImport 按 component
   字符串懒加载 .vue）→ router.addRoute；**权限 = 路由可达性**，无权限 URL 直接 404。
4. **按钮级权限**：`v-auth` 指令 + hasPermission hook（BACK 模式比对 codeList）。
5. **后端接口鉴权到方法**：Shiro `@RequiresPermissions("system:permission:add")`。
6. **角色授权差异更新**：`saveRolePermission(roleId, ids, lastIds)` 差集批量增删。
7. **菜单可视化 CRUD**：SysPermissionController add/edit/delete/queryTreeList + 前端菜单管理页。

# 4. 合规基线（强制遵循两份规约）

> 本专项所有批次（BASE-1.x / 2.x / 3.x）必须同时遵循：
>
> - `doc/HelloAI_AI开发协作规约.md`（AI 协作生命周期、验证体系、红线）
> - `doc/HelloAI_CODE_STYLE.md`（工程与代码规范）
>
> 以下条目为本专项强相关条款的落地口径，执行批次时逐条对照；未列条款仍按两份规约全文执行。
> 阶段归类口径：本专项属「平台基础架构（BASE）」专项，**不属于 P0~P3 业务主线阶段**，
> 但完整遵循规约的生命周期、验证与完成报告要求。

## 4.1 开发协作生命周期（规约 §9 / §13 / §34）

- 每个 BASE 批次按 **Step 0~9** 执行：工作区检查 → 读文档 → 读代码 → 输出实现计划 →
  改代码 → 单测/模块测试 → 既有 PS1 验证 → git diff/status → 文档回填 → 完成报告。
- **Step 3 实现计划必须覆盖 14 项**：当前实现 / 当前问题 / 目标状态 / 修改范围 / 修改文件 /
  新增文件 / 删除文件 / DB 影响 / API·MQ 影响 / 兼容性影响 / 风险 / 测试方案 /
  PowerShell 验证方案 / 回滚方案。发现计划外模块必须暂停说明。
- 完成报告使用规约 **§34 的 16 节模板**；验证状态区分 **PASS / FAIL / NOT RUN / BLOCKED**，
  禁止以"应该没问题"代替实际验证。

## 4.2 验证体系（规约 §16 / §23 / §25 / §27，CODE_STYLE §48）

- **优先复用既有 `scripts/powershell/` 验证资产，禁止默认新增 test/verify/smoke 脚本**；
  确需新增须先说明既有资产无法覆盖的理由并经确认。
- 本专项相关既有资产（候选，执行时按实际影响选定）：
  - `verify-admin-authz.ps1` —— `/api/admin/**` 授权（BASE 强化后回归）
  - `verify-code-style-p1-ui-sync.ps1` —— 前端 API 路径 / paths.ts 同步
  - `verify-dependency-direction.ps1` —— 域依赖方向（RBAC 归 system 域不反向依赖业务域）
  - `verify-contract-first.ps1` —— Controller / Service / DTO 契约
- 每个批次按 **Required / Regression / Diagnosis** 三档给出验证集合；`.tmp/` 仅诊断不转正。
- 后端编译基线 `mvn -pl helloai-start -am compile`；单测覆盖权限 / 状态 / 事务边界；
  前端 `npm run type-check` + 生产构建。

## 4.3 架构红线映射（规约 §40 / §41，目标架构 §12.3）

- **不新增第二套权限体系**（唯一事实源 sys_* 表 + Sa-Token StpInterface，不双写）；
- **不触碰 Event / 业务状态机 / Scheduler / Workflow / Review Runtime**（BASE 全批次）；
- **外部 Agent 契约不变**（API Key / MCP，不进 Sa-Token 会话体系）；
- 不借本专项顺手重构无关业务域；不创建平行架构（Old/New Service 并存）；
- 未执行验证即宣布完成 → 禁止。

## 4.4 CODE_STYLE 专项约束（本专项强相关）

- **域归属**（§5.5 / §6）：RBAC 属 `system` 域（`com.helloai.core.system`），遵循
  `system → shared` 底层依赖，**不得反向依赖 agent / task / planner / review 业务域**
  （菜单表只存数据，不引入业务依赖）；API 层经 helloai-api，核心逻辑在 core.system。
- **Controller / Service 分层**（§8 / §10）：Controller 只依赖 Service（Impl 不可被依赖）、
  禁止直捅 Mapper / QueryWrapper / SQL / @Transactional。
- **DTO 投影**（§11.3）：API 层不直接暴露 DB Entity——BASE 批次须核查既有
  `MenuController` / 权限接口是否直接返回 `SysPermission`，如命中则补 DTO / VO 投影。
- **ID 规范**（§12.1）：业务主键 Long + ASSIGN_ID；新增菜单/权限种子沿用。
- **事务**（§14）：菜单 / 角色 / 权限写操作 `@Transactional(rollbackFor = Exception.class)`。
- **Flyway**（§16.1）：V77 / V78 只读，本专项新变更一律 **V79+** 递增，禁止改历史 migration。
- **权限规范**（§43）：认证与授权分离，`/api/admin/**` 必须经 Admin 授权（Sa-Token 会话 +
  动作级权限码），**不以"已登录"放行**。
- **前端规范**（§44 / §45）：API 路径统一 `paths.ts`；页面 → api/*.ts → HTTP 分层；
  v-auth 指令统一走 auth store hasPermission，不在页面散落权限判断。
- **测试**（§47）：权限为优先测试项——每个 BASE 批次补权限单测
  （@SaCheckPermission 正/反向 + v-auth 权限码匹配）。
- **日志**（§27）：RBAC 写操作日志携带 userId / roleId / permissionId / traceId。
- **安全**（§42）：不写死密码 / Key；种子密码走迁移脚本明文已知值，不引入代码硬编码。

## 4.5 文档回填（规约 §32）

- 仅实现功能 → 差距表（G-013 行内登记批次状态）；
- 当前真实架构变化 → 项目基线文档（system 域 / RBAC 底座）；
- 目标边界变化 → 目标架构（§12 已融合，批次如扩展再回填）；
- 专项设计变化 → 本实施计划文档；
- 重大架构决策 → `log/HelloAI 架构变更记录.md`。

# 5. 批次总览

| 批次 | 主题 | 任务号 | 状态 |
|---|---|---|---|
| 批次一 | 前端动态路由 + 按钮级权限 | BASE-1.1 ~ BASE-1.6 | **已落地（2026-09-12，PASS）** |
| 批次二 | 菜单管理页 + 角色授权差异更新 | BASE-2.1 ~ BASE-2.3 | **已落地（2026-09-12，PASS）** |
| 批次三 | 扩展能力（路由渲染增强 / 部门 / 数据权限） | BASE-3.1 ~ BASE-3.3 | **已落地（2026-09-12，PASS）** |
| 授权补充 | ADMIN 角色绑定部门管理权限 | BASE-3.2 授权补充 | **已落地（2026-09-12，PASS）** |
| 收口 | 岗位能力移除 + 拆出独立「菜单管理」入口 | BASE-3.2 / BASE-2.2 收口 | **已落地（2026-09-12，PASS）** |
| 批次四 | 认证收口 + 角色体系 + 全量接口授权化 | BASE-4.1 ~ BASE-4.5 | **部分落地（4.1 / 4.2 / 4.3+4.4 合并 已实施；4.5 待实施）** |

> 三批次落地详情见 `doc/log/2026-09.md` 两段记录；
> 验证基线：core 1437 用例 0 失败（V85 移除岗位 7 用例后）/ api 58 用例 0 失败 / UI type-check + build / Docker API 全链路 / 浏览器实测；
> 实测修复 5 个缺陷（SaInterceptor 未注册 / addRoute 父参数须为 name / 登出后路由权限串用 / catch-all redirect 致登录后 404 / 会话失效静默落 404）。

> **授权补充（V84）**：部门原仅 SUPER_ADMIN（`"*"`）可用，按需将「部门管理」授予 ADMIN 角色
> （`depart:view` MENU + `depart:add/edit/delete` 动作码，共 4 码）——`V84__rbac_admin_depart_permission.sql`
> 幂等固化（`ON CONFLICT DO NOTHING`），等价于「角色管理 → 分配权限」（`lastPermissionIds` 差集，原权限零丢失）。
> 验证：Flyway version=84 success=t 且重复执行 `INSERT 0 0`；ADMIN 身份 `/api/auth/me`
> 含 4 个 depart 码、菜单树含「系统设置 → 部门管理」、部门 tree/新增/删除全 200；测试数据零残留。
> 详见 `doc/log/2026-09.md`「ADMIN 角色绑定部门管理权限」段。

> **收口（V85 / V86）**：① **岗位（position）彻底移除**——V82 引入的岗位判定对本系统无场景（部门已承担
> 组织归属 + 数据权限范围），V85 清 `position:*` 关联并软删 4 码 + `DROP TABLE sys_user_position`/`sys_position`
> （表中 0 行，不可逆），**V86 把软删遗留行物理清理**（`DELETE FROM sys_permission WHERE code LIKE 'position:%'`，
> 含任意 deleted 状态；关联表防御性清理），并删除后端 11 文件与前端 PositionList.vue / 岗位 API·路径·类型 / UserList 岗位列；
> ② **菜单维护独立成页**——V85 新增 `menu:view`（`/system/menus` → `system/MenuList`），新增 MenuList.vue
> （仅 type=MENU 树形 CRUD），PermissionList.vue 收敛为「权限管理」（仅 type=API 权限码 + 数据规则），
> 两页共用 `/api/admin/permissions`。验证：core 1437 / api 58 用例 0 失败；UI 构建通过；岗位端点 404、
> 菜单树无「岗位管理」含「菜单管理」、position 行 0 残留（含任意 deleted 状态）；浏览器实测通过。
> 详见 `doc/log/2026-09.md`「岗位能力移除 + 菜单管理独立入口」段。

# 6. 批次一：前端动态路由 + 按钮级权限（BASE-1.x）

## 目标

补齐 RBAC 闭环关键缺口：**权限 = 路由可达性**（无权限页面 URL 直达 404）+ **按钮级权限控制**。

## 任务明细

- **BASE-1.1 数据层**：sys_permission 新增 `component` 列（菜单组件路径，如 `system/UserList`）
  + V79 迁移 + 存量 15 个 MENU 权限码补齐 component 种子。
- **BASE-1.2 菜单树接口下发路由信息**：`listMenuTree` / MenuController 返回结构补
  component 等渲染字段（前端动态路由的数据源）。
- **BASE-1.3 前端动态路由**：路由守卫 + buildRoutesAction（仿 JeecgBoot transformObjToRoute /
  dynamicImport 懒加载）；静态路由收敛为白名单（/login /setup /404）；无权限 URL → 404。
- **BASE-1.4 权限码动作级细化**：user/role/permission 的 `:view/:add/:edit/:delete` 拆分
  （V80 数据迁移 + ADMIN 种子调整；SUPER_ADMIN 仍 `"*"`）。
- **BASE-1.5 前端 v-auth 指令 + hasPermission 按钮级控制**：用户/角色/权限三页按钮按码显隐。
- **BASE-1.6 后端 @SaCheckPermission 动作级细化**：管理接口权限码从 `user:manage/role:manage`
  拆到动作级；GlobalExceptionHandler 403 语义不变。

## 验收

1. ADMIN（无 user/role/permission 动作码）直接输 `/system/users` URL → 404，侧边栏无入口；
2. 按钮级：有 `:add` 码显示「新增」，无则隐藏；
3. SUPER_ADMIN `"*"` 全量可见/可用，行为回归不变；
4. core/api 单测 + UI type-check + 构建全绿；Docker 实测两角色差异。

# 7. 批次二：菜单管理页 + 角色授权差异更新（BASE-2.x）

## 目标

让 DB 化菜单真正可运维（可视化菜单树 CRUD），角色授权改差异更新。

## 任务明细

- **BASE-2.1 菜单管理 API**：SysPermissionController `add / edit / delete / queryTreeList`
  （菜单 CRUD，含类型 MENU/API、parent、path、icon、component、sort；鉴权 permission:manage）。
- **BASE-2.2 菜单管理页**：MenuList.vue（树形表格 + 编辑对话框 + 配置字段齐全 + 删除子菜单守卫）。
- **BASE-2.3 角色授权差异更新**：`assignPermissions` 支持 lastPermissionIds 差集批量增删
  （全量删插保留为兼容路径或直接替换）。

## 验收

1. 菜单可视化增删改查即时反映到 `/api/admin/menus/tree`（SUPER_ADMIN 视角）；
2. 删除有子节点菜单被拒绝（或级联确认）；
3. 角色授权差异更新日志可观测（仅增删变化项）；
4. 三页 + 菜单页 type-check/构建全绿；Docker 实测。

# 8. 批次三：扩展能力（BASE-3.x，按需）

## 任务明细（候选）

- **BASE-3.1 路由渲染增强**：sys_permission 补 hidden / keepAlive / externalLink 等字段 +
  前端菜单渲染适配（隐藏菜单、外链、iframe）。
- **BASE-3.2 部门**（已落地）：sys_depart + 用户-部门关联 + 部门数据过滤。
  > 岗位（position）原随本项一并引入（V82），后经评估对本系统无实际场景（部门已承担组织归属 +
  > 数据权限范围，岗位无权限/审批/路由配套），已于 **V85 彻底移除**（清权限码 + DROP 两表 + 删前后端代码）。
- **BASE-3.3 数据权限规则**（已落地）：数据规则表 + ruleFlag（行级数据过滤）。
- **BASE-2.2 收口 / BASE-3.2 收口**（已落地）：拆出独立「菜单管理」入口（`menu:view` + MenuList.vue，
  仅 type=MENU 树形 CRUD），原 PermissionList.vue 收敛为「权限管理」（仅 type=API 权限码 + 数据规则）。

## 触发条件

批次一/二验收通过后，按产品需求决定是否开展；无需求则挂起不实施。

# 9. 批次四：认证收口 + 角色体系 + 全量接口授权化（BASE-4.x）

> 立项：2026-09-13。承接批次一~三（RBAC 数据底座 + 管理面闭环），闭合代码核查暴露的三处缺口。
> 本批次是本专项在「底座」定位内的收口批次，**不触碰** Event / 状态机 / Scheduler / Workflow / Review Runtime。

## 9.1 立项背景（2026-09-13 代码核查）

批次一~三交付了「RBAC 四表 + 管理面 4 控制器动作级授权 + 前端动态路由 / v-auth」，但核查发现三处未闭合：

| # | 缺口 | 代码证据 | 实际影响 |
|---|---|---|---|
| 1 | **Sa-Token 认证未收口** | 全仓（除测试）0 处 `StpUtil.checkLogin()`；admin 认证走自建 `AuthInterceptor` → `AuthService.validateAdminToken()` → `StpUtil.getLoginIdByToken()`（只读、不校验 active-timeout、不续期） | `sa-token.active-timeout: 28800` **实际失效**；叠加 `timeout: -1` 后会话永不过期。Sa-Token 退化为「会话存储 + 注解校验器」，与「统一鉴权体系」目标不符 |
| 2 | **身份数据双写不一致** | `sys_user.role` 单字段：授权侧不读（`listPermissionCode` 只查 `sys_user_role`）、前端不渲染（登录消费 `roles[]`、用户列表消费 `roleCodes[]`）→ 已退化为死字段；而 `SysUserServiceImpl.create()` / `AdminInitializer` **只写该字段、不插 `sys_user_role`** | 走 `create()` 建出的账号，授权侧角色为空 → 登录后菜单树空、`@SaCheckPermission` 全 403。当前被 V1 种子 + V77 一次性迁移掩盖，批次四建号功能会直接踩中 |
| 3 | **接口授权覆盖极低** | `@SaCheckPermission` 仅出现在 4 个控制器 / 24 个方法（`SysUserRoleController` / `SysRoleController` / `SysPermissionController` / `SysDepartController`）；全仓 234 个接口中，**管理面 85 个无动作码、业务面 125 个全部无授权** | 任何「只读角色」无法落地：GUEST 登录后仍可 `POST /api/tasks` 建任务、`POST /api/requirement-conversations` 建对话、`POST /api/reviews` 提交审查 |

> 附带缺陷：`WebMvcConfig` 认证白名单排除的是 `/api/agents/register-with-token`（连字符），
> 而 `AgentController` 实际映射为 `/registerWithToken`（驼峰）——白名单未命中，该端点仍要过认证。

## 9.2 目标状态

```text
认证   Sa-Token 标准链路（StpUtil.checkLogin + active-timeout 滑动续期真实生效）
       agent 通道（API Key / MCP）显式旁路，不进 Sa-Token 会话（契约不变）
身份   单事实源 sys_user_role（sys_user.role 物理退场）
角色   SUPER_ADMIN（通配 "*"）/ ADMIN（管理面）/ NORMAL_USER（业务读写）/ GUEST（只读）
授权   适用接口 100% 动作级权限码化：管理面存量补齐 + 业务面写接口授权化 + 前端按钮级对齐
建号   管理员建号（user:add）+ 自助注册（sys_config 开关，默认关闭）
```

## 9.3 任务明细

> **落地进度（2026-09-13）**：BASE-4.1 **PASS**、BASE-4.2 **PASS**（V87 已应用；
> core 1453 / api 58 / job 69 用例 0 失败；E2E 覆盖授权断言一致性、滑动续期、存量会话迁移、
> 建号签发角色与 remark 落库）；BASE-4.3 ~ BASE-4.5 待实施。详见 `doc/log/2026-09.md`
> 「批次四首批落地」段。

### BASE-4.1 认证收口到 Sa-Token 标准链路

- **认证分支改写**：`AuthInterceptor` 的 admin 分支由 `authService.validateAdminToken(token)`
  改为标准 `StpUtil.checkLogin()`——token 由 Sa-Token 依 `token-name: X-Admin-Token` 自行解析，
  从而触发 `checkActiveTimeout` 校验与 `updateLastActiveToNow` 滑动续期（修复缺口 1）。
- **`validateAdminToken` 语义收敛**：仅保留「按 token 回读用户信息」用途（`/api/auth/me` 复用），
  不再作为守门路径；`migrateLegacySession` 存量会话迁移逻辑保留不动（前端零感知）。
- **授权守门改写**：`AdminOnlyInterceptor` 从「读手写 `_authType` attribute」改为
  「`StpUtil.isLogin()` + 角色判定（`SUPER_ADMIN` / `ADMIN`）」，事实源回到 Sa-Token。
- **agent 旁路显式化**：Agent API Key 通道（`/api/mcp/**`、`/api/agents/doorbell/**`、
  `/api/agent/**`、`/api/artifacts/**`、`/api/activities` 写侧等）在认证分发处短路，
  明确「不进 Sa-Token 会话」，保持现有 Bearer 契约不变。
- **修白名单不匹配**：`/api/agents/register-with-token` → `/api/agents/registerWithToken`。

### BASE-4.2 身份数据模型清理（`sys_user.role` 退场）

- **修正建号写入**：`SysUserServiceImpl.create()` 与 `AdminInitializer` 补插 `sys_user_role`
  （角色码 → `sys_role.id` 解析，事务内），杜绝「建了号却无角色」。
- **V87 迁移**：
  ① 防御性补齐历史 join 行（`sys_user.role` → `sys_user_role`，`ON CONFLICT DO NOTHING` 幂等）；
  ② `ALTER TABLE sys_user DROP COLUMN role`。
- **DTO 字段清理**：删 `SysUserItem.role`、`LoginResponse.role`、`AdminSession.role`；
  前端 `types/system.ts` 同步删字段。
- **`SysUserItem` / `LoginResponse` 契约收敛**：角色一律以 `roles[]` / `roleCodes[]` 表达。

### BASE-4.3 角色体系扩充（NORMAL_USER / GUEST）

- **V88 迁移**：新增 2 个内置角色 + 权限绑定（幂等）：
  - **NORMAL_USER（普通用户）**：业务操作者——全部 `*:view` 菜单码 + 业务写动作码
    （任务 / 子任务 / 需求对话 / 审查 / 积分 / 模块 等），**不含**系统设置与管理面
    （user / role / permission / menu / depart / llm-provider / config）。
  - **GUEST（游客）**：**纯只读**——仅 `*:view` 菜单码，**零写动作码**。
    用途：平台公网发布时提供「可浏览系统功能、不可改数据」的演示账户。
- **菜单差异**：GUEST 可见全部只读菜单；系统设置类菜单（`settings:view` 及其子项）不绑定。
- **前端零改动**：动态路由 / 侧边栏 / `hasPermission` 均已按权限码驱动，无需改代码。

### BASE-4.4 全量接口动作级权限化

- **4.4a 管理面存量补齐**：85 个无动作码的管理面接口按「资源:动作」补 `@SaCheckPermission`
  （矩阵见 §9.4）。
- **4.4b 业务面写接口授权化**：业务面 68 个写接口中，**剔除 Agent / 系统身份通道与公开白名单**
  （`McpController` 13、`AgentController` 注册 2、`AgentInboxController` 2、`ArtifactUploadController` 1、
  `ActivityController` 1、`SetupController` 1、`AuthController` login+logout 2，共 22），
  对其余 **46 个**补动作码；业务面读接口保持「登录即可读」，不补码。
- **4.4c 前端按钮级对齐**：业务页面按新动作码补 `v-auth`（与后端码一一对应），
  避免只读角色看到必然 403 的按钮。
- **粗粒度码退役**：`agent:manage` / `user:manage` / `role:manage` / `system:manage` 软删，
  不再新增引用（其覆盖能力由动作码替代）。
- **`CredentialController` 归位**：路径在业务面、语义是管理面（现依赖方法内 `requireAdmin()`），
  本批次纳入动作码统一口径。

### BASE-4.5 建号与注册开关

- **管理员建号**：`POST /api/admin/users`（`@SaCheckPermission("user:add")`）——
  透出 `SysUserService.create()` 能力，支持同时指定角色；UserList.vue 增「新增用户」按钮 + 对话框。
- **自助注册（默认关闭）**：`POST /api/auth/register` 由 `sys_config` 键
  `auth.register.enabled`（**默认 `'0'`**）门控；开启后注册用户默认绑 `GUEST`（最小权限）。
- **登录页注册栈**：开关关闭时保留现有引导文案；开启时渲染真实注册表单。

## 9.4 权限码矩阵（治理口径）

### 9.4.1 命名规范

```text
资源:动作        动作 ∈ { view, add, edit, delete, assign, approve, execute,
                        dispatch, publish, archive, replay, rebuild, claim,
                        submit, complete, rework, block, reassign, pause, resume,
                        send, finalize, regenerate, abandon, retry, adjust, key, ... }
菜单码（type=MENU）沿用既有 `xxx:view`；接口动作码（type=API）一律 `资源:动作`
SUPER_ADMIN 由 StpInterfaceImpl 返回 "*" 通配，自动覆盖全部新增码（无需绑定）
```

### 9.4.2 管理面动作码（BASE-4.4a，新增约 40 码）

| 资源 | 现有码 | 新增动作码 | 覆盖控制器（接口数） |
|---|---|---|---|
| agent 管理 | agent:view(MENU)、agent:manage(退役) | agent:add / agent:edit / agent:delete / agent:key | AdminAgentController（17） |
| llm-provider | — | llm-provider:view / add / edit / delete / key / model | AdminLlmProviderController（15） |
| team | team:view(MENU) | team:add / team:edit / team:archive / team:member | TeamController（9） |
| workflow-template | — | workflow-template:view / add / edit / publish / archive | WorkflowTemplateController（8） |
| workflow-instance | — | workflow-instance:view | WorkflowInstanceController（1） |
| prompt-template | — | prompt-template:view / add / edit / delete | AdminPromptController（7） |
| config | — | config:view / config:edit（替代 system:manage） | AdminConfigController（6） |
| platform-provider | — | platform-provider:view / edit | AdminProviderConfigController（3） |
| quality | quality:view(MENU) | quality:rebuild / quality:dispatch | AdminQualityController（6） |
| mq-recovery | — | mq-recovery:view / mq-recovery:replay | AdminMqRecoveryController（4） |
| duty-lease / browser-session / dashboard / menu | duty:view / browser:view / dashboard:view / menu:view（均 MENU） | 全读接口不补动作码；menu:add / edit / delete 复用 permission:* | 3 / 2 / 3 / 1 |
| RBAC 四控制器 | user:* / role:* / permission:* / depart:*（已授权） | 无需新增 | 24（现状基线） |

### 9.4.3 业务面动作码（BASE-4.4b，新增约 46 码）

| 资源 | 新增动作码 | 覆盖控制器（写接口数） |
|---|---|---|
| task | task:add / task:edit / task:delete / task:plan / task:republish / task:confirm-plan / task:reject-plan / task:report | TaskController（10） |
| subtask | subtask:edit / subtask:claim / subtask:execute / subtask:submit / subtask:complete / subtask:rework / subtask:block / subtask:reassign / subtask:redispatch / subtask:pause / subtask:resume / subtask:status | SubTaskController（16） |
| conversation | conversation:add / conversation:send / conversation:finalize / conversation:regenerate / conversation:abandon / conversation:delete / conversation:retry / conversation:mode | RequirementConversationController（10） |
| review | review:add（review:approve 已有） | ReviewController（1） |
| score | score:adjust | ScoreController（1） |
| module | module:edit | ModuleController（1） |
| prompt-enhance | prompt:enhance | PromptEnhancerController（1） |
| credential | credential:view / credential:bind / credential:rotate / credential:revoke | CredentialController（5） |
| agent-execution | agent-execution:preview | AgentExecutionController（2） |

> **不加权限码的接口**（Agent / 系统身份通道 + 公开白名单，共约 22 个写 + 若干读）：
> `McpController`（13 写）、`AgentController#register / registerWithToken`（2）、
> `AgentInboxController#markRead / archive`（2）、`ArtifactUploadController#upload`（1）、
> `ActivityController#create`（1）、`SetupController#initialize`（1）、`AuthController#login / logout`（2）。
> 这些走 `Bearer` API Key 或公开白名单，加 `@SaCheckPermission` 会直接阻断外部 Agent 与首启流程。

### 9.4.4 角色 × 能力矩阵

| 能力域 | SUPER_ADMIN | ADMIN | NORMAL_USER | GUEST |
|---|:--:|:--:|:--:|:--:|
| 全部菜单可见 | ✅（`"*"`） | 管理面 + 业务面 | 业务面 | 业务面只读菜单 |
| 系统设置（user/role/permission/menu/depart） | ✅ | ✅（按 V77/V84 显式码） | ❌ | ❌ |
| 平台配置（llm-provider / config / prompt-template / platform-provider） | ✅ | ❌（守 V77「管理类仅超管」口径） | ❌ | ❌ |
| 业务读写（任务/子任务/对话/审查/积分/模块） | ✅ | ✅ | ✅ | ❌（写 403） |
| 业务只读（全部 `*:view`） | ✅ | ✅ | ✅ | ✅ |
| 建号入组 | ✅ | ❌（user:add 仅超管） | ❌ | ❌ |

## 9.5 迁移清单（Flyway）

| 版本 | 内容 | 可逆性 |
|---|---|---|
| **V87** | 身份模型收口：防御性补齐 `sys_user.role` → `sys_user_role`；`ALTER TABLE sys_user DROP COLUMN role` | DDL 不可逆（列删除）；补齐段幂等 |
| **V88** | **权限码扩充**：管理面动作码 32 个（id 49~80）+ 业务面动作码 39 个（id 81~119，含 `workflow-instance:add`）；粗粒度码解绑 + 软删（`agent:manage` / `task:assign` / `review:approve` / `system:manage` / `user:manage` / `role:manage`） | 按 `code` 反向 DELETE 可逆 |
| **V89** | **角色扩充 + 权限绑定**：新增 NORMAL_USER（id=3）/ GUEST（id=4）；ADMIN 绑运维管理面 + 业务写码（57 码）；NORMAL_USER 绑业务菜单 + 业务写码（55 码）；GUEST 仅绑 16 个只读菜单码（零写码） | 按 `code` 反向 DELETE 可逆 |

> **顺序修正（2026-09-13）**：原计划为 V88=角色、V89=权限码；但角色绑定依赖权限码先落库
> （`sys_role_permission.permission_id` 取自 `sys_permission`），**顺序必须颠倒**：
> **V88=权限码 → V89=角色与绑定**。已按此实施。

> V77~V86 只读（红线）；新变更一律 V87+ 递增。

## 9.6 验收

1. **Sa-Token 收口**：admin 会话闲置超过 `active-timeout` 后请求返回 401（滑动续期生效）；
   持续活动不断连；`/api/auth/me` 正常回读。
2. **身份单事实源**：`sys_user.role` 列已删除；新建用户后 `sys_user_role` 有对应行；
   登录响应 `permissions` 非空、菜单树非空。
3. **四角色差异可复现**（Docker + 浏览器实测）：
   - SUPER_ADMIN：全量菜单 + 全接口 200；
   - ADMIN：管理面可用、平台配置类 403；
   - NORMAL_USER：业务读写可用、`/api/admin/**` 403、无系统设置菜单；
   - GUEST：`GET /api/tasks` 200、`POST /api/tasks` 403、
     `POST /api/requirement-conversations` 403、`POST /api/reviews` 403。
4. **覆盖度**：适用接口 100% 有动作码（管理面 109 / 业务面适用写 46）；
   Agent 与公开通道零改动（MCP / duty e2e 回归通过）。
5. **回归**：`verify-admin-authz.ps1` 改造前后断言一致；
   core / api 单测全绿；UI `type-check` + 构建通过。

## 9.7 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| 补动作码后 ADMIN / NORMAL_USER 未绑定新码 → 功能回归 | **高** | V89 必须同步为 ADMIN 绑定管理面码、为 NORMAL_USER 绑定业务写码；四角色实测逐条断言 |
| 认证链路改写可能放开 admin 端点 | **高** | 先跑 `verify-admin-authz.ps1` 建基线，改造后同组断言必须一致 |
| `checkLogin()` 误伤 Agent 请求 | **高** | agent 通道显式旁路 + MCP / duty e2e 回归 |
| `DROP COLUMN sys_user.role` 不可逆 | 中 | V87 先补齐 join 行再删列；本地 dev 库先行验证；生产前备份 |
| 自助注册被滥用 | 中 | 默认关闭；开启后默认绑 GUEST 最小权限；开关留审计日志 |
| 全量补码工作量大、易漏 | 中 | 以 §9.4 矩阵为清单逐控制器勾选；补权限单测（正/反向） |

## 9.8 合规基线

沿用 §4（规约 §9/§13/§34 生命周期、§16/§23/§25/§27 验证体系、CODE_STYLE §42~§48），并补充：

- **Step 3 实现计划**：本批次每个子任务实施前按规约 §13 的 14 项输出（本 §9 为批次级计划）。
- **域归属**（CODE_STYLE §5.5/§6）：RBAC 归 `system` 域，不反向依赖 agent / task / planner / review。
- **DTO 投影**（§11.3）：`SysUserItem` / `LoginResponse` 字段变更属契约变更，须同步前端类型。
- **事务**（§14）：`create()` 的用户 + 角色写入须在同一事务内。
- **Flyway**（§16.1）：V87+ 递增，V77~V86 只读。
- **权限规范**（§43）：认证与授权分离，`/api/admin/**` 必须经 Admin 授权，不以「已登录」放行。
- **安全**（§42）：不写死密码 / Key；注册开关默认关闭。
- **文档回填**（§32）：目标边界变化 → 目标架构 §12；专项变化 → 本文档；差距项 → 差距表 G-012/G-013 行内登记。

## 9.9 实施口径与偏差登记（BASE-4.3 / 4.4 合并实施，2026-09-13）

### 9.9.1 授权覆盖实测

- `@SaCheckPermission` 覆盖：**131 处**（原有 24 + 本批新增 **107**：管理面 60 / 业务面 47），分布于 23 个控制器。
- **码 ↔ 注解双向核对**：V88 新增 **71 码全部被至少一个注解引用（零幽灵码）**；注解引用共 88 码 = V88 的 71 + 既有迁移的 17（`depart:*` / `role:*` / `user:*` / `permission:*`）。
- Agent / 公开白名单通道 **12 个控制器实测 0 注解**（McpController / AgentController / AgentDoorbellController / AgentInboxController / ArtifactUploadController / ActivityController / SetupController / AuthController / HealthController / ToolsController / FeedController / AgentEventController），红线未越界。

### 9.9.2 登记偏差

| # | 项 | 说明 | 处置 |
|---|---|---|---|
| 1 | **ADMIN 未授予平台配置码** | `llm-provider:*` / `config:*` / `prompt-template:*` / `platform-provider:*` / `mq-recovery:*` 按 §9.4.4 归 SUPER_ADMIN 专有。ADMIN 仍持有 `settings:view`（V77 绑定；为保留「系统设置 → 部门管理」子树所必需——父菜单不可见则子菜单整体消失） | 无可见回归，原因见 #2 |
| 2 | **`Settings.vue` 当前不可达** | `settings:view` 的 `component` 为 NULL（聚合父菜单），`/settings` 被 `dynamic.ts` redirect 到首个可见子；前端 `router/` 无任何 `Settings.vue` 引用 | 本批**未做** Settings.vue 的 v-auth（页面进不去）。该页可达性属既有问题，另行登记 |
| 3 | **`sys_permission` 存在历史测试残留** | 6 行雪藏 id（2098…）测试数据（`DOCKER:TEST` / `PERM:PROBE` / `UI:TEST` / `TMP:MENU:VERIFY*`），均 `deleted=1`；与 V88 的 id 49~119 **无数字冲突** | 建议清理，非本批范围 |
| 4 | **前端失配 6 处**（子代理如实上报） | ① `SubTaskDetail.vue` 无 subtask 状态按钮，其「人工介入」2 按钮实调 `reviewApi.create` → 按**实际生效码** `review:add` 标注；② `ReviewList.vue` 无提交按钮；③ `QualityDashboard.vue` 无重算/派发按钮（后端有码、前端无落点）；④ `agent:key` 落点在 `AgentDetail.vue`；⑤ `SubTaskList.vue` 无独立「新建/编辑草稿/开始/提交/完成/返工/阻塞」按钮（「快速派发」是唯一新建入口 → `subtask:add`）；⑥ `conversation:mode` 无前端落点 | 按实际生效码标注；无落点的码保留供后端使用 |
| 5 | **补漏 4 处**（父任务收口） | `AgentDetail.vue` 操作区（`agent:edit` ×2 / `agent:key` / `agent:delete`）、`TaskList.vue` 停止（`task:edit`）、`TeamList.vue` 发布（`team:edit`）、`TaskIterationView.vue` 回填历史迭代（`task:report`） | 已补 v-auth |

### 9.9.3 方案 B：管理面路径限定放宽（2026-09-13，经用户确认）

**触发问题（E2E 实测暴露）**：BASE-4.1 把 `AdminOnlyInterceptor` 从「`_authType=="admin"`」收紧为
「`SUPER_ADMIN|ADMIN`」时非管理角色尚不存在；BASE-4.3 引入 NORMAL_USER / GUEST 后，
其可见菜单的**数据接口仍在 `/api/admin/**` 前缀下**，被角色闸在动作码之前拦成 403：

| 菜单码 | 前端数据接口 | 修复前 |
|---|---|---|
| `agent:view` | `/api/admin/agents/list` | 403 |
| `team:view` | `/api/admin/teams` | 403 |
| `browser:view` | `/api/admin/browser-sessions` | 403 |
| `quality:view` | `/api/admin/quality/*` | 403 |
| `duty:view` | `/api/admin/duty-leases` | 403 |

**决策（方案 B，用户选定）**：`AdminOnlyInterceptor` 放宽为**仅判定「平台账号身份」**
（`StpUtil.isLogin()`，即拒绝外部 Agent 的 API Key），不再判角色；授权一律交给动作级权限码。

**同批连带修正**：
- `MenuController` 路径 `/api/admin/menus/tree` → **`/api/menus/tree`**（+ 前端 `paths.ts` 与 2 处注释）。
  理由：「我的可见菜单」属自身资源而非管理面操作；置于 admin 前缀下会被平台账号外的闸拦下，
  移出后仅需登录，且**不违反** §43（它不再是 admin API）。细粒度过滤仍由服务层按当前账号权限码完成。
- `CODE_STYLE §43` 口径修订并留痕：原「`/api/admin/**` 必须经 Admin 授权、不以已登录放行」
  → 现「**授权一律以动作级权限码为准**，路径前缀只承担『平台账号 vs 外部 Agent』的通道区分」。

**代价与风险（已接受）**：
1. 管理面**无动作码的读接口**（agent list / teams / quality overview / duty leases / browser sessions）
   对**任何已登录账号**开放。缓解：这些接口均为只读；写操作全部有动作码守护（131 处覆盖）。
2. 「管理面路径归位」（把业务只读接口迁出 `/api/admin/**`）登记为**后续专项**——
   届时可恢复更严的路径语义。
3. 类名 `AdminOnlyInterceptor` 与现语义不符（历史命名），登记为技术债。

**验证**：`AdminOnlyInterceptorTest` 重写为 2 例（平台账号放行 / 未登录 403）；api 单测 58 → 57（预期）；UI type-check 0 error；全 reactor BUILD SUCCESS。


# 10. 红线与约束

- **不新增第二套权限体系**：唯一事实源仍是 sys_user_role / sys_role_permission /
  sys_permission + Sa-Token StpInterface；不做权限双写。
- **认证与授权分离**（CODE_STYLE §43）：`/api/admin/**` 必须经 Admin 授权，不以「已登录」放行；
  `@SaCheckPermission` 不得加在 Agent（API Key / MCP）与公开白名单通道上。
- **Event / 状态机不变**：本专项不触碰 Agent Event Stream、业务状态机、Scheduler /
  Workflow / Review Runtime。
- **外部 Agent 契约不变**：CLI_CLIENT 走 API Key / MCP，不进入 Sa-Token 会话。
- **已提交 Flyway DDL 不改**：V77~V86 只读，新变更一律新增迁移（V87+）。
- **编号纪律**：本专项任务一律 `BASE-xxx`，不占用 G-xxx / P0~P3 / A1~A7 / S1~S8；
  迭代记录按日期写入 `doc/log/`。
- **完成 ≠ PASS**：每批次结束按《HelloAI_AI开发协作规约》执行 git diff/status 回填。

# 11. 验收问题（全部批次完成后）

1. 无权限页面 URL 直达是否 404？（路由层面，非仅菜单隐藏）
2. 页面内按钮是否按权限码显隐？（v-auth）
3. 菜单树能否可视化增删改查？（不再是 SQL 维护）
4. 角色授权是否为差异更新？
5. SUPER_ADMIN / ADMIN 差异是否在 Docker 实测可复现？
6. 外部 Agent 与业务事件流是否零影响（回归）？
7. **（批次四）** Sa-Token 认证是否真正收口——`active-timeout` 滑动续期可实测？
8. **（批次四）** 身份是否单事实源——`sys_user.role` 已退场、建号即签发角色？
9. **（批次四）** 只读角色是否成立——GUEST 全写接口 403、只读接口 200？
10. **（批次四）** 授权覆盖度是否达标——适用接口 100% 动作码化且四角色差异可复现？

# 12. 勘误记录

> 记录本文档及关联文档中与代码 / 运行事实现状**不符**的描述。
> 冲突判定按《HelloAI_AI开发协作规约》事实源优先级：**代码与运行行为 > Flyway/库结构 >
> 可复现验证结果 > 差距表 > 基线文档 > 历史文档**。

## 12.1 Sa-Token 会话 Redis 键前缀（2026-09-13 修正）

- **原描述**：本文档 §2「S2 会话层」、`doc/log/2026-09.md`（S2 会话层）、
  `doc/HelloAI 实现差距表.md`（G-012 S2 会话层）均记为「Redis `satoken:` 前缀」。
- **事实**：Sa-Token 的会话键前缀取自 `SaTokenConfig.getTokenName()`，即配置项
  **`sa-token.token-name`**；`satoken` 只是该配置在库内的**默认值**。本项目
  `application.yml` 已覆盖为 `token-name: X-Admin-Token`，故实际键前缀是 **`X-Admin-Token:`**。
- **证据**：
  1. 字节码（sa-token-core 1.44.0，`cn.dev33.satoken.stp.StpLogic`）：
     - `splicingKeyTokenValue(t)` = `getTokenName() + ":" + loginType + ":token:" + t`
     - `splicingKeySession(id)` = `getTokenName() + ":" + loginType + ":session:" + id`
     - `SaTokenConfig` 构造器仅把 `tokenName` 初始化为 `"satoken"`，且无 `tokenPrefix` 默认赋值
       （即键前缀不来自 `token-prefix`）。
  2. 本机 Redis 实测键（BASE-4.1 E2E，2026-09-13）：
     `X-Admin-Token:login:token:<tokenValue>`、
     `X-Admin-Token:login:session:<loginId>`、
     `X-Admin-Token:login:last-active:<tokenValue>`
     （`login` 为 Sa-Token 默认 loginType；`last-active` 以 tokenValue 为后缀）。
- **影响面**：仅影响会话排查 / 调试的检索预期——按 `satoken:*` 模式扫描 Redis 会扫不到键，
  可能被误判为「会话未落 Redis」。会话存储介质（Redis，经 `sa-token-redis-jackson` 复用
  spring-data-redis）与 `active-timeout` 滑动续期行为**均不受影响**。
- **连带修正**：`helloai-core/pom.xml` 依赖注释、`AuthService` / `AuthServiceImpl` javadoc
  中的同类描述同步更正。
- **验证**：BASE-4.1 E2E 已用正确前缀完成滑动续期实证（`last-active` 时间戳静默不刷新、
  活动即刷新），见 §9.6 验收项 1。
