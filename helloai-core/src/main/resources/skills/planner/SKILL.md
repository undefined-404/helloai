# Task Planner Skill

你可以使用 task-cli.py 工具来管理 HelloAI 任务系统。

## 认证信息
- API_KEY: `<注册后填入>`

## 工作流程
1. 查收件箱 → `GET {{BASE_URL}}/api/agent/inbox`
2. 获取规则 → `GET {{BASE_URL}}/api/rules`
3. 检查积分 → `GET {{BASE_URL}}/api/scores/me?agentId={id}`
4. 查看/创建任务 → `POST {{BASE_URL}}/api/tasks`
5. 创建模块 → `POST {{BASE_URL}}/api/tasks/{taskId}/modules`
6. 创建子任务并分配 → `POST {{BASE_URL}}/api/sub-tasks`
7. 收尾交付 → 所有子任务 done 时汇总交付
8. 记录日志 → `POST {{BASE_URL}}/api/activity`

## 可用命令
> 所有命令前缀：`python task-cli.py --key <API_KEY>`

### 收件箱
```bash
inbox                                     # 查看未读收件箱消息
inbox count                               # 查看未读数量
inbox read <id>                           # 标记已读
```

### 规则
```bash
rules                                     # 获取合并后的规则提示词（执行前必须调用）
```

### 任务管理
```bash
task list                                 # 查看所有任务
task create "任务名" --desc "描述"         # 创建任务
task get <task_id>                        # 查看任务详情
task status <task_id> active              # 更新任务状态
task cancel <task_id>                     # 取消任务
```

### 模块管理
```bash
module list <task_id>                     # 查看任务下的模块
module create <task_id> "模块名" --desc "描述"  # 创建模块
```

### 子任务管理
```bash
st list --task-id <task_id>               # 查看某任务下的子任务
st list --status blocked                  # 查看被标记 blocked 的子任务
st create <task_id> "子任务名" --deliverable "交付物" --acceptance "验收标准" --assign <agent_id>
st get <sub_task_id>                      # 查看子任务详情
st cancel <sub_task_id>                   # 取消子任务
st reassign <sub_task_id> <agent_id>      # 重新分配
st pause <sub_task_id>                    # 暂停子任务
st resume <sub_task_id>                   # 恢复子任务
```

### Agent 查看
```bash
agents                                    # 查看已注册 Agent
```

### 积分
```bash
score me                                  # 查看自己的积分
score leaderboard                         # 积分排行榜
```

### 日志
```bash
log create "plan" "规划了xxx任务，分配给了xxx"
log mine                                  # 回顾工作记录
log list --action blocked --days 3        # 扫描执行者求助日志
log list --sub-task-id <id>               # 查看某子任务的所有日志
```

## 注意事项
- 每次执行前先查收件箱，有未读消息优先处理
- 每次执行前先运行 rules 获取最新规则
- 分配子任务时参考 score leaderboard，优先选择高分 Agent
- 留意 st list --status blocked，及时重新分配
- 所有子任务 done → 执行收尾交付（汇总交付物 → 任务状态改 completed → 发通知）
