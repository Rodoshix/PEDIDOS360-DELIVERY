# Integración local y preactivación de #70

Base comprobada: `950972300686d96f2fc0ae1f1289c74b83a257c9` (develop).
PR #97 y #98 están MERGED. Esta entrega añade pruebas y evidencia; no cambia
código productivo, contratos, migraciones, permisos operativos ni valores predeterminados.
HTTP sigue siendo el modo predeterminado; relay y prueba de identidad siguen deshabilitados.

## Alcance de la evidencia

Las ejecuciones usan un archivo `git archive` de la base, más la nueva prueba,
en un directorio temporal sin `.env.local`. El core se instaló antes de ejecutar
los servicios. Testcontainers administra PostgreSQL y RabbitMQ desechables; no
se usaron las bases persistentes ni el broker preexistente del equipo.

`PurchaseRabbitIntegrationTests` reutiliza `BffQueriesEndToEndTests` y agrega las
aplicaciones reales Pedidos y Carrito. Los seis servicios y el BFF ejecutan sus
controllers, clientes HTTP, repositorios y listeners originales. Existen dos
contextos BFF para contrastar HTTP/RabbitMQ; solamente uno consume respuestas RabbitMQ.
JWT de Entra, claves de ActorContext/IdentityProof y aprovisionamiento son fixtures.
Las claves de prueba existentes no acreditan distribución de claves operativas.

Los dos recorridos de compra realizan POST de carrito, POST de pedido, vaciado
por listener RabbitMQ, POST de pago (TARJETA/APROBADO y EFECTIVO/PENDIENTE), reinicio
del contexto Pagos conservando DB/broker y recuperación del consumer Pedidos detenido.
Comprueban intención persistida, estado PUBLISHED, Pedido CREADO mientras el listener
está detenido y CONFIRMADO tras arrancarlo. La reconciliación HTTP no procesa esos pagos.
El dispatcher de Carrito se invoca explícitamente; el scheduler de Pagos puede
ejecutarse al arrancar. No se interpreta publisher confirm como confirmación de negocio.

El fixture ejecuta además los ocho casos heredados de consultas: equivalencia de
las cuatro rutas, catálogo vacío/no vacío, Usuarios→Pagos y autorización CLIENTE/ADMIN,
rechazo de tenant/OID externos y de histórico UNKNOWN. Esos ocho casos se repiten
en dos clases de Pagos; el total de ejecuciones no significa ocho escenarios nuevos.

Los primeros intentos del nuevo fixture fallaron por una aserción inmediata tras
el reinicio: el scheduler tenía la fila bloqueada y el dispatch manual la omitía
correctamente por SKIP LOCKED. La traza mostró el claim y settlement PUBLISHED del
scheduler. La prueba final espera ese settlement sin cambiar tiempos, estado ni
contenido del outbox. No se corrigió código productivo ni se forzó elegibilidad SQL.
Los intentos fallidos se registran aparte y no cuentan como pruebas exitosas.

La suite frontend ejecuta JSX/handlers reales con Vite y fixtures de hooks, router,
sesión y transporte. Acredita POST→DELETE/reintento exclusivo en HTTP y ausencia
de DELETE/reintento en RabbitMQ, sin segundo POST ni anuncio falso de vaciado.
**No se ejecutó una compra conjunta en navegador real ni Entra live.** El recorrido
backend real y las regresiones frontend son evidencias diferentes.

## Pruebas ejecutadas

Resultados y hashes de logs, XML y fuentes en `LOCAL-PREACTIVATION-70-tests.json`.

| Ejecución | Alcance | Naturaleza |
|---|---|---|
| Core completo, install | 265 aprobadas: parser, correlación, deadline, ACK/retry/DLQ | Unitarias y fallos inyectados según caso |
| Pagos completo | 197 casos: 196 aprobados, 1 Entra live omitido; consumer/IdentityProof, outbox, HTTP, consultas E2E y compra nueva | Mezcla de unitarias, PG/Rabbit reales y fixtures declarados |
| CarritoCommandIntegrationTests | 26 casos: recepción, retry, versión, carreras, crash JVM y dedupe | PG/Rabbit reales; pérdida de confirm/ACK inyectada donde se indica |
| Pedidos: cuatro clases focalizadas | 38 casos: atomicidad, runtime SQL, publicación y consumer | PG/Rabbit reales; nack/confirm perdido inyectados |
| BFF: tres clases focalizadas | 67 casos: adapters y barrera temporal | Fixtures/inyección; no broker E2E |
| Frontend completo | 229 casos | Fixtures de transporte/auth; sin navegador |

