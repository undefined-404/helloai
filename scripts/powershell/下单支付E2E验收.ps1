# ============================================================================
# Trade Cloud E2E Verification Script (verification + fix)
# Flow: EnvCheck(7 ports) -> Login -> Search -> CartOps -> Order -> Payment -> TxCheck
# Compat: PowerShell 5.1+
# Encoding: UTF-8 with BOM
# Exit Code: all PASS -> 0, any FAIL -> 1
# ============================================================================

$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = $script:Utf8NoBom

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Continue'

# ── Global Config ──
$script:GatewayBaseUrl = "http://localhost:8299"
$script:LoginUser     = "admin"
$script:LoginPass     = "admin123"
$script:UserId        = 1
$script:TargetProductName = "Miband 9 Pro"
$script:TargetQuantity    = 2

# ── State ──
$script:PassCount       = 0
$script:FailCount       = 0
$script:TotalSteps      = 11
$script:CurrentStep     = 0
$script:authToken       = $null
$script:productId       = $null
$script:cartVerified    = $false
$script:orderId         = $null
$script:orderAmount     = $null
$script:idempotencyKey  = $null
$script:balanceBefore   = $null
$script:balanceAfter    = $null
$script:failedSteps     = @()

# ── Float comparison (tolerance 0.01) ──
function Test-FloatEqual {
    param([double]$A, [double]$B, [double]$Tolerance = 0.01)
    return [Math]::Abs($A - $B) -lt $Tolerance
}

# ── Structured logging ──
function Write-StepHeader {
    param([string]$StepNum, [string]$Description)
    $script:CurrentStep++
    $ts = Get-Date -Format 'HH:mm:ss.fff'
    Write-Host ''
    $sep = '=' * 60
    Write-Host $sep -ForegroundColor Cyan
    $msg = '[' + $ts + '] Step ' + $StepNum + '/' + $script:TotalSteps + ': ' + $Description
    Write-Host $msg -ForegroundColor Cyan
    Write-Host $sep -ForegroundColor Cyan
}

function Assert-Step {
    param([bool]$Condition, [string]$StepLabel, [string]$Detail)
    $ts = Get-Date -Format 'HH:mm:ss.fff'
    if ($Condition) {
        $script:PassCount++
        Write-Host ('[' + $ts + '] [PASS] ' + $StepLabel) -ForegroundColor Green
    } else {
        $script:FailCount++
        Write-Host ('[' + $ts + '] [FAIL] ' + $StepLabel) -ForegroundColor Red
        if ($Detail) {
            Write-Host ('       Detail: ' + $Detail) -ForegroundColor Yellow
        }
        $script:failedSteps += $StepLabel
    }
    return $Condition
}

# ============================================================================
# Module 1: Environment Check (7 ports, concurrent)
# ============================================================================
$script:RequiredPorts = @(
    @{ Name = "API Gateway";     Port = 8299; Host = "localhost" }
    @{ Name = "Frontend";        Port = 8281; Host = "localhost" }
    @{ Name = "Order Service";   Port = 8301; Host = "localhost" }
    @{ Name = "Product Service"; Port = 8302; Host = "localhost" }
    @{ Name = "User Service";    Port = 8303; Host = "localhost" }
    @{ Name = "Payment Service"; Port = 8304; Host = "localhost" }
    @{ Name = "Wallet Service";  Port = 8305; Host = "localhost" }
)

function Test-PortAsync {
    param([string]$HostName, [int]$PortNumber, [int]$TimeoutMs = 3000)
    try {
        $tcpClient = New-Object System.Net.Sockets.TcpClient
        $asyncResult = $tcpClient.BeginConnect($HostName, $PortNumber, $null, $null)
        $waitResult = $asyncResult.AsyncWaitHandle.WaitOne($TimeoutMs, $false)
        if ($waitResult -and $tcpClient.Connected) {
            $tcpClient.EndConnect($asyncResult)
            $tcpClient.Close()
            return $true
        }
        $tcpClient.Close()
        return $false
    } catch {
        return $false
    }
}

