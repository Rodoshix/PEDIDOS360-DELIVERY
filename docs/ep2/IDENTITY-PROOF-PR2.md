# PR 2 — Prueba de identidad de Usuarios para consultas de Pagos

Base de implementación: develop `94777e48bfa4e954269c22669c45feec84489069`.
Refs #81, #78 y #70. Esta entrega prepara el contrato; no implementa consumer Pagos,
la llamada funcional BFF → Pagos, el corte HTTP → RabbitMQ ni cambios de plataforma.
HTTP sigue oficial; relay DISABLED y prueba deshabilitada por defecto.

## Contrato implementado

JWS compacto independiente de ActorContext. Header protegido exacto: `alg=ES256`,
`typ=p360-identidad-pagos+jws`, `kid` UUID canónico en minúsculas. Clave EC P-256,
firma JOSE de 64 bytes R || S, sin HMAC, downgrade, URLs, claves embebidas, payload
separado o cabeceras adicionales.

Payload exacto de 14 claims:

| Claim | Tipo y valor |
|---|---|
| v | Entero JSON 1; no string, decimal o exponente |
| iss | usuarios-service |
| tenantId | UUID canónico del ActorContext autenticado |
| entraObjectId | UUID canónico correspondiente a sujetoId del actor |
| usuarioId | Entero JSON positivo hasta Long.MAX_VALUE; ID local persistido |
| activo | Boolean true |
| perfilVerificadoEn | Timestamp UTC canónico |
| emitidoEn | Timestamp UTC canónico |
| expiraEn | Timestamp UTC canónico |
| deadlineOriginal | Timestamp UTC del presupuesto inicial efectivo |
| aud | p360.pagos.consultas.q |
| operacion | pago.consultar.v1 |
| usuariosRequestId | UUID canónico del messageId del request de Usuarios |
| jti | UUID aleatorio nuevo por emisión; no promete consumo único |

Canonicalización específica del esquema plano: claves ordenadas lexicográficamente,
JSON UTF-8 compacto sin whitespace o escapes alternativos y sin duplicados, timestamps
`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`. No se afirma canonicalización RFC 8785 de JSON arbitrario:
usuarioId conserva precisión Long y no se convierte a double.
Base64URL sin padding, con comprobación de recodificación. Se rechazan extras,
faltantes, trailing tokens, UTF-8 inválido, tipos alternativos y overflow.

Límites antes de interpretar: JWS 4096 bytes (solo ASCII permitido por segmentos),
header decodificado 256 bytes, payload decodificado 2048 bytes, firma 64 bytes.
No contiene JWT, roles, scopes, PII ni ID del pago.

## Autoridades y claves

ActorContext continúa en `ActorContextSigner` y su configuración previa: privada solo
BFF y públicas para consumers. Usuarios mantiene role SERVICE y no puede emitir actores.

`UsuariosIdentityProofSigner` vive exclusivamente en usuarios-service. La configuración
de la nueva privada también vive allí. El core contiene un modelo, codec, catálogo
exclusivamente público y verificador; no contiene una capacidad de firma de pruebas.
BFF contiene únicamente el verificador de esta autoridad (conserva su privada de actor).
Pagos podrá reutilizar el verificador en #81, sin modificar su dominio en este PR.

El catálogo público exige entre 1 y 16 claves P-256 válidas. Rechaza material privado,
revocado, kid duplicado, material duplicado bajo distintos kid y reutilización de kid
o punto público de ActorContext. Se comprueba la correspondencia privada/pública
y una firma de arranque en Usuarios. No hay descubrimiento remoto de claves.
Los errores de carga no adjuntan mensajes de parsers que puedan incluir secretos.

Configuración común, sin valores de claves por defecto:

| Propiedad pedidos360.messaging.identity-proof | BFF | Usuarios |
|---|---|---|
| enabled | false por defecto | false por defecto |
| public-jwks | Públicas confiables de Usuarios | Públicas propias de Usuarios |
| clock-margin | 250ms por defecto | 250ms por defecto |
| key-id | No emite pruebas | kid activo requerido |
| private-jwk | Rechazada si el verificador se habilita | Privada requerida al habilitar |

