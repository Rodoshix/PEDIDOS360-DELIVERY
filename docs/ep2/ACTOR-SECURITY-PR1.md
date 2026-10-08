# PR 1 — ActorContext asimétrico y reliability

Fecha de verificación: 8 de octubre de 2026 (America/Santiago).

Veredicto: **READY FOR AUDIT**. Preparado para revisión del responsable; no autoriza merge ni activación.

Refs #81. Refs #77.

## Motivación y alcance

El secreto HMAC compartido permitía que cualquier consumer con acceso a él emitiera actores válidos.
Ahora solo el BFF carga una privada de emisión y los consumers cargan públicas confiables. Se preservan
tenant, identidad Entra oid, roles, scopes, vigencia, audiencia y autorización local de negocio.

La base inicial se verificó limpia y sincronizada: develop y origin/develop en
e62959c29e5d2e693c68746a113280cec843919a. No hubo cambios ajenos ni AGENTS.md aplicables.
El productor real es BFF y los verificadores reales son Usuarios, Restaurantes y Productos mediante
QueryConsumer compartido. La búsqueda de HMAC productivo encontró únicamente ActorContextSigner;
los otros usos explícitos eran fixtures de prueba del mismo flujo.

GitHub #77 está CLOSED. #81 está OPEN y su título es «EP2-16 — Pagos RabbitMQ query: consulta por ID»;
se conserva como referencia solicitada, sin implementar su consumer ni cerrar el issue.

## Contrato final

El envelope ConsultaPedidos360 V1 conserva su estructura y actor sigue siendo un string. Su contenido
es JWS compacto estándar: header protegido base64url, payload base64url y firma ES256 de 64 bytes.

Header exacto: alg=ES256, typ=p360act2 y kid UUID canónico. Payload exacto: v=p360act2, tenantId,
sujetoId, roles, scopes, emitidoEn, expiraEn y audiencia. keyId no es un claim; ActorContext.keyId()
conserva metadata de la cabecera verificada para compatibilidad interna.

