#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot 'i1/compose.yml'
$exampleFile = Join-Path $PSScriptRoot 'i1/.env.example'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$project = 'i1-compose-smoke-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$saved = @{}
$started = $false
$testVolumes = @("${project}_usuarios_pg_data", "${project}_carrito_pg_data")

function Invoke-Compose {
    $result = & docker compose --project-name $project --env-file $exampleFile --file $composeFile @args 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return ($result -join "`n").Trim()
}
function Invoke-Docker {
    $result = & docker @args 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return ($result -join "`n").Trim()
}
function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Get-FreePort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); return $listener.LocalEndpoint.Port }
    finally { $listener.Stop() }
}
function Check-Services {
    foreach ($service in @('frontend', 'usuarios', 'carrito', 'usuarios-db', 'carrito-db')) {
        $id = Invoke-Compose ps -q $service
        Assert-True ($id -match '^[0-9a-f]{64}$') "Contenedor no encontrado: $service"
        $health = Invoke-Docker inspect $id --format '{{.State.Health.Status}}'
        Assert-True ($health -eq 'healthy') "Servicio no saludable: $service"
    }
    & node (Join-Path $repoRoot 'frontend/tools/docker-smoke.mjs') "http://127.0.0.1:$env:I1_FRONTEND_PORT"
    if ($LASTEXITCODE -ne 0) { throw 'Smoke HTTP del frontend falló' }
    foreach ($api in @(
        @{ Port = $env:I1_USUARIOS_PORT; Path = '/usuarios/me' },
        @{ Port = $env:I1_CARRITO_PORT; Path = '/carrito' }
    )) {
        $origin = "http://127.0.0.1:$($api.Port)"
        $health = Invoke-RestMethod "$origin/actuator/health" -TimeoutSec 5
        Assert-True ($health.status -eq 'UP') 'API no está UP'
        $response = Invoke-WebRequest "$origin$($api.Path)" -SkipHttpErrorCheck -TimeoutSec 5
        Assert-True ([int]$response.StatusCode -eq 401) 'La API no conserva su protección'
    }
}

try {
    $ports = [System.Collections.Generic.HashSet[int]]::new()
    while ($ports.Count -lt 3) { [void]$ports.Add((Get-FreePort)) }
    $selected = @($ports)
    $values = @{
        I1_FRONTEND_PORT = [string]$selected[0]
        I1_USUARIOS_PORT = [string]$selected[1]
        I1_CARRITO_PORT = [string]$selected[2]
        I1_ENTRA_CLIENT_ID = '11111111-1111-1111-1111-111111111111'
        I1_ENTRA_TENANT_ID = '22222222-2222-2222-2222-222222222222'
        I1_ENTRA_API_SCOPE = 'api://33333333-3333-3333-3333-333333333333/access_as_user'
        I1_API_BASE_URL = 'http://localhost:8080'
        I1_USUARIOS_DB_PASSWORD = [guid]::NewGuid().ToString('N')
        I1_CARRITO_DB_PASSWORD = [guid]::NewGuid().ToString('N')
    }
    foreach ($key in $values.Keys) {
        $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
    }
    foreach ($volume in $testVolumes) {
        & docker volume inspect $volume 2>&1 | Out-Null
        Assert-True ($LASTEXITCODE -ne 0) 'El volumen de prueba ya existía; no se utilizará'
    }

    # Configuración capturada, nunca impresa: contiene contraseñas interpoladas.
    $model = Invoke-Compose config --format json | ConvertFrom-Json
    Assert-True (@($model.services.PSObject.Properties).Count -eq 5) 'Se esperaban cinco servicios'
    foreach ($name in @('usuarios', 'carrito')) {
        $api = $model.services.$name
        $db = $model.services."$name-db"
        Assert-True (-not $db.ports) 'PostgreSQL no debe publicar puertos'
        Assert-True ($api.depends_on."$name-db".condition -eq 'service_healthy') 'Falta dependencia saludable'
        Assert-True ($api.environment.LOCAL_IDENTITY_ENABLED -eq 'false') 'No habilitar identidad ficticia'
    }
    foreach ($name in @('frontend', 'usuarios', 'carrito')) {
        Assert-True ($model.services.$name.ports[0].host_ip -eq '127.0.0.1') 'Puerto fuera de loopback'
        Assert-True ($model.services.$name.read_only -eq $true) 'Runtime debe ser read-only'
    }
    Assert-True (@($model.volumes.PSObject.Properties).Count -eq 2) 'Se esperaban dos volúmenes'
    foreach ($passwordKey in @('I1_USUARIOS_DB_PASSWORD', 'I1_CARRITO_DB_PASSWORD')) {
        [Environment]::SetEnvironmentVariable($passwordKey, '', 'Process')
        $failure = & docker compose --project-name $project --env-file $exampleFile --file $composeFile config --quiet 2>&1
        Assert-True ($LASTEXITCODE -ne 0 -and ($failure -join ' ') -match $passwordKey) 'Debe rechazar contraseñas ausentes'
        [Environment]::SetEnvironmentVariable($passwordKey, $values[$passwordKey], 'Process')
    }
    Write-Output 'Configuración validada: cinco servicios, loopback, bases privadas y contraseñas requeridas.'
    Invoke-Compose build | Out-Null
    $started = $true
    Invoke-Compose up -d --wait --wait-timeout 180 | Out-Null
    Check-Services
    foreach ($name in @('usuarios', 'carrito')) {
        Invoke-Compose exec -T "$name-db" psql -U "pedidos360_$name" -d "pedidos360_$name" -v ON_ERROR_STOP=1 -c `
            "CREATE TABLE public.i1_smoke (marker text PRIMARY KEY); INSERT INTO public.i1_smoke VALUES ('$project');" | Out-Null
    }
    # Prueba real de persistencia: recrear contenedores SIN eliminar volúmenes.
    Invoke-Compose down | Out-Null
    foreach ($volume in $testVolumes) { Invoke-Docker volume inspect $volume --format '{{.Name}}' | Out-Null }
    Invoke-Compose up -d --no-build --wait --wait-timeout 180 | Out-Null
    Check-Services
    foreach ($name in @('usuarios', 'carrito')) {
        $marker = Invoke-Compose exec -T "$name-db" psql -U "pedidos360_$name" -d "pedidos360_$name" -tAc 'SELECT marker FROM public.i1_smoke;'
        Assert-True ($marker -eq $project) "No persistieron los datos sintéticos: $name"
    }
    Write-Output 'Compose aprobado: cinco servicios healthy, APIs protegidas y persistencia tras down/up sin borrar volúmenes.'
} finally {
    if ($started) {
        # Solo el proyecto aleatorio creado aquí. Nunca down -v ni limpieza global.
        & docker compose --project-name $project --env-file $exampleFile --file $composeFile down 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Revisar limpieza del proyecto $project" }
        foreach ($volume in $testVolumes) {
            $owner = & docker volume inspect $volume --format '{{index .Labels "com.docker.compose.project"}}' 2>$null
            if ($LASTEXITCODE -eq 0 -and $owner -eq $project) {
                & docker volume rm $volume | Out-Null
                if ($LASTEXITCODE -ne 0) { Write-Warning "Revisar volumen sintético $volume" }
            }
        }
    }
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    Write-Output 'Limpieza de prueba terminada. Solo se retiran datos sintéticos propios; las imágenes permanecen.'
}
