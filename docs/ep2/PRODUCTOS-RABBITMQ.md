# Productos request/reply - issue #80

Esta pieza agrega la consulta interna y sus pruebas. **HTTP sigue siendo oficial**,
relay DISABLED por defecto y #70 pendiente. No cambia BFF, frontend, Entra, RDS ni AWS.
#80 permanece abierto para auditoria del PR. Base develop d26726c (PR #87 integrado).

## Auditoria y reutilizacion

Graphify contrastado con codigo real: ProductoController llama a
`ProductoService.listarDisponiblesPorRestaurante(Long)`, que filtra con
`ProductoRepository.findByRestauranteIdAndDisponibleTrue()` y mapea ProductoResponse.
ProductosQueryProcessor invoca **ese mismo servicio local**, sin HTTP, stock,
reservas ni reglas nuevas. No hay ORDER BY garantizado en el HTTP; no se agrega uno.
Restaurante sin productos disponibles devuelve `[]`, exito 200, no un 404 inventado.

ProductosMessagingConfiguration registra QueryTopology PRODUCTOS, processor y
QueryPrecheck e importa las configuraciones #77. No duplica QueryConsumer,
@RabbitListener, firma, publishers, clasificador, recovery ni framework.
El modulo p360-messaging-core no se modifica.

## Topologia existente

| Elemento | Nombre |
|---|---|
| Query exchange (direct) | p360.queries |
| Functional queue | p360.productos.consultas.q |
| Operacion/routing key | producto.listar-disponibles.v1 |
| Retry exchange | p360.retry |
| Retry queue | p360.productos.consultas.retry.1s.q |
| Retry routing key | producto.listar-disponibles.retry.1s |
| DLX | p360.dlx |
| DLQ | p360.productos.consultas.dlq |
| Failed routing key | producto.listar-disponibles.failed |
| Respuestas | p360.bff.consultas.respuestas.q, exchange AMQP predeterminado |

Nombres desde pedidos360.messaging; cantidad/topologia #69 intactas (21 queues,
7 exchanges custom). **Ajuste de permisos autorizado durante #80:** #77 publica a
p360.dlx para handoff confirmado, pero #69 no permitia esa escritura a Productos.
Se agrega solamente p360.dlx a write de p360-productos-consumer; configure sigue
`^$`, read solo su funcional, otras cuentas intactas. No se aplica al broker AWS.
Permisos RabbitMQ son por exchange, no routing key: DLX compartido permite publicar
en otras rutas, aunque el publisher comun usa la del dominio. Mantener aislamiento
de vhost/credenciales; revisar el mismo requisito en los otros issues de consumers.

## Request / response

Envelope comun ConsultaPedidos360 V1, ocho campos exactos:

```json
{
  "messageId": "7cbd68bf-bcf7-49dd-b1e2-8e15a7a0a681",
  "type": "ConsultaPedidos360",
  "version": 1,
  "occurredAt": "2026-10-08T01:00:00Z",
  "expiresAt": "2026-10-08T01:00:05Z",
  "actor": "p360act1.<claims-base64url>.<firma-HMAC>",
  "operacion": "producto.listar-disponibles.v1",
  "payload": { "restauranteId": 7 }
}
```

Actor ilustrativo, no valido; nunca el JWT original. Propiedades AMQP: message_id
estable, correlation_id, reply_to autorizado, application/json y mensaje persistente.
Payload solo restauranteId, entero positivo representable en Long. Null, 0,
negativos, strings, decimales, overflow o campos adicionales: EnvelopeException
definitiva, DLQ **antes** de dominio, sin retry.

QueryResponse comun: correlationId/messageId del request, operacion, success,
status, payload/error y timestamp. Payload de exito: array de ProductoResponse con
id, restauranteId, nombre, descripcion, precio, categoria y disponible. Mismos
valores/filtrado; no prometer representacion textual de decimales ni orden nuevo.

## Identidad y plazo

HTTP publico BFF exige tenant/issuer Entra, scope access_as_user y CLIENTE o ADMIN.
Productos HTTP interno no tiene filtros Spring Security. La nueva dependencia
excluye el starter-security transitivo; spring-security-core solo aporta las
excepciones usadas por #77. La regresion prueba ausencia de security filter chain.

Consumer #77 verifica HMAC-SHA256, keyId, tenant esperado, audiencia de esta queue
y vigencia. QueryPrecheck reaplica scope y rol sin HttpServletRequest,
SecurityContext HTTP ni JWT original. No inventa pertenencia de catalogo ni perfil
activo porque el HTTP de catalogo no aplica esas comprobaciones.
Actor/firma/tenant/destino/vigencia/rol/scope invalidos: definitivos, no dominio, DLQ.
Sin clave/issuer configurados el modo ACTIVE falla cerrado.

