param(
    [string]$BaseUrl = "http://localhost:8081"
)

$ErrorActionPreference = "Stop"
$AdminPassword = $env:BANKING_SEED_ADMIN_PASSWORD
if (-not $AdminPassword) {
    throw "BANKING_SEED_ADMIN_PASSWORD must be set for smoke tests"
}

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [string]$IdempotencyKey = $null
    )
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    if ($IdempotencyKey) { $headers["Idempotency-Key"] = $IdempotencyKey }
    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $headers
        ContentType = "application/json"
    }
    if ($null -ne $Body) {
        $params.Body = ($Body | ConvertTo-Json -Depth 20)
    }
    Invoke-RestMethod @params
}

function Wait-Tx {
    param(
        [string]$TxId,
        [string]$Token,
        [string]$Expected
    )
    for ($i = 0; $i -lt 30; $i++) {
        $tx = Invoke-Json -Method GET -Path "/api/transactions/$TxId" -Token $Token
        if ($tx.status -eq $Expected) {
            return $tx
        }
        Start-Sleep -Milliseconds 150
    }
    throw "Transaction $TxId did not reach $Expected"
}

$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$aliceEmail = "alice-$suffix@example.test"
$bobEmail = "bob-$suffix@example.test"
$creditFailEmail = "fail-$suffix@example.test"

$alice = Invoke-Json POST "/api/auth/register" @{
    email = $aliceEmail
    phone = "+8401$suffix"
    password = "Password123!"
    pin = "123456"
    currency = "VND"
}
$bob = Invoke-Json POST "/api/auth/register" @{
    email = $bobEmail
    phone = "+8402$suffix"
    password = "Password123!"
    pin = "123456"
    currency = "VND"
}
$creditFailReceiver = Invoke-Json POST "/api/auth/register" @{
    email = $creditFailEmail
    phone = "+8403$suffix"
    password = "Password123!"
    pin = "123456"
    currency = "VND"
}
$admin = Invoke-Json POST "/api/auth/login" @{
    identifier = "admin@local.test"
    password = $AdminPassword
}

Invoke-Json POST "/api/accounts/$($alice.accountId)/deposit" @{ amount = "1000" } -Token $admin.accessToken | Out-Null
$balance = Invoke-Json GET "/api/accounts/$($alice.accountId)/balance" -Token $alice.accessToken
if ($balance.balance -ne "1000") { throw "Expected Alice balance 1000, got $($balance.balance)" }

$happy = Invoke-Json POST "/api/transactions/transfer" @{
    recipientEmail = $bobEmail
    amount = "250"
    idempotencyKey = [Guid]::NewGuid().ToString()
    pin = "123456"
} -Token $alice.accessToken -IdempotencyKey ([Guid]::NewGuid().ToString())
Wait-Tx $happy.id $alice.accessToken "COMPLETED" | Out-Null

$bobBalance = Invoke-Json GET "/api/accounts/$($bob.accountId)/balance" -Token $bob.accessToken
if ($bobBalance.balance -ne "250") { throw "Expected Bob balance 250, got $($bobBalance.balance)" }

$insufficient = Invoke-Json POST "/api/transactions/transfer" @{
    recipientEmail = $bobEmail
    amount = "999999"
    idempotencyKey = [Guid]::NewGuid().ToString()
    pin = "123456"
} -Token $alice.accessToken -IdempotencyKey ([Guid]::NewGuid().ToString())
Wait-Tx $insufficient.id $alice.accessToken "FAILED" | Out-Null

$creditFail = Invoke-Json POST "/api/transactions/transfer" @{
    recipientEmail = $creditFailEmail
    amount = "300"
    idempotencyKey = [Guid]::NewGuid().ToString()
    pin = "123456"
} -Token $alice.accessToken -IdempotencyKey ([Guid]::NewGuid().ToString())
Invoke-Json POST "/api/admin/accounts/$($creditFailReceiver.accountId)/suspend" @{} -Token $admin.accessToken | Out-Null
Wait-Tx $creditFail.id $alice.accessToken "FAILED" | Out-Null

$aliceAfterRefund = Invoke-Json GET "/api/accounts/$($alice.accountId)/balance" -Token $alice.accessToken
if ($aliceAfterRefund.balance -ne "750") { throw "Expected Alice refunded balance 750, got $($aliceAfterRefund.balance)" }

$audit = Invoke-Json GET "/api/admin/audit" -Token $admin.accessToken
$auditText = $audit | ConvertTo-Json -Depth 20
if ($auditText -notmatch "MoneyDebitReversed" -or $auditText -notmatch "AccountSuspended") {
    throw "Expected compensation and suspend events in audit"
}

$report = Invoke-Json POST "/api/reconciliation/run" @{} -Token $admin.accessToken
if (-not $report.zeroDrift) { throw "Expected zero drift reconciliation" }

Write-Host "Smoke passed: register/login, deposit, balance, transfer, sad paths, admin audit, reconciliation"
