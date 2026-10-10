# EP2 — ejecución local académica, 10 octubre 2026

**LOCAL EP2 PARTIAL.** Angular y la modalidad acotada RabbitAdmin están
implementados y probados localmente. Los nueve artefactos compilan. No se acredita
la entrega cloud, login Entra live de Angular ni reinicio automático fiable del
clúster demostrativo. No se ejecutó ninguna operación AWS, publicación, migración
RDS, activación, merge o cierre de issue.

Base remota verificada: `f6a9540e5db9890a7716184b917c718d35a21de6`.
Implementación: `1a3bb0df2c4e28ee55133b74cb0f21937a9d1628`.
Rama: `codex/ep2-academic-local-completion`. Evidencia y hashes completos en
`LOCAL-ACADEMIC-EP2-tests.json`; los logs completos permanecen fuera de Git.
No se versionan PDF oficiales, certificados ni contraseñas.

## Resultado implementado

- Angular 21 real y modular: rutas, MSAL Browser, perfil, catálogo, carrito,
  checkout, pedidos propios y pagos mediante contratos existentes. React no cambió.
  Validación de destino/scopes, enlace de cuenta durante solicitudes y errores
  sanitizados; no bearer ID token ni tenant/usuario aportados al backend.
- Checkout HTTP predeterminado. RabbitMQ no hace DELETE ni promete vaciado tras
  201. GET recuperable no crea pedidos/pagos ni cambia Idempotency-Key.
  Una escritura incierta bloquea otro POST; registro conservador en sessionStorage.
- RabbitAdmin: PUT opcional explícito de cola desechable exclusiva; DELETE 204 solo
  para esa modalidad vacía/propietaria. Durables y recursos críticos conservan 403
  o rechazo de ámbito. JWT, ADMIN y scope obligatorios. Exchanges/bindings CRUD
  probados. [Contrato y límites](RABBITADMIN-DISPOSABLE-QUEUES.md).
- Overlay **local de build**, no de activación, para Angular. Multihost existente
  y parámetros Compose se conservaron. Inspector reproduce verificación de las
  nueve imágenes sin arrancar aplicaciones ni publicar.

## Pruebas ejecutadas

| Suite completa | Ejecutados | Correctos | Omitidos | Fallos |
|---|---:|---:|---:|---:|
| Core (clean install antes de servicios) | 268 | 268 | 0 | 0 |
| BFF | 205 | 205 | 0 | 0 |
| Usuarios | 79 | 79 | 0 | 0 |
| Restaurantes | 35 | 35 | 0 | 0 |
| Productos | 45 | 45 | 0 | 0 |
| Carrito | 99 | 99 | 0 | 0 |
| Pedidos | 152 | 151 | 1 Entra live | 0 |
| Pagos | 197 | 196 | 1 Entra live | 0 |
| RabbitAdmin | 68 | 68 | 0 | 0 |
| Angular TestBed/Vitest 4.1.11 | 29 | 29 | 0 | 0 |
| React (sin cambios) | 253 | 253 | 0 | 0 |

Backend: Maven `clean verify` por servicio; core `clean install` y RabbitAdmin
`test` seguido de `package -DskipTests` (este package **no** es otra ejecución de
tests). Total backend: 1.146 correctas / 2 omitidas. Java 21, Docker Desktop 29.4.3,
PostgreSQL 17 y RabbitMQ Testcontainers donde los bancos lo requieren.

Las suites mezclan unidades/mocks, fallos inyectados y listeners HTTP/AMQP reales.
No se presenta una excepción inyectada como partición física de red. Entra JWT de
RabbitAdmin usa firmas RSA/validadores reales con autoridad fixture, no Entra live.
Se probaron las cinco migraciones pendientes mediante los bancos PostgreSQL de
Carrito/Pedidos/Pagos, incluyendo histórico, roles runtime y restricciones; esto
no acredita los roles/owners ni recuperación de RDS real.

Angular: las 29 pruebas son fixtures de SDK/API, HttpTestingController y componentes
reales; no E2E de ocho servicios. Inspección adicional en navegador real de la
pantalla sin sesión. Login usa SDK real y flujo redirect/PKCE, pero la autenticación
contra Entra/API Manager cloud y el recorrido autenticado de Angular quedan por
demostrar. Las cuentas públicas de configuración usadas en builds son sintéticas.
No se habilitó un bypass de autenticación para conseguir evidencia.

Otros resultados: Angular typecheck y builds production HTTP/RABBITMQ correctos;
React oxlint y build correctos (advertencia de chunk >500 kB). `npm audit` Angular
final, incluyendo dev: 0 avisos; la selección inicial Vitest 4.1.0 tenía avisos y
se sustituyó por 4.1.11 antes del build final. Doce regresiones multihost, una
simulación Compose local de transición/rollback y cinco checks de configuración
del demo pasaron. `git diff --check` correcto.

## Clúster: fallo real conservado y recuperación

Se reutilizó `infrastructure/rabbitmq/cluster-demo`, sin cambiar código/topología
ni borrar datos. Primera puesta en marcha con volúmenes existentes: el nodo 2
terminó con `node_not_running`; repetir el mismo start recuperó los dos nodos.
Provision/verify confirmó 2 miembros, 3 colas + 3 DLQ quorum con 2 réplicas online,
exchanges direct/topic, TLS verificado y volúmenes/redes propios.

