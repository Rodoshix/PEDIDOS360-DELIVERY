# Carrito: evidencia sanitizada de ACK de negocio

Candidato de código y pruebas locales; sin build de imagen, ECR, despliegue,
configuración operativa ni nueva ventana AWS.

Después de procesar un comando validado y cuando `basicAck(deliveryTag, false)`
termina sin excepción, el listener registra:

```text
messageId=<UUID validado> result=EMPTIED ACK_SENT
```

También se conserva el resultado OMITTED_VERSION_CHANGED cuando corresponde.
No se añaden payload, tenant, propietario, headers, token, deliveryTag ni datos
personales al registro. Se usan el UUID del comando ya validado y el enum del
receipt; nunca el header crudo recibido. Logger/timestamp existentes.

`ack()` devuelve un booleano de éxito: su excepción conserva la recuperación
anterior, sin nuevo retry ni publicación. Solo la rama de resultado de negocio
emite ACK_SENT. Handoffs a retry/DLQ conservan su ACK y comportamiento originales
sin emitir este registro: no se confunde ACK de descarte con vaciado exitoso,
y los mensajes inválidos no pueden introducir IDs arbitrarios en logs.

**ACK_SENT es invocación cliente AMQP exitosa, no recepción confirmada del
broker.** No se introduce protocolo de confirmación de consumer ACK. Correlacionar
con el mismo messageId en outbox/receipt terminal, captura continua, consumidor
único, identidad/conexión del broker estable y colas sin trabajo incierto después
de converger las métricas. Una reentrega produce otro ACK_SENT del mismo UUID:
no deduplicar registros ni afirmar exactamente una entrega por una sola fila de
receipt. La idempotencia sigue proporcionando un único efecto persistido.

## Pruebas ejecutadas

```sh
mvn -q -f backend/services/carrito-service/pom.xml \
  -Dtest=CarritoAckLoggingTests,CarritoCommandIntegrationTests,CarritoRabbitHttpGuardTests,CarritoServiceTests test
```

41 aprobadas, cero fallos/errores/omitidas:

- CarritoAckLoggingTests: 6 (incluye dos resultados parametrizados). Log exacto,
  orden después de ACK, sanitización, excepción sin éxito ni republicación,
  reentrega con dos ACK y receipt incierto sin ACK, handoff inválido sin log.
- CarritoCommandIntegrationTests: 26. RabbitMQ/PostgreSQL reales desechables;
  receipt transaccional, idempotencia, versionado, retry/DLQ, fallos y recuperación
  del consumidor existentes preservados.
- CarritoRabbitHttpGuardTests: 7. HTTP directo/BFF reales, 409 sin mutación,
  CRUD individual, versión posterior, mensaje duplicado, aislamiento y 401/403.
- CarritoServiceTests: 2. Comportamiento HTTP anterior preservado.

Los avisos/desconexiones del broker local en pruebas de recuperación pertenecen
a los escenarios de fallo; los XML finales no registran fallos. No Entra live,
AWS ni RDS operativo. Los logs completos/XML locales quedan fuera de Git;
CARRITO-ACK-SENT-tests.json conserva conteos y hashes de los XML.

## Rama y compatibilidad

Rama basada en head de #115, `549e974`: el guard y su arnés permanecen intactos.
PR Draft apilado con base `codex/carrito-http-empty-guard` para mostrar únicamente
listener, test y esta evidencia. Requiere #115 antes de integrar o retargetear a
develop, sin autorización de merge. #113 (overlays) y #114 (diagnóstico Core)
no comparten archivos modificados con este delta; no se afirma integración
conjunta probada ni aceptación AWS. Edge es otro PR independiente.

## Configuración y futura construcción, sin ejecutar

No flag nuevo: listener sigue condicionado a CARRITO_PEDIDOS_MODE=RABBITMQ.
HTTP no instancia este consumidor. No cambiar nivel del logger para ocultar
el INFO en la aceptación. Activación operativa y rollback usan los overlays ya
revisados y requieren autorización posterior; no se modificaron aquí.

Tras revisión independiente y autorización de build:

1. Reutilizar el ensamblado limpio/trazable de la candidata guard: git archive
   base f6a9540 y sustituir exclusivamente CarritoService.java aprobado #115 y
   CarritoCommandListener.java aprobado en este PR. Comparar todos los archivos:
   únicamente esos dos deltas de producción. Core, POM, migraciones, Dockerfile
   y configuración iguales a base; no incorporar arnés BFF/test-scope del PR
   al contexto de producción (ese classifier no se instala en Dockerfile).
2. Registrar base, heads, blobs, diff, hashes y manifiesto antes de construir.
   Construir únicamente Carrito linux/amd64, usando Dockerfile original y
   `--build-context messaging-core=backend/shared/p360-messaging-core` con el
   contexto `backend/services/carrito-service`. Verificar bytecode/logging del
   candidato y usuario non-root; las pruebas de este PR no validan una imagen
   que aún no se construyó.
3. Nueva etiqueta inmutable y digest; nunca sobrescribir candidata guard
   `sha256:8af304ce05eb65762293d9733ac989c7967e105188bd65b1a86339530843d05e`.
   Publicación ECR, pin, despliegue y ventana requieren autorizaciones separadas.
4. Recuperación conserva esquema migrado/datos; no reemplazar imagen mientras
   existan eventos pendientes/inciertos. Guard 409 y listener mantienen sus
   modos originales y no se añade ningún efecto de negocio por observabilidad.

El logging no resuelve admisión exclusiva ni el 502 histórico. Capturador actual
de contadores conserva su parada ante métricas null: debe revisarse por separado
cómo incorporar la nueva evidencia ACK_SENT antes de una ventana, sin convertir
null en cero ni aceptar solo PUBLISHED/EMPTIED. No se modifica el capturador aquí.
