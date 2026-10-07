# RabbitMQ AWS â€” #71

Artefactos reproducibles de la infraestructura validada en AWS Academy. No ejecutan
API AWS ni activan consumidores. Entrega 1 conserva su Compose y coordinaciÃ³n HTTP.
El broker es un nodo Ãºnico: quorum aporta persistencia, **no alta disponibilidad**.

## Estructura y fuente de verdad

```text
rabbitmq/
  compose.rabbitmq.yml       # stack independiente, sin ports
  rabbitmq.conf              # RabbitMQ 4.1.8, TLS y lÃ­mites
  enabled_plugins           # Management solamente
  bootstrap.sh              # guard de filesystem en cada arranque
  .env.example              # placeholders; PREPARED_ONLY
  topology/README.md        # inventario, permisos y policies de #69
  scripts/                  # operaciones, adaptador HTTPS, probes y tests
  rollback/README.md
```

`scripts/platform_source.py` importa `../../rabbitmq/platform_control.py`, fuente
versionada de #69. No hay segunda copia del inventario. Conservar la estructura del
repositorio al instalar ambos directorios pÃºblicos en un checkout separado de
Entrega 1; no sobrescribir su despliegue. El helper Docker monta esa fuente en
read-only. `Dockerfile.tools` fija Python por digest y pika 1.3.2; construirlo antes
de la ventana y registrar su image ID en `EP2_AMQP_TOOLS_IMAGE`. No instala paquetes
durante provisioning. El host necesita Linux, Python 3, Docker Compose v2 con
`up --wait`, findmnt, lsblk y stat; OpenSSL se verifica dentro de la imagen fijada.

## EBS: preparaciÃ³n operativa previa

Preparar, con autorizaciÃ³n AWS, **10 GiB gp3 cifrado**, en la misma AZ que la EC2,
`DeleteOnTermination=false`. Confirmar el volume ID en el serial NVMe antes de
formatear: nunca inferir el dispositivo por su posiciÃ³n (`nvme1n1` puede cambiar).
Este paquete **no crea, adjunta, formatea ni modifica** volÃºmenes.

Sobre un volumen nuevo confirmado vacÃ­o, el operador crea ext4, registra UUID y
monta `/opt/pedidos360/rabbitmq-data`. Usar fstab por UUID, por ejemplo:

```text
UUID=<UUID_DEL_EBS_VERIFICADO> /opt/pedidos360/rabbitmq-data ext4 defaults,nofail,x-systemd.device-timeout=30s 0 2
```

`nofail` permite recuperar el host si falta el EBS; **no permite arrancar el broker
en root**. El preflight exige mountpoint exacto, UUID, ext4, serial del EBS,
filesystem ID distinto del root y espacio libre >=3 GiB. Requiere >=2816 MiB de
MemAvailable (768 MiB broker + 2 GiB margen); no garantiza capacidad CPU.
Capturar `stat -f -c %i` solo sobre el EBS ya verificado para `EP2_EXPECTED_FS_ID`.
El bootstrap compara ese ID en **cada** arranque, incluido reboot/auto-restart.

Obtener UID/GID con `id -u rabbitmq` / `id -g rabbitmq` en la imagen fijada, asignar
el directorio de datos a esa identidad y comprobar escritura con preflight. No
hay chown recursivo automÃ¡tico. ConfiguraciÃ³n pÃºblica `rabbitmq.conf`, plugins y
bootstrap deben ser legibles (0644); el fallo de permisos inicial detectado en la
validaciÃ³n AWS quedÃ³ resuelto con esta separaciÃ³n de material pÃºblico/privado.

## TLS y archivos privados

```text
/opt/pedidos360/private-ep2/
  tls/ca.pem
  tls/server.pem
  tls/server-key.pem
  secrets/<archivo por cuenta>
  secrets/ERLANG_COOKIE
  secrets/PEDIDOS360_ACTOR_SECRET
  secrets/PEDIDOS360_ACTOR_KEY_ID
```

