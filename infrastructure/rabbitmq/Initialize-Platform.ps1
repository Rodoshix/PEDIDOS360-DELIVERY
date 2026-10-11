$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (!(Test-Path -LiteralPath '.env')) { throw 'Run New-LocalEnvironment.ps1 first.' }
    if (!(Test-Path -LiteralPath '.venv/Scripts/python.exe')) {
        & python -m venv .venv
        if ($LASTEXITCODE -ne 0) { throw 'Python venv failed' }
    }
    & ./.venv/Scripts/python.exe -m pip install -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw 'Validation dependency installation failed' }
    & docker compose --env-file .env up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Broker startup/health failed' }
    & ./.venv/Scripts/python.exe platform_control.py preflight
    if ($LASTEXITCODE -ne 0) { throw 'Existing topology incompatible; no provisioning performed. Stop and report.' }
    & ./.venv/Scripts/python.exe platform_control.py provision
    if ($LASTEXITCODE -ne 0) { throw 'Provisioning incomplete; keep consumers off, inspect and rerun additively.' }
} finally { Pop-Location }
