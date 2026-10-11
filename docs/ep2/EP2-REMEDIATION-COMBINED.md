# EP2 — prueba local de remediaciones combinadas

Estado: READY FOR INDEPENDENT REVIEW. Base 1e39a4c3a3d3e0d1abf589b47a752afc7147bb1f.

Los tres Draft existían antes de iniciar esta prueba. Un archivo git archive de la base se extrajo en un directorio aislado; se aplicó la unión de propiedades y regresiones de los commits de código indicados en combined-tests.json. La generación local cambió la codificación de algunos comentarios de POM: se comprobó igualdad del XML Maven efectivo excluyendo comentarios y hashes exactos de las regresiones. Esta diferencia del banco no está en los PR ni afecta la configuración ejecutada. No se cambió develop ni se creó una rama de integración. Los commits posteriores de evidencia no cambian estos archivos fuente.

## Resultado ejecutado

| Módulo | Casos | Omitidos |
|---|---|---|
| shared/p360-messaging-core | 268 | 0 |
| bff | 205 | 0 |
| services/usuarios-service | 79 | 0 |
| services/restaurantes-service | 35 | 0 |
| services/productos-service | 45 | 0 |
| services/carrito-service | 99 | 0 |
| services/pedidos-service | 152 | 1 |
| services/pagos-service | 197 | 1 |

1080 casos: 1078 correctos, 2 omitidos, cero fallos y errores. Java 21, Maven 3.9.15, core clean install y siete aplicaciones clean verify, en repositorio Maven aislado. Las omisiones corresponden a Entra live de Pedidos y Pagos.

Los siete JAR contienen amqp-client 5.37.0, Jackson 3 core/databind 3.1.7 y Tomcat core/el/websocket 11.0.26. Restaurantes y Productos contienen además Jackson 2 core/databind 2.21.7. Annotations permanece en 2.21 conforme a ambos BOM. Composición BOOT-INF/lib completa, SHA-256 de JAR, fuentes, logs y XML en el manifiesto. Todas las bibliotecas runtime empaquetadas están incluidas en las 122 consultas de coordenada/versión OSV; avisos devueltos: []. Se corrigen los 20 avisos iniciales y el aviso primario adicional GHSA-649p-m576-vr99 mediante las versiones oficiales; cero resultados OSV no equivale a seguridad universal.

Las suites existentes ejercitan PostgreSQL y RabbitMQ desechables, listeners, outbox, aislamiento tenant, dedupe, retry/DLQ y parada/recuperación del broker. Nack, commit/confirm incierto y ciertas excepciones de transporte se inyectan; no son particiones de red demostradas. BffQueriesEndToEndTests usa cinco contextos Spring reales compilados del checkout, no cinco imágenes desplegadas. JWT/Entra usa fixtures excepto las dos omisiones live. No se reejecutó frontend, ni se construyeron/publicaron imágenes Docker. Sin RDS ni broker AWS, migraciones operativas, cambios TLS, contratos, dominio, flags o topología.

## Integración pendiente de autorización

La simulación git merge-tree identifica conflictos en los ocho POM para cada par A/B, A/C y B/C. Son inserciones en properties, sin conflictos en código de negocio. Orden recomendado: PR109 → PR110 → PR111. Conservar todos los overrides; jackson-2-bom.version solo corresponde a Restaurantes y Productos. La composición probada es referencia para resolver esos conflictos, no autorización para merge ni para omitir protecciones. Revisar de nuevo las referencias y la resolución antes de integrar.

Persisten advertencias de agente dinámico Mockito/Byte Buddy en tests y las dos omisiones Entra. No aparecieron avisos Maven runtime nuevos en OSV. Quedan fuera del alcance OS/JRE, TLS operativo, permisos DLX, claves, requisitos RDS runtime/migrador, carga y despliegue AWS. Una reversión a versiones vulnerables reabre el bloqueo de publicación. No se ejecutó PoC de agotamiento ni se presume que tests verdes demuestren toda condición de explotación.
