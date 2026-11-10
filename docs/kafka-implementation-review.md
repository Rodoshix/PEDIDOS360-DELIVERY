# Revisión de implementación Kafka — 2026-11-09

Estado: hallazgos corregidos localmente; pendiente de una nueva revisión independiente. No se declara APTO PARA COMMIT. Ningún archivo staged, ningún commit, push o PR creado.

## Git y alcance

- Rama: `feature/kafka-integration`.
- Base, HEAD y referencia main: `6ef8d5c30c0f6f2b985c79fce2a624c9f3baefc5`.
- Remoto: `Rodoshix/PEDIDOS360-DELIVERY`.
- 19 archivos existentes modificados y 47 nuevos propuestos, incluyendo este informe y el manifiesto.
- No se modificaron Jira, GitHub, infraestructura AWS/Azure, Repartidores, Seguimiento ni configuraciones globales de Codex.
- Las siete migraciones históricas de los servicios participantes conservan sus SHA-256 del manifiesto original. Solo se agregan migraciones nuevas.
- Graphify se consultó como índice del commit base. No se regeneró ni alteró; los archivos nuevos aún no están indexados. Coste de nueva extracción Graphify: 0 tokens.

## Funcionalidades

1. Infraestructura local Kafka JVM 3.9.2/ZooKeeper 3.8.4 y Kafbat UI 1.3.0, imágenes por digest, puertos loopback y datos independientes.
2. Administrador Spring Boot 4.1.1 sin API HTTP: describe, plan, apply y groups. Consulta configuraciones efectivas, miembros, estados, offsets y lag. Catálogo limitado, preflight antes de aplicar, sin eliminación o reset de offsets. Docker non-root, raíz readonly y temporales tmpfs.
3. p360-kafka-core: DTO v1 tipados, esquemas JSON, validación estricta de clave, UUID, tamaño, tipos, versiones de 64 bits y claves JSON duplicadas.
4. Restaurantes produce estados; Productos proyecta de forma independiente. Crear, PUT y desactivar cubiertos. Nunca cambia Producto.disponible por el estado del restaurante.
5. Productos produce disponibilidad desde creación, PUT y PATCH; Carrito proyecta y expone advertencias del carrito propio. No modifica líneas, precios, totales o versión, ni sustituye validaciones síncronas.
6. Outbox transaccional y versiones persistentes. Bloqueos por entidad; publicador único entre réplicas mediante advisory lock; confirmación del broker antes de marcar enviado.
7. Consumidores classic, grupos estables, aplicación durable antes de confirmar offsets, guardas de versión, reintentos/backoff y terminales con metadatos sanitizados.
8. Snapshots explícitos por lotes y procedimiento no destructivo para reconstruir desde snapshots posteriores. No hay truncado, reset ni swap automático de tablas.
9. Nuevos GET informativos BFF y React opcional, bajo JWT existente. Respuestas desconocidas no implican disponibilidad; fallos informativos no bloquean recorridos previos.

## Evidencia ejecutada

Los totales Java suman el resultado más reciente de cada clase XML Surefire; algunas clases se repitieron de forma focalizada después de cambios. No representan una única invocación monolítica.

| Módulo | Pruebas | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|
| shared/p360-kafka-core | 26 | 0 | 0 | 0 |
| shared/p360-messaging-core | 286 | 0 | 0 | 0 |
| services/kafka-admin-service | 4 | 0 | 0 | 0 |
| services/restaurantes-service | 37 | 0 | 0 | 0 |
| services/productos-service | 47 | 0 | 0 | 0 |
| services/carrito-service | 106 | 0 | 0 | 0 |
| bff | 206 | 0 | 0 | 0 |

Total Java: **712**, todas aprobadas. Frontend: **258 pruebas**, todas aprobadas; lint sin avisos y build Vite con observaciones Kafka habilitadas aprobado. Vite conserva el aviso de tamaño de chunk >500 kB.

