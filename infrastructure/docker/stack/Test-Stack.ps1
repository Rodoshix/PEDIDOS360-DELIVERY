# Smoke aislado: identidades ficticias, sin login ni worker Entra real.
$ErrorActionPreference = 'Stop'
$taskProject = 'i5-stack-test-' + [guid]::NewGuid().ToString('N').Substring(0,10)
$taskTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$taskTls = Join-Path $taskTempRoot $taskProject
$taskPrevious = @{}
$taskConfig = @{
    STACK_FRONTEND_PORT = '5188'
    STACK_TLS_DIR = $taskTls
    STACK_SECRETS_DIR = (Join-Path $taskTls 'secrets')
    STACK_TLS_PASSWORD = [guid]::NewGuid().ToString('N')
    ENTRA_TENANT_ID = '11111111-1111-1111-1111-111111111111'
    ENTRA_API_CLIENT_ID = '22222222-2222-2222-2222-222222222222'
    ENTRA_FRONTEND_CLIENT_ID = '33333333-3333-3333-3333-333333333333'
    PAGOS_WORKER_CLIENT_ID = '44444444-4444-4444-4444-444444444444'
    PAGOS_WORKER_CLIENT_SECRET = [guid]::NewGuid().ToString('N')
}
foreach ($taskService in @('USUARIOS','RESTAURANTES','PRODUCTOS','CARRITO','PEDIDOS','PAGOS')) {
    $taskConfig["STACK_${taskService}_DB_PASSWORD"] = [guid]::NewGuid().ToString('N')
}
$taskCompose = @('compose','-p',$taskProject,'-f',(Join-Path $PSScriptRoot 'compose.yml'))
try {
    foreach ($taskKey in $taskConfig.Keys) {
        $taskPrevious[$taskKey] = [Environment]::GetEnvironmentVariable($taskKey,'Process')
        [Environment]::SetEnvironmentVariable($taskKey,$taskConfig[$taskKey],'Process')
    }
    & (Join-Path $PSScriptRoot 'New-LocalTls.ps1') -OutputDirectory $taskTls
    & (Join-Path $PSScriptRoot 'New-LocalSecrets.ps1') -OutputDirectory (Join-Path $taskTls 'secrets')
    $taskStartup = & docker @taskCompose up -d --wait --wait-timeout 240 2>&1
    if ($LASTEXITCODE -ne 0) {
        $taskStartup | Select-Object -Last 20 | ForEach-Object { Write-Output "$_" }
        & docker @taskCompose logs --tail 25 2>&1 | Select-String 'ERROR|FATAL|Caused by|Exception|Permission denied' | ForEach-Object { Write-Output $_.Line }
        throw 'El stack de prueba no llegó a healthy.'
    }
    $taskHealth = Invoke-WebRequest 'http://localhost:5188/healthz' -TimeoutSec 10
    if ($taskHealth.StatusCode -ne 200) { throw 'Frontend no saludable.' }
    # Nginx -> BFF: la salud es pública; el catálogo exige un JWT real.
    $taskProxyHealth = Invoke-WebRequest 'http://localhost:5188/api/actuator/health' -TimeoutSec 15
    if ($taskProxyHealth.StatusCode -ne 200) { throw 'Falló el proxy TLS al BFF.' }
    # Comprueba DNS y TLS del catálogo en la misma red interna, sin relajar el BFF.
    $taskCatalogStatus = & docker run --rm --network "${taskProject}_services" --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m --cap-drop ALL --security-opt no-new-privileges:true --mount "type=bind,source=$taskTls/truststore.p12,target=/run/truststore.p12,readonly" --mount "type=bind,source=$PSScriptRoot/TlsProbe.java,target=/probe/TlsProbe.java,readonly" --entrypoint java eclipse-temurin:21-jdk-alpine@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6 '-Djavax.net.ssl.trustStore=/run/truststore.p12' '-Djavax.net.ssl.trustStorePassword=changeit' /probe/TlsProbe.java https://productos:8083/productos
    if ($LASTEXITCODE -ne 0 -or $taskCatalogStatus -ne '200') { throw 'Falló el catálogo HTTPS interno.' }
    try {
        Invoke-WebRequest 'http://localhost:5188/api/pedidos/me' -TimeoutSec 10 | Out-Null
        throw 'Se aceptó una consulta de pedidos sin token.'
    } catch {
        if ([int]$_.Exception.Response.StatusCode -ne 401) { throw }
    }
    Write-Output 'STACK_OK: servicios saludables, proxy BFF HTTPS, catálogo HTTPS interno y rechazo 401 sin token.'
} finally {
    # -v solo sobre el proyecto de prueba generado aquí, nunca el stack persistente.
    & docker @taskCompose down -v | Out-Null
    $taskResolved = [IO.Path]::GetFullPath($taskTls)
    if ($taskResolved.StartsWith($taskTempRoot,[StringComparison]::OrdinalIgnoreCase) -and (Split-Path $taskResolved -Leaf) -eq $taskProject -and (Test-Path -LiteralPath $taskResolved)) {
        Remove-Item -LiteralPath $taskResolved -Recurse -Force
    }
    foreach ($taskKey in $taskPrevious.Keys) {
        [Environment]::SetEnvironmentVariable($taskKey,$taskPrevious[$taskKey],'Process')
    }
}
