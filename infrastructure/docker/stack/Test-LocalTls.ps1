$ErrorActionPreference = 'Stop'
$taskPrefix = 'pedidos360-tls-' + [guid]::NewGuid().ToString('N')
$taskTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$taskDirectory = Join-Path $taskTempRoot $taskPrefix
$taskServer = $taskPrefix + '-bff'
$taskPrevious = @{}
foreach ($taskKey in @('STACK_TLS_PASSWORD','SERVER_SSL_KEY_STORE_PASSWORD')) {
    $taskPrevious[$taskKey] = [Environment]::GetEnvironmentVariable($taskKey,'Process')
}
try {
    $env:STACK_TLS_PASSWORD = [guid]::NewGuid().ToString('N')
    $env:SERVER_SSL_KEY_STORE_PASSWORD = $env:STACK_TLS_PASSWORD
    & (Join-Path $PSScriptRoot 'New-LocalTls.ps1') -OutputDirectory $taskDirectory
    & docker run -d --name $taskServer --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m --cap-drop ALL --security-opt no-new-privileges:true --mount "type=bind,source=$taskDirectory/bff.p12,target=/run/tls/server.p12,readonly" -e SERVER_SSL_ENABLED=true -e SERVER_SSL_KEY_STORE=file:/run/tls/server.p12 -e SERVER_SSL_KEY_STORE_TYPE=PKCS12 -e SERVER_SSL_KEY_STORE_PASSWORD pedidos360-bff:i5-stack | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo iniciar BFF TLS de prueba.' }
    $taskDeadline = (Get-Date).AddSeconds(60)
    do {
        & docker run --rm --network "container:$taskServer" --mount "type=bind,source=$taskDirectory/truststore.p12,target=/run/tls/truststore.p12,readonly" -e SERVER_PORT=8080 --entrypoint java pedidos360-bff:i5-stack '-Djavax.net.ssl.trustStore=/run/tls/truststore.p12' '-Djavax.net.ssl.trustStorePassword=changeit' -cp /app Health
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $taskDeadline)
    if ($LASTEXITCODE -ne 0) { throw 'Falló TLS con certificado confiable.' }
    & docker run --rm --network "container:$taskServer" -e SERVER_PORT=8080 --entrypoint java pedidos360-bff:i5-stack -cp /app Health
    if ($LASTEXITCODE -eq 0) { throw 'Se aceptó un certificado sin confianza explícita.' }
    Write-Output 'TLS_OK: certificado confiable aceptado y certificado sin confianza rechazado.'
} finally {
    & docker rm -f $taskServer 2>$null | Out-Null
    # Solo eliminar el directorio temporal propio, nunca el directorio tls del stack.
    $taskResolved = [IO.Path]::GetFullPath($taskDirectory)
    if ($taskResolved.StartsWith($taskTempRoot, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path $taskResolved -Leaf) -eq $taskPrefix -and (Test-Path -LiteralPath $taskResolved)) {
        Remove-Item -LiteralPath $taskResolved -Recurse -Force
    }
    foreach ($taskKey in $taskPrevious.Keys) {
        [Environment]::SetEnvironmentVariable($taskKey,$taskPrevious[$taskKey],'Process')
    }
}
