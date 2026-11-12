# Administración programática Kafka

Aplicación Spring Boot sin servidor HTTP. Usa AdminClient con lista cerrada de comandos; no expone endpoints, cambios JWT/BFF ni operaciones de reset o eliminación de consumer groups. No imprime payloads, credenciales ni configuraciones sensibles.

## Catálogo operativo

| Comando | Resultado |
|---|---|
| `list` | Lista únicamente los tópicos autorizados que existen. |
| `describe` | Describe los cuatro tópicos del catálogo y sus configuraciones efectivas; informa los ausentes. |
| `plan` | Calcula creación o restauración de configuraciones, sin escribir. |
| `apply` | Crea los ausentes o restaura configuraciones aprobadas; realiza preflight del catálogo antes de escribir. |
| `groups` | Consulta grupos `p360-…`, estado, miembros seudonimizados/asignaciones, offsets confirmados y lag, sin alterarlos. |

Se conserva `TopicCatalog`: dos tópicos de negocio `p360.catalogo.*.v1` y sus DLT, tres particiones, una réplica. Negocio usa compactación y retención ilimitada; DLT usa borrado por retención de siete días. No se ofrecen parámetros para cambiar libremente este catálogo. La deriva de particiones/réplicas requiere otro plan, no una reasignación automática.

Ejemplos desde la raíz, solo si el broker local ya está autorizado y disponible:

```powershell
mvn -B -ntp -f backend/services/kafka-admin-service/pom.xml package
java -jar backend/services/kafka-admin-service/target/kafka-admin-service-0.0.1-SNAPSHOT.jar --p360.kafka.admin.command=list
java -jar backend/services/kafka-admin-service/target/kafka-admin-service-0.0.1-SNAPSHOT.jar --p360.kafka.admin.command=describe
java -jar backend/services/kafka-admin-service/target/kafka-admin-service-0.0.1-SNAPSHOT.jar --p360.kafka.admin.command=plan
java -jar backend/services/kafka-admin-service/target/kafka-admin-service-0.0.1-SNAPSHOT.jar --p360.kafka.admin.command=groups
```

### Salida consultiva de grupos

Se conserva `GROUP ... members=N` y `OFFSET ... committed=N lag=N` para offsets
conocidos. `MEMBER ... member_hash=<SHA-256> assignment=[topic-partición,...]` permite
correlacionar miembros/asignaciones sin imprimir consumerId, clientId ni host originales.
Un grupo sin miembros muestra `MEMBERS ... NONE`; sin offsets, `OFFSETS ... NONE`.
Una partición asignada sin commit muestra `committed=UNKNOWN lag=UNKNOWN`, nunca cero
inventado. Se ordena la salida para facilitar comparaciones; no se consumen eventos.

`GROUPS NO_VISIBLE_GROUPS` indica ausencia de grupos permitidos visibles. Los dos grupos
de negocio esperados que no aparecen se señalan `NOT_LISTED`: no demuestra inexistencia
global cuando la visibilidad puede estar limitada por permisos. Si un grupo desaparece
entre listado y descripción se informa `MISSING_DURING_QUERY`. Otros fallos/timeouts
interrumpen la consulta con error sanitizado y resultado incompleto, sin declarar éxito
ni devolver una lista vacía ficticia. Las líneas se reúnen antes de imprimir el resultado.
Las lecturas sucesivas no son un snapshot atómico de Kafka; una reasignación concurrente
puede cambiar lo observado y no habilita retries o mutaciones automáticas.

La ampliación está verificada mediante ocho casos de `ConsumerGroupInspectionTests`
con AdminClient simulado y el comando `groups` real contra Kafka 3.9.2/ZooKeeper 3.8.4
en un fixture local desechable. Se observaron miembros/asignaciones, offsets y lag,
particiones sin commit, grupos no visibles y un grupo vacío con offsets conservados.
Los errores y la desaparición concurrente de grupos se acreditaron solo con mocks.
El fixture usó PLAINTEXT en loopback: no acredita TLS/ACL ni ejecución AWS. Se conserva
el intento inicial fallido del harness y la comprobación posterior válida. No revalida
operaciones destructivas ni añade comandos, endpoints, permisos o reset de offsets.

