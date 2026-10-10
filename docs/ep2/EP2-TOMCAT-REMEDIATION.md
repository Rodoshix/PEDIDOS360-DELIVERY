# EP2 — remediación TOMCAT

Estado: **READY FOR INDEPENDENT REVIEW**. Rama `codex/71-tomcat-security`. Base `1e39a4c3a3d3e0d1abf589b47a752afc7147bb1f`.

## Cambios y artefactos

Tomcat Embedded 11.0.24 → 11.0.26 mediante `tomcat.version` en ocho POM. Core, EL y WebSocket quedan alineados; Spring Boot permanece en 4.1.1.

Cambios limitados a ocho POM, regresiones justificadas y documentación. Sin cambios de dominio, contratos, configuración runtime, SQL, Dockerfiles, TLS, flags, topología o aislamiento tenant. Java 21 conservado. Se aplican las propiedades administradas oficialmente por Spring Boot, sin actualizar versiones mayores.

Se inspeccionaron los árboles de dependencias de los ocho módulos y los siete JAR ejecutables. El manifiesto incluye SHA-256 y la lista completa BOOT-INF/lib de cada JAR; core es una biblioteca. No se construyeron ni publicaron imágenes Docker en esta remediación.

## Suites completas ejecutadas

| Módulo | Casos | Omitidos |
|---|---|---|
| shared/p360-messaging-core | 265 | 0 |
| bff | 205 | 0 |
| services/usuarios-service | 79 | 0 |
| services/restaurantes-service | 34 | 0 |
| services/productos-service | 44 | 0 |
| services/carrito-service | 99 | 0 |
| services/pedidos-service | 152 | 1 |
| services/pagos-service | 197 | 1 |

**1073 correctos, 2 omitidos, cero fallos y errores** (1075 casos). Core instalado antes de las siete aplicaciones; Java 21/Maven 3.9.15, `clean install` y `clean verify`, con repositorio Maven local de prueba. El manifiesto registra comandos, clases, logs, árboles, XML y hashes de los archivos fuente realmente ejecutados. Los commits posteriores de documentación no cambian esos archivos.

Dos regresiones contra Tomcat real: headers Forwarded/X-Forwarded/X-User-Id/X-Roles sin JWT no autentican (401), y header de 20 KiB se rechaza (400) sin afectar el healthcheck (200). No se modificó la configuración productiva de headers o límites.

PostgreSQL y RabbitMQ Testcontainers desechables verifican listeners, tenant, outbox, dedupe, ACK/confirms/returns y topología. Existen pruebas de `stop_app` y recuperación real del broker. Nack, incertidumbre, timeout y ciertas excepciones DB/transporte también se prueban mediante mocks o inyección: no equivalen a particiones reales de red. BffQueriesEndToEndTests levanta cinco contextos Spring reales del checkout, con HTTP y listeners, no cinco imágenes desplegadas. Las migraciones existentes solo se ejecutaron en bases desechables.

Las dos omisiones son Entra live en Pedidos y Pagos. JWT, ActorContext e IdentityProof emplean fixtures sintéticos. No se ejecutaron frontend, módulos auxiliares ajenos a estos ocho, TLS operativo, carga AWS ni auditoría general de OS/JRE. No hubo conexiones a RDS o RabbitMQ AWS.

## Avisos corregidos y residuales

- [GHSA-9xv2-5v5q-p794](https://tomcat.apache.org/security-11.html) — CVE-2026-65905
- [GHSA-gcx9-497g-6cp6](https://tomcat.apache.org/security-11.html) — CVE-2026-65182
- [GHSA-h3x4-894j-xpx5](https://tomcat.apache.org/security-11.html) — CVE-2026-68525

11.0.26 también incluye correcciones posteriores de [Apache Tomcat](https://tomcat.apache.org/security-11.html), documentadas en la auditoría previa. No se encontró activación de AJP/HTTP2 ni endpoints WebSocket; no se presume explotación runtime de todos los avisos. Se conserva la clasificación del mantenedor frente a diferencias de severidad de agregadores.

En esta rama independiente permanecen los avisos de otras familias del inventario: GHSA-5m9f-rphj-c435, GHSA-5xwg-cfvj-gff5, GHSA-68mj-5wr7-6fgg, GHSA-6g32-pxv4-2wfj, GHSA-7822-rcf6-97fx, GHSA-7hhh-6rmp-j9qf, GHSA-93j5-89vc-pph4, GHSA-cqgh-8p3p-mx4m, GHSA-cxp5-3px4-pw24, GHSA-gx83-3vf8-gh7j, GHSA-h6w7-qmcm-q6xr, GHSA-jh4v-gfqj-7rhx, GHSA-p6pp-m3f8-5c89, GHSA-q4xh-88c3-wmh7, GHSA-qx7j-jv8m-fppr, GHSA-wjgm-6hv5-3cvf, GHSA-wv8q-qhhj-9h54. Consulta OSV de 122 coordenadas runtime: 17 IDs residuales, enumerados en el manifiesto. No aparecieron IDs runtime nuevos respecto del inventario. Advertencias persistentes de tests: agente dinámico Mockito/Byte Buddy y dos omisiones Entra; no son vulnerabilidades runtime resueltas por estas propiedades. Los datos se contrastan con fuentes primarias y Maven Central; tests verdes no son evidencia suficiente por sí solos.

## Integración aislada y límites

Los tres Draft se crearon antes de probar la composición conjunta. [Resultado combinado](EP2-REMEDIATION-COMBINED.md): **1.078 correctos y dos omisiones**, cero fallos/errores. Los siete JAR contienen todas las versiones objetivo; OSV no devuelve avisos para las 122 coordenadas consultadas. El manifiesto combinado conserva hashes y commits de código fuente. No equivale a ausencia universal de vulnerabilidades ni autorización de publicación.

`git merge-tree` detectó conflictos mecánicos en los ocho POM entre cada par de PR. Orden recomendado: #109 → #110 → #111; conservar la unión de overrides y regresiones. No se hizo merge ni se modificó develop. Un rollback a versiones vulnerables reabre el gate de publicación.

Persisten requisitos operativos de TLS/CA, claves, permisos DLX, roles RDS runtime/migrador y corte coordinado. HTTP predeterminado y artefactos EP2 inactivos conservados. Sin terminalidad durable global nueva. No hubo cambios AWS/ECR, certificados, permisos, flags o issues.

Evidencia reproducible local: `operational-evidence/EP2-REMEDIATION-2026-10-10/C`. Hashes y composición en `EP2-TOMCAT-REMEDIATION-tests.json`; fuentes oficiales y controles negativos preservados fuera del repo. Revisar independientemente antes de cualquier integración.
