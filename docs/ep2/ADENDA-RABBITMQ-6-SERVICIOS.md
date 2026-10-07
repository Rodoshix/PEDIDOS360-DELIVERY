# Adenda aprobada — RabbitMQ en seis microservicios

## Decisión y estado

Ampliación posterior al diseño de PR #75 y al núcleo integrado por PR #76 en develop
(22f5c4d). El requisito docente confirmado exige al menos una cola funcional propia
en usuarios-service, restaurantes-service, productos-service, carrito-service,
pedidos-service y pagos-service, con manejo de fallos y DLQ en cada dominio.
BFF, repartidores-service, seguimiento-service y rabbit-admin-service quedan fuera
del conteo docente. El BFF participa técnicamente en request/reply.

Esta adenda registra diseño aprobado y trabajo pendiente, no implementación ni
activación. HTTP sigue siendo el modo predeterminado. #64/#65/#66 están completados;
#68/#69/#70 continúan pendientes. No se crean ramas de implementación en este cambio.

## Necesidad real y adaptación docente

Pago → Pedido resuelve coordinación real, y el vaciado de Carrito después de crear
Pedido traslada una operación que hoy coordina el frontend. Las cuatro consultas
RabbitMQ son una adaptación docente de lecturas existentes: siguen siendo síncronas
para el usuario. No se agrega stock, autenticación, cocina ni asignación automática.
Entra, los contratos HTTP públicos y ConfirmarPedidoPorPago V1 se preservan.

## Exchanges personalizados

Todos son direct, durables y sin auto-delete: cada mensaje tiene destino explícito.

| Exchange | Propósito | Compatibilidad |
|---|---|---|
| p360.pedidos.commands | Confirmación de Pedido | Conservar |
| p360.pedidos.retry | Retry 5/30/120 | Conservar |
| p360.pedidos.dlx | Fallos de confirmación | Conservar |
| p360.commands | Comandos adicionales: vaciado de Carrito | Nuevo |
| p360.queries | Consultas de cuatro dominios | Nuevo |
| p360.retry | Retry corto de cinco dominios | Nuevo |
| p360.dlx | DLQ de cinco dominios | Nuevo |

No crear p360.events. No migrar destructivamente los exchanges actuales.
El exchange predeterminado usado para respuestas no es un exchange personalizado.

## Colas funcionales, operaciones y productores

| Servicio consumer | Queue funcional | Producer | Exchange | Routing key | Operación existente / propósito |
|---|---|---|---|---|---|
| Usuarios | p360.usuarios.consultas.q | BFF | p360.queries | usuario.consultar-actual.v1 | GET /usuarios/me; UsuarioService.obtenerActual() |
| Restaurantes | p360.restaurantes.consultas.q | BFF | p360.queries | restaurante.listar.v1 | GET /restaurantes; RestauranteService.listar() |
| Productos | p360.productos.consultas.q | BFF | p360.queries | producto.listar-disponibles.v1 | GET /productos/restaurante/{restauranteId}/disponibles; listarDisponiblesPorRestaurante() |
| Carrito | p360.carrito.vaciado.q | Pedidos | p360.commands | carrito.vaciar-por-pedido.v1 | Vaciado condicionado al carrito/version que originó Pedido |
| Pedidos | p360.pedidos.confirmacion.q | Pagos | p360.pedidos.commands | pedido.confirmar.v1 | PedidoService.confirmarPorPago(), sin HTTP ni consulta a Pagos |
| Pagos | p360.pagos.consultas.q | BFF | p360.queries | pago.consultar.v1 | GET /pagos/{id}; PagoService.obtener(identidad, id) |

La consulta de Pago por ID verifica pertenencia localmente; listarPorPedido añade
actualmente una consulta HTTP a Pedidos y no es la operación seleccionada.
Carrito y Pedidos pueden conservar sus consultas HTTP internas al catálogo.

## Retry simple y DLQ

