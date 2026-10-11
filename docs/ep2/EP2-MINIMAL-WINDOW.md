# Ventana mínima EP2 — React, aplicaciones en A; demo en B

Preparación local, **no autorización ni despliegue**. Aplicaciones construidas
desde develop `f6a9540e5db9890a7716184b917c718d35a21de6`, sin Angular/#112.
No usar `edge/lab.mjs` para ejecutar: contiene IDs históricos de otro laboratorio.
No cargar `compose.host-a.yml`, `compose.host-b.yml` ni `compose.broker-host-a.yml`.

## Estado y parada previa

- A `i-07d0adf18d62d564b`: nueve contenedores healthy, 2 vCPU, 8.196 GB físicos,
  5.504 GB disponibles, raíz 24.828 GB libres, EBS broker montado separadamente.
- B `i-0cdcbd1090a2b8be2`: 2 vCPU, 8.196 GB, 7.607 GB disponibles,
  27.180 GB libres; Docker presente, sin contenedores. Ambas SSM Online.
- A: presupuesto Compose 7×512 MiB + React 128 MiB + broker 768 MiB =
  4.375 GiB; no construir en A. Capacidad de memoria suficiente para ensayo,
  rendimiento EP2 todavía no demostrado. CPU histórica: 106 muestras de 300 s,
  promedio no ponderado 61.96%, máximo 100%; ventana solicitada 24 h con lagunas.
  Créditos disponibles: último valor 0; Unlimited. No atribuir causa ni asegurar
  rendimiento sostenido a partir del snapshot de baja CPU.
- B: demo 2×768 MiB y 2×1 CPU; cabe. Dos réplicas requieren ambos nodos para
  quorum; mismo host y dos nodos **no dan HA**. Gestión/AMQPS solo loopback,
  Erlang solo bridge Docker interna; ningún SG nuevo necesario para esta ruta.
- RDS available, PostgreSQL 17.11; backup de un día y LatestRestorableTime
  observado. Eso **no acredita una restauración ensayada**.
- Mantener TLS vigente: antes de la ventana verificar CA/SAN/fecha real de todos
  los enlaces y legibilidad de archivos por UID 10001. No renovar por rutina.
- No generar confianza por IP ni usar HMAC heredado. Los archivos ES256 y el
  truststore EP2 deben existir y pasar el preflight; los nombres de archivos
  antiguos no acreditan configuración actual.
- Lectura fresca de A: los cinco consumers de consultas/Carrito **no tienen**
  write sobre `p360.dlx`, a diferencia del código de provisión actual. Es necesario
  para handoff diagnóstico confirmado. No añadir `p360.pedidos.dlx` al consumer
  de confirmaciones por analogía: esa ruta usa rechazo/DLX del broker.
- Truststore EP2 y archivos de ambos emisores ES256 ausentes en las dos raíces
  previstas consultadas. Revisar staging aprobado antes de generar material nuevo;
  no se inspeccionó contenido privado ni se acredita custodia/distribución completa.
- Antes de RDS: recuperación probada, roles aprobados y todos los escritores
  antiguos detenidos. Antes de checkout RabbitMQ: retirada verificable de pestañas
  y bundles HTTP anteriores; no existe barrera global de DELETE autorizada.

## Autorización agrupada solicitada (no ejecutada)

