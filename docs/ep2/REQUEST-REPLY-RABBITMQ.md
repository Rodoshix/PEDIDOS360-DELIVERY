# Base común request/reply y reliability simple — issue #77

Responsable de esta pieza: ejecución por confirmar; coordinación/revisión Integrante 1 e Integrante 5,
reliability con Integrante 3 y plataforma con Integrante 4.

Esta entrega implementa **solo la infraestructura reutilizable** de las consultas RabbitMQ. No
implementa ninguna operación de Usuarios, Restaurantes, Productos, Pagos ni Carrito, no declara
endpoints nuevos y **no activa RabbitMQ como transporte oficial**: HTTP sigue siendo el
predeterminado y el corte por flujo corresponde a #70.

```text
BFF                                     Servicio consumidor
 │                                        │
 │ RequestFactory: envelope + sobre        │
 │ RequestPublisher ── p360.queries ─────► cola funcional del dominio
 │                                        │  QueryConsumer (ACK manual)
 │                                        │   ├─ envelope estricto
 │                                        │   ├─ plazo (expiresAt)
 │                                        │   ├─ operación declarada
 │                                        │   ├─ sobre de actor firmado
 │                                        │   ├─ precheck del dominio
 │                                        │   └─ QueryProcessor
 │  PendingCorrelationRegistry            │  QueryReplyPublisher
 │  ResponseConsumer ◄── replyTo ─────────┘  (respuesta correlacionada)
 │  BffQueryAdapter: resultado/error       │
```

## 1. Envelope de consulta

Ocho campos exactos, sin admitir campos extra, faltantes ni duplicados:

```json
{
  "messageId": "7cbd68bf-bcf7-49dd-b1e2-8e15a7a0a681",
  "type": "ConsultaPedidos360",
  "version": 1,
  "occurredAt": "2026-10-07T01:30:00Z",
  "expiresAt": "2026-10-07T01:30:05Z",
  "actor": "p360act1.<claims base64url>.<firma HMAC>",
  "operacion": "usuario.consultar-actual.v1",
  "payload": { }
}
```

| Campo | Regla |
|---|---|
| `messageId` | UUID canónico y estable: un retry conserva el mismo identificador |
| `type` | Exactamente `ConsultaPedidos360` |
| `version` | Entero, inicialmente `1` |
| `occurredAt` | Instante UTC terminado en `Z` |
| `expiresAt` | Plazo **absoluto**; posterior a `occurredAt` |
| `actor` | Sobre firmado verificable; nunca el JWT original |
| `operacion` | Routing key atendida; debe coincidir con la configurada del dominio |
| `payload` | Objeto JSON con los parámetros mínimos de la operación |

`payload` es un objeto JSON que cada issue de consulta interpreta con su propio validador estricto.
La base no fija el esquema de cada operación; sí exige que sea un objeto y que no transporte JWT,
tokens, secretos ni perfiles completos.

### Propiedades AMQP

- `message_id`: mismo valor que `messageId`.
- `correlation_id`: identifica **esta espera** (lo genera el solicitante).
- `reply_to`: cola técnica autorizada de respuestas.
- `content_type`: `application/json`; codificación UTF-8.
- Mensaje persistente, `app_id` del emisor.
- `retry-count`: **campo reservado de Spring AMQP** (`MessageProperties.getRetryCount()`). Inicia en 0.

> Importante: `retry-count` no debe escribirse como header libre. Spring AMQP lo descarta de la trama
> porque lo administra como su propio contador; la base usa su API. Lo mismo aplica a cualquier
> código que inspeccione el intento.

## 2. Respuesta correlacionada

Todos los campos se escriben siempre; el que no aplica viaja como `null` explícito, no ausente:

```json
{
  "operacion": "usuario.consultar-actual.v1",
  "correlationId": "3f2a...",
  "messageId": "7cbd68bf-bcf7-49dd-b1e2-8e15a7a0a681",
  "success": true,
  "status": 200,
  "payload": { },
  "error": null,
  "timestamp": "2026-10-07T01:30:01Z"
}
```

