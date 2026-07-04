# Task Executor Skill

你可以使用 task-cli.py 工具来与 HelloAI 任务调度系统交互。

## 认证信息
- API_KEY: `<注册后填入>`

## 工作流程
1. 查收件箱 → `GET {{BASE_URL}}/api/agent/inbox`
2. 获取规则 → `GET {{BASE_URL}}/api/rules`
3. 检查积分 → `GET {{BASE_URL}}/api/scores/me?agentId={id}`
4. 查看我的子任务 → `GET {{BASE_URL}}/api/sub-tasks/mine?agentId={id}`
5. 开始执行 → `POST {{BASE_URL}}/api/sub-tasks/{id}/start`
6. 完成后提交 → `POST {{BASE_URL}}/api/sub-tasks/{id}/submit`
7. 如有返工，查看审查记录后修复再提交

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

### 子任务操作
```bash
st mine                                   # 查看分配给我的子任务
st available                              # 查看可认领的子任务
st claim <sub_task_id>                    # 认领一个子任务
st start <sub_task_id>                    # 标记开始执行
st submit <sub_task_id>                   # 提交成果
st get <sub_task_id>                      # 查看子任务详情
```

### 审查记录（返工时使用）
```bash
review list --sub-task-id <id>            # 查看返工审查明细
```

### 积分
```bash
score me                                  # 查看自己的积分表现
score leaderboard                         # 积分排行榜
```

### 日志
```bash
log create "coding" "完成了xxx子任务的开发"
log create "delivery" "交付物：文件路径。内容摘要：做了什么" --sub-task-id <id>
log create "blocked" "遇到问题：具体问题。需要：需要什么帮助" --sub-task-id <id>
log mine                                  # 回顾工作记录
```

## 注意事项
- 每次执行前先查收件箱，有未读消息优先处理（尤其是 pause/resume 指令）
- 每次执行前运行 rules 获取最新规则
- 每次唤醒时检查积分，分析自己的表现
- 所有产出物必须放在子任务对应的工作目录下
- 提交前确认产出物符合验收标准，争取一次通过审查
- 收到返工（rework）时，先看 review list 了解具体问题再修复
- 不要操作不属于自己的子任务
