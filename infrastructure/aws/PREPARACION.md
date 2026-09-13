# Material privado y comprobacion previa — bloque 2

Los scripts no crean recursos AWS ni usuarios en RDS. No se ha generado material
real de despliegue: las pruebas usan secretos ficticios en un directorio temporal.

## Requisitos

Node.js 22+, JDK 21 (java/keytool) y Docker Compose. Para ejecutar en EC2 instalar
tambien Node y un JDK: Docker solo no basta para este comprobador.
Nunca pegar tokens/secretos en el chat, comandos, historial o archivos de Git.

## Generacion local

Desde infrastructure/aws, definir PAGOS_WORKER_CLIENT_SECRET en el entorno
privado del proceso y P360_CACERTS con lib/security/cacerts del JDK 21. Para
introducir el worker en PowerShell sin mostrarlo ni guardarlo en el historial:

```powershell
$taskWorker = Read-Host 'Secreto del worker (no el ID)' -AsSecureString
$env:PAGOS_WORKER_CLIENT_SECRET = [System.Net.NetworkCredential]::new('', $taskWorker).Password
$env:P360_CACERTS = Join-Path (Split-Path (Split-Path (Get-Command keytool).Source)) 'lib/security/cacerts'
try {
    node deployment.mjs prepare private
    if ($LASTEXITCODE -ne 0) { throw 'Preparacion fallida; revisar sin imprimir secretos.' }
} finally {
    Remove-Item Env:PAGOS_WORKER_CLIENT_SECRET -ErrorAction SilentlyContinue
    $taskWorker = $null
}
```

No ejecutar sobre el material del stack local. La generacion rechaza un directorio
existente, incluso parcial, para evitar sobrescrituras. Ante fallo conservarlo
para diagnostico y elegir un nuevo destino privado; la rotacion es deliberada.

Genera contrasenas aleatorias independientes para seis bases y el keystore;
conserva el worker aportado, sin crearlo ni rotarlo en Entra. Importa autoridades
publicas del JDK en truststore y agrega los siete certificados de servidor.
Certificados internos de 30 dias, SAN por servicio/localhost/127.0.0.1 y serverAuth.
Son confianza privada fijada en el truststore, NO certificados HTTPS publicos.

Windows: restringe ACL de los directorios al usuario actual y SYSTEM.
Linux: directorios 0700 y archivos privados 0400. La preparacion no cambia la
confianza del sistema. El directorio private/ esta ignorado por Git.

## Bundle RDS y configuracion

Descargar el bundle publico exclusivamente de la URL oficial por HTTPS, sin
desactivar verificacion, y guardarlo como private/tls/rds-ca.pem:
https://truststore.pki.rds.amazonaws.com/us-east-1/us-east-1-bundle.pem

No usar un certificado cualquiera que simplemente pase las pruebas de formato.
El preflight comprueba que contiene CA vigentes, pero NO acredita su procedencia:
la descarga HTTPS desde AWS y su gestion son parte de la preparacion operativa.

Completar .env.deploy con valores reales (sin passwords), incluyendo:

```dotenv
AWS_TLS_DIR=./private/tls
AWS_SECRETS_DIR=./private/secrets
```

Las rutas se resuelven respecto de .env.deploy para el wrapper. Mantener este
archivo junto a compose.yml para usar tambien los comandos Compose documentados.
El script exige ECR y endpoint RDS en us-east-1, UUIDs, SHA completo y URLs HTTPS.
No descubre DNS, no comprueba permisos AWS y no demuestra que las URLs existan.
Las passwords generadas deben usarse al crear los usuarios correspondientes en
RDS; NO basta con generar los archivos para que las cuentas existan.

```sh
node deployment.mjs check .env.deploy
```

Verifica campos obligatorios, archivos no vacios, secretos distintos, SAN, uso
serverAuth, vigencia minima siete dias, acceso a keystores y coincidencia de cada
certificado con su entrada de truststore. Comprueba tambien Compose sin mostrar
su configuracion. No hace conexiones RDS/Entra ni ejecuta contenedores.

## Transferencia y permisos en EC2

Transferir solo el material necesario por un canal cifrado autenticado, no por
repositorios, imagenes ni buckets publicos. No usar chmod 777 para solventar
lectura de secretos. Tras ubicar private/ junto al Compose en EC2, validar la ruta
absoluta antes de cambiar permisos. Los directorios deben quedar root:root 0700;
los archivos montados en tls/ y secrets/, UID 10001 y modo 0400. Docker puede
montarlos y los procesos Java con UID 10001 pueden leerlos; nginx no los monta.
No aplicar cambios recursivos a /, home o al repositorio entero.

```sh
sudo node deployment.mjs check-linux .env.deploy
sudo node deployment.mjs up .env.deploy
```

El segundo comando repite el preflight Linux y solo entonces ejecuta up -d
--no-build. Un fallo de configuracion impide el arranque por este wrapper. Docker
Compose directo sigue siendo posible para un administrador, por lo que no debe
usarse para omitir las comprobaciones. No hace pull ni push automaticamente.
Si Docker falla, su salida se omite para evitar filtraciones: inspeccionar estado
y logs cuidadosamente sin publicarlos sin revision.

Limitaciones pendientes: verificacion de descifrado de claves/handshake real,
conectividad y permisos RDS, cadena publica de Entra, salud de las aplicaciones,
HTTPS/API Gateway y recorrido con ambas cuentas. No cerrar el issue por preflight.

## Pruebas

```sh
node --test infrastructure/aws/deployment.test.mjs infrastructure/aws/compose.test.mjs
```

Desde la raiz del repo. Requieren JDK/Node/Docker Compose. Generan claves reales
con credenciales ficticias, rechazan modificaciones y sobrescrituras y limpian
solo el directorio temporal de esa ejecucion. No utilizan .env.deploy real ni AWS.
