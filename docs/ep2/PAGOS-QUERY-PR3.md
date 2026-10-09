# PR 3 — Consumer de consultas de Pagos

Refs #81. Base obligatoria: develop `08bae7a2a43f29ad2f74d76a8802eeaa93fb3098`.
HTTP permanece oficial; relay DISABLED y prueba false por defecto. Esta entrega no
implementa productor/orquestación BFF #70, no activa flags y no modifica infraestructura.

## Contrato y autoridades

Envelope ConsultaPedidos360 V1 sin cambios: messageId, type, version, occurredAt,
expiresAt, actor, operacion, payload. Operación `pago.consultar.v1`, exchange
`p360.queries`, cola `p360.pagos.consultas.q`. Payload exactamente:

```json
{"pagoId":123,"pruebaIdentidad":"<JWS de Usuarios>"}
```

pagoId es entero JSON positivo representable como Long. No se admiten identidad,
tenant, roles, null, coerciones, campos adicionales ni JSON duplicado. message_id
coincide con messageId, correlation_id es UUID canónico y reply_to exclusivamente
p360.bff.consultas.respuestas.q. Un retry conserva identificadores, propiedades,
actor, prueba y plazo absolutos.

La ruta opt-in exige content_type exactamente `application/json` y receivedRoutingKey
igual a `pago.consultar.v1`. La policy de retry devuelve esa routing key funcional;
no se permite una excepción basada en x-death u otros headers. Las rutas anteriores
de Usuarios, Restaurantes y Productos conservan su validación de transporte existente.
Errores de construcción de RequestEnvelope se normalizan a EnvelopeException
exclusivamente en el parser: type/version inválidos van directamente a DLQ. Una
IllegalArgumentException ajena a la construcción no se reclasifica como protocolo.

QueryConsumer verifica ActorContext ES256 con públicas de BFF y tenant/audiencia
esperados. Pagos exige role SERVICE y rechaza material privado de actor. La prueba
solo admite públicas locales de Usuarios, distintas de las de ActorContext, con
los mismos controles de claves, canonicalización, esquema y firma del PR #93.
ACTIVE requiere identity-proof.enabled=true; no existe modo consumer sin prueba.
Ausencia, configuración inválida, privada de prueba o claves reutilizadas falla
cerrado. No hay fallback JWT/HMAC, identidad local ni RPC Pagos → Usuarios/Pedidos.

IdentityProofVerifier.verifyForPagos acepta un actor **ya verificado por el consumer**.
Contrasta tenant/OID, audiencia/operación, perfil activo, ID local positivo y plazo
de Pagos frente a la prueba. usuariosRequestId y deadlineOriginal son procedencia
firmada por Usuarios, no referencias independientes conocidas por Pagos. No se
compara usuariosRequestId con el messageId del paso Pagos. verify(...) del BFF
conserva sus comprobaciones independientes contra el plan original de Usuarios y D.
No se decodifican claims no verificados para fingir esa comprobación independiente.

## Autorización y resultado

IdentidadUsuario usa tenant acreditado por ambas firmas, ID local firmado por Usuarios
y roles CLIENTE/ADMIN del actor. Exige access_as_user. Una denegación de scope/rol
con identidad válida responde 403, después de verificar la prueba y antes de SQL.
PagoService.obtener no se modifica: búsqueda tenant/id antes de pertenencia.

| Recurso | Resultado |
|---|---|
| CLIENTE propio, mismo tenant | 200 |
| CLIENTE ajeno, mismo tenant | 403 |
| ADMIN propio/ajeno, mismo tenant | 200 |
| Otro tenant, inexistente o UNKNOWN | 404 uniforme |
| RECONCILED_LEGACY autorizado | Lectura 200; no habilita mutaciones |

Pago.usuarioId sigue siendo el registrador. No se deduce pertenencia desde el
propietario del Pedido. La operación solo llama obtener: no registra, aprueba,
confirma, reconcilia ni crea outbox. PagoNoEncontradoException y 403 de PagoException
se traducen expresamente a errores de negocio definitivos del core.

