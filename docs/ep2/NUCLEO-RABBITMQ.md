# Núcleo RabbitMQ — implementación de #65 y #66

Responsable: Integrante 1. Esta entrega implementa el núcleo y prepara la integración;
no activa el corte de producción ni implementa #67, #68, #69 o #70.
La arquitectura y el contrato V1 aprobados siguen siendo la fuente de verdad.

## Flujo implementado

```text
Registro HTTP autenticado de Pago
  → consulta síncrona existente a Pedidos (pertenencia, total y moneda)
  → transacción PostgreSQL: Pago + ConfirmarPedidoPorPago V1 en outbox
  → commit y respuesta HTTP, sin esperar RabbitMQ
  → dispatcher reclama un registro con lease y termina la transacción
  → publisher espera confirm correlacionado y verifica ausencia de return
  → transacción corta marca PUBLISHED
  → listener de Pedidos valida el mensaje bruto
  → PedidoService.confirmarPorPago(pedidoId), local y transaccional
  → ACK individual después del commit o resultado idempotente
```

Tarjeta APROBADO y efectivo PENDIENTE generan una intención por pago. Aprobar después
el efectivo no genera otra. La clave HTTP mantiene su alcance por usuario y operación.
Se cubre además la carrera donde otro registro con la misma clave hace commit entre
la búsqueda inicial y la comprobación de pago activo. Una falla de almacenamiento
de outbox revierte la operación y conserva su error técnico; no se presenta como
conflicto de pago activo si ese pago no existe.

## Persistencia y recuperación

`V3__confirmacion_outbox.sql` es aditiva. Agrega `pagos.coordinacion`, con HTTP para
los registros existentes, y `pagos.confirmacion_outbox`. La FK exige un Pago existente;
la restricción UNIQUE de `pago_id` impide dos comandos lógicos para ese pago.
El UUID y el payload se crean una sola vez, dentro de la transacción del Pago.

| Estado | Significado |
|---|---|
| PENDING | Publicación pendiente o fallida, con próxima fecha de intento |
| IN_FLIGHT | Reclamo con lease y token de propietario |
| PUBLISHED | Confirm positivo del broker, sin return |
| BLOCKED | Payload persistido inválido; requiere inspección |

El dispatcher usa `FOR UPDATE SKIP LOCKED`; reclama un registro justo antes de
publicarlo. El token impide que una finalización tardía sobrescriba un reclamo nuevo.
No mantiene una transacción DB durante la espera al broker. Recupera PENDING y
leases vencidos; un reinicio entre publicación y actualización DB puede duplicar la
entrega, conservando el `messageId`. Pedidos tolera ese duplicado mediante su servicio.

Timeout, conexión fallida, confirm negativo y return dejan PENDING recuperable.
Si falla el guardado del resultado, el lease permite recuperar IN_FLIGHT. Los errores
se guardan como códigos/tipos, sin payload, credenciales ni mensajes remotos.
BLOCKED se inspecciona con el UUID y el estado del Pago: no se descarta ni se cambia
su identidad automáticamente. Una reparación de datos requiere revisión; el replay
operativo y su procedimiento final corresponden a #68/#70.

El publisher usa mandatory, confirms correlacionados, returns, JSON persistente,
`app_id=pagos-service`, `message_id` estable y `retry-count=0`. Cada intento tiene una
correlación distinta, pero el comando conserva UUID y payload.
PUBLISHED **no confirma el Pedido** y no cambia `pedido_confirmado`.

## Configuración y convivencia HTTP

Ambos servicios usan `RabbitProperties` bajo `pedidos360.messaging`:

```yaml
pedidos360:
  messaging:
    coordination-mode: HTTP # o RABBITMQ; variable PEDIDOS360_COORDINATION_MODE
    exchanges:
      commands: p360.pedidos.commands
    queues:
      confirmacion: p360.pedidos.confirmacion.q
    routing-keys:
      confirmar: pedido.confirmar.v1
    confirm-timeout: 3s
    lease: 30s
    batch-size: 10
    dispatch-interval-ms: 5000
    publisher-retry-delay: 30s
```

`publisher-retry-delay` recupera la **publicación de outbox**; no sustituye el retry
5/30/120 del consumidor, reservado a #68. Los tiempos deben ser positivos y el lease
mayor que el timeout. Los nombres no aparecen en servicios ni listeners.

HTTP es el valor predeterminado. Cada Pago guarda el modo usado al crearse:

- HTTP: confirmación inmediata existente, flag y scheduler existentes; sin outbox.
- RABBITMQ: crea outbox; nunca se confirma por HTTP ni entra en el scheduler HTTP.

El endpoint interno y los modos interno/delegado de `PedidosClient` se conservan.
El scheduler solo consulta pagos HTTP: puede resolver históricos mientras el
dispatcher publica los nuevos pagos RabbitMQ. No coordinan el mismo Pago.
No se migra automáticamente un Pago histórico ni se cambia su modo por un reintento.

En RABBITMQ se habilitan publisher/dispatcher en Pagos y listener/declaración mínima
en Pedidos. Pedidos declara únicamente exchange principal durable, Queue durable y
Binding principal. Retry/DLX/DLQ quedan para #68 y plataforma/vhosts/usuarios para #69.
La salud Rabbit queda deshabilitada inicialmente para preservar `/actuator/health`
en HTTP sin requerir broker; su integración operativa queda para #69/#70.

Volver a HTTP detiene el dispatcher; no convierte ni elimina outbox existente. #70
debe acordar activación coordinada, pendientes/históricos, rollback y observabilidad.
No realizar el corte definitivo sin #68 y #69.

Se mantienen modelos/configuraciones de mensajería locales en ambos servicios para
preservar sus builds independientes. No se agregó un módulo Maven compartido ni se
cambió Docker. El JSON V1 es la frontera de compatibilidad.

## Consumer y punto de extensión para #68

`PedidoConfirmacionProcessor` valida los seis campos, tipos estrictos, UUID canónico,
timestamp UTC, IDs positivos, identidad AMQP coincidente y ausencia de campos extra,
campos duplicados o contenido JSON posterior. No consulta Pagos ni invoca HTTP.

- CREADO pasa a CONFIRMADO.
- CONFIRMADO y todos los estados posteriores son éxito sin actualización.
- CANCELADO, inexistente y mensaje inválido producen `ConfirmacionDefinitivaException`
  con razón explícita, entregada a `PedidoConfirmacionFailureHandler`.
- Los demás errores también se entregan al handler para la clasificación de #68.

La fábrica usa ACK MANUAL, prefetch 1 y concurrencia 1. ACK ocurre tras el servicio
transaccional. Si se pierde después del commit, la redelivery no repite el efecto.

El handler provisional **mantiene el fallo sin ACK** y registra su clase/razón;
con prefetch 1 ese consumidor queda detenido hasta intervención/reinicio del canal.
Esto es una limitación explícita del núcleo, no una política final de retry/DLQ.
El bean es sustituible por #68 sin reescribir el listener ni el servicio. #68 debe
implementar NACK, transferencia confirmada a retry, agotamiento, DLQ, replay y
pruebas avanzadas. El fallo provisional no provoca un ciclo automático de requeue.

## Evidencia reproducible

En cada directorio de servicio ejecutar `./mvnw clean test` (Windows: `mvnw.cmd`).
Docker debe estar disponible. Se usan PostgreSQL 17 y RabbitMQ 4.1 reales mediante
Testcontainers; el vhost `/` es exclusivo del broker de prueba.

`RabbitCoreTests` verifica atomicidad y rollback por falla de outbox, clave concurrente,
tarjeta/efectivo/aprobación, broker caído, return sin binding, reinicio del dispatcher,
lease vencido, propietario anterior, payload/propiedades AMQP y separación HTTP.
Confirm negativo y confirm que nunca llega se inyectan de forma controlada; la
republicación posterior se comprueba contra el broker real.

`RabbitConsumerTests` verifica contrato válido/inválido, servicio local, todos los
estados posteriores, CANCELADO/inexistente, ACK tras commit, duplicados, punto de
extensión y redelivery real cerrando el canal físico después del commit sin ACK.
Se ejecutan también las suites existentes de pagos/pedidos y seguridad local.
Las pruebas Entra live requieren credenciales externas y pueden quedar omitidas;
no se valida despliegue AWS ni el broker de producción en esta rama.

### Resultado de la verificación local

| Suite completa | Pruebas | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|
| pagos-service | 81 | 0 | 0 | 1 (Entra live) |
| pedidos-service | 72 | 0 | 0 | 1 (Entra live) |

Dentro de esas suites, `RabbitCoreTests` contiene 12 pruebas y
`RabbitConsumerTests` contiene 8; todas aprobadas. Los reportes reproducibles se
encuentran en `target/surefire-reports/` de cada servicio, fuera de Git.
