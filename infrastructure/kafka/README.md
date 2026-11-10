# Kafka local de Pedidos360

Infraestructura exclusiva de desarrollo. No ejecuta despliegues AWS/Azure ni cambia contratos RabbitMQ.

## Versiones y límites

Java 21, Spring Boot 4.1.1, Spring Kafka 4.1.1 y cliente Kafka 4.2.1 gestionados por el BOM. Broker JVM 3.9.2 con ZooKeeper 3.8.4; protocolo de grupos `classic`. Un broker, replicación 1 y min ISR 1: **sin alta disponibilidad**. Los digest de las imágenes se fijan en Compose.

El proyecto Docker `p360-kafka-dev` tiene volúmenes propios. ZooKeeper no publica puertos. Kafka y UI publican solamente en loopback. Una red interna aísla coordinación y una red puente permite acceso desde Docker Desktop. PLAINTEXT se limita a este entorno local; no reutilizar esta configuración en cloud.

## Preparación

Desde la raíz del checkout, con Java 21, Maven y Docker disponibles:

```powershell
mvn -B -ntp -f backend/shared/p360-messaging-core/pom.xml -DskipTests install
mvn -B -ntp -f backend/shared/p360-kafka-core/pom.xml install
mvn -B -ntp -f backend/services/kafka-admin-service/pom.xml package
docker compose -f infrastructure/kafka/compose.yml up -d zookeeper kafka
docker compose -f infrastructure/kafka/compose.yml ps
```

Las pruebas DB usan PostgreSQL desechable de Testcontainers. `-DskipTests` prepara una dependencia; no acredita pruebas.

Los Dockerfiles de Restaurantes, Productos y Carrito requieren los contextos `messaging-core` y `kafka-core`. El procedimiento existente `infrastructure/aws/compose.yml` + `infrastructure/aws/compose.build.yml` ya proporciona ambos contextos; no requiere el overlay Kafka y conserva Kafka deshabilitado. El overlay `infrastructure/kafka/compose.build.yml` continúa disponible para otros stacks locales y para habilitar explícitamente el ARG informativo React. Los demás servicios solo reciben `messaging-core`. Construir no publica imágenes ni inicia un despliegue.

## Administración programática

```powershell
java -jar backend/services/kafka-admin-service/target/kafka-admin-service-0.0.1-SNAPSHOT.jar --p360.kafka.admin.command=plan
java -jar backend/services/kafka-admin-service/target/kafka-admin-service-0.0.1-SNAPSHOT.jar --p360.kafka.admin.command=apply
java -jar backend/services/kafka-admin-service/target/kafka-admin-service-0.0.1-SNAPSHOT.jar --p360.kafka.admin.command=describe
java -jar backend/services/kafka-admin-service/target/kafka-admin-service-0.0.1-SNAPSHOT.jar --p360.kafka.admin.command=groups
```

`describe` es el valor predeterminado; `list` enumera el catálogo autorizado; `plan` no escribe. `apply` valida el catálogo completo antes de efectuar cambios. Un fallo de conexión puede dejar cambios parciales: revisar y repetir el plan. Solo se permiten los dos topics aprobados y sus destinos `.dlt`. Tres particiones, replicación 1. Topics de negocio: `compact`, `retention.ms=-1`. DLT: `delete`, retención 604800000 ms. El catálogo operativo no expone eliminación, reset de offsets ni nombres arbitrarios. El broker de desarrollo mantiene deshabilitada la eliminación de topics. Los comandos `test-*` se limitan a recursos de una ejecución desechable, con confirmaciones e identidad de clúster/broker verificadas; solo se prueban en un fixture aislado. Consultar [operaciones y demostración reproducible](../../backend/services/kafka-admin-service/README.md).

Kafka UI es solo observación, no el administrador:

```powershell
docker compose -f infrastructure/kafka/compose.yml --profile ui up -d ui
```

Acceso local: http://localhost:18090. Sin socket Docker, carpetas de Codex ni credenciales montadas.

