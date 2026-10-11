# PR #107 — transición controlada, pendiente de autorización operativa

Este procedimiento reemplaza la secuencia inicial genérica. No se ejecutó en
AWS. No cambia código de negocio, certificados, flags ni permisos. Su ensayo
local usa únicamente procesos sintéticos sin datos, puertos, secrets o mounts.

## Comportamiento comprobado de Compose

Se ensayó con Docker Compose 5.1.3 en un proyecto aleatorio
`p360-transition-test-*`, imagen python:3.12-alpine ya disponible y --pull never.
El proyecto no es pedidos360-aws ni tiene conexiones a AWS/RDS/RabbitMQ.

| Operación local | Resultado real |
|---|---|
| up de subconjunto con el mismo proyecto y nueva configuración | El servicio incluido cambió de ID: recreación, no conservación del contenedor anterior |
| Servicios omitidos del nuevo modelo | Conservan ID y continúan RUNNING si no se detienen expresamente |
| stop explícito de servicios anteriores con el modelo antiguo | Conservan ID en estado detenido; otro up del subconjunto no los arrancó |
| Red services con mismo nombre y definición | Conservó NetworkID; edge adicional se conectó al servicio recreado |
| Eliminación de depends_on remoto y up --no-deps | No arrancó los servicios anteriores detenidos |
| Arranque del worker sintético B tras detener A | Solo B activo; A conservado detenido |
| Rollback: stop B, up del worker anterior A | Solo A activo, mismo ID del worker preservado |
| Sentinel sintético en la red compartida | Conservó ID y siguió activo; no es una prueba del broker operativo |

