param([string]$Tag = 'i5-stack')
$ErrorActionPreference = 'Stop'
$taskPrefix = 'i5-images-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$taskNetwork = $taskPrefix + '-net'
$taskNames = [System.Collections.Generic.List[string]]::new()
$taskOldDbPassword = $env:DB_PASSWORD
$taskOldPostgresPassword = $env:POSTGRES_PASSWORD
function Invoke-DockerChecked {
    param([string[]]$Arguments)
    $taskOutput = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Falló una operación Docker de la prueba.' }
    return $taskOutput
}
function Wait-Healthy([string]$Name) {
    $taskDeadline = (Get-Date).AddSeconds(90)
    do {
        $taskInfo = (Invoke-DockerChecked @('inspect', $Name) | ConvertFrom-Json)[0]
        if ($taskInfo.State.Health.Status -eq 'healthy') { Write-Output "$Name healthy"; return }
        if (-not $taskInfo.State.Running) { throw "$Name no está ejecutándose." }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $taskDeadline)
    throw "$Name no llegó a healthy a tiempo."
}
try {
    $env:POSTGRES_PASSWORD = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    $env:DB_PASSWORD = $env:POSTGRES_PASSWORD
    Invoke-DockerChecked @('network','create','--internal',$taskNetwork) | Out-Null
    $taskDb = $taskPrefix + '-db'
    $taskNames.Add($taskDb)
    Invoke-DockerChecked @('run','-d','--name',$taskDb,'--network',$taskNetwork,
        '--tmpfs','/var/lib/postgresql/data:rw', '-e','POSTGRES_PASSWORD', '-e','POSTGRES_DB=images_test',
        '--health-cmd','pg_isready -U postgres -d images_test','--health-interval','2s','--health-retries','30',
        'postgres:17-alpine@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73') | Out-Null
    Wait-Healthy $taskDb
    foreach ($taskService in @('bff','restaurantes','productos')) {
        $taskName = $taskPrefix + '-' + $taskService
        $taskNames.Add($taskName)
        Invoke-DockerChecked @('run','-d','--name',$taskName,'--network',$taskNetwork,
            '--read-only','--tmpfs','/tmp:rw,noexec,nosuid,size=64m', '--cap-drop','ALL',
            '--security-opt','no-new-privileges:true', '--memory','512m',
            '-e',"DB_URL=jdbc:postgresql://${taskDb}:5432/images_test", '-e','DB_USERNAME=postgres',
            '-e','DB_PASSWORD','-e','ENTRA_ENABLED=false', "pedidos360-${taskService}:$Tag") | Out-Null
    }
    foreach ($taskService in @('bff','restaurantes','productos')) { Wait-Healthy ($taskPrefix + '-' + $taskService) }
    Write-Output 'SMOKE_OK: tres imágenes saludables, sin puertos publicados ni datos persistentes.'
} finally {
    foreach ($taskName in $taskNames) { & docker rm -f $taskName | Out-Null }
    & docker network rm $taskNetwork | Out-Null
    $env:DB_PASSWORD = $taskOldDbPassword
    $env:POSTGRES_PASSWORD = $taskOldPostgresPassword
}