| Bloque | Operaciones y efectos | Parada / recuperación |
|---|---|---|
| 1. Publicación | Publicar 8 imágenes, variante React checkout y 3 herramientas migrador en los 8 repositorios ECR existentes, tags distintos e inmutables. Generar pins por digest desde ECR. Transferir solo release/config revisados. | HEAD/config públicos distintos, librerías incorrectas o tag ya usado con otro contenido: parar. Publicar no cambia aplicaciones; conservar tags/digests previos. |
| 2. RDS y material | Retirar tráfico en API Manager bajo procedimiento aprobado, detener ocho aplicaciones sin detener broker. Backup privado consistente y ensayo de restauración aislado. Aplicar cinco migraciones, roles runtime no propietarios y material ES256 separado por emisor; ajustar únicamente permisos mínimos broker ya documentados. | Sin backup recuperable, ownership/grants no acreditados, checksum distinto o histórico ambiguo: parar. No backfill/reconciliación automática. Mantener esquema aplicado; no `clean`, `repair` ni bajar versión Flyway. |
| 3. Aplicaciones A | Reemplazar aplicaciones en el mismo proyecto `pedidos360-aws`, HTTP inicial; activar por etapas consultas, Pago→Pedido y finalmente Pedido→Carrito. Broker y EBS intactos. | Health/TLS/auth/tenant/latencia/settlement incorrectos: mantener mantenimiento, detener etapa nueva. Recuperar con imágenes **nuevas tenant-aware en HTTP**, no binarios antiguos que eludan guards. |
| 4. Demo B y aceptación | Crear material **sintético nuevo** para demo, dos volúmenes propios y bridges propios en B; instalar solo herramientas Python de ensayo si faltan. Arrancar dos nodos, provisionar 3+3 colas y direct/topic, ejecutar pruebas reales y capturar evidencia mediante SSM. | Falta de disco, memoria, TLS, membresía o mensajes persistidos: parar demo, conservar volúmenes. Sin reset/force_boot/purge. No tocar broker A. |

Escrituras RDS, publicaciones funcionales y mensajes ACKados no se deshacen
revirtiendo imágenes. La retirada temporal de tráfico y permisos/material privado
requieren aprobación de este bloque completo; no usar una supuesta autorización
anterior para saltarlos. No hay cambios IAM/SG/Lambda/DNS solicitados para conectar
microservicios entre hosts: permanecen en A. API Manager debe poder servir
mantenimiento real antes de permitir escrituras; el mecanismo concreto necesita
validación del operador, no está automatizado en esta preparación.

## 1. Publicar desde estación de build (solo tras aprobación)

Los builds locales usan configuración pública real de API Manager y Entra,
HTTP predeterminado. `builds.json` registra IDs locales; **no son digests ECR**.
Comandos Bash, no ejecutar desde los hosts de aplicaciones:

```sh
set -eu
PROFILE=pedidos360-lab
REGION=us-east-1
REGISTRY=694507973514.dkr.ecr.us-east-1.amazonaws.com
SHA=f6a9540e5db9890a7716184b917c718d35a21de6
TAG="$SHA-ep2-react-http"
export DOCKER_CONFIG="$(mktemp -d)" # directorio privado temporal, no logs de credenciales
aws ecr get-login-password --profile "$PROFILE" --region "$REGION" |
  docker login --username AWS --password-stdin "$REGISTRY"
for service in frontend bff usuarios restaurantes productos carrito pedidos pagos; do
  docker tag "p360-local/$service:ep2-react-f6a9540e5db9" "$REGISTRY/pedidos360-$service:$TAG"
  docker push "$REGISTRY/pedidos360-$service:$TAG"
  aws ecr describe-images --repository-name "pedidos360-$service" \
    --image-ids "imageTag=$TAG" --profile "$PROFILE" --region "$REGION" \
    --query 'imageDetails[0].imageDigest' --output text
done
docker tag p360-local/frontend:ep2-react-f6a9540e5db9-checkout-rabbit \
  "$REGISTRY/pedidos360-frontend:$SHA-ep2-react-rabbit"
docker push "$REGISTRY/pedidos360-frontend:$SHA-ep2-react-rabbit"
for service in carrito pedidos pagos; do
  docker tag "p360-local/$service-migrator-ready:ep2-f6a9540e5db9" \
    "$REGISTRY/pedidos360-$service:$SHA-ep2-migrator"
  docker push "$REGISTRY/pedidos360-$service:$SHA-ep2-migrator"
done
docker logout "$REGISTRY"
```

