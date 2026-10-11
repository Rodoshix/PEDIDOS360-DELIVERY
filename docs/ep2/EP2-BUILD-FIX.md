# EP2: empaquetado autosuficiente del core

Estado: READY FOR EP2 BUILD FIX REVIEW. Preparación local; sin autorización de publicación o despliegue.

## Fuente, causa raíz y alcance

Base develop: 9a782e59dbd28c06c548dcc20ab34689d1f50b7e. Fuente de los cuatro builds y suites backend: **3dc6bd6af72f5aad20b32ca3107e7b662914051f**. El commit posterior registra evidencia y precisa la regresión estática; no cambia Dockerfiles, POM, fuente core/main ni entradas de las imágenes. Los hashes por Git blob y comandos se conservan en EP2-BUILD-FIX-tests.json. Las imágenes se etiquetan con el commit construido, no con un HEAD documental posterior.

BFF, Carrito, Pedidos y Pagos declaran cl.duoc.pedidos360:p360-messaging-core:0.0.1-SNAPSHOT, pero sus recetas solo ejecutaban package. No instalaban el módulo fuente y no podían resolverlo en un builder sin caché previa. Construir otro servicio primero tampoco constituía una garantía reproducible. Los logs de la fase A acreditan los cuatro fallos de resolución.

Se reutiliza el patrón ya presente en Usuarios/Restaurantes/Productos: contexto messaging-core del checkout, COPY de pom.xml y src/main, y Maven install antes de package usando el wrapper del servicio. La librería es un JAR normal, con repackage Spring Boot deshabilitado en su POM. No se modifica su coordenada o versión ni se publica un SNAPSHOT externo.

Archivos cambiados:

- backend/bff/Dockerfile y backend/services/{carrito,pedidos,pagos}-service/Dockerfile: copiar/instalar core antes de empaquetar.
- infrastructure/aws/compose.build.yml: declarar el mismo contexto adicional para esos cuatro servicios.
- backend/shared/p360-messaging-core/.dockerignore: allowlist POM/src/main, excluyendo .m2, target, .env, claves y almacenes de certificados. Evita transferir artefactos del desarrollador por el contexto adicional; no se copia .m2.
- infrastructure/aws/compose.test.mjs: regresión sobre los siete builds Java y exclusiones del contexto.
- Este informe, EP2-BUILD-FIX-tests.json y EP2-MAVEN-ADVISORIES.json: evidencia y triage.

No hay cambios a lógica funcional, HTTP/RabbitMQ, tenant, JWT/roles, POM, Spring/Maven, Compose runtime, certificados, flags o PR #107.

## Builds locales limpios e identidad

| Componente | Build frío | Image ID local | Runtime |
|---|---|---|---|
| bff | PASS | sha256:670be9af80b2dec7e455b6aac8d03c9c1f19a74886a4b1a52c2e93ae5a115884 | linux/amd64; 10001:10001 |
| carrito | PASS | sha256:da2041f99dbbd6380f3d81b926df9b05d7f902b01201217e2456f47ee09c908c | linux/amd64; 10001:10001 |
| pedidos | PASS | sha256:4410c29635e29c08180862589b5a4e2dbd4e7c69b48d87158aaadd228f8f9e02 | linux/amd64; 10001:10001 |
| pagos | PASS | sha256:afae65ca952416bf78ec854e4a974715258af89d9aa3c43eb5304625ec110aa2 | linux/amd64; 10001:10001 |


Se creó un builder Docker-container local nuevo, pedidos360-ep2-fix-cold, sin repositorio Maven previo. BuildKit v0.33.1, imagen de herramienta moby/buildkit:buildx-stable-1@sha256:cec9f139f45e93c5c69c60f8b07cfad9f43f4ef6b6a6cd917527fea5ff2e3dea. Cada build usa --no-cache y namespace de caché distinto. Ningún build monta el .m2 del host; cada receta instala el core desde el mismo git archive antes de resolver el servicio. Las dependencias públicas se descargan cuando es necesario. La caché de descarga optimiza Maven; no constituye la fuente del core.

Las bases Java 21 permanecen fijadas por digest:

