# Reliability avanzada de Pedidos — #68

## Alcance y estado

Solo ConfirmarPedidoPorPago V1, flujo Pagos → Pedidos. No implementa #77–#82,
plataforma completa #69 ni corte #70. HTTP sigue predeterminado; no se modifica
Pagos, scheduler, frontend, BFF, Entra o AWS. Nombres/contrato y estados intactos.

## Componentes

Consumer → Processor → PedidoService local transaccional → ACK individual.
Errores → DefaultPedidoConfirmacionFailureHandler → ConfirmacionErrorClassifier /
ConfirmacionRetryPublisher / ConfirmacionFailureReporter / ConfirmacionConsumerRecovery.
Se elimina el bean pendingReliabilityPolicy: existe una sola política real.

## ACK, NACK y clasificación

| Caso | Resultado |
|---|---|
| CREADO | Commit CONFIRMADO, después ACK |
| CONFIRMADO o PREPARANDO/LISTO/EN_REPARTO/ENTREGADO | Éxito idempotente, ACK |
| Duplicado | Sin segundo efecto, ACK |
| CANCELADO, inexistente, V1 inválido o retry-count inválido | DEFINITIVO; basicNack(tag,false,false), DLX |
| DB caída, deadlock/locking, inesperado | TRANSITORIO; retry acotado |
| Error de canal/ACK, conexión, confirm incierto o return | INFRAESTRUCTURA; original sin settlement, recuperación con backoff |

Errores de negocio/contrato usan ConfirmacionDefinitivaException. El classifier
recorre la cadena de causas con protección frente a ciclos: un definitivo o
InvalidRetryMetadataException envuelto conserva DEFINITIVO. Otras excepciones
inesperadas siguen siendo TRANSITORIO; no se transforma cualquier Exception en definitivo.
ConfirmacionMessagePropertiesConverter conserva el header retry-count explícito:
Spring AMQP 4.1 lo extrae normalmente y puede inferirlo de x-death; no se usa esa
inferencia como contador de nuestra política. Se prueba cero explícito para replay. La clasificación
no se agrega a PedidoService. retry-count acepta enteros no negativos; ausente=0.
ACK IOException y cierre de canal no provocan otra publicación desde el mismo
handler. Después de un commit se recupera el original y el dominio tolera redelivery.

## Retry

| retry-count entrante | Destino | TTL ms | retry-count publicado |
|---|---|---:|---:|
| 0 | p360.pedidos.confirmacion.retry.5s.q | 5000 | 1 |
| 1 | p360.pedidos.confirmacion.retry.30s.q | 30000 | 2 |
| 2 | p360.pedidos.confirmacion.retry.120s.q | 120000 | 3 |
| >=3 con fallo transitorio | NACK sin requeue → DLQ | — | — |

Exchange retry p360.pedidos.retry; keys pedido.confirmar.retry.5s/30s/120s.
Colas durables sin consumer; TTL y retorno a p360.pedidos.commands +
pedido.confirmar.v1 declarados por el servicio. DLX p360.pedidos.dlx y binding
pedido.confirmar.failed → p360.pedidos.confirmacion.dlq, durable sin retorno.
La aplicación declara la DLQ durable sin x-delivery-limit ni x-queue-type.
Durable no significa quorum. Se retiró x-delivery-limit=-1 de la declaración para
no imponer un argumento exclusivo de quorum ni generar incompatibilidades classic.
La retención durante inspección/replay, tipos y límite efectivo de la DLQ quedan
en #69. delivery-limit=5 protege la principal, no debe aplicarse accidentalmente a la DLQ.

El publisher clona el mensaje, conserva body/messageId, incrementa retry-count y
publica persistent/mandatory con correlación distinta por intento. Espera confirm
positivo y ausencia de return antes de ACK del original. Timeout, nack, exchange
ausente, ruta inexistente o conexión fallida no permiten ACK del original.

## Handoff incierto y recuperación