| Campo | Regla |
|---|---|
| `operacion` | Operación atendida |
| `correlationId` | Igual a la correlación del request; también viaja como propiedad AMQP |
| `messageId` | Referencia el `messageId` del request |
| `success` | `true` con `status` 2xx y `payload` objeto; `false` con `status` de error y `error` objeto |
| `status` | Código equivalente al contrato HTTP existente |
| `error` | `{ "code", "title", "detail", "status" }`, sin datos sensibles |
| `timestamp` | Instante UTC |

Un error de negocio esperado (`403`, `404`, `409`) **viaja como respuesta correlacionada y se
confirma**: no se reintenta y no va a DLQ.

- `403` y `409`: siempre respuesta.
- `404`: respuesta cuando la operación describe un recurso concreto; en un listado sin parámetro es
  un fallo definitivo y termina en DLQ.

## 3. correlationId, replyTo y cola técnica

- El solicitante genera la correlación y la registra **antes** de publicar.
- `replyTo` se restringe a la cola técnica configurada (`pedidos360.messaging.queues.responses`). Un
  destino distinto se rechaza antes de tocar el broker.
- La respuesta se publica al **exchange predeterminado** usando `reply_to`: es la única forma de que
  llegue a una cola técnica compartida que no tiene binding propio.
- `p360.bff.consultas.respuestas.q` es durable, sin retry ni DLQ propia. El consumidor de respuestas
  confirma **siempre**, incluso sin espera asociada: una respuesta tardía, desconocida o duplicada se
  descarta y no se acumula.
- Esta solución contempla **una instancia BFF**. Escalar exige resolver la propiedad de las
  correlaciones antes de habilitar consumidores competidores.

## 4. Timeout y deadline

| Parámetro | Clave | Valor inicial |
|---|---|---|
| Presupuesto de la consulta | `pedidos360.messaging.deadline` | `5s` |
| Vigencia del sobre de actor | `pedidos360.messaging.actor-ttl` | `4s` |
| Timeout de confirmación | `pedidos360.messaging.confirm-timeout` | `3s` |
| Retry corto | `pedidos360.messaging.retry-delay` | `1s` |
| Backoff de recuperación de handoff | `pedidos360.messaging.handoff-backoff` | `500ms` |
| Intentos de transferencia | `pedidos360.messaging.handoff-attempts` | `2` |
| Máximo de correlaciones en vuelo | `pedidos360.messaging.max-pending-correlations` | `1024` |
| Tamaño máximo del cuerpo | `pedidos360.messaging.max-body-bytes` | `262144` |

Reglas:

- `expiresAt` es absoluto y **un retry no lo renueva**.
- La vigencia del actor debe ser **menor** que el plazo del request: así el retry corto siempre
  encuentra autorización vigente. La configuración se valida al arrancar y al planificar.
- Un mensaje vencido no se ejecuta, no se reintenta y se diagnostica; termina en DLQ.
- Si al solicitante todavía le queda presupuesto cuando el mensaje llega vencido, recibe un error
  correlacionado `504` (`PLAZO_AGOTADO`). Si su plazo ya se agotó, no se envía respuesta.
- Un timeout HTTP no garantiza que la consulta nunca se haya ejecutado.

## 5. Retry corto y DLQ

Forma aprobada por dominio:

```text
p360.queries --routing key--> cola funcional            (ACK manual desde el consumidor)
cola funcional --x-dead-letter-exchange--> p360.retry   (retry corto, un intento)
retry --x-dead-letter-exchange--> p360.queries          (vuelve a la cola funcional tras el TTL)
cola funcional --x-dead-letter-exchange--> p360.dlx     (fallo definitivo)
```