Pruebas específicas: contratos inválidos/duplicados/overflow; rollback outbox; versiones concurrentes; snapshot recuperando proyección vacía; ambos topics con Kafka/ZooKeeper reales; duplicados y versiones antiguas; DLT sin payload sensible; reinicio de consumidor; caída/reinicio del broker con outbox conservado; puntos reales de escritura; JWT firmado localmente, scope, propietario y rechazo de otro tenant; JSON del carrito idéntico antes/después de proyectar.

La prueba `ServiceProcessesIntegrationTests` inició los tres JAR reales y PostgreSQL desechable. Comprobó POST/PUT de restaurante hasta el GET informativo de Productos, POST/PATCH de producto hasta proyección de Carrito y rechazo HTTP sin JWT. Los casos JWT firmados y BFF se verificaron en pruebas específicas, no con una cuenta institucional ni con un despliegue cloud.

Builds Docker aprobados: Restaurantes, Productos y Carrito con los dos contextos compartidos. Administrador construido y ejecutado por Compose en modo restringido. Topics creados mediante el administrador; repetición apply y plan devolvieron UNCHANGED. Configuración efectiva: tres particiones, RF1, min ISR1, compact/-1 en negocio, delete/604800000 en DLT. Consulta groups y lag ejecutada. Salud HTTP de UI: 200.

Evidencias reproducibles en los `target/surefire-reports/` de cada módulo. Logs locales ignorados: `target/verification.log`, `target/process-verification.log`, `target/store-verification.log`, `target/docker-verification.log` y `frontend/verification.log`. No incluir target, logs ni node_modules en el commit.

## Límites y riesgos para revisión

- Entorno local de un broker sin alta disponibilidad; PLAINTEXT no es configuración cloud. Sin validación de EC2, Azure ni identidad institucional.
- Kafka y observaciones React están desactivados por defecto. Activar coordinadamente todos los escritores del origen antes de emitir snapshots de carga inicial; escritores que permanezcan desactivados no registran sus transiciones.
- El Compose anterior de construcción incorpora kafka-core solo en los tres participantes. Se verifican tanto ese procedimiento como su combinación con el overlay Kafka; ninguna de esas construcciones inicia un despliegue.
- El publicador conserva el orden con head-of-line blocking: un registro pendiente retrasa los siguientes. Los datos outbox publicados se conservan; no hay purga automática.
- DLT guarda metadatos, no payload inválido. Reprocesamiento/inspección posterior es manual y autorizado; un original compactado podría no estar disponible.
- Reconstrucción en sitio mediante snapshots y guardas de versión; no se automatiza staging/swap ni reparación destructiva.
- `npm audit --omit=dev` detectó un aviso alto en follow-redirects heredado del lockfile existente, que no se modificó. No se afirmó explotación en Pedidos360; los tests de prohibición de redirects del cliente existente pasan. Requiere revisión separada de dependencias antes de publicación.
- Se usaron APIs administrativas compatibles con broker 3.9; el compilador avisa de APIs de grupos deprecadas en cliente 4.2. No se usaron protocolos de grupos nuevos incompatibles.

## Corrección de hallazgos previos al commit