El original permanece UNACKED. Un executor dedicado solicita stop del container
fuera del hilo listener; forceStop=true evita drenar deliveries pendientes. El
cierre del canal libera deliveries sin confirmar; el callback de stop programa
start tras recovery-backoff=5s. No Thread.sleep y no NACK requeue=true de retry.
La carrera durante start() quedó corregida con una máquina de estados:
IDLE → STOPPING → BACKOFF → STARTING → IDLE, y CLOSED terminal. Estado, pending y
token de generación están protegidos por el mismo monitor; stop/start ocurren
fuera de él y en un único executor. Las llamadas recover() durante STOPPING,
BACKOFF o STARTING dejan pending=true. Tras start() se consume ese pendiente
iniciando un nuevo ciclo; cada reinicio espera 5 s después del callback de stop.
No se descarta la solicitud aunque el consumer procese antes de que start() retorne.
Solicitudes del mismo intervalo se agrupan; no ejecutan ciclos concurrentes.
Tokens invalidan callbacks tardíos/duplicados y callbacks de intentos fallidos.
Si start falla se vuelve a detener el consumidor parcialmente iniciado antes de
esperar backoff; un fallo de stop también se reintenta con backoff.
prefetch=1/concurrency=1.

ContextClosedEvent llama close() antes de que Spring detenga los beans de lifecycle.
close() pasa a CLOSED, invalida tokens, cancela tareas y rechaza nuevas recoveries.
Un start ya admitido/en curso se detiene al retornar si hubo cierre; no se programan
otros arranques. Para shutdown operativo intencional, llamar close() antes de
registry.stop(): un stop aislado no comunica intención al recuperador.
close es terminal; volver a habilitar requiere recrear el componente/aplicación.
El recovery del container por conexión también tiene intervalo 5s.

Una publicación pudo ser aceptada aunque se pierda el confirm/ACK: original y copia
pueden coexistir. Se conserva messageId y la confirmación local es idempotente.
El mecanismo evita loop caliente; no promete exactly-once ni disponibilidad si el
broker sigue caído. Redelivery repetida queda protegida adicionalmente por policy.

## Plataforma requerida antes de habilitar listener (#69)

La declaración actual de la principal no especifica tipo ni DLX. Se conserva para
no convertir implícitamente una cola classic existente ni cambiar sus argumentos
inmutables. #69 debe proporcionar la policy de la principal, por ejemplo:

```json
{
  "dead-letter-exchange": "p360.pedidos.dlx",
  "dead-letter-routing-key": "pedido.confirmar.failed",
  "dead-letter-strategy": "at-least-once",
  "overflow": "reject-publish",
  "delivery-limit": 5
}
```

Aplicar a la cola principal quorum con patrón exacto. Para las fuentes retry quorum,
policy at-least-once/reject-publish y protección de delivery según matriz de #69;
sus destinos DLX/keys/TTL ya están en declaraciones. No confundir delivery-limit
con los tres retries programados. Declaraciones no fuerzan x-queue-type: #69 debe
provisionar tipos y default_queue_type compatibles en un vhost preparado. Como
las declaraciones omiten el tipo, no basta precrear quorum si el tipo predeterminado
efectivo resulta incompatible con su redeclaración.
No borrar/migrar datos ni redeclarar tipo incompatible desde este servicio.