La condición de carga exige relay ACTIVE y enabled=true. DISABLED no necesita estas
claves para HTTP, incluso si el flag de prueba está presente. ACTIVE con prueba habilitada
y claves ausentes/incorrectas falla al arrancar; no hay fallback a identidad sin prueba.
El margen solo se puede aumentar desde 250ms hasta 1s: nunca se deshabilita ni se utiliza
como gracia posterior al vencimiento. Un valor fuera de rango falla cerrado.

Rotación documental: distribuir la nueva pública a verificadores; activar después
la privada correspondiente en Usuarios; esperar expiración de pruebas y operaciones
en vuelo antes de retirar la anterior. Para revocación urgente, retirar o marcar revocada
la clave en todos los procesos y recargar/reiniciar según el mecanismo operativo aprobado.
Las instancias que aún conservan una pública antigua no conocen una revocación externa.
Esta entrega no crea ni distribuye claves desplegables y no cambia secretos o AWS.

## Una lectura y equivalencia HTTP

El listener existente es `QueryConsumer`; no hay clase UsuariosRabbitConsumer.
Verifica actor, autorización y deadline antes de llamar a `UsuariosQueryProcessor`.
El request de Usuarios permanece `{}` y rechaza cualquier ID o identidad en payload.

`UsuarioService.resolverActual` captura perfilVerificadoEn **inmediatamente antes de
invocar findByTenantIdAndEntraObjectId**, dentro de la transacción readOnly existente.
La marca se trunca hacia abajo a milisegundos y la latencia del SELECT consume los
cuatro segundos. No es actualizadoEn ni un timestamp posterior utilizado para rejuvenecer
un resultado antiguo. No se promete que sea el instante exacto del snapshot MVCC del DB.

La consulta devuelve una sola entidad; se valida tenant/OID y activo y se copia a
`PerfilActualVerificado`. El ID, estado, perfil y firma utilizan esa copia, sin segunda
consulta. Ausencia conserva 404 e inactivo 403. Otras operaciones de UsuarioService
mantienen sus controles anteriores y transacciones.

`obtenerActual` proyecta esa resolución a UsuarioResponse. HTTP /usuarios/me y su DTO
de ocho campos no cambian. Con prueba habilitada, RabbitMQ utiliza UsuarioQueryResponse:
esos mismos ocho campos más pruebaIdentidad. Con prueba deshabilitada mantiene el payload
anterior. Los errores de negocio no incorporan prueba.

## Deadline original y BFF

`QueryOperationBudget` inicia una única operación con identidad y dos relojes:

```
D = floorMillis(min(t0 + presupuestoConfigurado, JWT.exp))
0 < presupuestoConfigurado <= 5 segundos
E = min(perfilVerificadoEn + 4 segundos, D)
perfilVerificadoEn <= emitidoEn < E <= D
```

El JWT debe estar autenticado y tener expiración válida posterior al inicio. D se
almacena, no se recalcula después de Usuarios. La prueba firma deadlineOriginal=D.
Los timestamps nunca se redondean hacia adelante.

RequestFactory admite explícitamente el mismo presupuesto y un plazo efectivo <=D.
El primer request utiliza D; el request de Pagos definido en PR 3 utiliza R <= E−margen.
La preparación de este PR 2 utilizaba E; PR 3 exige el recorte conservador. Los tests
preparan ese segundo envelope, sin publicarlo ni implementar la llamada funcional.
Cada paso lleva su propia audiencia/messageId/correlationId. ActorContext se recorta
por su TTL, el plazo efectivo y JWT.exp, sin renovar ninguno de esos límites.

El BFF descuenta el tiempo monotónico desde el inicio y aplica el menor tiempo restante
entre reloj absoluto y monotónico, también para subplazos. El callback de presupuesto
se utiliza antes del send, al esperar confirm y después del confirm; luego limita la espera
de respuesta. Un retroceso del reloj no reinicia los cinco segundos. No cancela por sí
mismo SQL/send ya iniciados. No se garantiza disponibilidad del segundo request o retry.
Tras verificar la prueba, BFF aplica también `E - margen` al presupuesto monotónico:
un retroceso del reloj de pared no permite recuperar los últimos 250ms reservados.
La construcción de la proyección tiene controles anteriores y posteriores de E-margen y D.
El resultado interno conserva ese límite efectivo y el adaptador comprueba ambos plazos
nuevamente después de resolver y construir el resultado, justo antes de devolverlo.
La garantía temporal llega hasta esa última comprobación local; no cubre una pausa posterior
del runtime ni el transporte/serialización HTTP que todavía no se ha integrado.

