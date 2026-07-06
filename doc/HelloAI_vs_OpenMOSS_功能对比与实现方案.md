# HelloAI vs OpenMOSS — 功能对比与 Java 实现方案

**日期**: 2026-07-05  
**对比范围**: task-cli.py 命令 + 配套后端 API + 相关文档  
**源码路径**:
- HelloAI: `E:\yhzx\1027\helloai`
- OpenMOSS: `E:\workspace\openMoss\OpenMOSS-main` (后端) + `OpenMOSS-webui` (WebUI 静态资源)

---

## 目录

1. [项目技术架构对比](#一项目技术架构对比)
2. [CLI 命令逐项对比](#二cli-命令逐项对比)
3. [详细缺失功能分析](#三详细缺失功能分析)
4. [Java 实现方案](#四java-实现方案)

---

## 一、项目技术架构对比

| 维度 | OpenMOSS | HelloAI |
|------|----------|---------|
| 后端语言 | Python 3 / FastAPI | Java 17 / Spring Boot 3.2 |
| ORM | SQLAlchemy | MyBatis-Plus 3.5.6 |
| 数据库 | SQLite | PostgreSQL 16 |
| ID 类型 | `String` (UUID v4) | `Long` (雪花算法) |
| 认证方式 | `X-Admin-Token` / `Bearer <api_key>` | 相同（已对齐） |
| 统一响应 | FastAPI JSON | `R<T>` (code+msg+data+traceId) |
| CLI 工具 | `skills/task-cli.py` (847 行) | `helloai-core/.../scripts/task-cli.py` (280 行) |
| CLI 版本管理 | `config.yaml` 中 `cli_version` | `ToolsController` 中硬编码 `CLI_VERSION = "2"` |
| Web 前端 | Vue SPA (static 目录，源码不在仓库) | Vue 3 + Element Plus (`helloai-ui/`) |

---

## 二、CLI 命令逐项对比

### 2.1 注册

| | OpenMOSS | HelloAI |
|---|----------|---------|
| CLI 命令 | `register --name xx --role xx --token xx` | **不存在** |
| CLI 文件 | `task-cli.py:78-93` | — |
| 调用端点 | `POST /api/agents/register` + `X-Registration-Token` header | — |
| 请求体 | `{"name":"xx","role":"xx","description":"xx"}` | — |
| 响应 | `{"id":"...","name":"...","api_key":"...","role":"...","message":"..."}` | — |
| 后端实现 | `routers/agents.py:57-79` 调用 `agent_service.register_agent()` | 后端已有 `AgentController.registerWithToken()` 端点，**CLI 缺少此命令** |

**OpenMOSS 注册逻辑** (`services/agent_service.py:23-50`):

```python
def register_agent(db, name, role, description):
    # 1. 角色白名单校验: planner/executor/reviewer/patrol
    # 2. 名称重复检查 (DB 唯一约束兜底)
    # 3. 生成 UUID 作为 Agent ID
    # 4. 生成 API Key: "ak_" + secrets.token_hex(16)   # ak_ + 32 位 hex
    # 5. 创建 Agent 记录: id, name, role, description, status="active",
    #                     api_key, total_score=0, created_at=now
    # 6. db.commit() 后返回 Agent 对象
```

**HelloAI 已有后端** (`AgentService.java:35-42`, `AgentController.java:55-74`):
```java
// POST /api/agents/register-with-token + X-Registration-Token
public Agent register(String name, AgentRole role, String description) {
    // 1. 名称重复检查
    // 2. 创建 Agent: name, role, apiKey = "ak_" + 32 位 hex, status=ACTIVE, score=0
    // 3. save(agent)
}
```

> **结论**: 后端接口已存在，只需在 CLI 中增加 `register` 命令。

---

### 2.2 规则获取

| | OpenMOSS | HelloAI |
|---|----------|---------|
| CLI 命令 | `rules` | **不存在** |
| 调用端点 | `GET /api/rules?cli_version=` | — |
| 响应 | `{"content":"...", "update_available":bool, "latest_version":int}` | — |
| 核心功能 | **合并** 全局规则 + 任务规则 + 子任务规则 + **CLI 版本检查** | — |
| 后端实现 | `routers/rules.py:61-130` 调用 `rule_service.get_merged_rules()` | 后端 `RulesController.getMergedRules()` 已存在（但不包含 CLI 版本检查） |

**OpenMOSS 版本检查逻辑** (rules.py:77-130):
```
1. 获取请求参数 cli_version (可选)
2. 调用 rule_service.get_merged_rules() 合并规则文本
3. 若 cli_version == None → 旧版 CLI，在 content 末尾追加更新指引
4. 若 cli_version < latest → 返回 update_available=true + 更新指令
5. 若 cli_version >= latest → 正常返回
```

**HelloAI 已有后端** (`RulesController.java:46-52`):
```java
@GetMapping("/merged")
public R<Map<String, Object>> getMergedRules(@RequestParam Long taskId, @RequestParam Long subTaskId) {
    String content = ruleService.getMergedRules(taskId, subTaskId);
    return R.ok(Map.of("content", content));
}
```

> **差异**: HelloAI 的 `/merged` 端点缺少 CLI 版本检查逻辑（`update_available`/`latest_version` 字段）。CLI 版本检查能力存在于 `ToolsController` 的独立端点 `/cli/check-update` 和 `/cli/version` 中。

---

### 2.3 任务管理

| CLI 命令 | OpenMOSS | HelloAI |
|----------|:--------:|:-------:|
| `task create <name> --desc xx --type once` | ✅ | ❌ |
| `task list [--status xx] [--page N]` | ✅ | ❌ |
| `task get <id>` | ✅ | ❌ |
| `task edit <id> --name xx --desc xx` | ✅ | ❌ |
| `task status <id> <新状态>` | ✅ | ❌ |
| `task cancel <id>` | ✅ | ❌ |
| `module create <task_id> <name>` | ✅ | ❌ |
| `module list <task_id>` | ✅ | ❌ |

**OpenMOSS 后端实现** (`routers/tasks.py`):

| 端点 | 方法 | 权限 | 业务逻辑 |
|------|------|------|----------|
| `POST /api/tasks` | 创建 | planner/admin | name 必填 + type=once/recurring, status=planning |
| `GET /api/tasks` | 列表 | 所有 Agent | 可选 status 过滤, 分页 (page_size=0 → 全量) |
| `GET /api/tasks/{id}` | 详情 | 所有 Agent | 按 UUID 查询, 不存在→404 |
| `PUT /api/tasks/{id}` | 编辑 | planner/admin | 仅 planning/active 状态可编辑 |
| `PUT /api/tasks/{id}/status` | 状态变更 | planner/admin | 状态枚举: planning/active/in_progress/completed/archived/cancelled |
| `POST /api/tasks/{id}/cancel` | 取消 | planner/admin | 已完成/已取消/已归档不能取消 |
| `POST /api/tasks/{id}/modules` | 创建模块 | planner/admin | module 绑定到 task_id |
| `GET /api/tasks/{id}/modules` | 模块列表 | 所有 Agent | 按 task_id 查询 |

**HelloAI 后端现状**:
- `TaskController.java` 存在, 需逐项比对端点

---

### 2.4 子任务管理

| CLI 命令 | OpenMOSS | HelloAI |
|----------|:--------:|:-------:|
| `st create <task_id> <name> ...` | ✅ | ❌ |
| `st list [--task-id xx] [--status xx]` | ✅ | ❌ |
| `st get <id>` | ✅ | ❌ |
| `st status <id>` | — (CLI 无此命令) | ✅ (HelloAI 独有) |
| `st mine` | ✅ | ✅ (`poll` 命令) |
| `st available` | ✅ (查看 pending 状态) | ❌ |
| `st latest <task_id>` | ✅ | ❌ |
| `st claim <id>` | ✅ | ❌ |
| `st start <id> [--session xxx]` | ✅ | ❌ |
| `st submit <id>` | ✅ | ✅ |
| `st edit <id> --name xx --desc xx` | ✅ | ❌ |
| `st cancel <id>` | ✅ | ❌ |
| `st block <id>` | ✅ | ❌ |
| `st session <id> <session_id>` | ✅ | ❌ |
| `st reassign <id> <agent_id>` | ✅ | ❌ |

**OpenMOSS 后端实现** (`routers/sub_tasks.py`):

| 端点 | 方法 | 权限 | 业务逻辑 |
|------|------|------|----------|
| `POST /api/sub-tasks` | 创建 | planner | task_id/name/deliverable/acceptance/priority/assigned_agent/module_id |
| `GET /api/sub-tasks` | 列表 | 所有 Agent | 可选 task_id/module_id/status 过滤, 分页 |
| `GET /api/sub-tasks/{id}` | 详情 | 所有 Agent | — |
| `GET /api/sub-tasks/mine` | 我的 | 所有 Agent | `WHERE assigned_agent == current_agent.id`, 可选 status 过滤 |
| `GET /api/sub-tasks/available` | 可认领 | 所有 Agent | `WHERE status == 'pending'` |
| `GET /api/sub-tasks/latest` | 最新 | 所有 Agent | `WHERE task_id==X AND assigned_agent==me ORDER BY updated_at DESC LIMIT 1` |
| `POST /api/sub-tasks/{id}/claim` | 认领 | executor | `pending → assigned`, 写 assigned_agent=me |
| `POST /api/sub-tasks/{id}/start` | 开始 | executor | `assigned/rework → in_progress`, 可选 session_id |
| `POST /api/sub-tasks/{id}/submit` | 提交 | executor | `in_progress → review` |
| `POST /api/sub-tasks/{id}/complete` | 审查通过 | reviewer | `review → done`, 写入 completed_at |
| `POST /api/sub-tasks/{id}/rework` | 驳回 | reviewer | `review → rework`, rework_count++, 可选换人 |
| `POST /api/sub-tasks/{id}/block` | 标记异常 | patrol | `→ blocked` |
| `POST /api/sub-tasks/{id}/reassign` | 重新分配 | planner | `blocked → assigned`, 更换 assigned_agent |
| `POST /api/sub-tasks/{id}/cancel` | 取消 | planner | 已完成/已取消不可取消 |
| `POST /api/sub-tasks/{id}/session` | 更新会话 | executor | 更新 current_session_id (cron 唤醒绑定新会话) |

> **关键差异**: OpenMOSS 的 `claim` 和 `start` 是两个独立步骤 (pending→assigned→in_progress)。HelloAI 的 `SubTaskService.claim()` 直接 `pending→ASSIGNED`, `start()` 直接到 `IN_PROGRESS`。两者状态机设计不同但逻辑等价。

---

### 2.5 积分系统

| CLI 命令 | OpenMOSS | HelloAI |
|----------|:--------:|:-------:|
| `score me` | ✅ (查看自己的积分概要) | ❌ |
| `score logs [--page N]` | ✅ (我的积分明细) | ❌ |
| `score agent-logs <agent_id>` | ✅ (指定 Agent 明细) | ❌ |
| `score leaderboard` | ✅ (排行榜) | ❌ |
| `score adjust <agent_id> <delta> <reason>` | ✅ (手动调整) | ❌ |

**OpenMOSS 后端** (`routers/scores.py`):

| 端点 | 业务逻辑 |
|------|----------|
| `GET /api/scores/me` | 返回: agent_id, agent_name, total_score, rank, total_agents, reward_count, penalty_count, total_records |
| `GET /api/scores/me/logs` | 分页, 按 created_at DESC |
| `GET /api/scores/{agent_id}/logs` | 同上, 查指定 Agent |
| `GET /api/scores/leaderboard` | 所有 Agent 按 total_score DESC, 前端计算 rank |
| `POST /api/scores/adjust` | reviewer/planner 角色可调, `reason` 自动加 `[手动调整]` 前缀 |

**HelloAI 已有后端** (`ScoreController.java`):

| 端点 | 状态 |
|------|:----:|
| `GET /api/scores/me?agentId=` | ✅ 已有 |
| `GET /api/scores/leaderboard` | ✅ 已有 |
| `POST /api/scores/adjust` | ✅ 已有 |
| `GET /api/scores/me/logs` | ❌ **缺失** (Agent 积分明细) |
| `GET /api/scores/{agent_id}/logs` | ❌ **缺失** (管理员查看积分明细) |

---

### 2.6 活动日志

| CLI 命令 | OpenMOSS | HelloAI |
|----------|:--------:|:-------:|
| `log create <action> <summary>` | ✅ | ❌ |
| `log mine [--action xx] [--days N]` | ✅ | ❌ |
| `log list [--sub-task-id xx]` | ✅ | ❌ |

**OpenMOSS action 白名单**: `coding, delivery, blocked, reflection, plan, review, patrol`  
**默认查询天数**: 7 天 (最大 60 天)  
**默认条数**: 20 (最大 500)

**HelloAI 后端现状**: `ActivityController.java` 存在 — 需比对端点

---

### 2.7 Agent 列表与自更新

| CLI 命令 | OpenMOSS | HelloAI |
|----------|:--------:|:-------:|
| `agents [--role xx]` | ✅ | ❌ |
| `update` | ✅ (CLI + SKILL.md) | ✅ (CLI + SKILL.md) |
| `version` | — | ✅ (HelloAI 独有) |

---

### 2.8 通知配置

| CLI 命令 | OpenMOSS | HelloAI |
|----------|:--------:|:-------:|
| `notification` | ✅ (查看通知配置) | ❌ |

**OpenMOSS 实现**: `GET /api/config/notification` — 返回 `enabled`/`channels`/`events`

---

## 三、详细缺失功能分析

### 3.1 总结: 缺失的 CLI 命令 (共计 18 个)

```
命令组        缺失命令
─────────────────────────────────────────
register      register          (Agent 自注册 — 最核心)
task          create, list, get, edit, status, cancel  (6个)
module        create, list      (2个)
st (子任务)   create, list, get, available, latest, claim, start, edit, cancel, block, session, reassign  (12个)
review        create, list, get (3个)
score         me, logs, agent-logs, leaderboard, adjust  (5个)
log           create, mine, list (3个)
agents        list              (1个)
notification  notification      (1个)
```

### 3.2 缺失的后端 API 端点

**经过代码对照后的精确清单**：

#### 3.2.1 子任务相关

| 端点 | 状态 | 说明 |
|------|:----:|------|
| `POST /api/sub-tasks` | ✅ 已有 | 创建 |
| `GET /api/sub-tasks` | ✅ 已有 | 列表 (taskId/status/assignedAgent) |
| `GET /api/sub-tasks/{id}` | ✅ 已有 | 详情 |
| `POST /api/sub-tasks/{id}/claim` | ✅ 已有 | 认领 |
| `POST /api/sub-tasks/{id}/start` | ✅ 已有 | 开始执行 |
| `POST /api/sub-tasks/{id}/submit` | ✅ 已有 | 提交审查 |
| `POST /api/sub-tasks/{id}/complete` | ✅ 已有 | 审查通过 |
| `POST /api/sub-tasks/{id}/rework` | ✅ 已有 | 驳回返工 |
| `POST /api/sub-tasks/{id}/block` | ✅ 已有 | 标记异常 |
| `POST /api/sub-tasks/{id}/reassign` | ✅ 已有 | 重新分配 |
| `POST /api/sub-tasks/{id}/pause` | ✅ 已有 | 暂停 (HelloAI 独有) |
| `POST /api/sub-tasks/{id}/resume` | ✅ 已有 | 恢复 (HelloAI 独有) |
| `GET /api/sub-tasks/available` | ✅ 已有 | 待认领 (Line 146) |
| `GET /api/sub-tasks/mine` | ✅ 已有 | 我的子任务 (Line 155) |
| `GET /api/sub-tasks/latest` | ❌ 缺失 | 我的最新子任务 |
| `POST /api/sub-tasks/{id}/session` | ❌ 缺失 | 更新会话 ID |

#### 3.2.2 积分相关

| 端点 | 状态 | 说明 |
|------|:----:|------|
| `GET /api/scores/me` | ✅ 已有 | Agent 积分概要 |
| `GET /api/scores/leaderboard` | ✅ 已有 | 排行榜 |
| `POST /api/scores/adjust` | ✅ 已有 | 手动调整 |
| `GET /api/scores/me/logs` | ❌ 缺失 | Agent 积分明细 (分页) |
| `GET /api/scores/{agentId}/logs` | ❌ 缺失 | 管理员查看积分明细 |

#### 3.2.3 规则相关

| 端点 | 状态 | 说明 |
|------|:----:|------|
| `GET /api/rules` | ✅ 已有 | 规则列表 |
| `GET /api/rules/{id}` | ✅ 已有 | 规则详情 |
| `GET /api/rules/merged` | ⚠️ 部分 | 缺少 CLI 版本检查 (`cliVersion` 参数) |

#### 3.2.4 活动日志

| 端点 | 状态 | 说明 |
|------|:----:|------|
| `POST /api/logs` (Agent 写入) | ❌ 缺失 | Agent 通过 CLI 写入活动日志 |
| `GET /api/logs/mine` | ❌ 缺失 | Agent 查看自己的日志 |

**实际缺失的端点总数: 6 个** (非最初估计的 10+)

### 3.3 缺失的 CLI 命令 (后端已有, 仅 CLI 缺失) — 共 13 个

这些命令对应的后端 API **已全部存在**, 只需在 CLI 中增加调用:

```
register     → POST /api/agents/register-with-token
task create  → POST /api/tasks
task list    → GET /api/tasks
st create    → POST /api/sub-tasks
st list      → GET /api/sub-tasks
st available → GET /api/sub-tasks/available    (已存在)
st claim     → POST /api/sub-tasks/{id}/claim   (已存在)
st start     → POST /api/sub-tasks/{id}/start   (已存在)
st submit    → POST /api/sub-tasks/{id}/submit  (已存在)
score me     → GET /api/scores/me?agentId=      (已存在)
score leaderboard → GET /api/scores/leaderboard  (已存在)
score adjust → POST /api/scores/adjust           (已存在)
agents       → GET /api/agents                   (已存在)
```

### 3.4 后端 + CLI 均缺失 — 共 8 个

```
CLI rules      + 后端 /rules/merged 缺少 cliVersion 参数
CLI st latest  + GET /api/sub-tasks/latest          (后端需新增)
CLI st session + POST /api/sub-tasks/{id}/session   (后端需新增)
CLI score logs + GET /api/scores/me/logs            (后端需新增)
CLI log create + POST /api/logs                     (后端需新增)
CLI log mine   + GET /api/logs/mine                 (后端需新增)
CLI notification + GET /api/config/notification     (后端需新增)
CLI st edit    + PUT /api/sub-tasks/{id}            (后端需新增)
```

### 3.3 缺失的文档

| 文件 | OpenMOSS 路径 | 说明 |
|------|---------------|------|
| `agent-onboarding.md` | `prompts/tool/agent-onboarding.md` | Agent 首次注册指引 (75 行) |
| `executor-backend.md` | `prompts/agents/executor-backend.md` | 后端 Agent 专业化 Prompt |
| `executor-frontend.md` | `prompts/agents/executor-frontend.md` | 前端 Agent 专业化 Prompt |
| `executor-devops.md` | `prompts/agents/executor-devops.md` | 运维 Agent 专业化 Prompt |
| `executor-researcher.md` | `prompts/agents/executor-researcher.md` | 调研 Agent 专业化 Prompt |
| `executor-tester.md` | `prompts/agents/executor-tester.md` | 测试 Agent 专业化 Prompt |

HelloAI 的 Prompt 管理已通过 `PromptTemplate` 表 + `PromptList.vue` 管理端实现，这些专业化 Prompt 可通过管理端直接配置，**不一定需要作为静态文件存放**。

---

## 四、Java 实现方案

### 4.1 原则

- **复用已有代码**: HelloAI 后端大部分能力已存在 (Agent 注册、子任务 CRUD、积分、活动日志)
- **CLI 优先**: CLI 是薄层，只需调用已有 API
- **Python CLI 保持不变**: 继续用 Python 实现 CLI (跨平台、Agent 开发者友好)，不做 Java CLI
- **按 openMOSS 对齐**: 命令名称、参数格式、输出格式对齐 openMOSS

### 4.2 实现步骤

#### 阶段 A: CLI `register` 命令 (P0 核心)

**修改文件**: `helloai-core/src/main/resources/scripts/task-cli.py`

新增内容:
```python
# 新增 _reg_headers 函数
def _reg_headers(token: str) -> dict:
    return {"X-Registration-Token": token, "Content-Type": "application/json"}

# 新增 cmd_register 函数
def cmd_register(args):
    """注册 Agent — 使用注册令牌自注册，返回 API Key"""
    # 发送 POST /api/agents/register-with-token
    # Header: X-Registration-Token: <token>
    # Body: {"name": args[0], "role": args[1], "description": args[2]}
    # 解析响应并打印 Agent ID 和 API Key
    # 提示: "请立即将 API Key 保存到 SKILL.md 中！"

# 注册命令不需要 --key 参数 (用 --token 代替)
# 需修改 main() 的参数解析逻辑
```

**CLI 用法** (对齐 openMOSS):
```bash
python task-cli.py register --name "AI小吴" --role executor --token helloai-reg-2024 --description "专业资讯搜集员"
```

**后端**: 无需修改, `AgentController.registerWithToken()` 已实现完整逻辑。

---

#### 阶段 B: CLI `rules` 命令 (P1)

**修改文件**: `task-cli.py` + `RulesController.java`

**CLI**:
```python
def cmd_rules(api_key, args):
    """获取合并规则 (含 CLI 版本检查)"""
    data = _get(api_key, "/rules/merged")
    # 打印 content
    # 若 update_available → 提示更新
```

**后端改造** (`RulesController.java`):

```java
// 改造 GET /api/rules/merged — 增加 cliVersion 参数和更新检查
@GetMapping("/merged")
public R<Map<String, Object>> getMergedRules(
        @RequestParam(value = "taskId", required = false) Long taskId,
        @RequestParam(value = "subTaskId", required = false) Long subTaskId,
        @RequestParam(value = "cliVersion", required = false) Integer cliVersion) {
    
    String content = ruleService.getMergedRules(taskId, subTaskId);
    int latest = Integer.parseInt(CLI_VERSION);  // 从配置读取
    
    Map<String, Object> result = new HashMap<>();
    result.put("content", content);
    
    if (cliVersion == null || cliVersion < latest) {
        result.put("updateAvailable", true);
        result.put("latestVersion", latest);
        result.put("updateInstructions", "python task-cli.py --key <KEY> update");
    }
    return R.ok(result);
}
```

---

#### 阶段 C: 子任务扩展端点 (P1)

**新增端点** (`SubTaskController.java`):

```java
// GET /api/sub-tasks/available — 待认领子任务 (status=PENDING)
@GetMapping("/available")
public R<List<SubTask>> available() {
    List<SubTask> list = subTaskService.lambdaQuery()
            .eq(SubTask::getStatus, SubTaskStatus.PENDING)
            .orderByDesc(SubTask::getCreateTime)
            .list();
    return R.ok(list);
}

// GET /api/sub-tasks/latest — 我的最新子任务
@GetMapping("/latest")
public R<SubTask> latest(@RequestParam Long taskId,
                          @RequestParam Long agentId) {
    SubTask st = subTaskService.lambdaQuery()
            .eq(SubTask::getTaskId, taskId)
            .eq(SubTask::getAssignedAgent, agentId)
            .orderByDesc(SubTask::getUpdateTime)
            .last("LIMIT 1").one();
    if (st == null) return R.fail("没有分配给你的子任务");
    return R.ok(st);
}

// POST /api/sub-tasks/{id}/session — 更新会话 ID
@PostMapping("/{id}/session")
public R<Void> updateSession(@PathVariable Long id,
                              @RequestBody Map<String, String> body) {
    // UPDATE sub_task SET current_session_id = ? WHERE id = ?
    subTaskService.updateSession(id, body.get("sessionId"));
    return R.ok();
}

// POST /api/sub-tasks/{id}/reassign — 重新分配
@PostMapping("/{id}/reassign")
public R<Void> reassign(@PathVariable Long id,
                         @RequestBody Map<String, Object> body) {
    Long newAgentId = Long.valueOf(body.get("agentId").toString());
    subTaskService.reassign(id, newAgentId);
    return R.ok();
}
```

**CLI** (task-cli.py 增加命令):
```python
# st available — 查看可认领子任务
# st latest — 获取最新子任务  
# st session — 更新会话 ID
# st reassign — 重新分配
```

---

#### 阶段 D: 积分明细端点 (P2)

**新增端点** (`ScoreController.java`):

```java
// GET /api/scores/me/logs — Agent 查看自己的积分明细
@GetMapping("/me/logs")
public R<PageResult<ScoreLogItem>> myLogs(
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
        HttpServletRequest request) {
    Long agentId = (Long) request.getAttribute(AuthInterceptor.AUTH_ID_KEY);
    // 复用 AgentService.getScoreLogs(agentId, page, pageSize)
    return R.ok(agentService.getScoreLogs(agentId, page, pageSize));
}

// GET /api/scores/{agentId}/logs — 管理员查看
@GetMapping("/{agentId}/logs")
public R<PageResult<ScoreLogItem>> agentLogs(
        @PathVariable Long agentId,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
    return R.ok(agentService.getScoreLogs(agentId, page, pageSize));
}
```

---

#### 阶段 E: 活动日志 Agent 写入端点 (P2)

**新增端点** (`ActivityController.java`):

```java
// POST /api/logs — Agent 写入活动日志 (openMOSS 风格)
@PostMapping
public R<ActivityLog> create(@RequestBody Map<String, String> body,
                              HttpServletRequest request) {
    Long agentId = (Long) request.getAttribute(AuthInterceptor.AUTH_ID_KEY);
    // 校验 action 白名单
    // 写入 activity_log 表
    ActivityLog log = new ActivityLog();
    log.setAgentId(agentId);
    log.setAction(body.get("action"));
    log.setLevel(body.getOrDefault("summary", "INFO"));
    // ... save
    return R.ok(log);
}

// GET /api/logs/mine — Agent 查看自己的日志
@GetMapping("/mine")
public R<List<ActivityLog>> mine(
        @RequestParam(value = "action", required = false) String action,
        @RequestParam(value = "days", defaultValue = "7") int days,
        @RequestParam(value = "limit", defaultValue = "20") int limit,
        HttpServletRequest request) {
    Long agentId = (Long) request.getAttribute(AuthInterceptor.AUTH_ID_KEY);
    // ... 按 agentId + 可选 action + 天数过滤
    return R.ok(list);
}
```

---

#### 阶段 F: CLI 命令批量补齐 (P2)

**task-cli.py 新增命令 (按 openMOSS 对齐)**:

```
register     → POST /api/agents/register-with-token (无需 --key)
rules        → GET /api/rules/merged
task create  → POST /api/tasks
task list    → GET /api/tasks
task get     → GET /api/tasks/{id}
st create    → POST /api/sub-tasks
st list      → GET /api/sub-tasks
st available → GET /api/sub-tasks/available
st latest    → GET /api/sub-tasks/latest
st claim     → POST /api/sub-tasks/{id}/claim
st start     → POST /api/sub-tasks/{id}/start
score me     → GET /api/scores/me
score logs   → GET /api/scores/me/logs
score leaderboard → GET /api/scores/leaderboard
score adjust → POST /api/scores/adjust
log create   → POST /api/logs
log mine     → GET /api/logs/mine
agents       → GET /api/agents
```

---

### 4.3 修正后的实现总览

| 阶段 | 内容 | 涉及文件 | 工作量 |
|:----:|------|----------|:------:|
| A | CLI `register` 命令 | `task-cli.py` | 30 行 Python |
| B | `rules` + CLI 版检 | `task-cli.py` + `RulesController.java` | 40 行 Python + 10 行 Java |
| C | 子任务扩展 (2 端点: latest, session) | `SubTaskController.java` + `task-cli.py` | 15 行 Java + 30 行 Python |
| D | 积分明细端点 (2 端点) | `ScoreController.java` | 20 行 Java |
| E | 活动日志端点 (2 端点) | `ActivityController.java` | 25 行 Java |
| F | CLI 批量补齐 (13 命令) | `task-cli.py` | 250 行 Python |

**总计**: 约 70 行 Java + 320 行 Python (大幅减少)

**后端新增端点**: 6 个 (latest, session, me/logs, {agentId}/logs, POST /logs, GET /logs/mine)

---

### 4.4 不需要做的事

| 项目 | 原因 |
|------|------|
| 后端任务 CRUD 全部重写 | `TaskController.java` 已存在 |
| 后端子任务 CRUD 全部重写 | `SubTaskController.java` 已存在 (含 claim/start/submit/rework/block/change-status) |
| 后端积分排行榜/调整 | `ScoreController.java` 已存在 |
| 后端规则 CRUD | `RulesController.java` 已存在 |
| 后端 Tools/CLI 版本管理 | `ToolsController.java` 已存在 |
| 5 个 executor 专业化 Prompt 文件 | 已通过 `PromptTemplate` 表 + Web 管理端实现 |
| 用 Java 重写 CLI | Python CLI 更适合 Agent 场景 (跨平台, 轻量) |
