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
- `registrar` **rechaza** un `correlationId` ya vivo en lugar de sobrescribirlo: sobrescribir perdería
  la respuesta de la primera consulta.
- Al cerrarse el BFF, `BffQueryAdapter.alCerrar()` (`@PreDestroy`) cancela las correlaciones en vuelo
  de forma excepcional: ningún llamador queda colgado hasta el timeout.

## 4. Timeout y deadline

| Parámetro | Clave | Valor inicial |
|---|---|---|
| Presupuesto **total** de la consulta | `pedidos360.messaging.deadline` | `5s` |
| Vigencia del sobre de actor | `pedidos360.messaging.actor-ttl` | `4s` |
| Timeout de confirmación de publicación | `pedidos360.messaging.confirm-timeout` | `3s` |
| Retry corto | `pedidos360.messaging.retry-delay` | `1s` |
| Backoff de recuperación del consumidor | `pedidos360.messaging.recovery-backoff` | `500ms` |
| Máximo de correlaciones en vuelo | `pedidos360.messaging.max-pending-correlations` | `1024` |
| Tamaño máximo del cuerpo | `pedidos360.messaging.max-body-bytes` | `262144` |

Reglas:

- `expiresAt` es absoluto y **un retry no lo renueva**.
- El **presupuesto es total**: el confirm de publicación descuenta su tiempo y la espera de la
  respuesta recibe **solo el resto** (`deadline − confirm`). No se apilan un plazo de confirm y otro
  de espera.
- La vigencia del actor debe ser **menor** que el plazo del request: así el retry corto siempre
  encuentra autorización vigente. La configuración se valida al arrancar y al planificar.
- Un mensaje vencido no se ejecuta, no se reintenta y se diagnostica; termina en DLQ.
- Si al solicitante todavía le queda presupuesto cuando el mensaje llega vencido, recibe un error
  correlacionado `504` (`PLAZO_AGOTADO`). Si su plazo ya se agotó, no se envía respuesta.
- Un timeout HTTP no garantiza que la consulta nunca se haya ejecutado.
- `recovery-backoff` reemplaza al antiguo `handoff-backoff`: ya **no** es una espera bloqueante entre
  intentos de handoff en el hilo del listener, sino el backoff entre la detención y el reinicio del
  listener durante la recuperación.

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

### Quién declara el TTL, el DLX y el retorno

El TTL y las transferencias programadas del broker **no son argumentos de cola en esta base**: son
propiedad de las **policies por cola de la plataforma de #69** (`ep2-<cola>`, `priority=10`,
`apply-to=quorum_queues`). Por eso `QueryTopology` declara:

- colas **durables y sin argumentos** (ni `x-message-ttl`, ni `x-dead-letter-exchange`, ni
  `x-dead-letter-routing-key`);
- los tres exchanges `direct` durables, **sin argumentos**;
- los tres bindings por dominio.

Razón: un argumento de cola es inmutable y el broker lo compara con la definición existente. Declarar
`x-message-ttl` en una cola ya aprovisionada con `message-ttl` por policy —o un DLX distinto en la
cola funcional— produce `PRECONDITION_FAILED`. Con la declaración sin argumentos, la aplicación
**puede redeclarar** el inventario de #69 sin 406, y el vhost `pedidos360` es quorum por defecto, así
que tampoco se fija `x-queue-type`.

Consecuencias operativas:

- `pedidos360.messaging.declare-topology` es `false` por defecto y solo debe activarse como
  herramienta de desarrollo/verificación.
- La aplicación **no necesita permiso `configure`**: el rol `p360-<servicio>-consumer` de #69 tiene
  `configure` en `^$` y `write` sobre `p360.retry` y `amq.default`.
- `spring.rabbitmq.dynamic=false` es compatible: sin declaración de aplicación no hay `RabbitAdmin`
  escribiendo en el broker.
- `p360.queries` y `p360.retry` son exchanges compartidos: cada publisher de aplicación valida su
  propio dominio y destino antes de publicar.

### Propiedades que conserva la transferencia

La transferencia clona las **propiedades del mensaje original** en lugar de reconstruirlas. Se
conservan `messageId`, `correlationId`, `replyTo`, `contentType`, `contentEncoding`, `deliveryMode`,
`appId`, los headers y la expiración original; solo se actualiza `retry-count` (campo reservado de
Spring AMQP, accesible con `MessageProperties.getRetryCount()`) y se anotan los headers de
diagnóstico `dominio`, `destino` y `clase-de-fallo`. Los datos de la entrega anterior (delivery tag,
consumer tag, exchange y routing key de origen) se limpian.

Sin `correlationId` ni `replyTo` la respuesta del segundo intento no tendría destino: un `403`, `404`
o `409` que apareciera en la entrega de retry acabaría en DLQ en lugar de volver al BFF como respuesta
HTTP equivalente.

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

