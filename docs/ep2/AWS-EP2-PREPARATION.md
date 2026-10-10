# #71 — Preparación local segura EP2

Base: `d9d97594719ba77abb307c4c27f962d2c2da2149`. Rama:
`codex/71-local-ep2-preparation`. Preparación inactiva para revisión; no despliegue,
activación, corte #70 ni clúster #73. El Compose operativo original no cambia.

## Configuración preparada

`infrastructure/aws/compose.ep2-prepared.yml` se combina con `compose.yml`.
Fija literalmente relay DISABLED, IdentityProof deshabilitada, declaración de
topología deshabilitada, cuatro consultas BFF HTTP y ambas coordinaciones HTTP.
Los valores de activación del entorno no sustituyen estas decisiones. La futura
activación exige autorización y otro artefacto revisado; este overlay no es un
interruptor de corte. No inicia un broker ni publica puertos AMQP/Management.

AMQPS usa `p360-rabbitmq:5671`, vhost `pedidos360`, validación de CA y hostname.
Las variables canónicas de Spring se comprueban mediante Binder real, incluyendo
`spring.rabbitmq`, `pagos.consultas.rabbitmq` y `pedidos360.messaging.carrito`.
Pagos conserva `p360-pagos-publisher` y una conexión separada
`p360-pagos-consumer`; Pedidos separa confirmación y publicación a Carrito.

`compose.ep2-build.yml` añade solamente el empaquetado RabbitAdmin y fija el
argumento frontend de checkout a HTTP. La imagen Admin reutiliza el wrapper Maven
existente de Usuarios mediante un contexto de build nombrado. Su Dockerfile no
cambia código Java, usa Temurin 21 fijado por digest y usuario `10001:10001`.
Su construcción local no demuestra un arranque AWS.

RabbitAdmin queda en el perfil opcional `ep2-admin-prepared`, red interna,
sin puertos publicados, HTTPS 8090 y cuenta exclusiva sandbox. Conserva JWT Entra,
scope y ADMIN. No ofrece un nuevo endpoint; no se inventa health Actuator.
Su readiness futura debe verificar API real y respuestas 401/403/200.
DELETE de queue sigue devolviendo 403 y permanece diferido en #101.

## Archivos y variables

Además de las variables existentes del Compose AWS se requiere
`EP2_PRIVATE_DIR`, directorio privado absoluto fuera de Git. Sin valores reales:

```text
EP2_PRIVATE_DIR/
  secrets/<12 contraseñas independientes según accounts()>
  secrets/ERLANG_COOKIE
  identity/PEDIDOS360_ACTOR_PRIVATE_JWK
  identity/PEDIDOS360_ACTOR_PUBLIC_JWKS
  identity/PEDIDOS360_ACTOR_KEY_ID
  identity/USUARIOS_IDENTITY_PROOF_PRIVATE_JWK
  identity/USUARIOS_IDENTITY_PROOF_PUBLIC_JWKS
  identity/USUARIOS_IDENTITY_PROOF_KEY_ID
  tls/ca.pem
  tls/server.pem
  tls/server-key.pem
  tls/apps-truststore.p12
AWS_TLS_DIR/rabbit-admin.p12  # solo perfil opcional
```

Las contraseñas mantienen los nombres de archivo documentados en el README del
broker. Configtree monta cada password en su propiedad concreta; nunca se colocan
JWK privadas en variables de entorno. Solo BFF recibe privada ActorContext; solo
Usuarios recibe privada IdentityProof. Los consumidores reciben conjuntos públicos.
No existe fallback HMAC ni dependencia de `PEDIDOS360_ACTOR_SECRET`.

`apps-truststore.p12` contiene **todas las CA ya necesarias** para HTTPS interno,
Entra y RDS/JDK, además de la CA del broker. No reemplazarlo con un almacén que
contenga únicamente la CA RabbitMQ. `changeit` protege un almacén de certificados
públicos, no material de firma. El almacén y el certificado HTTPS Admin están
pendientes de preparación operativa autorizada; no se generaron aquí.

