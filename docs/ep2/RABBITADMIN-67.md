# RabbitAdmin #67 — borrador sandbox BLOCKED

Base inspeccionada: develop `82ab9957e144c1893c914b4960f970578b2491f3`.
Antes de esta entrega no existía `backend/services/rabbit-admin-service/` ni historial
Git para esa ruta. La búsqueda GitHub por RabbitAdmin/#67 encontró PR #75
(planificación), #76 (núcleo) y #85 (plataforma), sin implementación de la API.
La ausencia en una búsqueda textual no prueba inexistencia de todo PR; el inventario
del árbol integrado sí demuestra que el servicio estaba pendiente.

PR #99 está MERGED con merge commit igual a la base anterior: la implementación
local de #70 está integrada. #70 permanece OPEN: corte operativo, retirada de
clientes y ensayo de rollback siguen pendientes y no se ejecutan desde #67.

## Matriz de aceptación

Rutas relativas a `backend/services/rabbit-admin-service/`.

| Requisito | Inventario inicial | Estado de implementación final y evidencia |
|---|---|---|
| Controller → DTO/Bean Validation → Service → RabbitAdmin | PENDIENTE | IMPLEMENTADO: RabbitAdminController.java, AdminDtos.java, RabbitAdminService.java; siete endpoints HTTP reales |
| Vhost único y credencial separada | PARCIAL: plataforma #69 provisiona sandbox, sin servicio | IMPLEMENTADO en código/fixture: BrokerConfiguration.java fija vhost y exige p360-admin-demo; configuración operativa pendiente |
| Prefijo p360.demo.; bloquear principal/amq/default | PENDIENTE en API; plataforma usa otro prefijo | IMPLEMENTADO: SandboxRules.java, validación repetida en servicio, tests de rutas/bindingId/permisos |
| JWT Entra + access_as_user + ADMIN | PARCIAL: patrón existente en Usuarios, sin API admin | IMPLEMENTADO: EntraConfiguration.java y SecurityConfiguration.java; firma/claims HTTP, CLIENTE/scope rechazados, sin bypass local |
| CRUD queues/exchanges/bindings + consulta básica | PENDIENTE | IMPLEMENTADO: DTO limitados, sin argumentos arbitrarios ni purge/usuarios/policies |
| Queue delete vacía y sin consumidores | PENDIENTE | PARCIAL/BLOCKED: deleteQueue(name,true,true) bloquea ready/consumidores, pero permite pérdida de unacknowledged obtenido con basic.get |
| 400/401/403/409/503 | PENDIENTE | IMPLEMENTADO: AdminErrors.java y security/service; errores sanitizados; incompatibilidad/ocupación real y broker detenido |
| Auditoría sin secretos | PENDIENTE | IMPLEMENTADO: AdminAuditFilter.java + call(); requestId generado, actorHash, acción/recurso válido/resultado; fixture verifica ausencia de token/password/body |
| Evidencia reproducible/API real sandbox | PENDIENTE: probes de plataforma no prueban esta API | IMPLEMENTADO local: RabbitAdminIntegrationTests y ConfigurationTests; manifest/log/XML con hashes |
| Revisión/integración/cierre #67 | PENDIENTE | PENDIENTE: PR Draft; no merge/cierre desde esta tarea |

## API y DTO nuevos de esta misión

**BLOQUEO P1:** `unacknowledgedMessageMustPreventDeletion` reproduce con RabbitMQ
real una cola con una entrega pendiente de ACK obtenida mediante basic.get, cero
mensajes ready y cero consumidores registrados. GET informa messages=0/consumers=0;
DELETE devuelve **204**, cuando la garantía de cola realmente vacía requiere rechazo.
La entrega se pierde al eliminar la cola. El draft no es apto para integración/activación.
Las 56 pruebas previas aprobadas no cubrían este caso; la regresión se conserva fallida.