### Recuperación activa cuando el handoff no se confirma

Con `AcknowledgeMode.MANUAL` una excepción normal **no** produce `basicNack` ni `basicReject`: el
canal sigue sano y el mensaje queda en el conjunto *unacked*. Con `prefetch=1` la ventana del
consumidor se llena y, sin intervención, el consumidor queda detenido indefinidamente. La base
resuelve ese escenario con `QueryConsumerRecovery`, que es la implementación **por defecto** de
`HandoffRecovery`:

```text
IDLE --sinConfirmar--> STOPPING --container detenido--> BACKOFF --espera--> STARTING --> IDLE
```

| Propiedad | Cómo se cumple |
|---|---|
| Recuperación automática real | Sí: nadie interviene; el ciclo lo ejecuta la aplicación |
| Quién cierra el canal | `container.stop()`: al cerrarse el canal el broker reencola el mensaje sin confirmar |
| Quién reinicia el consumidor | `container.start()` tras `recovery-backoff` |
| Bucle caliente | No: el reinicio siempre pasa por el backoff |
| Hilo del listener | Nunca se bloquea: no hay `Thread.sleep`; el ciclo corre en `p360-query-recovery` |
| Lock | Ninguna operación de container se ejecuta con el lock tomado: el listener no puede quedar bloqueado por la recuperación ni al contrario |
| Solicitudes concurrentes | Se acumulan en `pending` y producen **un** ciclo adicional, nunca uno simultáneo |
| Solicitud durante STARTING/STOPPING | Queda pendiente y se atiende al terminar el ciclo |
| Callbacks tardíos | Se descartan por generación: un callback de un ciclo anterior no reinicia nada |
| Listener no registrado | El ciclo se reintenta con backoff, sin girar en vacío |
| Cierre | `@EventListener(ContextClosedEvent)` + `close()`: el cierre es terminal y no admite ciclos nuevos |
| `requeue=true` | No se usa nunca: la redelivery la produce el cierre de canal |

El reintento del **handoff** es inmediato y único: si no se confirma, se devuelve `SIN_CONFIRMAR` y el
consumidor no confirma el request. No hay reintentos con espera dentro del hilo del listener.

Dos cosas que no deben confundirse:

| Mecanismo | Cómo funciona | Quién espera |
|---|---|---|
| Retry del **mensaje** | Cola de retry con TTL de plataforma, un solo intento | Nadie: no hay consumer en la cola de retry |
| Recuperación del **consumidor** | Cierre de canal + reinicio con backoff | El executor de recuperación |

### Restricción conocida

Si la transferencia a DLQ no se confirma, el mensaje también queda sin confirmar, pero ahora se
recupera con el mismo ciclo: el reinicio del listener lo reentrega y el intento completo se repite. La
retención sigue siendo deliberada: se prefiere reentregar a descartar el mensaje.

Límite que permanece abierto: la **política operativa de indisponibilidad de broker** (cuándo un
broker caído debe dejar de reintentar el ciclo, alertas y capacidad) pertenece a #69/#71. La base
garantiza que no se pierde el mensaje y que el ciclo no gira en caliente; no decide la operación.

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
    declare-topology: false     # solo herramienta de desarrollo; en produccion manda #69
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
    recovery-backoff: 500ms   # backoff entre detencion y reinicio del listener; ya no bloquea el hilo
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
| `RequestPublisher` | Publicación con `mandatory`, confirm y detección de return; informa el tiempo consumido |
| `PendingCorrelationRegistry` | Correlaciones en vuelo, límite, descarte de tardías y duplicadas, cancelación al cerrar |
| `ResponseConsumer` | Consumo de la cola técnica y ACK inmediato |
| `BffQueryAdapter` | Orquesta la consulta, descuenta el confirm del presupuesto y traduce el resultado |

Lado servicio:

