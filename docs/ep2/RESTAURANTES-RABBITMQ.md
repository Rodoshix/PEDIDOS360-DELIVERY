# EP2-14 — Restaurantes RabbitMQ (#79)

Base auditada: develop `82c32c0cc3582455040136bbefb70b4deb37bae4`. Implementación pendiente de revisión e integración. #79 permanece abierto; #70, #71 y #72 conservan responsabilidades independientes. No se atribuyen aprobaciones a integrantes: la auditoría técnica del responsable puede sustituir las revisiones internas por decisión del proyecto.

## Contrato y seguridad

La respuesta exitosa de `restaurante.listar.v1` lleva `payload` array de `RestauranteDto`,
incluido `[]`; `error=null`. Una respuesta con objeto se rechaza. Se conserva el envelope de ocho
campos y la matriz cerrada del [contrato compartido](REQUEST-REPLY-RABBITMQ.md). El request conserva
`payload={}`. En la integración BFF #70 se acredita además la solicitud pendiente antes de aceptar
el array; el nombre de operación remoto por sí solo no autoriza un resultado.

`p360.queries` / `restaurante.listar.v1` / `p360.restaurantes.consultas.q`, payload `{}`. El processor llama exclusivamente `RestauranteService.listar()`, que usa `findAll()`. Devuelve el DTO y orden existentes, incluye INACTIVOS y devuelve `[]` sin registros. HTTP permanece oficial.

El servicio HTTP interno no tiene filtro de Spring Security. El acceso público BFF con Entra habilitado exige `access_as_user` y rol CLIENTE o ADMIN; el precheck RabbitMQ conserva esa autorización. No incorpora validación de perfil activo, acceso a Usuarios, JWT original ni dependencia de SecurityContext HTTP. La exclusión de starter-security del core evita introducir filtros HTTP; security-core aporta la excepción de autorización.

Se reutiliza #77 con el endurecimiento ES256 de #92 para emisor/tenant, OID UUID, audiencia, vigencia y deadline; no existe fallback HMAC. replyTo solo admite la cola técnica BFF. El core aplica ACK manual, prefetch 1, confirms, mandatory y returns. El retry corto usa policy TTL 1s de #69, seguido de DLQ; un handoff sin ruta conserva el original y usa recovery con backoff 500ms. La aplicación no declara topología, TTL ni policies. Relay DISABLED por defecto; dynamic=false y declare-topology=false.

## Matriz ejecutada (2026-10-08)

| Caso / esperado | Obtenido |
|---|---|
| Suite completa Restaurantes | 34 pruebas; 0 fallos, errores o omitidas; verify SUCCESS |
| Listener con RabbitMQ 4.1.8 y PostgreSQL 17 reales | 25 pruebas aprobadas |
| Vacío, datos, INACTIVO, DTO equivalente a HTTP, correlación/messageId | Aprobado |
| `{}` válido; campos extra (4), array y JSON inválido rechazados | Aprobado |
| HMAC, tenant, audiencia, OID inválido con firma válida, actor vencido | Rechazo antes de dominio aprobado |
| Deadline vencido, scopes/roles y operación incorrecta | Aprobado; sin consulta en rechazos previos |
| replyTo fuera de cola técnica | Sin publicación a cola ajena; termina en DLQ |
| Manual ACK/prefetch 1 y consumer detenido/reiniciado | Aprobado |
| Fallo transitorio, TTL >=900ms y respuesta correlacionada | Aprobado; un retry |
| Fallo persistente | Dos invocaciones y DLQ con retry-count 1 |
| Handoff retry sin binding | Original conservado; recovery >=450ms; recuperación aprobada |
| Regresión HTTP/CRUD con PostgreSQL real | 6 pruebas aprobadas (alta, lectura, actualización, baja INACTIVO, validaciones, 404) |
| Configuración y autorización | 3 pruebas aprobadas; HTTP saludable sin broker por defecto |
| Adaptador BFF existente | 10 pruebas aprobadas; no cambios BFF |
| Plataforma #69 completa | 13 pruebas aprobadas en 190.741s |
| Inventario | 21 queues, 7 exchanges, 20 bindings, 21 policies; sin diferencias |
| Permisos de todas las cuentas, retry TTL, DLQ, delivery-limit, returns y persistencia tras reinicio | Aprobado |
| Compose caller real, build --no-cache restaurantes | Exit 0; imagen local.invalid/ep2-audit/pedidos360-restaurantes:ep2-14 |