expiresAt absoluto no se renueva. Vencido no ejecuta ni reintenta: se hereda el
comportamiento **actual** #77, que publica 504 y deriva a DLQ. BFF debe descartar
respuesta fuera del presupuesto; no se altera esa base desde #80.

## ACK, retry, DLQ

- Listener compartido: MANUAL, prefetch=1, concurrencia=1.
- Exito/error de negocio respondible: respuesta correlacionada confirmada sin
  return, luego ACK del original.
- Error tecnico DB/unexpected: unico retry, messageId/correlationId/replyTo/deadline
  conservados y retry-count=1; ACK solo despues de confirm, sin return.
- TTL corto por **policy #69**, nunca x-message-ttl desde la aplicacion. Retry queue
  sin consumer retorna a queries/operacion original. retry-delay=1s configurable debe
  concordar con RABBITMQ_SIMPLE_RETRY_MS de plataforma; no modifica la policy solo.
  No se usan retries 5/30/120 de Pedidos.
- Segundo fallo tecnico, payload/actor/operacion/deadline definitivos: transferencia
  confirmada a DLQ, despues ACK. Sin politica NACK/requeue paralela.
- Handoff fallido: original sin ACK; recovery stop/restart con backoff (500ms),
  redelivery, sin Thread.sleep en listener ni requeue=true normal. Main conserva
  delivery-limit=5 de #69. DLQ sin retorno/replay automatico.

El listado valido hoy no genera errores de negocio. El test 409 inyecta un
QueryBusinessException para probar la base; no crea una funcionalidad/conflicto
de Productos. No se inventa un 404 para restaurante vacio.

## Configuracion y build

Default: PEDIDOS360_RELAY_MODE=DISABLED, role SERVICE, declare-topology=false,
spring.rabbitmq.dynamic=false; sin listeners/declaraciones ni health Rabbit que
exija broker. Confirms correlated, returns y mandatory preparados en YAML.
Defaults guest son solo locales, no credenciales de despliegue. #70/#71 deben
configurar TLS/cuentas/secretos y provisionar permiso DLX antes de activar.

```sh
mvn -B -ntp -f backend/shared/p360-messaging-core/pom.xml install -DskipTests
mvn -B -ntp -f backend/services/productos-service/pom.xml verify
docker build --build-context messaging-core=backend/shared/p360-messaging-core \
  -f backend/services/productos-service/Dockerfile \
  -t pedidos360-productos-ep2-test backend/services/productos-service
```

Dockerfile instala core en el build desde contexto publico adicional, sin depender
de un artefacto privado en el host. Runtime intacto. Los callers del build deben
pasar messaging-core al integrar #70; este PR no cambia Compose AWS/Entrega 1.

## Pruebas y limites

No existian tests propios de Productos; se agregan:

| Suite | Casos | Cobertura |
|---|---:|---|
| ProductosQueryProcessorTests | 13 | IDs/payload, DTO/delegacion, vacio, errores |
| ProductosConfigurationTests | 3 | Default sin beans, rol/scope |
| ProductosHttpRegressionTests | 6 | PostgreSQL real, Flyway, filtro, CRUD, 400/404, health sin broker |
| ProductosRabbitTests | 22 | Aplicacion/listener, RabbitMQ 4.1.8 quorum, PostgreSQL real |

Verify: **44 tests**, sin failures/errors/skipped; Docker build PASS.
Rabbit real: equivalencia HTTP, correlacion/reply, ACK y barrera posterior, admin,
vacio, JSON/IDs/operacion invalidos, actor/firma/tenant/destino/rol/scope/vigencia,
deadline, 409, retry TTL real, agotamiento DLQ, handoff sin binding conserva
original y recupera sin bucle caliente, consumer detenido, policy efectiva sin
x-message-ttl, prefetch/ACK y cuenta sin configure. No mocks de broker.

DB temporal caida se simula con DataAccessResourceFailureException sobre servicio
espia; no se afirma desconexion real PostgreSQL. Filtro/consulta/HTTP usan DB real.
No se prueba AWS/Entra E2E ni caida real broker en #80. Reportes Surefire ignorados;
no secretos/logs privados versionados. Inventario/policies #69 comparados al base,
solo cambia permiso autorizado de Productos.

#70 pendiente: corte/adapter BFF, build context, TLS/cuentas, rollback y E2E. #71/#72:
E2E AWS/capacidad. HMAC distribuido y correlaciones BFF compartidas conservan limites
previos #77. Redelivery puede repetir lectura/respuesta, sin cambiar datos; BFF
descarta duplicadas. #78/#79/#81/#82 no se implementan, sin merge ni cierre de #80.
