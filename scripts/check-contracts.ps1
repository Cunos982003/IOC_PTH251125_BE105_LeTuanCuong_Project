# Check contract compliance across services
# Verifies that service contract copies match docs/contracts/

$ErrorActionPreference = "Stop"

$contractsDir = "docs/contracts"
$services = @(
    @{
        Name = "user-service"
        Contracts = @("events/UserRegistered", "events/TripCompleted")
    }
)

$allPassed = $true

Write-Host "Checking contract compliance..." -ForegroundColor Cyan

foreach ($service in $services) {
    Write-Host "`nService: $($service.Name)" -ForegroundColor Yellow

    foreach ($contract in $service.Contracts) {
        $sourceMd = "$contractsDir/$contract.md"
        $sourceJson = "$contractsDir/$contract.json"
        $targetMd = "$($service.Name)/src/test/resources/contracts/$contract.md"
        $targetJson = "$($service.Name)/src/test/resources/contracts/$contract.json"

        # Check MD file
        if (Test-Path $sourceMd) {
            if (Test-Path $targetMd) {
                $sourceContent = Get-Content $sourceMd -Raw
                $targetContent = Get-Content $targetMd -Raw
                if ($sourceContent -eq $targetContent) {
                    Write-Host "  [OK] $contract.md matches" -ForegroundColor Green
                } else {
                    Write-Host "  [FAIL] $contract.md MISMATCH" -ForegroundColor Red
                    $allPassed = $false
                }
            } else {
                Write-Host "  [FAIL] $contract.md MISSING in service" -ForegroundColor Red
                $allPassed = $false
            }
        }

        # Check JSON file
        if (Test-Path $sourceJson) {
            if (Test-Path $targetJson) {
                $sourceContent = Get-Content $sourceJson -Raw
                $targetContent = Get-Content $targetJson -Raw
                if ($sourceContent -eq $targetContent) {
                    Write-Host "  [OK] $contract.json matches" -ForegroundColor Green
                } else {
                    Write-Host "  [FAIL] $contract.json MISMATCH" -ForegroundColor Red
                    $allPassed = $false
                }
            } else {
                Write-Host "  [FAIL] $contract.json MISSING in service" -ForegroundColor Red
                $allPassed = $false
            }
        }
    }
}

Write-Host ""
if ($allPassed) {
    Write-Host "All contracts match!" -ForegroundColor Green
    exit 0
} else {
    Write-Host "Some contracts do not match!" -ForegroundColor Red
    exit 1
}