1. **Construcción compatible:** infrastructure/aws/compose.build.yml agrega únicamente el contexto kafka-core a Restaurantes, Productos y Carrito. No cambia imágenes base, contratos RabbitMQ ni flags de ejecución. Una regresión de Compose comprueba contexto, orden de instalación y Kafka opt-in; se ejecutaron los builds de los tres servicios con el Compose anterior y con el overlay nuevo. También se construyó el administrador local.
2. **Interpretación de contratos:** EventCodec.state usa el tipo de evento validado, no la presencia de un campo arbitrario. Productos usa exclusivamente disponible; Restaurantes usa exclusivamente estado. Campos extra contradictorios, null y objetos no alteran el significado. Campos obligatorios ausentes/tipos inválidos siguen rechazados; no se cambian esquemas JSON v1.
3. **Recuperación con sondeo continuo:** KafkaConsumerLoop mantiene un trabajo asíncrono en vuelo y pausa todas las particiones asignadas mientras hace poll cada 100 ms. No aumenta max.poll.interval.ms. Errores de acceso a datos conservan el evento con backoff de cinco segundos; errores de integridad/contrato/conflicto son terminales; errores desconocidos tienen tres intentos y luego suspensión sin DLT ni commit. Un fallo terminal conserva modo terminal sin reaplicar ni confirmar. Solo se confirma tras persistencia o envío terminal exitoso. Un rebalance invalida el trabajo anterior y reposiciona la partición si sigue asignada para evitar saltos; reaplicación idempotente ante fallo de commit.
4. **Outbox:** runbook propone conservar publicados al menos 30 días y exige aprobación, archivo verificable y recuperación acreditada antes de cualquier purga por lotes. Nunca pendientes, retenciones de auditoría ni estado de versiones. No se implementó ni ejecutó purga automática o manual.

Pruebas nuevas: 1 caso de contratos con múltiples variantes; 6 casos unitarios del bucle (fallo prolongado con reloj simulado superior a cinco minutos, I/O bloqueado, error permanente/reintentos terminales, rebalance con reposicionamiento, fallo de commit y contrato inválido); 1 integración Kafka adicional. Esta última reduce max.poll.interval.ms a 1000 ms solo en el cliente de prueba, inyecta bloqueo de I/O y fallos de acceso a datos, mantiene bloqueos de cuatro segundos y comprueba miembro/generación estable, offsets pendientes, recuperación durable y fallo/recuperación terminal. No se presenta como una caída física de PostgreSQL de cinco minutos. Las pruebas existentes cubren el envío DLT real, broker detenido/reiniciado sin borrar volúmenes, ambos recorridos y procesos JAR reales.

Se verificaron 13 pruebas de modelos Compose, incluyendo la preparación RabbitMQ existente. Los resultados de aquella corrección eran la unión del XML más reciente por clase; no una única ejecución de todas las suites. Las suites de servicios/BFF/RabbitMQ se repitieron; posteriormente se recompilaron los tres JAR con el core definitivo y se repitió el core con sus integraciones, procesos reales y caída de broker habilitados. Frontend: 258 pruebas, lint y build con flag informativo true.

Evidencias de esta corrección (ignoradas, no incluidas en el commit):
- backend/shared/p360-kafka-core/target/correction-final-rebalance.log: core definitivo, todas sus pruebas e instalación.
- backend/shared/p360-kafka-core/target/correction-old-final-build.log y correction-new-final-build.log: builds Compose anterior/nuevo.
- backend/shared/p360-kafka-core/target/correction-compose-final.log: 13 pruebas Compose; correction-admin-build.log: imagen administrador; correction-admin-plan.log: cuatro topics UNCHANGED sin escrituras.
- backend/shared/p360-kafka-core/target/correction-rabbit-core.log: 286 regresiones RabbitMQ aprobadas; su paso install falló por un JAR bloqueado por procesos concurrentes Windows. correction-rabbit-install.log acredita instalación posterior exitosa sin repetir innecesariamente esas pruebas.
- backend/services/restaurantes-service/target/correction-verification-retry.log: suite completa tras una detección Docker fallida en el primer intento. La repetición usa DOCKER_HOST solo en el proceso, sin configuración global.
- backend/services/{productos,carrito,kafka-admin}-service/target/correction-verification.log y backend/bff/target/correction-verification.log: suites completas. Productos/Carrito también correction-kafka.log con pruebas Kafka habilitadas. Los tres participantes tienen correction-final-package.log de recompilación final.
- frontend/correction-tests.log, correction-lint.log y correction-build.log: verificación frontend. No se cambió package-lock ni se corrigió follow-redirects en este alcance.

