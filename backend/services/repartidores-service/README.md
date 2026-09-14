# Repartidores Service

Servicio de repartidores de Pedidos360. Java 21, Spring Boot 4.1.1 y Maven Wrapper 3.9.15.

## Estado

Implementados: entidad `Repartidor` (perfil y disponibilidad), entidad `AsignacionRepartidor` (asignaciones), repositorios JPA, migración Flyway y endpoints REST de perfiles, disponibilidad y asignaciones con validaciones.
Se incluye identidad simulada local; Entra ID y la validación real de tokens siguen pendientes.
La base final estará en AWS RDS. El PostgreSQL de Docker es exclusivamente para desarrollo local.

## Antes de empezar

Necesitas JDK 21 y Docker Desktop con el motor Linux iniciado. Maven se descarga con el Wrapper del repositorio; no hace falta instalarlo por separado. La primera ejecución requiere internet para descargar Maven, dependencias e imagen de PostgreSQL.

En la terminal PowerShell de VS Code, desde la raíz del repositorio:

```powershell
Set-Location backend/services/repartidores-service
java -version
docker info --format '{{.OSType}}'
```

Comprueba Java 21 y una respuesta `linux`. Los comandos siguientes se ejecutan desde `backend/services/repartidores-service`, no desde la raíz. Puertos predeterminados: API 8087 y PostgreSQL 5435, ambos locales.

## Modelo

- `Repartidor`: perfil vinculado a su identidad externa (`tenantId` + `entraObjectId`, únicos).
- `EstadoDisponibilidad`: DISPONIBLE, OCUPADO, EN_CAMINO, EN_PAUSA, DESCONECTADO, INACTIVO y SUSPENDIDO.
- `Vehiculo`: MOTO, BICICLETA y AUTO.
- `AsignacionRepartidor`: asignación de un pedido a un repartidor (`pedidoId` único), con estado y fecha.
- `EstadoAsignacion`: ASIGNADA, EN_CAMINO, ENTREGADA y CANCELADA.
- Fechas en UTC y versión en `Repartidor` para detectar actualizaciones concurrentes.

En pruebas se usan UUID y `pedidoId` ficticios; esto no equivale a una sesión autenticada ni a un pedido real.

## API

| Método | Ruta | Comportamiento |
| --- | --- | --- |
| POST | `/repartidores` | Crea el perfil de la identidad actual; 201 con Location. Duplicado: 409. |
| GET | `/repartidores/me` | 200 con el perfil actual; 404 si no existe. |
| GET | `/repartidores/{id}` | 200; propietario o ADMIN del mismo directorio. |
| GET | `/repartidores?pagina=0&tamanio=20` | 200; solo ADMIN, paginado, ordenado por ID ascendente y filtrado por directorio. |
| PUT | `/repartidores/{id}` | 200 con el perfil actualizado; propietario o ADMIN. |
| PUT | `/repartidores/{id}/disponibilidad` | 200; cambia el estado de disponibilidad. |
| DELETE | `/repartidores/{id}` | Pasa el repartidor a INACTIVO; propietario o ADMIN; 204. |
| GET | `/repartidores/{id}/asignaciones` | 200; lista las asignaciones del repartidor. |
| POST | `/repartidores/{id}/asignaciones` | Asigna un pedido; 201; pedido duplicado: 409. |
| PUT | `/repartidores/{id}/asignaciones/{pedidoId}/estado` | 200; cambia el estado de una asignación. |

Los cuerpos se envían en JSON (`application/json`). POST y PUT aceptan únicamente los campos documentados; se rechazan campos desconocidos.

## Seguridad

- Los endpoints `/repartidores` y `/repartidores/**` requieren autenticación.
- `/actuator/health` y `/actuator/health/**` son públicos.
- La identidad local de prueba se activa solo con `repartidores.identidad-local.enabled=true`, el perfil `local` y escucha en loopback (`127.0.0.1`).
- La validación de tokens Entra ID/JWT se incorporará en un bloque posterior; esta rama no acredita autenticación de producción.

## Base local

Requiere Docker Desktop con el motor Linux funcionando.

Desde esta carpeta, en PowerShell:

```powershell
if (-not (Test-Path .env.local)) {
  Copy-Item .env.example .env.local
}
```

