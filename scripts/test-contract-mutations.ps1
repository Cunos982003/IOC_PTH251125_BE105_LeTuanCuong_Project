param()
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$manifest = Get-Content (Join-Path $root 'docs/contracts/manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$entry = $manifest.contracts | Where-Object name -eq 'events/TripCompleted'
$paths = @((Join-Path $root 'docs/contracts/events/TripCompleted.json'))
$paths += $entry.services | ForEach-Object { Join-Path $root "$_/src/test/resources/contracts/events/TripCompleted.json" }
$backup = @{}
foreach ($path in $paths) { $backup[$path] = [IO.File]::ReadAllBytes($path) }
$ps = (Get-Process -Id $PID).Path
Push-Location $root
try {
    & $ps -NoProfile -File scripts/check-contracts.ps1
    if ($LASTEXITCODE -ne 0) { throw 'Baseline copy check failed' }
    & mvn test '-Dtest=*ContractTest' '-Dsurefire.failIfNoSpecifiedTests=false'
    if ($LASTEXITCODE -ne 0) { throw 'Baseline contract tests failed' }
    $mutated = [IO.File]::ReadAllText($paths[0]).Replace('"fare":','"fareRenamed":')
    [IO.File]::WriteAllText($paths[0], $mutated, [Text.UTF8Encoding]::new($false))
    & $ps -NoProfile -File scripts/check-contracts.ps1
    if ($LASTEXITCODE -ne 1) { throw 'Mutation A must fail at copy verification' }
    Write-Host '[OK] Mutation A rejected by check-contracts.'
    foreach ($path in $paths | Select-Object -Skip 1) { [IO.File]::WriteAllText($path, $mutated, [Text.UTF8Encoding]::new($false)) }
    & $ps -NoProfile -File scripts/check-contracts.ps1
    if ($LASTEXITCODE -ne 0) { throw 'Mutation B copies must match before testing code' }
    & mvn -pl user-service test '-Dtest=ContractTest'
    if ($LASTEXITCODE -eq 0) { throw 'Mutation B incorrectly passed service contract tests' }
    $report = [xml](Get-Content 'user-service/target/surefire-reports/TEST-com.ridehailing.userservice.ContractTest.xml' -Raw)
    $failed = @($report.testsuite.testcase | Where-Object { $_.failure })
    if (!($failed | Where-Object name -eq 'tripCompletedEvent_matchesContract')) { throw 'Mutation B failed for the wrong reason; required fare assertion must fail' }
    Write-Host '[OK] Mutation B rejected by service fare assertion.'
} finally {
    foreach ($path in $paths) { [IO.File]::WriteAllBytes($path, $backup[$path]) }
    Pop-Location
}
Push-Location $root
try {
    & $ps -NoProfile -File scripts/check-contracts.ps1
    if ($LASTEXITCODE -ne 0) { throw 'Restored copies failed' }
    & mvn test '-Dtest=*ContractTest' '-Dsurefire.failIfNoSpecifiedTests=false'
    if ($LASTEXITCODE -ne 0) { throw 'Restored contract tests failed' }
} finally { Pop-Location }