Los bind mounts no crean rutas ausentes. Antes del despliegue hay que comprobar
propiedad/legibilidad desde UID 10001 de aplicaciones y UID real del broker:
Compose local no remapea propietario de secretos basados en archivo. Privadas
JWK 0400/0600 y directorio privado restringido; no confiar en ACL Unix simulada
en Windows. El preflight de identidad en Linux exige ausencia de bits group/other.
Asignar propiedad y acceso bajo autorización operativa, sin hacer públicas las claves.

El host de preflight necesita Node.js 22+, Python 3 y las dependencias previamente
documentadas. `identity_check.mjs` es offline: JSON sin duplicados, ES256/P-256,
UUID kid canónico, coordenadas y escalar canónicos, públicos sin `d`, conjuntos
acotados, sin claves duplicadas, revocadas ni URLs de resolución. Comprueba
correspondencia privada/pública firmando un desafío local que no es un actor ni
una prueba. Rechaza reutilización de kid o material entre emisores. Errores genéricos,
sin impresión de claves. La configuración incompleta bloquea las sondas de arranque.
Esto valida configuración; no sustituye validación de claims ni seguridad del emisor.

## Permisos mínimos

| Cuenta | Cambio | Sin ampliación |
|---|---|---|
| Pagos consumer | Añadir write `p360.dlx`; conservar `p360.retry`, `amq.default` | configure denegado, read solo cola propia |
| Carrito consumer | Añadir write `p360.dlx`; conservar `p360.retry` | Sin write default, configure denegado |
| Usuarios/Restaurantes/Productos | Sin cambio efectivo | Conservan retry/default/DLX |
| Admin sandbox | `^p360\.demo\.[A-Za-z0-9][A-Za-z0-9._-]{0,99}$` | Sin acceso al vhost de negocio |
| Bootstrap sandbox | Mantener `demo.` y añadir `p360.demo.` | Solo mantenimiento, nunca identidad de aplicación |

El prefijo antiguo se conserva exclusivamente para sondas históricas
`demo.ep2.persistence*`. Las pruebas de aplicación usan `p360.demo.platform*`.
Topología intacta: **21 queues / 7 exchanges / 20 bindings / 21 policies**.
Las regex de write sobre exchanges direct no aíslan routing keys: siguen siendo
indispensables los validadores existentes de cada publisher. No se unen cuentas.
Estos cambios se aplicaron únicamente al broker local desechable; permisos AWS pendientes.

## Evidencia y reproducción

Resultados, comandos y hashes: [AWS-EP2-PREPARATION-tests.json](AWS-EP2-PREPARATION-tests.json).
Los logs completos permanecen en el directorio ignorado `infrastructure/aws/rabbitmq/evidence`.
Los hashes de fuentes se calculan sobre UTF-8 con saltos LF, como blobs Git.
Los hashes de logs corresponden a bytes originales de la ejecución local.
No se versionan credenciales, fixtures privadas ni archivos académicos.

| Verificación ejecutada | Resultado | Tipo de evidencia |
|---|---|---|
| RabbitMQ TLS | 19 correctas | Broker desechable, probes AMQP reales |
| RabbitAdmin Maven | 61 correctas | Broker Testcontainers, JWT fixture |
| Identidad ES256 | 18 correctas, 1 omitida | Crypto local; symlink omitido en Windows |
| Cuentas exactas | 3 correctas | Unitarias offline |
| Guardas de seguridad | 11 correctas | Unitarias, fallos inyectados |
| Overlay + Compose heredado | 11 correctas | Configuración, sin arranque |
| Preparadores AWS existentes | 15 correctas | Offline; incluye 6 casos Compose ya contados |
| Binder Spring | 10 conexiones verificadas | Sin red ni contexto de aplicación |
| Imagen RabbitAdmin | Build correcto | Construcción local, sin despliegue |

