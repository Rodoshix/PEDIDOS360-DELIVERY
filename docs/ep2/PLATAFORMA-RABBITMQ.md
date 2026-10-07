# Plataforma RabbitMQ local — #69

## Alcance y decisiones efectivas

Plataforma reproducible para los seis servicios docentes de la adenda; no incorpora
consumers #77–#82, RabbitAdmin #67, corte #70 ni AWS #71. PR #84 aporta reliability
de Pedidos; #68 fue cerrado administrativamente después de verificar su integración.
HTTP continúa predeterminado y platform-ready=false en las aplicaciones existentes.
Solo el probe aislado habilita RABBITMQ/platform-ready=true.

RabbitMQ **4.1.8**, imagen fijada por versión y digest en
[compose.yml](../../infrastructure/rabbitmq/compose.yml). Un nodo, volumen nombrado,
hostname rabbitmq y nodename rabbit@rabbitmq estables. **Quorum de un miembro no
proporciona HA de host**; no se requiere cluster para 21 colas.

Se elige quorum para las 21 colas: persistencia consistente y retención/dead-lettering
comprobables, sin mezclar tipos de DLQ ni inferir quorum de durable. No es una
migración de datos previos: el broker local nuevo está separado del stack existente.
El vhost negocio y default de broker son quorum para que la aplicación pueda
redeclarar sin x-queue-type. Sandbox usa default classic explícito.

El inventario ejecutable está centralizado en
[platform_control.py](../../infrastructure/rabbitmq/platform_control.py): inventory()
produce colas, argumentos, bindings y policies; accounts() produce permisos.
`inventory` exporta JSON sin secretos. Provisionamiento aditivo mediante Management
API después de preflight completamente de lectura. No hay import destructivo,
DELETE de queues, purge, conversión de tipos ni autoimport al reiniciar.

## Exchanges y bindings

Los siete exchanges personalizados son **direct, durable=true, auto_delete=false,
internal=false, sin argumentos**:

| Exchange | Destinos |
|---|---|
| p360.pedidos.commands | Principal Pedidos; pedido.confirmar.v1 |
| p360.pedidos.retry | Retries Pedidos 5/30/120; pedido.confirmar.retry.5s/30s/120s |
| p360.pedidos.dlx | DLQ Pedidos; pedido.confirmar.failed |
| p360.commands | Principal Carrito; carrito.vaciar-por-pedido.v1 |
| p360.queries | Principales Usuarios/Restaurantes/Productos/Pagos |
| p360.retry | Cinco retries cortos con keys de la adenda |
| p360.dlx | Cinco DLQ independientes con keys .failed |

**20 bindings personalizados** (6 principales + 8 retries + 6 DLQ). BFF recibe por
exchange predeterminado `""`, routing key=`p360.bff.consultas.respuestas.q` tomada de
replyTo: su binding implícito no suma un octavo exchange personalizado.
No se crea p360.events. La matriz al final especifica cada binding y retorno.

## Argumentos y policies

Todas las queues: durable=true, auto_delete=false, exclusive=false y argumento
`x-queue-type=quorum`. Principal/DLQ Pedidos no reciben otros argumentos inmutables.
Las tres retry Pedidos conservan **exactamente** x-message-ttl 5000/30000/120000,
x-dead-letter-exchange=p360.pedidos.commands y
x-dead-letter-routing-key=pedido.confirmar.v1 del código integrado.

Una policy por cola, nombre `ep2-<queue>`, regex anclada escapada a **un nombre exacto**,
priority=10, apply-to=quorum_queues. Así no se extiende el límite 5 a DLQ. Una policy
ajena/operator policy que afecte estas colas bloquea el preflight; no se sustituye
silenciosamente. Verificar effective_policy_definition y argumentos, no solo nombre.

