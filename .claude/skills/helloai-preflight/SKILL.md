---
name: "helloai-preflight"
description: "HelloAI 项目动手前守则。凡是在本仓库做代码修改、重构、修 Bug、加功能、补迁移或改配置前，都应先读基线、差距、执行记录、调度分析、代码规范与架构参考。"
---

> 由 `.agents/helloai-guidance.master.json` 通过 `sync-agent-guidance.ps1` 生成。请不要手工直接修改本文件。

# HelloAI 动手前守则

本 skill 用于约束 HelloAI 仓库内的开发前检查，避免脱离项目现状、偏离既定技术选型，或写出不符合项目代码风格的改动。

## 触发时机

以下场景都应优先读取本 skill：

- 修改任意后端、前端、脚本、配置、SQL、测试代码
- 新增功能、修复 Bug、补骨架、做重构
- 落地路线图任务、差距项补齐、执行链路打通
- 修改 README、设计说明或实现文档，且改动与代码事实相关

如果只是做纯阅读、纯讨论、纯评审，可以不强制完整执行；但一旦准备动手改代码，就应先按本 skill 完成预检。

## 动手前必须先读的文档

执行代码修改前，必须先参考以下文档：

1. doc/HelloAI_项目基线文档.md
2. doc/HelloAI_实现差距表.md
3. doc/log/HelloAI_迭代执行记录.md
4. doc/design/HelloAI_调度解耦重构分析.md
5. doc/HelloAI_CODE_STYLE.md
6. doc/design/HelloAI_架构设计参考.md

## 读取目的

### 1. HelloAI_项目基线文档

先确认当前项目已经做到什么、哪些仍是目标态、哪些历史文档只是参考资产，避免按过期路线图直接开做。

### 2. HelloAI_实现差距表

先确认目标项当前是已交付、部分落地、未落地还是文档失真，避免把该改文档的问题误做成补功能。

### 3. HelloAI_迭代执行记录

先确认最近已经改过什么、收口到哪里、还有什么遗留约束，避免重复建设或把已确认结论推翻。

### 4. HelloAI_调度解耦重构分析

涉及调度、执行链、异步回写、MQ 解耦等改动时，必须先确认当前收敛方向与 AgentTeams 对齐点，避免把已经拆开的职责重新揉回单链路。

### 5. HelloAI_CODE_STYLE

所有代码修改都必须遵循代码规范，尤其关注包结构与分层边界、Controller/Service/Mapper 职责分离、DTO 返回约束、命名规范、注释风格、异常处理与事务边界，以及测试与脚本风格。

### 6. HelloAI_架构设计参考

涉及项目整体开发计划、阶段目标、技术选型、Agent 相关能力建设时，应参考架构设计参考，确保参考来源、核心概念与目标态方向保持一致。

## 执行规则

### 规则 1：先判断"这是补功能还是改口径"

动手前先用 3 到 5 句话说清：

- 这次要改什么
- 为什么改
- 属于补功能、补骨架、修 bug，还是修正文档口径
- 本次明确不做什么

### 规则 2：遇到文档冲突，按基线文档的事实源优先级判断

如果文档之间存在冲突，不要凭印象选。优先参考基线文档中定义的事实源优先级，核心原则是：

1. Code and runtime behavior
2. Flyway initialization scripts and database structure
3. Verification scripts and reproducible validation results
4. `doc/HelloAI_实现差距表.md`
5. `doc/HelloAI_项目基线文档.md`
6. `README.md`
7. Historical roadmap / technical plan / comparison documents

### 规则 3：所有代码改动必须服从项目既定技术边界

尤其注意：

- JDK 固定 `17`
- Spring AI 维持当前项目基线，不做跨大版本升级
- 以后端真实实现、Flyway、验收脚本为准，不拿旧路线图覆盖现实代码
- 不把编排逻辑塞进 Controller
- 涉及调度、执行链、异步回写、MQ 解耦的改动时，优先遵循 `doc/design/HelloAI_调度解耦重构分析.md`
- 旧路线图、旧技术方案和对比文档只保留为历史资产，不再作为当前实现的主设计依据

### 规则 4：优先做"小而闭环"的落地

如果任务较大，应优先推进：

- 后端骨架
- 最小可验证链路
- 必要测试或脚本验证
- 必要文档回填

避免一边做基座、一边顺手扩散到无关前端细修或大规模文档清洗。

### 规则 5：改完后检查是否需要回填文档

以下场景应同步考虑文档更新：

- 现实基线发生变化
- 差距项被关闭或状态改变
- 本轮有明确开发记录需要沉淀

优先回填：

- `doc/HelloAI_实现差距表.md`
- `doc/log/HelloAI_迭代执行记录.md`