| Servicio | Cola funcional | Retry corto | DLQ |
|---|---|---|---|
| Usuarios | `p360.usuarios.consultas.q` | `p360.usuarios.consultas.retry.1s.q` | `p360.usuarios.consultas.dlq` |
| Restaurantes | `p360.restaurantes.consultas.q` | `p360.restaurantes.consultas.retry.1s.q` | `p360.restaurantes.consultas.dlq` |
| Productos | `p360.productos.consultas.q` | `p360.productos.consultas.retry.1s.q` | `p360.productos.consultas.dlq` |
| Pagos | `p360.pagos.consultas.q` | `p360.pagos.consultas.retry.1s.q` | `p360.pagos.consultas.dlq` |

Routing keys por dominio:

| Dominio | Operación | Retry | Fallo definitivo |
|---|---|---|---|
| Usuarios | `usuario.consultar-actual.v1` | `usuario.consultar-actual.retry.1s` | `usuario.consultar-actual.failed` |
| Restaurantes | `restaurante.listar.v1` | `restaurante.listar.retry.1s` | `restaurante.listar.failed` |
| Productos | `producto.listar-disponibles.v1` | `producto.listar-disponibles.retry.1s` | `producto.listar-disponibles.failed` |
| Pagos | `pago.consultar.v1` | `pago.consultar.retry.1s` | `pago.consultar.failed` |

