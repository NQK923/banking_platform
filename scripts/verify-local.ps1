param(
    [switch]$SkipBuild,
    [switch]$KeepData,
    [switch]$KeepRunning
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
$HealthChecks = @(
    @{ Name = "account-service"; Url = "http://localhost:8081/actuator/health" },
    @{ Name = "transaction-service"; Url = "http://localhost:8082/actuator/health" },
    @{ Name = "notification-service"; Url = "http://localhost:8083/actuator/health" },
    @{ Name = "audit-service"; Url = "http://localhost:8084/actuator/health" },
    @{ Name = "reconciliation-service"; Url = "http://localhost:8086/actuator/health" },
    @{ Name = "api-gateway"; Url = "http://localhost:8080/actuator/health" }
)

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

    if (-not $SkipBuild) {
        & .\gradlew.bat clean build
    }

    if (-not $KeepData) {
        docker compose down -v --remove-orphans
    }

    docker compose up -d

    foreach ($Check in $HealthChecks) {
        $Healthy = $false
        for ($i = 0; $i -lt 120; $i++) {
            try {
                $Response = Invoke-RestMethod -Uri $Check.Url -TimeoutSec 2
                if ($Response.status -eq "UP") {
                    $Healthy = $true
                    break
                }
            } catch {
                Start-Sleep -Seconds 1
            }
        }
        if (-not $Healthy) {
            docker compose ps
            docker compose logs --tail=160 $Check.Name
            throw "$($Check.Name) did not become healthy"
        }
    }

    $PowerShellExe = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
    if (-not $PowerShellExe) {
        $PowerShellExe = (Get-Command powershell -ErrorAction Stop).Source
    }
    & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root "scripts\smoke.ps1") -BaseUrl "http://localhost:8080"
} finally {
    if (-not $KeepRunning) {
        docker compose down
    }
    Pop-Location
}
