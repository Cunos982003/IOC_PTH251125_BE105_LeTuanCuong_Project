# E2E Test Runner Script
# Chạy: .\tools\e2e\run-e2e.ps1

$ErrorActionPreference = "Stop"

Write-Host "================================" -ForegroundColor Cyan
Write-Host "E2E Test Suite - Ride Hailing" -ForegroundColor Cyan
Write-Host "================================" -ForegroundColor Cyan
Write-Host ""

# Kiểm tra Docker Compose đang chạy
Write-Host "Checking Docker Compose services..." -ForegroundColor Yellow
$containers = docker compose ps --format json 2>$null | ConvertFrom-Json
if (-not $containers -or $containers.Count -eq 0) {
    Write-Host "ERROR: No running containers found. Please run:" -ForegroundColor Red
    Write-Host "  docker compose --profile apps up -d" -ForegroundColor Red
    exit 1
}

Write-Host "Found $($containers.Count) running containers" -ForegroundColor Green
Write-Host ""

# Build E2E module
Write-Host "Building E2E test module..." -ForegroundColor Yellow
Push-Location "$PSScriptRoot"
try {
    mvn clean package -DskipTests -q
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Maven build failed" -ForegroundColor Red
        exit 1
    }
    Write-Host "Build successful" -ForegroundColor Green
    Write-Host ""
} finally {
    Pop-Location
}

# Set environment variables
$env:API_GATEWAY_URL = "http://localhost:8000"
$env:WS_GATEWAY_URL = "ws://localhost:8001/ws"
$env:REDIS_HOST = "localhost"
$env:REDIS_PASSWORD = "redis_password_123"

# Run tests
Write-Host "Running E2E tests..." -ForegroundColor Yellow
Write-Host ""

Push-Location "$PSScriptRoot"
try {
    mvn exec:java -Dexec.mainClass="com.ridehailing.e2e.scenario.ScenarioRunner" -q
    $exitCode = $LASTEXITCODE

    Write-Host ""
    if ($exitCode -eq 0) {
        Write-Host "All tests passed!" -ForegroundColor Green
    } else {
        Write-Host "Some tests failed!" -ForegroundColor Red
    }

    exit $exitCode
} finally {
    Pop-Location
}
