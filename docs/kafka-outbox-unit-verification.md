# Verificación unitaria del publicador Kafka

Ejecución local: 13 de noviembre de 2026, Java 21 y Maven offline.
Base: `b5a30ee997d399ee6300ddb54e88f41733ab0da9` (main).
Este archivo y `KafkaOutboxPublisherTests` son cambios locales posteriores a esa base;
no se atribuyen al commit publicado ni al despliegue AWS.

## Cobertura nueva

`KafkaOutboxPublisherTests` ejecuta el publicador real de `KafkaRuntime` con JDBC,
transacción, constructor de KafkaProducer y respuestas Future simulados. No crea un
DataSource, contexto Spring, PostgreSQL, broker, conexión de red ni contenedor Docker.

Para los contratos restaurante-estado y producto-disponibilidad se ejecutan tres casos:

| Caso | Comprobación |
|---|---|
| `SEND_THROWS` | Un error síncrono de envío programa el reintento, sin marcar entrega. |
| `ACK_FAILS` | Mientras la confirmación está pendiente no hay marcado; su fallo conserva el reintento. |
| `ACK_TIMES_OUT` | La ausencia de confirmación dentro del plazo no se considera éxito. |

Los seis casos verifican que se solicita un único incremento de intentos y reintento
a 30 segundos para la misma fila, sin `sent_at` prematuro. Al volver elegible la fila
simulada, la recuperación envía exactamente el mismo topic, clave, payload y UUID.
El marcado de entrega ocurre únicamente después del Future exitoso. No se permiten
escrituras JDBC adicionales; el runtime termina sin trabajo activo.

La elegibilidad y persistencia son simuladas: esto acredita la lógica del publicador,
no durabilidad SQL, rollback, exclusión entre réplicas, espera real de 30 segundos,
red real, confirms del broker o procesamiento exactamente una vez. La integración
híbrida previa con PostgreSQL real permanece separada y no se renombra.

## Ejecución reproducible

Desde la raíz de la fuente que contenga el nuevo archivo:

```powershell
mvn -o -B -ntp -f backend/shared/p360-kafka-core/pom.xml '-Dtest=KafkaOutboxPublisherTests' test
mvn -o -B -ntp -f backend/shared/p360-kafka-core/pom.xml '-Dtest=KafkaOutboxPublisherTests,EventCodecTests,KafkaConsumerLoopTests,KafkaTlsPropertiesTests' test
```

| Suite focalizada final | Aprobadas | Fallos/errores | Omitidas |
|---|---:|---:|---:|
| KafkaOutboxPublisherTests | 6 | 0 | 0 |
| EventCodecTests | 3 | 0 | 0 |
| KafkaConsumerLoopTests | 12 | 0 | 0 |
| KafkaTlsPropertiesTests | 6 | 0 | 0 |
| Total de la selección | 27 | 0 | 0 |

La primera ejecución aislada del nuevo archivo también aprobó sus seis casos;
son los mismos casos repetidos en regresión, no doce casos distintos. No se ejecutó
la suite completa con integración ni se levantó Docker. Los warnings existentes de
API deprecada y auto-attach de Mockito no impidieron la ejecución con Java 21.

## Trazabilidad y cierre delimitado

Los XML locales de Surefire identifican métodos, casos y cantidades; un recibo
sanitizado externo conserva sus hashes y el del archivo fuente. No se publican XML
con propiedades del entorno ni artefactos privados. KP-75 combina esta nueva evidencia
unitaria con DTO/consumidor y duplicados ya acreditados. KP-60 conserva las evidencias
previas de integración, recuperación, grupos y RabbitAdmin, sin repetirlas ni atribuirlas
a esta ejecución. El cierre de esas pruebas no equivale a publicar los archivos nuevos,
a modificar producción ni a cerrar la matriz cloud pendiente de otras incidencias.
