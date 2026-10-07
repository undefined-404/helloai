# ============================================================
# helloai 代码规范整改 阶段1(P0) 分层红线清理 验证脚本
# 用途：静态断言 8 个 Controller 无 QueryWrapper/lambdaQuery/updateById/save/select
#       直调；并断言 CODE_STYLE §11.3「Controller 不得暴露 core Entity」未超冻结基线；
#       然后打包 -> 启动 jar -> 等待就绪 -> 4 接口冒烟（非 5xx）-> 收尾。
# 说明：本阶段不改接口路径，冒烟使用当前路径；阶段2 路径整改后另行验证。
# 修复（2026-09-28）：原步骤 2/3/4 引用的 tmp\package-backend.ps1 /
#       tmp\kill-backend.ps1 / tmp\wait-backend.ps1 三个助手已不存在（脚本此前必然
#       在 [2/5] 失败），已按 start-sb.ps1 / kill-old.ps1 同口径内联，本机无 pwsh 未实跑。
# 追加（2026-10-07）：
#   - 新增 [1b/5] §11.3 静态断言（冻结基线 19 处 / 8 控制器，口径见 CODE_STYLE §11.3.1）；
#   - 新增 -StaticOnly 开关：只跑静态断言，不打包 / 不启动 / 不需要管理员口令；
#   - 修复 [5/5] 收尾对已删除脚本 tmp\kill-backend.ps1 的坏引用，改用 kill-old.ps1
#     （tmp\ 清理后原引用必然抛错，等于收尾步骤恒失败）。
# 用法（项目根）：
#   powershell -File .\scripts\powershell\verify-code-style-p0-layer.ps1 -StaticOnly
#   $env:HELLOAI_ADMIN_PASSWORD='<口令>'; powershell -File .\scripts\powershell\verify-code-style-p0-layer.ps1
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565",
    [switch]$StaticOnly
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8

$Root = 'e:\yhzx\1027\helloai'
$ControllerDir = Join-Path $Root 'helloai-api\src\main\java\com\helloai\api\controller'
$Controllers = @(
    'TaskController.java',
    'SubTaskController.java',
    'ModuleController.java',
    'RulesController.java',
    'CredentialController.java',
    'ScoreController.java',
    'AdminAgentController.java',
    'AgentController.java'
)
$Pattern = 'QueryWrapper|lambdaQuery|\.updateById\(|\.save\(|\.select'

function Assert-True([bool]$Cond, [string]$Msg) {
    if (-not $Cond) {
        throw ("ASSERT_FAIL: " + $Msg)
    }
}

# ---------- 1) 静态断言 ----------
Write-Host '== [1/5] static assertion on 8 controllers =='
$totalHits = 0
foreach ($file in $Controllers) {
    $path = Join-Path $ControllerDir $file
    Assert-True (Test-Path $path) ("controller file missing: " + $path)
    $hits = @(Select-String -Path $path -Pattern $Pattern -AllMatches)
    if ($hits.Count -gt 0) {
        foreach ($h in $hits) {
            Write-Host ("  HIT " + $file + ":" + $h.LineNumber + " " + $h.Line.Trim())
        }
    }
    $totalHits += $hits.Count
}
Assert-True ($totalHits -eq 0) ("static violation hits=" + $totalHits)
Write-Host '  STATIC_PASS (0 hits)'

