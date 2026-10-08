# Productos request/reply - issue #80

Esta pieza agrega la consulta interna y sus pruebas. **HTTP sigue siendo oficial**,
relay DISABLED por defecto y #70 pendiente. No cambia BFF, frontend, Entra, RDS ni runtime AWS.
#80 permanece OPEN hasta autorizar y mergear el PR documental de cierre.
Implementación integrada por [PR #88](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/pull/88),
merge SHA `0303a17d176ffd2ae61c73dd37bdce45189b05af`. Base histórica de implementación:
`d26726c` (PR #87 integrado). Auditoría de cierre sobre develop
`cd35d310731367d08bd31a324f053dfc32b617c6`, sin blockers funcionales.
La decisión del responsable sustituye las revisiones/coordinaciones internas por
la auditoría técnica de Codex; no atribuye aprobaciones a otros integrantes.
Véase [CIERRE-78-80.md](CIERRE-78-80.md).

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
de un artefacto privado en el host. El caller versionado
infrastructure/aws/compose.build.yml ahora pasa additional_contexts:
messaging-core: ../../backend/shared/p360-messaging-core para Productos.
El PR #89 incorporó después el mismo contexto al build de Usuarios.
Es un ajuste de BUILD autorizado en la correccion del mismo PR, no un despliegue.
En #80, Compose operativo, runtime de Entrega 1 y builds de otros servicios quedaron intactos.
additional_contexts requiere Compose 2.17+ y soporte del builder; referencia:
[Compose Build Specification](https://docs.docker.com/reference/compose-file/build/#additional_contexts).

Build real sin cache probado con los dos archivos versionados, .env.example e
identificadores publicos ficticios en el entorno (sin secretos, up ni push):

```sh
docker compose --env-file infrastructure/aws/.env.example \
  -f infrastructure/aws/compose.yml -f infrastructure/aws/compose.build.yml \
  build --no-cache productos
```

La comparacion de modelos resueltos antes/despues verifica que la unica diferencia
es additional_contexts de Productos. Las seis pruebas existentes compose.test.mjs
pasan, incluyendo runtime, TLS/RDS, Entra/worker y argumentos de build.

## Pruebas y limites

No existian tests propios de Productos; se agregan:

| Suite | Casos | Cobertura |
|---|---:|---|
| ProductosQueryProcessorTests | 13 | IDs/payload, DTO/delegacion, vacio, errores |
| ProductosConfigurationTests | 3 | Default sin beans, rol/scope |
| ProductosHttpRegressionTests | 6 | PostgreSQL real, Flyway, filtro, CRUD, 400/404, health sin broker |
| ProductosRabbitTests | 22 | Aplicacion/listener, RabbitMQ 4.1.8 quorum, PostgreSQL real |

Verify repetido: **44 tests**, sin failures/errors/skipped; build real mediante
caller Compose corregido, sin cache, PASS.
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

Regresion completa #69 repetida sobre broker local dedicado: **13 tests PASS**.
Verificados 21 queues quorum, 7 exchanges direct, 20 bindings custom y 21 policies;
usuarios/permisos de todas las cuentas, ocho retries con TTL real (incluye
5/30/120s), NACK/DLQ, delivery-limit=5, retencion DLQ tras 25 redeliveries,
mandatory/returns, aislamiento sandbox y persistencia tras stop/start del broker.
Backlog controlado drenado; las 21 queues funcionales/tecnicas quedaron vacias.
Se reconcilio solamente write DLX de Productos en ese broker local, sin rotar
credenciales ni ampliar permisos de otras cuentas. Comparacion fuente/live contra
develop mantiene inventario/policies y confirma la unica diferencia autorizada.
Reportes de plataforma/resources locales ignorados; sin acceso AWS.

#70 pendiente: operaciones concretas/adapter BFF, corte por flujo, activación,
orquestación y rollback. #71: AWS, TLS/secretos, cuentas/permisos del broker AWS
y despliegue. #72: E2E integrales, fallos integrados y regresión de entrega completa. HMAC distribuido y correlaciones BFF compartidas conservan limites
previos #77. Redelivery puede repetir lectura/respuesta, sin cambiar datos; BFF
descarta duplicadas. #80 no implementó otros consumers; #78 se integró después
por PR #89. #79/#81/#82 quedan fuera de este cierre. #80 está integrado y sigue OPEN.