Hacer la copia solo si no existe `.env.local`. Editar `DB_PASSWORD` antes de iniciar la base. El archivo queda ignorado por Git y contiene valores de desarrollo, sin comillas.

```powershell
docker compose --env-file .env.local up -d --wait
.\mvnw.cmd spring-boot:run
```

Spring carga `.env.local` desde el directorio de ejecución. Docker Compose usa el mismo archivo mediante `--env-file`. El puerto local de PostgreSQL es 5435; si se cambia `DB_PORT`, actualizar también `DB_URL`.

La base conserva sus datos en un volumen. Para detenerla conservando los datos:

```powershell
docker compose --env-file .env.local down
```

Las variables iniciales de usuario y contraseña de PostgreSQL se aplican al crear el volumen por primera vez; editar el archivo después no cambia las credenciales de una base existente.

Para probar la API localmente con la identidad simulada, habilitar `LOCAL_IDENTITY_ENABLED=true` en `.env.local`, usar el perfil `local` y definir `LOCAL_ROLES` (ADMIN o CLIENTE).

## Configuración

| Variable | Uso |
| --- | --- |
| `DB_URL` | URL JDBC de PostgreSQL; local: `jdbc:postgresql://localhost:5435/pedidos360_repartidores`. |
| `DB_USERNAME` | Usuario PostgreSQL. |
| `DB_PASSWORD` | Contraseña, sin valor incorporado al código. |
| `DB_NAME`, `DB_PORT` | Nombre de base y puerto publicado por el Compose local. |
| `SERVER_PORT` | Puerto HTTP; 8087 por defecto. |
| `SERVER_ADDRESS` | Dirección de escucha; 127.0.0.1 por defecto. |
| `LOCAL_IDENTITY_ENABLED` | `false` por defecto; `true` habilita identidad simulada solo con perfil `local` y loopback. |
| `LOCAL_TENANT_ID` | UUID ficticio del directorio; obligatorio en modo simulado. |
| `LOCAL_OBJECT_ID` | UUID ficticio del actor; obligatorio en modo simulado. |
| `LOCAL_ROLES` | `ADMIN` por defecto; admite `ADMIN`, `CLIENTE` o ambos separados por coma. |

En AWS se configurarán `DB_URL`, `DB_USERNAME` y `DB_PASSWORD` para RDS desde el entorno de despliegue, con TLS y acceso de red autorizado. No usar el Compose local para desplegar RDS ni subir credenciales.

Flyway administra las migraciones. Hibernate usa `ddl-auto: validate`: valida el modelo, no modifica tablas automáticamente. La cuenta de migración necesita permisos para crear las tablas; debe coordinarse con el responsable de RDS. Después de aplicar una migración compartida, agregar otra versión en vez de modificar la anterior.

## Probar los endpoints sin Entra ID

En `.env.local`, agregar las variables `LOCAL_*` de `.env.example` y cambiar `LOCAL_IDENTITY_ENABLED=true`. Los UUID de ejemplo son ficticios. `LOCAL_ROLES=ADMIN` habilita todas las operaciones incluido el listado; `CLIENTE` permite gestionar solo el propio perfil.

Con PostgreSQL local iniciado, arrancar desde esta carpeta:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Si ya arrancaste sin el perfil local, detén ese proceso con `Ctrl+C` antes de volver a iniciarlo. Mantén esta terminal abierta y usa otra para las peticiones HTTP.

El modo simulado requiere únicamente el perfil `local` y `SERVER_ADDRESS=127.0.0.1` (o `::1`). Si se intenta habilitar con otro perfil o escuchando en todas las interfaces, la aplicación se niega a arrancar.

Toda petición local usa la identidad configurada en el servidor. No se necesita un token ni se toman identidades o roles de headers HTTP. Cualquier proceso local puede usar esa identidad de prueba: no es autenticación real. Cambiar UUID o roles exige reiniciar el servicio.

Sin habilitación explícita, `/repartidores` y sus subrutas responden 401; solo la salud queda pública. Enviar un Bearer token no habilita el acceso hasta implementar la validación de Entra.

### Recorrido manual: crear, consultar, disponibilidad y asignación

Con identidad local habilitada y `LOCAL_ROLES=ADMIN`, en otra terminal PowerShell:

