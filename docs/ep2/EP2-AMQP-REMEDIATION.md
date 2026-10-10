# EP2 — remediación AMQP

Estado: READY FOR INDEPENDENT REVIEW. Rama independiente codex/71-amqp-security, base 1e39a4c3a3d3e0d1abf589b47a752afc7147bb1f. No autorización de publicación/despliegue.

## Cambio mínimo y composición

amqp-client5.30.0→5.37.0; Spring AMQP4.1.1/Boot4.1.1 conservados.

Ocho POM y regresiones pertinentes; no main/domain/configuración runtime/DTO/SQL/Dockerfiles/CA/flags/topología cambiados. Se usan propiedades administradas por Boot, sin actualizaciones mayores. Los siete JAR ejecutables y sus BOOT-INF/lib completos están en el manifiesto, con SHA256; core es JAR de biblioteca. dependency:tree por los ocho módulos confirma resolución efectiva y transitiva. No hay imagen Docker nueva ni pushECR.

## Pruebas completas ejecutadas

| Módulo | Casos | Fallos | Errores | Omitidos |
|---|---|---|---|---|
| shared/p360-messaging-core | 267 | 0 | 0 | 0 |
| bff | 203 | 0 | 0 | 0 |
| services/usuarios-service | 79 | 0 | 0 | 0 |
| services/restaurantes-service | 34 | 0 | 0 | 0 |
| services/productos-service | 44 | 0 | 0 | 0 |
| services/carrito-service | 99 | 0 | 0 | 0 |
| services/pedidos-service | 152 | 0 | 0 | 1 |
| services/pagos-service | 197 | 0 | 0 | 1 |

Total: 1075 casos, 1073 correctos, 2 omitidos, 0 fallos, 0 errores. Core clean install primero; aplicaciones clean verify. RepositorioMaven y worktrees cortos locales separados del workspace histórico; Java21/Maven3.9.15. Los resultados corresponden a los hashes POM/test publicados, no a las209 pruebas exploratorias previas.

Dos controles negativos bounded (255bytes invalid UTF8,40 tablas anidadas de pocos cientos de bytes) fallan con5.30.0 y pasan con5.37.0. Pruebas locales del decoder; no PoC de agotamiento. Core inicial265 y repetición final267: contar solamente ejecución final, no sumar ambas.

RabbitMQ4.1/4.1.8 Testcontainers y PostgreSQL17 desechables: listeners de catálogo/Usuarios/Pagos, PurchaseRabbitIntegrationTests, BffQueriesEndToEndTests, outbox/tenant/dedupe y reinicio JVMCarrito. BffQueriesEndToEndTests levanta cinco contextosSpring reales compilados del checkout con HTTP y listeners reales; no cinco hostsAWS ni contenedores de imágenes operativas. RabbitConsumerTests/CarritoPublisherIntegrationTests/RabbitCoreTests incluyen stop_app y recuperación real del broker. PlatformCompatibilityTests valida topología existente en broker desechable. ManualACK/confirms/returns/routing real donde corresponda; nack/confirm incierto/timeout/erroresDB también tienen casos Mockito/inyección y no se presentan como particiones reales de red. Testcontainers ejecuta Flyway exclusivamente en datos desechables; ningúnRDS.

Las dos omisiones son pruebasEntra live de Pedidos/Pagos. JWT/ES256/IdentityProof/Actor usan fixtures y decoder sintéticos; no se acreditaEntra operativo. No suiteFrontend por cambios exclusivamente Java; frontend sin modificación. No nuevo ensayoTLSinterhost operativo, cargaAWS o auditoríaOS/JRE general. Logs/árboles/XML originales permanecen en operational-evidence/EP2-REMEDIATION-2026-10-10/A; hashes en manifiesto; no publicar credenciales reales.

## Avisos y límites

Correcciones de versiones contrastadas con mantenedores/Central y rangos oficiales; testsverdes no son prueba universal de seguridad.

- [GHSA-5m9f-rphj-c435](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-5m9f-rphj-c435) / CVE-2026-63336
- [GHSA-5xwg-cfvj-gff5](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-5xwg-cfvj-gff5) / CVE-2026-61634
- [GHSA-68mj-5wr7-6fgg](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-68mj-5wr7-6fgg) / CVE-2026-69219
- [GHSA-6g32-pxv4-2wfj](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-6g32-pxv4-2wfj) / CVE-2026-63337
- [GHSA-7822-rcf6-97fx](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-7822-rcf6-97fx) / CVE-2026-106122
- [GHSA-93j5-89vc-pph4](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-93j5-89vc-pph4) / CVE-2026-69220
- [GHSA-cqgh-8p3p-mx4m](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-cqgh-8p3p-mx4m) / CVE-2026-106121
- [GHSA-h6w7-qmcm-q6xr](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-h6w7-qmcm-q6xr) / CVE-2026-106123
- [GHSA-jh4v-gfqj-7rhx](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-jh4v-gfqj-7rhx) / CVE-2026-75516
- [GHSA-qx7j-jv8m-fppr](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-qx7j-jv8m-fppr) / CVE-2026-63335

Persisten en esta rama aislada los avisos originales de otras familias: GHSA-7hhh-6rmp-j9qf, GHSA-9xv2-5v5q-p794, GHSA-cxp5-3px4-pw24, GHSA-gcx9-497g-6cp6, GHSA-gx83-3vf8-gh7j, GHSA-h3x4-894j-xpx5, GHSA-p6pp-m3f8-5c89, GHSA-q4xh-88c3-wmh7, GHSA-wjgm-6hv5-3cvf, GHSA-wv8q-qhhj-9h54. GHSA-649p adicional pendiente siJackson no actualizado. AvisosTomcat posteriores pendientes siTomcat no actualizado. Estos residuos se resuelven mediante los otros PR, no ampliando esta rama. ConsultaOSVruntime actual: 121 coordenadas/versiones; IDsrestantes: GHSA-7hhh-6rmp-j9qf, GHSA-9xv2-5v5q-p794, GHSA-cxp5-3px4-pw24, GHSA-gcx9-497g-6cp6, GHSA-gx83-3vf8-gh7j, GHSA-h3x4-894j-xpx5, GHSA-p6pp-m3f8-5c89, GHSA-q4xh-88c3-wmh7, GHSA-wjgm-6hv5-3cvf, GHSA-wv8q-qhhj-9h54. Esto no equivale a ausencia de vulnerabilidades: fuenteprimariaRabbitGHSA-cqgh fija5.37.0 frente a datoOSVhistórico5.36.1; ambos registros preservados. Avisosnuevos/transitivos de la consulta se investigan antes de declararapto para publicación.

## Integración y rollback

TresPR independientes comparten ocho POM: prever conflictos mecánicos en properties. Orden recomendadoAMQP→Jackson→Tomcat, reteniendo todas las propiedades y tests, sin cambiar main/contratos. Tras crear los tres Draft se ensayará composición aislada y suites completas; evidencia posterior agregada por referencia. No fusionar desde esta tarea.

Revertir únicamente PRafectado si aparece regresión binaria/serialización/auth/reliability, detener publicación y revisar; volver a versión vulnerable reabre gate, no autoriza desplegarla. Persisten TLS/CA/claves/permisosDLX/RDSruntime-migrador/operacionesde corte como gates separados; no se resuelven aquí. No terminalidaddurable global nueva. HTTPpredeterminado y artefactosEP2inactivos conservados. NingunaAWS/ECR/merge/issues/certificados/permisos modificados.