La [documentación de up](https://docs.docker.com/reference/cli/docker/compose/up/)
explica la recreación ante cambios de configuración/imagen y la eliminación
opcional de huérfanos. [down](https://docs.docker.com/reference/cli/docker/compose/down/)
elimina contenedores/redes definidos. No usar down en A durante este corte:
puede afectar las redes conectadas al broker y destruye contenedores de rollback.

**Prohibido durante transición/rollback operativo:** --remove-orphans,
COMPOSE_REMOVE_ORPHANS=true, down, rm, prune, --renew-anon-volumes,
--always-recreate-deps y up indiscriminado del monohost. No usar -p distinto
para A: puede duplicar procesos, no trasladarlos. El proyecto real y las labels
deben verificarse; si no son pedidos360-aws, DETENER y revisar el plan.
No confundir COMPOSE_IGNORE_ORPHANS con detención o seguridad.

Los contenedores antiguos de Restaurantes, Productos, Carrito y Pagos se
conservan detenidos. Usuarios/Pedidos/BFF/frontend pueden ser recreados:
su rollback exige las imágenes por digest, modelo anterior completo,
variables públicas, paths y material autorizados; no se promete conservar
su ID. No borrar imágenes anteriores ni modificar definiciones de redes
existentes con endpoints activos. Diferencias de driver/internal/IPAM/labels
requieren revisión; mismo nombre no garantiza compatibilidad.

## Variables y comandos del procedimiento (NO ejecutados)

Antes de la ventana, el operador identifica por host:

- A_OLD: modelo monohost efectivo archivado, con sus overlays en el mismo
  orden. No asumir que compose.yml del repo es el modelo realmente desplegado.
- A_ENV_OLD: archivo público y referencias privadas anteriores protegidos.
- A_NEW: compose.host-a.yml del commit aprobado; A_ENV_NEW: configuración
  validada con imagen candidata inmutable, TLS y endpoints privados.
- B_NEW: compose.host-b.yml del mismo commit; B_ENV_NEW: configuración B.
- Digests e IDs anteriores, proyecto real, redes y restart policy de cada
  contenedor. No exportar Config.Env ni contenido de secretos a la evidencia.

Definir COLD, CA y CB como arrays de argumentos (o funciones de shell), nunca
concatenando valores no validados. Representan respectivamente:

```text
docker compose [--env-file A_ENV_OLD] [todos los -f originales de A]
docker compose --env-file A_ENV_NEW -f compose.host-a.yml
docker compose --env-file B_ENV_NEW -f compose.host-b.yml
```

Cada comando siguiente se ejecutará manualmente solo en el host indicado y
con aprobación de ventana. Fijar COMPOSE_REMOVE_ORPHANS=false; comprobar que
las herramientas/automatizaciones no agregan flags prohibidas. No ejecutar
deployment.mjs up. CA/CB excluyen siempre compose.broker-host-a.yml y perfiles
RabbitAdmin. No modificar el modo HTTP ni las flags EP2.

## Secuencia por etapa, puertas de avance y recuperación

En cada stop conservar ID y comprobar State.Running=false mediante docker
inspect del ID registrado (solo campos ID/State/Image/labels, no Config.Env).
Enumerar docker ps por labels com.docker.compose.project y service: debe haber
cero copias RUNNING en el host retirado y exactamente una en el host destino.
Antes de actualizar A, comparar docker network inspect (ID, driver, internal,
IPAM y endpoints) con el modelo archivado, sin conectar/desconectar el broker.
Verificar sesiones/escritores RDS y solicitudes en vuelo con métricas de salud
autorizadas y metadatos pg_stat_activity por usuario/aplicación/origen, nunca
query/payload. Si no se puede acreditar quiescencia o los permisos de lectura
son insuficientes, DETENER: el stop por sí solo no prueba un commit pendiente.
Para aceptar salud HTTPS, usar la CA y nombre previstos con verificación
normal; no curl -k. Registrar timestamps, IDs/digests, salud, recuentos y
errores sanitizados por etapa; no tokens ni secretos.

| Etapa | Acción futura precisa | Condición de avance / recuperación |
|---|---|---|
| 0 — preparación | Autorizar SG/TLS/Docker B por separado. Completar matriz de imágenes/RDS abajo. Archivar A_OLD y digests. Validar sintaxis y salud previa. Acreditar backup/restore. Sin aplicaciones B contra RDS todavía. | Si falta esquema, runtime limitado, red/TLS, imágenes o rollback seguro: NO abrir ventana |
| 1 — quiescencia | Retirar tráfico por mecanismo previamente autorizado y verificar cero solicitudes en vuelo. A: COLD stop -t 90 bff. A: COLD stop -t 90 pagos. Verificar esos IDs detenidos y ausencia de reconciliador/confirmaciones/escritores en vuelo. | Pagos A permanece detenido durante todas las etapas. No basta healthcheck o interrupción del HTTP. Si el shutdown falla: mantener tráfico cerrado, investigar; no arrancar B |
| 2 — catálogo | A: COLD stop -t 90 restaurantes productos. Verificar ambos detenidos. B: CB up -d --no-deps --pull never restaurantes productos. | Salud de ambos B, conexión RDS verify-full y HTTPS por nombres restaurantes/productos desde A. Si falla: stop ambos B, verificar, reanudar ambos A con COLD up -d --no-deps --pull never restaurantes productos; tráfico y Pagos siguen detenidos |
| 3 — dependencias A | A: CA up -d --no-deps --pull never usuarios. Verificar salud local. Luego CA up -d --no-deps --pull never pedidos. | Usuarios/Pedidos sanos y Pedidos→Productos B TLS válido; schema/security ya aprobados. Si falla: mantener Pagos/BFF detenidos, reconstruir servicios A con imágenes/modelo de rollback compatible; no borrar redes/broker |
| 4 — carrito | A: COLD stop -t 90 carrito; verificar detenido. B: CB up -d --no-deps --pull never carrito. | Salud, Carrito→Productos local y RDS; no escrituras concurrentes de Carrito A. Si falla: stop Carrito B, verificar, COLD up -d --no-deps --pull never carrito en A; conservar tráfico cerrado mientras rutas no coincidan |
| 5 — Pagos | Reconfirmar Pagos A detenido y que ningún supervisor/reinicio lo reactivó. B: CB up -d --no-deps --pull never pagos. | Salud, RDS, Pagos→Usuarios/Pedidos A TLS y worker autenticado, sin duplicación. Si falla: stop Pagos B y verificar ausencia de procesos antes de cualquier arranque A. No alternar imágenes incompatibles con el esquema |
| 6 — aceptación | A: CA up -d --no-deps --pull never bff; verificar las seis dependencias, JWT/roles/tenant. Luego CA up -d --no-deps --pull never frontend. Validar cuatro consultas HTTP, checkout y Pago→Pedido autorizado sin efectos repetidos. | Solo reabrir tráfico con rutas completas B y ninguna copia antigua activa. Catálogo/carrito/pagos deben estar todos en B: CA ya apunta los cuatro allí. Una recuperación parcial no permite abrir tráfico |

--pull never evita reemplazos imprevistos durante la ventana: las imágenes
exactas deben estar previamente disponibles. --no-deps exige verificar las
dependencias manualmente; no autoriza saltarse healthchecks/TLS.
La falta de SG autorizados mantiene el entorno **NO DESPLEGABLE**.

### Recuperación por etapas y rollback completo

Hasta etapa 1 se puede cancelar y recuperar A sin trasladar servicios. Después
de cambiar rutas en A, mantener tráfico cerrado hasta completar todos los B o
volver íntegramente a A. Ante incertidumbre de escrituras/commit, detener la
etapa y registrar metadatos; no repetir POST ni vaciar carrito para probar.

Rollback completo, con autorización de la misma ventana: stop BFF A; stop -t90
Pagos B primero, luego Carrito y catálogo B. Verificar todos detenidos.
Restaurar el modelo/digests anteriores **compatibles con seguridad/esquema**.
Arrancar catálogo A, Usuarios A, Pedidos A, Carrito A, Pagos A, BFF/frontend A,
en ese orden y verificando cada dependencia. Workers y reconciliación Pagos
solo se reanudan tras confirmar B detenido. Los contenedores omitidos podrán
mantener su ID; los recreados no. Nunca hacer downgrade a autorización sin
tenant para recuperar servicio. No restaurar DB/EBS ni ejecutar migraciones
inversas, borrar receipts/outbox o republicar históricos automáticamente.

Stop manual conserva contenedores pero no es un cerrojo durable: comprobar
restart policies y supervisores externos; no reiniciar A durante la transición.
Si A se reinicia, antes de continuar comprobar todas las copias antiguas y
mantener tráfico cerrado. El ensayo no acredita comportamiento de systemd,
restart policies reales, DNS AWS, NACL ni shutdown de las aplicaciones Java.

## Matriz de imágenes y RDS

No se consultó el Docker operativo ni RDS desde esta corrección. Las imágenes
antiguas realmente desplegadas (tag, image ID y RepoDigest) y las candidatas
publicadas en ECR están **NO COMPROBADAS**. Las imágenes locales i5-stack o tags
de fixtures no son evidencia de AWS. IMAGE_TAG es un SHA aprobado e inmutable,
no una prueba de que la imagen exista o incorpore ese source; registrar digest
y provenance de build antes de avanzar. No copiar credenciales para completar
el inventario. Esta matriz especifica lo que el operador debe acreditar.

| Componente / destino | Imagen antigua desplegada | Candidata según Compose | Base/schema y migraciones versionadas |
|---|---|---|---|
| Frontend A | NO COMPROBADA | IMAGE_REGISTRY/pedidos360-frontend:IMAGE_TAG | Sin Flyway; build HTTP original |
| BFF A | NO COMPROBADA | IMAGE_REGISTRY/pedidos360-bff:IMAGE_TAG | Sin DB propia ni Flyway |
| Usuarios A | NO COMPROBADA | IMAGE_REGISTRY/pedidos360-usuarios:IMAGE_TAG | pedidos360_usuarios / usuarios, V1 crear_usuarios |
| Restaurantes B | NO COMPROBADA | IMAGE_REGISTRY/pedidos360-restaurantes:IMAGE_TAG | pedidos360_restaurantes / restaurantes, V1 crear_tabla_restaurantes, V2 insertar_restaurantes_iniciales |
| Productos B | NO COMPROBADA | IMAGE_REGISTRY/pedidos360-productos:IMAGE_TAG | pedidos360_productos / productos, V1 crear_tabla_productos, V2 insertar_productos_iniciales, V3 completar_datos_demostracion |
| Pedidos A | NO COMPROBADA | IMAGE_REGISTRY/pedidos360-pedidos:IMAGE_TAG | pedidos360_pedidos / pedidos, V1 crear_pedidos, V2 aislamiento_tenant, V3 carrito_vaciado_outbox |
| Carrito B | NO COMPROBADA | IMAGE_REGISTRY/pedidos360-carrito:IMAGE_TAG | pedidos360_carrito / carrito, V1 crear_carritos, V2 vaciado_por_pedido |
| Pagos B | NO COMPROBADA | IMAGE_REGISTRY/pedidos360-pagos:IMAGE_TAG | pedidos360_pagos / pagos, V1 crear_pagos, V2 unicidad_y_confirmacion, V3 confirmacion_outbox, V4 aislamiento_tenant |
| RabbitMQ A | Identidad operativa no releída; NO tocar | Variante futura fijada por digest en Compose | EBS/nodo/cookie actuales; fuera del traslado HTTP |

Fuentes SQL: backend/services/<servicio>-service/src/main/resources/db/migration.
No ejecutar seeds/backfill/migraciones de esta tabla por haberlos inventariado.
Los datos históricos UNKNOWN permanecen retenidos/inaccesibles conforme al
contrato; no reconciliarlos ni convertir outbox/coordinación durante el corte.

Los seis servicios tienen Hibernate ddl-auto validate y Flyway configurado
con su schema/clean-disabled. Compose solo suministra datasource credentials:
no demuestra un rol Flyway migrador independiente. **Bloqueante de ventana:**
comparar flyway_schema_history/checksums/success con el candidato; si hay SQL
pendiente, se requiere autorización separada y ejecución controlada por rol
migrador antes del arranque runtime. No resolverlo dando DDL al runtime.
Demostrar después que startup/validación Flyway con rol runtime limitado no
requiere mutaciones imprevistas, incluyendo acceso adecuado a su history.

| Rol | Privilegios necesarios a acreditar, no aplicados |
|---|---|
| Runtime por servicio | CONNECT a su DB, USAGE a su schema, DML/secuencias estrictamente necesarias; sin ownership, DDL ni acceso cruzado |
| Migrador por servicio | DDL de tablas/índices/constraints/triggers/funciones en su schema y Flyway history; credencial no distribuida al contenedor runtime |
| Dueño funciones Pedidos/Pagos | reconcile_tenant SECURITY DEFINER con search_path fijo y EXECUTE revocado a PUBLIC según V2/V4; runtime no dueño ni autorizado a reconciliar |
| Operador reconciliación | Aprobación específica/evidencia/auditoría; no ejecutar desde esta transición |

Carrito V2 agrega recepción durable, guard_vaciado_receipt y trigger; Pedidos V3
agrega outbox/guard_carrito_outbox; Pagos V4 conserva triggers de origen,
pedido asociado y contenido outbox. Verificar que roles runtime no puedan
anular esos controles. Un schema compatible no demuestra que las grants,
owners o funciones en RDS sean correctas. Rollback queda bloqueado si la
imagen anterior no preserva tenant/procedencia con el schema actual.

## Interhost y broker — alcance de las pruebas

Las regresiones comprueban que extra_hosts nunca oculta nombres locales;
locales comparten services y remotos conservan URLs/nombres DNS HTTPS.
Se comparan todos los bindings con la matriz SG del informe principal y
se conservan redes services/egress existentes. La bridge local no conecta
hosts: el trayecto propuesto requiere puertos privados publicados, SG,
rutas/NACL y TLS verificado. No se acredita conectividad por una IP, un
healthcheck local o sintaxis Compose. JWT/IdentityProof/roles permanecen sin
cambios; las pruebas estáticas no sustituyen pruebas de autorización reales.

Dependencia futura adicional: CarritoSnapshotClient de Pedidos usa
pedidos.carrito-url (CARRITO_SERVICE_URL), cuyo default actual es localhost
HTTP y que no está suministrado por el Compose canónico AWS. La ruta de
snapshot para coordinación RabbitMQ necesitará https://carrito:8084 antes
del corte #70. No se activa esa ruta ni se corrige configuración de activación
desde esta revisión del traslado HTTP. La matriz SG A→B 8084 ya contempla
el puerto, pero eso no sustituye configurar el endpoint futuro y probarlo.

**compose.broker-host-a.yml es exclusivamente FUTURO.** No incluirlo en CA,
CB, comandos de corte HTTP ni rollback. No ejecutar up/down de su proyecto,
conectar bridge nueva al broker ni publicar 5671 durante este traslado.
El broker operativo, mounts, cookie, guard, certificados, imagen y topología
21/7/20/21 quedan intactos. Su propuesta necesitará autorización y revisión
propias antes de cualquier aplicación futura.

## Reproducción y límites

```text
python -B -m unittest discover -s infrastructure/aws -p test_multihost.py -v
python -B -m unittest discover -s infrastructure/aws -p test_multihost_transition.py -v
```

La segunda suite requiere Docker local y python:3.12-alpine ya cacheada.
No instala ni descarga software. Crea/limpia únicamente fixtures aleatorias.
No es una prueba de workers reales, integridad SQL, CA/SAN, SG o AWS.
El ensayo de transición tiene un caso integrado con múltiples comprobaciones;
no inflar cada assert como una prueba independiente. Resultados/hashes y
provenance de evidencia se registran en AWS-MULTIHOST-PREPARATION-tests.json.
