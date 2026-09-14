param(
    [ValidateSet('config','build','up','ps','down')][string]$Action = 'ps',
    [string]$EnvironmentFile = (Join-Path $PSScriptRoot '.env.local')
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $EnvironmentFile)) { throw 'Falta .env.local (usar .env.example como referencia).' }
$taskPrevious = @{}
try {
    foreach ($taskLine in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $EnvironmentFile))) {
        if ($taskLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            $taskKey = $Matches[1]
            $taskValue = $Matches[2].Trim()
            if (-not ($taskKey -match '^(STACK_|ENTRA_|PAGOS_WORKER_)')) { throw 'Variable no permitida en configuración del stack.' }
            $taskPrevious[$taskKey] = [Environment]::GetEnvironmentVariable($taskKey, 'Process')
            [Environment]::SetEnvironmentVariable($taskKey, $taskValue, 'Process')
        }
    }
    foreach ($taskKey in @('STACK_TLS_PASSWORD','STACK_USUARIOS_DB_PASSWORD','STACK_RESTAURANTES_DB_PASSWORD',
            'STACK_PRODUCTOS_DB_PASSWORD','STACK_CARRITO_DB_PASSWORD','STACK_PEDIDOS_DB_PASSWORD',
            'STACK_PAGOS_DB_PASSWORD','PAGOS_WORKER_CLIENT_SECRET')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($taskKey, 'Process'))) {
            throw "Falta $taskKey. No se usará una contraseña predeterminada."
        }
    }
    foreach ($taskDirectory in @{ STACK_TLS_DIR = 'tls'; STACK_SECRETS_DIR = 'secrets' }.GetEnumerator()) {
        $taskKey = $taskDirectory.Key
        if (-not $taskPrevious.ContainsKey($taskKey)) {
            $taskPrevious[$taskKey] = [Environment]::GetEnvironmentVariable($taskKey, 'Process')
        }
        $taskPath = [Environment]::GetEnvironmentVariable($taskKey, 'Process')
        if ([string]::IsNullOrWhiteSpace($taskPath)) { $taskPath = $taskDirectory.Value }
        if (-not [IO.Path]::IsPathRooted($taskPath)) { $taskPath = Join-Path $PSScriptRoot $taskPath }
        [Environment]::SetEnvironmentVariable($taskKey, [IO.Path]::GetFullPath($taskPath), 'Process')
    }
    if ($Action -eq 'up') { & (Join-Path $PSScriptRoot 'New-LocalSecrets.ps1') -OutputDirectory $env:STACK_SECRETS_DIR }
    if ($Action -eq 'up' -and -not (Test-Path -LiteralPath $env:STACK_TLS_DIR)) {
        & (Join-Path $PSScriptRoot 'New-LocalTls.ps1') -OutputDirectory $env:STACK_TLS_DIR
    }
    $taskArgs = @('compose','-f',(Join-Path $PSScriptRoot 'compose.yml'))
    switch ($Action) {
        'config' { $taskArgs += @('config','--quiet') }
        'build' { $taskArgs += 'build' }
        'up' { $taskArgs += @('up','-d','--wait','--wait-timeout','240') }
        'ps' { $taskArgs += 'ps' }
        'down' { $taskArgs += 'down' } # No -v: conservar las bases persistentes.
    }
    & docker @taskArgs
    if ($LASTEXITCODE -ne 0) { throw 'La operación Compose no terminó correctamente.' }
} finally {
    foreach ($taskKey in $taskPrevious.Keys) {
        [Environment]::SetEnvironmentVariable($taskKey, $taskPrevious[$taskKey], 'Process')
    }
}
