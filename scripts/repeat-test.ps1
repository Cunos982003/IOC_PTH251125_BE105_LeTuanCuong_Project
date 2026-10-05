# Repeat Test Script
# Usage: .\scripts\repeat-test.ps1 [-TestClass <TestClassName>] [-Times <Number>] [-Module <ModuleName>]

param(
    [string]$TestClass = "ConcurrencyTest",
    [int]$Times = 100,
    [string]$Module = "dispatch-service"
)

$ErrorActionPreference = "Continue"
$passed = 0
$failed = 0
$failedRuns = @()

Write-Host "Running $TestClass $Times times..." -ForegroundColor Cyan
Write-Host "Module: $Module" -ForegroundColor Gray
Write-Host ""

for ($i = 1; $i -le $Times; $i++) {
    Write-Host "Run $i/$Times..." -NoNewline

    $output = & mvn -pl $Module test "-Dtest=$TestClass" 2>&1
    $exitCode = $LASTEXITCODE

    if ($exitCode -eq 0) {
        Write-Host " PASS" -ForegroundColor Green
        $passed++
    } else {
        Write-Host " FAIL" -ForegroundColor Red
        $failed++
        $failedRuns += $i

        # Save failure details
        $failureFile = "$Module/target/test-failure-run-$i.log"
        $output | Out-File -FilePath $failureFile
        Write-Host "  Failure log saved to: $failureFile" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "========================" -ForegroundColor Cyan
Write-Host "Results: $passed/$Times passed, $failed/$Times failed" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })

if ($failed -gt 0) {
    Write-Host ""
    Write-Host "Failed runs: $($failedRuns -join ', ')" -ForegroundColor Red
    Write-Host ""
    Write-Host "To investigate failures, check the log files in target/" -ForegroundColor Yellow
    exit 1
} else {
    Write-Host ""
    Write-Host "All tests passed!" -ForegroundColor Green
    exit 0
}
