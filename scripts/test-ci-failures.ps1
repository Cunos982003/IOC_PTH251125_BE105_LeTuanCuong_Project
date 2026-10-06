# Script test CI pipeline với failure cases
# Chạy: .\scripts\test-ci-failures.ps1

param(
    [Parameter(Mandatory=$false)]
    [ValidateSet('test', 'contract', 'cleanup')]
    [string]$Scenario = 'test'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

function Test-TestFailure {
    Write-Host "=== Creating intentional test failure ===" -ForegroundColor Yellow

    $testFile = Join-Path $root "user-service/src/test/java/com/ridehailing/userservice/CiTestFailure.java"

    $content = @"
package com.ridehailing.userservice;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.fail;

public class CiTestFailure {
    @Test
    public void shouldFailIntentionally() {
        fail("Intentional failure to test CI pipeline");
    }
}
"@

    [IO.File]::WriteAllText($testFile, $content)
    Write-Host "✓ Created test failure at: $testFile" -ForegroundColor Green
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor Cyan
    Write-Host "  git add $testFile" -ForegroundColor White
    Write-Host "  git commit -m 'test: intentional CI failure'" -ForegroundColor White
    Write-Host "  git push origin test-ci" -ForegroundColor White
    Write-Host ""
    Write-Host "Expected: Job 'test' should fail and upload surefire-reports" -ForegroundColor Yellow
}

function Test-ContractFailure {
    Write-Host "=== Creating contract mismatch ===" -ForegroundColor Yellow

    $contractFile = Join-Path $root "user-service/src/test/resources/contracts/http/user-get-user.response.json"

    if (Test-Path $contractFile) {
        $content = Get-Content $contractFile -Raw
        $modified = $content + "`n// INVALID LINE FOR TESTING`n"
        [IO.File]::WriteAllText($contractFile, $modified)

        Write-Host "✓ Modified contract at: $contractFile" -ForegroundColor Green
        Write-Host ""
        Write-Host "Next steps:" -ForegroundColor Cyan
        Write-Host "  git add $contractFile" -ForegroundColor White
        Write-Host "  git commit -m 'test: contract mismatch'" -ForegroundColor White
        Write-Host "  git push origin test-ci" -ForegroundColor White
        Write-Host ""
        Write-Host "Expected: Job 'contracts' should fail with MISMATCH error" -ForegroundColor Yellow
    } else {
        Write-Host "✗ Contract file not found: $contractFile" -ForegroundColor Red
        Write-Host "Run 'pwsh scripts/check-contracts.ps1' to see available contracts" -ForegroundColor Yellow
    }
}

function Cleanup {
    Write-Host "=== Cleaning up test files ===" -ForegroundColor Yellow

    $testFile = Join-Path $root "user-service/src/test/java/com/ridehailing/userservice/CiTestFailure.java"
    if (Test-Path $testFile) {
        Remove-Item $testFile -Force
        Write-Host "✓ Removed: $testFile" -ForegroundColor Green
    }

    # Restore contracts
    Push-Location $root
    try {
        git restore "**/src/test/resources/contracts/**/*.json" 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "✓ Restored contract files" -ForegroundColor Green
        }
    } finally {
        Pop-Location
    }

    Write-Host ""
    Write-Host "Cleanup complete!" -ForegroundColor Green
}

switch ($Scenario) {
    'test' { Test-TestFailure }
    'contract' { Test-ContractFailure }
    'cleanup' { Cleanup }
}