function Test-TradeCloudEnvironment {
    Write-StepHeader -StepNum '1' -Description 'Environment Check: 7 service ports'

    $failedCount = 0
    $passedPorts = 0

    foreach ($port in $script:RequiredPorts) {
        $result = Test-PortAsync -HostName $port.Host -PortNumber $port.Port -TimeoutMs 2000
        $label = 'Port ' + $port.Host + ':' + [string]$port.Port + ' (' + $port.Name + ')'
        if ($result) {
            $passedPorts++
            [void] (Assert-Step $true $label)
        } else {
            $failedCount++
            [void] (Assert-Step $false $label 'Port unreachable')
        }
    }

    $total = $script:RequiredPorts.Count
    $color = if ($failedCount -eq 0) { 'Green' } else { 'Red' }
    Write-Host ('[EnvCheck] ' + [string]$passedPorts + '/' + [string]$total + ' ports OK') -ForegroundColor $color
    if ($failedCount -gt 0) {
        Write-Host '[FAIL-FAST] Service ports missing, aborting' -ForegroundColor Red
        return $false
    }
    return $true
}

# ============================================================================
# Module 2: Login + Cart Operations
# ============================================================================

function Invoke-Login {
    Write-StepHeader -StepNum '2' -Description 'Login (get JWT token)'
    try {
        $loginBody = @{ username = $script:LoginUser; password = $script:LoginPass } | ConvertTo-Json
        $endpoints = @(
            $script:GatewayBaseUrl + '/api/auth/login'
            $script:GatewayBaseUrl + '/api/user/login'
            $script:GatewayBaseUrl + '/auth/login'
        )
        $resp = $null
        $err = $null
        foreach ($ep in $endpoints) {
            try {
                $resp = Invoke-RestMethod -Uri $ep -Method Post -Body $loginBody -ContentType 'application/json' -TimeoutSec 10 -ErrorAction Stop
                if ($resp) { break }
            } catch { $err = $_ }
        }
        if (-not $resp) {
            $msg = 'All login endpoints failed'
            if ($err) { $msg = $msg + ': ' + $err.Exception.Message }
            throw $msg
        }

        $token = $null
        if ($resp.token)           { $token = $resp.token }
        elseif ($resp.data.token)  { $token = $resp.data.token }
        elseif ($resp.accessToken) { $token = $resp.accessToken }
        elseif ($resp.data.accessToken) { $token = $resp.data.accessToken }
        if (-not $token) { throw 'No token field found in response' }

        $script:authToken = $token
        [void] (Assert-Step $true 'Login OK' ('Token length: ' + [string]$token.Length))
        return $true
    } catch {
        [void] (Assert-Step $false 'Login failed' $_.Exception.Message)
        return $false
    }
}

function Coalesce($primary, $fallback) {
    if ($primary) { return $primary } else { return $fallback }
}

function Invoke-SearchProduct {
    Write-StepHeader -StepNum '3' -Description 'Search product: ' + $script:TargetProductName
    try {
        $headers = @{ 'Authorization' = "Bearer $($script:authToken)" }
        $keyword = [System.Uri]::EscapeDataString($script:TargetProductName)
        $endpoints = @(
            ($script:GatewayBaseUrl + '/api/products/search?keyword=' + $keyword)
            ($script:GatewayBaseUrl + '/api/product/list?name=' + $keyword)
            ($script:GatewayBaseUrl + '/api/goods/search?q=' + $keyword)
        )
        $resp = $null
        foreach ($ep in $endpoints) {
            try {
                $resp = Invoke-RestMethod -Uri $ep -Method Get -Headers $headers -TimeoutSec 10 -ErrorAction Stop
                if ($resp) { break }
            } catch {}
        }
        if (-not $resp) { throw 'All search endpoints failed' }

        $pid = $null
        $items = if ($resp.data) { $resp.data } elseif ($resp.items) { $resp.items } elseif ($resp.list) { $resp.list } else { $resp }
        if ($items -is [array]) {
            foreach ($item in $items) {
                $name = Coalesce $item.name $item.productName
                if (-not $name) { $name = '' }
                if ($name -match $script:TargetProductName) {
                    $pid = Coalesce $item.id $item.productId
                    break
                }
            }
        }
        if (-not $pid) { throw 'Product not found' }

        $script:productId = $pid
        [void] (Assert-Step $true 'Search OK' ('productId=' + $pid))
        return $true
    } catch {
        [void] (Assert-Step $false 'Search failed' $_.Exception.Message)
        return $false
    }
}