| Servicio | Retry queue | Binding desde p360.retry | DLQ | Binding desde p360.dlx |
|---|---|---|---|---|
| Usuarios | p360.usuarios.consultas.retry.1s.q | usuario.consultar-actual.retry.1s | p360.usuarios.consultas.dlq | usuario.consultar-actual.failed |
| Restaurantes | p360.restaurantes.consultas.retry.1s.q | restaurante.listar.retry.1s | p360.restaurantes.consultas.dlq | restaurante.listar.failed |
| Productos | p360.productos.consultas.retry.1s.q | producto.listar-disponibles.retry.1s | p360.productos.consultas.dlq | producto.listar-disponibles.failed |
| Carrito | p360.carrito.vaciado.retry.1s.q | carrito.vaciar-por-pedido.retry.1s | p360.carrito.vaciado.dlq | carrito.vaciar-por-pedido.failed |
| Pagos | p360.pagos.consultas.retry.1s.q | pago.consultar.retry.1s | p360.pagos.consultas.dlq | pago.consultar.failed |

Cada cola funcional nueva tiene DLX p360.dlx y la routing key failed de su fila.
Inicial retry-count=0; fallo transitorio → publicación confirmada a retry con
retry-count=1; otro fallo → NACK requeue=false → DLQ. TTL corto configurable,
valor aprobado inicial 1000 ms. Las retry queues no tienen consumer; su DLX y
routing key de retorno son el exchange funcional y la key funcional de su dominio.
Los nombres .1s identifican el valor inicial; cualquier cambio operativo de TTL
debe quedar coordinado/documentado y no redefinir argumentos incompatibles.

Pedidos conserva sus tres retry queues .retry.5s.q / .retry.30s.q / .retry.120s.q,
bindings pedido.confirmar.retry.5s / .30s / .120s y retorno pedido.confirmar.v1
en p360.pedidos.commands. Su DLQ sigue siendo p360.pedidos.confirmacion.dlq,
binding p360.pedidos.dlx + pedido.confirmar.failed. No compartir esas colas con
otros dominios: tienen retorno específico a confirmación de Pedido.

## Request/reply y cola técnica

Una cola durable p360.bff.consultas.respuestas.q recibe respuestas mediante el
exchange predeterminado y replyTo. No tiene retry/DLQ propia dentro de este conteo;
la aplicación debe retirar respuestas huérfanas/tardías y evitar acumulación.
Se conserva la respuesta hasta ACK del BFF; expiración limitada por el presupuesto
restante. Esta solución contempla una instancia BFF; escalar requiere resolver
propiedad de correlaciones antes de habilitar consumidores competidores.

Envelope nuevo: messageId estable, type, version, occurredAt, expiresAt y parámetros
mínimos por consulta. AMQP: message_id coincidente, correlation_id, reply_to,
content_type JSON, app_id y retry-count. La respuesta conserva correlationId e
identifica la solicitud; incluye resultado o error equivalente al contrato HTTP.
No modificar el payload estricto de ConfirmarPedidoPorPago V1.

Presupuesto inicial configurable de 5 s, alineado con timeouts BFF y cliente.
expiresAt no se renueva por retry. TTL se calcula por tiempo restante; el consumer
comprueba el deadline incluso después de salir de retry. Consultas vencidas no se
ejecutan ni reintentan; se diagnostican/DLQ, sin respuestas fuera de plazo. Un error
de negocio esperado (403/404, etc.) genera respuesta correlacionada y ACK, no retry.
Payload inválido, autorización del sobre inválida y fallo técnico agotado → DLQ.
Un timeout HTTP no garantiza que la consulta nunca haya sido ejecutada.

El consumer hace ACK solo después de confirm positivo de respuesta sin return.
La transferencia a retry sigue la misma regla. mandatory=true, confirms
correlacionados y returns son obligatorios. Si el handoff falla no se confirma el
original: aplicar recuperación controlada del canal/consumer con backoff, sin loop
inmediato ni requeue=true como retry normal. El mecanismo exacto se valida en el
issue de base común con pruebas reales, sin política paralela a #68.

## Autorización verificable

BFF valida Entra y emite contexto de actor verificable entre servicios; por ejemplo,
sobre firmado con claves gestionadas fuera de Git. Identidad/tenant/roles enviados
como campos sin autenticidad son insuficientes. El consumer verifica integridad,
emisor, plazo y destino, y vuelve a aplicar pertenencia/tenant/perfil activo según
la operación actual. replyTo se restringe al destino permitido. No transportar JWT
original ni secretos. Usuarios no dispone automáticamente del contexto HTTP en el
listener: compartir la lógica con actor explícito, sin omitir sus controles.
El formato concreto del contexto verificable se especifica y revisa en el issue
de base común antes de implementar consumers; no altera seguridad Entra existente.