`apply` escribe; ejecutar solo en el entorno autorizado. Es repetible: un segundo apply sin deriva informa UNCHANGED. Un fallo puede dejar cambios parciales; inspeccionar y volver a planificar. El broker de desarrollo sigue con `delete.topic.enable=false`; esta ampliación no modifica su Compose, configuración ni volúmenes.

## Operaciones desechables protegidas

| Comando | Alcance y confirmación |
|---|---|
| `test-create` | Crea un tópico propio de la ejecución, una partición, una réplica, cleanup delete y retention.ms=600000. |
| `test-describe` | Inspecciona ese tópico y configuración efectiva, incluida la procedencia del valor. |
| `test-update` | Modifica una sola configuración permitida; exige `UPDATE:<topic>:<config>:<value>` y verifica el valor efectivo mediante AdminClient. |
| `test-reset-config` | Elimina el override de una configuración; exige `RESET:<topic>:<config>`. Verifica que ya no sea DYNAMIC_TOPIC_CONFIG. |
| `test-delete` | Elimina solo ese tópico; exige `DELETE:<topic>`. No informa éxito hasta observar su ausencia. |

Todos los comandos test requieren **simultáneamente**:

1. `--p360.kafka.admin.disposable=true`.
2. `--p360.kafka.admin.run-id=<UUID-v4-canónico>`.
3. `--p360.kafka.admin.cluster-id=<ID-del-clúster-aislado>`.
4. `--p360.kafka.admin.topic=p360.disposable.<mismo-UUID>.<slug>`; slug minúsculo alfanumérico/guion, 1–32 caracteres.
5. Un único bootstrap loopback con puerto no privilegiado, distinto de 19092.
6. AdminClient debe confirmar ID del clúster, un único broker, rack `p360-disposable-<mismo-UUID>` y `delete.topic.enable=true`.
7. La confirmación exacta indicada cuando corresponda. No se aceptan parámetros extra, duplicados ni operaciones desconocidas.

Nunca se admiten `p360.catalogo.*`, DLT operativas ni tópicos pertenecientes a otra ejecución. Tampoco se permite alterar offsets mediante comandos administrativos.

Configuraciones permitidas en recursos desechables:

- `retention.ms`: entero entre 60000 y 604800000.
- `segment.ms`: entero entre 60000 y 86400000.
- `cleanup.policy`: `delete`, `compact` o `compact,delete`.

Reset no es borrado de datos inmediato, pero puede cambiar la política efectiva: por eso requiere confirmación. No se permiten credenciales, configuración de brokers, nombres arbitrarios ni topología arbitraria como parámetros. La auditoría registra operación, recurso validado, ejecución y resultado; un rechazo previo a validación usa operación UNKNOWN sin reflejar el valor no confiable.

Cada actualización, incluida una aparente ampliación de retención o una política sin delete, exige confirmación exacta. Reducir retención o activar delete puede eliminar registros; no se clasifica el riesgo mediante una lectura previa susceptible a carreras. La confirmación vincula operación, tópico, clave y valor; no se reutiliza entre operaciones ni valores. Reset y delete conservan sus confirmaciones propias.

Las mutaciones test requieren **todos** sus argumentos en la invocación actual: command, bootstrap-servers, disposable, run-id, cluster-id, topic y, según corresponda, config, value y confirm. No se heredan autorizaciones de Environment, variables, application.yml ni perfiles. También apply requiere command explícito; conserva la configuración habitual de conexión. Una operación mutante heredada sin command explícito se rechaza antes de crear AdminClient. Sin operación configurada, el valor predeterminado sigue siendo describe. Las consultas pueden usar configuración heredada. AdminRequest tiene constructor privado y solo se obtiene mediante el parser; no existe una fábrica alternativa sin control de procedencia.