| Clase | Policy efectiva |
|---|---|
| 6 principales | DLX y failed key de dominio, dead-letter-strategy=at-least-once, overflow=reject-publish, delivery-limit=5 |
| 3 retries Pedidos | dead-letter-strategy=at-least-once, overflow=reject-publish, delivery-limit=-1; TTL/retorno en argumentos originales |
| 5 retries cortos | message-ttl configurable inicialmente 1000; DLX funcional/key original, at-least-once, reject-publish, delivery-limit=-1 |
| 6 DLQ | delivery-limit=-1, overflow=reject-publish; **sin TTL/DLX ni retorno automático** |
| BFF respuestas | message-ttl=60000, max-length=1000, reject-publish, delivery-limit=5; sin DLX/retry propia |

Las cinco principales nuevas reciben la misma protección mínima de redelivery del
broker; no copian los tres retries de negocio de Pedidos. Su única transferencia
programada a retry pertenece a #77/#82, no la realiza la plataforma. TTL corto se
configura con RABBITMQ_SIMPLE_RETRY_MS (100..30000 ms) en **policy**, evitando cambiar
argumentos inmutables. Valor aprobado/probado=1000 ms; coordinar cualquier cambio.
Nombres .1s conservan la identidad aprobada. Pedidos mantiene sus tres TTL exactos.

delivery-limit limita reentregas por canal/settlement, **no retry-count** de aplicación.
Retries sin consumer no necesitan protección por lecturas; DLQ conserva fallos tras
25 cierres de canal comprobados. No se añade x-delivery-limit a declaraciones Java.

La cola BFF limita respuestas huérfanas a 60s/1000 pendientes, con rechazo al publisher
si llena. #77 debe además asignar expiration según presupuesto restante, validar
expiresAt/correlationId/replyTo y retirar respuestas tardías. La plataforma no renueva
deadlines, no firma autorización y no implementa multiplexado de varias instancias BFF.
Respuesta expirada o con redelivery agotada se retira: no hay DLQ BFF en la arquitectura.

## Vhosts, usuarios y mínimo privilegio

Negocio: pedidos360. Sandbox: pedidos360-admin-demo. Doce usuarios, trece entradas de
permisos: solo el operador bootstrap tiene ambos vhosts. Aplicaciones sin tags,
sin configure; admin-demo tag management (no administrator), acceso solo sandbox.
Bootstrap administrador es para provisionamiento/pruebas operativas; **nunca aplicación**.

`^$` deniega todo. Los nombres de recursos permitidos se usan como regex exactas
ancladas escapadas. Permisos por recurso/exchange, no por routing key de direct exchange.

| Usuario | Vhost | configure | write | read |
|---|---|---|---|---|
| p360-bootstrap | pedidos360 | ^p360\. | ^p360\. | ^p360\. |
| p360-bootstrap | pedidos360-admin-demo | ^demo\. | ^demo\. | ^demo\. |
| p360-pagos-publisher | pedidos360 | ^$ | p360.pedidos.commands | ^$ |
| p360-pedidos-consumer | pedidos360 | ^$ | p360.pedidos.retry | p360.pedidos.confirmacion.q |
| p360-bff | pedidos360 | ^$ | p360.queries | p360.bff.consultas.respuestas.q |
| p360-pedidos-carrito-publisher | pedidos360 | ^$ | p360.commands | ^$ |
| p360-usuarios-consumer | pedidos360 | ^$ | p360.retry / amq.default | p360.usuarios.consultas.q |
| p360-restaurantes-consumer | pedidos360 | ^$ | p360.retry / amq.default | p360.restaurantes.consultas.q |
| p360-productos-consumer | pedidos360 | ^$ | p360.retry / amq.default | p360.productos.consultas.q |
| p360-pagos-consumer | pedidos360 | ^$ | p360.retry / amq.default | p360.pagos.consultas.q |
| p360-carrito-consumer | pedidos360 | ^$ | p360.retry | p360.carrito.vaciado.q |
| p360-replay | pedidos360 | ^$ | p360.pedidos.commands / p360.commands | Solo las 6 DLQ |
| p360-admin-demo | pedidos360-admin-demo | ^demo\. | ^demo\. | ^demo\. |