Total de estas ejecuciones: **822 casos, 821 aprobados, 1 omitido, 0 fallos/errores**.
El omitido pertenece a la prueba Entra live de Pagos; no se omitió ninguna compra nueva.

No se reejecutaron las suites completas de Usuarios, Restaurantes, Productos,
Pedidos, Carrito o BFF. Sus aplicaciones reales sí participan en el fixture conjunto.
No se atribuyen a esta entrega las ejecuciones completas de PR anteriores.

Reproducción en una copia desechable del repositorio, con Java 21, Maven, Node y
Docker disponibles, sin importar configuración de bases persistentes:

```powershell
mvn -B -ntp -f backend/shared/p360-messaging-core/pom.xml install
mvn -B -ntp -f backend/services/pagos-service/pom.xml test
mvn -B -ntp -f backend/services/carrito-service/pom.xml test "-Dtest=CarritoCommandIntegrationTests"
mvn -B -ntp -f backend/services/pedidos-service/pom.xml test "-Dtest=CarritoPublisherIntegrationTests,PedidoCarritoOutboxTests,CarritoOutboxMigrationCompatibilityTests,RabbitConsumerTests"
mvn -B -ntp -f backend/bff/pom.xml test "-Dtest=BffConsultasAdapterTests,BffTemporalBarrierTests,BffQueryHttpAdapterTests"
# En frontend, con sus dependencias instaladas:
npm test
```

Las ejecuciones registradas utilizaron el directorio de cada módulo como cwd.
La prueba conjunta recorre padres para localizar la raíz del repositorio.

`RabbitCoreTests.schedulerSoloReconciliacionHttpHistorica` contrasta pagos HTTP
pendientes y nuevos RabbitMQ en PG real, con cliente Pedidos stub. No acredita
por sí solo un drenaje HTTP contra el stack conjunto ni un histórico operativo limpio.
UNKNOWN/RECONCILED_LEGACY no se reactivan ni se asignan a un tenant por defecto.
Los nuevos pagos conservan coordinación persistida: cambiar un flag no migra los antiguos.

## Matriz de preactivación

READY local significa evidencia para auditoría, no permiso de activación.
Todas las activaciones permanecen BLOCKED hasta acreditar sus requisitos operativos.

| Flujo | Configuración predeterminada y dependencias | Evidencia local/recuperación | Condición de activación | Rollback seguro | Corte |
|---|---|---|---|---|---|
| Usuarios | `bff.queries.usuarios=HTTP`; relay DISABLED, proof disabled | Listener real, respuesta objeto y prueba; barrera temporal/correlación por regresiones | Usuarios y BFF con claves separadas, permisos/TLS, reloj y una sola instancia BFF Rabbit activa | Selector HTTP; retirar correlaciones pendientes y descartar respuestas huérfanas, sin fallback | BLOCKED |
| Restaurantes | `bff.queries.restaurantes=HTTP` | Array vacío/no vacío real y equivalencia HTTP | Consumer, permisos efectivos, broker/TLS, BFF único | Selector HTTP y retirada de pendientes | BLOCKED |
| Productos | `bff.queries.productos=HTTP` | Array vacío/no vacío real y equivalencia HTTP | Consumer, permisos efectivos, broker/TLS, BFF único | Selector HTTP y retirada de pendientes | BLOCKED |
| Pagos | `bff.queries.pagos=HTTP`; conexión de consultas separada | Usuarios→Pagos real, prueba no expuesta, tenant/OID y límites; retry/DLQ por suite | Usuarios/proof incluso si la ruta pública Usuarios sigue HTTP; claves operativas; write DLX pendiente | Selector HTTP; conservar autorización tenant y DTO; no fallback HMAC | BLOCKED |
| Pago→Pedido | coordination HTTP, relay independiente | Tarjeta/efectivo, outbox/reinicio/listener detenido; duplicados y fallos por suite | Roles/migraciones PR #94, publisher y consumer separados, permisos/policies, plan histórico | Conservar modo por Pago; drenar outbox Rabbit; scheduler solo pagos HTTP nuevos autenticados; no republicar histórico | BLOCKED |
| Pedido→Carrito | Pedidos/Carrito/build frontend HTTP | Snapshot, creación/outbox, listener/vaciado reales; versión/dedupe/carreras/retry por pruebas existentes reejecutadas | Tres modos coordinados, write DLX pendiente, roles/migraciones, retirar clientes antiguos y resolver pendientes | Suspender checkout; drenar/resolver intenciones y mensajes antes de restaurar DELETE automático | BLOCKED |

## Requisitos de #71 aún no acreditados

