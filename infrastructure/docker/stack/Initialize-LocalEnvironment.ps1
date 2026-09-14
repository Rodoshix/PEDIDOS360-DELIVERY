# Reutiliza la configuración local existente sin mostrar secretos ni sobrescribirla.
$ErrorActionPreference = 'Stop'
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskDestination = Join-Path $PSScriptRoot '.env.local'
if (Test-Path -LiteralPath $taskDestination) { throw 'Ya existe .env.local; no se sobrescribirá.' }
function Read-LocalValues([string]$Path) {
    $taskValues = @{}
    foreach ($taskLine in [IO.File]::ReadAllLines($Path)) {
        if ($taskLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            $taskValues[$Matches[1]] = $Matches[2].Trim()
        }
    }
    return $taskValues
}
$taskFrontend = Read-LocalValues (Join-Path $taskRepo 'frontend/.env.local')
$taskWorker = Read-LocalValues (Join-Path $taskRepo 'backend/services/pagos-service/.env.worker.local')
$taskSettings = [ordered]@{
    STACK_FRONTEND_PORT = '5180'
    ENTRA_TENANT_ID = $taskWorker['ENTRA_TENANT_ID']
    ENTRA_API_CLIENT_ID = $taskWorker['ENTRA_API_CLIENT_ID']
    ENTRA_FRONTEND_CLIENT_ID = $taskFrontend['VITE_ENTRA_CLIENT_ID']
    PAGOS_WORKER_CLIENT_ID = $taskWorker['PAGOS_WORKER_CLIENT_ID']
    PAGOS_WORKER_CLIENT_SECRET = $taskWorker['PAGOS_WORKER_CLIENT_SECRET']
}
foreach ($taskKey in $taskSettings.Keys) {
    if ([string]::IsNullOrWhiteSpace($taskSettings[$taskKey])) { throw "Falta $taskKey en la configuración fuente." }
}
foreach ($taskKey in @('ENTRA_TENANT_ID','ENTRA_API_CLIENT_ID','ENTRA_FRONTEND_CLIENT_ID','PAGOS_WORKER_CLIENT_ID')) {
    $taskGuid = [guid]::Empty
    if (-not [guid]::TryParse($taskSettings[$taskKey], [ref]$taskGuid)) { throw "ID inválido: $taskKey" }
}
if ($taskFrontend['VITE_ENTRA_TENANT_ID'] -ne $taskSettings.ENTRA_TENANT_ID -or
    $taskFrontend['VITE_ENTRA_API_SCOPE'] -ne "api://$($taskSettings.ENTRA_API_CLIENT_ID)/access_as_user") {
    throw 'Frontend y worker no corresponden al mismo tenant/API.'
}
foreach ($taskKey in @('STACK_TLS_PASSWORD','STACK_USUARIOS_DB_PASSWORD','STACK_RESTAURANTES_DB_PASSWORD',
        'STACK_PRODUCTOS_DB_PASSWORD','STACK_CARRITO_DB_PASSWORD','STACK_PEDIDOS_DB_PASSWORD','STACK_PAGOS_DB_PASSWORD')) {
    $taskBytes = New-Object byte[] 32
    $taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
    $taskSettings[$taskKey] = [Convert]::ToBase64String($taskBytes)
}
# Artefacto generado de ejecución, excluido de Git; no registrar su contenido.
$taskLines = foreach ($taskEntry in $taskSettings.GetEnumerator()) { "$($taskEntry.Key)=$($taskEntry.Value)" }
[IO.File]::WriteAllLines($taskDestination, $taskLines, [Text.UTF8Encoding]::new($false))
Write-Output 'Configuración local preparada sin modificar las fuentes ni mostrar secretos.'