QueryResponse conserva sus ocho campos exteriores y el éxito contiene exactamente
pagoId, pedidoId, usuarioId, monto, moneda, metodo, estado y fecha. PagoQueryResponse
es una whitelist RabbitMQ independiente; DTO HTTP intacto. No incluye JWS, tenant,
procedencia, idempotencia ni outbox. PagoQueryRequest redacta su toString.

## Tiempo y reliability

```text
D = deadline original firmado por Usuarios, sin renovación
E = min(perfilVerificadoEn + 4s, D)
R = expiresAt del request de Pagos
R <= E − margen <= E <= D
actor.expiraEn <= R
L = min(R, actor.expiraEn, E − margen)
```

Margen 250ms..1s, sin gracia posterior. El futuro BFF debe generar R <= E−margen
utilizando el mismo QueryOperationBudget inicial (máximo 5s y recortado por JWT).
Este PR no implementa ese productor ni obtiene un JWT desde RabbitMQ.

### Barrera de aceptación del BFF y alcance académico acordado

El adaptador admite un R explícito con el QueryOperationBudget original. Verifica
el ActorContext local mediante el mismo firmante y sus públicas confiables; para
Pagos verifica la prueba JWS del request, tenant/OID, D original y R <= E−margen.
Publicación, espera, resolución y retorno se limitan a min(D,R,actor.expiraEn,E−margen
cuando aplica). Usuarios conserva su verificación independiente de prueba contra
messageId de Usuarios y D, y añade el límite E−margen verificado antes del retorno.
Ningún timestamp remoto, payload de respuesta o header amplía el presupuesto.
Las respuestas 403/404 también comprueban el límite antes de lanzar el error de
negocio; un vencimiento invalida el presupuesto y retira la correlación pendiente.
El registro resuelve cada correlación una sola vez y descarta huérfanas/duplicadas;
el adaptador sigue contrastando messageId del cuerpo, correlación y operación.

RequestFactory y BffActorContextFactory solo añaden acceso al verificador existente
del JWS emitido. BffIdentityProofValidator añade la validación del request explícito
de Pagos; no debilita ni sustituye la de Usuarios. Estos cambios permiten verificar
límites autenticados sin decodificar claims no verificados ni modificar RequestPlan,
DTO públicos, endpoints o implementar la cadena Usuarios→Pagos del issue #70.

Se acepta explícitamente una garantía temporal **acotada**, distinta de la garantía
estricta anteriormente evaluada. No existe ledger, terminalidad durable global ni
fencing físico que impida publish() de un propietario obsoleto. El guard monotónico
solo conserva agotamiento irreversible dentro de su entrega; entre redeliveries,
reinicios e instancias se depende de relojes operativamente sincronizados. Un
retroceso arbitrario no detectado en otro proceso puede producir nueva ejecución.
No se afirma impedir físicamente toda ejecución/publicación tardía. La barrera
independiente del BFF rechaza la aceptación funcional fuera de sus límites verificados,
con su presupuesto monotónico original, bajo esas condiciones operativas.

QueryDeadlineGuard es opt-in para Pagos. Captura reloj/ticks al recibir, antes de
parsear, y mide el menor restante entre reloj absoluto y elapsed monotónico. Solo
permite recortar L; agotamiento terminal sincronizado no se reactiva al retroceder
el reloj o por llamadas concurrentes. El guard viaja explícitamente por procesador,
respuesta y política de fallos; no usa ThreadLocal. Los constructores/API anteriores
mantienen las rutas antiguas sin guard y las mismas llamadas de settlement.
El envelope de Pagos no admite una ventana superior a cinco segundos ni emisión
fuera del límite futuro admitido; rechaza plazos extremos antes de convertirlos a nanos.

Controles antes/después de SQL, tras proyectar, antes de send y para la espera de
confirm. El SELECT usa una transacción readOnly y statement_timeout local calculado
en milisegundos redondeados hacia abajo; se comprueba de nuevo tras adquirir la
conexión y ajustar el límite. No se cambian timeouts globales. La espera del pool,
pausas JVM o una llamada de transporte ya iniciada no tienen cancelación instantánea.
Resultados tardíos se descartan; no autorizan trabajo funcional posterior.