- eclipse-temurin:21-jdk-alpine@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6.
- eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699.

ECR: ningún digest candidato acreditado ni push ejecutado. Image ID y descriptor local son resultados Docker/BuildKit; no acreditan un manifiesto publicado. Labels OCI identifican fuente/commit; no son firma criptográfica. Maven 3.9.15/Spring Boot 4.1.1 no se actualizaron.

Inspección local de docker image save y JAR, sin arrancar las imágenes: los cuatro contienen exactamente un BOOT-INF/lib/p360-messaging-core-0.0.1-SNAPSHOT.jar; hashes de aplicación/core y listas de dependencias constan en JSON. No se encontraron directorios .m2/.env, nombres de keystore privado o recursos de aplicación con marcadores de clave privada. Es revisión de nombres/capas y marcadores específicos, no certificación universal de ausencia de secretos. Fuente limpia de Git y COPY selectivo excluyen tests/artefactos locales; no se usaron credenciales reales.

Usuarios non-root, puertos, healthchecks y capa runtime conservados. Compose runtime/multihost no cambia: mantiene read_only, cap_drop, no-new-privileges, tmpfs, TLS y modos inactivos. Ninguna imagen fue arrancada contra AWS.

## Pruebas ejecutadas y límites

| Suite completa | Casos | Correctos | Omitidos | Fallos/errores finales |
|---|---|---|---|---|
| core | 265 | 265 | 0 | 0 |
| bff | 203 | 203 | 0 | 0 |
| carrito | 99 | 99 | 0 | 0 |
| pedidos | 152 | 151 | 1 | 0 |
| pagos | 197 | 196 | 1 | 0 |


914 correctos; 2 omisiones Entra live; 916 casos backend registrados. No se duplican conteos del primer intento de Carrito.

12 pruebas estáticas Node (Compose/EP2) y 13 Python multihost/transición correctas. git diff --check correcto. La regresión nueva valida contexto core y orden install→package para los siete servicios Java; tests existentes verifican TLS, usuarios DB, archivos secretos, límites de puertos y configuración inactiva. No se agregaron tests de negocio que reflejen la implementación del Dockerfile.

Primer intento de Carrito: 99 casos, cero fallos de aserción y un error de entorno CreateProcess 206 al reiniciar una JVM con un classpath demasiado largo en Windows. Se conservan log y XML originales. Repetición completa con junctions locales abreviando fuente/repositorio Maven: 99 casos correctos, incluida caída/reinicio real de JVM y dedupe durable. Sin cambiar tests, dependencias o código para obtener éxito. El primer error no se borra ni se suma como una prueba exitosa adicional.

Core: ActorContext, IdentityProof, protocolo y reliability, con fixtures de claves y RabbitMQ real cuando la suite lo requiere. BFF: correlación, deadlines/aceptación y autorización, combinando tests unitarios/fixtures y broker real. Carrito/Pedidos/Pagos: PostgreSQL real desechable para tenant, integridad, migraciones locales, outbox/fencing/dedupe; broker real para confirm/returns/retry/DLQ y recuperación. Las pruebas de Pagos incluyen aplicaciones reales con listeners HTTP/RabbitMQ y fixture local de firma/decoder Entra; no equivalen a Entra live ni a despliegue AWS. Las inyecciones de nack/timeout/fallos unitarios no se presentan como particiones de red reales. Las clases y conteos exactos están en el JSON.

Las pruebas Entra live condicionadas de Pedidos y Pagos se omiten; no se suministran secretos. No se repitieron frontend ni suites completas Usuarios/Restaurantes/Productos, cuyos Dockerfiles no cambian. La cobertura E2E existente compila/utiliza esos servicios en el banco local. Los builds Docker omiten tests Java; el éxito de pruebas se acredita por las ejecuciones Maven separadas. Las migraciones ejecutadas por tests solo afectaron PostgreSQL Testcontainers desechable, nunca RDS.

## Reproducción local