# ---------- 1b) 静态断言：CODE_STYLE §11.3 API 层不得暴露 core Entity ----------
# 口径见 doc/HelloAI_CODE_STYLE.md §11.3.1：
#   命中 = helloai-api Controller 中 public 方法（HTTP 端点）的「签名」
#          （返回类型或入参）出现 com.helloai.core.*.entity 类型；
#   计数单位 = 方法；private 映射器 / VO / port record / 局部变量均不计。
Write-Host '== [1b/5] static assertion: CODE_STYLE 11.3 (controller must not expose core Entity) =='
$EntityBaseline = 19
$EntityImportRe = 'import\s+com\.helloai\.core\.[a-z]+\.entity\.([A-Za-z0-9_]+)'
$entityViolations = New-Object System.Collections.ArrayList
$entityFileSet = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($cf in @(Get-ChildItem -Path $ControllerDir -Filter '*Controller.java' -File | Sort-Object Name)) {
    $lines = @(Get-Content -Path $cf.FullName -Encoding UTF8)
    $ents = New-Object System.Collections.ArrayList
    foreach ($ln in $lines) {
        $m = [regex]::Match($ln, $EntityImportRe)
        if ($m.Success) { [void]$ents.Add($m.Groups[1].Value) }
    }
    $ents = @($ents | Sort-Object -Unique)
    if ($ents.Count -eq 0) { continue }

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $head = $lines[$i]
        # 只认方法声明行：public 开头 + 含 '(' + 非类声明（public class XxxController { 无 '('，天然排除）
        if ($head -notmatch '^\s*public\s' -or $head -notmatch '\(' -or $head -match '\bclass\b') { continue }
        # 拼接完整签名（返回类型 + 形参），按括号配平跨行收集
        $sig = $head
        $depth = ([regex]::Matches($sig, '\(')).Count - ([regex]::Matches($sig, '\)')).Count
        $j = $i
        while ($depth -gt 0 -and ($j + 1) -lt $lines.Count -and ($j - $i) -lt 40) {
            $j++
            $sig += ' ' + $lines[$j]
            $depth += ([regex]::Matches($lines[$j], '\(')).Count - ([regex]::Matches($lines[$j], '\)')).Count
        }
        foreach ($e in $ents) {
            # 词边界：防止 SubTaskResponse 撞车 SubTask、TaskTimeline 撞车 Task
            $wordRe = '(?<![A-Za-z0-9_])' + [regex]::Escape($e) + '(?![A-Za-z0-9_])'
            if ($sig -cmatch $wordRe) {
                [void]$entityViolations.Add("  VIOLATION " + $cf.Name + ":" + ($i + 1) + "  " + $sig.Trim())
                [void]$entityFileSet.Add($cf.Name)
                break
            }
        }
        $i = $j
    }
}
foreach ($v in $entityViolations) { Write-Host $v }
$entityCount = $entityViolations.Count
$entityFileCount = $entityFileSet.Count
Write-Host ("  11.3 violations=" + $entityCount + " controllers=" + $entityFileCount + " baseline=" + $EntityBaseline)
Assert-True ($entityCount -le $EntityBaseline) ("CODE_STYLE 11.3 regression: violations=" + $entityCount + " > baseline=" + $EntityBaseline)
if ($entityCount -lt $EntityBaseline) {
    Write-Host ("  IMPROVED: violations=" + $entityCount + " < baseline=" + $EntityBaseline + " ; refresh CODE_STYLE 11.3.1")
}
Write-Host '  11.3_STATIC_PASS'

# ---------- -StaticOnly：静态断言完成即退出（不打包 / 不启动 / 不需口令） ----------
if ($StaticOnly) {
    Write-Host 'STATIC_ONLY_DONE (skipped [2/5]..[5/5])'
    exit 0
}

$AdminPassword = $env:HELLOAI_ADMIN_PASSWORD
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { throw "未设置管理员口令：请导出环境变量 HELLOAI_ADMIN_PASSWORD" }

# ---------- 2) 打包 ----------
Write-Host '== [2/5] package backend =='
# 内联打包（替代已删除的 tmp\package-backend.ps1，与 start-sb.ps1 同口径；-f 定位根 pom，位置无关）
mvn -f (Join-Path $Root 'pom.xml') -pl helloai-start -am -DskipTests package | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'BUILD_FAILED'
}
Write-Host '  PACKAGE_PASS'