```powershell
$repUrl = 'http://127.0.0.1:8087'
$perfil = @{
  nombre = 'Repartidor Uno'
  telefono = '+56912345678'
  vehiculo = 'MOTO'
  zona = 'Norte'
} | ConvertTo-Json

$creado = Invoke-RestMethod -Method Post -Uri "$repUrl/repartidores" -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($perfil))
$repartidorId = $creado.id
Invoke-RestMethod "$repUrl/repartidores/$repartidorId"

$disp = @{ estado = 'EN_CAMINO' } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$repUrl/repartidores/$repartidorId/disponibilidad" -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($disp))

$asignacion = @{ pedidoId = 100; nota = 'Primer pedido' } | ConvertTo-Json
$asignada = Invoke-RestMethod -Method Post -Uri "$repUrl/repartidores/$repartidorId/asignaciones" -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($asignacion))
Invoke-RestMethod "$repUrl/repartidores/$repartidorId/asignaciones"

$estadoAsig = @{ estado = 'ENTREGADA' } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$repUrl/repartidores/$repartidorId/asignaciones/100/estado" -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($estadoAsig))
```

El POST devuelve 201 y un encabezado `Location: /repartidores/{id}`; las consultas devuelven 200. Se envía UTF-8 explícito para conservar los acentos también en Windows PowerShell.

Si ya ejecutaste el POST con esa identidad, obtendrás 409. Para un nuevo recorrido usa otra `LOCAL_OBJECT_ID` o recrea el perfil.

La respuesta expone el perfil y el estado, pero no los identificadores de identidad externa. Ejemplo ilustrativo:

```json
{
  "id": 1,
  "nombre": "Repartidor Uno",
  "telefono": "+56912345678",
  "vehiculo": "MOTO",
  "zona": "Norte",
  "estadoDisponibilidad": "EN_CAMINO",
  "creadoEn": "2026-09-04T12:00:00Z",
  "actualizadoEn": "2026-09-04T12:01:00Z",
  "version": 1
}
```

### Probar CLIENTE y desactivación

Para probar el rol CLIENTE, detén el servicio, cambia `LOCAL_ROLES=CLIENTE` en `.env.local` y vuelve a iniciarlo con el perfil `local`. Con `CLIENTE`, `GET /repartidores` devuelve 403 y no puede acceder a perfiles ajenos.

La siguiente operación es opcional y pasa el repartidor a INACTIVO. Hazla al final:

```powershell
Invoke-RestMethod -Method Delete -Uri "$repUrl/repartidores/$repartidorId"
```

Devuelve 204, sin cuerpo.

### Respuestas de error

