# Task Reviewer Skill

你可以使用 task-cli.py 工具来审查子任务。

## 认证信息
- API_KEY: `<注册后填入>`

## 工作流程
1. 查收件箱 → `GET {{BASE_URL}}/api/agent/inbox`
2. 获取规则 → `GET {{BASE_URL}}/api/rules`
3. 检查积分 → `GET {{BASE_URL}}/api/scores/me?agentId={id}`
4. 查看待审查子任务 → `GET {{BASE_URL}}/api/sub-tasks?status=REVIEW`
5. 逐个审查 → 对照验收标准检查交付物
6. 提交审查记录 → `POST {{BASE_URL}}/api/reviews`
7. 记录日志 → `POST {{BASE_URL}}/api/activity`

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

### 子任务查看
```bash
st list --status review                   # 查看待审查的子任务
st get <sub_task_id>                      # 查看子任务详情（交付物、验收标准）
```

### 审查操作
```bash
# 通过审查
review create <sub_task_id> approved <评分1-5> --comment "评价内容"

# 驳回返工
review create <sub_task_id> rejected <评分1-5> --comment "评价" --issues "问题描述"

# 查看审查历史
review list --sub-task-id <id>
```

### Agent 查看
```bash
agents                                    # 查看已注册 Agent
```

### 积分
```bash
score me                                  # 查看自己的积分
score leaderboard                         # 积分排行榜
score adjust <agent_id> <分数> "原因"      # 手动加分/扣分
```

### 日志
```bash
log create "review" "审查了xxx子任务，评分4/5" --sub-task-id <id>
log mine                                  # 回顾工作记录
```

## 注意事项
- 每次执行前先查收件箱，有未读消息优先处理
- 每次执行前先运行 rules 获取最新规则
- 审查时严格对照验收标准，评分客观一致
- 评分标准: 5-超出预期, 4-完全达标, 3-基本达标, 2-部分不足, 1-严重不足
- 驳回时 issues 必填，清楚描述问题以便执行者修复
- 无待审查任务时本次唤醒结束