Exportar el commit fuente con git archive a un directorio limpio. Utilizar herramientas existentes Docker Buildx, Java 21 y Maven 3.9.15. Crear un builder nuevo para comprobar caché vacía, sin sustituir el builder del usuario. Ejemplo de build (paths desde raíz de la exportación):

    docker buildx build --builder pedidos360-ep2-fix-cold --no-cache --platform linux/amd64 --load --progress plain --build-arg BUILDKIT_CACHE_MOUNT_NS=ep2-isolated-bff --build-context messaging-core=backend/shared/p360-messaging-core --label org.opencontainers.image.revision=3dc6bd6af72f5aad20b32ca3107e7b662914051f --tag pedidos360-local/ep2-build-fix-bff:3dc6bd6af72f-local-only backend/bff

Repetir con contextos backend/services/carrito-service, pedidos-service y pagos-service; namespaces y tags distintos. EP2-BUILD-FIX-tests.json conserva los cuatro comandos exactos y metadata. No usar tags operativos.

Para tests: mvn -B -ntp -Dmaven.repo.local=<repositorio aislado> clean install en backend/shared/p360-messaging-core; luego clean verify en BFF/Carrito/Pedidos/Pagos. Mantener flags RUN_ENTRA*_LIVE deshabilitados y fuente sin .env.local. Docker disponible para Testcontainers; sin endpoints AWS. Usar rutas cortas en Windows para tests que lanzan JVM hija.

Validaciones estáticas:

    node --test infrastructure/aws/compose.test.mjs infrastructure/aws/ep2-prepared.test.mjs
    python -m unittest infrastructure/aws/test_multihost.py infrastructure/aws/test_multihost_transition.py
    git diff --check

El overlay compose.build.yml ahora proporciona los contextos de los siete builds Java. compose build requiere las variables públicas existentes para interpolar el modelo; no se añadieron secretos o scripts de despliegue. No ejecutar compose up como parte de acreditación de empaquetado.

## Clasificación de los 20 avisos Maven

Las versiones empaquetadas afectadas son transitivas de starters Spring Boot/AMQP/JSON/Web, no dependencias directas nuevas: amqp-client 5.30.0, tomcat-embed-core 11.0.24 y tools.jackson.core core/databind 3.1.5 en los cuatro runtimes. POM y dependency:tree complementan inspección BOOT-INF/lib. Scope runtime; también se utilizan en compilación/pruebas. No son avisos confinados al builder Maven.

Jackson 2 core/databind 2.21.5 **sí consta en BOOT-INF/lib de Productos en el SBOM de fase A**, como dependencia transitiva del servicio que incorpora Springdoc/OpenAPI; **no está empaquetado en los cuatro runtimes corregidos**. No se reclasifica como dependencia solo de tests ni como falso positivo global: se distingue por servicio y se preservan rutas/digests del SBOM previo. EP2-MAVEN-ADVISORIES.json distingue versión observada en SBOM, versión en los cuatro JAR nuevos, scope/árbol, relación transitiva, rangos afectados, eventos de corrección y aplicabilidad.

Prioridades de revisión separada: P1 antes de publicar/activar la ruta pertinente; P2 análisis de alcance/coerciones/configuración; P3 sin ruta funcional identificada en main, pendiente de reevaluación si cambia configuración. Estas prioridades no sustituyen CVSS del proveedor ni autorizan aceptar riesgos. Presencia de paquete afectado está demostrada; explotación de cada aviso contra nuestra configuración no se ha demostrado. No se ejecutaron PoC destructivos.