Un agotamiento observado de D o de un subplazo invalida definitivamente la operación.
El estado terminal y sus comprobaciones están sincronizados: un retroceso del reloj o
una llamada concurrente no puede reactivarlo. Los timeouts observados al verificar la
prueba, publicar o esperar también invalidan el presupuesto. No existe reset; RequestFactory
y claimUsuarios rechazan el mismo presupuesto terminal.

Con prueba habilitada el adaptador solicita Usuarios como máximo una vez por presupuesto;
la redelivery/retry del mismo request en el servicio conserva el deadline original.
No se implementa refresco de prueba mediante una nueva consulta.

`BffIdentityProofValidator`, después de validar correlación/operación/messageId, verifica:
firma y pública local de Usuarios, esquema, issuer/audiencia/operación, tenant/OID,
usuariosRequestId, D exacto, vigencia/frescura, ID del DTO igual al firmado y activo=true.
Devuelve la proyección sin JWS y la prueba validada como resultado interno tipado.
La proyección exige exactamente los ocho campos HTTP más pruebaIdentidad en la respuesta
interna: id Long positivo coincidente, activo=true, nombre/apellido/email strings no nulos,
telefono string o null explícito y creadoEn/actualizadoEn como instantes ISO-8601 UTC.
Los instantes del perfil conservan su precisión original; el requisito de milisegundos
canónicos corresponde a la prueba firmada. Se rechazan campos faltantes, tipos incorrectos,
campos extra y objetos anidados; la whitelist copia únicamente los ocho escalares permitidos.
No se modifican UsuarioResponse, sus rutas ni las reglas de negocio del dominio.
El adaptador devuelve solamente la proyección HTTP. Un payload con prueba recibido con
verificador deshabilitado se rechaza: no se expone automáticamente ni se utiliza sin validar.

El verificador exige `ahora + margen < expiraEn`; igualdad rechaza. Rechaza emisión en
el futuro fuera del margen y E diferente de min(verificación+4s,D). El margen resta tiempo
utilizable; no añade gracia. La garantía distribuida depende del desfase real acordado.

## Errores, confirm, ACK y DLQ

Una firma/vínculo/estructura/clave inválidos se distinguen de VENCIDA. En BFF, inválida
produce QueryUnavailableException (502 del contrato del adaptador), mientras VENCIDA
produce QueryTimeoutException (504 funcional). No hay fallback HTTP automático tras
una prueba inválida. JWT ya vencido al iniciar no permite emitir ActorContext.

En listeners funcionales IdentityProofException pertenece a la clasificación definitiva
ya reconocida por QueryFailureHandler (AccessDeniedException): diagnóstico sin retry de
negocio. VENCIDA conserva ese motivo explícito y nunca se presenta como firma inválida.
El BFF puede haber agotado su espera; no se añade una respuesta tardía funcional.

El request original solo admite ACK tras respuesta o transferencia confirmada y sin
return. Nack/return son rechazos conocidos; transporte, interrupción o timeout posterior
al envío mantienen resultado incierto. El nuevo callback monotónico conserva esos estados:
NO_ENVIADO antes de send, INCIERTO después de enviar sin confirm observado, CONFIRMADO
después del confirm positivo sin return aunque el presupuesto se agote después.
Un resultado incierto no publica alternativa; conserva original y recovery con backoff.
Si la espera de confirm termina en timeout, un callback que devuelve cero/negativo o
lanza QueryTimeoutException demuestra agotamiento funcional (504) y conserva INCIERTO.
Con presupuesto vigente, el timeout de confirm/transporte sigue siendo indisponibilidad
(502); nack/return concluyentes conservan RECHAZADO_CONFIRMADO. No hay publicación alternativa.

El handoff diagnóstico a DLQ conserva su timeout propio de publisher confirm, independiente
de D. No renueva negocio/respuesta/retry. Se conserva un retry corto por request y las policies
actuales. ResponseConsumer sigue siendo el único listener BFF y mantiene su ACK/descarte:
una prueba inválida detectada después de correlacionar no se transfiere automáticamente
a otra DLQ desde la cola compartida de respuestas.