Conservar mapping de los nueve tags/digests y crear `compose.image-pins.yml`
con `services.<servicio>.image: <repositorio>@sha256:<digest real>`.
La variante React RabbitMQ va solo en `FRONTEND_RABBIT_IMAGE`; no usarla como
frontend inicial. Remover únicamente el directorio temporal Docker identificado.

## 2. Datos — orden exacto y acceso

Fuente: `MULTIHOST-PREFLIGHT-2026-10-10/flyway-repo-comparison.json`; volver a
comparar con RDS en la ventana. Orden:
Carrito V2; Pedidos V2 y V3; Pagos V3 y V4 (migraciones internas en orden Flyway).
Usuarios/Restaurantes/Productos no tienen migraciones nuevas según ese inventario.

Separar migrador/propietario de runtime; el rol antiguo propietario se reserva
para migración y queda fuera de las aplicaciones nuevas. Propuesta de runtime:
`pedidos360_{carrito,pedidos,pagos}_runtime`, LOGIN sin SUPERUSER/CREATEDB/CREATEROLE,
sin pertenencia al propietario, sin CREATE/TRIGGER/TRUNCATE, sin reconciliación.
Grant CONNECT solo DB propia, USAGE schema/secuencias; SELECT/INSERT/UPDATE/DELETE
solo tablas de dominio y outbox propias. Recepción Carrito: SELECT/INSERT/UPDATE,
sin DELETE; auditoría reconciliación: ningún permiso. Función SECURITY DEFINER
`reconcile_tenant` debe seguir propiedad del migrador, con PUBLIC/runtime sin
EXECUTE y `search_path` fijo. No ampliar EXECUTE mediante grants globales.

Config Flyway por servicio: archivo privado restringido, nunca contraseña en CLI.
Debe contener URL `jdbc:postgresql://<DNS RDS>:5432/pedidos360_<servicio>?sslmode=verify-full&sslrootcert=/run/private/rds-ca.pem`,
usuario migrador y password, schemas/defaultSchema propios, cleanDisabled=true,
locations=`filesystem:/build/src/main/resources/db/migration`, target fijo 2 para
Carrito, 3 Pedidos y 4 Pagos. Prevalidate admite únicamente las cinco migraciones
pendientes acreditadas, sin ignorar checksum, missing o future; postvalidate no
admite ninguna excepción. La validación manual ordinaria rechaza pending, por lo
que no sirve sin ese tratamiento explícito **antes** de aplicar las aprobadas.
Se usa el builder
del Dockerfile existente (JDK 21 por digest, mismo commit), no un frontend nuevo:

```sh
# Estación local: repetir para carrito, pedidos, pagos y publicar tools solo si se autoriza.
docker buildx build --platform linux/amd64 --load --target build \
  --build-context messaging-core=backend/shared/p360-messaging-core \
  -t p360-local/pedidos-migrator:ep2-f6a9540e5db9 backend/services/pedidos-service
docker build --build-arg BUILDER_IMAGE=p360-local/pedidos-migrator:ep2-f6a9540e5db9 \
  -t p360-local/pedidos-migrator-ready:ep2-f6a9540e5db9 infrastructure/aws/migrator
# En A, migrador trasladado/acreditado por digest, archivos privados ya verificados:
docker run --rm --cpus 1 --memory 512m --network pedidos360-aws_egress \
  --mount type=bind,src="$MIGRATION_PRIVATE_DIR",dst=/run/private,readonly \
  "$PEDIDOS_MIGRATOR_IMAGE" sh -ec '
    sh mvnw -o -B -ntp org.flywaydb:flyway-maven-plugin:12.4.0:validate -Dflyway.configFiles=/run/private/pedidos.conf "-Dflyway.ignoreMigrationPatterns=*:pending"
    sh mvnw -B -ntp org.flywaydb:flyway-maven-plugin:12.4.0:migrate -Dflyway.configFiles=/run/private/pedidos.conf
    sh mvnw -o -B -ntp org.flywaydb:flyway-maven-plugin:12.4.0:validate -Dflyway.configFiles=/run/private/pedidos.conf -Dflyway.ignoreMigrationPatterns='
```