Las cuatro consultas tienen permiso amq.default para responder; RabbitMQ autoriza el
exchange, no permite restringir replyTo por nombre de queue mediante este permiso.
**#77 debe validar replyTo exacto y autorización verificable**. p360.retry también es
compartido: validar dominio/destino en cada publisher de aplicación. No se agregan
exchanges para esquivar esto. Replay de consultas vencidas no está autorizado a
p360.queries: iniciar solicitud nueva autenticada, sin resucitar deadlines.

Pagos publisher y Pagos query tienen credenciales separadas para roles distintos.
#81 deberá coordinar conexiones, o proponer explícitamente un usuario combinado de
ambos permisos; no se altera su código aquí. Pedidos → Carrito tiene publisher separado
preparado para #82. Estas identidades no activan consumers futuros por sí solas.

## Compatibilidad real con las aplicaciones

Pedidos no fija tipo ni DLX en la principal; sus retries sí fijan TTL/DLX/key y la DLQ
no fija delivery-limit. Default quorum + argumentos compatibles permiten redeclaración
real del conjunto de beans de aplicación sin PRECONDITION_FAILED.

**Configuración de ejecución futura con usuario restringido:**

```text
RABBITMQ_HOST=<host privado o localhost para prueba>
RABBITMQ_PORT=5679 (host local; 5672 dentro de red Docker)
RABBITMQ_VHOST=pedidos360
RABBITMQ_USERNAME=p360-pedidos-consumer
RABBITMQ_PASSWORD=<secreto privado>
SPRING_RABBITMQ_DYNAMIC=false
```

RabbitAdmin automático intentaría declarar recursos y requiere configure/read/write
adicionales. Deshabilitarlo mediante propiedad existente de Spring evita dar permisos
de administración a la aplicación. @RabbitListener sigue verificando pasivamente y
consumiendo la cola ya creada. Java no cambió. El probe usa un RabbitAdmin **operativo
separado** con bootstrap para contrastar los beans actuales: no ejecuta la aplicación
con credenciales admin. Pagos no declara topología y su publisher tiene write commands.

No aplicar PEDIDOS360_COORDINATION_MODE=RABBITMQ ni
PEDIDOS360_RELIABILITY_PLATFORM_READY=true en el stack actual desde #69. #70 debe
verificar ready/plataforma, habilitar health Rabbit donde corresponda, hacer corte
coordinado por flujo y gestionar close() antes de registry.stop(). HTTP sigue default.

## Runbook local

Requisitos: Docker Compose v2, PowerShell 7, Python 3.11+; Java 21/Maven Wrapper del
servicio para probe. Pika 1.3.2 se instala **solo en venv de herramientas infra**.
No dependencias de servicios ni consumidores de negocio nuevos.

```powershell
cd infrastructure/rabbitmq
./New-LocalEnvironment.ps1
./Initialize-Platform.ps1
docker compose --env-file .env ps
./.venv/Scripts/python.exe platform_control.py verify
docker compose exec -T rabbitmq rabbitmq-diagnostics -q check_local_alarms
docker compose exec -T rabbitmq rabbitmqctl list_vhosts name default_queue_type
docker compose exec -T rabbitmq rabbitmqctl list_queues -p pedidos360 name type arguments policy
./.venv/Scripts/python.exe platform_control.py inventory
```

New-LocalEnvironment genera passwords diferentes, no los imprime ni sobrescribe .env.
Archivo privado ignorado por Git; proteger permisos de host y no compartir evidencia
de `docker inspect`/`docker compose config` con environment resuelto. Docker admins
pueden leer variables; AWS debe usar gestión de secretos en #71. Rotación requiere
actualizar secretos y credenciales broker explícitamente: RABBITMQ_DEFAULT_PASS solo
inicializa volumen vacío. No borrar volumen para arreglar una password.