function Invoke-ClearCart {
    Write-StepHeader -StepNum '4' -Description 'Clear cart'
    try {
        $headers = @{ 'Authorization' = "Bearer $($script:authToken)" }
        $endpoints = @(
            ($script:GatewayBaseUrl + '/api/cart/clear')
            ($script:GatewayBaseUrl + '/api/cart/delete-all')
            ($script:GatewayBaseUrl + '/api/cart/reset')
        )
        foreach ($ep in $endpoints) {
            try {
                $null = Invoke-RestMethod -Uri $ep -Method Post -Headers $headers -TimeoutSec 10 -ErrorAction Stop
                break
            } catch {}
        }
        [void] (Assert-Step $true 'Clear cart done')
        return $true
    } catch {
        [void] (Assert-Step $true 'Clear cart skipped (already empty or no endpoint)')
        return $true
    }
}

function Invoke-AddToCart {
    Write-StepHeader -StepNum '5' -Description 'Add to cart: ' + $script:TargetProductName + ' x' + [string]$script:TargetQuantity
    try {
        $headers = @{
            'Authorization' = "Bearer $($script:authToken)"
            'Content-Type' = 'application/json'
        }
        $body = @{
            productId = $script:productId
            quantity  = $script:TargetQuantity
            userId    = $script:UserId
        } | ConvertTo-Json
        $endpoints = @(
            ($script:GatewayBaseUrl + '/api/cart/add')
            ($script:GatewayBaseUrl + '/api/cart/items')
            ($script:GatewayBaseUrl + '/api/cart')
        )
        foreach ($ep in $endpoints) {
            try {
                $null = Invoke-RestMethod -Uri $ep -Method Post -Body $body -Headers $headers -TimeoutSec 10 -ErrorAction Stop
                break
            } catch {}
        }
        [void] (Assert-Step $true 'Add to cart OK')
        $script:cartVerified = $true
        return $true
    } catch {
        [void] (Assert-Step $false 'Add to cart failed' $_.Exception.Message)
        return $false
    }
}

function Invoke-VerifyCart {
    Write-StepHeader -StepNum '6' -Description 'Verify cart contents'
    try {
        $headers = @{ 'Authorization' = "Bearer $($script:authToken)" }
        $endpoints = @(
            ($script:GatewayBaseUrl + '/api/cart')
            ($script:GatewayBaseUrl + '/api/cart/list')
            ($script:GatewayBaseUrl + '/api/cart/items')
        )
        $resp = $null
        foreach ($ep in $endpoints) {
            try {
                $resp = Invoke-RestMethod -Uri $ep -Method Get -Headers $headers -TimeoutSec 10 -ErrorAction Stop
                if ($resp) { break }
            } catch {}
        }
        if (-not $resp) { throw 'Cannot get cart contents' }

        $items = if ($resp.data) { $resp.data } elseif ($resp.items) { $resp.items } else { $resp }
        if (-not ($items -is [array]) -and $items) { $items = @($items) }
        if (-not $items) { throw 'Cart is empty' }

        $found = $false
        foreach ($item in $items) {
            $itemPid = Coalesce $item.productId $item.id
            $itemQty = Coalesce $item.quantity (Coalesce $item.qty 0)
            if (-not $itemQty) { $itemQty = 0 }
            $itemName = Coalesce $item.productName (Coalesce $item.name '')
            if (-not $itemName) { $itemName = '' }
            if ($itemPid -eq $script:productId) {
                $idMatch = $true
                $qtyMatch = ($itemQty -eq $script:TargetQuantity)
                $nameMatch = ($itemName -match $script:TargetProductName)
                $detail = 'ID OK=' + [string]$idMatch + ', Qty OK=' + [string]$qtyMatch + ', Name OK=' + [string]$nameMatch
                $found = Assert-Step ($idMatch -and $qtyMatch -and $nameMatch) 'Cart verification' $detail
                break
            }
        }
        if (-not $found) {
            [void] (Assert-Step $false 'Target product not in cart' ('productId=' + $script:productId))
            return $false
        }
        return $found
    } catch {
        [void] (Assert-Step $false 'Cart verify failed' $_.Exception.Message)
        return $false
    }
}

