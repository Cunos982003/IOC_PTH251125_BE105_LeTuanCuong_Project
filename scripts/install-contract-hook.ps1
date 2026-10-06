param()
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$existing = & git -C $root config --get core.hooksPath
if ($LASTEXITCODE -eq 0 -and $existing -and $existing -ne '.githooks') { throw "Existing core.hooksPath '$existing'; not overwritten." }
$hook = Join-Path $root '.git/hooks/pre-commit'
if (!$existing -and (Test-Path $hook)) { throw 'Existing .git/hooks/pre-commit; not overwritten.' }
& git -C $root config core.hooksPath .githooks
if ($LASTEXITCODE -ne 0) { throw 'Could not configure hooks' }
Write-Host 'Contract pre-commit hook installed.'
