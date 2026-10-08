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
siguen en #69. La corrección del mismo PR #89 añade exclusivamente el contexto
Docker `messaging-core` al build de Usuarios en `infrastructure/aws/compose.build.yml`
y el permiso write DLX de Usuarios en la fuente de plataforma. El runtime AWS
permanece intacto; no se despliega ni se amplían permisos de otros consumidores.
Pendientes revisión I1/I5 y coordinación I3/I4 antes de declarar el DoD aprobado.
ActorContext y Pagos no cambiaron. #81 sigue bloqueado por resolución de identidad:
esta consulta devuelve el perfil actual, sin convertir UUID a Long ni copiar datos.

## Corrección de blockers del mismo PR #89

Revalidación del 8 de octubre de 2026 (America/Santiago), desde el head
`52cda4d26964f5382c6502668dbee7de484657c1`:

| Verificación | Resultado |
|---|---|
| Usuarios `mvnw verify` | 73 pruebas; 0 fallos/errores/omitidas; 25 casos RabbitMQ + PostgreSQL reales |
| BFF `BffConsultasAdapterTests` | 10 pruebas; 0 fallos/errores/omitidas |
| Caller Compose real, build Usuarios sin caché | Correcto; imagen local construida; sin iniciar servicios AWS |
| Suite completa `test_platform.py` | 13 pruebas OK en 229,144 s |
| `Test-Application.ps1` de #69 | PLATFORM APPLICATION CHECK PASSED; declaraciones reales, retry original 5s, commit PostgreSQL e idempotencia |
| Inventario real #69 | 21 queues, 7 exchanges, 20 bindings, 21 policies |
| Permisos completos | 12 usuarios, 13 entradas de permisos, 2 vhosts; coincidencia exacta |
| Delta de plataforma contra develop | Solo Usuarios añade write p360.dlx; inventario idéntico y permiso de Productos conservado |
| DLX Usuarios | Publicación confirmada alcanza p360.usuarios.consultas.dlq; configure sigue denegado (403) |
| Consumidores no autorizados a DLX | Restaurantes, Pagos y Carrito reciben 403; sin ampliaciones |
| Reliability #69 | 8 colas retry con TTL real; DLQ por nack; delivery-limit 5; retención tras 25 cierres; mandatory/returns; persistencia stop/start; backlog 1000 mensajes drenado |

Caller ejecutado (con variables de interpolación de fixture local):

```powershell
docker compose -p pedidos360-pr89-build -f infrastructure/aws/compose.yml -f infrastructure/aws/compose.build.yml build --no-cache usuarios
```

Valores de fixture: registro `local.invalid/ep2-audit`, tag `pr89`, tenant/client IDs
UUID sintéticos y hosts `.invalid` para frontend/API/RDS. Solo se ejecuta `build`;
no se requieren credenciales AWS ni conexión RDS/Entra. El único cambio en el
caller es `usuarios.build.additional_contexts.messaging-core`.

Evidencia local ignorada y regenerable:

- `backend/services/usuarios-service/target/ep2-13-correction-verify.log`
- `backend/services/usuarios-service/target/ep2-13-compose-build.log`
- `backend/bff/target/ep2-13-correction-bff.log`
- `infrastructure/rabbitmq/evidence/ep2-13-full-platform.log`
- `infrastructure/rabbitmq/evidence/ep2-13-permissions.json`
- `infrastructure/rabbitmq/evidence/ep2-13-application.log`

La comparación de `inventory()` y `accounts()` contra develop comprueba que no
cambian queues/exchanges/bindings/policies ni configure/read/tags/vhosts.
Write Usuarios: `^(p360\.retry|amq\.default|p360\.dlx)$`.
Relay sigue DISABLED por defecto; HTTP oficial; ActorContext/Pagos intactos;
#81 bloqueado; #78 abierto; PR borrador sin merge. Revisión humana pendiente.

Reproducción de plataforma en el broker local dedicado, sin consumidores ni
mensajes funcionales (la inicialización usa el inventario versionado actualizado):

```powershell
Push-Location infrastructure/rabbitmq
./Initialize-Platform.ps1
$env:EP2_PLATFORM_TESTS='1'
./.venv/Scripts/python.exe test_platform.py
./Test-Application.ps1
Pop-Location
```

`git diff --check` aprobado. Recomendación de esta revalidación: **READY FOR AUDIT**.