Limitaciones: la pausa global privilegia orden y memoria acotada sobre throughput; un registro pendiente frena la entrega de toda esa instancia. El timeout de una operación JDBC bloqueada sigue dependiendo del driver/pool, aunque no bloquea poll. Errores de infraestructura/configuración requieren reparación y no se descartan como si fueran mensajes inválidos. DLT mantiene metadatos sin payload y no garantiza replay de originales compactados. Siguen pendientes seguridad/HA cloud y la revisión independiente. Al finalizar no quedaron contenedores activos; se detuvieron Kafka/ZooKeeper conservando sus volúmenes y Testcontainers retiró sus fixtures desechables. main/HEAD siguen en la base y el índice está vacío. Ningún resultado autoriza commit, publicación o despliegue.

## Corrección final del consumidor — posterior a la segunda auditoría

Estado: corrección y verificaciones locales terminadas; pendiente de nueva auditoría independiente. No constituye aprobación de commit.

### Clasificación de fallos

KafkaConsumerLoop clasifica explícitamente CannotCreateTransactionException, TransactionSystemException, TransactionTimedOutException y UnexpectedRollbackException como infraestructura, junto a DataAccessException no asociadas a integridad. Mantiene registro, poll, backoff e idempotencia; no se confirma ni publica DLT por imposibilidad de abrir una transacción. Contrato inválido, conflicto de versión e integridad de datos conservan tratamiento terminal. Un fallo desconocido tiene tres intentos y después suspende procesamiento con ERROR, sin DLT ni commit, hasta reparación/reinicio; no se presume que sea transitorio ni que el mensaje sea inválido. La lógica de persistencia KafkaStores no se cambió.

### Cierre y plazos

La parada normal señala running/requestStop, impide nuevas tareas y usa wakeup en lugar de interrumpir al propietario KafkaConsumer. Ese propietario cierra su ejecutor y consumidor; interrupciones previas se limpian temporalmente durante la limpieza y se restauran después. Trabajo pendiente no se confirma durante close. Se comprueban terminación real y cierre de clientes mediante isShutdownComplete; start rechaza reiniciar una instancia todavía incompleta.

Los plazos Spring están documentados en el runbook: shutdown-timeout-ms (30000), task-shutdown-timeout-ms (5000), consumer-close-timeout-ms (5000) y producer-close-timeout-ms (3000), todos bajo p360.kafka. El plazo de workers debe cubrir tarea + consumidor; el cierre del productor añade su presupuesto separado. Los tres KafkaConfiguration leen estas opciones sin añadir dependencias a otros servicios. El timeout intenta interrupción y registra cierre INCOMPLETE; no promete detener JDBC ni informa éxito mientras quede trabajo. El recurso/consulta debe recuperarse; el registro se recupera por reentrega y guardas de versión, sin borrar offsets.

### Evidencia separada por tipo

- **Unitarias con Kafka simulado:** KafkaConsumerLoopTests tiene 12 casos. Agrega clasificación transaccional explícita, suspensión de errores desconocidos, cierre sin trabajo, cierre con trabajo activo, timeout de tarea que ignora interrupciones y conservación de interrupción del llamador; mantiene rebalances, commits y DLT. La reentrega simulada no se presenta como integración real.
- **PostgreSQL y TransactionTemplate reales, consumidor Kafka simulado:** KafkaTransactionRecoveryTests tiene 2 casos. Una DataSource de prueba rechaza getConnection con SQLException; DataSourceTransactionManager realmente produce CannotCreateTransactionException durante TransactionTemplate, no desde el procesador. Más de tres fallos mantienen cero DLT y cero commits; al recuperar adquisición de conexión se persiste el mismo evento. El segundo caso ejecuta SELECT pg_sleep(0.8) en PostgreSQL desechable: cierre de 50 ms, consulta visible en pg_stat_activity y continuación JDBC tras interrupción, sin commit del offset; se verifica terminación y persistencia posteriores.
- **Kafka/ZooKeeper y PostgreSQL reales:** KafkaRuntimeIntegrationTests tiene 4 casos. La nueva prueba bloquea deliberadamente un KafkaStores simulado que ignora interrupciones, comprueba cierre incompleto y rechazo de restart, offset pendiente en el broker, libera el trabajo y arranca otra instancia del mismo grupo con KafkaStores real. La reentrega persiste la proyección. Captura logs del cliente para rechazar Failed to close coordinator. Se mantienen ambos recorridos, reinicios, caída/reinicio del broker sin borrar volúmenes y fallos de DLT inyectados. Los cierres normales verifican isShutdownComplete.
- **Procesos reales:** ServiceProcessesIntegrationTests volvió a ejecutar los tres JAR. Se verificó que el JAR core embebido en cada servicio coincide por SHA-256 con el core final.

