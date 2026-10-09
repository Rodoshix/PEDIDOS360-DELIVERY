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

Ejecución final completa del 8 de octubre de 2026 (hora local):

| Módulo | Casos | Exitosos | Omitidos | Fallos / errores |
|---|---:|---:|---:|---:|
| Core | 226 | 226 | 0 | 0 / 0 |
| BFF | 140 | 140 | 0 | 0 / 0 |
| Usuarios | 79 | 79 | 0 | 0 / 0 |
| Restaurantes | 34 | 34 | 0 | 0 / 0 |
| Productos | 44 | 44 | 0 | 0 / 0 |
| Pedidos | 131 | 130 | 1 | 0 / 0 |
| Pagos | 175 | 174 | 1 | 0 / 0 |
| Total | 829 | 827 | 2 | 0 / 0 |

Son 77 regresiones nuevas incluidas en el total: 29 core y 48 Pagos. Una ejecución
focalizada adicional del caso de permisos verificó la denegación de basicPublish
al exchange de comandos y conexiones registradas simultáneamente; no se suma al total.
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

Código probado: `40b01c72594994065def33b4180c0b426b974baa`. El commit posterior
contiene únicamente este registro documental y `evidencias/PR3-tests.json`.
SHA-256 del JSON: `c96f28220c0de71ef6456fe82ec17f8ad1d6e651ef3724b0c20967b34ff51ca8`.
Sus 332 fuentes se comparan con contenido normalizado a LF; logs, reportes y JAR
se identifican con hashes de sus bytes originales.
