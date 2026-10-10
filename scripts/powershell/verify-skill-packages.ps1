# ============================================================
# helloai 技能包一致性校验脚本（REF-1.1 / REF-1.2）
# 用途：校验 classpath `skills/plugins/*.md` 的 YAML frontmatter（技能元数据唯一事实源）
#       与运行资产、工具注册事实的静态一致性：
#       A. 目录内每个 .md 均可解析（有开/闭围栏 + 必填 name/version/description）；
#          已构建时同步核对 target/classes 拷贝
#       B. requiredTools 均命中 ToolRegistry 注册事实（@Tool 注解单一事实源，
#          显式 name 或方法名两种形态都收集）
#       C. version 格式合法（三段式数字，如 1.0.0；带/不带引号均可）
#       D. name 必须等于文件名去 .md（由此同时保证 name 与 fileName 唯一）
#       E. frontmatter 结构约束：禁止 fileName 键 / 禁止未知顶层键 / 围栏之间不得出现整行 ---
#       F. 测试资源不得遮蔽 main 技能目录（src/test/resources/skills 下不得有同名 md）
#
# 【2026-10-09 变更说明】元数据来源由 Java `KNOWN_SPECS` 硬编码改为 md frontmatter（REF-1.1b），
#   故原「正则解析 Java 源码文本」的机器（Split-TopLevelArgs / Parse-ListOf）整体作废。
#   两处断言的等价替换（非删减）：
#     · 旧 A「声明的 fileName 必须存在于 classpath」——扫描模型下「文件名即声明源」，该断言
#       前提由构造消失；替换为等价的「目录内每个 md 都是合法技能包」。
#     · 旧 D 的「未引用资源 WARN」——目录内每个 md 都是包，孤儿恒不存在；替换为更强的
#       「name == 文件名去 .md」。
#   B 的 @Tool 收集逻辑（Get-ToolName 及调用点）与旧脚本一字不差。
# Ref:  doc/design/Planner_Capability_Awareness.md §6-D5-2；plan/HelloAI 借鉴落地实施计划.md REF-1.1/1.2
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

# ════════════════════ frontmatter 解析（最小子集，与 SkillFrontMatter/SkillPackageParser 同规则） ════════════════════
$script:KnownKeys = @('name', 'version', 'description', 'requiredTools',
                      'dependencies', 'inputSchema', 'outputSchema', 'validationRules')

# 切分围栏：返回 @{ Ok; Yaml; Body } 或 @{ Ok=$false; Error }
function Get-FrontMatter([string]$Text) {
    $content = $Text -replace "`r`n", "`n"
    if ($content.Length -gt 0 -and [int][char]$content[0] -eq 0xFEFF) { $content = $content.Substring(1) }
    $lines = $content -split "`n"
    if ($lines.Count -eq 0 -or $lines[0].Trim() -ne '---') {
        return @{ Ok = $false; Error = 'frontmatter 缺失：文件未以 --- 开头' }
    }
    $close = -1
    for ($i = 1; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Trim() -eq '---') { $close = $i; break }
    }
    if ($close -lt 0) { return @{ Ok = $false; Error = 'frontmatter 未闭合：找不到结束围栏 ---' } }
    if ($close -le 1) { return @{ Ok = $false; Error = 'frontmatter 为空（--- 围栏之间无内容）' } }
    $yaml = ($lines[1..($close - 1)] -join "`n")
    $body = ''
    if ($close + 1 -le $lines.Count - 1) { $body = ($lines[($close + 1)..($lines.Count - 1)] -join "`n") }
    return @{ Ok = $true; Yaml = $yaml; Body = $body }
}

# 去引号（单/双引号成对则剥掉）
function Remove-Quotes([string]$V) {
    if ($V.Length -ge 2) {
        if (($V.StartsWith('"') -and $V.EndsWith('"')) -or ($V.StartsWith("'") -and $V.EndsWith("'"))) {
            return $V.Substring(1, $V.Length - 2)
        }
    }
    return $V
}

# 解析 frontmatter 子集：标量 / 空 flow([] {}) / 块序列 / 块映射（映射结构存在即可，不解析内部）
# 返回 @{ Ok; Values; Keys } 或 @{ Ok=$false; Error }
function ConvertFrom-SkillFrontMatter([string]$Yaml) {
    $values = @{}
    $keys = New-Object System.Collections.Generic.List[string]
    $lines = $Yaml -split "`n"
    $i = 0
    while ($i -lt $lines.Count) {
        $raw = $lines[$i]
        $trimmed = $raw.Trim()
        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { $i++; continue }
        $m = [regex]::Match($raw, '^([A-Za-z][A-Za-z0-9_]*)\s*:\s*(.*)$')
        if (-not $m.Success) { return @{ Ok = $false; Error = "无法解析的 frontmatter 行：$trimmed" } }
        $key = $m.Groups[1].Value
        $rest = $m.Groups[2].Value.Trim()
        $keys.Add($key) | Out-Null
        if ($rest -eq '[]') { $values[$key] = @() }
        elseif ($rest -eq '{}') { $values[$key] = @{} }
        elseif ($rest -ne '') { $values[$key] = (Remove-Quotes $rest) }
        else {
            # 块：收集后续缩进行
            $block = New-Object System.Collections.Generic.List[string]
            $j = $i + 1
            while ($j -lt $lines.Count) {
                $l = $lines[$j]
                if ($l.Trim() -eq '') { $j++; continue }
                if ($l -match '^\s') { $block.Add($l); $j++ } else { break }
            }
            if ($block.Count -eq 0) {
                $values[$key] = $null
            } else {
                $seq = @()
                $isSeq = $true
                foreach ($b in $block) {
                    $bm = [regex]::Match($b, '^\s*-\s*(.+)$')
                    if ($bm.Success) { $seq += (Remove-Quotes $bm.Groups[1].Value.Trim()) }
                    else { $isSeq = $false; break }
                }
                if ($isSeq) { $values[$key] = $seq } else { $values[$key] = $null }
            }
            $i = $j - 1
        }
        $i++
    }
    return @{ Ok = $true; Values = $values; Keys = $keys }
}