Los XML más recientes suman **712 pruebas Java**, sin fallos, errores ni omisiones: core 26, messaging-core 286, Restaurantes 37, Productos 47, Carrito 106, administrador 4 y BFF 206. Se ejecutaron las suites completas de estos siete módulos. La última ejecución completa del core tuvo 25 casos; posteriormente se repitió la clase transaccional completa con 2 casos al agregar JDBC real, dando 26 por unión de XML. No es una ejecución monolítica de todo el repositorio ni incluye suites de servicios ajenos al alcance Kafka.

Frontend: 258 pruebas, lint y build aprobados sin cambiar código ni dependencias frontend. Compose: 13 pruebas aprobadas. Se reconstruyeron los tres servicios mediante los procedimientos anterior y nuevo. No cambiaron Dockerfiles/Compose en esta corrección final. Las siete migraciones históricas conservan sus bytes frente al corpus original; git diff --check aprobado. No se hicieron pruebas cloud, una caída física prolongada de PostgreSQL ni se garantiza terminar una consulta JDBC bloqueada para siempre. Los ERROR de timeout/cierre incompleto en las pruebas de fallo son intencionales; los logs definitivos no contienen Failed to close coordinator ni Interrupted while waiting for consumer heartbeat.

### Archivos de esta corrección final

- Core: KafkaConsumerLoop.java y KafkaRuntime.java.
- Configuración de runtime: KafkaConfiguration.java de Restaurantes, Productos y Carrito.
- Pruebas: KafkaConsumerLoopTests.java, KafkaRuntimeIntegrationTests.java y nuevo KafkaTransactionRecoveryTests.java.
- Documentación: infrastructure/kafka/README.md, este informe y kafka-change-manifest.json.

### Logs de esta corrección final (ignorados, no versionar)

- backend/shared/p360-kafka-core/target/final-fix-complete.log: core completo, broker real, reentrega y procesos JAR; final-fix-real-jdbc.log: clase transaccional completa con JDBC real.
- backend/shared/p360-kafka-core/target/final-fix-rabbit.log: suite completa de 286 pruebas RabbitMQ, BUILD SUCCESS.
- backend/services/{restaurantes,productos,carrito,kafka-admin}-service/target/final-fix-verification.log y backend/bff/target/final-fix-verification.log: suites completas y package.
- backend/shared/p360-kafka-core/target/final-fix-old-build.log y final-fix-new-build.log: ambos builds Compose; final-fix-compose.log: 13 pruebas de infraestructura.
- frontend/final-fix-tests.log, final-fix-lint.log y final-fix-build.log: frontend.

Persisten como límites operativos la pausa global, DLT sin payload, retención/purga pendiente y seguridad/alta disponibilidad cloud. El aviso heredado follow-redirects queda fuera de este alcance. No se autorizaron commits, publicación ni despliegue y no se solicitan hasta superar una nueva auditoría independiente.

## Jira relacionado

