# RabbitMQ AWS — #71

Artefactos reproducibles de la infraestructura validada en AWS Academy. No ejecutan
API AWS ni activan consumidores. Entrega 1 conserva su Compose y coordinación HTTP.
El broker es un nodo único: quorum aporta persistencia, **no alta disponibilidad**.

## Estructura y fuente de verdad

```text
rabbitmq/
  compose.rabbitmq.yml       # stack independiente, sin ports
  rabbitmq.conf              # RabbitMQ 4.1.8, TLS y límites
  enabled_plugins           # Management solamente
  bootstrap.sh              # guard de filesystem en cada arranque
  .env.example              # placeholders; PREPARED_ONLY
  topology/README.md        # inventario, permisos y policies de #69
  scripts/                  # operaciones, adaptador HTTPS, probes y tests
  rollback/README.md
```

`scripts/platform_source.py` importa `../../rabbitmq/platform_control.py`, fuente
versionada de #69. No hay segunda copia del inventario. Conservar la estructura del
repositorio al instalar ambos directorios públicos en un checkout separado de
Entrega 1; no sobrescribir su despliegue. El helper Docker monta esa fuente en
read-only. `Dockerfile.tools` fija Python por digest y pika 1.3.2; construirlo antes
de la ventana y registrar su image ID en `EP2_AMQP_TOOLS_IMAGE`. No instala paquetes
durante provisioning. El host necesita Linux, Python 3, Docker Compose v2 con
`up --wait`, findmnt, lsblk y stat; OpenSSL se verifica dentro de la imagen fijada.

## EBS: preparación operativa previa

Preparar, con autorización AWS, **10 GiB gp3 cifrado**, en la misma AZ que la EC2,
`DeleteOnTermination=false`. Confirmar el volume ID en el serial NVMe antes de
formatear: nunca inferir el dispositivo por su posición (`nvme1n1` puede cambiar).
Este paquete **no crea, adjunta, formatea ni modifica** volúmenes.

Sobre un volumen nuevo confirmado vacío, el operador crea ext4, registra UUID y
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
hay chown recursivo automático. Configuración pública `rabbitmq.conf`, plugins y
bootstrap deben ser legibles (0644); el fallo de permisos inicial detectado en la
validación AWS quedó resuelto con esta separación de material público/privado.

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
Guardar la **clave de la CA fuera de la EC2**. Instalar únicamente cadena de
confianza y certificado/clave servidor en el árbol privado. Verificar cadena,
SAN, coincidencia clave/certificado y vencimiento; preflight exige al menos siete
días restantes. Renovar mediante una ventana controlada, verificar health y TLS.
Los certificados definitivos, CSR y claves no forman parte del repositorio.

AMQPS **5671** y Management HTTPS **15671**, TLS 1.2/1.3, `verify_peer`.
5672/15672 deshabilitados; no se publica ningún puerto del host en AWS.
`fail_if_no_peer_cert=false` conserva autenticación por password sobre TLS con
validación de servidor; **no exige mTLS**. Todo cliente verifica CA y hostname.
No abrir Security Groups para AMQP/Management; operar desde la red Docker interna.
No usar `curl -k`, credenciales en URL ni proxies/redirects en Management.

Archivos de contraseñas (una línea >=24 caracteres): `BOOTSTRAP_PASSWORD`,
`PAGOS_PUBLISHER_PASSWORD`, `PEDIDOS_CONSUMER_PASSWORD`, `BFF_PASSWORD`,
`PEDIDOS_CARRITO_PUBLISHER_PASSWORD`, `REPLAY_PASSWORD`, `ADMIN_DEMO_PASSWORD`,
`USUARIOS_CONSUMER_PASSWORD`, `RESTAURANTES_CONSUMER_PASSWORD`,
`PRODUCTOS_CONSUMER_PASSWORD`, `CARRITO_CONSUMER_PASSWORD`, `PAGOS_CONSUMER_PASSWORD`.
La lista canónica proviene de `accounts()` de #69. Cookie >=24 caracteres;
actor secret >=32 y key ID no vacío: están reservados para integración posterior,
**no se conectan al BFF desde este paquete**.

