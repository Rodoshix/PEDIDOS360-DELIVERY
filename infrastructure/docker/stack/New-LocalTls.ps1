# Genera identidades TLS solo para este stack local. No instala certificados en Windows.
param([string]$OutputDirectory = (Join-Path $PSScriptRoot 'tls'))
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:STACK_TLS_PASSWORD) -or $env:STACK_TLS_PASSWORD.Length -lt 12) {
    throw 'Define STACK_TLS_PASSWORD con al menos 12 caracteres en el entorno del proceso.'
}
$taskDestination = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $taskDestination) { throw 'El directorio TLS ya existe; no se sobrescribirá.' }
$taskKeytool = (Get-Command keytool -ErrorAction Stop).Source
$taskJavaRoot = Split-Path (Split-Path $taskKeytool -Parent) -Parent
$taskCacerts = Join-Path $taskJavaRoot 'lib/security/cacerts'
if (-not (Test-Path -LiteralPath $taskCacerts)) { throw 'No se encontró cacerts del JDK.' }
New-Item -ItemType Directory -Path $taskDestination | Out-Null
function Invoke-KeytoolChecked([string[]]$Arguments) {
    & $taskKeytool @Arguments 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Falló keytool. El directorio parcial se conserva para diagnóstico.' }
}
# El truststore contiene certificados PÚBLICOS, incluidos los necesarios para Entra.
Invoke-KeytoolChecked @('-importkeystore','-noprompt','-srckeystore',$taskCacerts,
    '-srcstorepass','changeit','-destkeystore',"$taskDestination/truststore.p12",
    '-deststoretype','PKCS12','-deststorepass','changeit')
foreach ($taskService in @('bff','usuarios','restaurantes','productos','carrito','pedidos','pagos')) {
    Invoke-KeytoolChecked @('-genkeypair','-alias',$taskService,'-keyalg','RSA','-keysize','2048',
        '-validity','30','-dname',"CN=$taskService",
        '-ext',"SAN=dns:$taskService,dns:localhost,ip:127.0.0.1",
        '-ext','EKU=serverAuth','-storetype','PKCS12',
        '-keystore',"$taskDestination/$taskService.p12",'-storepass:env','STACK_TLS_PASSWORD',
        '-keypass:env','STACK_TLS_PASSWORD','-noprompt')
    Invoke-KeytoolChecked @('-exportcert','-rfc','-alias',$taskService,
        '-keystore',"$taskDestination/$taskService.p12",'-storepass:env','STACK_TLS_PASSWORD',
        '-file',"$taskDestination/$taskService.crt")
    Invoke-KeytoolChecked @('-importcert','-noprompt','-alias',"pedidos360-$taskService",
        '-file',"$taskDestination/$taskService.crt",'-keystore',"$taskDestination/truststore.p12",
        '-storepass','changeit')
}
Write-Output 'TLS generado (30 días). Claves privadas fuera de Git; no se modificó la confianza del sistema.'
