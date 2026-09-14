#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$UsuariosImage = 'pedidos360-usuarios:i1-25-smoke',
    [string]$CarritoImage = 'pedidos360-carrito:i1-25-smoke'
)

$ErrorActionPreference = 'Stop'
$runId = 'i1-smoke-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$containers = [System.Collections.Generic.List[string]]::new()
$networkCreated = $false
$previousPgPassword = $env:POSTGRES_PASSWORD
$previousDbPassword = $env:DB_PASSWORD

function Invoke-Docker {
    # Función simple: conserva -e/-d de Docker sin confundirlos con parámetros comunes de PowerShell.
    $result = & docker @args 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return ($result -join "`n").Trim()
}

function Wait-Healthy([string]$Name) {
    $deadline = [DateTime]::UtcNow.AddSeconds(100)
    do {
        $status = Invoke-Docker inspect $Name --format '{{.State.Status}} {{.State.Health.Status}}'
        if ($status -eq 'running healthy') { return }
        if ($status -match 'exited|dead|unhealthy') { throw "No arrancó correctamente: $Name ($status)" }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Tiempo de espera de salud agotado: $Name"
}

try {
    # Contraseña sintética de esta ejecución: no se escribe ni se imprime.
    $env:POSTGRES_PASSWORD = [guid]::NewGuid().ToString('N')
    $env:DB_PASSWORD = $env:POSTGRES_PASSWORD
    Invoke-Docker network create --label "pedidos360.smoke=$runId" $runId | Out-Null
    $networkCreated = $true
    foreach ($service in @(
        @{ Name = 'usuarios'; Port = 8081; Path = '/usuarios/me'; Image = $UsuariosImage },
        @{ Name = 'carrito'; Port = 8084; Path = '/carrito'; Image = $CarritoImage }
    )) {
        $dbName = "$runId-$($service.Name)-db"
        $appName = "$runId-$($service.Name)"
        Invoke-Docker run -d --name $dbName --label "pedidos360.smoke=$runId" --network $runId `
            --tmpfs '/var/lib/postgresql/data:rw,nosuid,size=256m' --shm-size 128m `
            -e POSTGRES_PASSWORD -e POSTGRES_USER=smoke -e POSTGRES_DB=smoke `
            --health-cmd 'pg_isready -U smoke -d smoke' --health-interval 2s --health-timeout 3s `
            --health-retries 15 postgres:17-alpine | Out-Null
        $containers.Add($dbName)
        Wait-Healthy $dbName

        Invoke-Docker run -d --name $appName --label "pedidos360.smoke=$runId" --network $runId `
            --read-only --tmpfs '/tmp:rw,noexec,nosuid,size=64m' --cap-drop ALL `
            --security-opt no-new-privileges:true --memory 512m --cpus 2 `
            --publish "127.0.0.1::$($service.Port)" `
            -e "DB_URL=jdbc:postgresql://${dbName}:5432/smoke" -e DB_USERNAME=smoke -e DB_PASSWORD `
            $service.Image | Out-Null
        $containers.Add($appName)
        Wait-Healthy $appName
        $binding = Invoke-Docker port $appName "$($service.Port)/tcp"
        if ($binding -notmatch '^127\.0\.0\.1:\d+$') { throw 'Puerto de prueba fuera de loopback' }
        $origin = "http://$binding"
        $health = Invoke-RestMethod "$origin/actuator/health" -TimeoutSec 5
        $unexpectedFields = @($health.PSObject.Properties.Name | Where-Object { $_ -notin @('status', 'groups') })
        $unexpectedGroups = @($health.groups | Where-Object { $_ -and $_ -notin @('liveness', 'readiness') })
        if ($health.status -ne 'UP' -or $unexpectedFields.Count -gt 0 -or $unexpectedGroups.Count -gt 0) {
            throw "Health inesperado: status=$($health.status); campos=$($health.PSObject.Properties.Name -join ',')"
        }
        foreach ($headers in @(@{}, @{ Authorization = 'Bearer invalid-smoke-token'; 'X-Roles' = 'ADMIN' })) {
            $response = Invoke-WebRequest "$origin$($service.Path)" -Headers $headers -SkipHttpErrorCheck -TimeoutSec 5
            # Algunas versiones de PowerShell entregan application/problem+json como bytes.
            $body = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { $response.Content }
            $problem = $body | ConvertFrom-Json
            if ([int]$response.StatusCode -ne 401 -or $problem.status -ne 401) {
                throw "Protección inesperada: HTTP=$($response.StatusCode); status JSON=$($problem.status)"
            }
        }
        $uid = Invoke-Docker exec $appName id -u
        if ($uid -ne '10001') { throw 'Usuario inesperado en runtime' }
        Invoke-Docker exec $appName sh -c 'test ! -e /app/.env.local && test ! -e /build && ! command -v javac && ! command -v mvn' | Out-Null
        $migrations = Invoke-Docker exec $dbName psql -U smoke -d smoke -tAc "SELECT count(*) FROM $($service.Name).flyway_schema_history WHERE success;"
        if ([int]$migrations -lt 1) { throw 'Flyway no aplicó migraciones' }
        Invoke-Docker restart $appName | Out-Null
        Wait-Healthy $appName
        $afterRestart = Invoke-Docker exec $dbName psql -U smoke -d smoke -tAc "SELECT count(*) FROM $($service.Name).flyway_schema_history WHERE success;"
        if ($afterRestart -ne $migrations) { throw 'El reinicio alteró el historial de migraciones' }
        Write-Output "$($service.Name): healthy, 401 sin identidad, UID 10001, imagen mínima y reinicio correctos."
    }
} finally {
    # Solo recursos identificados y etiquetados por esta ejecución, sin volúmenes persistentes.
    $containers.Reverse()
    foreach ($container in $containers) {
        $label = & docker inspect $container --format '{{index .Config.Labels "pedidos360.smoke"}}' 2>$null
        if ($LASTEXITCODE -eq 0 -and $label -eq $runId) {
            & docker rm -f $container | Out-Null
            if ($LASTEXITCODE -ne 0) { Write-Warning "No se pudo retirar $container" }
        }
    }
    if ($networkCreated) {
        $label = & docker network inspect $runId --format '{{index .Labels "pedidos360.smoke"}}' 2>$null
        if ($LASTEXITCODE -eq 0 -and $label -eq $runId) {
            & docker network rm $runId | Out-Null
            if ($LASTEXITCODE -ne 0) { Write-Warning "No se pudo retirar la red $runId" }
        }
    }
    $env:POSTGRES_PASSWORD = $previousPgPassword
    $env:DB_PASSWORD = $previousDbPassword
    Write-Output 'Limpieza de recursos efímeros terminada; imágenes conservadas. Sin tocar bases/volúmenes existentes.'
}