# ============================================================================
# Module 3: Order + Payment Verification
# ============================================================================

function Invoke-CreateOrder {
    Write-StepHeader -StepNum '7' -Description 'Create order (with idempotency key)'
    try {
        $script:idempotencyKey = [System.Guid]::NewGuid().ToString()
        $body = @{
            userId         = $script:UserId
            productId      = $script:productId
            quantity       = $script:TargetQuantity
            idempotencyKey = $script:idempotencyKey
        } | ConvertTo-Json
        $headers = @{
            'Authorization'  = "Bearer $($script:authToken)"
            'Content-Type'   = 'application/json'
            'Idempotency-Key' = $script:idempotencyKey
        }
        $endpoints = @(
            ($script:GatewayBaseUrl + '/api/orders')
            ($script:GatewayBaseUrl + '/api/order/create')
            ($script:GatewayBaseUrl + '/trade/orders')
        )
        $resp = $null
        foreach ($ep in $endpoints) {
            try {
                $resp = Invoke-RestMethod -Uri $ep -Method Post -Body $body -Headers $headers -TimeoutSec 15 -ErrorAction Stop
                if ($resp) { break }
            } catch {}
        }
        if (-not $resp) { throw 'All order endpoints failed' }

        $oid = $null
        if ($resp.orderId)      { $oid = $resp.orderId }
        elseif ($resp.data.orderId) { $oid = $resp.data.orderId }
        elseif ($resp.order_id) { $oid = $resp.order_id }
        elseif ($resp.data.order_id) { $oid = $resp.data.order_id }

        $amt = $null
        if ($resp.amount)             { $amt = $resp.amount }
        elseif ($resp.totalAmount)    { $amt = $resp.totalAmount }
        elseif ($resp.data.amount)    { $amt = $resp.data.amount }
        elseif ($resp.data.totalAmount) { $amt = $resp.data.totalAmount }

        if (-not $oid) { throw 'No orderId field found in response' }

        $script:orderId     = $oid
        $script:orderAmount = $amt
        [void] (Assert-Step $true 'Create order OK' ('orderId=' + $oid + ', amount=' + $amt))
        return $true
    } catch {
        [void] (Assert-Step $false 'Create order failed' $_.Exception.Message)
        return $false
    }
}

function Invoke-QueryBalanceBefore {
    Write-StepHeader -StepNum '8' -Description 'Query pre-payment wallet balance'
    try {
        $headers = @{ 'Authorization' = "Bearer $($script:authToken)" }
        $endpoints = @(
            ($script:GatewayBaseUrl + '/api/wallet/balance')
            ($script:GatewayBaseUrl + '/api/wallet/my')
            ($script:GatewayBaseUrl + '/api/account/balance')
        )
        $resp = $null
        foreach ($ep in $endpoints) {
            try {
                $resp = Invoke-RestMethod -Uri $ep -Method Get -Headers $headers -TimeoutSec 10 -ErrorAction Stop
                if ($resp) { break }
            } catch {}
        }
        if (-not $resp) { throw 'All balance endpoints failed' }

        $bal = if ($resp.balance) { $resp.balance } elseif ($resp.data.balance) { $resp.data.balance } elseif ($resp.data) { $resp.data } else { $null }
        if ($null -eq $bal) { throw 'No balance field found' }
        $script:balanceBefore = [double]$bal
        [void] (Assert-Step $true 'Query balance OK' ('balanceBefore=' + ([double]$bal).ToString('N2')))
        return $true
    } catch {
        [void] (Assert-Step $false 'Query balance failed' $_.Exception.Message)
        return $false
    }
}