Management: http://127.0.0.1:15679, operador bootstrap; seleccionar pedidos360 para
inventario/policies/DLQ y sandbox para demo. AMQP: 127.0.0.1:5679. Ambos puertos solo
loopback del host; no modificar stack frontend/BFF ni abrir Security Groups. En red
local dedicada Docker el tráfico AMQP es sin TLS: no afirmar cifrado ni exposición
apta para redes externas. #71 debe decidir TLS/red privada/túnel antes de despliegue.

Healthcheck: ping + check_running + check_local_alarms, cada 15s, timeout 10s,
start_period 30s, ocho retries. `up/start --wait` verifica healthy. API `/api/nodes`
y `/api/overview` permiten diagnóstico autenticado adicional.

### Cola/tipo/argumento incompatible

Mantener listeners/dispatchers apagados. Ejecutar preflight e inspeccionar inventario
real con mensajes ready/unacked, bindings, policies y argumentos. Classic existente,
TTL/DLX incompatible, argumentos antiguos x-delivery-limit, exchanges/bindings ajenos
u operator policies que afecten el conjunto **bloquean** provisionamiento.
Reportar la incompatibilidad; respaldar definitions y mensajes bajo procedimiento
supervisado. Diseñar migración con aprobación: broker/vhost aislado preparado y
transferencia confirmada con identidades preservadas, deteniendo producers/consumers
antes del cambio. No borrar/recrear la queue bajo el mismo nombre automáticamente.
Nombres/contrato aprobados no se cambian para ocultar el conflicto.

Un provisionamiento parcialmente fallido por conexión puede reejecutarse aditivamente;
no es una transacción global de broker. Ready solo después de verify y pruebas de
permisos/declaraciones. Reboot no ejecuta scripts ni cambia políticas por sí solo.

### Persistencia y parada

```powershell
docker compose --env-file .env stop -t 30 rabbitmq
docker compose --env-file .env start --wait rabbitmq
./.venv/Scripts/python.exe platform_control.py verify
```

Volumen pedidos360-rabbitmq-local_data mantiene topología, usuarios, policies y
mensajes persistent. Stop/start de contenedor con mismo nodo **no destruye volumen**.
`down -v`, reset, eliminación de datos y cambio de nodename son destructivos y no
forman parte del runbook. Un volumen no reemplaza backup ni protege una caída de disco.

### DLQ y replay

Inspección por dominio, messageId, x-death (reason/queue/time/count), retry-count y
correlationId cuando exista; no tokens. NACK no agrega headers nuevos. Consultar estado
real/corregir causa primero. DLQ sin TTL y delivery-limit=-1 conserva inspecciones.
Para Pedidos usar herramienta y runbook de [reliability](RELIABILITY-RABBITMQ.md),
con p360-replay y secreto privado: publish mandatory/confirm/no-return → solo después
ACK DLQ, mismo messageId y metadatos replay. Para futuros dominios, #77/#82 deben
implementar replay validando autorización/plazos/versión; plataforma no ejecuta replay
de comandos artificiales ni reproduce consultas caducadas.

## Validación y evidencia

Pruebas **solo en el broker dedicado vacío y sin consumidores funcionales**, con
EP2_PLATFORM_TESTS=1. El precheck exige 21 queues vacías y ningún consumer; no purga.
Consumes solo los messageId generados y deja un mensaje desconocido sin ACK si lo
encuentra. Sandbox conserva demo.platform/demo.platform.q, fuera de las 21 negocio.
No ejecutar en un broker compartido. Logs/resources/compilación privados en evidence/.

```powershell
$env:EP2_PLATFORM_TESTS='1'
./.venv/Scripts/python.exe test_platform.py
./Test-Application.ps1
```