#69 debe configurar y comprobar el límite **efectivo** de DLQ para conservar fallos
ante múltiples inspecciones/replays sin ACK. Con RabbitMQ 4.1.8, combinar argumento
-1 con policy delivery-limit=5 produce límite 5; no asumir precedencia del argumento.
Referencia: [resolve_delivery_limit en RabbitMQ 4.1.8](https://github.com/rabbitmq/rabbitmq-server/blob/v4.1.8/deps/rabbit/src/rabbit_quorum_queue.erl).
La fixture separa patrones exactos: límite 5 solo en la principal; retries con
at-least-once/reject-publish; DLQ quorum con policy aislada delivery-limit=-1.
Estas policies solo preparan el broker Testcontainers, no la plataforma #69.
No se migran tipos ni se modifican colas existentes desde #68. #69 debe inventariar
también argumentos persistidos por versiones previas: retirar un argumento del
código no lo retira de una cola existente y puede exigir un procedimiento compatible.

La principal sin DLX policy puede perder rechazos. Por eso el listener tiene
autoStartup condicionado a pedidos360.messaging.reliability.platform-ready=false
(PEDIDOS360_RELIABILITY_PLATFORM_READY). Mantener false hasta verificar policies,
tipos, bindings y permisos. Este flag no sustituye coordination-mode: ambos son
necesarios para arranque automático. El flag platform-ready controla el listener,
no las declaraciones cuando coordination-mode=RABBITMQ. #70 coordina habilitación;
no se activa aquí.

Los tests usan broker aislado con default_queue_type=quorum y policies explícitas;
eso es fixture de pruebas, no implementación/despliegue de #69. La garantía de
dead-lettering at-least-once depende del broker quorum y policy, no de basicNack.

## Diagnóstico

messageId UUID seguro, pedidoId/pagoId numéricos, retry-count, clasificación,
clase/razón de excepción, destino, resultado y redelivery. No cuerpo completo,
JWT, credenciales ni mensajes de excepción remotos. NACK_SENT_DLQ_REQUESTED significa
solicitud de dead-letter, no prueba de entrega final. CONFIRMED_NO_RETURN solo se
registra tras confirm positivo sin return; handoff incierto no se etiqueta RETRY
publicado. NACK no agrega headers al mensaje; broker aporta x-death.

## Replay manual seguro

Herramienta Java independiente ConfirmacionDlqReplay; no bean, endpoint ni scheduler.
Se procesa una entrega por ejecución, con ACK manual de DLQ y confirms obligatorios.

1. Inspeccionar DLQ (messageId, x-death, retry-count) y logs; no hacer bucles de peek/requeue.
2. Corregir causa y verificar Pedido real. CANCELADO/inexistente no se reparan inventando
   estados. Mensaje malformado requiere reparación controlada, no replay ciego.
3. Preparar credencial operativa privada en RABBITMQ_REPLAY_URI con permisos mínimos;
   no escribir URI/secreto en archivos versionados o argumentos visibles.
4. Desde pedidos-service ejecutar mvnw.cmd -q dependency:build-classpath
   -Dmdep.outputFile=target/replay-classpath.txt (en una sola línea).
5. En PowerShell cargar classpath y ejecutar:

```powershell
$replayDependencies = Get-Content -Raw target/replay-classpath.txt
java -cp "target/classes;$($replayDependencies.Trim())" cl.duoc.pedidos360.pedidos.messaging.ConfirmacionDlqReplay p360.pedidos.confirmacion.dlq p360.pedidos.commands pedido.confirmar.v1
```

Usar nombres de configuración del entorno, sin asumir que sandbox es producción.
La herramienta hace basicGet sin autoAck y solicita REPLAY <messageId>; cualquier
otra entrada/EOF cierra el canal y conserva el original. La confirmación del operador
certifica inspección/corrección/estado real; la herramienta no consulta DB ni acepta
por sí misma que el dominio se pueda reparar.

6. Publica body/messageId originales persistent/mandatory; retry-count reinicia en 0,
   añade replay-id/replayed-at/replay-previous-retry-count y conserva diagnóstico x-death; elimina x-delivery-count.
7. Solo tras confirm positivo y no-return envía ACK individual de DLQ. Si falla o
   hay incertidumbre, cierre del canal recupera original sin ACK. Puede duplicarse
   publicación si se pierde confirm/ACK: el mismo messageId y servicio idempotente
   hacen seguro el efecto. No eliminar DLQ con Management antes de confirm.

ACK de replay significa broker aceptó publicación, no Pedido confirmado. UUID
inválido requiere reparación revisada antes de usar la herramienta.

## Pruebas y evidencia

RabbitConsumerTests: listener real + PostgreSQL 17/RabbitMQ 4.1; estados, commit/ACK,
duplicados, inválidos y definitivos a DLQ, TTL reales 5/30/120, handoff, quorum,
delivery-limit, destino DLQ rechazando temporalmente por capacidad, consumer caído, stop/start broker,
PostgreSQL realmente inaccesible (NOLOGIN y conexiones terminadas) y replay real. ReliabilityPolicyTests: clasificación, metadata,
ACK/NACK ordering y fallos de confirm/return/timeout/conexión inyectados de forma
controlada. Mantener regresión completa Pagos/Pedidos, sin cambios en Pagos.

Ejecutar mvnw.cmd test en ambos servicios. Reportes target/surefire-reports y logs
locales fuera de Git. No atribuir tests Entra live omitidos a verificación externa.
El broker stop/start_app conserva disco y demuestra reinicio de aplicación broker;
no simula destrucción del volumen ni fallo de EC2. Pruebas específicas de nodo/host,
permisos/vhosts reales y AWS pertenecen a #69/#70/#71.

Referencias técnicas: [Spring confirms/returns](https://docs.spring.io/spring-amqp/reference/amqp/template.html)
y [RabbitMQ quorum/dead-lettering](https://www.rabbitmq.com/docs/4.1/quorum-queues).

### Matriz de casos y pruebas

| Casos de la solicitud | Prueba / evidencia |
|---|---|
| 1–7, 25–27: commit/ACK, estados y duplicados | validV1ConfirmacionDuplicadaAckDespuesDelCommit; todosLosEstadosValidosListenerRealAckSinRetroceso; consumoRealYDuplicadoNoDejanMensajesSinAck; ackIOExceptionListenerRealRecuperaSinDuplicarEfecto; redeliveryRealAlCerrarCanalDespuesDeCommit |
| 8–13 y 29: definitivos e inválidos mediante listener real | definitivosMedianteListenerRealVanADlqSinBloquearSiguiente; invalidoNoInvocaServicioLocal |
| 14: PostgreSQL inaccesible | postgresRealInaccesibleGeneraRetryRecuperable; rol NOLOGIN y conexiones reales terminadas, restaurado en finally. Invoca processor y publisher por separado; no demuestra por sí solo listener → handler → retry bajo caída DB |
| 15–17: TTL 5/30/120 sin acortar tiempos | ttlRealRetryConservaIdentidadYVuelveAPrincipal, tres invocaciones parametrizadas. Cada etapa se prueba individualmente, no un único mensaje recorriendo inicial + tres retries |
| 18: agotamiento | agotamientoRealDlqConXDeath; agotamientoNackSinRequeue |
| 19–23: confirms, NACK, returns, timeout, exchange/binding y broker | publisherConfirmsReturnsTimeout (inyección controlada); bindingAusenteYExchangeInexistenteNoAceptanRetry (broker real); consumerCaidoYReinicioBrokerPersistenComando |
| 24, 28 y 30: consumer caído, redelivery repetida, reinicio | consumerCaidoYReinicioBrokerPersistenComando usa stop_app/start_app, no EC2/contenedor. deliveryLimitCincoProtegeRedeliveryRepetida comprueba x-delivery-count=5 y motivo x-death delivery_limit desde la principal |
| 31: destino DLQ rechaza temporalmente | dlqDestinoRechazaTemporalmenteRetieneHastaLiberarCapacidad; quorum at-least-once con binding intacto |
| 32–33: replay | replayExitosoYFallidoConBrokerReal; misma herramienta Java, confirm/no-return antes de ACK, cierre sin ACK conserva original |
| 34–35: bean/factory | beanReemplazadoYFactoryManualUno; único handler real, MANUAL/prefetch=1/consumer=1 |
| 36–37: sin sleep ni requeue normal | Inspección de código: executor/backoff y TTL; basicNack solo false,false |
| Handoff y ACK inciertos | handoffFallidoRecuperaListenerConBackoffSinLoop demuestra una recuperación y luego restaura el processor normal; handoffInciertoSinAckNiNack; ackInciertoNoSegundaPublicacion; nackInciertoRecupera |
| Carrera durante start | recoveryDuranteStartRealPreservaFailureYRedeliveryConPrefetchUno: latch impide retornar del primer restart antes del segundo fallo; broker real reentrega original tres veces, confirma Pedido y permite continuar con un pedido barrera. ConfirmacionConsumerRecoveryTests comprueba el mismo intervalo con otro hilo y reloj virtual, backoff, pending durante stop, tokens, errores y cierre |
| Conservación DLQ | dlqConservaOriginalTrasVeinticincoCierresSinAckConPolicyDePlataformaFixture: cierres físicos y reentregas bajo policy quorum aislada -1; conservación de body/messageId más allá de cinco y del default 20 del broker, sin afirmar plataforma productiva |
| Definitivos envueltos | definitivosEnvueltosNoSeReintentan verifica NACK sin retry para ambas excepciones envueltas; causasCiclicasNoBloqueanClassifierNiConviertenInesperadosEnDefinitivos comprueba recorrido acotado |
| Counter explícito / replay cero | converterMantieneContadorExplicitoSinInferirXDeath; TTL/replay real |
| Tarjeta/efectivo/outbox/Idempotency-Key | Suite de Pagos existente, incluyendo RabbitCoreTests y tests concurrentes PostgreSQL |

### Límite de enrutamiento DLX

At-least-once no reemplaza la validación de bindings: una DLX sin ruta no ofrece la misma garantía que un destino existente que rechaza temporalmente. #69/#70 deben verificar y proteger binding/DLQ antes de platform-ready=true. La prueba de destino indisponible usa rechazo por capacidad con binding intacto; no se afirma conservación frente a eliminación administrativa de la ruta.

### Fixture del worker interno DLX

El broker de test configura únicamente dead_letter_worker_publisher_confirm_timeout=1000 mediante argumento Erlang para acotar la espera de recuperación del destino DLQ. No reduce TTL 5/30/120 ni cambia el retry de aplicación. El timeout/reintento interno del worker DLX del broker no es el confirm-timeout del publisher ni recovery-backoff del listener; tiempos de plataforma real se verifican en #69. Referencia de implementación: [worker DLX RabbitMQ 4.1](https://github.com/rabbitmq/rabbitmq-server/blob/v4.1.0/deps/rabbit/src/rabbit_fifo_dlx_worker.erl).

## Resultado local inicial de #68 (antes de correcciones de auditoría)

Verificación 6–7 de octubre de 2026: suites completas mvnw.cmd test. Reportes Surefire locales.

| Suite | Tests | Failures | Errors | Skipped | Duración acumulada suites (s) |
|---|---:|---:|---:|---:|---:|
| pedidos-service | 107 | 0 | 0 | 1 | 245.376 |
| pagos-service | 81 | 0 | 0 | 1 | 32.92 |

Pedidos: tiempo total del comando 251.77 s. Pagos: duración indicada es suma de suites, no cronómetro del comando. Cada omisión corresponde a Entra live sin credenciales externas. Pedidos pasa de 72 a 107 tests: 35 casos adicionales incluyendo parametrizados; 23 tests RabbitConsumerTests y 20 ReliabilityPolicyTests. Los fallos de confirm/nack/timeout se inyectan donde corresponde; TTL, DLQ, canal y persistencia se verifican con broker real. Regresión completa Pagos sin modificar su código.

Durante desarrollo se corrigió el tratamiento de retry-count de Spring AMQP 4.1 y la fixture de capacidad quorum (puede superar temporalmente max-length): el test demuestra NACK real antes de verificar conservación. La ejecución final tiene cero failures/errors. git diff --check y revisión de alcance completadas.

## Correcciones de auditoría de PR #84 — validación final

7 de octubre de 2026: suites completas `mvnw.cmd test` en Pedidos y Pagos, después
de corregir la race, separar policies y reforzar las pruebas. Reportes locales
Surefire; logs de ejecución en `target/pr84-correcciones-final-test.log` (Pedidos)
y `target/pr84-correcciones-test.log` (Pagos), excluidos de Git.

| Suite | Tests | Failures | Errors | Skipped | Suma suites (s) | Comando completo (s) |
|---|---:|---:|---:|---:|---:|---:|
| pedidos-service | 118 | 0 | 0 | 1 | 268.864 | 275.127 |
| pagos-service | 81 | 0 | 0 | 1 | 28.401 | 35.656 |

Las dos omisiones son Entra live sin credenciales externas. La suma de tiempos de
suites no incluye todo el arranque/compilación de Maven; el comando completo se
midió con Stopwatch. No se afirma validación externa de Entra/AWS ni plataforma #69.

Se mantienen los 35 casos adicionales originales y se agregan 11: siete de recovery
determinista, dos de classifier defensivo y dos con broker real (race durante start
y retención DLQ tras 25 cierres sin ACK). Totales: RabbitConsumerTests 25,
ReliabilityPolicyTests 22, ConfirmacionConsumerRecoveryTests 7. La prueba de límite
existente ahora verifica contador 5 y motivo delivery_limit. PostgreSQL 17 y RabbitMQ
4.1 reales en Testcontainers; TTL 5/30/120 individuales sin reducir tiempos.

HTTP sigue default y platform-ready=false; principal y contrato V1 intactos, sin
cambios en Pagos ni nuevas funcionalidades #77–#82. #69 conserva provisionamiento,
tipos/policies compatibles y retención DLQ efectiva. #70 conserva corte/rollback
y coordinación del shutdown operativo (`close()` antes de `registry.stop()`).