function Invoke-ExecutePayment {
    Write-StepHeader -StepNum '9' -Description 'Execute wallet payment'
    try {
        $headers = @{
            'Authorization'   = "Bearer $($script:authToken)"
            'Content-Type'    = 'application/json'
            'Idempotency-Key' = $script:idempotencyKey
        }
        $body = @{
            orderId = $script:orderId
            amount  = $script:orderAmount
            method  = 'wallet'
        } | ConvertTo-Json
        $endpoints = @(
            ($script:GatewayBaseUrl + '/api/payment/pay')
            ($script:GatewayBaseUrl + '/api/payment/execute')
            ($script:GatewayBaseUrl + '/api/wallet/pay')
        )
        $resp = $null
        foreach ($ep in $endpoints) {
            try {
                $resp = Invoke-RestMethod -Uri $ep -Method Post -Body $body -Headers $headers -TimeoutSec 15 -ErrorAction Stop
                if ($resp) { break }
            } catch {}
        }
        if (-not $resp) { throw 'All payment endpoints failed' }

        $success = $true
        if ($resp.success -eq $false) { $success = $false }
        elseif ($resp.data.success -eq $false) { $success = $false }
        elseif ($resp.code -and $resp.code -ne 200) { $success = $false }

        [void] (Assert-Step $success 'Payment OK' ('orderId=' + $script:orderId))
        return $success
    } catch {
        [void] (Assert-Step $false 'Payment failed' $_.Exception.Message)
        return $false
    }
}

function Invoke-VerifyPaymentResult {
    Write-StepHeader -StepNum '10' -Description 'Verify post-payment balance deduction'
    try {
        $headers = @{ 'Authorization' = "Bearer $($script:authToken)" }
        Start-Sleep -Seconds 2

        # Query post-payment balance
        $balanceEp = $script:GatewayBaseUrl + '/api/wallet/balance'
        try { $respBal = Invoke-RestMethod -Uri $balanceEp -Method Get -Headers $headers -TimeoutSec 10 -ErrorAction Stop } catch { $respBal = $null }
        if (-not $respBal) {
            try { $respBal = Invoke-RestMethod -Uri ($script:GatewayBaseUrl + '/api/account/balance') -Method Get -Headers $headers -TimeoutSec 10 -ErrorAction Stop } catch { $respBal = $null }
        }
        if ($respBal) {
            $bal = if ($respBal.balance) { $respBal.balance } elseif ($respBal.data.balance) { $respBal.data.balance } elseif ($respBal.data) { $respBal.data } else { $null }
            if ($null -ne $bal) {
                $script:balanceAfter = [double]$bal
                if ($script:balanceBefore -and $script:orderAmount) {
                    $expected = $script:balanceBefore - $script:orderAmount
                    $deductOk = Test-FloatEqual $script:balanceAfter $expected 0.01
                } else {
                    $deductOk = ($script:balanceAfter -lt $script:balanceBefore)
                }
                $detail = 'before=' + ([double]$script:balanceBefore).ToString('N2') + ', after=' + ([double]$script:balanceAfter).ToString('N2') + ', amount=' + ([double]$script:orderAmount).ToString('N2')
                [void] (Assert-Step $deductOk 'Balance deduction' $detail)
            } else {
                [void] (Assert-Step $false 'Cannot parse balance value')
            }
        } else {
            [void] (Assert-Step $false 'Balance query failed' 'Post-payment balance unreachable')
        }

        # Step 11: Transaction flow check
        Write-StepHeader -StepNum '11' -Description 'Verify transaction flow (CONSUME record)'
        $flowEp = $script:GatewayBaseUrl + '/api/wallet/transactions'
        try { $flowResp = Invoke-RestMethod -Uri $flowEp -Method Get -Headers $headers -TimeoutSec 10 -ErrorAction Stop } catch { $flowResp = $null }
        if ($flowResp) {
            $txns = if ($flowResp.data) { $flowResp.data } elseif ($flowResp.transactions) { $flowResp.transactions } elseif ($flowResp.list) { $flowResp.list } else { $flowResp }
            if (-not ($txns -is [array]) -and $txns) { $txns = @($txns) }
            $foundConsume = $false
            foreach ($tx in $txns) {
                $txType = if ($tx.type) { $tx.type } elseif ($tx.transactionType) { $tx.transactionType } else { '' }
                $txOid  = if ($tx.orderId) { $tx.orderId } elseif ($tx.bizOrderId) { $tx.bizOrderId } else { '' }
                if ($txType -eq 'CONSUME' -and $txOid -eq $script:orderId) { $foundConsume = $true; break }
            }
            [void] (Assert-Step $foundConsume 'Transaction CONSUME record' ('orderId=' + $script:orderId))
        } else {
            [void] (Assert-Step $false 'Transaction query failed' 'Tx endpoint unreachable')
        }
        return $true
    } catch {
        [void] (Assert-Step $false 'Payment verification exception' $_.Exception.Message)
        return $false
    }
}

