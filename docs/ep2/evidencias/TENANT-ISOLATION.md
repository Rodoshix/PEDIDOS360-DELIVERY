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

GET /internal/pedidos/{id}/resumen-pago, Bearer delegado original, Cache-Control: no-store.

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

ConfirmarPedidoPorPago V1 conserva seis campos sin tenant. Worker sigue siendo de un único tenant, no un bus multitenant general. No se implementa consumer Pagos #81 ni BFF #70. No cambian contratos, deadlines o handoff diagnóstico de PR #92/#93.

## Evidencia de pruebas

TENANT-ISOLATION-tests.json contiene evidencia nueva: resultados Surefire y hashes de fuentes/reportes/logs. No se reutilizan las 573 pruebas del PR #93 como ejecuciones nuevas.

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

721 pruebas registradas: 719 exitosas, 2 omitidas, 0 fallos y 0 errores. Siete suites completas, ejecutadas en esta rama.

| Suite | Total | Exitosas | Omitidas |
|---|---:|---:|---:|
| backend/services/pedidos-service | 131 | 130 | 1 |
| backend/services/pagos-service | 96 | 95 | 1 |
| backend/bff | 140 | 140 | 0 |
| backend/services/usuarios-service | 79 | 79 | 0 |
| backend/shared/p360-messaging-core | 197 | 197 | 0 |
| backend/services/restaurantes-service | 34 | 34 | 0 |
| backend/services/productos-service | 44 | 44 | 0 |

Omitida: cl.duoc.pedidos360.pedidos.WorkerEntraLiveTests.workerConfirmaIdempotentementePeroNoAccedeComoUsuario (Entra live condicionado por entorno).

Omitida: cl.duoc.pedidos360.pagos.client.EntraWorkerLiveTests.autenticaConProveedorReal (Entra live condicionado por entorno).

Los fallos encontrados durante preparación (referencia de fixture, search_path de trigger y comentario Java) se corrigieron antes de estas ejecuciones finales; no se relajaron expectativas de negocio para hacer pasar pruebas.

## Archivos del PR

- backend/bff/src/test/java/cl/duoc/pedidos360/bff/client/ComercioInternalIsolationTests.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/client/PedidoResumen.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/client/PedidosRestClient.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/entity/Pago.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/entity/TenantOrigin.java
- backend/services/pagos-service/src/main/java/cl/duoc/pedidos360/pagos/exception/PagoExceptionHandler.java
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