No registrar prueba completa, JWT, perfil o claves. Los tipos de prueba/resultados internos
utilizan toString redactado. Los motivos públicos de error son estables y no contienen payload.

## Compatibilidad, habilitación y rollback documentales

Se conserva operación v1, request {}, envelope exterior y topología 21/7/20/21. No se
modifican policies, permisos, frontend, Pago→Pedido, outbox o idempotencia. No se agrega
consumer de respuestas, RPC Pagos→Usuarios o consumer Pagos. HTTP es el transporte oficial.

La ampliación v1 requiere coordinación. El lector BFF anterior devolvía payload sin
proyección y el test de Usuarios comparaba payload completo con HTTP. El test ahora
compara el perfil por separado y verifica la firma. No se asume compatibilidad de otros
lectores estrictos: deben comprobarse antes de habilitar; si existen y no pueden coordinarse,
el despliegue se bloquea y requiere versionar la operación por decisión posterior.

Secuencia futura, pendiente de autorización: actualizar core/BFF/Usuarios con flags apagados;
provisionar claves separadas; verificar lectores y suites; habilitar firma/verificación de
forma coordinada; implementar y auditar #81; analizar aislamiento multitenant y ADMIN;
solo después evaluar el corte de #70. Este documento no ejecuta esa secuencia.

Rollback por decisión de #70: mantener/restaurar el modo HTTP oficial con JWT validado y
tratamiento de operaciones en vuelo; no degradar RabbitMQ a payload sin prueba, HMAC ni
fallback automático tras una firma inválida. No ampliar timeouts para ocultar expiraciones.

## Riesgos residuales y bloqueo explícito para #81

- Prueba puntual de actividad: desactivación posterior puede no ser visible hasta expirar.
- Replay acotado para lectura permitido; jti sirve para trazabilidad y no uso único.
- La firma protege identidad/estado y sus límites; no firma PII ni el envelope completo.
- Usuarios firma el deadline recibido. BFF honesto lo contrasta con el contexto inicial;
  la prueba no demuestra independientemente que el JWT original era auténtico frente a BFF comprometido.
- BFF comprometido conserva su privada de ActorContext y puede falsificar identidad/roles;
  Usuarios comprometido conserva capacidad de emitir pruebas. No se prometen esas defensas.
- JWS no cifra; confianza operacional depende de proteger claves y sincronizar relojes.
- Un proceso con pública antigua no conoce una revocación hasta actualizarse.
- Esta preparación no añade protección completa al encadenamiento funcional que aún no existe.
- **Pendiente obligatorio #81:** Pago guarda usuarioId local y no tenant. Analizar aislamiento
  multitenant, alcance ADMIN y autorización entre tenants antes de autorizar su consumer.
  La prueba no resuelve automáticamente ese problema. PagoService.obtener y su dominio no cambian.

## Correcciones de auditoría del PR #93

HEAD auditado: d69f80015070b307ff9a81cd73499937082fdf9d. La autorización posterior
se limita a los cuatro hallazgos y sus regresiones; no incorpora activación ni consumer Pagos.

| Hallazgo | Corrección y reproducción permanente |
|---|---|
| P1, éxito posterior a expiración durante proyección/devolución | BffIdentityCorrectionTests: pausas locales en la copia de un escalar permitido y tras el validador real; 3749ms acepta, 3750/4001/5001ms rechazan con QueryTimeoutException para E=4000ms, D=5000ms, margen=250ms |
| P2, proyección arbitraria/JWS anidado | Whitelist de ocho campos y forma interna exacta de nueve; regresiones de cada campo ausente/tipo incorrecto, extras, JWS anidado, nulabilidad, timestamps, ID y actividad |
| P2, presupuesto reactivado tras timeout | Estado terminal sincronizado; regresiones avance a D/subplazo, timeout, retroceso y nueva planificación rechazada; 32 llamadas concurrentes continúan rechazadas |
| P2, cero/negativo interpretado como indisponibilidad tras timeout de confirm | RequestPublisherBudgetCorrectionTests: cero, negativo y excepción → timeout funcional INCIERTO; presupuesto positivo/transporte → indisponibilidad; nack/return mantienen rechazo concluyente; vencido antes del send → NO_ENVIADO |