**Un solo retry.** No se usan las etapas 5/30/120: pertenecen exclusivamente al flujo de Pedidos
(#68). Las colas de retry no tienen consumidor y su TTL inicial es `1000 ms`.

Clasificación de errores:

| Caso | Resultado |
|---|---|
| Fallo transitorio (primer intento) | Transferencia confirmada al retry corto |
| Fallo transitorio (ya reintentado) | DLQ |
| Envelope inválido, actor no autenticado, autorización denegada | DLQ |
| Plazo vencido | DLQ (con respuesta `504` si queda presupuesto) |
| Error de negocio esperado | Respuesta correlacionada y ACK |

### Regla de confirmación (ACK solo tras handoff seguro)

El request original se confirma **solo** después de que:

- la respuesta quedó confirmada por el broker sin return; o
- la transferencia a retry/DLQ quedó confirmada por el broker sin return.

Si la transferencia no se confirma, el mensaje **permanece sin ACK**. La base no usa
`requeue=true` como retry normal ni produce un bucle inmediato.

### Recuperación cuando el handoff no se confirma

`HandoffRecovery` es el punto de extensión. La implementación por defecto **solo diagnostica**: deja
el mensaje sin confirmar y lo registra. Con `prefetch=1` el consumidor se detiene hasta intervención
o reinicio, sin perder el mensaje y sin consumir CPU.

La activación de una recuperación activa (reinicio de canal con espera, supervisada por plataforma)
queda diferida a **#69**, que define la política operativa de indisponibilidad de broker. Esta base
no inventa esa política.

### Restricción conocida

Si la transferencia a DLQ no se confirma tras agotar los intentos de handoff, el mensaje permanece
sin confirmar. Es deliberado: se prefiere retener el mensaje a descartarlo o a reintentarlo en bucle.
La política operativa para ese escenario corresponde a #69.

## 6. Autorización del contexto de actor

Los listeners RabbitMQ **no heredan** el `SecurityContext` HTTP. Por eso el BFF valida Entra por HTTP
y emite un **sobre firmado** con el contexto de actor:

```text
p360act1.<claims JSON en base64url>.<HMAC-SHA256>
```

Claims:

```json
{
  "v": "p360act1",
  "tenantId": "11111111-1111-1111-1111-111111111111",
  "sujetoId": "44444444-4444-4444-4444-444444444444",
  "roles": ["CLIENTE"],
  "scopes": ["access_as_user"],
  "emitidoEn": "2026-10-07T01:30:00Z",
  "expiraEn": "2026-10-07T01:30:04Z",
  "audiencia": "p360.usuarios.consultas.q",
  "keyId": "11111111-2222-3333-4444-555555555555"
}
```

Verificación obligatoria en el consumidor, en este orden: formato → clave conocida → firma →
emisor esperado (`tenantId`) → destino permitido (`audiencia` = cola funcional) → vigencia propia →
coherencia con el plazo del request.

Reglas:

- **Nunca se transporta el JWT original** ni secretos.
- `sujetoId` es el identificador de Entra (`oid`), **no** el id local de perfil.
- `roles` y `scopes` son autorización, no pertenencia: el consumidor debe **volver a aplicar** sus
  propias reglas de tenant, pertenencia y perfil activo. `QueryPrecheck` es el punto de extensión
  para esas comprobaciones.
- Si no hay clave de firma configurada, el BFF **no emite** sobre y el consumidor **no verifica**:
  falla cerrado en lugar de aceptar un contexto sin autenticidad.
- `keyId` permite rotar claves y rechazar sobres firmados con una clave retirada.

### Decisión pendiente de seguridad

El algoritmo (HMAC-SHA256 simétrico, 32 bytes mínimo) y el contrato quedan implementados y probados.
Lo que **no** está especificado todavía y requiere decisión arquitectónica antes de operar es la
**provisión y rotación distribuidas de las claves** entre BFF y servicios consumidores, y su
integración con el gestor de secretos de AWS. La base no la inventa: hoy la clave se inyecta por
variable de entorno o gestor de secretos y su ausencia impide firmar o verificar.

## 7. Configuración central

Todos los nombres viven bajo `pedidos360.messaging`. Ninguna clase de dominio contiene nombres
literales.

```yaml
pedidos360:
  messaging:
    relay-mode: DISABLED        # ACTIVE habilita la base; HTTP sigue siendo el predeterminado
    role: SERVICE               # BFF mantiene correlaciones; SERVICE consume su cola funcional
    declare-topology: false     # declaracion efectiva coordinada con #69
    exchanges:
      queries: p360.queries
      retry: p360.retry
      dlx: p360.dlx
    queues:
      responses: p360.bff.consultas.respuestas.q
    naming:
      prefix: p360.
      query-suffix: .consultas.q
      retry-suffix: .consultas.retry.1s.q
      dlq-suffix: .consultas.dlq
      retry-key-suffix: .retry.1s
      failed-key-suffix: .failed
    routing:
      usuario: usuario.consultar-actual.v1
      restaurante: restaurante.listar.v1
      producto: producto.listar-disponibles.v1
      pago: pago.consultar.v1
      usuario-base: usuario.consultar-actual
      restaurante-base: restaurante.listar
      producto-base: producto.listar-disponibles
      pago-base: pago.consultar
    deadline: 5s
    actor-ttl: 4s
    retry-delay: 1s
    confirm-timeout: 3s
    handoff-backoff: 500ms
    handoff-attempts: 2
    max-pending-correlations: 1024
    max-body-bytes: 262144
    actor:
      emisor: ${ENTRA_TENANT_ID:}
      clave-id: ${PEDIDOS360_ACTOR_KEY_ID:}
      secreto: ${PEDIDOS360_ACTOR_SECRET:}
      tolerancia-reloj: 5s
      roles-permitidos: CLIENTE,ADMIN
```

El BFF agrega:

```yaml
pedidos360:
  bff:
    actor:
      emisor: ${ENTRA_TENANT_ID:}
      ttl: 4s
      destinos: p360.usuarios.consultas.q,p360.restaurantes.consultas.q,p360.productos.consultas.q,p360.pagos.consultas.q
```

Con `relay-mode: DISABLED` no se declara topología, no se levantan listeners y no cambia ninguna
ruta HTTP.

## 8. Uso desde #78–#81

El módulo compartido es `backend/shared/p360-messaging-core` (artefacto
`cl.duoc.pedidos360:p360-messaging-core`). Para habilitar una consulta:

1. Agregar la dependencia `p360-messaging-core` y `spring-boot-starter-amqp` al servicio.
2. Declarar la topología del dominio:

```java
@Bean
QueryTopology queryTopology(MessagingProperties properties) {
    return QueryTopology.of(properties, Domain.USUARIOS);
}
```

3. Implementar solo la operación de dominio:

```java
@Bean
QueryProcessor consultarUsuarioActual(UsuarioService usuarios) {
    return (actor, request) -> {
        // actor ya viene verificado: firma, emisor, destino y plazo.
        // Aqui se reaplica pertenencia/tenant/perfil activo con la identidad de actor.sujetoId().
        return usuarios.consultarPorIdentidadVerificada(actor);   // JsonNode equivalente al HTTP 200
    };
}
```

4. Para un error esperado, lanzar `QueryBusinessException` (`prohibido`, `noEncontrado`, `conflicto`).
   Para un fallo transitorio, `QueryTemporaryException`.
5. Ajustar `pedidos360.messaging.role: SERVICE` y declarar `pedidos360.messaging.actor.*`.
6. En el BFF, registrar el invocador (`QueryInvoker`) que reaplique autorización sin
   `SecurityContext` HTTP y devuelva `OperationResult`. El adaptador se encarga de correlación,
   plazo y confirmaciones.

Errores 400/401/403/404/409/429 conservan la equivalencia con el contrato HTTP vigente.

### Qué falta en cada issue

- **#78 Usuarios**: procesador de `usuario.consultar-actual.v1`, resolución local de `oid` a perfil,
  comprobación de perfil activo y su prueba E2E.
- **#79 Restaurantes**: procesador de `restaurante.listar.v1` y validación de la respuesta esperada.
- **#80 Productos**: procesador de `producto.listar-disponibles.v1` con `restauranteId` positivo.
- **#81 Pagos**: procesador de `pago.consultar.v1` con pertenencia local (`PagoService.obtener`).
- **#69**: declaración efectiva de la topología, argumentos por cola, policies, permisos, matriz de
  tipos de cola, medición de recursos y política operativa de indisponibilidad de broker.
- **#70**: activación coordinada, corte por flujo, convivencia HTTP, rollback y observabilidad.

### Construcción

`p360-messaging-core` es un módulo Maven independiente y **no hay POM agregador** en el repositorio.
Antes de construir un servicio que lo use hay que instalarlo en el repositorio local una vez:

```bash
cd backend/shared/p360-messaging-core && mvn install
cd backend/bff && mvn test          # o el servicio consumidor que corresponda
```

Si un pipeline construye servicios en un orden distinto, debe ejecutar primero `mvn install` sobre
el módulo compartido. No se agregó un POM padre para no cambiar la construcción independiente de
`pagos-service` ni `pedidos-service`.

## 9. Separación de responsabilidades

Lado BFF:

| Componente | Responsabilidad |
|---|---|
| `RequestFactory` | Envelope, plazo absoluto y sobre firmado del actor |
| `RequestPublisher` | Publicación con `mandatory`, confirm y detección de return |
| `PendingCorrelationRegistry` | Correlaciones en vuelo, límite, descarte de tardías y duplicadas |
| `ResponseConsumer` | Consumo de la cola técnica y ACK inmediato |
| `BffQueryAdapter` | Orquesta la consulta y traduce el resultado |

Lado servicio:

| Componente | Responsabilidad |
|---|---|
| `QueryConsumer` | Envelope, plazo, operación, actor, precheck, ACK manual |
| `QueryProcessor` | Operación de dominio (lo aporta #78–#81) |
| `QueryReplyPublisher` | Respuesta correlacionada confirmada |
| `QueryFailureHandler` | Clasificación y decisión de retry/DLQ/respuesta |
| `HandoffPublisher` | Transferencia confirmada a retry o DLQ |
| `HandoffRecovery` | Recuperación cuando la transferencia no se confirma |

Ninguna de estas clases contiene lógica de Usuarios, Restaurantes, Productos ni Pagos.

## 10. Pruebas

Ejecución reproducible:

```bash
cd backend/shared/p360-messaging-core && mvn test
cd backend/bff && mvn test
```

Docker debe estar disponible: se usa RabbitMQ 4.1 real con Testcontainers. El vhost `/` es exclusivo
del broker de prueba.

| Suite | Pruebas | Cobertura |
|---|---:|---|
| `MessagingContractTests` | 17 | Envelope, respuesta, topología, sobre de actor y configuración |
| `QueryMessagingFlowTests` | 15 | Flujos con broker real: correlación, retry, DLQ, confirms, handoff |
| `ClasificacionFallosTest` | 3 | Clasificación de fallos sin broker |
| `PendingCorrelationRegistryTests` | 7 | Correlaciones concurrentes, tardías y duplicadas |
| `BffConsultasAdapterTests` | 8 | Adaptador del BFF extremo a extremo con broker real |

Matriz de casos del issue #77:

| # | Caso | Dónde |
|---:|---|---|
| 1 | Request publicado correctamente | `BffConsultasAdapterTests`, `MessagingContractTests` |
| 2 | Respuesta correlacionada | `BffConsultasAdapterTests`, `QueryMessagingFlowTests` |
| 3 | Múltiples requests simultáneos | `BffConsultasAdapterTests`, `PendingCorrelationRegistryTests` |
| 4 | correlationId desconocido | `PendingCorrelationRegistryTests`, `ResponseConsumer` |
| 5 | Respuesta tardía | `PendingCorrelationRegistryTests`, `BffConsultasAdapterTests` |
| 6 | Timeout | `BffConsultasAdapterTests` |
| 7 | `expiresAt` vencido | `QueryMessagingFlowTests`, `MessagingContractTests` |
| 8 | Retry corto | `QueryMessagingFlowTests` |
| 9 | Retry agotado → DLQ | `QueryMessagingFlowTests` |
| 10 | Publisher confirm positivo | `QueryMessagingFlowTests` |
| 11 | Broker NACK | `ClasificacionFallosTest`, `QueryMessagingFlowTests` |
| 12 | Returned message | `QueryMessagingFlowTests` |
| 13 | Binding inexistente | `QueryMessagingFlowTests` |
| 14 | Broker caído | `QueryMessagingFlowTests`, `BffConsultasAdapterTests` |
| 15 | ACK solo tras handoff seguro | `QueryMessagingFlowTests` |
| 16 | Respuesta duplicada | `PendingCorrelationRegistryTests`, `BffConsultasAdapterTests` |
| 17 | `messageId` estable | `MessagingContractTests`, `QueryMessagingFlowTests` |
| 18 | `replyTo` inválido/no autorizado | `MessagingContractTests`, `BffConsultasAdapterTests` |
| 19 | Contexto de actor inválido | `MessagingContractTests`, `QueryMessagingFlowTests` |
| 20 | Payload inválido | `MessagingContractTests`, `QueryMessagingFlowTests` |
| 21 | Restart/recovery | `QueryMessagingFlowTests` (topología y leases tras reinicio del canal) |
| 22 | Ausencia de bucle por `requeue=true` | `QueryMessagingFlowTests` (`basicNack`/`basicReject` nunca invocados) |

## 11. Invariantes de la entrega

- `ConfirmarPedidoPorPago V1` intacto: no se modificó su payload, sus propiedades ni sus exclusiones.
- No se modificó #68 ni la topología especializada de Pedidos.
- No se implementaron consultas específicas de ningún dominio.
- No se activó RabbitMQ como transporte oficial ni se cambió el frontend.
- No se desplegó AWS, no se modificó Entra y no se modificó la topología existente en el broker.
- No se tocaron `pagos-service` ni `pedidos-service`: conservan sus propias propiedades de mensajería
  del núcleo Pago → Pedido.
