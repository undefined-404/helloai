# ============================================================
# helloai G-010 D5-2 技能包一致性校验脚本
# 用途：校验 AgentSkillSpecServiceImpl.KNOWN_SPECS 声明与运行资产的静态一致性：
#       A. fileName 均存在于 classpath（src/main/resources/skills/plugins/，
#          已构建时同步核对 target/classes 拷贝）
#       B. requiredTools 均命中 ToolRegistry 注册事实（@Tool 注解单一事实源，
#          显式 name 或方法名两种形态都收集）
#       C. version 格式合法（三段式数字，如 1.0.0）
#       D. 声明结构防漂移（name / fileName 唯一；未引用资源文件 WARN）
# Ref:  doc/design/Planner_Capability_Awareness.md §6-D5-2
# 前置：无（纯本地静态校验，不依赖服务 / 数据库 / 构建产物）
# 用法（项目根）：
#   powershell -File .\scripts\powershell\verify-skill-packages.ps1
#   powershell -File .\scripts\powershell\verify-skill-packages.ps1 -RepoRoot D:\work\helloai
# 退出码：0 = 全 PASS（WARN 可容忍）；1 = 存在 FAIL
# ============================================================
param(
    [string]$RepoRoot = (Get-Location).Path,
    [switch]$Quiet
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8

$global:PassCount = 0
$global:FailCount = 0
$global:WarnCount = 0

function Write-Check([string]$Level, [string]$Msg) {
    switch ($Level) {
        "PASS" { $global:PassCount++; Write-Host "[PASS] $Msg" }
        "FAIL" { $global:FailCount++; Write-Host "[FAIL] $Msg" -ForegroundColor Red }
        "WARN" { $global:WarnCount++; Write-Host "[WARN] $Msg" -ForegroundColor Yellow }
    }
}

function Read-Utf8File([string]$Path) {
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

# 把块文本按"顶层逗号"切成参数列表：括号平衡 + 引号感知（嵌套 List.of()/Map.of() 不误切）
# 注意：逐字符分支全部用 if/elseif 显式 [char] 比较——PS 5.1 下 switch 对 char 输入的
# case 匹配不可靠，实测同一逻辑 switch 版与 if 版行为不一致，改用 if 链保证确定性。
function Split-TopLevelArgs([string]$Block) {
    $result = @()
    $depth = 0
    $inStr = $false
    $sb = New-Object System.Text.StringBuilder
    foreach ($c in $Block.ToCharArray()) {
        if ($inStr) {
            [void]$sb.Append($c)
            if ($c -eq [char]'"') { $inStr = $false }
            continue
        }
        if ($c -eq [char]'"') { $inStr = $true; [void]$sb.Append($c); continue }
        if ($c -eq [char]'(') { $depth++; [void]$sb.Append($c); continue }
        if ($c -eq [char]')') { $depth--; [void]$sb.Append($c); continue }
        if ($c -eq [char]',') {
            if ($depth -eq 0) {
                $result += $sb.ToString().Trim()
                [void]$sb.Clear()
            } else {
                [void]$sb.Append($c)
            }
            continue
        }
        [void]$sb.Append($c)
    }
    if ($sb.Length -gt 0) { $result += $sb.ToString().Trim() }
    Write-Output $result
}

# 从 "List.of(...)" 或空串提取工具名数组（去引号、去空白）
function Parse-ListOf([string]$Text) {
    $tools = @()
    if ($null -eq $Text -or $Text.Trim() -eq "") { return }
    $m = [regex]::Match($Text.Trim(), '^List\.of\s*\((.*)\)$', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $m.Success) { return }
    $inner = $m.Groups[1].Value
    if ($inner.Trim() -eq "") { return }
    # 内层顶层逗号切分（if 链保证 PS 5.1 确定性）
    $parts = @()
    $depth = 0
    $inStr = $false
    $sb = New-Object System.Text.StringBuilder
    foreach ($c in $inner.ToCharArray()) {
        if ($inStr) {
            [void]$sb.Append($c)
            if ($c -eq [char]'"') { $inStr = $false }
            continue
        }
        if ($c -eq [char]'"') { $inStr = $true; [void]$sb.Append($c); continue }
        if ($c -eq [char]'(') { $depth++; [void]$sb.Append($c); continue }
        if ($c -eq [char]')') { $depth--; [void]$sb.Append($c); continue }
        if ($c -eq [char]',') {
            if ($depth -eq 0) { $parts += $sb.ToString().Trim(); [void]$sb.Clear() }
            else { [void]$sb.Append($c) }
            continue
        }
        [void]$sb.Append($c)
    }
    if ($sb.Length -gt 0) { $parts += $sb.ToString().Trim() }
    foreach ($p in $parts) {
        $name = $p.Trim().Trim('"').Trim()
        if ($name -ne "") { $tools += $name }
    }
    Write-Output $tools
}

# 从 @Tool 起始位置解析工具名：优先 name = "xx"，缺省回落方法名（spring-ai 语义）
function Get-ToolName([string]$Src, [int]$Idx) {
    $open = $Src.IndexOf('(', $Idx)
    if ($open -lt 0) { return $null }
    $k = $open + 1
    while ($k -lt $Src.Length -and [char]::IsWhiteSpace($Src[$k])) { $k++ }
    # 显式 name = "..."
    if ($k + 4 -le $Src.Length -and $Src.Substring($k, 4) -eq "name") {
        $eq = $Src.IndexOf('=', $k)
        if ($eq -gt 0) {
            $q1 = $Src.IndexOf('"', $eq + 1)
            if ($q1 -gt 0) {
                $q2 = $Src.IndexOf('"', $q1 + 1)
                if ($q2 -gt $q1) { return $Src.Substring($q1 + 1, $q2 - $q1 - 1) }
            }
        }
        return $null
    }
    # 无 name → 括号平衡扫过注解参数区，取方法名
    $depth = 1
    $inStr = $false
    $i = $open + 1
    while ($i -lt $Src.Length) {
        $c = $Src[$i]
        if ($inStr) {
            if ($c -eq [char]'"') {
                if ($i + 1 -lt $Src.Length -and $Src[$i + 1] -eq [char]'"') { $i++ }
                else { $inStr = $false }
            }
        } else {
            if ($c -eq [char]'"') { $inStr = $true }
            elseif ($c -eq [char]'(') { $depth++ }
            elseif ($c -eq [char]')') {
                $depth--
                if ($depth -eq 0) { $i++; break }
            }
        }
        $i++
    }
    $sig = $Src.Substring($i, [Math]::Min(600, $Src.Length - $i))
    $m = [regex]::Match($sig, '(?s)\bpublic\s+[\w<>\.,\[\]\? ]+\s+(\w+)\s*\(')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

# ════════════════════ STEP1: 定位与解析 KNOWN_SPECS 声明 ════════════════════
Write-Host "STEP1: 解析 KNOWN_SPECS 声明"
$coreDir = Join-Path $RepoRoot "helloai-core"
$specFile = Join-Path $coreDir "src\main\java\com\helloai\core\agent\skill\AgentSkillSpecServiceImpl.java"
if (-not (Test-Path $specFile)) {
    Write-Check "FAIL" "未找到 KNOWN_SPECS 源文件: $specFile（确认在仓库根运行或 -RepoRoot 正确）"
    Write-Host "校验中止：$($global:FailCount) FAIL / $($global:PassCount) PASS / $($global:WarnCount) WARN"
    exit 1
}
$specSrc = Read-Utf8File $specFile
$chunks = [regex]::Split($specSrc, 'new\s+SkillPackage\s*\(')
$packages = New-Object System.Collections.Generic.List[object]
$declaredNames = New-Object System.Collections.Generic.List[string]
$declaredFiles = New-Object System.Collections.Generic.List[string]
for ($ci = 1; $ci -lt $chunks.Length; $ci++) {
    $chunk = $chunks[$ci]
    # 截到该包块的闭合括号（顶层平衡）。注意：拆分正则已消费构造函数自身的开括号，
    # 故深度从 1 起算，首次回到 0 即包闭合（避免被嵌套的空 List.of() 误判）。
    $depth = 1
    $inStr = $false
    $end = -1
    $chars = $chunk.ToCharArray()
    for ($i = 0; $i -lt $chars.Length; $i++) {
        $c = $chars[$i]
        if ($inStr) {
            if ($c -eq '"') { $inStr = $false }
        } else {
            if ($c -eq '"') { $inStr = $true }
            elseif ($c -eq '(') { $depth++ }
            elseif ($c -eq ')') {
                $depth--
                if ($depth -eq 0) { $end = $i; break }
            }
        }
    }
    $block = if ($end -gt 0) { $chunk.Substring(0, $end) } else { $chunk }
    $block = $block -replace '\s+', ' '
    $argList = @(Split-TopLevelArgs $block)
    if ($argList.Count -lt 9) {
        Write-Check "FAIL" "SkillPackage 声明参数不足（期望 9 参，实得 $($argList.Count)），需人工核对 knownSpecs()"
        continue
    }
    $name = $argList[0].Trim('"').Trim()
    $version = $argList[1].Trim('"').Trim()
    $requiredTools = @(Parse-ListOf $argList[3])
    $file = $argList[8].Trim('"').Trim()
    $pkgObj = [PSCustomObject]@{
        Name          = $name
        Version       = $version
        RequiredTools = $requiredTools
        FileName      = $file
    }
    $packages.Add($pkgObj)
    $declaredNames.Add($name)
    $declaredFiles.Add($file)
    Write-Host "  声明: $name  v$version  file=$file  requiredTools=[$($requiredTools -join ', ')]"
}
if ($packages.Count -eq 0) {
    Write-Check "FAIL" "未解析到任何 SkillPackage 声明（正则可能未匹配，需人工核对源码）"
    Write-Host "校验中止：$($global:FailCount) FAIL / $($global:PassCount) PASS / $($global:WarnCount) WARN"
    exit 1
}
Write-Check "PASS" "解析到 $($packages.Count) 个技能包声明"

# ════════════════════ STEP2: 结构与版本格式校验 ════════════════════
Write-Host "STEP2: name/fileName 唯一性与 version 格式"
# map.put 键重复 = 后 put 覆盖前 put（LinkedHashMap），技能被静默吞掉——最隐蔽的漂移点
$putKeys = [regex]::Matches($specSrc, 'map\.put\s*\(\s*"([^"]+)"\s*,\s*new\s+SkillPackage')
$keyList = @($putKeys | ForEach-Object { $_.Groups[1].Value })
$dupKey = $keyList | Group-Object | Where-Object { $_.Count -gt 1 }
if ($dupKey) { Write-Check "FAIL" "map.put 键重复（后包静默覆盖前包）: $($dupKey.Name -join ', ')" } else { Write-Check "PASS" "map.put 键全部唯一" }
$dupName = $declaredNames | Group-Object | Where-Object { $_.Count -gt 1 }
if ($dupName) { Write-Check "FAIL" "name 重复: $($dupName.Name -join ', ')" } else { Write-Check "PASS" "name 全部唯一" }
$dupFile = $declaredFiles | Group-Object | Where-Object { $_.Count -gt 1 }
if ($dupFile) { Write-Check "FAIL" "fileName 重复（LinkedHashMap 后值覆盖前值）: $($dupFile.Name -join ', ')" } else { Write-Check "PASS" "fileName 全部唯一" }
foreach ($p in $packages) {
    $ver = $p.Version
    if ($ver -match '^[0-9]+\.[0-9]+\.[0-9]+$') {
        Write-Check "PASS" "版本格式合法: $($p.Name) v$ver"
    } else {
        Write-Check "FAIL" "版本格式非法（期望三段式数字 1.0.0）: $($p.Name) v='$ver'"
    }
    if ([string]::IsNullOrWhiteSpace($p.Name)) { Write-Check "FAIL" "name 为空: 声明序 #$(($packages.IndexOf($p)) + 1)" }
}

# ════════════════════ STEP3: fileName 存在于 classpath ════════════════════
Write-Host "STEP3: fileName 存在于 classpath（资源目录）"
$srcPlugins = Join-Path $coreDir "src\main\resources\skills\plugins"
$targetPlugins = Join-Path $coreDir "target\classes\skills\plugins"
if (-not (Test-Path $srcPlugins)) {
    Write-Check "FAIL" "资源目录不存在: $srcPlugins（技能 md 资产应位于此处）"
}
$srcFiles = @()
if (Test-Path $srcPlugins) { $srcFiles = @(Get-ChildItem $srcPlugins -File -Filter *.md | ForEach-Object { $_.Name }) }
$targetExists = Test-Path $targetPlugins
foreach ($p in $packages) {
    $f = $p.FileName
    if ([string]::IsNullOrWhiteSpace($f)) {
        Write-Check "FAIL" "fileName 为空: $($p.Name)"
        continue
    }
    $inSrc = $srcFiles -contains $f
    if ($inSrc) {
        Write-Check "PASS" "fileName 在源码资源中: $($p.Name) -> skills/plugins/$f"
    } else {
        Write-Check "FAIL" "fileName 不在源码资源中（classpath 运行期将缺失）: $($p.Name) -> skills/plugins/$f"
    }
    if ($targetExists) {
        $targetFiles = @(Get-ChildItem $targetPlugins -File -Filter *.md | ForEach-Object { $_.Name })
        if ($targetFiles -contains $f) {
            Write-Check "PASS" "构建产物同步: target/classes/skills/plugins/$f"
        } else {
            Write-Check "WARN" "构建产物未同步（target/classes 缺少 $f，重新构建后生效）：$($p.Name)"
        }
    }
}
# 孤儿资源（声明外的 md 文件）
if ($srcFiles.Count -gt 0) {
    $orphans = @($srcFiles | Where-Object { $declaredFiles -notcontains $_ })
    if ($orphans.Count -gt 0) {
        Write-Check "WARN" "未声明引用的资源文件（疑似改名残留）: $($orphans -join ', ')"
    } else {
        Write-Check "PASS" "无孤儿 md 资源（目录内全部被声明引用）"
    }
}

# ════════════════════ STEP4: requiredTools 命中 ToolRegistry 事实 ════════════════════
Write-Host "STEP4: requiredTools 均命中 ToolRegistry 注册事实（@Tool 注解）"
$javaFiles = @(Get-ChildItem (Join-Path $coreDir "src\main\java") -Recurse -File -Filter *.java | Where-Object { $_.FullName -notmatch '\\test\\' })
$toolNames = New-Object System.Collections.Generic.List[string]
$noNameHits = 0
foreach ($jf in $javaFiles) {
    $src = Read-Utf8File $jf.FullName
    $idx = 0
    while (($idx = $src.IndexOf('@Tool', $idx)) -ge 0) {
        # 排除 "@Tools" / "@ToolParam" 等前缀命中
        $nextCh = if ($idx + 5 -lt $src.Length) { $src[$idx + 5] } else { ' ' }
        if ($nextCh -eq '(') {
            $name = Get-ToolName $src $idx
            if ($null -ne $name -and $name -ne "") {
                $toolNames.Add($name)
                Write-Host "  注册工具: $name（$($jf.Name)）"
            } else {
                $noNameHits++
                Write-Host "  [WARN 前置] @Tool 工具名解析失败（跳过，人工复核）: $($jf.Name) @$idx"
            }
        }
        $idx += 5
    }
}
if ($toolNames.Count -eq 0) {
    Write-Check "FAIL" "未收集到任何 @Tool 注册事实（扫描失效，无法判定 requiredTools 命中，禁止全绿）"
} else {
    Write-Check "PASS" "收集到 $($toolNames.Count) 个注册工具（@Tool 注解单一事实源）: $($toolNames -join ', ')"
    foreach ($p in $packages) {
        if ($p.RequiredTools.Count -eq 0) {
            Write-Check "PASS" "requiredTools 未声明（无工具依赖）: $($p.Name)"
            continue
        }
        $missing = @($p.RequiredTools | Where-Object { $toolNames -notcontains $_ })
        if ($missing.Count -eq 0) {
            Write-Check "PASS" "requiredTools 全部命中注册事实: $($p.Name) -> $($p.RequiredTools -join ', ')"
        } else {
            Write-Check "FAIL" "requiredTools 未命中 ToolRegistry: $($p.Name) -> $($missing -join ', ')"
        }
    }
}
if ($noNameHits -gt 0) { Write-Check "WARN" "$noNameHits 处 @Tool 注解工具名未能静态解析，请人工复核" }

# ════════════════════ 汇总 ════════════════════
Write-Host ""
Write-Host "═══ verify-skill-packages 汇总 ═══"
Write-Host "  PASS: $($global:PassCount)  FAIL: $($global:FailCount)  WARN: $($global:WarnCount)"
if ($global:FailCount -gt 0) {
    Write-Host "校验未通过，存在 $($global:FailCount) 个 FAIL 项" -ForegroundColor Red
    exit 1
}
Write-Host "校验通过（技能包声明与运行资产一致）" -ForegroundColor Green
exit 0