# ---------- 3) 启动 jar ----------
Write-Host '== [3/5] start backend jar =='
# 先停旧进程，保证幂等
# 改用既有 kill-old.ps1（替代已删除的 tmp\kill-backend.ps1，同为释放 6565 端口）
& (Join-Path $PSScriptRoot 'kill-old.ps1')
$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    throw 'JAVA_HOME_NOT_SET'
}
$javaExe = Join-Path $javaHome 'bin\java.exe'
Assert-True (Test-Path $javaExe) ("java.exe missing: " + $javaExe)
$jarPath = Join-Path $Root 'helloai-start\target\helloai-start-1.0.0-SNAPSHOT.jar'
Assert-True (Test-Path $jarPath) ("jar missing: " + $jarPath)
$proc = Start-Process -FilePath $javaExe `
    -ArgumentList @('-jar', $jarPath) `
    -RedirectStandardOutput (Join-Path $Root 'spring-boot-run.log') `
    -RedirectStandardError (Join-Path $Root 'spring-boot-err.log') `
    -PassThru -NoNewWindow
Write-Host ('  Started PID=' + $proc.Id)
$proc.Id | Out-File -FilePath (Join-Path $Root '.spring-boot-pid') -Encoding ASCII
Start-Sleep -Seconds 10
if ($proc.HasExited) {
    Get-Content (Join-Path $Root 'spring-boot-err.log') -Tail 20 -Encoding UTF8
    throw ('PROC_EXITED code=' + $proc.ExitCode)
}
Write-Host '  PROC_ALIVE_AFTER_10S'

# ---------- 4) 等待就绪 + 冒烟 ----------
Write-Host '== [4/5] wait ready =='
# 内联健康等待（替代已删除的 tmp\wait-backend.ps1；/api/health 与 verify-c3-env 同口径，最长 120s）
$ready = $false
foreach ($i in 1..40) {
    try {
        $null = Invoke-RestMethod -Uri ($BaseUrl + '/api/health') -TimeoutSec 3
        $ready = $true
        break
    } catch {
        Start-Sleep -Seconds 3
    }
}
if (-not $ready) {
    throw 'BACKEND_NOT_READY'
}
Write-Host '  BACKEND_UP'

Write-Host '== [4b/5] smoke 4 endpoints (non-5xx) =='
# 阶段1 不改路径：使用当前路径验证收口后接口仍正常
# 先登录 admin 拿到 token，带认证真实执行查询逻辑（401 只能证明路由存在）
$loginBody = @{ type = 'admin'; username = 'admin'; credential = $AdminPassword } | ConvertTo-Json
$loginResp = Invoke-RestMethod -Uri ($BaseUrl + '/api/auth/login') -Method Post `
    -ContentType 'application/json' -Body $loginBody -TimeoutSec 15
Assert-True ($loginResp.code -eq 200) ('admin login code=' + $loginResp.code + ' msg=' + $loginResp.msg)
$headers = @{ 'X-Admin-Token' = [string]$loginResp.data.token }
Write-Host '  ADMIN_LOGIN_PASS'

$smoke = @(
    '/api/tasks',
    '/api/sub-tasks/available',
    '/api/rules',
    '/api/scores/leaderboard'
)
foreach ($path in $smoke) {
    $url = $BaseUrl + $path
    try {
        $resp = Invoke-WebRequest -Uri $url -Method Get -Headers $headers -TimeoutSec 15 -UseBasicParsing
        $status = [int]$resp.StatusCode
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
    }
    Write-Host ('  ' + $path + ' -> HTTP ' + $status)
    Assert-True ($status -lt 500) ('smoke 5xx: ' + $path + ' status=' + $status)
}
Write-Host '  SMOKE_PASS'

# ---------- 5) 收尾 ----------
Write-Host '== [5/5] cleanup =='
# 复用既有 kill-old.ps1（原先引用的 tmp\kill-backend.ps1 随 tmp\ 清理已不存在，必然抛错）
& (Join-Path $PSScriptRoot 'kill-old.ps1')
Write-Host 'P0_LAYER_VERIFY_PASS'
