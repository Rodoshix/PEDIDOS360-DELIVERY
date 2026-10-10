# EP2 — remediación JACKSON

Estado: **READY FOR INDEPENDENT REVIEW**. Rama `codex/71-jackson-security`. Base `1e39a4c3a3d3e0d1abf589b47a752afc7147bb1f`.

## Cambios y artefactos

Jackson BOM 3.1.5 → 3.1.7 mediante `jackson-bom.version` en ocho POM. Jackson 2 BOM 2.21.5 → 2.21.7 mediante `jackson-2-bom.version` solo en Restaurantes y Productos. Core y databind de ambas familias se verificaron por separado. Los BOM oficiales mantienen annotations 2.21; no se fuerza una versión inexistente.

Cambios limitados a ocho POM, regresiones justificadas y documentación. Sin cambios de dominio, contratos, configuración runtime, SQL, Dockerfiles, TLS, flags, topología o aislamiento tenant. Java 21 conservado. Se aplican las propiedades administradas oficialmente por Spring Boot, sin actualizar versiones mayores.

Se inspeccionaron los árboles de dependencias de los ocho módulos y los siete JAR ejecutables. El manifiesto incluye SHA-256 y la lista completa BOOT-INF/lib de cada JAR; core es una biblioteca. No se construyeron ni publicaron imágenes Docker en esta remediación.

## Suites completas ejecutadas

| Módulo | Casos | Omitidos |
|---|---|---|
| shared/p360-messaging-core | 266 | 0 |
| bff | 203 | 0 |
| services/usuarios-service | 79 | 0 |
| services/restaurantes-service | 35 | 0 |
| services/productos-service | 45 | 0 |
| services/carrito-service | 99 | 0 |
| services/pedidos-service | 152 | 1 |
| services/pagos-service | 197 | 1 |

**1074 correctos, 2 omitidos, cero fallos y errores** (1076 casos). Core instalado antes de las siete aplicaciones; Java 21/Maven 3.9.15, `clean install` y `clean verify`, con repositorio Maven local de prueba. El manifiesto registra comandos, clases, logs, árboles, XML y hashes de los archivos fuente realmente ejecutados. Los commits posteriores de documentación no cambian esos archivos.

Una regresión verifica rechazo temprano de nombre JSON de 16 KiB con límite 64; falla con 3.1.5 y pasa con 3.1.7. El primer intento tuvo un import incorrecto, corregido antes de las suites: su fallo de compilación se conserva y no se cuenta como control negativo. Dos regresiones comprueban OpenAPI mediante HTTP real en Restaurantes y Productos.

PostgreSQL y RabbitMQ Testcontainers desechables verifican listeners, tenant, outbox, dedupe, ACK/confirms/returns y topología. Existen pruebas de `stop_app` y recuperación real del broker. Nack, incertidumbre, timeout y ciertas excepciones DB/transporte también se prueban mediante mocks o inyección: no equivalen a particiones reales de red. BffQueriesEndToEndTests levanta cinco contextos Spring reales del checkout, con HTTP y listeners, no cinco imágenes desplegadas. Las migraciones existentes solo se ejecutaron en bases desechables.

Las dos omisiones son Entra live en Pedidos y Pagos. JWT, ActorContext e IdentityProof emplean fixtures sintéticos. No se ejecutaron frontend, módulos auxiliares ajenos a estos ocho, TLS operativo, carga AWS ni auditoría general de OS/JRE. No hubo conexiones a RDS o RabbitMQ AWS.

## Avisos corregidos y residuales

- [GHSA-7hhh-6rmp-j9qf](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf) — CVE-2026-89425
- [GHSA-cxp5-3px4-pw24](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24) — CVE-2026-91777
- [GHSA-gx83-3vf8-gh7j](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-gx83-3vf8-gh7j) — CVE-2026-83557
- [GHSA-p6pp-m3f8-5c89](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-p6pp-m3f8-5c89) — CVE-2026-89407
- [GHSA-q4xh-88c3-wmh7](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-q4xh-88c3-wmh7) — CVE-2026-68497
- [GHSA-wjgm-6hv5-3cvf](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wjgm-6hv5-3cvf) — CVE-2026-19032
- [GHSA-wv8q-qhhj-9h54](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54) — CVE-2026-91776

Además del inventario inicial se corrige [GHSA-649p-m576-vr99 / CVE-2026-68498](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-649p-m576-vr99), cuyo fix es 3.1.6/2.21.6. El test acotado acredita el comportamiento del parser; no demuestra todos los escenarios de explotación.

En esta rama independiente permanecen los avisos de otras familias del inventario: GHSA-5m9f-rphj-c435, GHSA-5xwg-cfvj-gff5, GHSA-68mj-5wr7-6fgg, GHSA-6g32-pxv4-2wfj, GHSA-7822-rcf6-97fx, GHSA-93j5-89vc-pph4, GHSA-9xv2-5v5q-p794, GHSA-cqgh-8p3p-mx4m, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5, GHSA-h6w7-qmcm-q6xr, GHSA-jh4v-gfqj-7rhx, GHSA-qx7j-jv8m-fppr. Consulta OSV de 122 coordenadas runtime: 13 IDs residuales, enumerados en el manifiesto. No aparecieron IDs runtime nuevos respecto del inventario. Advertencias persistentes de tests: agente dinámico Mockito/Byte Buddy y dos omisiones Entra; no son vulnerabilidades runtime resueltas por estas propiedades. Los datos se contrastan con fuentes primarias y Maven Central; tests verdes no son evidencia suficiente por sí solos.

## Integración aislada y límites

Los tres Draft se crearon antes de probar la composición conjunta. [Resultado combinado](EP2-REMEDIATION-COMBINED.md): **1.078 correctos y dos omisiones**, cero fallos/errores. Los siete JAR contienen todas las versiones objetivo; OSV no devuelve avisos para las 122 coordenadas consultadas. El manifiesto combinado conserva hashes y commits de código fuente. No equivale a ausencia universal de vulnerabilidades ni autorización de publicación.

`git merge-tree` detectó conflictos mecánicos en los ocho POM entre cada par de PR. Orden recomendado: #109 → #110 → #111; conservar la unión de overrides y regresiones. No se hizo merge ni se modificó develop. Un rollback a versiones vulnerables reabre el gate de publicación.

Persisten requisitos operativos de TLS/CA, claves, permisos DLX, roles RDS runtime/migrador y corte coordinado. HTTP predeterminado y artefactos EP2 inactivos conservados. Sin terminalidad durable global nueva. No hubo cambios AWS/ECR, certificados, permisos, flags o issues.

Evidencia reproducible local: `operational-evidence/EP2-REMEDIATION-2026-10-10/B`. Hashes y composición en `EP2-JACKSON-REMEDIATION-tests.json`; fuentes oficiales y controles negativos preservados fuera del repo. Revisar independientemente antes de cualquier integración.

Control final de formato: se eliminó una línea vacía adicional al EOF de JacksonSecurityRegressionTests.java para superar git diff --check. Se repitió la suite completa de core (266 casos correctos). La prueba combinada conserva el archivo previo, idéntico salvo ese whitespace; sus hashes originales permanecen en el manifiesto combinado. No se reejecutaron las siete aplicaciones por este cambio sin efecto funcional.