### 规则 6：脚本必须显式声明 UTF-8 编码，避免中文乱码

任何写入仓库的 PowerShell（`.ps1`）或 Linux shell 脚本（`.sh`），必须在文件开头（注释头之后、业务逻辑之前）显式声明输出编码，避免在中文 Windows / 非 UTF-8 终端下出现打印乱码或日志乱码。

**PowerShell 脚本（`.ps1`）强制模板**（来自 `verify-agenthub-duty-e2e.ps1` L32-39 实战可用的最终版本）：

```powershell
# ------------------------------------------------------------
# UTF-8 编码强制头（规则 6）—— 避免中文乱码
# ------------------------------------------------------------
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = $script:Utf8NoBom   # 关键：无 BOM，防止管道输出时添加 BOM
```

**模板要点**（与下方"源文件本身编码 / 管道原始字节传输 / here-string 串入 BOM"三段是同一套 4 件套的入口段，缺一不可）：

- `$script:Utf8NoBom`：**无 BOM 的 UTF-8** 实例化对象。下游所有 `[IO.File]::WriteAllText` / `StandardInputEncoding` / `GetBytes` 都用这个实例，保证字节流无 BOM 污染
- `[Console]::InputEncoding` / `[Console]::OutputEncoding`：控制 PS 5.1 控制台 stdin/stdout 在中文 Windows 下的中文渲染
- `$OutputEncoding`：**用无 BOM 实例**，**不要用** `[System.Text.Encoding]::UTF8`（后者在 PS 5.1 下管道输出时会多写一个 BOM，让下游 `docker exec psql` / `ssh` 解析失败）
- 入口函数（`Run-Psql` / `Send-Mcp` / `Invoke-Json` 等 helper）首行必须 `$Sql = $Sql.TrimStart([char]0xFEFF)` 剥源文件 BOM
- 源文件本身必须保存为 **UTF-8 with BOM**（规则 6 下面那段），否则 PS 5.1 默认按 GBK 解析源码，会在解析期抛 `字符串缺少终止符`，根本走不到这 4 行
- 这套模板已在 `verify-agenthub-duty-e2e.ps1`（S1 checkIn / S2 checkOut / S3 Lease 过期扫描 ALL PASSED）覆盖所有中文路径：控制台打印 + docker exec psql stdin + 临时文件落盘 + JSON body 写入

**Linux shell 脚本（`.sh`）强制模板**：

```
#!/usr/bin/env bash
# <脚本用途简介>
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8
```

要求：

- 所有仓库内新增的 `verify-*.ps1` / `start-*.ps1` / `test-*.ps1` / e2e 冒烟脚本 / hook 脚本 / CI 脚本，都必须遵循上述模板
- 脚本内如需通过 `Invoke-WebRequest` / `curl` 发送包含中文的 JSON body，应把 body 以 UTF-8 编码写入临时文件再引用，不要依赖控制台默认编码
- 出现“控制台中文乱码”或“日志中文乱码”时，先检查是否漏掉这两行，再排查其他原因
- 该规则不因用途“临时验证”而豁免，任何长期或临时脚本都适用

**源文件本身编码（PS 5.1 兼容关键）**：

上面两行只解决“运行时输出”的编码。`.ps1` **源文件自身的字节编码**是另一件事，PS 5.1 在中文 Windows 上默认按 GBK 解析源码，若脚本存为 UTF-8 no-BOM，中文字节会被误识为字符串引号，抛 `TerminatorExpectedAtEndOfString` / `字符串缺少终止符: "` 类解析错误，此时规则 6 上面的两行根本还没执行到。

必须满足以下之一：

- **首选**：保存为 **UTF-8 with BOM**（前 3 字节 `EF BB BF`）。PowerShell 5.1 见到 BOM 后切 UTF-8 解析器，任意位置的中文字符串、注释、变量都安全。
- **次选**：全脚本纯 ASCII（含注释也不用中文）。历史脚本 `verify-mcp-e2e.ps1` 就是这种做法。

排查与修复：脚本运行报 `字符串缺少终止符` 且报错行含中文，先怀疑源文件是 UTF-8 no-BOM。一键修回 UTF-8 with BOM：

```
$p = 'xxx.ps1'
$c = Get-Content -Raw -Encoding UTF8 $p
[IO.File]::WriteAllText($p, $c, (New-Object System.Text.UTF8Encoding($true)))
```

生成或改动 `.ps1` 后，若含中文字面量，应用 `[System.Management.Automation.Language.Parser]::ParseFile` 做一次静态语法自检，确认 0 error 再交付。

**管道原始字节传输（PS 5.1 外部命令 stdin 编码问题）**：

