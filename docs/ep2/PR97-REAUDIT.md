# PR #97 — Corrección focalizada y reauditoría

HEAD auditado: `0d7f94a0acd659cac61708815d08a1399f09bcbd`.
Base: `2ae0f9e19cd9a9d7014c6e2e935adcbbe81a5269`.
Commit de código y regresiones: `236d9dbf52a4c68e1c50dc6904622bbd8abe2873`.

## Igualdad de ID

Se aplicó exactamente `.filter(x -> x.getId().equals(c.carritoId()))` en `CarritoReceiptStore.process()`. La búsqueda acotada de comparaciones de IDs dentro del PR no encontró otra igualdad entre dos envoltorios que requiriera corrección; las restantes comparaciones son contra primitivos, enums o null.

Precisión sobre el hallazgo: en el HEAD auditado `Carrito.getId()` devuelve `Long`, pero `VaciarCarritoPorPedido.carritoId()` devuelve **long primitivo**. Por las reglas de Java, el `==` previo desempaqueta el `Long` y compara valores. Por tanto, no se reprodujo el rechazo por identidad de referencias atribuido a esa línea. Se conservó la mejora explícita autorizada, sin modificar el contrato V1 ni los tipos del comando.

La misma prueba PostgreSQL pasó antes y después: ID >127, otro objeto `Long` del mismo valor confirmado con `isNotSameAs`, tenant/OID/versión legítimos → EMPTIED; después de modificar el carrito, el duplicado conserva el estado terminal y no incrementa la versión ni borra la línea nueva; una orden distinta con snapshot anterior → OMITTED_VERSION_CHANGED. El log de la ejecución anterior se conserva separadamente de los totales posteriores.

## Control de retry y conversión

- El publisher de Pedidos ejecuta su implementación real y se inspecciona `Basic.Get` sin conversión: `retry-count=0`, `user_id=p360-pedidos-carrito-publisher`, UUID y cuerpo original.
- El listener real observa entrega inicial 0/Pedidos y entrega desde retry 1/Carrito. Se compara el cuerpo byte por byte con el contenido canónico y se conserva el mismo UUID.
- Al recibir el retry, una consulta PostgreSQL confirma que la recepción exacta y `retry_authorized=true` ya estaban comprometidos. El error transitorio que inicia este retry se inyecta; transporte, TTL, broker, listener y base son reales.
- Retry sin recepción previa y retry con recepción pero sin autorización terminan en DLQ sin vaciar ni fabricar procedencia.
- El caso de confirm incierto publica efectivamente la copia por RabbitMQ y verifica su confirm interno; únicamente el future observado por el handoff se deja sin completar para inducir timeout. El original no se ACKea, no hay segunda publicación de retry ni alternativa a DLQ en esa entrega, y la copia legítima produce un único efecto. No se presenta como partición real de red ni se excluyen duplicados recuperables futuros del mismo handoff.

Spring AMQP 4.1.1 `DefaultMessagePropertiesConverter.toMessageProperties()` traslada el header numérico `retry-count` a `MessageProperties.retryCount` y lo retira del mapa. Una aserción nueva que buscaba el header en `RabbitTemplate.receive()` falló por ese detalle de representación; se corrigió para comprobar tanto la propiedad AMQP cruda como el accessor tipado. La normalización opt-in del listener ya reconstruía el header desde `BasicProperties` y conservó correctamente 0→1. **No se encontró incompatibilidad funcional de serialización/conversión y no se cambió ese código de producción.**

## Alcance y evidencia

El commit funcional cambia una línea de producción y dos archivos de pruebas. Se agregan cuatro casos; las aserciones de transporte del test existente del publisher también se refuerzan. No se rediseñaron outbox, dedupe, contratos, flags ni conexiones.

Las ejecuciones se realizan desde copias temporales aisladas de las ocho fuentes/POM, comprobadas byte por byte, para evitar la interferencia del compilador del IDE observada en la entrega inicial. Core se prueba e instala primero; después se ejecutan las siete suites de servicios. Los resultados, casos y SHA-256 quedan en `ISSUE82-tests.json`. El commit posterior de documentación no modifica fuentes funcionales.

Los límites y prerrequisitos de activación de `CARRITO-POR-PEDIDO.md` permanecen vigentes: permiso DLX operativo, versión/protocolos del broker frente a GHSA, TLS/credenciales, roles PostgreSQL, despliegue tenant-aware y corte #70 sin DELETE HTTP paralelo. No se realizaron esas operaciones. HTTP sigue predeterminado; el PR continúa Draft; no se fusionan ni cierran issues.


Las primeras ejecuciones aisladas de Pagos fallaron por la estructura incompleta de la copia: sus fixtures E2E buscan tanto `backend/bff/pom.xml` como `backend/services/pedidos-service/pom.xml`. En la segunda, la fixture de Pedidos fallo antes de guardar el classloader original y su cleanup lo dejo null, causando tambien fallos posteriores de descubrimiento JDBC. Se completo la estructura de fuentes, sin cambiar tests versionados, y se reejecuto la suite integra. Estos intentos fallidos se conservan fuera de los totales exitosos finales.


## Resultado final

| Suite | Casos | Exitosos | Omitidos |
|---|---:|---:|---:|
| core | 265 | 265 | 0 |
| bff | 203 | 203 | 0 |
| usuarios | 79 | 79 | 0 |
| restaurantes | 34 | 34 | 0 |
| productos | 44 | 44 | 0 |
| pedidos | 152 | 151 | 1 |
| pagos | 187 | 186 | 1 |
| carrito | 99 | 99 | 0 |

1063 casos, 1061 exitosos, dos Entra live omitidos; cero failures/errors. 64 regresiones #82 incluidas (cuatro nuevas); pruebas focalizadas posteriores: 26 Carrito + seis publisher. Core probado e instalado antes de ejecutar servicios; hash del JAR instalado igual al construido en la copia aislada.

**READY FOR INDEPENDENT RE-AUDIT**. La documentacion/evidencia posterior no cambia las fuentes funcionales verificadas. GitHub permanece OPEN/DRAFT; sin merge ni activacion.