La suite valida broker real/version/health/Management, vhosts, 12 usuarios/13 permisos,
21 tipos/policies/bindings; NACK a las seis DLQ; las ocho TTL sin acortar tiempos;
permisos positivos/negativos; request/reply técnico BFF; aislamiento sandbox; límite 5
de principal y retención DLQ tras 25 cierres; persistent después de stop/start de
**contenedor**; recursos con 1000 mensajes de 1KiB y posterior ACK de todos.
Preflight classic se prueba con queue real sandbox observada por adaptador de lectura,
no convirtiendo ni borrando una queue negocio. Es prueba de detector, no migración.

Test-Application compila probe externo en infrastructure (sin modificar servicio),
usa PostgreSQL 17 Testcontainers, arranca PedidosApplication real con usuario consumer,
dynamic=false/platform-ready=true únicamente en ese contexto. Declara los beans
reales por operador separado, usa el **PagoConfirmacionPublisher original** con
credenciales restringidas, publica dos veces V1 y verifica
CONFIRMADO y version=1. No llama HTTP funcional ni conecta al PostgreSQL actual.
No prueba todavía autorización request/reply ni integración consumers #77–#82.

## Recursos, riesgos y siguientes pasos

Compose limita broker a 768MiB / 2 CPU / 256 PIDs; Erlang usa dos schedulers,
watermark memoria relativa 0.6 y disk_free_limit=512MB. Son límites locales de ensayo,
no dimensionamiento aprobado EC2. No se asignan 21 réplicas por queue: hay un nodo.
Quorum consume log/metadata aun con colas vacías. Backlog aumenta disco; DLQ sin TTL
requiere inspección y capacidad, no abandono. at-least-once/reject-publish evita ACK
temprano, pero un binding eliminado o disco perdido sigue siendo riesgo operativo.

#77: envelope/firmas/authorization/replyTo/expiration/correlación/consumers, publisher
confirms/returns y ACK/handoff simple. La plataforma prepara destinos y permisos.
#70: coordinar activación real, health de apps, exclusión HTTP/Rabbit, rollback, outbox
pendiente, shutdown y prueba E2E. Ningún flag global fue cambiado por esta rama.
#71: sumar medidas del stack JVM/DB existente al broker antes de elegir EC2. Reservar
RAM/SO/cache/disco para picos, volumen EBS durable/backups, alarmas, red privada/TLS,
secrets, Management por túnel sin exposición pública. No recomendar instancia de
1GiB para stack completo por ver bajo idle; medir carga real. Cluster solo con
requisito explícito; tres contenedores en un host no dan HA de host.

