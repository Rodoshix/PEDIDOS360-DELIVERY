# Índice sanitizado de evidencias Kafka y mensajería

Actualizado localmente el 12 de noviembre de 2026. Base previamente publicada:
`2f28e3709294743be24cbf1ae16fa68848339e5d`, rama `feature/kafka-integration`.
El diagnóstico administrativo ampliado, las pruebas y la conciliación documental forman
parte de este incremento definitivo; no se atribuyen a esa base ni al despliegue AWS.
La segunda actualización Jira registra 16 incidencias finalizadas y 16 abiertas,
sin convertir pendientes de aceptación o del ciclo operativo en resultados aprobados.

## Matriz mínima

| Referencia | Evidencia | Entorno y resultado | Incidencias / límites |
|---|---|---|---|
| E1 | Restaurantes → Productos y Productos → Carrito, outbox y proyecciones | PASS AWS histórico | KP-58/KP-70/KP-71/KP-80; no prueba fallos AWS. |
| E2 | Pago → RabbitMQ → Pedido y republicación lógica idempotente | PASS AWS histórico | Pedido sintético 2; no demuestra redelivered=true físico en AWS. |
| E3 | Idempotencia HTTP con misma clave y contradicción | PASS AWS histórico: 201/201/409; mismo pago y sin outbox adicional en repetición | Pedido/pago sintéticos 3; EFECTIVO sin cobro real. Frontend original restaurado. |
| E4 | Matriz de aislamiento entre dos usuarios legítimos | PASS AWS histórico: acceso propio y denegación 403 de recursos ajenos; 401 sin autenticación | No acredita otro tenant ni todas las combinaciones de permisos. Panel temporal retirado; 28 tablas con hashes idénticos. |
| E5 | Recuperación Kafbat y consulta de topics, retención, ACL, grupos y lag | PASS AWS histórico consultivo | KP-69/KP-81/KP-85; no sustituye acceso interactivo protegido a ambos dashboards ni los negativos de grupos. |
| E6 | Retry Rabbit 5/30/120 s, confirms antes de ACK, DLQ y reentrega física idempotente | PASS fixture Docker local real | No AWS; excepciones de prueba y transporte manual de entregas declarados en el informe privado. |
| E7 | DLT de ambos topics, continuación, duplicados, fallo transaccional y recuperación de publicación terminal | PASS local real/acotado | Kafka/PostgreSQL reales, fallos controlados; no caída física prolongada AWS ni prueba de fallo del publicador outbox. |
| E8 | Revocación, reasignación, reentrega, SQL y lag cero con earliest | PASS fixture local real | Ensayo posterior válido; conservar el FAIL anterior con latest. No se reescribe como éxito ni se afirma rebalance AWS. |
| E9 | Fallo/no confirmación del publicador outbox y recuperación de ambos topics | 2 casos PASS en esta revisión local | PostgreSQL real desechable, producer Mockito y runtime real; no broker Kafka real. Ver política efectiva. |
| E10 | Grupos: miembros/asignaciones, vacío, sin offsets, ausencia, carrera y errores | 8 unitarias PASS y consultas PASS contra Kafka 3.9.2/ZooKeeper 3.8.4 locales reales | Activo, sin commit, no visible y vacío observados en broker; errores/carrera solo con mocks. Fixture PLAINTEXT loopback, sin prueba TLS/ACL AWS ni mutación de offsets. Se conserva el fallo inicial del harness. |
| E11 | Validación RabbitAdmin y rechazo de recursos protegidos | 35 casos PASS focalizados en esta revisión local | 6 de configuración y 29 invocaciones de integración existentes; HTTP/AMQP y RabbitMQ desechables, JWT de fixture. No suite completa, TLS cloud ni login Entra real. |
| E12 | Estados informativos React | Cobertura fuente y ejecución previa: 285 pruebas PASS del checkout temporal, incluidos diagnósticos | No es el total del candidato productivo sin paneles. Tests locales con transporte simulado cubren error/recuperación, desconocido, no mutación y descarte de observación anterior; no otra sesión integrada AWS de todos los estados. |
| E13 | Acceso a dashboards Kafka UI y RabbitMQ | Bloqueo público PASS AWS; acceso interactivo autorizado PENDING | Rechazo externo y ausencia de ingreso público acreditados; no equivalen a comprobar una sesión interactiva autorizada. |