Los errores de validación y negocio usan `application/problem+json`, con `status`, `detail` e `instance`; los errores de campos añaden `errores`.

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Revisa los campos enviados.",
  "instance": "/repartidores/1/disponibilidad",
  "errores": { "estado": "El estado de disponibilidad es obligatorio." }
}
```

| Código | Causa habitual |
| --- | --- |
| 400 | Campos inválidos, JSON roto, estado/vehículo inexistente, propiedades no admitidas, ID no positivo o paginación fuera de límites. |
| 401 | Sin identidad habilitada/validada; un Bearer token todavía no autentica. |
| 403 | CLIENTE intentando listar o acceder a otro perfil, o identidad con perfil desactivado. |
| 404 | Consulta a un repartidor inexistente, de otro directorio o asignación de un pedido no asignado. |
| 409 | Perfil duplicado por identidad, cambio a estado actual, pedido ya asignado o conflicto de escritura. |
| 415 | POST/PUT con un tipo de contenido distinto de `application/json`. |

PowerShell muestra las respuestas HTTP de error como excepciones. Revisa el cuerpo de la respuesta para distinguir una validación esperada de un problema de conexión. El frontend debe usar `status` y `errores`, sin depender del texto exacto de `detail`.

Un CLIENTE recibe 403 al consultar o modificar IDs ajenos, sin confirmar su existencia. ADMIN recibe 404 para IDs inexistentes o de otro directorio. Un repartidor INACTIVO no puede operar ni reactivarse desde PUT.

## Pruebas

Con Docker Desktop funcionando:

```powershell
.\mvnw.cmd verify
```

Las pruebas levantan PostgreSQL 17 temporal mediante Testcontainers, ejecutan la migración y verifican persistencia, unicidad por identidad, asignaciones, CRUD HTTP, validaciones, roles CLIENTE/ADMIN, recorrido de estados, errores y restricciones del modo local. Usan una base aislada y no leen `.env.local`. Los contenedores de prueba se eliminan al terminar.

No necesitas levantar Compose ni el servicio para ejecutar `verify`. Los servidores HTTP de prueba usan puertos aleatorios. El bloque actual contiene 29 pruebas; también cubre JSON inválido, campos no permitidos, conflictos sin filtrar detalles, protección contra versiones obsoletas en persistencia y limpieza de la identidad simulada. Los resultados quedan en `target/surefire-reports/` y no se suben a Git.

No se omiten automáticamente las pruebas cuando falta Docker. Para comprobar únicamente la compilación:

```powershell
.\mvnw.cmd test-compile
```

Esto no acredita que la persistencia funcione.

## Salud

Con la base disponible y el servicio iniciado:

```powershell
Invoke-RestMethod http://localhost:8087/actuator/health
```

Debe devolver `status: UP`. La salud ahora depende también de la base de datos. Antes de exponer operaciones de repartidores fuera del entorno local debe completarse su control de acceso.

## Si algo falla

| Síntoma | Qué revisar |
| --- | --- |
| Maven no encuentra Java o indica versión incompatible | `java -version` y `JAVA_HOME` deben apuntar al JDK 21. Abre otra terminal después de cambiar la configuración. |
| Docker no responde o las pruebas fallan al buscar un entorno Docker | Abre Docker Desktop, espera que el motor esté listo y comprueba `docker info`. No hace falta restablecerlo de fábrica. |
| Puerto 8087 o 5435 ocupado | Detén solo el proceso propio que usa el puerto, o ajusta `SERVER_PORT` / `DB_PORT` y `DB_URL`. Actualiza también la URL de los ejemplos si cambias 8087. |
| Error de contraseña o conexión a PostgreSQL | Comprueba el archivo local y `docker compose --env-file .env.local ps`. Cambiar la contraseña del archivo no modifica la almacenada en un volumen existente. No borres el volumen para resolverlo. |
| Todos los endpoints de repartidores devuelven 401 | Verifica `LOCAL_IDENTITY_ENABLED=true`, el arranque con perfil `local` y que ejecutas Maven desde la carpeta del servicio. Reinicia tras cambiar variables. |
| El modo local impide arrancar | Debe ser el único perfil activo, usar loopback, UUID válidos y roles admitidos. No lo habilites en despliegues compartidos. |
| GET `/repartidores/me` devuelve 404 | Falta crear el perfil mediante POST para la identidad local configurada. |

Para detener la aplicación usa `Ctrl+C` en su terminal. Después puedes detener PostgreSQL con el comando `docker compose --env-file .env.local down` indicado antes, conservando sus datos.

## Integración pendiente con el equipo

- BFF: exponer los endpoints de repartidores vía API Gateway; el frontend no debe conectarse directamente a PostgreSQL ni inventar `pedidoId` o `repartidorId`.
- Identidad: integrar y probar la validación de Entra ID y el transporte de identidad entre BFF y servicio. Enviar `X-User-Id`, `X-Roles` o un Bearer token no resuelve ese paso. No exponer el modo simulado ni usarlo como seguridad del BFF.
- RDS: coordinar base, credenciales, permisos de migración, TLS y acceso de red. La configuración por entorno ya existe; la conexión real con AWS no está validada.
- Contrato de asignaciones: coordinar el vínculo por `pedidoId` con pedidos-service (Integrante 3) para validar que el pedido pertenece al repartidor y que el flujo de estados de la asignación sea el correcto.
- Concurrencia: `@Version` detecta escrituras solapadas en persistencia. La API no recibe versión ni `If-Match`, por lo que no detecta por sí sola que un formulario quedó antiguo antes de iniciar una nueva petición. No asumir que ese caso está resuelto.

Esta rama no incorpora login de frontend, despliegue ni autenticación de producción. El contrato descrito aquí es el comportamiento actual del servicio local.

Seguimiento: Issue #12; rama `feature/i4-12-repartidores-service`.
