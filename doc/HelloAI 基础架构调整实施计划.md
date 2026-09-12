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
  X-Admin-Token 头，active-timeout=28800s 滑动续期，Redis `satoken:` 前缀）；
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

# 9. 红线与约束

- **不新增第二套权限体系**：唯一事实源仍是 sys_user_role / sys_role_permission /
  sys_permission + Sa-Token StpInterface；不做权限双写。
- **Event / 状态机不变**：本专项不触碰 Agent Event Stream、业务状态机、Scheduler /
  Workflow / Review Runtime。
- **外部 Agent 契约不变**：CLI_CLIENT 走 API Key / MCP，不进入 Sa-Token 会话。
- **已提交 Flyway DDL 不改**：V77/V78 只读，新变更一律新增迁移（V79+）。
- **编号纪律**：本专项任务一律 `BASE-xxx`，不占用 G-xxx / P0~P3 / A1~A7 / S1~S8；
  迭代记录按日期写入 `doc/log/`。
- **完成 ≠ PASS**：每批次结束按《HelloAI_AI开发协作规约》执行 git diff/status 回填。

# 10. 验收问题（全部批次完成后）

1. 无权限页面 URL 直达是否 404？（路由层面，非仅菜单隐藏）
2. 页面内按钮是否按权限码显隐？（v-auth）
3. 菜单树能否可视化增删改查？（不再是 SQL 维护）
4. 角色授权是否为差异更新？
5. SUPER_ADMIN / ADMIN 差异是否在 Docker 实测可复现？
6. 外部 Agent 与业务事件流是否零影响（回归）？
