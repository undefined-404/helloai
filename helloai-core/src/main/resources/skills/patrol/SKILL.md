# Task Patrol Skill

你可以使用 task-cli.py 工具来巡查任务状态。

## 认证信息
- API_KEY: `<注册后填入>`

## 工作流程
1. 查收件箱 → `GET {{BASE_URL}}/api/agent/inbox`
2. 获取规则 → `GET {{BASE_URL}}/api/rules`
3. 检查积分 → `GET {{BASE_URL}}/api/scores/me?agentId={id}`
4. 扫描异常任务 → 检查超时/卡住/孤儿/返工溢出
5. 标记 blocked → `POST {{BASE_URL}}/api/sub-tasks/{id}/block`
6. 记录日志 → `POST {{BASE_URL}}/api/activity`

## 可用命令
> 所有命令前缀：`python task-cli.py --key <API_KEY>`

### 收件箱
```bash
inbox                                     # 查看未读收件箱消息
inbox count                               # 查看未读数量
```

### 规则
```bash
rules                                     # 获取合并后的规则提示词（执行前必须调用）
```

### 任务巡查
```bash
task list                                 # 查看所有任务
st list --status in_progress              # 检查是否超时/卡住
st list --status assigned                 # 检查是否长期未启动
st list --status blocked                  # 查看已标记异常的
st list --status paused                   # 查看已暂停的
st get <sub_task_id>                      # 查看子任务详情
```

### 异常标记
```bash
st block <sub_task_id>                    # 标记子任务异常
```

### Agent 查看
```bash
agents                                    # 查看已注册 Agent
agents --role executor                    # 按角色过滤
```

### 积分
```bash
score me                                  # 查看自己的积分
score leaderboard                         # 积分排行榜
```

### 日志
```bash
log create "patrol" "巡查发现xxx子任务超时，已标记blocked" --sub-task-id <id>
log mine                                  # 回顾工作记录
log list --action blocked --days 3        # 扫描执行者求助日志
```

## 异常处理规则
| 异常类型 | 判定条件                | 严重级别 | 处理方式           |
| -------- | ----------------------- | -------- | ------------------ |
| 超时     | in_progress 超过 1 小时 | warning  | 写记录 + 通知      |
| 严重超时 | in_progress 超过 2 小时 | critical | 标记 blocked + 通知 |
| 卡住     | 超 2 小时无更新         | warning  | 写记录 + 通知      |
| 孤儿任务 | 无认领超 1 小时         | warning  | 通知规划师         |
| 返工溢出 | 返工次数 ≥ 3            | warning  | 写记录 + 通知      |

## 注意事项
- 每次执行前先查收件箱，有未读消息优先处理
- 每次执行前先运行 rules 获取最新规则
- 只查不改（warning）— 一般异常只写记录 + 发通知
- 紧急干预（critical）— 严重异常才主动标记 blocked
- 不要直接修改子任务的内容或状态（除 block 外）