Las tres herramientas se construyeron localmente y pasaron ensayo real offline
con PostgreSQL desechable (12 operaciones bootstrap/prevalidate/migrate/postvalidate).
Faltan permisos reales, archivos TLS/config privados y recuperación acreditada.
No se construye ni compila en A. Alternativa
aprobable: operador ejecuta mismo Flyway 12.4.0 ya instalado en VPC; no reemplazar
por SQL manual que omita historial/checksums. No migrar usando rol runtime.

Antes de migrar: respaldar las tres DB (formato custom `pg_dump`, service files
privados, sin password en argumento), incluyendo datos/outbox, inventariar roles
y ownership, restaurar y validar en PostgreSQL aislado y documentar lectura y
constraints. El ensayo sintético Testcontainers **no sustituye** esa recuperación.
Un backup o RDS restaurable anunciado por API no basta para avanzar.
No reconciliar UNKNOWN ni crear intenciones para históricos. No revocar grants
operativos antes de guardar/validar el plan completo y detener binarios antiguos.

Después de guardar permisos actuales y **solo tras aprobación**, cambios mínimos
en el broker existente (no ejecutar provisión general ni rotar credenciales):

```sh
BROKER=pedidos360-ep2-broker-rabbitmq-1
for domain in usuarios restaurantes productos pagos; do
  docker exec "$BROKER" rabbitmqctl set_permissions -p pedidos360 \
    "p360-$domain-consumer" '^$' '^(p360\.retry|amq\.default|p360\.dlx)$' \
    "^(p360\.$domain\.consultas\.q)$"
done
docker exec "$BROKER" rabbitmqctl set_permissions -p pedidos360 p360-carrito-consumer \
  '^$' '^(p360\.retry|p360\.dlx)$' '^(p360\.carrito\.vaciado\.q)$'
docker exec "$BROKER" rabbitmqctl list_permissions -p pedidos360
```

Rollback de permisos: restaurar exactamente las regex guardadas después de
detener consumers afectados; nunca quitar DLX a un consumer activo con originales
pendientes. La corrección `demo.`/`p360.demo.` de Admin sigue fuera de esta
activación mínima de ocho aplicaciones; RabbitAdmin DELETE permanece 403.

## 3. A — comandos de corte y rollback

`release` y `.env.window` son archivos revisados previamente transferidos, sin
secretos en Git. Variables: EC2_PRIVATE_IP=172.31.81.78, TLS y secretos existentes,
EP2_PRIVATE_DIR verificado, tres roles runtime, registry/tag anteriores, IDs
públicos Entra/worker verificados y RDS real. Mantener proyecto y redes actuales.

```sh
cd "$RELEASE_DIR/infrastructure/aws"
dc() { docker compose -p pedidos360-aws --env-file "$WINDOW_ENV" \
  -f compose.yml -f compose.ep2-prepared.yml -f compose.edge.yml \
  -f compose.ep2-runtime-roles.yml -f "$IMAGE_PINS" "$@"; }
dc config --quiet
# Después de mantenimiento y drenaje; nunca detener RabbitMQ en este corte.
dc stop -t 60 frontend bff pagos pedidos carrito usuarios restaurantes productos
# Después de migraciones/roles/preflight TLS e identidad.
dc up -d --no-deps --wait --wait-timeout 180 usuarios restaurantes productos
dc up -d --no-deps --wait --wait-timeout 180 carrito pedidos pagos
dc up -d --no-deps --wait --wait-timeout 180 bff frontend
dc ps
```

