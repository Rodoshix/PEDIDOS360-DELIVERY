$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$service = Join-Path $repository 'backend/services/pedidos-service'
$evidence = Join-Path $PSScriptRoot 'evidence'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
Push-Location $service
try {
    & ./mvnw.cmd -B -ntp -DskipTests package dependency:build-classpath '-Dmdep.outputFile=target/platform-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Maven build/classpath failed' }
} finally { Pop-Location }
Push-Location (Join-Path $repository 'backend/services/pagos-service')
try {
    & ./mvnw.cmd -B -ntp -DskipTests compile
    if ($LASTEXITCODE -ne 0) { throw 'Pagos publisher compilation failed' }
} finally { Pop-Location }
$classpath = (Get-Content -Raw -LiteralPath (Join-Path $service 'target/platform-classpath.txt')).Trim()
# Include original Pagos classes only, never its application.yml/Flyway migrations.
$pagosClasses = Join-Path $evidence 'pagos-probe-classes.jar'
& jar --create --file $pagosClasses -C (Join-Path $repository 'backend/services/pagos-service/target/classes') cl
if ($LASTEXITCODE -ne 0) { throw 'Publisher probe class-only archive failed' }
$classpath = (Join-Path $service 'target/classes') + ';' + $pagosClasses + ';' + $classpath
$compileArgs = Join-Path $evidence 'compile.args'
$runArgs = Join-Path $evidence 'run.args'
# JVM argument files avoid Windows command length limits; files contain paths, no secrets.
@('-cp', ('"' + $classpath.Replace('\','/') + '"'), '-d', ('"' + $evidence.Replace('\','/') + '"'), ('"' + (Join-Path $PSScriptRoot 'PlatformApplicationCheck.java').Replace('\','/') + '"')) | Set-Content -LiteralPath $compileArgs -Encoding utf8NoBOM
& javac ('@' + $compileArgs)
if ($LASTEXITCODE -ne 0) { throw 'Infrastructure probe compilation failed' }
@('-cp', ('"' + ($evidence + ';' + $classpath).Replace('\','/') + '"'), 'cl.duoc.pedidos360.pedidos.messaging.PlatformApplicationCheck', ('"' + $PSScriptRoot.Replace('\','/') + '"')) | Set-Content -LiteralPath $runArgs -Encoding utf8NoBOM
Push-Location $service
try {
    & java ('@' + $runArgs)
    if ($LASTEXITCODE -ne 0) { throw 'Actual application/platform check failed' }
} finally { Pop-Location }