Un guard por entrega no distribuye ticks entre procesos. Retry/redelivery conservan
el R absoluto original y vuelven a verificar actor/prueba; no asignan cinco segundos
nuevos. La garantía distribuida requiere relojes dentro del desfase acordado y el
presupuesto monotónico original del BFF. No se promete inmunidad a un reloj externo
arbitrariamente incorrecto ni cancelación global durable.

ACK manual, prefetch/concurrencia 1, confirms correlacionados y mandatory returns.
403/404 producen respuesta definitiva; identidad/protocolo inválidos van a DLQ sin
retry funcional. Transitorios reciben un único retry corto si L sigue vigente.
Vencimiento impide respuesta/retry nuevos. DLQ diagnóstica conserva únicamente su
confirm-timeout independiente; no renueva negocio. Nack/return son rechazo conocido;
transporte/interrupción/timeout después de send son INCIERTO, incluso si se agota L.
INCIERTO impide una alternativa inmediata y conserva el original con recovery/backoff.
Un confirm positivo sin return observado después de R conserva ACK seguro: ya existe
handoff de respuesta confirmado, no se publica una alternativa ni un retry. El BFF
debe descartarla funcionalmente si su límite efectivo ya venció. La regresión de
confirm tardío inyecta reloj/future; no simula una partición real del broker.
ACK fallido no vuelve al tratamiento de negocio. No se promete exactly-once: una
redelivery puede repetir una lectura y la respuesta puede duplicarse.

## Dos conexiones y permisos

Con DISABLED continúa la autoconfiguración previa. Con ACTIVE se registran explícitamente:

| Bean | Uso |
|---|---|
| rabbitConnectionFactory / rabbitTemplate, primary | Publisher existente Pago → Pedido |
| queryConnectionFactory / queryRabbitTemplate | Consumer, respuestas y handoffs de consultas |
| queryListenerFactory | Exclusivamente queryConnectionFactory |

El publisher conserva spring.rabbitmq.* y se inyecta por qualifier explícito.
La conexión de consultas carga pagos.consultas.rabbitmq.* mediante Binder, sin
registrar un segundo RabbitProperties que interfiera con la autoconfiguración.
Credenciales de consultas deben ser explícitas, no guest y tener otro username.
No se declara topología y el listener desactiva autoDeclare.

Configuración de consultas: PAGOS_QUERY_RABBITMQ_HOST/PORT/VHOST/USERNAME/PASSWORD.
Las propiedades nativas permiten configurar TLS cuando lo requiera el futuro entorno.
No se generan/distribuyen claves ni credenciales operativas.

Perfil probado **solo en RabbitMQ desechable**:

```text
configure: ^$
read: ^p360\.pagos\.consultas\.q$
write: ^(amq\.default|p360\.retry|p360\.dlx)$
```

El inventario operativo del repositorio todavía no concede p360.dlx a
p360-pagos-consumer. Su autorización/verificación es requisito previo a activación;
este PR no modifica platform_control.py ni permisos operativos. amq.default autoriza
el exchange, no una whitelist de routing keys: la aplicación limita replyTo. No unir
privilegios con p360-pagos-publisher. Topología aprobada permanece 21/7/20/21.

## Evidencia, riesgos y entrega

evidencias/PR3-tests.json vincula las siete suites completas, casos, omisiones,
logs/reportes y fuentes mediante SHA-256. Los tests focalizados están incluidos
en los totales completos y no se suman otra vez.

PagosQueryRabbitTests levanta listener y PostgreSQL reales, broker quorum y cuentas
configure-denied. Comprueba matrices, retry/DLQ, SQL con pg_sleep y statement_timeout,
metadatos/JSON duplicado, dos conexiones simultáneas, outbox V1 y permisos negativos.
Las fallas TransientDataAccessResourceException y pg_sleep se introducen con spy;
no se presentan como caída/partición real de red. GuardedQueryReliabilityTests y
PagosQueryProcessorTests inyectan tiempo/transporte/ACK/proyección; no usan broker.
Las suites existentes conservan sus integraciones reales y sus omisiones Entra live.

