# Issue #70 — Fase 1: consultas BFF

Base: `develop` / `be54492428ad62dfcc272de8becacac708354108`. Implementación local opt-in,
pendiente de auditoría independiente. HTTP sigue siendo oficial y predeterminado; este PR no
autoriza corte, despliegue ni cierre de #70/#81.

## Compatibilidad y selección

| Ruta HTTP BFF | Operación RabbitMQ existente | Payload request | Payload success | Modo por defecto |
|---|---|---|---|---|
| GET /usuarios/me | usuario.consultar-actual.v1 | `{}` | Objeto: ocho campos públicos, tras verificar y retirar IdentityProof | HTTP |
| GET /restaurantes | restaurante.listar.v1 | `{}` | Array de RestauranteDto, incluido `[]` y restaurantes INACTIVOS | HTTP |
| GET /productos/restaurante/{id}/disponibles | producto.listar-disponibles.v1 | restauranteId positivo | Array de ProductoResponse disponible del restaurante solicitado, incluido `[]` | HTTP |
| GET /pagos/{id} | Usuarios → pago.consultar.v1 | pagoId positivo + pruebaIdentidad verificada | Objeto: ocho campos de PagoResponse | HTTP |

Las demás rutas (detalle de restaurante/producto, todos los productos, productos sin filtro de
disponibilidad, pagos por pedido, escrituras, Pedidos y Carrito) conservan HTTP. No se simulan
consultas ausentes filtrando otro resultado ni se agrega RPC. DTO públicos, Pago.usuarioId,
tenant-aware, histórico, Pago→Pedido/outbox y Pedido→Carrito permanecen intactos.

Variables nuevas independientes: `BFF_QUERY_USUARIOS_MODE`, `BFF_QUERY_RESTAURANTES_MODE`,
`BFF_QUERY_PRODUCTOS_MODE`, `BFF_QUERY_PAGOS_MODE`: solo `HTTP` o `RABBITMQ`; default `HTTP`.
Se selecciona un transporte antes de realizar llamadas. Un error, timeout, return, nack o confirm
incierto de RabbitMQ devuelve error; nunca invoca automáticamente HTTP como alternativa.

`RABBITMQ` exige el adaptador disponible (`PEDIDOS360_RELAY_MODE=ACTIVE`) y claves ActorContext
ES256 válidas. Usuarios/Pagos exigen `PEDIDOS360_IDENTITY_PROOF_ENABLED=true` y públicas de Usuarios
independientes. El modo Pagos siempre consulta Usuarios por RabbitMQ dentro de la misma operación,
aunque el endpoint /usuarios/me conserve HTTP. No se cambian valores operativos de estas variables.

## Identidad y deadline

La cadena usa JWT autenticado por la seguridad existente, ActorContext firmado para cada destino,
Usuarios real, verificación independiente de firma/tenant/OID/id local/actividad/request original/D,
Pagos con la misma prueba y validación de la respuesta correlacionada. El JWS solo queda en un
resultado interno del paquete, con toString redactado, y en el request interno de Pagos. No se
devuelve, persiste ni registra como DTO público.

Un único QueryOperationBudget: D se recorta por JWT; publicación, espera, resolución y serialización
HTTP consumen el mismo presupuesto monotónico. Solo se consulta Usuarios una vez, sin renovar
prueba ni D. R de Pagos se recorta al mínimo del deadline/actor de Usuarios y E−margen; el actor de
Pagos se recorta a R. Se conserva `R <= E−margen <= E <= D` y `actor.expiraEn <= R`.
Antes del retorno público se revalida el límite; también en los errores 403/404. Al vencer se
invalida el presupuesto y se descarta la correlación. Duplicados/huérfanos no completan otra espera.

## Parser y frontera de confianza

ResponseSchema es único y compartido por ResponseConsumer, BffQueryAdapter y diagnósticos/tests.
Usa una tabla cerrada de operaciones canónicas: Usuarios/Pagos objeto, catálogo array. Mantiene
ocho campos exactos, duplicate detection, tipos, status, UUID y timestamp. RequestEnvelope.payload
no cambia. En el listener, la forma es un precheck estructural; no acredita autorización. En el BFF
se acreditan messageId/correlationId/operación esperada y presupuesto antes de aceptar la forma.
Después se verifica IdentityProof y el DTO público; no se confía solo en la operación remota.

## Rollback y condiciones operativas

Para futuras operaciones autorizadas, retornar los modos de consultas a HTTP afecta solicitudes
nuevas; dejar terminar o agotar las correlaciones en vuelo antes de detener el listener BFF.
No reenviar solicitudes inciertas por HTTP ni purgar mensajes pendientes. Relay DISABLED también
requiere que los cuatro modos vuelvan a HTTP; una selección incompatible falla al arrancar.
Este rollback no cambia coordinación Pago→Pedido ni restaura autorización anterior sin tenant.

