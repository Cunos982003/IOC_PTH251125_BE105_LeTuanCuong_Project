# Run from any directory: .\tools\e2e\run-e2e.ps1
$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Push-Location $projectRoot
try {
    $redisContainer = docker compose ps -q redis
    if ($LASTEXITCODE -ne 0 -or -not $redisContainer) {
        throw "Redis Compose container not found. Start the stack with docker compose --profile apps up -d."
    }
    if (-not $env:API_GATEWAY_URL) { $env:API_GATEWAY_URL = "http://localhost:8000" }
    if (-not $env:WS_GATEWAY_URL) { $env:WS_GATEWAY_URL = "ws://localhost:8001/ws/driver" }
    if (-not $env:REDIS_HOST) { $env:REDIS_HOST = "localhost" }
    if (-not $env:REDIS_PORT) { $env:REDIS_PORT = "6379" }
    if (-not $env:REDIS_PASSWORD) {
        $container = docker inspect $redisContainer | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect Redis container environment" }
        $passwordEntry = $container[0].Config.Env | Where-Object { $_.StartsWith("REDIS_PASSWORD=") } | Select-Object -First 1
        if (-not $passwordEntry) { throw "Redis container does not expose REDIS_PASSWORD; set it explicitly in your environment." }
        $env:REDIS_PASSWORD = $passwordEntry.Substring("REDIS_PASSWORD=".Length)
    }
    Write-Host "Building E2E harness..."
    mvn "-f" "$PSScriptRoot\pom.xml" "clean" "package" "-DskipTests" "-q"
    if ($LASTEXITCODE -ne 0) { throw "E2E Maven build failed" }
    Write-Host "Running E2E suite..."
    mvn "-f" "$PSScriptRoot\pom.xml" "exec:java" "-Dexec.mainClass=com.ridehailing.e2e.scenario.ScenarioRunner" "-q"
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $exitCode
