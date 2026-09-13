# Materializa secretos de ejecución para Compose; nunca se incorporan a imágenes.
param([string]$OutputDirectory = (Join-Path $PSScriptRoot 'secrets'))
$ErrorActionPreference = 'Stop'
$taskMapping = @{
    tls_password = 'STACK_TLS_PASSWORD'
    worker_secret = 'PAGOS_WORKER_CLIENT_SECRET'
}
foreach ($taskService in @('usuarios','restaurantes','productos','carrito','pedidos','pagos')) {
    $taskMapping[$taskService + '_db_password'] = 'STACK_' + $taskService.ToUpperInvariant() + '_DB_PASSWORD'
}
$taskValues = @{}
foreach ($taskName in $taskMapping.Keys) {
    $taskValue = [Environment]::GetEnvironmentVariable($taskMapping[$taskName], 'Process')
    if ([string]::IsNullOrWhiteSpace($taskValue)) { throw "Falta $($taskMapping[$taskName])." }
    $taskValues[$taskName] = $taskValue
    $taskFile = Join-Path $OutputDirectory $taskName
    if ((Test-Path -LiteralPath $taskFile) -and [IO.File]::ReadAllText($taskFile) -cne $taskValue) {
        throw 'Hay un secreto existente distinto: realizar una rotación explícita antes de continuar.'
    }
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
foreach ($taskName in $taskValues.Keys) {
    $taskFile = Join-Path $OutputDirectory $taskName
    if (-not (Test-Path -LiteralPath $taskFile)) {
        [IO.File]::WriteAllText($taskFile, $taskValues[$taskName], [Text.UTF8Encoding]::new($false))
    }
}
Write-Output 'Archivos de secretos preparados (sin mostrar sus valores).'