La única diferencia de permisos frente a develop es write `p360.dlx` de `p360-restaurantes-consumer`: `^(p360\.retry|amq\.default|p360\.dlx)$`. Configure sigue `^$`; read sigue su cola de consultas. Usuarios/Productos conservan DLX; Pagos/Carrito siguen sin DLX. El test 07 publica a DLX con la cuenta real y recibe en su DLQ; comprueba configure denegado y deniega DLX a los consumidores restantes.

Los fallos transitorios/persistentes del dominio se inyectan con spy; PostgreSQL y RabbitMQ son reales, pero no se simula una caída física de PostgreSQL. La autorización Entra se contrasta con el código BFF y pruebas de precheck; no se ejecutó E2E con Entra ni AWS. Una primera ejecución detectó una comparación IntNode/LongNode del test HTTP; se normalizó mediante serialización JSON y se repitió verify completo con éxito.

## Reproducción y reportes

Desde la raíz, en PowerShell, con Docker disponible y JDK/Maven del proyecto:

```powershell
& backend/services/usuarios-service/mvnw.cmd -B -ntp -f backend/shared/p360-messaging-core/pom.xml -DskipTests install
& backend/services/usuarios-service/mvnw.cmd -B -ntp -f backend/services/restaurantes-service/pom.xml verify
Push-Location backend/bff
& ../services/usuarios-service/mvnw.cmd -B -ntp '-Dtest=BffConsultasAdapterTests' test
Pop-Location
Push-Location infrastructure/rabbitmq
$env:EP2_PLATFORM_TESTS='1'
& ./.venv/Scripts/python.exe test_platform.py
Pop-Location
```

Se usa el wrapper Windows existente de Usuarios; Restaurantes conserva su wrapper Linux para Docker. La suite #69 requiere la plataforma local dedicada provisionada conforme a su README, sin mensajes ni consumidores ajenos. Se actualizó únicamente el permiso local de Restaurantes; no hubo provisioning AWS.

Build real (variables fixture, sin levantar stack):

```powershell
$env:IMAGE_REGISTRY='local.invalid/ep2-audit'
$env:IMAGE_TAG='ep2-14'
$env:ENTRA_TENANT_ID='aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
$env:ENTRA_API_CLIENT_ID='11111111-2222-3333-4444-555555555555'
$env:ENTRA_FRONTEND_CLIENT_ID='22222222-3333-4444-5555-666666666666'
$env:FRONTEND_ORIGIN='https://frontend.example.invalid'
$env:PUBLIC_API_BASE_URL='https://api.example.invalid'
$env:RDS_HOST='database.example.invalid'
$env:PAGOS_WORKER_CLIENT_ID='33333333-4444-5555-6666-777777777777'
docker compose -p pedidos360-79-build -f infrastructure/aws/compose.yml -f infrastructure/aws/compose.build.yml build --no-cache restaurantes
```

Reportes locales (ignorados por Git): `backend/services/restaurantes-service/target/surefire-reports/`, `backend/bff/target/ep2-14-bff.log`, `infrastructure/rabbitmq/evidence/ep2-14-full-platform.log`, `ep2-14-permissions.json` en esa misma carpeta; logs verify y Compose en `$env:TEMP/p360-79-restaurantes.log` y `$env:TEMP/p360-79-compose.log`. No se versionan credenciales ni reportes voluminosos. La matriz resume ejecuciones reales, no resultados futuros.

## Alcance

Dos clases nuevas: `RestaurantesMessagingConfiguration` y `RestaurantesQueryProcessor`. Integración mínima del core en pom/Dockerfile/YAML; el único ajuste Compose es additional_contexts.messaging-core bajo build de restaurantes. Sin cambios al DTO, service, controller, ActorContext, Pagos, Pedidos, Carrito, frontend, BFF funcional o runtime AWS. Sin corte #70, despliegue, merge ni cierre #79. Estado propuesto: READY FOR AUDIT.