Generar la clave del servidor y CSR fuera de Git, solicitar certificado firmado
por la CA privada con `SAN DNS:p360-rabbitmq`, EKU serverAuth y vigencia adecuada.
Guardar la **clave de la CA fuera de la EC2**. Instalar Ãºnicamente cadena de
confianza y certificado/clave servidor en el Ã¡rbol privado. Verificar cadena,
SAN, coincidencia clave/certificado y vencimiento; preflight exige al menos siete
dÃ­as restantes. Renovar mediante una ventana controlada, verificar health y TLS.
Los certificados definitivos, CSR y claves no forman parte del repositorio.

AMQPS **5671** y Management HTTPS **15671**, TLS 1.2/1.3, `verify_peer`.
5672/15672 deshabilitados; no se publica ningÃºn puerto del host en AWS.
`fail_if_no_peer_cert=false` conserva autenticaciÃ³n por password sobre TLS con
validaciÃ³n de servidor; **no exige mTLS**. Todo cliente verifica CA y hostname.
No abrir Security Groups para AMQP/Management; operar desde la red Docker interna.
No usar `curl -k`, credenciales en URL ni proxies/redirects en Management.

Archivos de contraseÃ±as (una lÃ­nea >=24 caracteres): `BOOTSTRAP_PASSWORD`,
`PAGOS_PUBLISHER_PASSWORD`, `PEDIDOS_CONSUMER_PASSWORD`, `BFF_PASSWORD`,
`PEDIDOS_CARRITO_PUBLISHER_PASSWORD`, `REPLAY_PASSWORD`, `ADMIN_DEMO_PASSWORD`,
`USUARIOS_CONSUMER_PASSWORD`, `RESTAURANTES_CONSUMER_PASSWORD`,
`PRODUCTOS_CONSUMER_PASSWORD`, `CARRITO_CONSUMER_PASSWORD`, `PAGOS_CONSUMER_PASSWORD`.
La lista canÃ³nica proviene de `accounts()` de #69. Cookie >=24 caracteres;
actor secret >=32 y key ID no vacÃ­o: estÃ¡n reservados para integraciÃ³n posterior,
**no se conectan al BFF desde este paquete**.

Secretos sin permisos para others; directorios privados con acceso mÃ­nimo.
La clave TLS debe ser legible por la identidad RabbitMQ verificada (por ejemplo
root:grupo-rabbitmq 0640), nunca world-readable. Preflight comprueba legibilidad
con esa identidad. El bootstrap no reemplaza una cookie persistida distinta.
La contraseÃ±a bootstrap pasa al proceso de RabbitMQ en entorno interno para
inicializaciÃ³n; no a argumentos, Compose ni logs. Docker/root siguen siendo una
frontera privilegiada. RotaciÃ³n de cuentas persistidas requiere procedimiento
explÃ­cito: cambiar un archivo bootstrap no rota automÃ¡ticamente el usuario.

## Runbook

Desde este directorio, preparar `.env` **local e ignorado** desde `.env.example`.
Completar variables de volumen, UUID, filesystem ID, paths y tools image ID;
`AWS_APPROVED` se usa solo en una ventana autorizada, `LOCAL_TEST` nunca en EC2.
Mantener:

```dotenv
PEDIDOS360_COORDINATION_MODE=HTTP
PEDIDOS360_RELIABILITY_PLATFORM_READY=false
PEDIDOS360_RELAY_MODE=DISABLED
PEDIDOS360_DECLARE_TOPOLOGY=false
```

Estos flags son guardas del paquete; no modifican los servicios de Entrega 1.
TambiÃ©n comprobar su configuraciÃ³n real antes de operar; #70 sigue desactivado.

```sh
python3 -B scripts/tools.py preflight
python3 -B scripts/tools.py start
python3 -B scripts/tools.py provision
python3 -B scripts/tools.py verify
python3 -B scripts/tools.py status
python3 -B scripts/tools.py measure
python3 -B scripts/tools.py stop
python3 -B scripts/tools.py rollback
```