| Componente | Responsabilidad |
|---|---|
| `QueryConsumer` | Envelope, plazo, operación, actor, precheck, ACK manual |
| `QueryProcessor` | Operación de dominio (lo aporta #78–#81) |
| `QueryReplyPublisher` | Respuesta correlacionada confirmada |
| `QueryFailureHandler` | Clasificación y decisión de retry/DLQ/respuesta, sin bloquear el hilo |
| `HandoffPublisher` | Transferencia confirmada a retry o DLQ, clonando las propiedades originales |
| `HandoffRecovery` | Contrato de recuperación cuando la transferencia no se confirma |
| `QueryConsumerRecovery` | Implementación por defecto: stop/restart del listener con backoff en su propio executor |

Ninguna de estas clases contiene lógica de Usuarios, Restaurantes, Productos ni Pagos.

## 10. Pruebas

Ejecución reproducible:

```bash
cd backend/shared/p360-messaging-core && mvn install -DskipTests   # modulo compartido
cd backend/shared/p360-messaging-core && mvn test                  # 52 pruebas
cd backend/bff && mvn test                                         # 78 pruebas
```

Docker debe estar disponible: se usa RabbitMQ 4.1 real con Testcontainers. El vhost `/` es exclusivo
del broker de prueba.

| Suite | Pruebas | Cobertura |
|---|---:|---|
| `MessagingContractTests` | 17 | Envelope, respuesta, topología sin argumentos, sobre de actor y configuración |
| `QueryMessagingFlowTests` | 20 | Flujos con broker real: correlación, retry, DLQ, confirms, handoff, 403/404/409 tras retry |
| `RecoveryRealTests` | 1 | **Recuperación real**: handoff fallido, original sin ACK, stop/restart, reentrega y barrera |
| `QueryConsumerRecoveryTests` | 7 | Máquina de estados: un solo ciclo, concurrencia, STARTING, cierre, listener ausente |
| `PlatformCompatibilityTests` | 4 | Compatibilidad con el inventario y las policies de #69, sin `PRECONDITION_FAILED` |
| `ClasificacionFallosTest` | 3 | Clasificación de fallos sin broker |
| `PendingCorrelationRegistryTests` | 10 | Correlaciones concurrentes, tardías, duplicadas, duplicado rechazado y cancelación al cerrar |
| `BffConsultasAdapterTests` | 10 | Adaptador del BFF extremo a extremo, deadline total y cierre |

### Prueba de recuperación real

`RecoveryRealTests` usa broker y listener **reales** (`@RabbitListener` con ACK manual y
`prefetch=1`). El binding del retry apunta deliberadamente a otra routing key, de modo que la
transferencia vuelve sin ruta de forma determinista —sin esperas probabilísticas—. La secuencia que
acredita es:

1. el request llega a la cola funcional y lo atiende el listener real;
2. el procesador falla de forma transitoria y la transferencia al retry **no se confirma**;
3. el original **no recibe ACK** (se comprueba que el canal solo vio un ACK en total, el del intento
   que sí respondió);
4. `QueryConsumerRecovery` detiene el container y lo reinicia tras el backoff;
5. el broker **reentrega** al cerrarse el canal: la cola de retry queda en cero, así que la reentrega
   no viene del TTL;
6. **no hay bucle caliente**: un único ciclo, dos invocaciones del procesador y separación entre
   solicitudes de al menos el backoff;
7. el segundo intento responde y confirma;
8. con `prefetch=1` el consumidor sigue vivo y procesa un **mensaje barrera** posterior.

Matriz de casos del issue #77:

| # | Caso | Dónde |
|---:|---|---|
| 1 | Request publicado correctamente | `BffConsultasAdapterTests`, `MessagingContractTests` |
| 2 | Respuesta correlacionada | `BffConsultasAdapterTests`, `QueryMessagingFlowTests` |
| 3 | Múltiples requests simultáneos | `BffConsultasAdapterTests`, `PendingCorrelationRegistryTests` |
| 4 | correlationId desconocido | `PendingCorrelationRegistryTests` |
| 5 | Respuesta tardía | `PendingCorrelationRegistryTests`, `BffConsultasAdapterTests` |
| 6 | Timeout | `BffConsultasAdapterTests` (deadline total) |
| 7 | `expiresAt` vencido | `QueryMessagingFlowTests`, `MessagingContractTests` |
| 8 | Retry corto | `QueryMessagingFlowTests` |
| 9 | Retry agotado → DLQ | `QueryMessagingFlowTests` |
| 10 | Publisher confirm positivo | `QueryMessagingFlowTests` |
| 11 | Broker NACK | `ClasificacionFallosTest`, `QueryMessagingFlowTests` |
| 12 | Returned message | `QueryMessagingFlowTests` |
| 13 | Binding inexistente | `QueryMessagingFlowTests` |
| 14 | Broker caído | `QueryMessagingFlowTests`, `BffConsultasAdapterTests` |
| 15 | ACK solo tras handoff seguro | `QueryMessagingFlowTests`, `RecoveryRealTests` |
| 16 | Respuesta duplicada | `PendingCorrelationRegistryTests`, `BffConsultasAdapterTests` |
| 17 | `messageId` estable | `MessagingContractTests`, `QueryMessagingFlowTests`, `elRetryRealConserva…` |
| 18 | `replyTo` inválido/no autorizado | `MessagingContractTests`, `QueryMessagingFlowTests`, `BffConsultasAdapterTests` |
| 19 | Contexto de actor inválido | `MessagingContractTests`, `QueryMessagingFlowTests` |
| 20 | Payload inválido | `MessagingContractTests`, `QueryMessagingFlowTests` |
| 21 | Restart/recovery | `RecoveryRealTests`, `QueryConsumerRecoveryTests` |
| 22 | Ausencia de bucle por `requeue=true` | `QueryMessagingFlowTests` (`basicNack`/`basicReject` nunca invocados), `RecoveryRealTests` |

Casos añadidos por la revisión de PR #86:

| Caso | Dónde |
|---|---|
| Handoff fallido → original sin ACK → recuperación → reentrega → barrera | `RecoveryRealTests` |
| Máquina de estados de recovery (un ciclo, concurrencia, STARTING, cierre) | `QueryConsumerRecoveryTests` |
| `correlationId` y `replyTo` preservados en el retry | `QueryMessagingFlowTests.elRetryRealConservaCorrelationIdReplyToYPlazo` |
| 403/404/409 en la entrega de retry vuelven al BFF y no van a DLQ | `QueryMessagingFlowTests` (tres pruebas) |
| Sin `PRECONDITION_FAILED` contra las policies de #69 | `PlatformCompatibilityTests` |
| Sensibilidad de la prueba: el broker sí rechaza argumentos incompatibles | `PlatformCompatibilityTests.elBrokerRechazaArgumentosIncompatibles…` |
| Deadline total (confirm + espera ≤ presupuesto) | `BffConsultasAdapterTests.elDeadlineEsTotal…` |
| Cierre del BFF cancela correlaciones en vuelo | `BffConsultasAdapterTests.elCierreDelAdaptador…` |

### Compatibilidad con #69: qué queda demostrado y qué no

Demostrado contra broker real:

- la declaración de la aplicación (colas durables **sin argumentos**, exchanges y bindings) se aplica
  sobre un inventario ya aprovisionado con policies activas **sin `PRECONDITION_FAILED`**;
- la aplicación **no declara** `x-message-ttl` ni el DLX de la cola funcional (verificado con
  `rabbitmqctl list_queues name arguments`: el único argumento visible es `x-queue-type`, que añade el
  broker según el vhost);
- el broker **sí** rechaza argumentos incompatibles, así que la prueba es sensible a una regresión que
  volviera a declararlos;
- tras la redeclaración, el enrutamiento del dominio sigue vigente y el retry y la DLQ quedan sin
  consumer.

**No** demostrado aquí:

- la **aplicación efectiva** de las policies: el broker de prueba de Testcontainers no las aplica a
  estas colas (la columna `effective_policy_definition` queda vacía), así que la suite comprueba que
  `rabbitmqctl set_policy` las acepta y que la declaración de la aplicación es compatible, pero no el
  TTL real. El TTL y el retorno del retry se acreditan en `QueryMessagingFlowTests`, donde la suite
  declara las colas con los argumentos de plataforma;
- los permisos mínimos por usuario (escenario con credenciales sin `configure`);
- el tipo quorum del vhost `pedidos360` y la medición de recursos.

Los tests usan el vhost `/` del broker de prueba, no el vhost `pedidos360` de la plataforma.

## 11. Invariantes de la entrega

- `ConfirmarPedidoPorPago V1` intacto: no se modificó su payload, sus propiedades ni sus exclusiones.
- No se modificó #68 ni la topología especializada de Pedidos.
- No se implementaron consultas específicas de ningún dominio.
- No se activó RabbitMQ como transporte oficial ni se cambió el frontend.
- No se desplegó AWS, no se modificó Entra y no se modificó la topología existente en el broker.
- No se tocaron `pagos-service` ni `pedidos-service`: conservan sus propias propiedades de mensajería
  del núcleo Pago → Pedido.

## 12. Límites que permanecen abiertos

| Límite | Dónde se resuelve |
|---|---|
| Provisión y rotación **distribuidas** de claves HMAC y su integración con el gestor de secretos de AWS | #71 (hoy: variable de entorno o gestor, una sola clave vigente) |
| Escalado horizontal del BFF: la cola de respuestas es compartida y un consumidor competidor robaría correlaciones ajenas | #71 (requiere cola de respuestas por instancia o propiedad de correlaciones) |
| `concurrencia=1` en el listener de consultas | #71 |
| Política operativa de indisponibilidad de broker (alertas, capacidad, cuándo dejar de recuperar) | #69/#71 |
| Aplicación real de policies y permisos mínimos por usuario en la plataforma | #69 (ya integrado; su evidencia vive en `PLATAFORMA-RABBITMQ.md`) |
| Activación del transporte, corte por flujo y rollback | #70 |
| Declaración de topología de la aplicación en despliegue | `declare-topology: false` en todos los entornos con plataforma aprovisionada |