Secretos sin permisos para others; directorios privados con acceso mínimo.
La clave TLS debe ser legible por la identidad RabbitMQ verificada (por ejemplo
root:grupo-rabbitmq 0640), nunca world-readable. Preflight comprueba legibilidad
con esa identidad. El bootstrap no reemplaza una cookie persistida distinta.
La contraseña bootstrap pasa al proceso de RabbitMQ en entorno interno para
inicialización; no a argumentos, Compose ni logs. Docker/root siguen siendo una
frontera privilegiada. Rotación de cuentas persistidas requiere procedimiento
explícito: cambiar un archivo bootstrap no rota automáticamente el usuario.

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
También comprobar su configuración real antes de operar; #70 sigue desactivado.

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
despliega: inspecciona red/mount/permisos/TLS/flags y usa contenedores efímeros sin
red para comprobar UID/lectura. Management preflight hace únicamente GET.
Start levanta **solo rabbitmq**, `--no-deps`. Provision ejecuta preflight remoto y
luego aplica aditivamente la fuente #69 vía HTTPS: sin DELETE/purge/conversión
classic→quorum. Incompatibilidad bloquea, requiere inspección; no se repara
destructivamente. Verify confirma health, TLS-only, puertos, topología/policies,
cuentas/permisos. Status también muestra filesystem del contenedor.

Measure muestrea Linux y contenedores del proyecto Entrega 1 + broker, por defecto
10 intervalos de 30 segundos; no altera servicios ni consulta AWS. Para otra
ventana: `python3 -B scripts/measure.py --samples 10 --interval 30`.
Obtener CPU credits aparte mediante observación AWS autorizada. La medición no
prueba carga funcional completa. No guardar su salida privada en Git.

Para comprobar persistencia con **reinicio de RabbitMQ autorizado**, usar:

```sh
EP2_ALLOW_PERSISTENCE_RESTART=1 python3 -B scripts/test_persistence.py
```

El probe declara exclusivamente exchange/queue `demo.ep2.persistence*` en el
vhost sandbox; queue quorum durable separada de las 21 oficiales. Exige queue
vacía/sin consumers, publica mensaje persistente con confirm mandatory, conserva
testigo local ignorado, stop/up solo broker, verifica y recupera el mismo ID con
ACK cercado por un RPC posterior. No purga ni borra. Un fallo deja evidencia y
mensaje para inspección; no repetir a ciegas con otro ID.

Reboot EC2 **no se automatiza aquí**. En una ventana autorizada, preparar el
testigo persistente, confirmar flags, reiniciar, comprobar EBS antes de broker,
TLS/topología/alarms y health de aplicaciones; luego recuperar/ACK del testigo.
EC2 RUNNING o SSM disponible no prueban disponibilidad del stack. Reintentar la
regresión HTTP autenticada tras health estable; no activar #70 para probar EBS.

Stop/rollback detiene solo broker y preserva container, cookie, EBS y red externa.
Ver [rollback](rollback/README.md) y [evidencia sanitizada](../../../docs/ep2/AWS-RABBITMQ.md).

## Validación local aislada

Python con pika 1.3.2 y OpenSSL disponible (`EP2_TEST_OPENSSL` permite ruta explícita):

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
Reutiliza 13 tests #69 vía import, añade cuatro pruebas TLS, y adapta `start --wait`
a `up --no-deps --wait` para compatibilidad Compose. No ejecutar estos tests sobre
AWS ni sobre colas usadas por aplicaciones. Las pruebas validan infraestructura,
no consumers Java, autorización Entra ni E2E de tarjeta/efectivo por RabbitMQ.