| Aviso | Paquete y versiÃ³n presente | Versiones corregidas publicadas | Prioridad | Aplicabilidad / impacto |
|---|---|---|---|---|
| [GHSA-5m9f-rphj-c435](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-5m9f-rphj-c435) | com.rabbitmq:amqp-client 5.30.0 | com.rabbitmq:amqp-client: 5.33.0 | P1 | MITM si se usa useSslProtocol() sin trust manager seguro/hostname. No hay llamada a esa sobrecarga en main; el overlay exige CA y hostname. TLS operativo aÃºn no acreditado. |
| [GHSA-5xwg-cfvj-gff5](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-5xwg-cfvj-gff5) | com.rabbitmq:amqp-client 5.30.0 | com.rabbitmq:amqp-client: 5.33.0 | P2 | AceptaciÃ³n de frames mayores que frame_max; depende de frames del peer AMQP. Presente en cliente runtime; no se reprodujo un peer malicioso. |
| [GHSA-68mj-5wr7-6fgg](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-68mj-5wr7-6fgg) | com.rabbitmq:amqp-client 5.30.0 | com.rabbitmq:amqp-client: 5.33.1 | P1 | OOM al decodificar long strings/bytes AMQP. Ruta de protocolo relevante al habilitar conexiones; no se probÃ³ explotaciÃ³n. Auth del dominio posterior no protege el parser de frames. |
| [GHSA-6g32-pxv4-2wfj](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-6g32-pxv4-2wfj) | com.rabbitmq:amqp-client 5.30.0 | com.rabbitmq:amqp-client: 5.33.0 | P3 | Carga de clases en JSON-RPC ProcedureDescription. No se encontrÃ³ uso de JSON-RPC/ProcedureDescription en main. CÃ³digo empaquetado; ruta explotable no identificada. |
| [GHSA-7822-rcf6-97fx](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-7822-rcf6-97fx) | com.rabbitmq:amqp-client 5.30.0 | com.rabbitmq:amqp-client: 5.36.0 | P2 | UTF-8 malformado afecta consumers JSON-RPC segÃºn el aviso. AplicaciÃ³n usa request/reply propio, sin JsonRpcClient/Server; no se demostrÃ³ alcance sobre listeners propios. |
| [GHSA-7hhh-6rmp-j9qf](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf) | tools.jackson.core:jackson-core 3.1.5; com.fasterxml.jackson.core:jackson-core 2.21.5 (Productos fase A; ausente en cuatro runtimes corregidos) | tools.jackson.core:jackson-core: 3.1.7, 3.2.3; com.fasterxml.jackson.core:jackson-core: 2.21.7, 2.22.3, 2.18.11 | P2 | DoS en parser DataInput. Parsers del envelope/respuesta usan byte[]/String; no se encontrÃ³ DataInput en main. Presencia confirmada; entrada por esa ruta no demostrada. |
| [GHSA-93j5-89vc-pph4](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-93j5-89vc-pph4) | com.rabbitmq:amqp-client 5.30.0 | com.rabbitmq:amqp-client: 5.33.1 | P1 | Stack overflow por tables/arrays AMQP anidados. Decoder runtime relevante si recibe frames adversarios; no se ejecutÃ³ PoC ni se acredita mitigaciÃ³n por validaciÃ³n JSON. |
| [GHSA-9xv2-5v5q-p794](https://tomcat.apache.org/security-11.html) | org.apache.tomcat.embed:tomcat-embed-core 11.0.24 | org.apache.tomcat.embed:tomcat-embed-core: 11.0.25, 10.1.58, 9.0.121 | P3 | Replay limitado con autenticaciÃ³n DIGEST de Tomcat. Sin configuraciÃ³n DIGEST en main; autorizaciÃ³n JWT/Spring Security. OSV CRITICAL, Apache Low; no bypass JWT demostrado. |
| [GHSA-cqgh-8p3p-mx4m](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-cqgh-8p3p-mx4m) | com.rabbitmq:amqp-client 5.30.0 | com.rabbitmq:amqp-client: 5.36.1 | P3 | Bucle en mapper JSON-RPC truncado. Sin uso de JSON-RPC/JSONReader del cliente RabbitMQ en main; no hay ruta explotable identificada. |
| [GHSA-cxp5-3px4-pw24](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24) | tools.jackson.core:jackson-databind 3.1.5; com.fasterxml.jackson.core:jackson-databind 2.21.5 (Productos fase A; ausente en cuatro runtimes corregidos) | tools.jackson.core:jackson-databind: 3.1.7, 3.2.3; com.fasterxml.jackson.core:jackson-databind: 2.21.7, 2.18.11, 2.22.3 | P2 | DoS por resoluciÃ³n cuadrÃ¡tica de referencias hacia delante. No se encontraron JsonIdentityInfo/object-id en DTO main; verificar otros deserializadores antes de aceptaciÃ³n. |
| [GHSA-gcx9-497g-6cp6](https://tomcat.apache.org/security-11.html) | org.apache.tomcat.embed:tomcat-embed-core 11.0.24 | org.apache.tomcat.embed:tomcat-embed-core: 11.0.25, 10.1.58, 9.0.121 | P2 | Bypass de security constraints declarativas en Tomcat. No se identificÃ³ configuraciÃ³n declarativa web.xml/Realm en main; Spring Security JWT aplica filtros independientes. OSV CRITICAL frente a Apache Important. |
| [GHSA-gx83-3vf8-gh7j](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-gx83-3vf8-gh7j) | tools.jackson.core:jackson-databind 3.1.5; com.fasterxml.jackson.core:jackson-databind 2.21.5 (Productos fase A; ausente en cuatro runtimes corregidos) | tools.jackson.core:jackson-databind: 3.1.6, 3.2.2; com.fasterxml.jackson.core:jackson-databind: 2.18.10, 2.21.6, 2.22.2 | P2 | Validador por defecto con JsonTypeInfo y base Comparable. No se encontrÃ³ JsonTypeInfo/Comparable ni typing global habilitado en main. El aviso aclara que typing con PTV explÃ­cito es otra ruta. |
| [GHSA-h3x4-894j-xpx5](https://tomcat.apache.org/security-11.html) | org.apache.tomcat.embed:tomcat-embed-core 11.0.24 | org.apache.tomcat.embed:tomcat-embed-core: 11.0.25, 10.1.58, 9.0.121 | P3 | Restricciones de mÃ©todo en autenticaciÃ³n FORM Tomcat. No se encontrÃ³ FORM Tomcat en main. OSV CRITICAL, Apache Low; no bypass de roles JWT demostrado. |
| [GHSA-h6w7-qmcm-q6xr](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-h6w7-qmcm-q6xr) | com.rabbitmq:amqp-client 5.30.0 | com.rabbitmq:amqp-client: 5.35.0 | P2 | ContraseÃ±a en excepciÃ³n de ConnectionFactoryConfigurator.load(). No se encontrÃ³ esa API en main; conexiones usan configurers Spring. Mantener saneamiento de logs; no se probaron secretos reales. |
| [GHSA-jh4v-gfqj-7rhx](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-jh4v-gfqj-7rhx) | com.rabbitmq:amqp-client 5.30.0 | com.rabbitmq:amqp-client: 5.34.0 | P1 | OOM con frame_max=0 y frame sobredimensionado de broker/MITM. Cliente runtime afectado; los lÃ­mites del envelope llegan despuÃ©s de allocation. No se demostrÃ³ broker malicioso ni TLS operativo. |
| [GHSA-p6pp-m3f8-5c89](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-p6pp-m3f8-5c89) | tools.jackson.core:jackson-core 3.1.5; com.fasterxml.jackson.core:jackson-core 2.21.5 (Productos fase A; ausente en cuatro runtimes corregidos) | tools.jackson.core:jackson-core: 3.1.7, 3.2.2; com.fasterxml.jackson.core:jackson-core: 2.18.11, 2.21.7, 2.22.3 | P2 | ReDoS de NumberInput.looksLikeValidNumber(). Hay parsing JSON HTTP/RabbitMQ, pero no se demostrÃ³ llamada alcanzable con entrada adversaria; requiere triage de coerciones, no basta strict fields. |
| [GHSA-q4xh-88c3-wmh7](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-q4xh-88c3-wmh7) | tools.jackson.core:jackson-databind 3.1.5; com.fasterxml.jackson.core:jackson-databind 2.21.5 (Productos fase A; ausente en cuatro runtimes corregidos) | tools.jackson.core:jackson-databind: 3.2.2, 3.1.6; com.fasterxml.jackson.core:jackson-databind: 2.18.10, 2.21.6, 2.22.2 | P2 | DoS por nÃºmeros no acotados al deserializar Duration/XMLGregorianCalendar. Duration se usa para configuraciÃ³n, no se identificÃ³ DTO pÃºblico de esos tipos; coerciÃ³n explotable no demostrada. |
| [GHSA-qx7j-jv8m-fppr](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-qx7j-jv8m-fppr) | com.rabbitmq:amqp-client 5.30.0 | com.rabbitmq:amqp-client: 5.31.0 | P2 | ExcepciÃ³n del assembler con body frame malformado. Ruta AMQP genÃ©rica presente; probar robustez del cliente frente a peer adversario antes de activar, sin asumir que ACK de negocio la mitiga. |
| [GHSA-wjgm-6hv5-3cvf](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wjgm-6hv5-3cvf) | tools.jackson.core:jackson-databind 3.1.5; com.fasterxml.jackson.core:jackson-databind 2.21.5 (Productos fase A; ausente en cuatro runtimes corregidos) | tools.jackson.core:jackson-databind: 3.1.6, 3.2.2; com.fasterxml.jackson.core:jackson-databind: 2.18.10, 2.21.6, 2.22.2 | P2 | ResoluciÃ³n de FileSystemProvider al deserializar Path sin allowlist de schemes. Path se usa para archivos locales, no se identificÃ³ DTO externo Path; ruta de deserializaciÃ³n no demostrada. |
| [GHSA-wv8q-qhhj-9h54](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54) | tools.jackson.core:jackson-databind 3.1.5; com.fasterxml.jackson.core:jackson-databind 2.21.5 (Productos fase A; ausente en cuatro runtimes corregidos) | tools.jackson.core:jackson-databind: 3.1.7, 3.2.3; com.fasterxml.jackson.core:jackson-databind: 2.18.11, 2.21.7, 2.22.3 | P2 | RetenciÃ³n de IDs de tipo polimÃ³rficos desconocidos. No se encontrÃ³ JsonTypeInfo/typing en main; presencia no prueba una ruta funcional vulnerable. |


No se demostró una vulnerabilidad crítica explotable en el runtime actual. Los tres avisos Tomcat marcados CRITICAL por OSV no prueban bypass de JWT: Apache califica DIGEST/FORM como Low y security constraints como Important, con precondiciones concretas que no se identifican en la autorización Spring Security configurada ([fuente primaria](https://tomcat.apache.org/security-11.html)). Se conservan ambas perspectivas sin convertir el agregado en prueba de explotación.

**Publicación/despliegue siguen bloqueados por el gate de seguridad pendiente**, particularmente riesgos de parser AMQP ante broker/MITM y validación TLS efectiva. Propuesta separada, sin implementarla: evaluar versión compatible del cliente AMQP que incluya correcciones pertinentes, Jackson y Tomcat según las ramas corregidas y precondiciones reales, con regresiones propias; no actualizar automáticamente Spring/Maven ni toda dependencia. No basta un límite del envelope para mitigar un allocation anterior al parser de negocio.

No hubo nuevo escaneo CVE completo de OS/JRE/otras dependencias ni aceptación formal de riesgo. Esta clasificación cubre los 20 avisos solicitados y no certifica imágenes libres de vulnerabilidades.

## Riesgos residuales y evidencia

RDS: cinco migraciones pendientes y separación runtime/migrador/ownership del preflight anterior; no reconsultados ni resueltos aquí. Flyway de arranque y TLS interhost/material en B siguen requiriendo gates/autorización independientes. El frontend sintético de fase A sigue sin ser candidato operativo. Core conserva versión SNAPSHOT local pero se compila desde fuente acreditada; no se garantiza reproducibilidad binaria por timestamps de JAR o metadata. No se publica ni retaggea ninguna imagen operativa.

Evidencia completa local: operational-evidence/EP2-BUILD-FIX-2026-10-10 fuera del repositorio, bajo Documents/ChatGPT/Pedidos360 Delivery: logs, git archive, scripts exactos, descriptores locales, JAR/capas, árboles Maven y XML Surefire. EP2-BUILD-FIX-tests.json conserva hashes SHA-256 de logs/metadata y fuente. Los hashes verifican integridad del material disponible, no firma de autoría; los logs completos no se publican automáticamente. El manifiesto público contiene metadata y conteos, sin credenciales operativas.

No se realizaron acciones AWS/ECR, instalaciones en hosts, despliegues, activación de EP2, cambio de broker, permisos/certificados, contratos, lógica multitenant, issues o merge. El PR permanece Draft para revisión independiente.
