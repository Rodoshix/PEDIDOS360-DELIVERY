# Aislamiento tenant-aware previo a #81

Base: develop, 4c061d059d95102d1a10d3660c0182e58b20bcf8.
Rama: feature/81-tenant-isolation-pedidos-pagos.

## Identidad y autorización

IdentidadUsuario(UUID tenantId, Long usuarioId, Set<Rol> roles) exige tenant, ID positivo y roles inmutables no vacíos. Tenant desde tid del JWT validado; ID local desde Usuarios /usuarios/me con el mismo Bearer; roles desde autoridades permitidas. No se modifican UsuarioService, validadores JWT, actividad ni /usuarios/me.

LOCAL_TENANT_ID es obligatorio si se habilita identidad local. Se conservan perfil exclusivamente local, loopback y exclusión mutua con JWT. No hay tenant por defecto ni identidad tomada de headers, parámetros o mensajes.

| Recurso individual | Resultado |
|---|---|
| CLIENTE propio, mismo tenant | Permitido |
| CLIENTE ajeno, mismo tenant | 403 |
| ADMIN ajeno, mismo tenant | Permitido |
| Externo, inexistente o UNKNOWN | 404 uniforme |
| RECONCILED_LEGACY autorizado | Lectura; mutación 409 uniforme |

Los controles funcionales siguen siendo necesarios: aprobar cobros y gestionar estados requiere ADMIN. Colecciones filtradas pueden devolver 200 []. No se habilita /usuarios/{id}/pedidos en JWT ni BFF. Pago.usuarioId sigue siendo el registrador; Pedido.usuarioId, el propietario. Los filtros viven en servicios y consultas, también recargas y recuperación. Los probes escalares sin tenant detectan colisiones de restricciones globales; no devuelven entidades ajenas y ocultan las colisiones mediante 404.

## Proyección interna: contrato definitivo

GET /internal/pedidos/{id}/resumen-pago, Bearer delegado original en producción, Cache-Control: no-store. En desarrollo exclusivamente local/loopback, identidad interna autenticada y configurada con tenant explícito, sin fabricar JWT.

Respuesta 200 con exactamente siete campos:

| Campo | Validación |
|---|---|
| pedidoId | Entero positivo, igual al solicitado |
| tenantId | UUID canónico persistido, igual al tenant autenticado |
| usuarioId | Entero positivo del propietario del Pedido |
| estado | Estado actual válido de Pedido |
| total | Entero no negativo dentro de Long |
| moneda | CLP, moneda actual del servicio |
| tenantOrigin | AUTHENTICATED_NEW o RECONCILED_LEGACY persistido |

Se rechazan campos ausentes, null, extras, duplicados, tipos coercibles, estructura incorrecta y JSON adicional. No hay fallback al DTO público. Una dependencia inválida falla sin conceder autorización; tenant discordante produce 404. El histórico reconciliado permite lectura; registrar Pago exige AUTHENTICATED_NEW antes de abrir la transacción que guarda Pago e intención outbox.

