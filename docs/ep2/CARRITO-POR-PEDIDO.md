# #82 — VaciarCarritoPorPedido V1

Implementación acotada sobre develop `2ae0f9e19cd9a9d7014c6e2e935adcbbe81a5269`.
HTTP sigue siendo el modo predeterminado. Este PR prepara la coordinación; no realiza el corte del checkout (#70), no modifica frontend, headers públicos, DTO, endpoints ni Pago→Pedido. Los modos RabbitMQ se ejercitan exclusivamente en contenedores desechables durante las pruebas.

## Snapshot y vínculo autenticado

`CheckoutPedido` mantiene el camino HTTP existente. Solo en modo `RABBITMQ`, antes de iniciar la transacción de escritura, `CarritoSnapshotClient` consulta el GET `/carrito` existente con el JWT delegado autenticado. `tid` y `oid` proceden del `JwtAuthenticationToken` validado por la seguridad vigente; el tenant debe coincidir con `IdentidadUsuario`. El ID local numérico no se convierte en OID.

La respuesta debe contener los siete campos públicos existentes (`id`, `restauranteId`, `moneda`, `total`, `version`, `actualizadoEn`, `items`). Se validan ID positivo, versión no negativa, restaurante y pares producto/cantidad exactos, sin duplicados. El parser HTTP usado por este snapshot rechaza claves JSON duplicadas y tokens posteriores. Total y precios del carrito **no** autorizan los precios del pedido: permanece la consulta independiente a Productos mediante `CatalogoPedidos`.

GET `/carrito` lee la raíz versionada y sus líneas dentro de una transacción `REPEATABLE_READ`. La prueba PostgreSQL bloquea la ejecución después del finder real y realiza una escritura concurrente desde otra transacción: tanto el snapshot devuelto como una segunda lectura dentro de la transacción conservan la versión anterior. El finder instrumentado se delega a un repositorio Spring Data real con el mismo `@EntityGraph`; no se sustituyen resultados por objetos fabricados.

Una modificación posterior al snapshot y anterior al commit de Pedido no invalida retrospectivamente el pedido. El consumer omite el vaciado si observa una versión superior; los productos nuevos permanecen. No existe atomicidad distribuida entre ambas bases ni locking distribuido.

## Contrato exacto

Ocho campos raíz; `propietario` contiene exactamente dos campos:

```json
{
  "messageId": "33333333-3333-3333-3333-333333333333",
  "type": "VaciarCarritoPorPedido",
  "version": 1,
  "occurredAt": "2026-10-09T00:00:00Z",
  "pedidoId": 1,
  "carritoId": 1,
  "expectedCarritoVersion": 0,
  "propietario": {
    "tenantId": "11111111-1111-1111-1111-111111111111",
    "entraObjectId": "22222222-2222-2222-2222-222222222222"
  }
}
```

UUID canónicos, IDs positivos, versión integral no negativa y timestamp UTC. El parser común del comando rechaza campos extra, faltantes, duplicados, tipos incorrectos y trailing tokens; tamaño máximo 64 KiB. No incluye JWT, JWS, precios, productos, roles ni secretos. El contenido se canonicaliza antes de guardar la recepción; reutilizar UUID con contenido semánticamente distinto se rechaza.

AMQP 0-9-1: exchange `p360.commands`, ruta `carrito.vaciar-por-pedido.v1`, cola `p360.carrito.vaciado.q`, content type exactamente `application/json`, messageId igual al UUID del comando. El publisher coloca `user_id=p360-pedidos-carrito-publisher`. La primera entrega exige ese `receivedUserId`, no un header arbitrario. El tenant se restringe también al tenant configurado en el consumer.

## Outbox de Pedidos

V3 crea `pedidos.carrito_vaciado_outbox`; V1/V2 existentes no se alteran. Pedido y comando se guardan en la misma transacción de `PedidoService.crearConCarrito`. FK y unicidad establecen una intención por pedido. Solo `AUTHENTICATED_NEW` del tenant acreditado puede originarla. No hay backfill de intenciones para históricos.

| Estado | Transición |
|---|---|
| PENDING | Claim transaccional → IN_FLIGHT |
| IN_FLIGHT | Confirm positivo sin return y lease vigente → PUBLISHED |
| IN_FLIGHT | Nack, return o incertidumbre → PENDING con demora |
| IN_FLIGHT vencido | Nuevo claim con token nuevo, mismo UUID y payload |
| PUBLISHED / BLOCKED | Terminal e inmutable |

`BLOCKED` está reservado en el esquema; este dispatcher no lo utiliza para clasificar fallos automáticamente. Un payload externo inválido conserva trabajo pendiente y no se publica; requiere diagnóstico administrativo.

Claim usa `FOR UPDATE SKIP LOCKED`, token aleatorio y lease. La publicación ocurre fuera de la transacción del claim. Settlement exige token, lease no vencido, payload exacto, tenant y asociación con un pedido elegible. Un propietario obsoleto no puede marcar publicado. La incertidumbre puede producir otra publicación del mismo comando: entrega al menos una vez y efecto protegido por dedupe en Carrito.

El trigger impide cambiar UUID, pedido, propietario, carrito, versión o payload; comprueba la correspondencia entre columnas y cuerpo. También impide borrar intenciones o reabrir estados terminales. La seguridad del vínculo OID–ID local nace del snapshot autenticado de la aplicación, no de inferir OID a partir de `Pedido.usuarioId` ni de una FK entre bases. Un atacante con capacidad de ejecutar el publisher o fabricar íntegramente escrituras confiables de la aplicación queda fuera de esa garantía.

## Recepción, dedupe y retry en Carrito

V2 crea `carrito.vaciado_por_pedido`; la raíz y líneas existentes no cambian. La primera recepción autenticada se persiste y confirma en una transacción separada **antes** de ejecutar o autorizar retry. UUID, referencias, cuerpo canónico y fecha de recepción son inmutables.

| Caso | Resultado definitivo |
|---|---|
| Tenant/OID/carrito discordante o versión inferior | REJECTED + DLQ confirmada |
| Versión igual | Vaciar + EMPTIED en una transacción |
| Versión superior | OMITTED_VERSION_CHANGED, sin borrar líneas |
| Duplicado terminal idéntico | Sin nuevo efecto; ACK seguro |
| UUID reutilizado con contenido diferente | DLQ; recepción original intacta |
| Retry sin recepción previa o no autorizado | DLQ; no crea recepción |

La fila de recepción se bloquea con `FOR UPDATE`. El aggregate conserva su `@Version` y `revisionContenido`: cada modificación real ensucia la raíz. Si la escritura concurrente gana, la versión obsoleta hace rollback de vaciado y settlement; el retry observa la versión superior y omite. Si el vaciado gana, la escritura obsoleta hace rollback y debe reintentarse desde una lectura nueva.

Un error transitorio u optimista autoriza de manera persistente **un** retry antes del handoff. La copia usa `user_id=p360-carrito-consumer`, `retry-count=1`, exchange `p360.retry` y ruta `carrito.vaciar-por-pedido.retry.1s`. La policy existente la entrega nuevamente desde `p360.commands` con la ruta funcional. Además del origen consumer, se exige recepción exacta previamente autorizada: publicar un retry no establece confianza.

No hay segunda ejecución funcional desde una redelivery original que ya autorizó retry; solo recupera el handoff. Un fallo transitorio en retry termina REJECTED y DLQ. Copias múltiples del mismo handoff son posibles ante pérdida de ACK/confirm; la recepción serializa el efecto y no permite un segundo nivel de retry.

ACK manual solo después del commit terminal o de confirm positivo y ausencia de return del handoff. Ante timeout, nack, return, excepción o ACK incierto, se conserva el original y se recupera el listener con backoff; no se publica una transferencia alternativa. Ningún DELETE HTTP se reutiliza como listener.

## Conexiones y dependencias operativas

- Pedidos usa conexión/template exclusivos para Carrito. La conexión principal mantiene las propiedades `spring.rabbitmq.*` del flujo Pago→Pedido; la prueba verifica instancias y usuarios diferentes.
- Ambos clientes nuevos usan los configuradores de Boot para conservar TLS, truststore y parámetros de conexión. En entornos remotos se debe acreditar TLS y origen de credenciales antes de activar.
- `PEDIDOS_CARRITO_MODE` y `CARRITO_PEDIDOS_MODE` defaults `HTTP`. Sus respectivos `*_PLATFORM_READY` defaults `false`; RabbitMQ exige credenciales dedicadas explícitas. No hay fallback automático.
- No se declaran recursos desde la aplicación. Se conserva la topología 21 colas / 7 exchanges / 20 bindings / 21 policies del repositorio.
- Publisher: configure `^$`, write `^p360\.commands$`, read `^$`. Consumer: configure `^$`, read únicamente `p360.carrito.vaciado.q`, write únicamente `p360.retry` y `p360.dlx` para handoffs. Sin `impersonator` ni unión con credenciales Pago→Pedido.
- La configuración operativa prevista actualmente no concede DLX a Carrito. Su aprobación y aplicación futuras son prerrequisito; **no se cambiaron permisos operativos**. Los tests añaden ese permiso solamente al broker desechable.
- Migrador y runtime PostgreSQL deben ser roles separados; runtime no debe ser owner ni poder deshabilitar triggers. Los grants mínimos de las tablas nuevas deben revisarse para el entorno real; no se aplicaron a bases del proyecto.
- Recepciones e intenciones se retienen sin TTL ni limpieza automática. Borrarlas anularía la deduplicación frente a una entrega antigua; cualquier política futura requiere una cota de redelivery acreditada.

### Garantía de user_id y aviso de seguridad

[Validated User-ID de RabbitMQ](https://www.rabbitmq.com/docs/validated-user-id) vincula esta propiedad al usuario autenticado, salvo privilegio `impersonator`. El test real AMQP 0-9-1 con usuario distinto produce channel close 406 y confirm negativo; omitir el origen llega al listener pero termina en DLQ sin crear recepción.

[GHSA-6588-rqcr-59pw](https://github.com/rabbitmq/rabbitmq-server/security/advisories/GHSA-6588-rqcr-59pw) describe una suplantación desde AMQP 1.0 que puede alcanzar consumers 0-9-1 por conversión. La metadata lista 4.3.0–4.3.4 y fix 4.3.5; el texto del aviso indica que no estableció todo el rango afectado. Los tests usan 4.1.8, como el pin actual del proyecto: estar fuera del rango listado **no demuestra inmunidad**. No se reprodujo el ataque AMQP 1.0. Antes de activar debe acreditarse que la versión/protocolos expuestos no permiten esa conversión vulnerable, mediante revisión/parche o prueba adversarial específica autorizada. No basta con que este cliente use 0-9-1.

## Evidencia y límites

Los resultados completos y hashes de fuentes/logs/Surefire se registran en `ISSUE82-tests.json`. Se ejecutan las siete suites habituales y adicionalmente la suite Carrito: ocho módulos en total, tras instalar el core actualizado.

- PostgreSQL 17 real: snapshot RR, ambas carreras, rollback atómico, dedupe concurrente, fencing, triggers, rol runtime limitado y migración desde schemas anteriores sin backfill.
- RabbitMQ 4.1.8 real: publisher, listener, validación user_id, duplicados, ruta de retry con TTL real y DLQ, mandatory return retirando un binding y recuperación al restaurarlo. La prueba del publisher detiene y reinicia `rabbitmqctl stop_app/start_app` en su contenedor desechable.
- Reinicio de JVM real: un proceso separado inicia el contexto de Carrito, invoca manualmente el processor y confirma commit; el test lo termina forzosamente. Otro proceso inicia contexto nuevo y reutiliza el resultado persistido; después se envía una copia al listener RabbitMQ real. No se presenta esa invocación manual como listener E2E ni como crash dentro del commit.
- Nack, pérdida de confirm, error SQL transitorio y pérdida de ACK son **inyectados**. El caso confirm perdido sí publica primero en broker real, pero su timeout se induce con un future no completado; no demuestra una partición real de red.
- El snapshot de Pedidos usa JWT ya autenticado como fixture y upstream inyectado. Las suites existentes mantienen sus pruebas de JWT; no se hizo autenticación Entra live ni checkout de frontend en este flujo nuevo.
- No se probó un compromiso total de la base ni pérdida simultánea de discos. No hay afirmación de exactly-once en RabbitMQ: el efecto transaccional de un comando se deduplica mientras permanezca su recepción.

## Futuro corte #70 y rollback

Este PR permanece inactivo por defecto. Antes del corte, #70 debe retirar el DELETE HTTP para la misma creación de pedido, validar snapshot/JWT delegado en el recorrido desplegado, acreditar permisos/TLS/broker y grants, y coordinar versiones. **No se habilitará RabbitMQ mientras el frontend siga realizando el vaciado HTTP de ese checkout**. No se cambió esa responsabilidad.

Rollback de código conserva tablas e intenciones; no elimina recepciones ni republica históricos. Retornar a HTTP exige primero detener la creación de nuevas intenciones y coordinar dispatcher/consumer y vaciados pendientes para evitar vaciado paralelo. Cambiar solo un flag sin resolver trabajo pendiente no constituye un rollback seguro. Los requisitos de despliegue tenant-aware del PR #94 siguen pendientes de acreditación operativa.

### Ejecución final

Fuentes de código y tests: commit `0e416cb916626c987406ed47b2c8824403f7685c` (la documentación se registra después, sin cambiar esas fuentes funcionales).

| Suite | Casos | Exitosos | Omitidos |
|---|---:|---:|---:|
| Core (test + install) | 265 | 265 | 0 |
| BFF | 203 | 203 | 0 |
| Usuarios | 79 | 79 | 0 |
| Restaurantes | 34 | 34 | 0 |
| Productos | 44 | 44 | 0 |
| Pedidos | 152 | 151 | 1 |
| Pagos | 187 | 186 | 1 |
| Carrito | 95 | 95 | 0 |
| **Total** | **1059** | **1057** | **2** |

Cero failures/errors; **60 regresiones específicas #82** incluidas en el total. Las dos omisiones son Entra live, sin credenciales reales.

Una reejecución en el workspace coincidió con el inicio del compilador JDT de VS Code y falló por desaparición temporal de clases/recursos de `target`; no se cuenta como exitosa. Pedidos y Carrito se reejecutaron completos desde copias temporales aisladas de las mismas fuentes y terminaron sin fallos. Al finalizar se restauró únicamente la codificación de dos comentarios originales del YAML de Pedidos; no cambiaron valores de configuración, lógica ni tests. El manifiesto identifica esta corrección no funcional y conserva hashes de logs y XML de las ejecuciones finales.

Los artifacts locales originales están archivados en `C:/Users/Rodri/AppData/Local/Temp/p360-issue82-evidence`; los hashes y nombres de cada caso quedan versionados en `ISSUE82-tests.json`. No se atribuyen estos resultados a GitHub CI ni a entornos desplegados.