Wrappers `.sh` equivalentes disponibles en `scripts/`. Preflight de host no
despliega: inspecciona red/mount/permisos/TLS/flags y usa contenedores efÃ­meros sin
red para comprobar UID/lectura. Management preflight hace Ãºnicamente GET.
Start levanta **solo rabbitmq**, `--no-deps`. Provision ejecuta preflight remoto y
luego aplica aditivamente la fuente #69 vÃ­a HTTPS: sin DELETE/purge/conversiÃ³n
classicâ†’quorum. Incompatibilidad bloquea, requiere inspecciÃ³n; no se repara
destructivamente. Verify confirma health, TLS-only, puertos, topologÃ­a/policies,
cuentas/permisos. Status tambiÃ©n muestra filesystem del contenedor.

Measure muestrea Linux y contenedores del proyecto Entrega 1 + broker, por defecto
10 intervalos de 30 segundos; no altera servicios ni consulta AWS. Para otra
ventana: `python3 -B scripts/measure.py --samples 10 --interval 30`.
Obtener CPU credits aparte mediante observaciÃ³n AWS autorizada. La mediciÃ³n no
prueba carga funcional completa. No guardar su salida privada en Git.

Para comprobar persistencia con **reinicio de RabbitMQ autorizado**, usar:

```sh
EP2_ALLOW_PERSISTENCE_RESTART=1 python3 -B scripts/test_persistence.py
```

El probe declara exclusivamente exchange/queue `demo.ep2.persistence*` en el
vhost sandbox; queue quorum durable separada de las 21 oficiales. Exige queue
vacÃ­a/sin consumers, publica mensaje persistente con confirm mandatory, conserva
testigo local ignorado, stop/up solo broker, verifica y recupera el mismo ID con
ACK cercado por un RPC posterior. No purga ni borra. Un fallo deja evidencia y
mensaje para inspecciÃ³n; no repetir a ciegas con otro ID.

Reboot EC2 **no se automatiza aquÃ­**. En una ventana autorizada, preparar el
testigo persistente, confirmar flags, reiniciar, comprobar EBS antes de broker,
TLS/topologÃ­a/alarms y health de aplicaciones; luego recuperar/ACK del testigo.
EC2 RUNNING o SSM disponible no prueban disponibilidad del stack. Reintentar la
regresiÃ³n HTTP autenticada tras health estable; no activar #70 para probar EBS.

Stop/rollback detiene solo broker y preserva container, cookie, EBS y red externa.
Ver [rollback](rollback/README.md) y [evidencia sanitizada](../../../docs/ep2/AWS-RABBITMQ.md).

## ValidaciÃ³n local aislada

Python con pika 1.3.2 y OpenSSL disponible (`EP2_TEST_OPENSSL` permite ruta explÃ­cita):

```sh
python3 -B scripts/test_guards.py
python3 -B scripts/local_fixture.py
docker network create --internal pedidos360-ep2-aws-tests
export EP2_ENV_FILE="$PWD/test-local/.env"
python3 -B scripts/tools.py start
python3 -B scripts/tools.py provision
EP2_PLATFORM_TESTS=1 python3 -B scripts/test_tls_platform.py
EP2_ALLOW_PERSISTENCE_RESTART=1 python3 -B scripts/test_persistence.py
python3 -B scripts/tools.py stop
```

Fixture genera exclusivamente secretos/certificados locales de prueba ignorados.
Proyecto/red nuevos, puertos **loopback 5782/15782** solo mediante overlay de test.
Reutiliza 13 tests #69 vÃ­a import, aÃ±ade cuatro pruebas TLS, y adapta `start --wait`
a `up --no-deps --wait` para compatibilidad Compose. No ejecutar estos tests sobre
AWS ni sobre colas usadas por aplicaciones. Las pruebas validan infraestructura,
no consumers Java, autorizaciÃ³n Entra ni E2E de tarjeta/efectivo por RabbitMQ.