El administrador también se puede ejecutar como job Docker, después de compilar su JAR:

```powershell
docker compose -f infrastructure/kafka/compose.yml --profile tools run --build --rm admin --p360.kafka.admin.command=describe
```

Ese contenedor usa usuario 10001, raíz de solo lectura, temporales tmpfs y ninguna capacidad Linux adicional. No publica API HTTP ni puertos administrativos.

## Activación de aplicaciones

Kafka es optativo. Iniciar cada servicio participante con `--p360.kafka.enabled=true --p360.kafka.bootstrap-servers=localhost:19092`. Desde la red Docker usar `kafka:9092`. Conservar la configuración DB/JWT existente mediante sus mecanismos establecidos, sin copiar credenciales al runbook. Los flags y contratos RabbitMQ no cambian.

Compilar React con `VITE_KAFKA_INFORMATION_ENABLED=true` para mostrar observaciones. El ARG Docker es público y vale false por defecto. Un fallo de observación no bloquea catálogo o carrito. Las reglas existentes y validaciones síncronas siguen siendo autoritativas.

GET `/productos/restaurante/{id}/estado-restaurante` devuelve una observación. GET `/carrito/disponibilidad` devuelve observaciones solo de los productos del carrito del usuario autenticado. Campos: `id` decimal, `conocido`; si es conocido: `valor`, `version` decimal, `ocurridoEn`, `observadoEn`. Ausencia significa desconocido. La fecha expresa una observación, no disponibilidad garantizada para comprar.

## Entrega, orden y errores

Escrituras y snapshots bloquean la entidad origen. El cambio, su versión de flujo y el payload outbox son una transacción local. La versión no es la del carrito. Un advisory lock transaccional elige publicador entre réplicas; se respeta secuencia pendiente sin saltar un registro anterior. La confirmación del broker precede la marca de envío: una caída puede duplicar el mismo eventId. No se purgan filas automáticamente.

El consumidor persiste antes de confirmar offsets. Versión inferior: ignorar. Igual versión/estado: duplicado. Igual versión/estado distinto: conflicto terminal. La clave debe coincidir con el ID validado. Los fallos de acceso a datos conservan el offset pendiente con reintento cada cinco segundos y sondeo continuo. Errores desconocidos tienen tres intentos antes de suspender el procesamiento sin DLT; nunca se confirma antes de persistencia o confirmación terminal. La pausa afecta todas las particiones de la instancia y requiere observar lag/outbox.

La DLT contiene solo código y topic/partición/offset de origen. No conserva payload inválido, que podría contener secretos. Si falla su publicación, no se confirma el original. No existe replay automático. Con autorización, el operador puede inspeccionar el offset retenido, corregir el origen y publicar un snapshot; la compactación puede haber eliminado el mensaje original. No resetear grupos operativos.

## Snapshots y reconstrucción

Ejecutar una instancia del productor con su configuración normal y `--p360.kafka.snapshot-run=true`. Recorre IDs en lotes de 100, bloqueando cada fila con el protocolo del escritor. Emite estado completo y versión persistente. La instancia permanece publicando; detenerla después de vaciar su outbox pendiente. Un snapshot repetido a igual versión/estado es idempotente.

Un reinicio normal conserva tablas y offsets estables. Para una base reconstruida vacía, aplicar migraciones, iniciar el consumidor y emitir snapshots completos desde el origen. Los snapshots posteriores reconstruyen aunque los offsets del grupo ya hayan avanzado. No truncar una proyección viva ni resetear su grupo. Mientras se reconstruye, las observaciones desconocidas siguen siendo informativas. Verificar cobertura del origen, outbox drenado y lag cero antes de declarar recuperación. Esta versión recupera en sitio con guardas de versión; no automatiza intercambios de tablas ni reparaciones destructivas.

## Verificación

Con el broker aislado saludable y los topics provisionados:

```powershell
$env:P360_KAFKA_IT='true'
mvn -B -ntp -f backend/shared/p360-kafka-core/pom.xml install
mvn -B -ntp -f backend/services/restaurantes-service/pom.xml package
mvn -B -ntp -f backend/services/productos-service/pom.xml package
mvn -B -ntp -f backend/services/carrito-service/pom.xml package
mvn -B -ntp -f backend/bff/pom.xml package
```

Sin el flag, las clases específicas Kafka real se omiten explícitamente: no se presentan como aprobadas. Las regresiones existentes RabbitMQ/PostgreSQL siguen necesitando Docker. Ejecutar pruebas y build frontend desde `frontend`. Los JWT sintéticos firmados no acreditan aceptación de un tenant institucional ni un despliegue cloud.

`P360_KAFKA_OUTAGE_IT=true` habilita además la prueba que detiene y reinicia **solo** `p360-kafka-dev-kafka-1`, conservando su volumen. Usarla únicamente con este broker de desarrollo. `P360_KAFKA_PROCESSES_IT=true` habilita la prueba con los JAR ejecutables reales; activarla después de compilar los tres servicios y ejecutar `mvn -B -ntp -f backend/shared/p360-kafka-core/pom.xml -Dtest=ServiceProcessesIntegrationTests test`. Esta prueba inicia procesos propios con PostgreSQL desechable y los termina al finalizar. No activar ese flag antes de preparar los JAR.

Detener solo este proyecto con `docker compose -f infrastructure/kafka/compose.yml --profile ui stop`. Conservar volúmenes; no eliminar datos persistentes como limpieza rutinaria.


## Recuperación del consumidor

Un único trabajo en vuelo se ejecuta fuera del hilo propietario de KafkaConsumer. Se pausa la entrega de todas las particiones asignadas mientras ese registro está pendiente y se llama a poll cada 100 ms; las esperas de base de datos o DLT no bloquean ese sondeo. Esto conserva orden con un coste deliberado de throughput: una incidencia detiene la entrega en esa instancia, no los heartbeats. No se incrementó max.poll.interval.ms.

Errores de contrato, conflictos de versión e integridad de datos pasan al terminal. Errores desconocidos de procesamiento tienen tres intentos y después suspenden ese registro/instancia sin DLT ni commit; requieren reparación y reinicio seguro. Errores de acceso a datos (conectividad, recuperación o configuración/permisos) conservan el registro y se reintentan cada cinco segundos, sin convertir una indisponibilidad del sistema en pérdida de eventos válidos. Una conexión bloqueada mantiene un solo trabajo en vuelo. Requiere reparación operativa si la configuración es incorrecta.

Si la publicación terminal falla, se mantiene modo terminal: no se vuelve a aplicar el mensaje ni se confirma su offset. El commit ocurre únicamente después de persistir o recibir confirmación terminal. Un fallo de commit permite reaplicación idempotente. Durante rebalance se invalida la generación del trabajo: nunca se confirma un registro de la asignación anterior; su nuevo propietario lo recupera desde el offset durable. No hay resets de grupos.

## Política propuesta de retención del outbox (sin purga automática)

Conservar publicados al menos 30 días desde sent_at. No es autorización de borrado: antes de cualquier purga se necesita aprobación operativa, copia verificable/archivo de auditoría y ausencia de retenciones legales o investigaciones. El alcance aprobado para esta corrección solo documenta la política; no agrega jobs, DELETE ni cambios de esquema.

Nunca purgar filas con sent_at NULL, kafka_state, proyecciones ni registros bajo investigación. Conservar también publicados necesarios para una recuperación pendiente. Antes de autorizar un lote: verificar snapshots recientes y su cobertura, outbox drenado, offsets/lag y recuperación comprobada; archivar payload, event_id, entidad, versión y fechas con hashes. La retención compactada de Kafka no sustituye ese archivo ni garantiza conservar cada evento histórico. Mantener el último estado/versionado por entidad independientemente de la edad del outbox.

