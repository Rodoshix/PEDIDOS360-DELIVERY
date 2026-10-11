$ErrorActionPreference = 'Stop'
$target = Join-Path $PSScriptRoot '.env'
if (Test-Path -LiteralPath $target) { throw '.env already exists; credentials are not overwritten. Follow the rotation runbook.' }
$lines = Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env.example')
$generated = foreach ($line in $lines) {
    if ($line -match '_PASSWORD=$') {
        $bytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(24)
        $line + [Convert]::ToHexString($bytes).ToLowerInvariant()
    } else { $line }
}
$generated | Set-Content -LiteralPath $target -Encoding utf8NoBOM
Write-Output 'Private .env generated with distinct passwords. No credentials printed.'