La política Pedidos.Confirmar solo cubre la confirmación. El token de worker no autoriza esta lectura. El endpoint acredita al usuario delegado, no demuestra exclusividad del proceso Pagos; necesita controles de red en despliegue. BFF no publica /internal/**. PedidoResponse, PagoResponse y /usuarios/me no cambian. Sí cambia la semántica autorizada de ADMIN y 404, y se añade esta proyección exclusivamente interna.

## Migraciones y procedencia

Nuevas versiones: Pedidos V2__aislamiento_tenant.sql y Pagos V4__aislamiento_tenant.sql. No se editan migraciones previas. Añaden tenant_id UUID NULL sin default tenant, y tenant_origin. UNKNOWN exige tenant NULL; AUTHENTICATED_NEW y RECONCILED_LEGACY exigen tenant no nulo. Histórico existente permanece UNKNOWN, sin backfill inferido por ID, email o configuración.

Las altas ordinarias exigen AUTHENTICATED_NEW y tenant no nulo; PostgreSQL rechaza inserts antiguos que omiten procedencia. Tenant y origen acreditados son inmutables. Histórico no admite mutaciones ordinarias, también en líneas de Pedido. Se conservan unicidad por registrador/clave y Pago activo por Pedido. No se añaden índices: se conservan PK, índices usuario/Pedido y el índice de pendientes del outbox. Índices futuros requieren volumen y EXPLAIN reales.

Pedido nuevo obtiene tenant del actor autenticado. Pago nuevo lo obtiene del Pedido persistido tras exigir igualdad con el actor. ADMIN no reemplaza Pago.usuarioId por el propietario del Pedido.

La tabla tenant_reconciliation_audit y la función reconcile_tenant preparan una operación futura controlada. Requieren recurso, versión esperada, tenant y SHA-256 de evidencia; bloquean la fila, mantienen los campos de negocio y registran operador de sesión, fecha y procedencia en la misma transacción local. Una repetición idéntica es idempotente; contradicciones fallan. PUBLIC no recibe ejecución de la función ni escritura en auditoría. No hay endpoint o privilegio ADMIN público para reconciliar.

Prerequisito operativo: runtime no puede ser propietario de esquema/tablas/funciones, superusuario o heredero del rol migrador/reconciliador. La migración no crea roles operativos ni modifica credenciales. Un propietario puede eludir protecciones; verificar separación efectiva antes de desplegar. Las pruebas verifican denegación con un rol runtime separado en PostgreSQL desechable.

La función registra la referencia de evidencia; no acredita por sí sola su suficiencia. El futuro operador debe comprobar evidencia, namespace de IDs, relaciones y versiones. Primero Pedido, luego sus Pagos. No hay transacción entre bases: reconciliación parcial no habilita Pago desconocido. Reconciliar tenant permite lectura, no libera trabajo o mutaciones pendientes.

## Idempotencia, workers y reliability

Replay de Pago nuevo del tenant/registrador correcto devuelve resultado sin otra intención ni confirmación. Pago reconciliado se rechaza sin reactivarlo. Carreras conservan restricciones PostgreSQL; colisiones con UNKNOWN u otro tenant se ocultan sin debilitar unicidad.

AUTHENTICATED_NEW no puede transformarse en histórico mediante escritura ordinaria: tenant/origen son inmutables en PostgreSQL. El estado de negocio puede cambiar tras la lectura remota; se conserva coordinación recuperable, sin nuevo RPC ni transacción distribuida.

Recuperación HTTP selecciona solo AUTHENTICATED_NEW del tenant configurado y revalida antes de actualizar. Confirmación interna comprueba tenant del JWT de aplicación y contexto de worker. Processor RabbitMQ V1 toma tenant solo de configuración confiable. UNKNOWN no se confirma; RECONCILED_LEGACY permanece retenido, con razón diagnóstica HISTORICO_RETENIDO.

Claim outbox une Pago para filtrar tenant y AUTHENTICATED_NEW, conservando FOR UPDATE OF o SKIP LOCKED: bloquea outbox, no Pago padre. Leases, fencing, recuperación de leases vencidos, publisher confirms, returns, atomicidad Pago-outbox, UUID, payload y estados permanecen. Histórico pendiente queda retenido incluso después de reconciliar tenant; no se recrea/republica ni se marca artificialmente PUBLISHED.

## Correcciones autorizadas de la auditoría de PR #94

HEAD previo auditado: 51a15a4bf65e453f52c3f0e686dd73658fa13fb3. Correcciones limitadas a P1 (settlement outbox) y P2 (HTTP local). El HEAD final se identifica en GitHub y los hashes normalizados del JSON vinculan las fuentes probadas sin incluir un hash circular del propio commit.

**P1.** Claim conserva messageId, pagoId, pedidoId persistido, tenantId autorizado, lease token y payload original. finish y block comparten un único UPDATE condicional: UUID, estado IN_FLIGHT, mismo Pago/Pedido/payload, token, lease vigente por reloj de PostgreSQL, igualdad entre tenant reclamado y tenant del worker, y Pago asociado del mismo tenant con AUTHENTICATED_NEW. Un claim construido para un Pago externo no concede autorización. Los atributos de tenant/origen del Pago siguen protegidos por su trigger previo.

V4 añade guard_outbox_identity: SQL directo no puede cambiar message_id, pago_id o payload. Conserva actualizaciones legítimas de estado, intentos y leases de trabajo nuevo; retiene mutaciones/borrado de outbox histórico. No modifica V1–V3. El trigger comprueba además el vencimiento después de obtener el lock: si un settlement esperó hasta agotar el lease, omite el UPDATE y produce cero filas, incluso si no cambió la versión de la fila bloqueada.

finish/block devuelven éxito solo con una fila actualizada. Ante cero filas el dispatcher informa NOT_SETTLED o BLOCK_NOT_SETTLED y termina esa pasada; nunca informa PUBLISHED/BLOCKED como si hubiera finalizado. Si RabbitMQ confirmó, registra BROKER_CONFIRMED separado de la finalización en base y no publica de nuevo inmediatamente. Mantiene recuperación durable del lease con el mismo UUID/payload, fencing frente a un lease nuevo y retención histórica. Se conserva la semántica al menos una vez: una recuperación posterior a una confirmación sin settlement puede repetir entrega; la idempotencia existente es necesaria. No se promete exactly-once.

**P2.** PedidosRestClient conserva JwtAuthenticationToken autenticado en producción. La alternativa local requiere el bean IdentidadUsuario configurado, perfil únicamente local, server.address loopback, tenant explícito coincidente y Entra deshabilitado; acepta solo el principal autenticado igual a esa identidad. No toma identidad de headers ni usa fallback ante JWT inválido/ausente. El tenant de los siete campos sigue obligado a coincidir con el del actor.

### Regresiones permanentes y reproducciones corregidas

Los nombres siguientes pertenecen a clases de Pagos; el JSON conserva los casos y hashes de los reportes de la ejecución específica.

| Requisito | Evidencia |
|---|---|
| A, claim válido | OutboxIsolationTests.validClaimAndFinalizationPreserveAssociation |
| B/D, claim y settlement externos | foreignClaimExcludedAndFinishBlockDenied; incluye claim con tenant falsificado |
| C, lease distinto | staleLeaseCannotFinishOrBlockNewLease |
| E/F, histórico | unknownCannotFinishOrBlock; reconciledCannotFinishOrBlock |
| G/I, reasociación SQL antes de finalizar | runtimeCannotChangePaymentAssociationBeforeFinish; SQLState 23514 y asociación original |
| H, reasociación mientras finish espera | associationCannotChangeWhileFinishWaitsForConcurrentWriter; lock observado en pg_stat_activity |
| J/K, UUID/payload | runtimeCannotChangeMessageUuid; runtimeCannotChangeOriginalPayloadButCanUpdateMetadata |
| L, block con idénticas garantías | Casos externos/históricos/lease incorrecto/claim incorrecto prueban block y finish; block legítimo queda BLOCKED |
| M, cero filas y efectos | incorrectAssociationPayloadOrClaimTenantAffectsZeroRows; RabbitCoreTests.brokerConfirmWithFencedCompletionReportsUnsettledWithoutSecondSend; blockAffectingZeroRowsReportsUnsettledAndDoesNotPublish |
| N, concurrencia/fencing | competingPublishersOnlyOneCanSettleSameLease; simultaneousClaimsUseSkipLockedAndDifferentLeases |
| O, histórico junto a altas nuevas | RabbitCoreTests.retainedOldOutboxAndNewPaymentPublishOnlyNewCommand; replay no añade intención ni entrega |
| Lease vencido y recuperación | expiredLeaseRetainsWorkUntilRecoveryWithStableIdentity; leaseExpiringWhileFinishWaitsCannotBeSettled |
| Upgrade y privilegios | TenantMigrationTests.outboxUpgradeRetainsOriginalCommandAndGuardsRuntimeWithSeparatedMigrationOwner |

OutboxIsolationTests usa PostgreSQL 17 real para CAS, SQL runtime y locks. La prueba de migración ejecuta V1–V3, inserta Pago/comando histórico y aplica V4 con un login migrador no superusuario; otro login runtime mínimo no puede reconciliar, escribir auditoría ni deshabilitar el trigger, y sí puede procesar trabajo nuevo. Los fixtures/roles pertenecen exclusivamente a contenedores desechables. La limpieza después de cada caso evita contaminar otras suites que comparten el contenedor.

RabbitCoreTests usa RabbitMQ real para confirm/routing y comprueba una entrega sin envío inmediato alternativo cuando el settlement fue fenced. La sustitución de lease y la rama de payload inválido son inyecciones controladas, no particiones reales de red. La prueba de dos publishers verifica la competición real de settlement en PostgreSQL; no acredita despliegues simultáneos de servicios operativos.

TenantLocalHttpEndToEndTests arranca las dos aplicaciones HTTP de producción y PostgreSQL desechable. Compila las fuentes reales de Pedidos desde el repositorio con JDK 21 y -parameters, sin añadir dependencia entre servicios ni un stub de Pedidos. Sus primeros siete casos verifican: proyección local exacta y POST 201; intención Pago+outbox en coordinación RabbitMQ sin enviar al broker; tenant discordante sin escritura; UNKNOWN/externo/inexistente 404; configuración local ausente 401 pese a headers falsificados; rechazo de no-loopback y local+Entra; JWT válido en modo producción y rechazo de JWT inválido, tenant incorrecto o ausencia de token. Los flujos locales no instalan JWT. Solo el caso JWT usa claves RSA firmadas de fixture y un stub HTTP de Usuarios; no ejecuta Entra live. El octavo caso de integridad se describe a continuación.

### P1 adicional: integridad Pago–Pedido del comando V1

HEAD auditado de esta corrección: 2e5fd9e6dc21b24361150624b83c9f650bc4c80d. La auditoría reprodujo con PostgreSQL y RabbitMQ reales un outbox de Pago para Pedido 101 cuyo JSON apuntaba al Pedido 201: se publicó, quedó PUBLISHED y el processor confirmó 201 mientras 101 seguía CREADO. El flujo legítimo crear(Pago) generaba referencias coherentes, pero el INSERT runtime no las acreditaba; la inmutabilidad del payload conservaba también un contenido inicialmente incorrecto. El filtro de tenant de Pedidos evitaba efectos externos, sin impedir la publicación incoherente ni los efectos sobre otro Pedido del mismo tenant.

Ahora Claim obtiene pedidoId mediante pedidoIdReclamado: consulta tenant-aware sobre el Pago persistido, dentro de la misma transacción después de FOR UPDATE OF o SKIP LOCKED. No lo toma del JSON. Antes de publicar, dispatcher exige igualdad de messageId, pagoId y pedidoId con Claim y mantiene las validaciones V1 previas. Una discrepancia utiliza block() y su UPDATE protegido; no reescribe bytes ni UUID, no publica, no regenera comando y no añade retries funcionales. El settlement revalida también pedidoId persistido; NOT_SETTLED sigue conservando recuperación sin falso bloqueo.

V4 añade guard_payment_order, que impide todo cambio ordinario de Pago.pedido_id desde la inserción. Se elige esta garantía en vez de comprobar solo existencia de outbox: evita la carrera INSERT outbox/UPDATE Pago y no añade locks del padre al claim. No hay ruta, setter o UPDATE de aplicación que reasigne el Pedido de un Pago; sus cambios legítimos de estado y confirmación siguen permitidos. Tenant/origen y sus reglas de reconciliación permanecen intactos.

guard_outbox_identity valida además, exclusivamente al INSERT, las referencias del JSON original: UUID igual a message_id, pagoId numérico igual a pago_id, pedidoId numérico igual al Pedido del padre AUTHENTICATED_NEW. JSON inválido, ausente o incoherente falla con SQLState 23514. Esta defensa comprueba referencias, sin presentar el trigger como un validador completo del protocolo; dispatcher/processor conservan sus restantes restricciones. No cambia datos existentes ni verifica/republica automáticamente histórico. Un outbox preexistente corrupto de trabajo nuevo puede ser reclamado y bloqueado conservando sus bytes; UNKNOWN y RECONCILED_LEGACY no son reclamados.

| Caso autorizado | Regresión permanente |
|---|---|
| A/L: comando legítimo, Pedido correcto, V1/UUID | TenantLocalHttpEndToEndTests.realBrokerCommandConfirmsOnlyPersistedPaymentOrderViaManualProcessor; RabbitCoreTests.tarjetaCommitPublicacionRealYPayloadExacto |
| B/C/D/E: referencias de otro Pago/Pedido/tenant o UUID discordante | RabbitCoreTests.inconsistentInitialPayloadsAreBlockedBeforeAnyBrokerPublication; cuatro variantes, BLOCKED y cola real vacía. El caso entre servicios también usa Pedidos persistidos reales de ambos tenants |
| F: INSERT runtime incorrecto | OutboxIsolationTests.runtimeInsertCannotIntroduceInconsistentReferences; seis variantes, SQLState 23514 y cero filas |
| G: estabilidad antes y después del outbox | paymentOrderImmutableBeforeAndAfterOutboxInsert; también login runtime separado en TenantMigrationTests |
| H: UPDATE frente a claim e INSERT | concurrentOrderUpdateCannotDivergeFromClaimAndPublicationAssociation; concurrentPaymentUpdateAndOutboxInsertRetainOriginalOrder; bloqueo SQL observado en pg_stat_activity |
| I: histórico retenido | unknownCannotFinishOrBlock; reconciledCannotFinishOrBlock; retainedOldOutboxAndNewPaymentPublishOnlyNewCommand; upgrade desde V3 conserva bytes/origen/estado |
| J: block sin settlement | blockAffectingZeroRowsReportsUnsettledAndDoesNotPublish; inyección de lease, SQL real, BLOCK_NOT_SETTLED sin publicación |
| K: fencing/SKIP LOCKED | staleLeaseCannotFinishOrBlockNewLease; competingPublishersOnlyOneCanSettleSameLease; simultaneousClaimsUseSkipLockedAndDifferentLeases |

La prueba nueva de broker/processor registra Pago e intención a través del HTTP local real de ambos servicios, publica con confirms/returns en RabbitMQ desechable, verifica los seis campos V1 y el UUID, recibe el mensaje y después invoca manualmente el bean processor de Pedidos. Comprueba que solo su Pedido queda CONFIRMADO y otro queda CREADO. Añade comandos incoherentes hacia Pedidos realmente persistidos del mismo tenant y de otro tenant: quedan BLOCKED, sin entregas y sin modificar sus Pedidos. No ejecuta el listener/ACK de esas entregas. Los casos corruptos usan un fixture de owner en contenedor para representar corrupción previa, restauran el trigger y prueban la barrera del dispatcher y ausencia de entregas; no afirman que runtime pueda eludir el nuevo INSERT guard. Las pruebas SQL runtime y migrador separado siguen sin privilegios operativos.

ConfirmarPedidoPorPago V1 conserva seis campos sin tenant. Worker sigue siendo de un único tenant, no un bus multitenant general. No se implementa consumer Pagos #81 ni BFF #70. No cambian contratos, deadlines o handoff diagnóstico de PR #92/#93.

## Evidencia de pruebas

TENANT-ISOLATION-tests.json contiene evidencia nueva posterior a la corrección de integridad: resultados Surefire y hashes de fuentes/reportes/logs. No se reutilizan las 573 pruebas del PR #93, las 719 exitosas iniciales de PR #94 ni las 744 del HEAD previo como ejecuciones del estado corregido. Las siete suites se ejecutaron nuevamente; los totales históricos quedan identificados separadamente.

Migraciones: PostgreSQL 17 desechable, ejecutar versión anterior, insertar histórico, actualizar, verificar UNKNOWN, escritores antiguos, inmutabilidad, reconciliación idempotente y denegación runtime. Otras pruebas usan fixtures históricos explícitos en contenedores. Locks, unicidad y leases se prueban con PostgreSQL real. Suites outbox/consumer usan RabbitMQ real para payload, confirms, returns y procesamiento. Fallos inyectados no demuestran particiones reales de red. Entra live no se considera ejecutada cuando está omitida.

## Despliegue y rollback: no ejecutados

1. Inventariar versiones, bases, namespaces, escritores, jobs, consumidores y roles.
2. Suspender/drenar tráfico y tareas antiguos. No hacer rolling con lectores sin aislamiento; los triggers de inserts no vuelven seguros esos lectores.
3. Aplicar migraciones autorizadas con rol separado.
4. Desplegar Pedidos/proyección y después Pagos/cliente coordinadamente.
5. Validar procedencia, matriz, contrato y retención del histórico.
6. Rehabilitar HTTP y solo los automatismos previamente autorizados.

Se necesita corte controlado de operaciones afectadas sin ejecutar negocio durante rechazo temporal; no se garantiza cero interrupción. Rollback solo a una versión con aislamiento, manteniendo esquema aditivo. No volver a ADMIN global, resumen sin tenant, escritores antiguos o republicación histórica.

HTTP oficial; relay/prueba DISABLED por defecto. Sin cambios de topología 21/7/20/21, permisos RabbitMQ, AWS o claves operativas. Sin migraciones/reconciliación sobre datos operativos. Riesgos residuales: compromiso de servicio/base privilegiada, evidencia administrativa incorrecta, IDs importados incompatibles y garantías de red/roles no verificadas en despliegue real. #81 permanece pendiente, sin cierre automático.

## Resultado de la ejecución final

752 pruebas registradas: 750 exitosas, 2 omitidas, 0 fallos y 0 errores. Siete suites completas ejecutadas de nuevo tras la corrección de integridad, cada comando con exit code 0. Hay seis casos nuevos frente al HEAD 2e5fd9e6dc21b24361150624b83c9f650bc4c80d, además de ampliar casos existentes. Primero se ejecutaron las 65 regresiones específicas (0 fallos/errores/omisiones); están incluidas en el total de las suites y no se suman dos veces. El JSON conserva sus reportes separados y hashes, y distingue los totales del HEAD previo. Después de ampliar el caso entre servicios con Pedidos reales de ambos tenants se repitieron las regresiones específicas y la suite completa de Pagos; los reportes finales corresponden a esas fuentes definitivas.

| Suite | Total | Exitosas | Omitidas |
|---|---:|---:|---:|
| backend/services/pedidos-service | 131 | 130 | 1 |
| backend/services/pagos-service | 127 | 126 | 1 |
| backend/bff | 140 | 140 | 0 |
| backend/services/usuarios-service | 79 | 79 | 0 |
| backend/shared/p360-messaging-core | 197 | 197 | 0 |
| backend/services/restaurantes-service | 34 | 34 | 0 |
| backend/services/productos-service | 44 | 44 | 0 |

Omitida: cl.duoc.pedidos360.pedidos.WorkerEntraLiveTests.workerConfirmaIdempotentementePeroNoAccedeComoUsuario (Entra live condicionado por entorno).

Omitida: cl.duoc.pedidos360.pagos.client.EntraWorkerLiveTests.autenticaConProveedorReal (Entra live condicionado por entorno).

Las ejecuciones de preparación detectaron problemas del harness: SET ROLE sobre conexión pooled, compilación del servicio hermano sin -parameters y fixtures de outbox sin limpieza final. Se corrigió el aislamiento/compilación del harness, sin relajar expectativas funcionales. Los hashes/resultados corresponden a las ejecuciones finales posteriores; los logs fallidos de preparación no se presentan como evidencia exitosa.

## Archivos del PR

- backend/bff/src/test/java/cl/duoc/pedidos360/bff/client/ComercioInternalIsolationTests.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/client/PedidoResumen.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/client/PedidosRestClient.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/entity/Pago.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/entity/TenantOrigin.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/exception/PagoExceptionHandler.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/messaging/OutboxDispatcher.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/messaging/OutboxRepository.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/messaging/OutboxStore.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/repository/PagoRepository.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/security/IdentidadActual.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/security/IdentidadUsuario.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/security/LocalIdentityConfiguration.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/security/LocalIdentityProperties.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/security/TenantSistema.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/service/PagoService.java
- backend/services/pagos-service/src/main/resources/application.yml
- backend/services/pagos-service/src/main/resources/db/migration/V4__aislamiento_tenant.sql
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/PagoAutorizacionTests.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/OutboxIsolationTests.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/PagoConcurrenciaTests.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/PagoEstadosTests.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/PagoIdempotenciaTests.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/PagoRecuperacionTests.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/PagoServiceTests.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/PagoTenantIsolationTests.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/PedidosClientStub.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/PedidosHttpServerStub.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/PedidosRestClientTests.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/RabbitCoreTests.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/TenantMigrationTests.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/TenantLocalHttpEndToEndTests.java
- backend/services/pagos-service/src/test/java/cl/duoc/pedidos360/pagos/security/TenantIdentityTests.java
- backend/services/pagos-service/src/test/resources/application.properties
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/controller/PedidoInternoController.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/controller/PedidoResumenPagoController.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/dto/PedidoResumenPagoResponse.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/entity/Pedido.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/entity/TenantOrigin.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/exception/ApiExceptionHandler.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/exception/PedidoHistoricoRetenidoException.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/messaging/ConfirmacionDefinitivaException.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/messaging/PedidoConfirmacionProcessor.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/repository/PedidoRepository.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/security/IdentidadActual.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/security/IdentidadUsuario.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/security/LocalIdentityConfiguration.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/security/LocalIdentityProperties.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/security/SecurityConfiguration.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/security/TenantSistema.java
- backend/services/pedidos-service/src/main/java/cl/duoc/pedidos360/pedidos/service/PedidoService.java
- backend/services/pedidos-service/src/main/resources/application.yml
- backend/services/pedidos-service/src/main/resources/db/migration/V2__aislamiento_tenant.sql
- backend/services/pedidos-service/src/test/java/cl/duoc/pedidos360/pedidos/DelegadoHttpTests.java
- backend/services/pedidos-service/src/test/java/cl/duoc/pedidos360/pedidos/PedidoAutorizacionTests.java
- backend/services/pedidos-service/src/test/java/cl/duoc/pedidos360/pedidos/PedidoConfirmacionPagoTests.java
- backend/services/pedidos-service/src/test/java/cl/duoc/pedidos360/pedidos/PedidoInternoSeguridadHttpTests.java
- backend/services/pedidos-service/src/test/java/cl/duoc/pedidos360/pedidos/PedidoRepositoryTests.java
- backend/services/pedidos-service/src/test/java/cl/duoc/pedidos360/pedidos/PedidoTenantIsolationTests.java
- backend/services/pedidos-service/src/test/java/cl/duoc/pedidos360/pedidos/RabbitConsumerTests.java
- backend/services/pedidos-service/src/test/java/cl/duoc/pedidos360/pedidos/TenantMigrationTests.java
- backend/services/pedidos-service/src/test/java/cl/duoc/pedidos360/pedidos/WorkerEntraLiveTests.java
- backend/services/pedidos-service/src/test/java/cl/duoc/pedidos360/pedidos/security/TenantIdentityTests.java
- backend/services/pedidos-service/src/test/resources/application.properties
- docs/ep2/evidencias/TENANT-ISOLATION-tests.json
- docs/ep2/evidencias/TENANT-ISOLATION.md