PowerShell 5.1 将字符串通过管道喂给外部命令（`$s | docker`、`$s | ssh`、`$s | mysql` 等）时，会把字符串以 **UTF-16 LE + BOM** 写入 stdin。对纯 ASCII 文本看起来正常（因为 ASCII 在 UTF-8/16 LE/ANSI 下字节相同），但一旦包含中文/换行/特殊符号，外部命令会收到错乱的字节序，报 `syntax error at or near "X"` 或静默失败。

同样地，`Get-Content -Raw` 不带 `-Encoding` 时默认按 ANSI（中文 Windows = GBK）读文件，读 UTF-8 文件时会产生 U+FEFF 等隐藏字符污染源文本。

**管道传原文给 docker exec / ssh / mysql 等外部命令的推荐写法**：

- **首选**：先 `Set-Content -Encoding UTF8` 或 `Out-File -Encoding UTF8` 落临时文件，再用 `cmd /c type <file> | <external>` 让 cmd 透传原始字节
- **次选**：用 `.NET Process API`（`[System.Diagnostics.Process]`），手动设置 `StartInfo.StandardInputEncoding = UTF8` 后通过 `BaseStream.Write()` 写字节流
- **避免**：字符串直接 `| docker` / `| ssh` / `| mysql` —— UTF-16 LE 包装无法关闭

**BOM 通过 here-string 串入字符串变量（隐蔽陷阱）**：

如果 `.ps1` 本身是 **UTF-8 with BOM**，PS 5.1 解析 here-string（`@"..."@`）或字符串字面量时会**保留源文件首字符 U+FEFF** 作为变量首字符。任何后续写到磁盘或送管道的 SQL/JSON 都会以 `U+FEFF` 开头，导致下游 `psql` 报 `syntax error at or near "INSERT"`、JSON 解析器报错、HTTP body 多一个隐形字符。

**入口函数必须 strip**：

```powershell
$Sql = $Sql.TrimStart([char]0xFEFF)
```

所有接收 here-string / 字符串字面量作为入参的 helper（Run-Psql、Send-Mcp、Invoke-Json 等）都应该在入口第一行 trim。

生成涉及 `docker exec / ssh / mysql -e` 等命令的脚本时，必须先用上面两种方式之一保证源数据无编码转换。

**双引号内 CJK 导致解析器提前闭合字符串（PS 5.1 隐蔽解析陷阱）**：

即使源文件已存为 UTF-8 with BOM，只要 `Write-Output` / `Write-Host` 等用**双引号字符串**且串内含中文（尤其全角括号 `（）`、全角引号 `""`），PS 5.1 解析器在遇到某些全角字符字节叠加微妙 BOM/隐藏字符时，可能把它误判为字符串结束引号、**提前闭合字符串**；随后本该在串内的内容变成裸 token，再往后的 `}` 被当成多余符号，抛 `Unexpected token '}'` / `字符串缺少终止符` 类解析错误。这与"源文件必须 UTF-8 with BOM"是叠加隐患——BOM 只降低概率，双引号 + CJK + 变量插值组合仍可能踩到解析器边界。

**修复范式（推荐，最稳）：所有要输出的字符串一律用单引号 + `+` 拼接变量，彻底避开双引号插值；runtime 字面量保持纯 ASCII，中文只留在 `#` 注释里（注释不参与字符串解析）。**

```powershell
# 反例：双引号插值 + 中文全角括号，易触发提前闭合
Write-Output "[$Scenario] PASS : $Detail（启动成功）"

# 正例：单引号逐字字符串 + 拼接，纯 ASCII 输出
Write-Output ('[' + $Scenario + '] PASS : ' + $Detail)
```

要点：

- 单引号是 PS 的"逐字字符串"，不插值不转义，解析器不会在其中寻找 `$` / 引号边界，最不易被 CJK 字节干扰。
- 需要拼变量时用 `('literal ' + $var + ' literal')`，不要退回双引号。
- 中文提示语放脚本顶部 `#` 注释块或独立 `.md`，不塞进 `Write-Output` 字符串体；头注释本身也尽量纯 ASCII。
- 落地参考：`verify-execution-dispatch-guard.ps1`（S6 守卫脚本）已全量单引号 + 拼接、头注释无中文。

## 推荐执行顺序

当用户要求"继续开发""补某个路线图项""修某块实现"时，建议采用下面顺序：

1. 先读上面 6 份文档
2. 提炼当前任务边界与不做项
3. 到代码中确认现状
4. 再动手修改
5. 做最小验证
6. 视情况回填文档

## 一句话要求

在 HelloAI 仓库中，任何代码修改都不应脱离以下前提：**先读基线、先看差距、先对执行记录、先校准调度收敛方向、严格遵守代码规范，并参考当前架构设计参考再动手。**