Estas barreras previenen accidentes en pruebas locales. ID de clúster, rack y endpoint **no demuestran aislamiento físico o lógico completo** ni la ausencia de montajes operativos. El fixture verifica adicionalmente sus contenedores, redes y montajes antes de ejecutar operaciones destructivas. No sustituyen ACL/TLS ni protegen frente a un administrador Docker capaz de falsificar la infraestructura. No reutilizar esta configuración en cloud.

## Demostración reproducible y aislada

La demostración automatizada crea ZooKeeper 3.8.4 y Kafka JVM 3.9.2 por digest. Usa una red Docker propia, puerto aleatorio publicado solo en 127.0.0.1 y tmpfs para todos los directorios declarados como volúmenes por las imágenes. Antes de borrar, inspecciona etiquetas, red, ausencia de binds/volúmenes y marcador del broker. No monta el socket Docker ni secretos en Kafka/ZooKeeper. Testcontainers usa su mecanismo estándar de limpieza de fixtures.

```powershell
$env:P360_KAFKA_ADMIN_IT = 'true'
mvn -B -ntp -f backend/services/kafka-admin-service/pom.xml package
```

Esto prueba el mismo ApplicationRunner que despacha los comandos CLI: crea el catálogo en el fixture, modifica una configuración y acredita su reconciliación, repite apply, crea un tópico desechable, cambia/inspecciona/restablece configuración, crea un grupo de prueba con commit=1 y lag=1, consulta sin alterar offsets y elimina solo el tópico de prueba. Las cuatro copias del catálogo siguen presentes hasta el desmontaje del fixture. No son los tópicos del broker de desarrollo.

Al finalizar se retiran únicamente los contenedores y red propios; el estado tmpfs desaparece. Nunca ejecutar `docker compose down -v` contra el entorno conservado. `target/disposable-isolation.json` registra la ejecución; sus endpoints dejan de ser válidos cuando termina la prueba. No reutilizarlos para ejecutar borrados manuales.

### Regresiones del núcleo en el mismo broker descartable

Primero deben existir los JAR locales de Restaurantes, Productos y Carrito construidos con las dependencias compartidas correspondientes. Para prepararlos sin iniciar servicios ni publicar:

```powershell
mvn -B -ntp -f backend/shared/p360-messaging-core/pom.xml -DskipTests install
mvn -B -ntp -f backend/shared/p360-kafka-core/pom.xml -DskipTests install
foreach ($service in @('restaurantes','productos','carrito')) {
  mvn -B -ntp -f "backend/services/$service-service/pom.xml" -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw "Fallo al preparar $service" }
}
```

Esta preparación no acredita pruebas. No se modifican ni despliegan sus fuentes. Después:

```powershell
$env:P360_KAFKA_ADMIN_IT = 'true'
$env:P360_KAFKA_CORE_REGRESSION = 'true'
mvn -B -ntp -f backend/services/kafka-admin-service/pom.xml package
```

La quinta prueba mantiene vivo el fixture mientras invoca Maven sobre p360-kafka-core. Inyecta `P360_KAFKA_IT_BOOTSTRAP_SERVERS` al test Kafka y `P360_KAFKA_BOOTSTRAP_SERVERS` a los JAR hijos. Habilita los casos con broker real y procesos reales. **Deshabilita explícitamente la prueba histórica de outage**, que detiene un contenedor de desarrollo por nombre fijo: no debe tocar ese entorno. Se espera una omisión justificada en el core. No se presenta como acreditación de esa caída de broker ni como una nueva prueba física prolongada de PostgreSQL.

La prueba de grupos realiza commits únicamente desde su consumidor de prueba, con nombre UUID propio. La funcionalidad administrativa solo lee offsets. Kafka y ZooKeeper de desarrollo permanecen detenidos y sus volúmenes no se montan.

## Evidencias locales

