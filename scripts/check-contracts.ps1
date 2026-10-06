param([switch]$Staged)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$services = @('user-service','location-service','dispatch-service','pricing-service','payment-service','ws-gateway','api-gateway')
function Read-Contract([string]$path) {
    if ($Staged) {
        $text = & git -C $root show ":$path" 2>$null
        if ($LASTEXITCODE -ne 0) { throw "Missing staged file: $path" }
        return ($text -join "`n").Replace("`r`n", "`n").TrimEnd()
    }
    $full = Join-Path $root $path
    if (!(Test-Path -LiteralPath $full -PathType Leaf)) { throw "Missing file: $path" }
    return ([IO.File]::ReadAllText($full)).Replace("`r`n", "`n").TrimEnd()
}
try {
    $manifest = Read-Contract 'docs/contracts/manifest.json' | ConvertFrom-Json
    $failed = @()
    $expected = @{}
    foreach ($entry in $manifest.contracts) {
        foreach ($suffix in $entry.files) {
            $source = "docs/contracts/$($entry.name)$suffix"
            $expected[$source] = $true
            $sourceText = Read-Contract $source
            if ($source.EndsWith('.json')) { $null = $sourceText | ConvertFrom-Json }
            foreach ($service in $entry.services) {
                if ($services -notcontains $service) { throw "Unknown service: $service" }
                $copy = "$service/src/test/resources/contracts/$($entry.name)$suffix"
                $expected[$copy] = $true
                try {
                    if ((Read-Contract $copy) -cne $sourceText) { $failed += "MISMATCH: $copy (source: $source)" }
                } catch { $failed += $_.Exception.Message }
            }
        }
    }
    if ($Staged) {
        $paths = & git -C $root ls-files
        if ($LASTEXITCODE -ne 0) { throw 'Cannot list staged files' }
    } else {
        $paths = @(Get-ChildItem (Join-Path $root 'docs/contracts') -Recurse -File | ForEach-Object { $_.FullName.Substring($root.Length + 1).Replace('\','/') })
        foreach ($service in $services) {
            $dir = Join-Path $root "$service/src/test/resources/contracts"
            if (Test-Path $dir) { $paths += Get-ChildItem $dir -Recurse -File | ForEach-Object { $_.FullName.Substring($root.Length + 1).Replace('\','/') } }
        }
    }
    foreach ($path in $paths) {
        if ($path -match '^(docs/contracts/|[^/]+/src/test/resources/contracts/)' -and $path -match '\.(json|md)$' -and $path -notin @('docs/contracts/manifest.json','docs/contracts/README.md') -and !$expected.ContainsKey($path)) {
            $failed += "UNREGISTERED: $path"
        }
    }
    if ($failed.Count) { $failed | ForEach-Object { Write-Host "[FAIL] $_" -ForegroundColor Red }; exit 1 }
    Write-Host '[OK] All registered contract copies match.' -ForegroundColor Green
    exit 0
} catch {
    Write-Host "[FAIL] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
