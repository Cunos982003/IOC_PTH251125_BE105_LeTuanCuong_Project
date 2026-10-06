#!/usr/bin/env pwsh
# Contract validation: ensure all copies match docs/contracts/ (source of truth)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

Write-Host "Checking contract copies against source of truth in docs/contracts/..." -ForegroundColor Cyan

$failures = @()

function Compare-JsonFiles {
    param($source, $copy)

    if (-not (Test-Path $source)) {
        return "Source missing: $source"
    }

    if (-not (Test-Path $copy)) {
        return "Copy missing: $copy"
    }

    $sourceContent = Get-Content $source -Raw -Encoding UTF8
    $copyContent = Get-Content $copy -Raw -Encoding UTF8

    # Normalize whitespace and compare
    $sourceNorm = ($sourceContent -replace '\s+', ' ').Trim()
    $copyNorm = ($copyContent -replace '\s+', ' ').Trim()

    if ($sourceNorm -ne $copyNorm) {
        return "Content mismatch: $copy does not match $source"
    }

    return $null
}

# Find all contract files in test resources
$testContracts = Get-ChildItem -Path $root -Recurse -Filter "*.json" |
    Where-Object { $_.FullName -match 'src[/\\]test[/\\]resources[/\\]contracts' } |
    ForEach-Object {
        $fullPath = $_.FullName
        # Extract relative path after "contracts/"
        if ($fullPath -match 'contracts[/\\](.+)$') {
            $relativePath = $matches[1] -replace '\\', '/'
            @{
                copy = $fullPath
                sourceRelative = "docs/contracts/$relativePath"
            }
        }
    }

Write-Host "Found $($testContracts.Count) contract files in test resources" -ForegroundColor Cyan

foreach ($contract in $testContracts) {
    $sourcePath = Join-Path $root $contract.sourceRelative
    $copyPath = $contract.copy
    $copyRelative = $copyPath -replace [regex]::Escape($root + '\'), ''

    $result = Compare-JsonFiles $sourcePath $copyPath
    if ($result) {
        $failures += $result
        Write-Host "  X $copyRelative" -ForegroundColor Red
    } else {
        Write-Host "  + $copyRelative" -ForegroundColor Green
    }
}

if ($failures.Count -gt 0) {
    Write-Host "`nContract validation failed:" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host "  - $failure" -ForegroundColor Red
    }
    Write-Host "`nEnsure all test resource contracts match docs/contracts/" -ForegroundColor Yellow
    exit 1
} else {
    Write-Host "`nAll contract copies match source of truth" -ForegroundColor Green
    exit 0
}