No sumar las suites como casos distintos: seis pruebas Compose se repitieron.
La primera ejecución TLS falló por una expectativa antigua de denegación DLX;
se corrigió al permiso autorizado, manteniendo el rechazo de configure. Otra
ejecución se detuvo antes de casos porque el broker nuevo aún no estaba
provisionado. Ambas quedan identificadas en el manifiesto. El resultado final
de 19 correctas corresponde a un broker nuevo provisionado y quedó detenido.

Pruebas offline reproducibles desde la raíz:

```powershell
python -B -m unittest discover -s infrastructure/rabbitmq -p test_accounts.py -v
python -B -m unittest discover -s infrastructure/aws/rabbitmq/scripts -p test_guards.py -v
node --test infrastructure/aws/rabbitmq/scripts/identity_check.test.mjs
node --test infrastructure/aws/ep2-prepared.test.mjs infrastructure/aws/compose.test.mjs
node --test infrastructure/aws/deployment.test.mjs infrastructure/aws/compose.test.mjs infrastructure/aws/transfer-worker.test.mjs
```

Para Binder usar el classpath del pom RabbitAdmin con `dependency:build-classpath`,
y ejecutar Java 21 source launcher sobre `scripts/PreparedBindingProbe.java`, con
raíz del repositorio como argumento. Es una sonda offline sin contexto de aplicación.

La prueba TLS exige Docker, pika 1.3.2 y fixture LOCAL_TEST **nuevo y vacío**:
`local_fixture.py` con OpenSSL, `tools.py start`, `tools.py provision`, y
`EP2_ENV_FILE` apuntando al `.env` ignorado más `EP2_PLATFORM_TESTS=1`; ejecutar
unittest discovery `test_tls_platform.py`. Nunca ejecutar estas pruebas en EC2 ni
sobre datos compartidos. Genera material efímero exclusivamente bajo `test-local`.
El `.env.example` permanece PREPARED_ONLY y bloquea operaciones.
La suite verifica TTL originales, reinicio/persistencia, permisos, nack/DLQ,
CA no confiable y SAN incorrecto. Son probes AMQP/manuales de infraestructura,
no listeners de negocio ni un ensayo de todos los servicios desplegados.

La suite Maven completa de RabbitAdmin usa Testcontainers RabbitMQ real y JWT
firmados por fixture RSA, **no Entra live**. No se reejecutaron suites funcionales
de microservicios o frontend porque sus fuentes no cambian.

## Pendientes operativos y rollback

Antes de AWS: autorización separada; filesystem/EBS y recursos reales; certificados
y ACL verificados en Linux; claves operativas separadas y rotación; Entra/red;
roles PostgreSQL runtime/migrador y propiedad SECURITY DEFINER del PR #94;
no coexistencia insegura con binarios anteriores; históricos sin reconciliación
automática; retiro de clientes antiguos y ensayo de corte/rollback #70; E2E #72.
La conectividad del overlay completo, confianza combinada y autenticación real
no están acreditadas por Compose config, Binder o pruebas locales de un broker.

Conservar el riesgo temporal aceptado: no existe terminalidad durable global,
se requiere sincronización de relojes y persisten correlaciones BFF locales.
No declarar RabbitMQ 4.1.8 inmune a GHSA-6588-rqcr-59pw por ausencia de fallo en
estas sondas AMQP 0-9-1; la revisión operativa del protocolo/version queda pendiente.
Este PR no cambia autenticación de comandos ni claims de negocio.

Antes de activar cualquier flujo hay que guardar modos, inventario, permisos,
versiones e indicadores de pendientes, y validar HTTP. Ante fallo, detener el
perfil nuevo conservando datos y colas. No borrar, purgar ni republicar históricos.
Tras un futuro corte, nunca restaurar HTTP mientras exista coordinación RabbitMQ
en vuelo: detener entrada, drenar/reconciliar bajo autorización y verificar ausencia
de doble coordinador. Este PR mantiene HTTP y no ejecuta ese procedimiento.

Ni AWS, flags desplegados, permisos operativos, issues ni contratos fueron modificados.
