# Local integration only. No AWS commands, deployment, flags or business data.
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$testRepo = Join-Path ([System.IO.Path]::GetTempPath()) ('p360-v080-m2-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRepo | Out-Null
Write-Host "Fresh Maven repository: $testRepo"
$steps = @(
    @{pom='backend/shared/p360-messaging-core/pom.xml'; args=@('clean','install','-Dtest=IdentityProofDeadlineLoggingTests')},
    @{pom='backend/bff/pom.xml'; args=@('clean','install','-DskipTests')},
    @{pom='backend/services/carrito-service/pom.xml'; args=@('clean','verify','-Pbff-http-integration','-Dtest=CarritoServiceTests,CarritoRabbitHttpGuardTests,CarritoAckLoggingTests,CarritoCommandIntegrationTests')}
)
foreach ($step in $steps) {
    $argsMaven = @('-B','-ntp',"-Dmaven.repo.local=$testRepo",'-f',(Join-Path $repoRoot $step.pom)) + $step.args
    & mvn @argsMaven
    if ($LASTEXITCODE -ne 0) { throw "Maven validation failed" }
}
Push-Location $repoRoot
try {
    & node --test infrastructure/aws/edge/index.test.mjs infrastructure/aws/edge/admission.test.mjs
    if ($LASTEXITCODE -ne 0) { throw "Edge validation failed" }
    & node --test infrastructure/aws/compose.test.mjs infrastructure/aws/ep2-prepared.test.mjs infrastructure/aws/ep2-minimal.test.mjs
    if ($LASTEXITCODE -ne 0) { throw "Compose validation failed" }
} finally { Pop-Location }
# Keep the temporary repository for inspection; no recursive deletion is performed.