`if-empty` no protege ese estado. El [proceso classic del broker](https://github.com/rabbitmq/rabbitmq-server/blob/v4.1.8/deps/rabbit/src/rabbit_amqqueue_process.erl)
consulta el backing queue ready y la existencia de consumidores; basic.get no registra
un consumer. No se cambia la prueba para aceptar 204 ni se redefine vacía como ready=0.

Cambio mínimo a definir/aprobar antes de completar DELETE: un mecanismo verificable
de quiescencia que impida productores/basic.get/consumers concurrentes, cierre o resuelva
entregas pendientes, confirme ready=0, unacknowledged=0 y consumers=0, y mantenga esa
exclusión durante la eliminación. Una lectura previa de management seguida de
deleteQueue(true,true) conserva la carrera; no basta para prometer la garantía.
La aplicación AMQP/RabbitAdmin y permisos actuales no proporcionan ese mecanismo.
No se implementan cambios de permisos, nuevas APIs ni una garantía debilitada desde
esta tarea. Se requiere decisión de seguridad/operación antes de continuar.

No se modifica ningún endpoint, DTO o contrato de los servicios existentes.
El servicio independiente usa puerto 8090 y loopback por defecto; no se registra en BFF.

| Método/ruta | Request exacto | Éxito |
|---|---|---|
| PUT /admin/rabbit/queues/{name} | `{"durable":true}` | 200: name, messages, consumers |
| GET /admin/rabbit/queues/{name} | Sin cuerpo | 200: name, messages, consumers |
| DELETE /admin/rabbit/queues/{name} | Sin cuerpo | BORRADOR BLOCKED: 204 confirma if-unused + if-empty, insuficiente frente a unacknowledged/basic.get |
| PUT /admin/rabbit/exchanges/{name} | `{"type":"direct","durable":true}`; type direct/topic/fanout | 200: name, type, durable |
| DELETE /admin/rabbit/exchanges/{name} | Sin cuerpo | 204, solamente exchange sin bindings (if-unused) |
| POST /admin/rabbit/bindings | `{"queue":"p360.demo.q","exchange":"p360.demo.e","routingKey":"demo"}` | 201: bindingId |
| DELETE /admin/rabbit/bindings/{bindingId} | ID devuelto por POST | 204 |

Recursos: `p360.demo.` seguido de 1–100 caracteres ASCII alfanuméricos/punto/guion/
underscore, comenzando por alfanumérico. Routing key: 0–128 caracteres ASCII
alfanuméricos/punto/guion/underscore/#/*. Exclusivamente bindings queue→exchange.
Queue siempre classic/durable/no-exclusive/no-auto-delete; x-queue-type lo fija el
servidor. No se acepta mapa de argumentos. Exchanges durables y no-auto-delete.
PUT no transforma tipos/atributos de recursos existentes: incompatibilidad produce 409.

bindingId es Base64url canónico sin padding de queue + LF + exchange + LF + routingKey.
No es una credencial ni prueba de autorización. DELETE vuelve a validar todos los
componentes, incluidos nombres y prefijo; un ID construido por el cliente no evita
JWT/ADMIN ni permite tocar recursos externos. No se requiere almacenamiento nuevo.

400: DTO/sintaxis/tipo incorrecto, extra/duplicado, cuerpo adicional o ID no canónico.
409: nombre fuera del sandbox/prefijo permitido, recurso ausente/ocupado/protegido,
redeclara incompatible o rechazo definitivo del protocolo. Binding DTO con nombre
inválido devuelve 400 por Bean Validation; bindingId con recurso externo devuelve 409.
401: JWT ausente/inválido. 403: JWT válido sin scope/ADMIN.
503: broker/conexión/RPC sin resultado confirmado. No se promete que 503 implique
ausencia de efecto: el operador debe consultar antes de repetir una operación incierta.
No hay retry automático de una operación REST ni fallback a borrado incondicional.

Para lectura y eliminación de exchange se usa el template interno de RabbitAdmin:
los wrappers getQueueInfo/deleteExchange pueden ocultar excepciones devolviendo
null/false. Las llamadas de protocolo preservan la causa para clasificar el resultado;
no hay conexión secundaria ni cliente management HTTP. El controller solo usa service.
Referencia: [Spring RabbitAdmin](https://github.com/spring-projects/spring-amqp/blob/v4.1.1/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/core/RabbitAdmin.java).

RabbitMQ 4.1.8 rechaza borrado condicional quorum. La API crea classic; si un operador
precrea una quorum en sandbox, PUT incompatible y DELETE condicional devuelven 409,
conservando la cola. No se fuerza la eliminación. Regresión real y [código del broker](https://github.com/rabbitmq/rabbitmq-server/blob/v4.1.8/deps/rabbit/src/rabbit_quorum_queue.erl).
La garantía condicional utilizada no debe extrapolarse a tipos/versiones no probados.

## Autenticación y auditoría

Entra v2, RS256 y JWKS Microsoft construido desde tenant configurado validado como
UUID, nunca desde el token. Reutiliza las reglas de Usuarios: issuer, tid, audiencia
única, azp del frontend, oid UUID, ver, exp/nbf y validación temporal estándar.
API y frontend requieren IDs distintos. La política temporal conserva el skew estándar
del validador de Entra; no es el deadline de consultas RabbitMQ.
ADMIN y scope access_as_user son ambos obligatorios. No hay entra.enabled, identidad
local, basic/form login ni acceso anónimo a operaciones. Faltan propiedades → falla
el arranque. JWT sin firma/HMAC, otra firma, expirado o claims incorrectos → 401.

Auditoría: UUID de request generado por servidor, hash SHA-256 de tenant:oid, operación,
nombre validado y resultado. No registra Bearer, cuerpo/query/URL arbitraria, password,
datos personales en claro ni excepciones de broker en la respuesta pública.
El actorHash es seudónimo estable y requiere política de retención/acceso a logs.
La auditoría usa logging de aplicación, no almacenamiento inmutable externo.

## Configuración y pendientes operativos

Las variables están en `.env.example`; no se importa ese archivo automáticamente.
Exportarlas al proceso o suministrarlas mediante gestión de secretos autorizada.
Requeridas: RABBIT_ADMIN_PASSWORD y los tres IDs Entra. Host/port/TLS son configuración
de servidor; username debe ser p360-admin-demo. Vhost fijo pedidos360-admin-demo no
tiene selector ni variable. Propiedades spring.rabbitmq/query/body del cliente no
seleccionan la conexión. RabbitAdmin auto-start/redeclaración y dynamic están desactivados:
arrancar no crea topología. No hay consumer, publisher de negocio, outbox ni core compartido.

Para TLS, RABBIT_ADMIN_TLS=true usa truststore Java por defecto y verificación de hostname;
los truststores se suministran por configuración JVM del servidor. Las pruebas locales
no acreditan TLS/Entra live ni permisos desplegados.

**Pendiente #71:** `infrastructure/rabbitmq/platform_control.py` y
`PLATAFORMA-RABBITMQ.md` conceden a p360-admin-demo `^demo\.`; #67 exige `p360.demo.`.
Con esos permisos la nueva API no puede operar sus recursos. Antes de cualquier demo
operativa, autorizar/revisar la configuración exacta del sandbox (configure/write/read
para p360.demo., ningún permiso en pedidos360 o /, sin tags administrator/impersonator).
Esta tarea no cambia esos archivos, grants ni credenciales persistentes. No se propone
un regex que una ambos prefijos o amplíe acceso a topología principal.

Además: TLS/red privada HTTPS, distribución de secretos, asignación real ADMIN/scope,
límites/cuotas del sandbox y conservación de auditoría en destino quedan pendientes.
Un ADMIN comprometido puede administrar los recursos demo permitidos y agotar su cuota;
no se vende este sandbox como aislamiento físico del broker/host.

## Ejecución y evidencia

```powershell
mvn -B -ntp -f backend/services/rabbit-admin-service/pom.xml verify
```

Java 21/Maven y Docker requeridos. Testcontainers crea su propio RabbitMQ 4.1.8,
vhost y cuenta sandbox restringida; no conecta al broker persistente del equipo.
HTTP server y validación criptográfica/claims son reales. Solo autoridad/firma Entra
y aprovisionamiento del broker son fixtures. No se ejecutó Entra live, AWS, TLS de
destino ni suites de negocio sin cambios. La ejecución previa de 56 casos empaquetó
un jar ejecutable; no se presenta como build final aprobado tras detectar el bloqueo.

Cobertura: CRUD/consulta, tipos y bindings, JSON estricto, aislamiento, ADMIN/CLIENTE/
scope, tokens incorrectos, colas con mensajes/consumidores y cambios entre GET/DELETE,
broker detenido mediante stop_app, 503 y recuperación start_app, configuración fail-closed,
auditoría y rechazos de protocolos incompatibles. No son mocks de ocupación/conexión.

Intentos intermedios se excluyen de la evidencia final: se ajustó la expectativa del
bindingId protegido a 409; nuevas regresiones detectaron coerción numérica a string
y rechazo de delete condicional quorum inicialmente clasificado como 503. Se corrigieron
en este servicio nuevo y se volvió a ejecutar la suite completa.

Resultados, fuentes LF, hashes de logs/XML/jar y limitaciones en `RABBITADMIN-67-tests.json`.
Suite final: **57 casos, 56 aprobados, 1 fallo, 0 errores/omisiones**. El único fallo
es la regresión unacknowledged/basic.get (espera 409, recibe 204), también reproducida
aisladamente. Maven verify termina con exit 1; no hay build final aprobado.
La compatibilidad se sustenta en diff limitado a servicio nuevo y documentación:
no modifica contratos V1, HTTP de negocio, topología 21/7/20/21, #70 ni frontend.

**BLOCKED:** garantía de eliminación segura no satisfecha frente a entregas sin ACK.
El PR Draft conserva implementación parcial, evidencia y regresión fallida para revisión.
No autoriza integración, cierre de #67, activación ni cambios operativos. #70 sigue abierto.