La suite real de 11 casos terminó **9 correctos y 2 errores**. Membresía,
replicación, direct/topic, confirms, returns, ACK/redelivery, NACK a las 3 DLQ,
permisos/TLS y caída SIGKILL/recuperación pasaron. El reinicio completo falló otra
vez al arrancar nodo 2; la sonda siguiente falló por indisponibilidad TLS.
Se repitió start sobre los mismos volúmenes sin reset, force_boot, purge ni
forget_cluster_node. Se verificó mayoría, se recuperaron y ACKearon los tres
mensajes sintéticos persistidos por el caso fallido, y la sonda AMQP 1.0 pasó
separadamente. Esto **no convierte la suite original en verde** ni demuestra
arranque desatendido fiable. Ambos nodos demo se dejaron detenidos al terminar;
volúmenes preservados y broker local ajeno intacto.

Bloqueante local pendiente: investigar/corregir de forma focalizada el reinicio
del demo antes de presentarlo como reproducible. No se debilitó la prueba ni se
amplió esta tarea a cambios de servidor/configuración ajenos al empaquetado.
Dos nodos no aportan HA con pérdida de una réplica quorum; sin TLS Erlang en este
perfil local, sin demostración de particiones y sin resolución operativa del
aviso GHSA-6588-rqcr-59pw. No trasladar estas claves/perfil directamente a AWS.

## Artefactos

Nueve imágenes **locales**, linux/amd64: Angular + BFF + Usuarios + Restaurantes +
Productos + Carrito + Pedidos + Pagos, más RabbitAdmin. Referencias y composición
exacta en el manifiesto. No son digests ECR publicados ni imágenes cloud aprobadas.
Runtime non-root 101:101 en Angular y 10001:10001 en Java. JARs extraídos de las
imágenes verifican amqp-client 5.37.0, Jackson core/databind 3.1.7 y Tomcat 11.0.26.
No se encontraron .p12/.jks/.key/.pem como recursos classpath de aplicación.
Esto no equivale a un escaneo completo de secretos/CVEs de todas las capas.

Reproducir Java: `docker build --platform linux/amd64 --build-context
messaging-core=backend/shared/p360-messaging-core -t p360-local/<servicio>:ep2-academic
backend/<ruta>` (BFF usa `backend/bff`, los demás `backend/services/<nombre>-service`).
RabbitAdmin usa `infrastructure/aws/rabbitmq/Dockerfile.rabbit-admin` y contexto
adicional `maven-wrapper=backend/services/usuarios-service`. Angular tiene su
Dockerfile propio y cinco ARG públicos del README. `python -B
infrastructure/aws/inspect-local-ep2-images.py` comprueba los tags locales y JARs,
creando/eliminando únicamente contenedores de inspección etiquetados, sin start.
Los builds Java utilizaron caché de BuildKit; el core se construye desde el contexto
del mismo checkout, no se copia .m2 del host. No se afirma build sin caché.

## Matriz de ambas pautas

Se reutilizaron las lecturas íntegras de los PDF oficiales ya acreditadas, sin
copiarlos al repositorio. Hashes de fuente en el manifiesto.

| Indicador | Evidencia local | Estado final de entrega |
|---|---|---|
| Configuración centralizada / beans (12% / 13%) | Core y topología existente; suites verdes | Cloud pendiente |
| Separación / listeners (10% / 15%) | Productores, consumers y outbox integrados; regresiones reales | Activación cloud pendiente |
| ACK/NACK, retry, errores, DLQ (20%) | Core/servicios; broker real del demo y sus 3 DLQ | Flujo cloud pendiente |
| REST admin / servicio / validación (13% / 10% / 7%) | 68 pruebas; CRUD demo acotado y API protegida | Revisión y despliegue pendientes |
| Angular del Encargo | App real compilable y contratos probados; React intacto | Login/recorrido autenticado pendientes |
| Dos nodos / 3 colas+DLQ / direct-topic / Compose | Membresía y mensajería real local | Reinicio local y despliegue AWS pendientes |
| Front/backend cloud, API Manager JWT, IDaaS | Implementaciones existentes; ninguna nueva evidencia cloud | No acreditado en esta ejecución |
| Código final GitHub y evidencias reales cloud | Rama y PR Draft; builds/pruebas locales | Merge autorizado y captura cloud pendientes |

## Pendientes exclusivamente operativos/cloud

Autorización agrupada para publicar imágenes finales ECR, aplicar las cinco
migraciones con roles/owners y recuperación acreditados, distribuir secretos
necesarios sin ampliaciones de permisos, desplegar multihost/TLS, formar el clúster
demo separado, verificar API Manager/Entra/Angular, retirar binarios/clientes antiguos,
activar los cortes aprobados y ensayar rollback tenant-aware. No se presupone que
revertir imágenes revierte esquema ni que clientes viejos dejan de hacer DELETE.

Antes de esa ventana también faltan revisión/merge de este PR, acreditación de login
Angular y resolver el fallo local de reinicio. Por ello **no** se declara que lo único
pendiente es AWS, ni `EP2 v0.8.0 READY FOR FINAL SUBMISSION`. La fecha única continúa
siendo lunes 12 de octubre a las 08:00 Chile; cualquier requisito sin evidencia antes
de ese momento debe presentarse como incompleto.