No `down`, `--remove-orphans`, `--force-recreate` global, `docker compose build`
en A, ni un segundo nombre de proyecto para las mismas aplicaciones.
El broker es un proyecto independiente conectado a `pedidos360-aws_services`;
verificar ID/red/alias `p360-rabbitmq` antes/después, sin recrear red/broker.

Consultas: añadir `-f compose.ep2-query-candidate.yml` antes de argumentos finales
a la función dc; detener/reemplazar únicamente bff/usuarios/restaurantes/productos/pagos.
Prueba de Usuarios→Pagos y catálogo, luego habilitar tráfico aprobado. Rollback:
retirar ese overlay y recrear mismas aplicaciones tenant-aware en HTTP, sin
fallback automático. No reiniciar BFF con correlaciones pendientes: drenarlas
hasta deadline antes de detener y aceptar fallo controlado de las restantes.

Pago→Pedido: añadir overlay `compose.ep2-pago-candidate.yml`, detener/reemplazar
pedidos y pagos con `--no-deps`; HTTP worker queda false. Contabilizar históricos
UNKNOWN/RECONCILED y outbox pendiente: no reactivar ni reprocesar por cambio de modo.
Rollback requiere parar ambos coordinadores, esperar leases y publishers inciertos,
contabilizar mensajes y outbox; solo después HTTP con nuevas imágenes. Si no es
posible atribuir una intención/mensaje, permanecer detenido: no publicar alternativa.

Pedido→Carrito: último overlay `compose.ep2-carrito-candidate.yml`, previa retirada
verificable de clientes/pestañas HTTP; reemplazar pedidos/carrito/frontend. No
afirmar que un 201 vació el carrito. Rollback conserva outbox/recepciones: cerrar
checkout, esperar/drain mensajes y leases, verificar ausencia de comando antiguo
pendiente antes de volver a frontend HTTP. Sin purge ni eliminación de outbox.
Si clientes antiguos no pueden retirarse, **no activar este último corte**.

## 4. B — dos nodos en el mismo host, separados de A

Transferir únicamente directorio `cluster-demo` de develop, sin `.local`,
credenciales, caches ni evidencias previas. Nueva raíz privada propuesta
`/opt/pedidos360-demo-73`; nunca copiar volumen/cookie operativos.
No SG de Management, Erlang, ni AMQP público; acceso exclusivamente mediante SSM.

```sh
# En B tras aprobación; init crea solamente secretos/certificados demo nuevos.
cd /opt/pedidos360-demo-73
umask 077
python3 -m venv .local/venv
.local/venv/bin/python -m pip install -r requirements.txt
.local/venv/bin/python demo.py init
.local/venv/bin/python demo.py preflight
.local/venv/bin/python demo.py start
.local/venv/bin/python demo.py provision
.local/venv/bin/python demo.py verify
DEMO73_REAL_TESTS=1 .local/venv/bin/python -m unittest test_cluster -v
.local/venv/bin/python demo.py resources
```

SSM port-forward para dashboard HTTPS loopback 15783/15784; validar CA y hostname
demo, no desactivar TLS del cliente. `ssm start-session --document-name
AWS-StartPortForwardingSession --target i-0cdcbd1090a2b8be2 --parameters
'{"portNumber":["15783"],"localPortNumber":["15783"]}'` con perfil/región explícitos.
Si falta python3-venv/pip, aprobación de instalación aislada en B; no improvisar
APT con TLS desactivado. Dos nodos no HA; Erlang no TLS en bridge de un host;
AMQP 1.0 permanece disponible y GHSA operativo no se declara solucionado.

## Aceptación antes de declarar funcionamiento

1. Digest/revision correctos, nueve contenedores actuales/imagen anterior registrados,
   ocho nuevas aplicaciones sanas sin duplicados, broker/EBS/identidad sin cambios.
