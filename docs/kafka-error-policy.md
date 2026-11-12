# Política de errores Kafka: comportamiento efectivo

Inspección local del 12 de noviembre de 2026. Describe el runtime auditado; no cambia
código de negocio, DTO, JSON v1 ni configuración. Esta política fue adoptada expresamente
como referencia operativa y conciliada en KP-67/KP-72 el 12 de noviembre de 2026.
Se conserva el historial de la referencia anterior a 1/5/30 s, sin modificar los criterios.

| Recorrido / fallo | Comportamiento efectivo | Condición de avance |
|---|---|---|
| Publicador outbox: envío sin confirmación o fallido | `send().get(20 s)`; conserva la fila y su identidad, incrementa attempts y fija next_attempt a 30 s. El ciclo inspecciona cada 500 ms; no garantiza ejecución exacta al vencer el plazo. | Solo marca sent_at tras confirmación. Si la base falla, la transacción no acredita entrega; puede haber reenvío con el mismo evento. |
| Consumidor: infraestructura de datos/transacciones | Conserva el registro y el offset pendiente; espera 5 s entre intentos completados fallidos. Pausa particiones y mantiene poll. No hay un máximo numérico de intentos de infraestructura. | Persistencia exitosa; reparar la infraestructura/configuración cuando corresponda. No convertir eventos válidos en DLT por caída de base. |
| Consumidor: error desconocido | Tres intentos totales, con espera de 5 s entre fallos; después suspende el procesamiento, sin DLT ni commit. | Reparación y reinicio seguro bajo control operativo; no replay automático. |
| Contrato inválido, conflicto de versión o integridad | Tratamiento terminal, sin seguir reintentando el efecto de negocio. | DLT confirmada antes de confirmar offset. No prometer reparación automática del dato inválido. |
| Fallo de publicación DLT | Conserva modo terminal y offset; espera 5 s tras resultado terminal fallido, sin reaplicar negocio. | Confirmación del destino terminal. |
| Fallo de commit de offset o rebalance | Puede haber reentrega; se conserva la idempotencia de persistencia y la guarda de generación. | No confirmar trabajo de una asignación revocada ni trabajo incompleto al cerrar. |

No es una política uniforme de tres reintentos adicionales a 1/5/30 s. Las esperas de
publicación, procesamiento y destino terminal no deben mezclarse. Infraestructura
incorrectamente configurada también puede requerir reparación del operador; reintentar
no garantiza que se recupere sola.

La entrega es al menos una vez. La confirmación puede perderse después de que Kafka
acepte un mensaje; el eventId/payload estable y la proyección idempotente son necesarios.
Las DLT contienen diagnóstico correlacionable, no una copia automática del payload.
No se usan DLQ RabbitMQ como destinos Kafka. No se habilitan purgas ni resets/replay.

Fuentes: `KafkaRuntime.publish`, `KafkaConsumerLoop.classify/schedule/tick` y
`KafkaStores`, en `backend/shared/p360-kafka-core/src/main/java/cl/duoc/pedidos360/kafka/`.

## Verificación focalizada y límites

`KafkaOutboxPublisherIntegrationTests` usa el runtime y outbox reales con PostgreSQL
desechable y un KafkaProducer interceptado mediante Mockito. Para cada uno de los dos
topics mantiene inicialmente un Future sin resolver, verifica sent_at nulo, simula un
timeout, verifica attempts/next_attempt y habilita la recuperación en la base del fixture.
Comprueba clave, payload y eventId idénticos, una única fila y publicación marcada solo
tras la confirmación controlada. No modifica el código productivo para introducir el fallo.

La prueba adelanta next_attempt exclusivamente en el PostgreSQL desechable para no
esperar 30 s. No demuestra un broker Kafka real caído, una confirmación real perdida ni
la duración física de la espera. No es una prueba unitaria pura ni una ejecución AWS.
Las pruebas unitarias de codec/ciclo y de persistencia se registran separadamente.

## Decisión vigente

La política efectiva anterior es la referencia operativa adoptada; se conservan JSON v1
y el runtime auditado. Imponer una política de reintentos a 1/5/30 s exigiría autorización
nueva de cambio de comportamiento y pruebas. La conciliación documental no acredita
pruebas cloud no ejecutadas ni resuelve automáticamente sus pendientes.
