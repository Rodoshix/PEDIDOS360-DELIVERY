# Material privado y comprobacion previa — bloque 2

Los scripts no crean recursos AWS ni usuarios en RDS. Las pruebas locales usan
secretos ficticios en un directorio temporal; no inspeccionan secretos reales de EC2.

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

## Completar material cuando RDS ya tiene contrasenas

Si los seis archivos `*_db_password` ya fueron generados en EC2 y aplicados a RDS,
**no usar prepare en otro destino ni regenerar las contrasenas**. Usar la accion
`complete`, con Node 22+, JDK 21 y las mismas variables privadas del preparador:

```sh
node deployment.mjs complete /opt/pedidos360/private
```

Ejecutar en un proceso con permisos para leer el directorio (root en EC2).
Introducir el secreto real del worker de Entra de forma oculta en ese proceso;
no incluirlo como argumento ni confundirlo con la contrasena maestra de RDS.
El entorno de sudo puede descartar variables: definirlas dentro del proceso
privilegiado, no asumir que se conservan automaticamente.

`complete` exige que el directorio contenga solamente `secrets/` con los seis
archivos esperados: 64 caracteres hexadecimales, distintos, sin saltos de linea,
sin enlaces simbolicos y con permisos privados en Linux. Agrega certificados,
truststore, contrasena TLS y secreto del worker sin reescribir los archivos DB.
Rechaza material parcial o ya completado. Si falla despues de empezar, conservar
todos los archivos y diagnosticar; no borrar el directorio ni regenerar DB.
Las pruebas comprueban que contenido y fecha de modificacion de los seis archivos
DB se mantienen. Esto no demuestra conexion real con Entra ni handshake interno.

Descargar despues el bundle RDS y comprobar el material antes de ajustar los
propietarios a UID 10001 para Docker, como se describe a continuacion.

### Transferencia del worker desde el PC al laboratorio

`transfer-worker.mjs` es una herramienta de una sola ejecucion para esta instancia
del laboratorio, no un desplegador generico. Usa AWS CLI con el perfil local
`pedidos360-lab`, verifica la cuenta `694507973514` y dirige SSM Run Command a
`i-0c1b0042eb32cf54e` en `us-east-1`. Requiere Node 22 y JDK 21 instalados en EC2.
Lee exclusivamente la variable worker del archivo ignorado
`backend/services/pagos-service/.env.worker.local`, sin imprimir su contenido.

```sh
node infrastructure/aws/transfer-worker.mjs
```

Primero EC2 genera una clave RSA 4096 temporal en un directorio root privado y
devuelve solo la clave publica. El PC cifra el worker con RSA-OAEP SHA-256 antes
de enviarlo: los parametros y registros de SSM contienen ciphertext, nunca el
valor original. EC2 descifra en memoria, ejecuta `complete`, valida certificados
y compara internamente las huellas de los seis secretos DB antes y despues.
El codigo del preparador se transmite junto con su SHA-256; no requiere push ni
publicar archivos privados. La autenticacion del canal depende del perfil AWS/SSM.

Al terminar correctamente elimina solo la clave de transferencia, la copia
temporal del preparador y su directorio vacio. El worker queda en el archivo
privado definitivo, necesario para los contenedores. Ante fallo conserva el
material para diagnostico: revisar el ID SSM impreso, no repetir automaticamente.
No configura RDS, no verifica la validez del worker en Entra y no arranca Docker.
No descarga todavia el bundle de RDS ni cambia los archivos al UID de la aplicacion.

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
node --test infrastructure/aws/deployment.test.mjs infrastructure/aws/compose.test.mjs infrastructure/aws/transfer-worker.test.mjs
```

Desde la raiz del repo. Requieren JDK/Node/Docker Compose. Generan claves reales
con credenciales ficticias, rechazan modificaciones y sobrescrituras y limpian
solo el directorio temporal de esa ejecucion. No utilizan .env.deploy real ni AWS.