# ════════════════════ STEP1: 定位技能目录 ════════════════════
Write-Host "STEP1: 定位技能包目录"
$coreDir = Join-Path $RepoRoot "helloai-core"
$srcPlugins = Join-Path $coreDir "src\main\resources\skills\plugins"
if (-not (Test-Path $srcPlugins)) {
    Write-Check "FAIL" "资源目录不存在: $srcPlugins（技能 md 资产应位于此处）"
} else {
    Write-Check "PASS" "技能包目录存在: src/main/resources/skills/plugins"
}
$classesPlugins = Join-Path $coreDir "target\classes\skills\plugins"

# ════════════════════ STEP2: 解析每个 md 的 frontmatter ════════════════════
Write-Host "STEP2: 解析 frontmatter（技能元数据唯一事实源）"
$mdFiles = @(Get-ChildItem $srcPlugins -File -Filter *.md | Sort-Object Name)
if ($mdFiles.Count -eq 0) {
    Write-Check "FAIL" "技能目录内没有任何 .md（解析失效，禁止全绿）"
}
$packages = @()
foreach ($f in $mdFiles) {
    $text = Read-Utf8File $f.FullName
    $fm = Get-FrontMatter $text
    if (-not $fm.Ok) {
        Write-Check "FAIL" "$($f.Name)：$($fm.Error)"
        continue
    }
    $parsed = ConvertFrom-SkillFrontMatter $fm.Yaml
    if (-not $parsed.Ok) {
        Write-Check "FAIL" "$($f.Name)：$($parsed.Error)"
        continue
    }
    $pkg = @{
        FileName  = $f.Name
        Stem      = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
        Keys      = $parsed.Keys
        Values    = $parsed.Values
        Name      = [string]$parsed.Values['name']
        Version   = [string]$parsed.Values['version']
        Desc      = [string]$parsed.Values['description']
        RequiredTools = @()
    }
    if ($null -ne $parsed.Values['requiredTools'] -and $parsed.Values['requiredTools'] -is [array]) {
        $pkg.RequiredTools = @($parsed.Values['requiredTools'])
    }
    $packages += $pkg
}
if ($packages.Count -eq 0) {
    Write-Check "FAIL" "未解析到任何技能包（禁止全绿）"
} else {
    Write-Check "PASS" "解析到 $($packages.Count) 个技能包"
}

# ════════════════════ STEP3: 断言 A —— 每个 md 均为合法技能包 ════════════════════
Write-Host "STEP3: 每个 .md 均可解析（含必填三字段）"
foreach ($p in $packages) {
    if ([string]::IsNullOrWhiteSpace($p.Name)) { Write-Check "FAIL" "$($p.FileName)：缺少必填字段 name" }
    if ([string]::IsNullOrWhiteSpace($p.Version)) { Write-Check "FAIL" "$($p.FileName)：缺少必填字段 version" }
    if ([string]::IsNullOrWhiteSpace($p.Desc)) { Write-Check "FAIL" "$($p.FileName)：缺少必填字段 description" }
}
if ($packages.Count -gt 0) { Write-Check "PASS" "全部 $($packages.Count) 个技能包均含 name / version / description" }

# 已构建时同步核对 target/classes 拷贝
if (Test-Path $classesPlugins) {
    foreach ($p in $packages) {
        $built = Join-Path $classesPlugins $p.FileName
        if (Test-Path $built) {
            Write-Check "PASS" "构建产物同步: target/classes/skills/plugins/$($p.FileName)"
        } else {
            Write-Check "WARN" "构建产物未同步（target/classes 缺少 $($p.FileName)，重新构建后生效）"
        }
    }
} else {
    Write-Check "WARN" "未构建（target/classes 不存在），跳过产物同步核对"
}