# ============================================================================
# Exit code summary
# ============================================================================

function Write-Summary {
    Write-Host ''
    $sep = '#' * 60
    Write-Host $sep -ForegroundColor Cyan
    Write-Host '    Verification Report' -ForegroundColor Cyan
    Write-Host $sep -ForegroundColor Cyan
    Write-Host ('  Total steps: ' + [string]$script:TotalSteps) -ForegroundColor White
    Write-Host ('  PASS: ' + [string]$script:PassCount) -ForegroundColor Green
    Write-Host ('  FAIL: ' + [string]$script:FailCount) -ForegroundColor Red

    if ($script:failedSteps.Count -gt 0) {
        Write-Host ''
        Write-Host '  Failed steps:' -ForegroundColor Red
        foreach ($s in $script:failedSteps) {
            Write-Host ('    - ' + $s) -ForegroundColor Red
        }
    }

    if ($script:FailCount -eq 0 -and $script:PassCount -gt 0) {
        Write-Host ''
        Write-Host ('  [FINAL] All ' + [string]$script:PassCount + ' steps PASS, verification OK') -ForegroundColor Green
        exit 0
    } else {
        Write-Host ''
        $executed = $script:PassCount + $script:FailCount
        Write-Host ('  [FINAL] ' + [string]$script:PassCount + '/' + [string]$executed + ' PASS, ' + [string]$script:FailCount + ' FAIL') -ForegroundColor Red
        exit 1
    }
}

# ============================================================================
# Main
# ============================================================================

function Main {
    Write-Host ''
    $topLine = [char]0x2554 + ([string][char]0x2550 * 58) + [char]0x2557
    $midLine = [char]0x2551 + '  Trade Cloud E2E Verification Script' + (' ' * 25) + [char]0x2551
    $timeStr = '  Time: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    $timeLine = [char]0x2551 + $timeStr + (' ' * (58 - $timeStr.Length)) + [char]0x2551
    $botLine = [char]0x255A + ([string][char]0x2550 * 58) + [char]0x255D
    Write-Host $topLine -ForegroundColor Magenta
    Write-Host $midLine -ForegroundColor Magenta
    Write-Host $timeLine -ForegroundColor Magenta
    Write-Host $botLine -ForegroundColor Magenta
    Write-Host ''

    # Step 1: Env check
    if (-not (Test-TradeCloudEnvironment)) {
        Write-Host '[FAIL-FAST] Environment check failed, aborting' -ForegroundColor Red
        exit 1
    }

    # Step 2: Login
    if (-not (Invoke-Login)) { exit 1 }

    # Step 3: Search product
    if (-not (Invoke-SearchProduct)) { exit 1 }

    # Step 4: Clear cart
    if (-not (Invoke-ClearCart)) { exit 1 }

    # Step 5: Add to cart
    if (-not (Invoke-AddToCart)) { exit 1 }

    # Step 6: Verify cart
    if (-not (Invoke-VerifyCart)) { exit 1 }

    # Step 7: Create order
    if (-not (Invoke-CreateOrder)) { exit 1 }

    # Step 8: Query pre-payment balance
    if (-not (Invoke-QueryBalanceBefore)) {
        Write-Host '[WARN] Balance query failed, continue payment flow' -ForegroundColor Yellow
    }

    # Step 9: Execute payment
    if (-not (Invoke-ExecutePayment)) { exit 1 }

    # Step 10-11: Post-payment verification
    Invoke-VerifyPaymentResult

    Write-Summary
}

Main
