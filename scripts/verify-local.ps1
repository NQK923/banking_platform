param(
    [switch]$SkipBuild,
    [switch]$KeepData,
    [switch]$KeepRunning,
    [int]$GatewayPort = 8080
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
$GatewayUrl = "http://127.0.0.1:$GatewayPort"
$env:BANKING_GATEWAY_PORT = $GatewayPort.ToString()
$HealthChecks = @(
    @{ Name = "api-gateway"; Url = "$GatewayUrl/actuator/health" }
)
function Test-HealthUrl {
    param(
        [string]$Url,
        [hashtable]$Headers
    )
    try {
        $Response = Invoke-RestMethod -Uri $Url -Headers $Headers -TimeoutSec 2
        return $Response.status -eq "UP"
    } catch {
        return $false
    }
}

function Test-AccountRoute {
    try {
        $Body = @{
            identifier = "admin@local.test"
            password = $env:BANKING_SEED_ADMIN_PASSWORD
        } | ConvertTo-Json
        $Response = Invoke-RestMethod `
            -Method POST `
            -Uri "$GatewayUrl/api/auth/login" `
            -ContentType "application/json" `
            -Body $Body `
            -TimeoutSec 3
        return -not [string]::IsNullOrWhiteSpace($Response.accessToken)
    } catch {
        return $false
    }
}

Push-Location $Root
try {
    if (-not $env:BANKING_POSTGRES_PASSWORD) {
        $env:BANKING_POSTGRES_PASSWORD = [guid]::NewGuid().ToString("N")
    }
    if (-not $env:BANKING_JWT_SECRET) {
        $env:BANKING_JWT_SECRET = [guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N")
    }
    if (-not $env:INTERNAL_SERVICE_TOKEN) {
        $env:INTERNAL_SERVICE_TOKEN = [guid]::NewGuid().ToString("N")
    }
    if (-not $env:BANKING_SEED_ADMIN_PASSWORD) {
        $env:BANKING_SEED_ADMIN_PASSWORD = "Local-" + [guid]::NewGuid().ToString("N")
    }
    if (-not $env:BANKING_SEED_ADMIN_PIN) {
        $env:BANKING_SEED_ADMIN_PIN = (Get-Random -Minimum 100000 -Maximum 999999).ToString()
    }

    if (-not $KeepData) {
        docker compose down -v --remove-orphans
        if ($LASTEXITCODE -ne 0) {
            throw "Docker Compose reset failed with exit code $LASTEXITCODE"
        }
    }

    if (-not $SkipBuild) {
        & .\gradlew.bat clean build
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE"
        }
    }

    docker compose up -d
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose startup failed with exit code $LASTEXITCODE"
    }

    foreach ($Check in $HealthChecks) {
        $Healthy = $false
        for ($i = 0; $i -lt 60; $i++) {
            $Headers = @{}
            if ($env:INTERNAL_SERVICE_TOKEN) {
                $Headers["X-Service-Token"] = $env:INTERNAL_SERVICE_TOKEN
            }
            if (Test-HealthUrl -Url $Check.Url -Headers $Headers) {
                $Healthy = $true
                break
            }
            Start-Sleep -Seconds 1
        }
        if (-not $Healthy) {
            docker compose ps
            docker compose logs --tail=160 $Check.Name
            throw "$($Check.Name) did not become healthy"
        }
    }

    $AccountRouteReady = $false
    for ($i = 0; $i -lt 60; $i++) {
        if (Test-AccountRoute) {
            $AccountRouteReady = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $AccountRouteReady) {
        docker compose ps
        docker compose logs --tail=160 account-service api-gateway
        throw "Account route did not become ready through the Gateway"
    }

    $PowerShellExe = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
    if (-not $PowerShellExe) {
        $PowerShellExe = (Get-Command powershell -ErrorAction Stop).Source
    }
    & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root "scripts\smoke.ps1") -BaseUrl $GatewayUrl
    if ($LASTEXITCODE -ne 0) {
        throw "Backend smoke failed with exit code $LASTEXITCODE"
    }
} finally {
    if (-not $KeepRunning) {
        docker compose down
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Docker Compose cleanup failed with exit code $LASTEXITCODE"
        }
    }
    Pop-Location
}