- `target/admin-security-final.log`: pruebas y compilación del administrador.
- `target/core-regression.log`: regresiones del núcleo ejecutadas dentro del fixture.
- `target/disposable-isolation.json`: ID de ejecución, clúster, endpoint y contenedores desechables.
- `target/surefire-reports/`: resultados por clase.

Los artefactos target son locales e ignorados por Git. Un log de un intento fallido de preparación no acredita una operación exitosa. Para evaluar la ejecución más reciente, comprobar los resultados finales, el marcador ISOLATION VERIFIED, el cambio de configuración, el lag y DELETED, además de la ausencia final del tópico mediante AdminClient.


## Verificación registrada — 2026-11-10

- Administrador: 19 pruebas aprobadas, cero fallos, errores u omisiones, seguido de package exitoso. Incluye las 4 regresiones previas, 10 casos de seguridad y 5 casos de integración/orquestación.
- Núcleo Kafka: 26 casos detectados, 25 ejecutados correctamente y 1 omitido intencionalmente (outage del broker de desarrollo). Incluye ambos topics y los procesos JAR en el endpoint efímero.
- Kafka real 3.9.2/ZooKeeper 3.8.4: creación/listado/descripción, reconciliación idempotente, restauración efectiva del catálogo, reducción y aumento de retention.ms, cambios compact/delete/compact y segment.ms, eliminación del override retention.ms, grupo con commit=1/lag=1 sin alteración por inspección y eliminación confirmada del tópico desechable.
- Imagen local del administrador construida; los nueve rechazos previstos del CLI empaquetado se verificaron y se registran en target/admin-security-packaged.json y target/admin-security-packaged.log, sin red, con raíz readonly y usuario no privilegiado. Revisar allí el resultado de cada caso.
- Los contenedores de desarrollo permanecieron detenidos. Se compararon metadatos de sus tres volúmenes conservados antes/después, sin cambios; no se montaron para la prueba. Esto no equivale a un hash del contenido completo de esos volúmenes.
- No se validan cloud, ACL/TLS, reasignaciones de particiones/réplicas ni políticas arbitrarias fuera de la lista permitida. segment.ms se actualiza y verifica explícitamente en el fixture.

La preparación del fixture necesitó correcciones locales antes de la ejecución exitosa (escritura de configuración dentro del tmpfs y coherencia entre timeouts del cliente). Los intentos fallidos no llegaron a ejecutar borrados. Los resultados actuales corresponden a admin-security-final.log y core-regression.log, no a esos intentos.


### Corrección de consentimiento y procedencia

Se añadieron cuatro pruebas unitarias de seguridad: consentimiento ligado a operación/tópico/configuración/valor; rechazo de mutaciones heredadas antes de Admin.create; exigencia de cada campo de autorización en la invocación actual; preservación de consultas y describe predeterminado. Los casos existentes mantienen los rechazos de catálogo operativo, namespaces ajenos, endpoint, clúster y marcador inválidos.

En el fixture real se verificó que una reducción de retención sin consentimiento o con valor confirmado distinto deja retention.ms=600000. Con consentimiento exacto se aplicaron 120000 y después 240000. Activar delete sin consentimiento desde compact se rechazó y conservó compact; con consentimiento se aplicó delete y después se restauró compact. Se verificaron segment.ms=60000 y la restauración de retention.ms al valor del broker, 86400000. Las invocaciones con mutaciones heredadas se rechazaron sin eliminar el tópico ni cambiar su retención. La eliminación final explícita se acreditó con AdminClient y dejó presentes las cuatro copias del catálogo del fixture.

El registro target/admin-extension-verification.json contiene los hashes finales de los nueve archivos del incremento y de sus evidencias locales. No se vuelve a acreditar la prueba omitida de outage, ni cloud, ni aislamiento completo a partir de metadatos del broker. No se ejecutaron regresiones frontend/RabbitMQ en esta corrección: no se modificaron sus fuentes, contratos ni configuración.