El guard monotónico es por operación/entrega: no existe terminalidad durable global ni fencing
físico de publish de propietarios obsoletos. Retrocesos arbitrarios no detectados entre procesos
pueden reactivar ejecución; se depende de sincronización operativa de relojes. Correlaciones son
locales al proceso BFF: la cola compartida no acredita escalamiento horizontal de varios BFF.
Se preservan confirms, returns, retry único, recuperación ante incertidumbre y timeout propio de
DLQ diagnóstica; no hay exactamente una vez.

Permisos DLX operativos, claves operativas/TLS, sincronización temporal y requisitos de despliegue
PR #94 (roles migrador/runtime, propietario SECURITY DEFINER, red de endpoints internos, corte de
binarios antiguos y reconciliación verificable) siguen pendientes fuera de los contenedores.
No se crean migraciones ni ledger; no se altera el inventario 21/7/20/21.

## Evidencia

Commit de código probado: `257c69f2ea626c9aa271cfd2d84e6613d3c6b780`.
La adición posterior de este informe y del JSON no cambia código ni pruebas.

| Suite completa | Casos | Aprobados | Omitidos |
|---|---:|---:|---:|
| Core (install) | 249 | 249 | 0 |
| BFF | 203 | 203 | 0 |
| Usuarios | 79 | 79 | 0 |
| Restaurantes | 34 | 34 | 0 |
| Productos | 44 | 44 | 0 |
| Pedidos | 131 | 130 | 1 |
| Pagos | 187 | 186 | 1 |
| Total | 927 | 925 | 2 |

Cero fallos/errores en las ejecuciones completas finales. Las dos omisiones son
WorkerEntraLiveTests / EntraWorkerLiveTests (opt-in live no habilitado). Hay 58 casos nuevos:
17 Core, 33 BFF y 8 E2E en Pagos. Las tres comparaciones catálogo antes fallidas ahora devuelven
200 y el mismo cuerpo JSON que HTTP; se agrega Restaurantes vacío y se exige resultado no vacío
en las listas de Restaurantes/Productos de demostración.

Se corrigieron dos montajes: la respuesta discordante del test BFF ahora usa una operación conocida
con forma válida para comprobar el vínculo (se conserva la aserción 502); desconocidas se rechazan
en precheck y no resuelven la espera. La espera de settlement de Pagos exige primero estadística
numérica de management y después cero; no interpreta métrica ausente como cero y conserva 5 s.
Las ejecuciones iniciales con esos montajes fallaron; no se contabilizan como aprobadas.

SHA-256 del JSON versionado: `3de8e2956e7ac0d3ce8cf4933dfa8632d96197db57e2fa3aca7cde82588e210d`.
Incluye 345 hashes de fuentes normalizados LF y los hashes binarios de logs/XML de las ejecuciones.

La evidencia final se registra en `evidencias/BFF-PHASE1-tests.json`: commit de código, fuentes con
SHA-256 normalizado LF, artefactos/logs con hash binario, casos Surefire y omisiones. Se instala
primero el core y después se ejecutan las siete suites completas.

BffQueriesEndToEndTests compila las fuentes reales de BFF/Usuarios/Restaurantes/Productos y arranca
esas cuatro aplicaciones más Pagos en el mismo JVM, con listeners reales, PostgreSQL 17 y RabbitMQ
4.1.8 desechables. Entra utiliza una autoridad RSA local; ActorContext e IdentityProof usan claves
ES256 efímeras en memoria. No hay respuestas remotas simuladas. Se aprovisiona solo un subconjunto
de colas classic de consulta sin retry para este E2E; no demuestra toda la plataforma quorum.
Las exclusiones Security en el montaje de catálogo reproducen su POM real, que excluye el starter;
no se modifica seguridad productiva. La base se aprovisiona con administrador de contenedor y no
acredita roles operativos. HTTP y Rabbit se comparan mediante los endpoints BFF reales.

Las suites existentes de cada consumer cubren brokers quorum/policies/retry/DLQ y PostgreSQL reales;
fallos de servicio seleccionados son inyectados. BffConsultasAdapterTests usa broker/listener BFF
real con servicios de respuesta fixture. BffQueryHttpAdapterTests, BffCatalogResponseBindingTests y
BffTemporalBarrierTests inyectan publicador/reloj, con validadores criptográficos reales; no son E2E
de red. No se presentan inyecciones como particiones reales ni omisiones Entra como pruebas live.