## Carrito: contrato y atomicidad

VaciarCarritoPorPedido V1 incluye messageId, type, version, occurredAt, pedidoId,
carritoId, expectedCarritoVersion y referencia de propietario/tenant verificable.
La creación de Pedido debe capturar una vinculación comprobada con el carrito y
su snapshot. Hoy no existe esa garantía: no confiar solamente en IDs/version
aportados por el cliente ni asumir equivalencia entre usuarioId y objectId Entra.

Guardar Pedido + intención de vaciado en la misma transacción mediante outbox en
Pedidos. Consumer con deduplicación persistente y control concurrente de versión:
coincidencia → vaciar + registrar resultado + commit → ACK; duplicado → ACK;
versión posterior → omitir vaciado, registrar resultado y ACK; vínculo inválido →
DLQ; error temporal → un retry corto y después DLQ. No borrar productos nuevos.
El método vaciar() actual depende de identidad HTTP y requiere adaptación local
condicionada; no llamarlo ciegamente desde el listener.

## Conteo aprobado

| Recurso | Cantidad |
|---|---:|
| Colas funcionales | 6 |
| Retry queues (5 simples + 3 Pedidos) | 8 |
| DLQ | 6 |
| Cola técnica de respuestas BFF | 1 |
| Total queues en vhost negocio | 21 |
| Exchanges personalizados | 7 |

No incluye artefactos de pruebas, sandbox RabbitAdmin ni exchanges incorporados
del broker. No agregar una segunda cola funcional por servicio sin caso real aprobado.

## Plataforma, diagnóstico y replay

Colas funcionales/retry/DLQ durables, mensajes persistentes, policies y permisos
mínimos coordinados en #69. Preservar requisitos quorum y dead-lettering
at-least-once/overflow reject-publish/delivery-limit de Pedidos; #69 verificará
compatibilidad de argumentos/tipos existentes y fijará matriz por cola antes de
declarar. No redeclarar una cola classic como quorum sin plan compatible.

Diagnóstico por messageId/correlationId, dominio, retry-count, clase de error,
destino y resultado; no registrar tokens, secretos ni perfiles completos.
DLQ sin replay automático. Inspeccionar, corregir causa y verificar estado real;
republicar con mandatory/confirm/return, conservar messageId y añadir metadatos de
replay; ACK de DLQ solo tras publicación aceptada. Si falla, conservar mensaje.
Consultas vencidas no se reproducen con deadline antiguo ni se resucitan bajo el
mismo request HTTP: el solicitante inicia una consulta nueva autorizada. El comando
de Carrito siempre vuelve a validar versión/propietario antes de producir efectos.

## Plan, riesgos y orden

#68 conserva reliability avanzada Pago → Pedido y no absorbe consumers nuevos.
#69 prepara plataforma de 21 queues/7 exchanges. #70 integra/corta por flujo con
flags, exclusión de coordinadores y rollback. HTTP público permanece; HTTP sigue
predeterminado hasta cortes probados. El vaciado HTTP del frontend y el comando
no pueden habilitarse en paralelo sin una estrategia explícita de transición.

Orden: versionar adenda → base request/reply y plataforma suficiente → consultas
Usuarios/Restaurantes/Productos/Pagos. En paralelo completar #68. Carrito requiere
contrato, base de mensajería, outbox/vinculación y plataforma. Después integración
local/regresión → #70 → AWS/evidencias. Ver [plan](PLAN-ACTIVIDADES.md) para issues.

Riesgos: pérdida de autorización fuera de HTTP; vaciado tardío; dual-write; ACK antes
de handoff; respuestas tardías/duplicadas; TTL que renueve deadlines; tipos de cola
incompatibles; doble ejecución durante corte; consumo de recursos/backlog. Medir
CPU/RAM/disco en #69. La cantidad de colas no autoriza cluster ni cambios AWS.

Contraste: servicios reales y Graphify; UsuarioService.obtenerActual(),
RestauranteService.listar(), ProductoService.listarDisponiblesPorRestaurante(),
PagoService.obtener(identidad,id), CarritoService y Carrito.@Version. El frontend
RealConfirmarPedidoPanel crea Pedido y luego llama DELETE /carrito. Estas operaciones
existen; los adapters RabbitMQ adicionales todavía no están implementados.