Una futura tarea autorizada debe usar lotes acotados, predicado sent_at NOT NULL y corte por sent_at, revalidación transaccional, métricas, trazabilidad y copia restaurable. Nunca usar TRUNCATE ni borrar volúmenes. Hasta esa aprobación no se elimina ningún registro; vigilar crecimiento y espacio disponible.


## Fallos transaccionales y cierre verificable

La clasificación es explícita: contratos inválidos, conflicto de versión e integridad de datos son terminales; DataAccessException no asociadas a integridad y CannotCreateTransactionException, TransactionSystemException, TransactionTimedOutException y UnexpectedRollbackException se consideran infraestructura. No se convierte una imposibilidad de abrir/finalizar transacción en un mensaje inválido. Un resultado transaccional incierto se recupera por reaplicación idempotente. Excepciones desconocidas no se consideran transitorias indefinidamente ni justifican por sí solas descartar un evento: tras tres intentos se pausa hasta intervención, conservando offset y poll. Un rebalance/reinicio puede provocar una nueva entrega.

La parada normal cambia running, señala requestStop y llama a wakeup (única operación KafkaConsumer realizada fuera de su hilo propietario). No interrumpe normalmente el hilo que cierra al consumidor. El propietario deja de aceptar trabajo, espera el ejecutor y cierra KafkaConsumer sin confirmar trabajo pendiente. Interrupciones previas se limpian temporalmente para la limpieza y se restauran después. Los registros no confirmados se reentregan, incluso si el trabajo anterior llegó a persistir: las guardas de versión hacen segura su reaplicación.

Límites configurables por propiedades Spring (también mediante su forma habitual en variables de entorno):

| Propiedad | Predeterminado | Función |
|---|---:|---|
| p360.kafka.shutdown-timeout-ms | 30000 | Espera de workers del runtime |
| p360.kafka.task-shutdown-timeout-ms | 5000 | Espera ordenada del trabajo asíncrono |
| p360.kafka.consumer-close-timeout-ms | 5000 | Cierre KafkaConsumer en su propietario |
| p360.kafka.producer-close-timeout-ms | 3000 | Cierre KafkaProducer, después de la espera de workers |

Valores entre 1 y 120000 ms; el plazo runtime debe cubrir al menos tarea + consumidor. El cierre del productor tiene un presupuesto separado y puede sumarse al plazo runtime. Estas opciones no se transmiten como configuración del cliente Kafka.

Si una tarea no termina dentro del plazo, se intenta interrumpir, se registra ERROR de cierre incompleto y no se confirma su offset. shutdownNow no garantiza terminar JDBC. isShutdownComplete() comprueba ejecutores, trabajo y cierre de clientes; no equivale a isRunning(). Un runtime incompleto rechaza start para evitar solapar generaciones. No se promete terminar un hilo que ignore interrupciones: se necesita recuperar el recurso/liberar la llamada y verificar nuevamente el estado; un cierre de proceso forzado requiere autorización operativa. No se borran offsets ni datos para reparar este caso.


### Consentimiento de administración desechable

Las actualizaciones de configuración exigen `UPDATE:<topic>:<config>:<value>` incluso cuando parezcan no destructivas. Reset y eliminación conservan `RESET:<topic>:<config>` y `DELETE:<topic>`. Las mutaciones test exigen argumentos explícitos de operación, conexión, identidad de ejecución, recurso y consentimiento en la invocación actual; no reutilizan autorizaciones heredadas de configuración o entorno. Sin argumentos, una operación mutante heredada se rechaza; sin operación configurada se conserva describe. Apply también requiere command explícito.

Los controles de ID de clúster, rack y endpoint son barreras contra accidentes, no una prueba de aislamiento físico o lógico. Las demostraciones destructivas quedan restringidas al fixture documentado en backend/services/kafka-admin-service/README.md, con inspección de montajes y sin volúmenes operativos. No habilitar delete.topic.enable en el broker de desarrollo.