La reproducción temporal original del P1 copiaba un payload mínimo de dos campos,
que ya no pertenece al contrato aceptado. La regresión permanente utiliza el perfil
completo y una copia lenta de un campo de la whitelist para aislar el control temporal.
Los avances de reloj y fallos de confirm/transporte son inyecciones deterministas,
no evidencia de pausas reales del runtime, partitions de red ni llamadas a Pagos.

## Pruebas y evidencia

Resultados reproducibles de la ejecución final en `evidencias/PR2-tests.json`.
Las suites completas de módulos modificados son core, BFF y Usuarios. La evidencia
identifica cuáles utilizan RabbitMQ/PostgreSQL reales y cuáles inyectan fallos.
No afirmar pruebas de partición real de red, despliegue AWS, secretos operativos,
consumer Pagos o corte HTTP→RabbitMQ: ninguno se ejecuta en esta entrega.

Ejecución del 8 de octubre de 2026 sobre el código del commit
`d36943cbe792380bb3536bec98cc40110ed45df8` (los commits documentales posteriores no cambian ese código):

| Módulo / comando completo | Casos | Fallos / errores | Omitidos |
|---|---:|---:|---:|
| p360-messaging-core / mvn -B -ntp install | 197 | 0 / 0 | 0 |
| BFF / mvn -B -ntp test | 139 | 0 / 0 | 0 |
| Usuarios / mvn -B -ntp test | 79 | 0 / 0 | 0 |
| Restaurantes / mvn -B -ntp test | 34 | 0 / 0 | 0 |
| Productos / mvn -B -ntp test | 44 | 0 / 0 | 0 |
| Pagos / mvn -B -ntp test | 81 | 0 / 0 | 1 |
| Total | 574 | 0 / 0 | 1 |

**573 casos ejecutados con éxito; 1 omitido.** La omisión es
`EntraWorkerLiveTests.autenticaConProveedorReal`: no está definida la variable
RUN_ENTRA_WORKER_LIVE. No se probó autenticación con el proveedor Entra real.

Las seis suites completas se ejecutaron después de instalar el core corregido, sobre
las fuentes finales identificadas por el commit de código indicado. La evidencia incluye
casos, resultados, hashes de reportes/logs, marcas UTC de escritura de los reportes y
SHA-256 normalizado a LF de los 33 archivos de implementación de este PR.
Primero se ejecutaron las regresiones específicas: core 49 casos y BFF 58 casos,
ambas sin fallos/errores/omisiones. Esos casos están incluidos en las suites completas,
no se suman dos veces. Se añaden 54 regresiones nuevas (11 core y 43 BFF).

| Evidencia | Naturaleza |
|---|---|
| IdentityProofTests / BffIdentityProofTests / UsuariosIdentityProofTests | Criptografía real con claves efímeras en memoria; escenarios unitarios y configuración aislada |
| UsuariosRabbitTests | Listener real, RabbitMQ quorum y PostgreSQL reales; fallos DB/servicio específicos inyectados mediante spy |
| BffConsultasAdapterTests | RabbitMQ real; servicio de Usuarios simulado, validación/proyección BFF real |
| BffIdentityCorrectionTests / RequestPublisherBudgetCorrectionTests | Regresiones permanentes de los cuatro hallazgos; relojes, pausas y transporte inyectados, sin broker en esas clases |
| SettlementPublicationTests | Fallos confirm/transporte/ACK/tiempo inyectados; incluye presupuesto monotónico y estados de publicación |
| QueryMessagingFlowTests / PlatformCompatibilityTests / RecoveryRealTests | Integración de broker existente, no despliegue de producción |
| RestaurantesRabbitTests / ProductosRabbitTests | Regresiones de listeners y broker/DB reales |
| Suite Pagos | Regresión existente de HTTP/dominio/Pago→Pedido; no nuevo consumer de consulta |

## Entrega Git y revisión

Rama: `feature/81-usuarios-identity-proof`; destino develop. El PR se entrega DRAFT
para auditoría independiente, con Refs #81 / #78 / #70 y sin cierres automáticos.
Contrato, fuentes, configuración por defecto, evidencia y pendientes están preparados
para revisión. **READY FOR AUDIT** no autoriza merge, claves operativas, activación o despliegue.