2. Login **Entra real**, API Manager/JWT: 401 sin token, 403 scope/rol incorrecto;
   CLIENTE/ADMIN, tenant externo/UNKNOWN 404; no JWS ni tokens en salida pública.
3. Catálogo vacío/no vacío, Usuarios→Pagos con identidad, correlación/deadline;
   checkout/pagos autorizados, idempotencia y ausencia de POST/DELETE duplicados.
4. Flujo real confirm→ACK, nack/return/incertidumbre, retry único y DLQ; pruebas
   de error controlado identificadas como inyecciones, no particiones reales.
5. Snapshot carrito/version, duplicado un efecto, cambio posterior conservado;
   Pago+outbox atómico, fencing/lease y recuperación después de interrupción.
6. Demo membresía dos nodos, tres queues/tres DLQ, direct/topic, mensajes persistidos
   recuperados después de stop/start; quorum perdido al caer un nodo, no falsa HA.
7. Ensayo con carga académica: MemAvailable >=1 GiB, ningún OOM/alarm/disco, p95
   bajo presupuesto funcional y sin errores de timeout. Si CPU se mantiene >90%
   durante 10 min o hay timeouts, parar avance; CPU actual no demuestra capacidad.
8. Capturas frontend/backend/dashboard, timestamps, hashes y guion desde entorno
   realmente desplegado; guardar ensayos locales separados de evidencia cloud.

Este paquete no certifica EP2 funcionando. No cerrar issues ni hacer merge.

## Ejecución local acreditada

- Ocho imágenes linux/amd64 non-root desde archivo Git exacto de develop; variante
  React checkout RabbitMQ adicional. IDs completos en `EP2-MINIMAL-LOCAL-tests.json`.
  Config pública coincide con bundle actualmente servido y JWT authorizer del API.
  BuildKit cache permitido, sin `.m2` del desarrollador ni Angular en contexto.
- React ejecutó 253 tests y oxlint durante cada build HTTP/RabbitMQ; ambos builds
  pasaron. Warning existente de chunk >500 kB, sin cambio visual/funcional.
- Core 268, Carrito 27, Pedidos 14, Pagos 78: 387 casos en reportes finales.
  Una ejecución inicial de Pedidos tuvo timeout en prueba de outage real; su
  clase completa repitió seis casos correctos sin cambios. Conservar ambos logs;
  no declarar que el primer intento pasó ni atribuir causa a carga sin evidencia.
- Cinco migraciones ensayadas en PostgreSQL real: histórico/constraints en suites
  existentes y runner desde versiones previas en fixture vacío. Eso no acredita
  una restauración ni privilegios de RDS. El primer ensayo del runner evidenció
  schema ausente y el segundo rechazo de pending; se corrigió preparación del
  banco y secuencia pre/postvalidate, sin alterar migraciones ni tests existentes.
- BFFQueriesEndToEndTests levanta cinco aplicaciones, PostgreSQL y RabbitMQ reales
  con autenticación de fixture. No es Entra live, navegador autenticado ni cloud.
- Clúster: ensayo aislado de restart PASS y suite real 11/11 PASS, incluidos SIGKILL,
  pérdida de quorum, NACK/DLQ y restart con mensajes; cinco checks estáticos PASS.
  Fallo antiguo no reproducido: no se cambió configuración/arranque. Demo detenido
  al finalizar, mismos contenedores y volúmenes, sin purge/reset.
- Compose: ocho checks PASS, incluidas tres regresiones nuevas; `git diff --check`.
- #112 CLOSED sin merge, ramas eliminadas, nueve imágenes `ep2-academic` eliminadas
  previamente. Eliminación de Angular/evidencias/caches de #112 bloqueada por
  política automática; permanecen ajenas al Git archive/build. No eludir el bloqueo.
- Rama local de preparación `codex/ep2-minimal-runtime-preparation`; develop intacto.
  No ECR push, migración real, cambio SG/IAM/flags, restart operativo ni despliegue.