- El inventario `PLATAFORMA-RABBITMQ.md` no concede write `p360.dlx` a
  `p360-pagos-consumer` ni `p360-carrito-consumer`. Es requisito de sus handoffs
  explícitos. Los permisos ampliados del fixture desechable no corrigen ese inventario
  ni demuestran permisos desplegados. No unir credenciales de consultas con Pago→Pedido.
- Pedidos utiliza retry avanzado y rechazo para dead-lettering por policy. El inventario
  concede write `p360.pedidos.retry`; contrastar permisos efectivos, recurso DLX y
  dead-lettering del tipo de cola/policy antes de activar. No copiar privilegios de
  otros consumers indiscriminadamente. La evidencia de plataforma previamente documentada
  no se presenta como comprobación operativa nueva.
- TLS, certificados, vhost, credenciales aisladas y ausencia de `impersonator` deben
  acreditarse en destino. El fixture conjunto no usa TLS y parte de sus conexiones
  utiliza admin del contenedor; Carrito sí emplea cuentas publisher/consumer separadas.
- Las consultas del fixture usan colas classic mínimas. Las pruebas de reliability
  usan sus propias policies/quorum fixtures. No se aprovisionó ni certificó conjuntamente
  la topología operativa completa 21/7/20/21; esa topología no se modifica.
- El fixture conjunto probó RabbitMQ 4.1.8 mediante AMQP 0-9-1. El rechazo de `user_id` falsificado
  en ese protocolo no demuestra seguridad de conversión desde AMQP 1.0. Verificar
  versión/protocolos efectivos respecto de [GHSA-6588-rqcr-59pw](https://github.com/rabbitmq/rabbitmq-server/security/advisories/GHSA-6588-rqcr-59pw).
  El aviso enumera 4.3.0–4.3.4 y fix 4.3.5, pero su descripción limita la reproducción
  a una revisión: no inferir inmunidad de todas las versiones anteriores por ese rango.
- Migrador y runtime PostgreSQL separados, propiedad y search_path de SECURITY DEFINER,
  red de endpoints internos y corte de binarios antiguos del PR #94 siguen pendientes
  de acreditación en destino. El fixture conjunto usa propietario del contenedor;
  los tests de roles SQL son evidencia local separada.
- Inventariar UNKNOWN, RECONCILED_LEGACY, pagos HTTP pendientes e intenciones Rabbit
  antes del corte. No reconciliar datos reales ni modificar mensajes/UUID de outbox.
- Relojes sincronizados, timeouts y una sola instancia BFF Rabbit activa: correlaciones
  pendientes son locales. El presupuesto no se renueva. No existe terminalidad durable
  global frente a retrocesos arbitrarios entre procesos; sigue siendo un riesgo aceptado.

## Retirada verificable de clientes antiguos

1. Suspender checkout en el entorno académico controlado y registrar responsable/ventana.
2. Inventariar clientes, pestañas, sesiones, versiones de build y artefactos cacheados.
3. Cerrar realmente pestañas/procesos anteriores y verificar cada cliente controlado.
   Publicar assets o invalidar caché no cierra JavaScript ya cargado.
4. Resolver pendientes; distribuir build Rabbit y coordinar modos de Pedidos/Carrito.
5. Capturar solicitudes de una compra controlada: un POST /pedidos, cero DELETE
   automáticos, estado de recepción y GET del carrito; observar clientes inventariados
   durante la ventana y registrar evidencia. No confundir DELETE manual con automático.
6. Reabrir checkout solo con inventario firmado y evidencia. Si no puede demostrarse
   retirada efectiva, mantener BLOCKED Pedido→Carrito; una barrera servidor requiere
   autorización adicional y no se implementa aquí.

Esta secuencia está definida, **no ejecutada contra clientes desplegados**.

## #72 y límites finales

Pendientes: compra frontend/backend conjunta en navegador, Entra live, permisos/TLS
de destino, inventario y drenaje histórico real, retirada de clientes y ensayo de corte/
rollback. Los fallos de confirm/nack inyectados no son particiones reales de red.
Las pruebas que detienen/reinician broker, canal o JVM se identifican por su caso,
sin generalizar el resultado a AWS. No se activaron flags persistentes ni se tocaron issues.

**READY FOR INDEPENDENT AUDIT**: integración local backend y regresiones declaradas
aprobadas, sin defecto productivo nuevo confirmado. La activación de los seis flujos
sigue BLOCKED por requisitos operativos; Pedido→Carrito incluye retirada de clientes.
No se autoriza el corte ni se presenta el resultado como evidencia AWS/Entra live.
