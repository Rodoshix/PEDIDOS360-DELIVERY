# EP2 — remediación AMQP

Estado: **READY FOR INDEPENDENT REVIEW**. Rama `codex/71-amqp-security`. Base `1e39a4c3a3d3e0d1abf589b47a752afc7147bb1f`.

## Cambios y artefactos

`com.rabbitmq:amqp-client` 5.30.0 → 5.37.0, mediante `rabbit-amqp-client.version` en ocho POM. Spring AMQP y Spring Boot permanecen en 4.1.1.

Cambios limitados a ocho POM, regresiones justificadas y documentación. Sin cambios de dominio, contratos, configuración runtime, SQL, Dockerfiles, TLS, flags, topología o aislamiento tenant. Java 21 conservado. Se aplican las propiedades administradas oficialmente por Spring Boot, sin actualizar versiones mayores.

Se inspeccionaron los árboles de dependencias de los ocho módulos y los siete JAR ejecutables. El manifiesto incluye SHA-256 y la lista completa BOOT-INF/lib de cada JAR; core es una biblioteca. No se construyeron ni publicaron imágenes Docker en esta remediación.

## Suites completas ejecutadas

| Módulo | Casos | Omitidos |
|---|---|---|
| shared/p360-messaging-core | 267 | 0 |
| bff | 203 | 0 |
| services/usuarios-service | 79 | 0 |
| services/restaurantes-service | 34 | 0 |
| services/productos-service | 44 | 0 |
| services/carrito-service | 99 | 0 |
| services/pedidos-service | 152 | 1 |
| services/pagos-service | 197 | 1 |

**1073 correctos, 2 omitidos, cero fallos y errores** (1075 casos). Core instalado antes de las siete aplicaciones; Java 21/Maven 3.9.15, `clean install` y `clean verify`, con repositorio Maven local de prueba. El manifiesto registra comandos, clases, logs, árboles, XML y hashes de los archivos fuente realmente ejecutados. Los commits posteriores de documentación no cambian esos archivos.

Dos regresiones acotadas del decoder AMQP: UTF-8 inválido en shortstr de 255 bytes y tablas anidadas a profundidad 40. Fallan con 5.30.0 y pasan con 5.37.0; no se ejecutó una prueba de agotamiento. La primera ejecución de core tenía 265 casos; la repetición completa final tiene 267 y es la única contabilizada.

PostgreSQL y RabbitMQ Testcontainers desechables verifican listeners, tenant, outbox, dedupe, ACK/confirms/returns y topología. Existen pruebas de `stop_app` y recuperación real del broker. Nack, incertidumbre, timeout y ciertas excepciones DB/transporte también se prueban mediante mocks o inyección: no equivalen a particiones reales de red. BffQueriesEndToEndTests levanta cinco contextos Spring reales del checkout, con HTTP y listeners, no cinco imágenes desplegadas. Las migraciones existentes solo se ejecutaron en bases desechables.

Las dos omisiones son Entra live en Pedidos y Pagos. JWT, ActorContext e IdentityProof emplean fixtures sintéticos. No se ejecutaron frontend, módulos auxiliares ajenos a estos ocho, TLS operativo, carga AWS ni auditoría general de OS/JRE. No hubo conexiones a RDS o RabbitMQ AWS.

## Avisos corregidos y residuales

- [GHSA-5m9f-rphj-c435](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-5m9f-rphj-c435) — CVE-2026-63336
- [GHSA-5xwg-cfvj-gff5](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-5xwg-cfvj-gff5) — CVE-2026-61634
- [GHSA-68mj-5wr7-6fgg](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-68mj-5wr7-6fgg) — CVE-2026-69219
- [GHSA-6g32-pxv4-2wfj](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-6g32-pxv4-2wfj) — CVE-2026-63337
- [GHSA-7822-rcf6-97fx](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-7822-rcf6-97fx) — CVE-2026-106122
- [GHSA-93j5-89vc-pph4](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-93j5-89vc-pph4) — CVE-2026-69220
- [GHSA-cqgh-8p3p-mx4m](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-cqgh-8p3p-mx4m) — CVE-2026-106121
- [GHSA-h6w7-qmcm-q6xr](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-h6w7-qmcm-q6xr) — CVE-2026-106123
- [GHSA-jh4v-gfqj-7rhx](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-jh4v-gfqj-7rhx) — CVE-2026-75516
- [GHSA-qx7j-jv8m-fppr](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-qx7j-jv8m-fppr) — CVE-2026-63335

El mantenedor fija GHSA-cqgh-8p3p-mx4m en 5.37.0; el dato OSV histórico de 5.36.1 no se utilizó para elegir la versión. Varias alertas requieren JSON-RPC no usado aquí; los defectos de shortstr/tablas pertenecen también al cliente AMQP general.

En esta rama independiente permanecen los avisos de otras familias del inventario: GHSA-7hhh-6rmp-j9qf, GHSA-9xv2-5v5q-p794, GHSA-cxp5-3px4-pw24, GHSA-gcx9-497g-6cp6, GHSA-gx83-3vf8-gh7j, GHSA-h3x4-894j-xpx5, GHSA-p6pp-m3f8-5c89, GHSA-q4xh-88c3-wmh7, GHSA-wjgm-6hv5-3cvf, GHSA-wv8q-qhhj-9h54. Consulta OSV de 122 coordenadas runtime: 10 IDs residuales, enumerados en el manifiesto. No aparecieron IDs runtime nuevos respecto del inventario. Advertencias persistentes de tests: agente dinámico Mockito/Byte Buddy y dos omisiones Entra; no son vulnerabilidades runtime resueltas por estas propiedades. Los datos se contrastan con fuentes primarias y Maven Central; tests verdes no son evidencia suficiente por sí solos.

## Integración aislada y límites

Los tres Draft se crearon antes de probar la composición conjunta. [Resultado combinado](EP2-REMEDIATION-COMBINED.md): **1.078 correctos y dos omisiones**, cero fallos/errores. Los siete JAR contienen todas las versiones objetivo; OSV no devuelve avisos para las 122 coordenadas consultadas. El manifiesto combinado conserva hashes y commits de código fuente. No equivale a ausencia universal de vulnerabilidades ni autorización de publicación.

`git merge-tree` detectó conflictos mecánicos en los ocho POM entre cada par de PR. Orden recomendado: #109 → #110 → #111; conservar la unión de overrides y regresiones. No se hizo merge ni se modificó develop. Un rollback a versiones vulnerables reabre el gate de publicación.

Persisten requisitos operativos de TLS/CA, claves, permisos DLX, roles RDS runtime/migrador y corte coordinado. HTTP predeterminado y artefactos EP2 inactivos conservados. Sin terminalidad durable global nueva. No hubo cambios AWS/ECR, certificados, permisos, flags o issues.

Evidencia reproducible local: `operational-evidence/EP2-REMEDIATION-2026-10-10/A`. Hashes y composición en `EP2-AMQP-REMEDIATION-tests.json`; fuentes oficiales y controles negativos preservados fuera del repo. Revisar independientemente antes de cualquier integración.