Los informes/capturas/logs completos E1–E8 y la evidencia SQL se custodian privadamente.
No están publicados en GitHub y no se inventan enlaces públicos. Este índice no adjunta
Terraform, estados, planes, variables, certificados, credenciales, direcciones internas,
cabeceras, tokens, respaldos ni datos personales. Las evidencias históricas no prueban
salud, saldo ni capacidad actuales del entorno.

## Fuentes versionables y reproducción local focalizada

- `backend/shared/p360-kafka-core/src/test/java/cl/duoc/pedidos360/kafka/KafkaOutboxPublisherIntegrationTests.java`
- `backend/services/kafka-admin-service/src/test/java/cl/duoc/pedidos360/kafkaadmin/ConsumerGroupInspectionTests.java`
- `backend/services/rabbit-admin-service/src/test/java/cl/duoc/pedidos360/rabbitadmin/ConfigurationTests.java`
- `backend/services/rabbit-admin-service/src/test/java/cl/duoc/pedidos360/rabbitadmin/RabbitAdminIntegrationTests.java`
- `frontend/tests/kafkaObservationsUi.test.js`, `frontend/src/features/carrito/KafkaObservations.jsx`
- [Política efectiva adoptada](kafka-error-policy.md).

Comandos desde la raíz (requieren únicamente dependencias locales y Docker para PostgreSQL/RabbitMQ desechables):

```powershell
mvn -o -B -ntp -f backend/shared/p360-kafka-core/pom.xml '-Dtest=KafkaOutboxPublisherIntegrationTests,KafkaConsumerLoopTests,EventCodecTests,KafkaStoresIntegrationTests' test
mvn -o -B -ntp -f backend/services/kafka-admin-service/pom.xml '-Dtest=ConsumerGroupInspectionTests,AdminSafetyTests,TopicCatalogTests,KafkaTlsPropertiesTests' test
mvn -o -B -ntp -f backend/services/rabbit-admin-service/pom.xml '-Dtest=ConfigurationTests,RabbitAdminIntegrationTests#crudRoundTripAndBrokerCounts+invalidQueueDto+invalidExchangeType+invalidBindingDto+protectedNamesRejectedOnAllResourceRoutes+invalidBindingCannotReachProtectedResources' test
```

Resultados de las invocaciones finales: núcleo 21 PASS; administrador Kafka 26 PASS;
RabbitAdmin 35 PASS. Cero fallos/errores/omisiones en esas selecciones. Son ejecuciones
separadas, no una suite completa del proyecto. E9 es integración híbrida, no unitaria pura.
Los XML en `target/surefire-reports` y el receipt local de verificación registran clases,
cantidades y hashes; no son archivos publicados ni se deben añadir a Git.

Se conservan los intentos iniciales fallidos: sintaxis PowerShell de selección Maven,
dos aserciones de capitalización del enum y ruta V3/V4 del fixture. Se corrigió el harness,
no el runtime. RabbitAdmin no pudo iniciar offline por dependencias ausentes; la selección
final descargó dependencias Maven sin modificar POM/versiones ni configuración global.
No contar los intentos fallidos o pruebas omitidas históricas como aprobados.

## Pendientes que este ajuste no resuelve automáticamente

- La conciliación de KP-67/KP-72 fue adoptada expresamente; no se cambió el runtime.
- Si KP-75 exige unitarias puras, decidir formalmente si acepta E9 híbrida o mantiene esa
  exigencia. No renombrar una integración para simular cobertura unitaria.
- El diagnóstico ampliado de grupos tiene evidencia local real, no de despliegue AWS.
- Acceso interactivo a dashboards PENDING; bloqueo público PASS. Negativos no ejecutados de identidad
  o tenant; fallos AWS. No inferirlos de resultados positivos o de una DLT vacía.
- Aclaración Angular/React, paquete final, retirada autorizada, residuos/costes y redespliegue.
- Recuperación física prolongada, alta disponibilidad, pérdida real de ACK/confirms y carga
  siguen fuera de lo demostrado. No interrumpir servicios compartidos para repetir el fixture.

Este documento no autoriza cerrar Jira, publicar cambios, ejecutar pruebas cloud, detener
recursos ni realizar operaciones destructivas.