Referencias oficiales: [RabbitMQ quorum 4.1](https://www.rabbitmq.com/docs/4.1/quorum-queues),
[permisos](https://www.rabbitmq.com/docs/4.1/access-control),
[vhosts/default queue type](https://www.rabbitmq.com/docs/4.1/vhosts).

## Matriz final de colas y bindings

TTL/retorno de Pedidos en argumentos originales; nuevos TTL/retornos en policies.
Todas quorum, durables, sin auto-delete/exclusive. `—` significa no configurado.

| Queue | Tipo / argumentos adicionales | Binding exchange + key | DLX + retorno/failed key | TTL ms | delivery-limit | Overflow / estrategia |
|---|---|---|---|---:|---:|---|
| p360.pedidos.confirmacion.q | quorum; x-queue-type; sin otros argumentos | p360.pedidos.commands / pedido.confirmar.v1 | p360.pedidos.dlx / pedido.confirmar.failed | — | 5 | reject-publish / at-least-once |
| p360.pedidos.confirmacion.retry.5s.q | quorum; x-queue-type; TTL/DLX/key originales | p360.pedidos.retry / pedido.confirmar.retry.5s | p360.pedidos.commands / pedido.confirmar.v1 | 5000 | -1 | reject-publish / at-least-once |
| p360.pedidos.confirmacion.retry.30s.q | quorum; x-queue-type; TTL/DLX/key originales | p360.pedidos.retry / pedido.confirmar.retry.30s | p360.pedidos.commands / pedido.confirmar.v1 | 30000 | -1 | reject-publish / at-least-once |
| p360.pedidos.confirmacion.retry.120s.q | quorum; x-queue-type; TTL/DLX/key originales | p360.pedidos.retry / pedido.confirmar.retry.120s | p360.pedidos.commands / pedido.confirmar.v1 | 120000 | -1 | reject-publish / at-least-once |
| p360.pedidos.confirmacion.dlq | quorum; x-queue-type; sin otros argumentos | p360.pedidos.dlx / pedido.confirmar.failed | — / — | — | -1 | reject-publish / — |
| p360.usuarios.consultas.q | quorum; x-queue-type; sin otros argumentos | p360.queries / usuario.consultar-actual.v1 | p360.dlx / usuario.consultar-actual.failed | — | 5 | reject-publish / at-least-once |
| p360.usuarios.consultas.retry.1s.q | quorum; x-queue-type; sin otros argumentos | p360.retry / usuario.consultar-actual.retry.1s | p360.queries / usuario.consultar-actual.v1 | 1000 | -1 | reject-publish / at-least-once |
| p360.usuarios.consultas.dlq | quorum; x-queue-type; sin otros argumentos | p360.dlx / usuario.consultar-actual.failed | — / — | — | -1 | reject-publish / — |
| p360.restaurantes.consultas.q | quorum; x-queue-type; sin otros argumentos | p360.queries / restaurante.listar.v1 | p360.dlx / restaurante.listar.failed | — | 5 | reject-publish / at-least-once |
| p360.restaurantes.consultas.retry.1s.q | quorum; x-queue-type; sin otros argumentos | p360.retry / restaurante.listar.retry.1s | p360.queries / restaurante.listar.v1 | 1000 | -1 | reject-publish / at-least-once |
| p360.restaurantes.consultas.dlq | quorum; x-queue-type; sin otros argumentos | p360.dlx / restaurante.listar.failed | — / — | — | -1 | reject-publish / — |
| p360.productos.consultas.q | quorum; x-queue-type; sin otros argumentos | p360.queries / producto.listar-disponibles.v1 | p360.dlx / producto.listar-disponibles.failed | — | 5 | reject-publish / at-least-once |
| p360.productos.consultas.retry.1s.q | quorum; x-queue-type; sin otros argumentos | p360.retry / producto.listar-disponibles.retry.1s | p360.queries / producto.listar-disponibles.v1 | 1000 | -1 | reject-publish / at-least-once |
| p360.productos.consultas.dlq | quorum; x-queue-type; sin otros argumentos | p360.dlx / producto.listar-disponibles.failed | — / — | — | -1 | reject-publish / — |
| p360.carrito.vaciado.q | quorum; x-queue-type; sin otros argumentos | p360.commands / carrito.vaciar-por-pedido.v1 | p360.dlx / carrito.vaciar-por-pedido.failed | — | 5 | reject-publish / at-least-once |
| p360.carrito.vaciado.retry.1s.q | quorum; x-queue-type; sin otros argumentos | p360.retry / carrito.vaciar-por-pedido.retry.1s | p360.commands / carrito.vaciar-por-pedido.v1 | 1000 | -1 | reject-publish / at-least-once |
| p360.carrito.vaciado.dlq | quorum; x-queue-type; sin otros argumentos | p360.dlx / carrito.vaciar-por-pedido.failed | — / — | — | -1 | reject-publish / — |
| p360.pagos.consultas.q | quorum; x-queue-type; sin otros argumentos | p360.queries / pago.consultar.v1 | p360.dlx / pago.consultar.failed | — | 5 | reject-publish / at-least-once |
| p360.pagos.consultas.retry.1s.q | quorum; x-queue-type; sin otros argumentos | p360.retry / pago.consultar.retry.1s | p360.queries / pago.consultar.v1 | 1000 | -1 | reject-publish / at-least-once |
| p360.pagos.consultas.dlq | quorum; x-queue-type; sin otros argumentos | p360.dlx / pago.consultar.failed | — / — | — | -1 | reject-publish / — |
| p360.bff.consultas.respuestas.q | quorum; x-queue-type; sin otros argumentos | default / p360.bff.consultas.respuestas.q | — / — | 60000 | 5 | reject-publish / — |

## Mediciones y ejecución final local

7 de octubre de 2026, Docker Desktop/Windows, broker 4.1.8 de un nodo;
21 queues negocio y una queue de prueba aislada en sandbox. Suite final: **13
pruebas, 0 fallos/errores, 190.569 s**, incluyendo los ocho TTL reales.
Logs privados infrastructure/rabbitmq/evidence-tests-final.log y resources.json
en evidence/, excluidos de Git. Primera ejecución detectó errores de fixture
(contador sin headers, credenciales negativas y estadísticas aún no refrescadas);
corregidos antes de esta ejecución completa. Ninguna cola eliminada/purgada.

| Muestra | Memoria contenedor Docker | Memoria Erlang (bytes) | CPU Docker | Directorio mnesia (KiB) |
|---|---:|---:|---:|---:|
| idle_after_tests | 103.7MiB / 768MiB | 129929216 | 0.24% | 904 |
| 1000_persistent_messages_1KiB_in_2_queues | 92.96MiB / 768MiB | 137875456 | 1.20% | 2216 |
| after_drain | 90.67MiB / 768MiB | 137875456 | 0.19% | 2596 |

Docker stats y mem_used Erlang usan contabilidad distinta; snapshots individuales,
no promedios ni peak. GC/cache explican que RSS Docker no crezca monótonamente con
backlog. du -sk mide directorio de datos dentro del volumen, no tamaño del disco
virtual Docker/EBS ni imagen; logs/segmentos pueden conservar espacio tras ACK.
Los 1000 mensajes de prueba fueron retirados con ACK individual; sin purgar.
No se midió throughput sostenido, carga de todos los consumers ni comportamiento
del stack completo; AWS sigue pendiente y estas cifras no predicen sus picos.

Prueba final de aplicación: `Test-Application.ps1` exit 0, log privado
`infrastructure/rabbitmq/evidence/application-success.log`. PedidosApplication real
arrancó con platform-ready=true, dynamic=false y p360-pedidos-consumer; declaraciones
originales contrastadas mediante conexión operativa separada sin PRECONDITION_FAILED.
PagoConfirmacionPublisher original con p360-pagos-publisher publicó el mismo claim
dos veces, confirm positivo; listener emitió dos ACK, estado CONFIRMADO y version=1.
Además, ConfirmacionRetryPublisher original publicó una copia con las credenciales
del consumer a retry 5s; retornó por TTL real y recibió un tercer ACK idempotente.
PostgreSQL 17 aislado Testcontainers y contexto cerrados al terminar. El probe CLI
termina su propio proceso después de cerrar recursos; no altera lifecycle de producción.
Se excluyen los recursos/migraciones de Pagos del classpath de aplicación Pedidos:
solo se cargan sus clases originales para el probe del publisher.

Regresión relevante ejecutada: PedidoConfirmacionPagoTests (4), ReliabilityPolicyTests
(22), ConfirmacionConsumerRecoveryTests (7): **33 pruebas, 0 failures/errors/skipped**,
Maven BUILD SUCCESS, 11.230s. Builds de Pedidos/Pagos correctos sin modificar fuentes.
No se atribuyen a esta rama ejecuciones completas previas de #68 ni Entra live/AWS.
Validación final: compose config -q, Python py_compile, preflight/verify y git diff --check.
Las rutas a respuestas BFF se prueban a nivel AMQP; no existe todavía consumer de
consulta de aplicación ni correlador/autorización del BFF (#77–#81).