Nimbus JOSE JWT 10.9.1 realiza la firma y verificación ES256/P-256. Es la misma versión ya resuelta
por Spring Security del BFF y se declara explícitamente en el core. Java efectivo: 21.0.8.
[Referencia oficial ES256](https://connect2id.com/products/nimbus-jose-jwt/examples/jws-with-ec-signature).

Se rechazan algoritmos diferentes, headers o claims adicionales/faltantes/duplicados, kid desconocido
o retirado, firmas inválidas, audiencia/tenant incorrectos, tipos inválidos, actor vencido, emisión
futura fuera de tolerancia y plazos incoherentes. No se resuelven claves, JWKs ni URLs aportados por
el mensaje. No hay fallback HMAC ni transporte del JWT de Entra.

Límites: sobre 16384 caracteres, header codificado 1024, payload decodificado 8192 bytes, roles/scopes
64 elementos únicos de hasta 64 caracteres, audiencia 256 caracteres, vigencia positiva hasta
5 minutos y tolerancia configurable entre 0 y 60 segundos. Los defaults de plazo/TTL siguen 5s/4s.

El contrato permite redelivery de lecturas durante la vigencia. No promete uso único ni firma de
messageId, operación o payload. Un actor se puede reutilizar en su audiencia mientras está vigente;
replay vencido o en audiencia ajena se rechaza. No se añadió una semántica nueva de deduplicación.

## Configuración y compatibilidad

- BFF: PEDIDOS360_ACTOR_KEY_ID, PEDIDOS360_ACTOR_PRIVATE_JWK y PEDIDOS360_ACTOR_PUBLIC_JWKS.
- Consumers: únicamente PEDIDOS360_ACTOR_PUBLIC_JWKS, además del tenant ya existente.
- public-jwks es un JWK Set local confiable con 1–16 públicas EC P-256 y kids únicos.
- SERVICE rechaza privada explícita en private-jwk o dentro del conjunto de públicas.
- BFF exige privada y pública coincidentes; hace una firma/verificación de configuración antes de
  arrancar. Claves marcadas como revocadas, curvas incorrectas o kids duplicados se rechazan.
- Retirar una pública revoca su confianza al recargar/reiniciar cada proceso. La configuración es
  una instantánea inmutable: no se implementa consulta remota ni recarga automática.
- ACTIVE sin claves válidas falla cerrado al iniciar. DISABLED no carga estas claves.
- PEDIDOS360_RELAY_MODE=DISABLED permanece por defecto; HTTP oficial y sus DTO no cambian.
- PEDIDOS360_ACTOR_SECRET deja de consumirse. Configuración antigua no habilita el modo protegido.

Todos los fixtures de firma son efímeros, generados únicamente en memoria desde src/test. El bean
consumidor de las integraciones recibe exclusivamente públicas; el productor de prueba firma por
separado. No se generaron ni versionaron claves para despliegue, ni se modificaron secretos externos.

## Correcciones de reliability

Se respeta un retry máximo, también cuando un return/nack conocido impide publicar una respuesta de
negocio en la entrega de retry. El contador largo se satura antes de convertir a int para impedir
que un desbordamiento permita intentos adicionales.

El deadline se comprueba al recibir, inmediatamente antes del processor y después de este. También
se recalcula al gestionar un fallo y antes del handoff normal. Requests, respuestas y retry no se
envían con plazo agotado; sus confirms usan min(confirm-timeout, presupuesto restante) después del
envío. Las respuestas tienen TTL individual igual al resto, sin renovar expiresAt. Los requests
no agregan TTL individual y las policies permanecen intactas.

El BFF espera la respuesta usando expiresAt absoluto y limpia la correlación también si el publisher
lanza QueryTimeoutException. No se cambia ningún endpoint ni contrato HTTP público.

### Excepción diagnóstica confirmada por el usuario

Una consulta vencida no responde ni vuelve a ejecutar negocio ni entra a retry. Exclusivamente la
publicación diagnóstica a DLQ mantiene su confirm-timeout configurado, independiente del plazo
agotado. Preserva expiresAt y añade plazo-vencido al diagnóstico.

ACK del original solo tras confirm positivo sin return. Un nack, return o resultado incierto del
handoff deja el original sin settlement y solicita la recuperación con backoff existente. Si el
resultado de publicar una respuesta es incierto, no se publica otra copia a retry/DLQ: se conserva
el original y la redelivery comprueba de nuevo el deadline antes de ejecutar. Se preservan mandatory,
publisher returns y confirms correlacionados. No se aumentan timeouts ni se cambian policies/topología.

## Pruebas y evidencias

Los resultados finales por suite y caso se guardan en [PR1-tests.json](evidencias/PR1-tests.json),
extraídos de los XML Surefire locales sin copiar logs sensibles ni contar repeticiones como casos nuevos.

| Módulo | Casos de regresión completos | Fallos / errores / omitidos |
|---|---:|---|
| p360-messaging-core | 100 | 0 / 0 / 0 |
| BFF | 79 | 0 / 0 / 0 |
| Usuarios | 73 | 0 / 0 / 0 |
| Restaurantes | 34 | 0 / 0 / 0 |
| Productos | 44 | 0 / 0 / 0 |
| Total, sin duplicar reejecuciones | 330 | 0 / 0 / 0 |

Comandos reproducibles, desde la raíz:

```powershell
mvn -f backend/shared/p360-messaging-core/pom.xml install
mvn -f backend/bff/pom.xml test
mvn -f backend/services/usuarios-service/pom.xml test
mvn -f backend/services/restaurantes-service/pom.xml test
mvn -f backend/services/productos-service/pom.xml test
git diff --check
```

El core final pasó sus 100 casos. Después de las últimas comprobaciones de deadline, se revalidaron
con ese artefacto BffConsultasAdapterTests (11), UsuariosRabbitTests (25), RestaurantesRabbitTests (25)
y ProductosRabbitTests (22): todas sin fallos, errores u omisiones.

Separación de evidencias:

- Core sin broker: ActorSecurityTests 29, RelayReliabilityTests 17, contrato 17, clasificación 3 y
  recuperación 7. Cubren límites, firmas, configuración, retry, deadline, TTL y decisiones ante
  confirms inciertos/negativos y returns inyectados. Los mocks no acreditan comportamiento del broker.
- Core con RabbitMQ real Testcontainers: PlatformCompatibilityTests 4, QueryMessagingFlowTests 22,
  RecoveryRealTests 1. Cubren confirms y returns reales, retry/DLQ, redelivery con recuperación y
  compatibilidad de policies. Algunas pruebas invocan el consumer con Channel mock para verificar
  orden de ACK; RecoveryRealTests y los servicios prueban listeners reales.
- Suites RabbitMQ de servicios: BFF 11, Usuarios 25, Restaurantes 25, Productos 22. Arrancan brokers
  aislados; los tres servicios usan PostgreSQL y políticas quorum con permisos de consumer sin
  capacidad de configurar topología. La nueva prueba de limpieza de correlación en BFF inyecta un
  publisher mock; no se presenta como confirm real.
- Los tests nuevos del core con broker demuestran return real de una respuesta de negocio en el
  segundo intento → DLQ sin retry adicional, y vencimiento durante processor → solo diagnóstico
  DLQ, expiresAt original y ninguna respuesta funcional.
- La pérdida/latencia de confirm se inyecta en pruebas unitarias. No se simuló una partición de red
  real del broker. La recuperación real acredita conservación/redelivery ante handoff returned.

Las ejecuciones iniciales detectaron fixtures que aún esperaban un 504 RabbitMQ fuera de plazo y
una mutación que producía base64url no canónico. Se corrigieron los fixtures a las garantías nuevas
y las ejecuciones finales pasaron. Las regresiones HTTP oficiales continúan incluidas en los módulos.

El inventario estático de infrastructure/rabbitmq/platform_control.py sigue produciendo 21 queues,
7 exchanges, 20 bindings y 21 policies. No se modificó ese archivo ni otra infraestructura.
No se midió ni alteró un broker compartido/AWS; los brokers de prueba son aislados y no representan
la topología completa de producción.

## Riesgos residuales y exclusiones

La privada del BFF sigue siendo un secreto operativo; una intrusión en BFF permite emitir actores.
Replay en la misma audiencia durante vigencia y ausencia de enlace a payload/messageId permanecen
según contrato. Distribución de claves y revocación requieren coordinación externa y reinicio; no
hay fetch remoto ni gestor de secretos implementado. El timeout limita la espera de confirms, pero
no interrumpe un processor o una llamada send bloqueante. Si se pierde el ACK tras respuesta
confirmada, una redelivery puede repetir la lectura y producir una respuesta duplicada.

No AWS, despliegues, activación pública, corte BFF #70, frontend, contratos HTTP, reglas de dominio,
prueba nueva de identidad de Usuarios, consumer Pagos #81 ni flujo Pago → Pedido. No se cerraron
issues, no se hizo merge, no se generaron claves desplegables y no hay dependencias circulares.

## Plan documental de activación y rollback

1. Mantener DISABLED en todo el conjunto mientras se distribuyen binarios/configuración.
2. Aprovisionar privada únicamente en BFF y públicas en cada consumer mediante el canal confiable
   externo. Verificar roles, kids, pares coincidentes y ausencia de material privado en consumers.
3. Reproducir pruebas locales de firma, contrato, HTTP y RabbitMQ antes del corte futuro de #70.
4. Activar coordinadamente solo bajo el procedimiento autorizado de #70; este PR no lo ejecuta.
5. Rotar agregando pública nueva, cambiar privada/kid emisor, esperar vigencia/drenado y retirar la
   anterior. Para compromiso, retirar la pública/reiniciar verificadores sin aceptar fallback.
6. Rollback: DISABLED y HTTP oficial primero; después revertir binarios y configuración. No mezclar
   HMAC y ES256 en ACTIVE, purgar colas, renovar deadlines ni reproducir consultas vencidas. Una
   consulta nueva obtiene autorización y plazo nuevos.

Rama: feature/81-asymmetric-actor-security. Destino del PR: develop. Commit y URL definitivos se
entregan con el PR; no se consideran autorización para merge.

## Archivos modificados

La lista completa se incluye a continuación; target/ y logs locales no se versionan.

<!-- files -->

- `backend/bff/src/main/java/cl/duoc/pedidos360/bff/messaging/BffQueryAdapter.java`
- `backend/bff/src/main/resources/application.yml`
- `backend/bff/src/test/java/cl/duoc/pedidos360/bff/messaging/BffConsultasAdapterTests.java`
- `backend/bff/src/test/java/cl/duoc/pedidos360/messaging/fixture/FixtureActorKeys.java`
- `backend/services/productos-service/src/main/resources/application.yml`
- `backend/services/productos-service/src/test/java/cl/duoc/pedidos360/messaging/fixture/FixtureActorKeys.java`
- `backend/services/productos-service/src/test/java/cl/duoc/pedidos360/productos/ProductosRabbitTests.java`
- `backend/services/restaurantes-service/src/main/resources/application.yml`
- `backend/services/restaurantes-service/src/test/java/cl/duoc/pedidos360/messaging/fixture/FixtureActorKeys.java`
- `backend/services/restaurantes-service/src/test/java/cl/duoc/pedidos360/restaurantes/RestaurantesRabbitTests.java`
- `backend/services/usuarios-service/src/main/resources/application.yml`
- `backend/services/usuarios-service/src/test/java/cl/duoc/pedidos360/messaging/fixture/FixtureActorKeys.java`
- `backend/services/usuarios-service/src/test/java/cl/duoc/pedidos360/usuarios/UsuariosRabbitTests.java`
- `backend/shared/p360-messaging-core/pom.xml`
- `backend/shared/p360-messaging-core/src/main/java/cl/duoc/pedidos360/messaging/QueryMessagingConfiguration.java`
- `backend/shared/p360-messaging-core/src/main/java/cl/duoc/pedidos360/messaging/actor/ActorContext.java`
- `backend/shared/p360-messaging-core/src/main/java/cl/duoc/pedidos360/messaging/actor/ActorContextSigner.java`
- `backend/shared/p360-messaging-core/src/main/java/cl/duoc/pedidos360/messaging/actor/SigningKey.java`
- `backend/shared/p360-messaging-core/src/main/java/cl/duoc/pedidos360/messaging/actor/SigningKeyProvider.java`
- `backend/shared/p360-messaging-core/src/main/java/cl/duoc/pedidos360/messaging/relay/HandoffFailureException.java`
- `backend/shared/p360-messaging-core/src/main/java/cl/duoc/pedidos360/messaging/relay/HandoffPublisher.java`
- `backend/shared/p360-messaging-core/src/main/java/cl/duoc/pedidos360/messaging/relay/QueryConsumer.java`
- `backend/shared/p360-messaging-core/src/main/java/cl/duoc/pedidos360/messaging/relay/QueryFailureHandler.java`
- `backend/shared/p360-messaging-core/src/main/java/cl/duoc/pedidos360/messaging/relay/QueryReplyPublisher.java`
- `backend/shared/p360-messaging-core/src/main/java/cl/duoc/pedidos360/messaging/relay/RequestPublisher.java`
- `backend/shared/p360-messaging-core/src/test/java/cl/duoc/pedidos360/messaging/ActorSecurityTests.java`
- `backend/shared/p360-messaging-core/src/test/java/cl/duoc/pedidos360/messaging/MessagingContractTests.java`
- `backend/shared/p360-messaging-core/src/test/java/cl/duoc/pedidos360/messaging/PlatformCompatibilityTests.java`
- `backend/shared/p360-messaging-core/src/test/java/cl/duoc/pedidos360/messaging/QueryMessagingFlowTests.java`
- `backend/shared/p360-messaging-core/src/test/java/cl/duoc/pedidos360/messaging/RecoveryRealTests.java`
- `backend/shared/p360-messaging-core/src/test/java/cl/duoc/pedidos360/messaging/RelayReliabilityTests.java`
- `backend/shared/p360-messaging-core/src/test/java/cl/duoc/pedidos360/messaging/fixture/FixtureActorKeys.java`
- `docs/ep2/ACTOR-SECURITY-PR1.md`
- `docs/ep2/REQUEST-REPLY-RABBITMQ.md`
- `docs/ep2/evidencias/PR1-tests.json`