KP-57/KP-68/KP-69: plataforma y clientes. KP-64/KP-84–86: administrador. KP-58/KP-70–72: productores, proyecciones y recuperación. KP-59/KP-73/KP-74: BFF/React/JWT. KP-60/KP-75–77: pruebas y regresiones. KP-66/KP-67: decisiones y contratos. No se cambiaron estados o responsables durante el desarrollo.

## Control previo a commit

Mensaje exacto propuesto:

`feat(kafka): integrate event streaming into Pedidos360`

Contenido propuesto: exclusivamente los archivos enumerados abajo y en kafka-change-manifest.json. `git diff --check` aprobado; índice vacío; HEAD permanece en el commit base. Se requiere revisión y aprobación expresa antes de git add o git commit. Push, PR y despliegue requieren autorización independiente. No se añaden trailers Co-authored-by.

## Archivos propuestos

- `M` `backend/bff/src/main/java/cl/duoc/pedidos360/bff/client/ComercioClient.java`
- `A` `backend/bff/src/main/java/cl/duoc/pedidos360/bff/controller/KafkaObservacionesController.java`
- `M` `backend/bff/src/test/java/cl/duoc/pedidos360/bff/JwtHttpTests.java`
- `M` `backend/services/carrito-service/Dockerfile`
- `M` `backend/services/carrito-service/pom.xml`
- `A` `backend/services/carrito-service/src/main/java/cl/duoc/pedidos360/carrito/kafka/CarritoDisponibilidadController.java`
- `A` `backend/services/carrito-service/src/main/java/cl/duoc/pedidos360/carrito/kafka/KafkaConfiguration.java`
- `A` `backend/services/carrito-service/src/main/resources/db/migration/V3__kafka_state_outbox_projection.sql`
- `A` `backend/services/carrito-service/src/test/java/cl/duoc/pedidos360/carrito/CarritoKafkaObservacionTests.java`
- `A` `backend/services/kafka-admin-service/.dockerignore`
- `A` `backend/services/kafka-admin-service/Dockerfile`
- `A` `backend/services/kafka-admin-service/pom.xml`
- `A` `backend/services/kafka-admin-service/src/main/java/cl/duoc/pedidos360/kafkaadmin/KafkaAdminApplication.java`
- `A` `backend/services/kafka-admin-service/src/main/java/cl/duoc/pedidos360/kafkaadmin/TopicCatalog.java`
- `A` `backend/services/kafka-admin-service/src/main/java/cl/duoc/pedidos360/kafkaadmin/TopicReconciler.java`
- `A` `backend/services/kafka-admin-service/src/main/resources/application.yml`
- `A` `backend/services/kafka-admin-service/src/test/java/cl/duoc/pedidos360/kafkaadmin/TopicCatalogTests.java`
- `A` `backend/services/kafka-admin-service/src/test/java/cl/duoc/pedidos360/kafkaadmin/TopicReconcilerTests.java`
- `M` `backend/services/productos-service/Dockerfile`
- `M` `backend/services/productos-service/pom.xml`
- `A` `backend/services/productos-service/src/main/java/cl/duoc/pedidos360/productos/kafka/KafkaConfiguration.java`
- `A` `backend/services/productos-service/src/main/java/cl/duoc/pedidos360/productos/kafka/KafkaSnapshotCommand.java`
- `A` `backend/services/productos-service/src/main/java/cl/duoc/pedidos360/productos/kafka/RestauranteEstadoController.java`
- `M` `backend/services/productos-service/src/main/java/cl/duoc/pedidos360/productos/repository/ProductoRepository.java`
- `M` `backend/services/productos-service/src/main/java/cl/duoc/pedidos360/productos/service/ProductoService.java`
- `A` `backend/services/productos-service/src/main/resources/db/migration/V4__kafka_state_outbox_projection.sql`
- `A` `backend/services/productos-service/src/test/java/cl/duoc/pedidos360/productos/KafkaProducerIntegrationTests.java`
- `M` `backend/services/restaurantes-service/Dockerfile`
- `M` `backend/services/restaurantes-service/pom.xml`
- `A` `backend/services/restaurantes-service/src/main/java/cl/duoc/pedidos360/restaurantes/kafka/KafkaConfiguration.java`
- `A` `backend/services/restaurantes-service/src/main/java/cl/duoc/pedidos360/restaurantes/kafka/KafkaSnapshotCommand.java`
- `M` `backend/services/restaurantes-service/src/main/java/cl/duoc/pedidos360/restaurantes/repository/RestauranteRepository.java`
- `M` `backend/services/restaurantes-service/src/main/java/cl/duoc/pedidos360/restaurantes/service/RestauranteService.java`
- `A` `backend/services/restaurantes-service/src/main/resources/db/migration/V3__kafka_state_outbox_projection.sql`
- `A` `backend/services/restaurantes-service/src/test/java/cl/duoc/pedidos360/restaurantes/KafkaProducerIntegrationTests.java`
- `A` `backend/shared/p360-kafka-core/.dockerignore`
- `A` `backend/shared/p360-kafka-core/pom.xml`
- `A` `backend/shared/p360-kafka-core/src/main/java/cl/duoc/pedidos360/kafka/EventCodec.java`
- `A` `backend/shared/p360-kafka-core/src/main/java/cl/duoc/pedidos360/kafka/KafkaConsumerLoop.java`
- `A` `backend/shared/p360-kafka-core/src/main/java/cl/duoc/pedidos360/kafka/KafkaRuntime.java`
- `A` `backend/shared/p360-kafka-core/src/main/java/cl/duoc/pedidos360/kafka/KafkaStores.java`
- `A` `backend/shared/p360-kafka-core/src/main/resources/contracts/producto-disponibilidad-v1.schema.json`
- `A` `backend/shared/p360-kafka-core/src/main/resources/contracts/restaurante-estado-v1.schema.json`
- `A` `backend/shared/p360-kafka-core/src/test/java/cl/duoc/pedidos360/kafka/EventCodecTests.java`
- `A` `backend/shared/p360-kafka-core/src/test/java/cl/duoc/pedidos360/kafka/KafkaConsumerLoopTests.java`
- `A` `backend/shared/p360-kafka-core/src/test/java/cl/duoc/pedidos360/kafka/KafkaRuntimeIntegrationTests.java`
- `A` `backend/shared/p360-kafka-core/src/test/java/cl/duoc/pedidos360/kafka/KafkaStoresIntegrationTests.java`
- `A` `backend/shared/p360-kafka-core/src/test/java/cl/duoc/pedidos360/kafka/KafkaTransactionRecoveryTests.java`
- `A` `backend/shared/p360-kafka-core/src/test/java/cl/duoc/pedidos360/kafka/ServiceProcessesIntegrationTests.java`
- `A` `docs/kafka-change-manifest.json`
- `A` `docs/kafka-implementation-review.md`
- `M` `frontend/.env.example`
- `M` `frontend/Dockerfile`
- `A` `frontend/src/features/carrito/KafkaObservations.jsx`
- `M` `frontend/src/features/carrito/RealCartPanel.jsx`
- `M` `frontend/src/features/carrito/RealCatalog.jsx`
- `A` `frontend/src/features/carrito/kafkaObservations.js`
- `M` `frontend/src/features/restaurantes/RealCatalogPanel.jsx`
- `A` `frontend/tests/kafkaObservations.test.js`
- `A` `frontend/tests/kafkaObservationsUi.test.js`
- `M` `infrastructure/aws/compose.build.yml`
- `M` `infrastructure/aws/compose.test.mjs`
- `A` `infrastructure/kafka/README.md`
- `A` `infrastructure/kafka/broker.properties`
- `A` `infrastructure/kafka/compose.build.yml`
- `A` `infrastructure/kafka/compose.yml`