# ════════════════════ STEP4: 断言 E —— frontmatter 结构约束 ════════════════════
Write-Host "STEP4: frontmatter 结构约束（禁止 fileName / 未知顶层键 / 内嵌围栏）"
foreach ($p in $packages) {
    if ($p.Keys -contains 'fileName') {
        Write-Check "FAIL" "$($p.FileName)：frontmatter 不得声明 fileName（由目录扫描推导）"
    } else {
        Write-Check "PASS" "$($p.FileName)：未声明 fileName（正确）"
    }
    $unknown = @($p.Keys | Where-Object { $script:KnownKeys -notcontains $_ })
    if ($unknown.Count -gt 0) {
        Write-Check "FAIL" "$($p.FileName)：未知顶层字段（拼写错误？）: $($unknown -join ', ')"
    } else {
        Write-Check "PASS" "$($p.FileName)：顶层字段全部已知"
    }
    # 围栏之间不得出现整行 ---（否则说明多写了 YAML 文档分隔符，围栏定位不可信）
    $text = Read-Utf8File (Join-Path $srcPlugins $p.FileName)
    $fm = Get-FrontMatter $text
    $innerFence = $false
    if ($fm.Ok) {
        foreach ($l in ($fm.Yaml -split "`n")) { if ($l.Trim() -eq '---') { $innerFence = $true; break } }
    }
    if ($innerFence) {
        Write-Check "FAIL" "$($p.FileName)：frontmatter 内出现整行 ---（会破坏围栏匹配）"
    } else {
        Write-Check "PASS" "$($p.FileName)：frontmatter 内无整行 ---"
    }
}

# ════════════════════ STEP5: 断言 C —— version 三段式 ════════════════════
Write-Host "STEP5: version 格式（三段式数字）"
foreach ($p in $packages) {
    if ([string]::IsNullOrWhiteSpace($p.Version)) { continue }
    if ($p.Version -match '^[0-9]+\.[0-9]+\.[0-9]+$') {
        Write-Check "PASS" "版本格式合法: $($p.Name) v$($p.Version)"
    } else {
        Write-Check "FAIL" "版本格式非法（期望三段式数字 1.0.0）: $($p.Name) v='$($p.Version)'"
    }
}

# ════════════════════ STEP6: 断言 D —— name == 文件名去 .md，且唯一 ════════════════════
Write-Host "STEP6: name 与文件名一致（由此保证 name / fileName 唯一）"
foreach ($p in $packages) {
    if ([string]::IsNullOrWhiteSpace($p.Name)) { continue }
    if ($p.Name -eq $p.Stem) {
        Write-Check "PASS" "name 与文件名一致: $($p.Name)"
    } else {
        Write-Check "FAIL" "name 与文件名不一致（要求 name == 文件名去 .md）: name='$($p.Name)' 文件名='$($p.FileName)'"
    }
}
# 注：$packages 元素为 Hashtable，Group-Object -Property Name 不做键查找，
# 会把所有项归入一个 Name 为空的组（恒 Count>1）→ 恒误报"重复"。必须用脚本块分组。
$dupName = @($packages | Where-Object { -not [string]::IsNullOrWhiteSpace($_.Name) } | Group-Object { $_.Name } | Where-Object { $_.Count -gt 1 })
if ($dupName.Count -gt 0) {
    Write-Check "FAIL" "name 重复: $(($dupName | ForEach-Object { $_.Name }) -join ', ')"
} else {
    Write-Check "PASS" "name 全部唯一"
}

# ════════════════════ STEP7: 断言 B —— requiredTools 命中 ToolRegistry 事实 ════════════════════
Write-Host "STEP7: requiredTools 均命中 ToolRegistry 注册事实（@Tool 注解）"
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
    if ($noNameHits -gt 0) { Write-Check "WARN" "$noNameHits 处 @Tool 注解工具名未能静态解析，请人工复核" }
}

# ════════════════════ STEP8: 断言 F —— 测试资源不得遮蔽 main 技能目录 ════════════════════
Write-Host "STEP8: 测试资源遮蔽守卫"
$testSkills = Join-Path $coreDir "src\test\resources\skills"
if (Test-Path $testSkills) {
    $shadow = @()
    foreach ($d in @(Get-ChildItem $testSkills -Recurse -File -Filter *.md -ErrorAction SilentlyContinue)) {
        $rel = $d.FullName.Substring($testSkills.Length).TrimStart('\', '/')
        if ($rel -like 'plugins\*' -or $rel -like 'plugins/*') { $shadow += $rel }
    }
    if ($shadow.Count -gt 0) {
        Write-Check "FAIL" "测试资源遮蔽 main 技能目录（测试 classpath 优先，会让单测读到替身）: $($shadow -join ', ')"
    } else {
        Write-Check "PASS" "无测试资源遮蔽（src/test/resources/skills 下无 plugins/ 同名目录）"
    }
} else {
    Write-Check "PASS" "无测试技能资源目录（无需遮蔽检查）"
}

# ════════════════════ 汇总 ════════════════════
Write-Host ""
Write-Host "═══ verify-skill-packages 汇总 ═══"
Write-Host "  PASS: $($global:PassCount)  FAIL: $($global:FailCount)  WARN: $($global:WarnCount)"
if ($global:FailCount -gt 0) {
    Write-Host "校验未通过，存在 $($global:FailCount) 个 FAIL 项" -ForegroundColor Red
    exit 1
}
Write-Host "校验通过（技能包 frontmatter 与运行资产一致）" -ForegroundColor Green
exit 0
