# ============================================================
# helloai 代码规范整改 阶段1(P0) 分层红线清理 验证脚本
# 用途：静态断言 8 个 Controller 无 QueryWrapper/lambdaQuery/updateById/save/select
#       直调，然后打包 -> 启动 jar -> 等待就绪 -> 4 接口冒烟（非 5xx）-> 收尾。
# 说明：本阶段不改接口路径，冒烟使用当前路径；阶段2 路径整改后另行验证。
# 用法（项目根）：
#   powershell -File .\scripts\powershell\verify-code-style-p0-layer.ps1
# ============================================================
param(
    [string]$BaseUrl = "http://localhost:6565"
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

# ---------- 2) 打包 ----------
Write-Host '== [2/5] package backend =='
& (Join-Path $Root 'tmp\package-backend.ps1')
if ($LASTEXITCODE -ne 0) {
    throw 'BUILD_FAILED'
}
Write-Host '  PACKAGE_PASS'

# ---------- 3) 启动 jar ----------
Write-Host '== [3/5] start backend jar =='
# 先停旧进程，保证幂等
& (Join-Path $Root 'tmp\kill-backend.ps1')
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
& (Join-Path $Root 'tmp\wait-backend.ps1')
if ($LASTEXITCODE -ne 0) {
    throw 'BACKEND_NOT_READY'
}
Write-Host '  BACKEND_UP'

Write-Host '== [4b/5] smoke 4 endpoints (non-5xx) =='
# 阶段1 不改路径：使用当前路径验证收口后接口仍正常
# 先登录 admin 拿到 token，带认证真实执行查询逻辑（401 只能证明路由存在）
$loginBody = @{ type = 'admin'; username = 'admin'; credential = 'admin123' } | ConvertTo-Json
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
& (Join-Path $Root 'tmp\kill-backend.ps1')
Write-Host 'P0_LAYER_VERIFY_PASS'
