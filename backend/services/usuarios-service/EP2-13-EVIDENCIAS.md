# EP2-13 / #78 — Evidencia de consulta del perfil actual

Base: develop `0303a17d176ffd2ae61c73dd37bdce45189b05af`.
Ejecución: 8 de octubre de 2026, America/Santiago. Java 21, Maven Wrapper,
PostgreSQL 17 y RabbitMQ 4.1.8 (contenedores temporales), sin credenciales reales.

## Resultados reproducibles

- Usuarios `mvnw verify`: **73 pruebas, 0 fallos, 0 errores, 0 omitidas**.
  Incluye 25 casos Rabbit reales y 48 casos de regresión HTTP/repositorio/seguridad.
- BFF `BffConsultasAdapterTests`: **10 pruebas, 0 fallos, 0 errores, 0 omitidas**.
- Docker build con contexto `messaging-core`: correcto.
- Comandos completos en README. Logs locales ignorados en
  `target/ep2-13-verify.log`, `target/surefire-reports`, `target/ep2-13-docker.log`
  y `backend/bff/target/ep2-13-bff.log`. Los reportes se regeneran al ejecutar.

## Matriz esperado / obtenido

| Caso | Esperado | Obtenido |
|---|---|---|
| Perfil existente | 200, mismo DTO HTTP, ID local Long | Coincide con GET /usuarios/me; correlación y messageId preservados |
| Perfil ausente | 404 equivalente HTTP, ACK sin retry/DLQ | Correcto; status/detail comparados con HTTP |
| Perfil desactivado | 403 equivalente HTTP, ACK sin retry/DLQ | Correcto; status/detail comparados con HTTP |
| Mismo oid en otro tenant | No revelar perfil ajeno, 404 | Correcto |
| ADMIN | Resolver su propio perfil | Correcto |
| Firma/sobre/tenant/audiencia/rol/scope inválidos | DLQ, sin dominio ni retry | Correcto |
| JSON inválido o payload con IDs/tenant/oid/array | DLQ sin ejecutar dominio | Correcto |
| Actor vencido / request vencido | No ejecutar ni reintentar | Correcto; request vencido devuelve 504 y DLQ |
| Fallo DB transitorio | Un retry TTL 1s y respuesta correlacionada | Dos invocaciones; demora >=900ms; correlación y messageId preservados |
| Fallo persistente | Un retry, después DLQ | Dos invocaciones; retry-count=1 |
| Handoff sin ruta retry | Sin ACK prematuro; recuperación con backoff | Mismo request recuperado; >=450ms; consumer permanece vivo |
| Consumer detenido | Request permanece en cola; recuperación al iniciar | Correcto |
| Topología/permisos | Quorum, TTL por policy, sin configure; prefetch=1/ACK manual | Correcto |
| Timeout BFF y correlaciones | Deadline total, limpieza, duplicados, broker caído | 10 pruebas del adaptador existentes aprobadas |
| Seguridad HTTP | Mantener validación JWT y denegaciones previas | Regresión completa aprobada |

Los fallos técnicos se inyectan mediante spy en UsuarioService, pero las entregas,
TTL, confirms, returns, DLQ, canales y consultas exitosas usan broker y DB reales.
La comparación HTTP del listener utiliza identidad local de fixture; la validación
JWT real del endpoint se cubre por las pruebas de regresión existentes.
La suite BFF usa su fixture de servicio: no se afirma un despliegue conjunto
BFF + usuarios-service con Entra real.

## Límites y revisión pendiente

Relay DISABLED; sin corte, merge ni despliegue. Provisioning y policies de producción
siguen en #69. Los compose compartidos requieren coordinación para añadir el
contexto Docker `messaging-core`; no se editaron infraestructura ni AWS.
Pendientes revisión I1/I5 y coordinación I3/I4 antes de declarar el DoD aprobado.
ActorContext y Pagos no cambiaron. #81 sigue bloqueado por resolución de identidad:
esta consulta devuelve el perfil actual, sin convertir UUID a Long ni copiar datos.
