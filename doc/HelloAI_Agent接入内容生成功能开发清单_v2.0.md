# HelloAI Agent 接入内容生成功能开发清单

**版本**: v2.0  
**日期**: 2026-07-05  
**变更**: 合并 GPT-5 方案（工程完整度）与 MiniMax 方案（UX 闭环思路），形成最终可执行版本

---

## 目录

1. [功能定义](#1-功能定义)
2. [交互方案](#2-交互方案)
3. [后端开发清单](#3-后端开发清单)
4. [前端开发清单](#4-前端开发清单)
5. [接口清单](#5-接口清单)
6. [执行步骤](#6-执行步骤)
7. [验收标准](#7-验收标准)
8. [后续可选增强](#8-后续可选增强)
9. [附录：方案决策记录](#9-附录方案决策记录)

---

## 1. 功能定义

### 1.1 本期要实现的能力

1. **注册即展示**：注册 Agent 成功后，弹窗直接切换到 onboarding 内容展示（不再靠一闪而过的 `ElMessage`）。
2. **列表页入口**：Agent 卡片操作区增加 `生成接入内容` 按钮。
3. **详情页入口**：Agent 详情页操作区增加 `生成接入内容` 按钮。
4. **动态内容生成**：后端按 `agentId` 动态拼装接入文本，注入 `agentName`、`role`、`apiKey`、`baseUrl`、角色 SKILL。
5. **一键复制**：前端弹窗支持复制完整接入内容、仅复制 SKILL。
6. **清理死菜单**：移除侧边栏"Prompt 模板"菜单项和路由，不再让运营人员从那里找接入方式。
7. **修复遗漏 bug**：注册时将 `specializationSlug` 传递给后端。

### 1.2 本期不做的内容

1. 不实现自动操控 Trae / Qoder UI。
2. 不实现浏览器自动粘贴或 IDE 插件注入。
3. 不重构整个 Prompt 管理模块（仅移除菜单入口，后端/数据库不动）。
4. 不在本期补齐 `task-cli.py` 与 `SKILL.md` 的全部命令差异。
5. 不删除后端 PromptTemplate 相关 API 和数据库表。

---

## 2. 交互方案

### 2.1 核心流程：注册向导（MiniMax 贡献）

这是本方案相比 GPT-5 原版最大的增强点——把"注册 → Key 一闪而过 → 用户懵逼"改造为"注册 → 自动展示 onboarding → 一键复制"。

```
┌──────────────────────────────────────────┐
│ 第 1 步：注册 Agent                       │
│                                           │
│ 名称: [____________]                      │
│ 角色: [EXECUTOR ▼]                        │
│ 专业化: [AI酱瓜-后端 ▼]   ← 修复：传递给后端 │
│ 描述: [____________]                      │
│                                           │
│              [取消]  [注册并生成引导]       │
└──────────────────────────────────────────┘
              ↓ 注册 API 返回成功 ↓
┌──────────────────────────────────────────┐
│ 🚀 Agent 已激活！复制到 Trae / Qoder 即可  │
│                                           │
│ Agent：AI小吴  |  角色：EXECUTOR           │
│ API Key：ak_xxxx...  |  服务地址：localhost │
│ ────────────────────────────────────────  │
│ 你是 HelloAI 平台中的一个已注册 Agent...   │
│                                           │
│ 【Agent 信息】                             │
│ - 名称：AI小吴                             │
│ - 角色：EXECUTOR                           │
│ - API Key：ak_xxxxxxxx                     │
│ - 服务地址：http://localhost:6565           │
│                                           │
│ 【SKILL】                                  │
│ # Task Executor Skill                      │
│ ...（完整 SKILL 内容，变量已替换）...        │
│ ────────────────────────────────────────  │
│  [📋 复制全部]  [📋 仅复制 SKILL]  [关闭]   │
└──────────────────────────────────────────┘
```

### 2.2 列表页入口

**文件**: `helloai-ui/src/views/agent/AgentList.vue`

已有 AgentCard 组件的操作区（`AgentCard.vue`）新增：

- 按钮文案：`生成接入内容`
- 点击后打开 `AgentOnboardingDialog`，传入当前 Agent ID

### 2.3 详情页入口

**文件**: `helloai-ui/src/views/agent/AgentDetail.vue`

在操作区（现有编辑/切换状态/重置Key/删除按钮旁）新增：

- 按钮文案：`生成接入内容`
- 点击后打开 `AgentOnboardingDialog`

### 2.4 弹窗组件

**新组件**: `helloai-ui/src/views/agent/components/AgentOnboardingDialog.vue`

弹窗内容分为 3 个区域：

1. **Agent 摘要区**
   - `agentName` / `role` / `apiKey` 前12位 + `...` / `baseUrl`
2. **可复制接入内容区**
   - 使用 `<pre>` 或 `<el-input type="textarea" readonly>` 展示完整 `content`
3. **操作区**
   - `📋 复制全部` — 复制 `data.content`
   - `📋 仅复制 SKILL` — 复制 `data.skillContent`
   - `关闭`

弹窗属性：

| 属性 | 值 |
|------|-----|
| `append-to-body` | `true` |
| 宽度 | `880px` |
| `close-on-click-modal` | `false` |

**Props / Emits**：

```ts
// Props
modelValue: boolean
agentId: string | number | null

// Emits
'update:modelValue'
```

**内部逻辑**：

```ts
watch(() => props.modelValue, async (val) => {
  if (val && props.agentId) {
    loading.value = true
    data.value = await agentApi.getOnboardingContent(String(props.agentId))
    loading.value = false
  }
})
```

---

## 3. 后端开发清单

### 3.1 新增 DTO

**新增文件**: `helloai-api/src/main/java/com/helloai/api/dto/agent/AgentOnboardingResponse.java`

```java
package com.helloai.api.dto.agent;

import lombok.Data;

@Data
public class AgentOnboardingResponse {
    private Long agentId;
    private String agentName;
    private String role;
    private String apiKey;
    private String baseUrl;
    private String title;
    private String content;
    private String skillContent;
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `agentId` | `Long` | 当前 Agent ID |
| `agentName` | `String` | 当前 Agent 名称 |
| `role` | `String` | 当前 Agent 角色（枚举名） |
| `apiKey` | `String` | 完整的 API Key（明文） |
| `baseUrl` | `String` | 当前服务访问地址 |
| `title` | `String` | 弹窗标题文案 |
| `content` | `String` | 可复制的完整接入文本 |
| `skillContent` | `String` | 纯角色 SKILL 内容（变量已替换） |

### 3.2 扩展 PromptTemplateService

**修改文件**: `helloai-core/src/main/java/com/helloai/core/service/PromptTemplateService.java`

保留现有方法：

```java
public String getSkillForAgent(String role, String apiKey, String baseUrl, String agentName)
```

新增方法：

```java
/**
 * 拼装完整接入内容（Agent 信息摘要 + 执行要求 + SKILL）
 *
 * @param role      Agent 角色
 * @param apiKey    Agent API Key
 * @param baseUrl   服务地址
 * @param agentName Agent 名称
 * @return 完整接入文本
 */
public String buildOnboardingContent(String role, String apiKey, String baseUrl, String agentName) {
    String skillContent = getSkillForAgent(role, apiKey, baseUrl, agentName);

    return """
        你是 HelloAI 平台中的一个已注册 Agent，请按以下信息接入并开始工作。

        【Agent 信息】
        - 名称：%s
        - 角色：%s
        - API Key：%s
        - 服务地址：%s

        【执行要求】
        1. 你已在 HelloAI 平台完成注册，无需再次注册。
        2. 你需要按照以下 Skill 内容工作。
        3. 优先使用 task-cli.py 和平台 API 完成任务。
        4. 首次进入后先读取规则，再查看收件箱和任务。

        【SKILL】
        %s
        """.formatted(agentName, role, apiKey, baseUrl, skillContent);
}
```

### 3.3 baseUrl 获取策略

**涉及文件**: `helloai-common/src/main/java/com/helloai/common/config/AgentConfigProperties.java`

优先级：

1. 配置文件 `helloai.agent.baseUrl`（如有配置）
2. 根据 `HttpServletRequest` 拼接：`request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort()`

### 3.4 新增管理接口

**修改文件**: `helloai-api/src/main/java/com/helloai/api/controller/AdminAgentController.java`

```java
@GetMapping("/{id}/onboarding-content")
public R<AgentOnboardingResponse> onboardingContent(@PathVariable("id") Long id,
                                                    HttpServletRequest request) {
    Agent agent = agentService.getById(id);
    if (agent == null) {
        return R.fail("Agent 不存在");
    }

    // 1. 解析 baseUrl
    String baseUrl = agentConfigProperties.getBaseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
        baseUrl = request.getScheme() + "://"
                + request.getServerName() + ":"
                + request.getServerPort();
    }

    // 2. 获取纯 SKILL 内容
    String skillContent = promptTemplateService.getSkillForAgent(
            agent.getRole().name(), agent.getApiKey(), baseUrl, agent.getName());

    // 3. 拼装完整接入内容
    String content = promptTemplateService.buildOnboardingContent(
            agent.getRole().name(), agent.getApiKey(), baseUrl, agent.getName());

    // 4. 组装响应
    AgentOnboardingResponse resp = new AgentOnboardingResponse();
    resp.setAgentId(agent.getId());
    resp.setAgentName(agent.getName());
    resp.setRole(agent.getRole().name());
    resp.setApiKey(agent.getApiKey());
    resp.setBaseUrl(baseUrl);
    resp.setTitle("复制到 Trae / Qoder 的接入内容");
    resp.setContent(content);
    resp.setSkillContent(skillContent);

    return R.ok(resp);
}
```

**路径**: `GET /api/admin/agents/{id}/onboarding-content`

**入参**: `id` (Path, Long)

**返回**: `R<AgentOnboardingResponse>`

### 3.5 后端改动文件清单

#### 新增

```
helloai-api/src/main/java/com/helloai/api/dto/agent/AgentOnboardingResponse.java
```

#### 修改

```
helloai-core/src/main/java/com/helloai/core/service/PromptTemplateService.java   (+buildOnboardingContent 方法)
helloai-api/src/main/java/com/helloai/api/controller/AdminAgentController.java   (+onboardingContent 端点)
```

---

## 4. 前端开发清单

### 4.1 扩展 API 层

**修改文件**: `helloai-ui/src/api/agent.ts`

```ts
/** 获取 Agent 接入内容 */
getOnboardingContent(id: string) {
  return request.get<any, AgentOnboardingResponse>(`/admin/agents/${id}/onboarding-content`)
}
```

### 4.2 扩展类型定义

**修改文件**: `helloai-ui/src/types/index.ts`

```ts
export interface AgentOnboardingResponse {
  agentId: string | number
  agentName: string
  role: string
  apiKey: string
  baseUrl: string
  title: string
  content: string
  skillContent: string
}
```

### 4.3 新增弹窗组件

**新增文件**: `helloai-ui/src/views/agent/components/AgentOnboardingDialog.vue`

核心结构：

```vue
<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
    :title="data?.title || '生成接入内容'"
    width="880px"
    append-to-body
    :close-on-click-modal="false"
  >
    <!-- Agent 摘要区 -->
    <div class="onboarding-summary" v-if="data">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="Agent">{{ data.agentName }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ data.role }}</el-descriptions-item>
        <el-descriptions-item label="API Key">{{ data.apiKey.substring(0,12) + '...' }}</el-descriptions-item>
        <el-descriptions-item label="服务地址">{{ data.baseUrl }}</el-descriptions-item>
      </el-descriptions>
    </div>

    <!-- 内容区 -->
    <div class="onboarding-content" v-if="data" v-loading="loading">
      <el-input
        type="textarea"
        :rows="20"
        :model-value="showSkillOnly ? data.skillContent : data.content"
        readonly
      />
    </div>

    <!-- 操作区 -->
    <template #footer>
      <el-button type="primary" @click="copyContent">📋 复制全部</el-button>
      <el-button @click="copySkill">📋 仅复制 SKILL</el-button>
      <el-button @click="dialogClose">关闭</el-button>
    </template>
  </el-dialog>
</template>
```

### 4.4 列表页：注册改为向导式（MiniMax 贡献）

**修改文件**: `helloai-ui/src/views/agent/AgentList.vue`

关键改动点：

```ts
// 新增状态 — 注册后自动打开 onboarding
const onboardingDialog = ref(false)
const onboardingAgentId = ref<string | number | null>(null)

// 改造注册逻辑 — 修复 specializationSlug 传递 + 注册后切换弹窗
async function handleRegister() {
  const res: any = await agentApi.register({
    name: form.name,
    role: form.role,
    description: form.description,
    specializationSlug: form.specializationSlug || undefined  // ← 修复：之前未传递
  })
  // 不再用 ElMessage.success（一闪而过），改为：
  registerDialog.value = false
  onboardingAgentId.value = res.id  // 注册返回的 Agent ID
  onboardingDialog.value = true     // 直接打开 onboarding 弹窗
}

// 列表页按钮打开
function openOnboarding(agent: AgentListItem) {
  onboardingAgentId.value = agent.id
  onboardingDialog.value = true
}
```

模板新增：

```vue
<AgentOnboardingDialog
  v-model="onboardingDialog"
  :agent-id="onboardingAgentId"
/>
```

AgentCard 事件：

```vue
@onboarding="openOnboarding"
```

### 4.5 卡片组件增加操作按钮

**修改文件**: `helloai-ui/src/views/agent/components/AgentCard.vue`

```ts
// 新增 emit
const emit = defineEmits(['click', 'edit', 'toggle-status', 'delete', 'onboarding'])

// emit 调用
emit('onboarding', props.agent)
```

新增按钮（放在现有编辑/禁用/删除按钮旁）：

```html
<el-button size="small" type="primary" plain @click.stop="emit('onboarding', props.agent)">
  生成接入内容
</el-button>
```

### 4.6 详情页接入弹窗

**修改文件**: `helloai-ui/src/views/agent/AgentDetail.vue`

```ts
const onboardingDialog = ref(false)

function openOnboarding() {
  onboardingDialog.value = true
}
```

操作区新增按钮：

```vue
<el-button type="primary" plain @click="openOnboarding">生成接入内容</el-button>
```

底部挂载：

```vue
<AgentOnboardingDialog
  v-model="onboardingDialog"
  :agent-id="agent?.id || null"
/>
```

### 4.7 移除 PromptList 菜单（MiniMax 贡献）

**修改文件**: `helloai-ui/src/router/index.ts`

注释或删除 `/prompts` 路由（只注释，不删文件，可回滚）：

```ts
// {
//   path: 'prompts',
//   component: () => import('@/views/prompt/PromptList.vue'),
//   meta: { title: 'Prompt 管理' }
// },
```

**修改文件**: 侧边栏布局组件（`MainLayout.vue` 或对应 `SideMenu.vue`）

注释 Prompt 菜单项。

### 4.8 前端改动文件清单

#### 新增

```
helloai-ui/src/views/agent/components/AgentOnboardingDialog.vue
```

#### 修改

```
helloai-ui/src/api/agent.ts                        (+getOnboardingContent)
helloai-ui/src/types/index.ts                      (+AgentOnboardingResponse)
helloai-ui/src/views/agent/AgentList.vue            (注册向导 + 修复 specializationSlug + 挂载弹窗)
helloai-ui/src/views/agent/AgentDetail.vue          (+按钮 + 挂载弹窗)
helloai-ui/src/views/agent/components/AgentCard.vue (+按钮 + emit)
helloai-ui/src/router/index.ts                      (注释 /prompts 路由)
helloai-ui/src/layout/...                           (注释 Prompt 菜单项)
```

---

## 5. 接口清单

### 5.1 新增接口

#### 获取 Agent 接入内容

| 属性 | 值 |
|------|-----|
| 方法 | `GET` |
| 路径 | `/api/admin/agents/{id}/onboarding-content` |
| 鉴权 | 管理员 Token |

##### 成功响应示例

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "agentId": 12,
    "agentName": "AI小吴",
    "role": "EXECUTOR",
    "apiKey": "ak_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
    "baseUrl": "http://localhost:6565",
    "title": "复制到 Trae / Qoder 的接入内容",
    "content": "你是 HelloAI 平台中的一个已注册 Agent，请按以下信息接入并开始工作。\n\n【Agent 信息】\n- 名称：AI小吴\n- 角色：EXECUTOR\n- API Key：ak_a1b2c3...\n- 服务地址：http://localhost:6565\n\n【执行要求】\n...\n\n【SKILL】\n# Task Executor Skill\n...",
    "skillContent": "# Task Executor Skill\n\n你可以使用 task-cli.py 工具来与任务系统交互。\n..."
  }
}
```

### 5.2 接口调用关系

```
前端 AgentOnboardingDialog
    │
    ▼
GET /api/admin/agents/{id}/onboarding-content
    │
    ├─ AgentService.getById(id)              → Agent 实体
    ├─ AgentConfigProperties.getBaseUrl()    → 服务地址
    └─ PromptTemplateService
        ├─ getSkillForAgent(...)             → 纯 SKILL（变量替换）
        └─ buildOnboardingContent(...)        → 完整接入文本
```

---

## 6. 执行步骤

### 第 1 阶段：后端（预计 1 小时）

- [ ] 新增 `AgentOnboardingResponse.java` DTO
- [ ] 在 `PromptTemplateService.java` 新增 `buildOnboardingContent(...)`
- [ ] 在 `AdminAgentController.java` 新增 `GET /{id}/onboarding-content`
- [ ] 启动后端，用 curl/Postman 验证接口返回

**验收**：`curl http://localhost:6565/api/admin/agents/1/onboarding-content` 返回完整结构

### 第 2 阶段：前端类型与 API（预计 0.5 小时）

- [ ] 在 `types/index.ts` 新增 `AgentOnboardingResponse`
- [ ] 在 `api/agent.ts` 新增 `getOnboardingContent(id)`

**验收**：浏览器控制台可调通 API

### 第 3 阶段：前端组件（预计 2 小时）

- [ ] 新增 `AgentOnboardingDialog.vue`（摘要区 + 内容区 + 三个按钮）
- [ ] 在 `AgentCard.vue` 增加 `生成接入内容` 按钮和 `onboarding` emit
- [ ] 在 `AgentList.vue`：
  - 修复 `handleRegister` 传递 `specializationSlug`
  - 注册成功后不再用 `ElMessage.success`，改为打开 onboarding 弹窗
  - 挂载 `AgentOnboardingDialog` 处理列表页卡片按钮事件
- [ ] 在 `AgentDetail.vue`：加按钮 + 挂载弹窗

**验收**：注册新 Agent → 自动弹 onboarding → 按钮可用

### 第 4 阶段：清理（预计 0.5 小时）

- [ ] 在 `router/index.ts` 注释 `/prompts` 路由
- [ ] 在侧边栏组件注释 Prompt 菜单项

**验收**：侧边栏无 Prompt 菜单，直接访问 `/prompts` 无页面

### 第 5 阶段：联调验证（预计 1 小时）

- [ ] 注册一个新的 `EXECUTOR` Agent（选一个专业化）
- [ ] 验证注册弹窗第 2 步自动展示 onboarding 内容
- [ ] 验证 `specializationSlug` 已正确保存（在详情页查看）
- [ ] 验证 `agentName` / `role` / `apiKey` / `baseUrl` 均正确替换
- [ ] 验证"复制全部"和"仅复制 SKILL"按钮均可正常复制
- [ ] 列表页卡片点击"生成接入内容"→ 弹窗正常
- [ ] 详情页点击"生成接入内容"→ 弹窗正常
- [ ] 老 Agent（已注册的）两个入口均可正常生成
- [ ] 将复制内容粘贴到 Trae / Qoder，验证外部 Agent 可接入

---

## 7. 验收标准

| # | 标准 | 验证方式 |
|---|------|----------|
| 1 | 注册新 Agent 后自动弹出 onboarding 内容 | 注册一个 EXECUTOR，观察弹窗 |
| 2 | 内容中 `role/name/apiKey/baseUrl` 均为当前 Agent 真实值 | 目视检查弹窗内容 |
| 3 | `specializationSlug` 在注册时正确传递并持久化 | 详情页查看 |
| 4 | 列表页卡片"生成接入内容"按钮可见且可用 | 点击已有 Agent 的卡片按钮 |
| 5 | 详情页"生成接入内容"按钮可见且可用 | 进入任意 Agent 详情页 |
| 6 | "复制全部"可正常工作 | 粘贴到记事本验证 |
| 7 | "仅复制 SKILL"可正常工作 | 粘贴到记事本验证 |
| 8 | 侧边栏无"Prompt 模板"菜单项 | 目视检查侧边栏 |
| 9 | 用户无需进入 Prompt 菜单即可完成外部 Agent 接入 | 全流程走通 |
| 10 | 老 Agent 重置 Key 后可通过详情页重新获取接入内容 | 重置 Key → 点按钮 → 验证新 Key |

---

## 8. 后续可选增强

1. 弹窗增加 `下载 SKILL.md` 按钮
2. 接入内容中增加 `task-cli.py` 下载/安装说明
3. 生成接入内容后自动记录操作日志（`activity_log`）
4. 后续补齐 `task-cli.py` 缺失命令（`register`/`rules` 等），使 CLI 与 SKILL 完全对齐
5. 支持按 Agent 角色定制 onboarding 文案模板

---

## 9. 附录：方案决策记录

### A. 为什么采用"注册向导"而非"仅加按钮"

GPT-5 原方案只在列表/详情加按钮，不改造注册流程。但实际代码中（`AgentList.vue:194`），注册成功后的 API Key 显示在 `ElMessage.success` 里，3 秒自动消失——运营人员大概率错过。

MiniMax 方案指出的这个痛点是真实存在的。因此最终方案在 GPT-5 的按钮布局基础上，增加了注册向导式流程：注册成功后直接打开 onboarding 弹窗。

### B. 为什么移除 PromptList 菜单

PromptList.vue 展示的是 `prompt_template` 表的 CRUD，但：

- SKILL 内容的真正来源是 `resources/skills/{role}/SKILL.md` 文件，不是数据库
- 角色模板和专业化变体是为 LLM Agent 设计的 system prompt，运营人员不需要编辑
- 已有 RuleList 页面管理全局/模块/Agent 规则

因此移除菜单入口（不删后端代码和数据库表，可随时恢复）。

### C. DTO 设计：为什么返回 8 个字段

`content` 和 `skillContent` 分开返回，前端可以做"复制全部"和"仅复制 SKILL"两个独立按钮。`baseUrl` 前端无法自行获取（需要从配置文件或 request 上下文解析），由后端统一返回避免前端拼错。

### D. 改量估算

| 层级 | 新增文件 | 修改文件 | 预估行数 |
|------|----------|----------|----------|
| 后端 | 1 | 2 | ~80 |
| 前端 | 1 | 7 | ~220 |
| **合计** | **2** | **9** | **~300** |