BffConsultasAdapterTests ejercita publisher del BFF, listener de respuestas y
adaptador con RabbitMQ real. Los servicios remotos son fixtures deliberadamente
rápidas/tardías; no es una cadena HTTP productiva Usuarios→Pagos→BFF. Incluye
200/403/404 enviados después de actor.expiraEn pero antes de D, duplicado real,
referencias discordantes y requests explícitos de Pagos con prueba ES256 válida:
aceptación previa, vencimiento del actor antes de R y vencimiento de R antes de D.
BffTemporalBarrierTests inyecta reloj/publicador/resolución y verifica límites,
expiración durante resolución/proyección, errores, proof inválida y presupuesto
irreversible. BffIdentityCorrectionTests completa el mock del nuevo verificador
manteniendo sus aserciones previas; no se modifican resultados esperados.

Antes del cambio, el probe externo sobre a6c50c4 usó el adaptador/listener reales y
RabbitMQ desechable con servicio remoto fixture: actor venció a
2026-10-09T03:44:13.116Z, hubo aceptación 200 a 03:44:13.323470200Z y R era
03:44:14.103Z. SHA-256 de ese log externo:
`8cdd25a59b7893cc417524a3980268f3f3d8804bfb101ec09d3fe37145068d83`.
No verificó el consumer real de Pagos ni PostgreSQL/IdentityProof. Después, las
regresiones permanentes de broker reproducen la ventana de aproximadamente 207 ms
tras el actor y exigen QueryTimeoutException, presupuesto invalidado y correlación
retirada; las respuestas tardías se descartan. En Pagos se comprueba además con JWS
válido y actor vencido antes de R. Esta evidencia demuestra la barrera del BFF,
sin atribuir ejecución a la futura orquestación #70 ni garantía durable global.

Ejecución final completa del 9 de octubre de 2026 (hora local):

| Módulo | Casos | Exitosos | Omitidos | Fallos / errores |
|---|---:|---:|---:|---:|
| Core | 232 | 232 | 0 | 0 / 0 |
| BFF | 170 | 170 | 0 | 0 / 0 |
| Usuarios | 79 | 79 | 0 | 0 / 0 |
| Restaurantes | 34 | 34 | 0 | 0 / 0 |
| Productos | 44 | 44 | 0 | 0 / 0 |
| Pedidos | 131 | 130 | 1 | 0 / 0 |
| Pagos | 179 | 178 | 1 | 0 / 0 |
| Total | 869 | 867 | 2 | 0 / 0 |

Son 117 regresiones del PR incluidas en el total: 35 core, 52 Pagos y 30 BFF.
Este cierre añade 40 casos a los 829 anteriores: seis core, cuatro Pagos y 30 BFF.
Las pruebas focalizadas no se suman a los totales completos. El caso de conexiones
y permisos vuelve a ejecutarse dentro de la suite completa de Pagos; no se atribuye
vigencia nueva al antiguo log focalizado de permisos. Las dos omisiones son
WorkerEntraLiveTests y EntraWorkerLiveTests por ausencia de RUN_ENTRA_WORKER_LIVE.
El core se instaló antes de las seis suites dependientes/vecinas. El JSON verifica
su hash instalado y classpath donde es una dependencia. Pedidos usa su consumer V1
independiente y no incorpora ese artefacto. No se ejecutaron las pruebas Entra live.

Los permisos, TLS/relojes, roles PostgreSQL separados, despliegue de migraciones PR #94,
control de endpoints internos y corte coordinado de binarios antiguos requieren
validación operativa posterior. JWS no cifra; actividad es una prueba puntual, revocación
requiere actualizar públicas y el compromiso del BFF/Usuarios sigue siendo residual.
Rollback de este consumer es DISABLED manteniendo HTTP tenant-aware; no restaurar
binarios anteriores a PR #94 ni aceptar identidad sin prueba en RabbitMQ.

Entrega en feature/81-pagos-query-consumer, PR DRAFT hacia develop con Refs #81.
READY FOR AUDIT no autoriza merge, activación, despliegue ni cierre del issue.

Código probado: `24469843a97f906b625b9b4893e0be291bd22878`. El commit posterior
contiene únicamente este registro documental y `evidencias/PR3-tests.json`.
SHA-256 del JSON: `9457e90a313a36e775f130431cf6351ddeca1d2b4e56efb21f6251d548e02b94`.
Sus 333 fuentes se comparan con contenido normalizado a LF; logs, reportes y JAR
se identifican con hashes de sus bytes originales.
