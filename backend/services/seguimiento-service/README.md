# Seguimiento Service

Servicio de seguimiento de pedidos de Pedidos360. Java 21, Spring Boot 4.1.1 y Maven Wrapper 3.9.15.

## Estado

Implementados: entidad `Seguimiento` (estado actual) y `SeguimientoEvento` (historial), repositorios JPA, migración Flyway y endpoints de consulta y actualización de estados con validaciones.
Se incluye identidad simulada local; Entra ID y la validación real de tokens siguen pendientes.
La base final estará en AWS RDS. El PostgreSQL de Docker es exclusivamente para desarrollo local.

## Antes de empezar

Necesitas JDK 21 y Docker Desktop con el motor Linux iniciado. Maven se descarga con el Wrapper del repositorio; no hace falta instalarlo por separado. La primera ejecución requiere internet para descargar Maven, dependencias e imagen de PostgreSQL.

En la terminal PowerShell de VS Code, desde la raíz del repositorio:

```powershell
Set-Location backend/services/seguimiento-service
java -version
docker info --format '{{.OSType}}'
```

Comprueba Java 21 y una respuesta `linux`. Los comandos siguientes se ejecutan desde `backend/services/seguimiento-service`, no desde la raíz. Puertos predeterminados: API 8088 y PostgreSQL 5434, ambos locales.

## Modelo

- `Seguimiento`: una entrada por pedido (`pedidoId` único) con su estado actual.
- `EstadoSeguimiento`: enum con RECIBIDO, EN_PREPARACION, LISTO, EN_CAMINO, ENTREGADO, CANCELADO, RECHAZADO y DEVOLUCION.
- `SeguimientoEvento`: historial de cambios de estado con fecha de ocurrencia y nota opcional.
- Fechas en UTC y versión en `Seguimiento` para detectar actualizaciones concurrentes.
- No guarda datos sensibles del pedido; solo la referencia `pedidoId` y el estado.

En pruebas se usan `pedidoId` ficticios; esto no equivale a una sesión autenticada ni a un pedido real.

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

Spring carga `.env.local` desde el directorio de ejecución. Docker Compose usa el mismo archivo mediante `--env-file`. El puerto local de PostgreSQL es 5434; si se cambia `DB_PORT`, actualizar también `DB_URL`.

La base conserva sus datos en un volumen. Para detenerla conservando los datos:

```powershell
docker compose --env-file .env.local down
```

Las variables iniciales de usuario y contraseña de PostgreSQL se aplican al crear el volumen por primera vez; editar el archivo después no cambia las credenciales de una base existente.

## Configuración

| Variable | Uso |
| --- | --- |
| `DB_URL` | URL JDBC de PostgreSQL; local: `jdbc:postgresql://localhost:5434/pedidos360_seguimiento`. |
| `DB_USERNAME` | Usuario PostgreSQL. |
| `DB_PASSWORD` | Contraseña, sin valor incorporado al código. |
| `DB_NAME`, `DB_PORT` | Nombre de base y puerto publicado por el Compose local. |
| `SERVER_PORT` | Puerto HTTP; 8088 por defecto. |
| `SERVER_ADDRESS` | Dirección de escucha; 127.0.0.1 por defecto. |
| `LOCAL_IDENTITY_ENABLED` | `false` por defecto; `true` habilita identidad simulada solo con perfil `local` y escucha loopback. |
| `LOCAL_TENANT_ID` | UUID ficticio del directorio; obligatorio en modo simulado. |
| `LOCAL_OBJECT_ID` | UUID ficticio del actor; obligatorio en modo simulado. |
| `LOCAL_ROLES` | `ADMIN` por defecto; admite `ADMIN`, `CLIENTE` o ambos separados por coma. Nunca se asigna desde el cuerpo HTTP. |

En AWS se configurarán `DB_URL`, `DB_USERNAME` y `DB_PASSWORD` para RDS desde el entorno de despliegue, con TLS y acceso de red autorizado. No usar el Compose local para desplegar RDS ni subir credenciales.

Flyway administra las migraciones. Hibernate usa `ddl-auto: validate`: valida el modelo, no modifica tablas automáticamente. La cuenta de migración necesita permisos para crear las tablas; debe coordinarse con el responsable de RDS. Después de aplicar una migración compartida, agregar otra versión en vez de modificar la anterior.

## API

| Método | Ruta | Comportamiento |
| --- | --- | --- |
| POST | `/seguimientos` | Inicia el seguimiento de un pedido con `pedidoId` y `estadoInicial`; 201 con Location. Duplicado de pedido: 409. |
| GET | `/seguimientos/{pedidoId}` | 200 con el estado actual; 404 si el pedido no tiene seguimiento. |
| GET | `/seguimientos/{pedidoId}/historial` | 200 con el estado actual y el historial; 404 si el pedido no tiene seguimiento. |
| PUT | `/seguimientos/{pedidoId}/estado` | Cambia el estado y registra el evento; 200. Estado igual al actual: 409. |
| GET | `/seguimientos?pagina=0&tamanio=20` | 200; solo ADMIN, paginado desde 0, tamaño entre 1 y 100, ordenado por ID ascendente. |

Los cuerpos se envían en JSON (`application/json`). POST y PUT aceptan únicamente los campos documentados; se rechazan campos desconocidos.

## Probar los endpoints sin Entra ID

En `.env.local`, agregar las variables `LOCAL_*` de `.env.example` y cambiar `LOCAL_IDENTITY_ENABLED=true`. Los UUID de ejemplo son ficticios. `LOCAL_ROLES=ADMIN` habilita todas las operaciones incluido el listado; `CLIENTE` permite consultar y cambiar estados pero no listar todos.

Con PostgreSQL local iniciado, arrancar desde esta carpeta:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Si ya arrancaste sin el perfil local, detén ese proceso con `Ctrl+C` antes de volver a iniciarlo. Mantén esta terminal abierta y usa otra para las peticiones HTTP.

El modo simulado requiere únicamente el perfil `local` y `SERVER_ADDRESS=127.0.0.1` (o `::1`). Si se intenta habilitar con otro perfil o escuchando en todas las interfaces, la aplicación se niega a arrancar.

Toda petición local usa la identidad configurada en el servidor. No se necesita un token ni se toman identidades o roles de headers HTTP. Cualquier proceso local puede usar esa identidad de prueba: no es autenticación real. Cambiar UUID o roles exige reiniciar el servicio.

Sin habilitación explícita, `/seguimientos` y sus subrutas responden 401; solo la salud queda pública. Enviar un Bearer token no habilita el acceso hasta implementar la validación de Entra.

### Recorrido manual: iniciar, consultar y cambiar estados

Con identidad local habilitada y `LOCAL_ROLES=ADMIN`, en otra terminal PowerShell:

```powershell
$segUrl = 'http://127.0.0.1:8088'
$crear = @{
  pedidoId = 100
  estadoInicial = 'RECIBIDO'
} | ConvertTo-Json

$creado = Invoke-RestMethod -Method Post -Uri "$segUrl/seguimientos" -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($crear))
Invoke-RestMethod "$segUrl/seguimientos/100"
Invoke-RestMethod "$segUrl/seguimientos/100/historial"

$cambio = @{
  estado = 'EN_CAMINO'
  nota = 'Repartidor en ruta'
} | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$segUrl/seguimientos/100/estado" -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($cambio))
```

El POST devuelve 201 y un encabezado `Location: /seguimientos/100`; las consultas devuelven 200. Cada PUT registra un evento en el historial. Se envía UTF-8 explícito para conservar los acentos también en Windows PowerShell.

Si ya ejecutaste el POST con ese pedido, obtendrás 409. Para un nuevo recorrido usa otro `pedidoId`.

La respuesta expone el estado actual y las fechas; no expone datos del pedido. Ejemplo ilustrativo:

```json
{
  "id": 1,
  "pedidoId": 100,
  "estadoActual": "EN_CAMINO",
  "creadoEn": "2026-09-04T12:00:00Z",
  "actualizadoEn": "2026-09-04T12:01:00Z",
  "version": 1
}
```

El historial devuelve `pedidoId`, `estadoActual` y la lista `eventos` con `estado`, `ocurridoEn` y `nota` opcional. El listado devuelve `contenido`, `pagina`, `tamanio`, `totalElementos` y `totalPaginas`.

### Probar CLIENTE y listado

Para probar el rol CLIENTE, detén el servicio, cambia `LOCAL_ROLES=CLIENTE` en `.env.local` y vuelve a iniciarlo con el perfil `local`. Con `CLIENTE`, la consulta y el cambio de estado funcionan, pero `GET /seguimientos` devuelve 403.

### Respuestas de error

Los errores de validación y negocio usan `application/problem+json`, con `status`, `detail` e `instance`; los errores de campos añaden `errores`.

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Revisa los campos enviados.",
  "instance": "/seguimientos/100/estado",
  "errores": { "estado": "El nuevo estado es obligatorio." }
}
```

| Código | Causa habitual |
| --- | --- |
| 400 | Campos inválidos, JSON roto, estado inexistente, propiedades no admitidas, ID no positivo o paginación fuera de límites. |
| 401 | Sin identidad habilitada/validada; un Bearer token todavía no autentica. |
| 403 | CLIENTE intentando listar todos los seguimientos, o identidad sin rol ADMIN para listar. |
| 404 | Consulta o cambio de estado a un pedido sin seguimiento. |
| 409 | Seguimiento duplicado por pedido, cambio al estado actual o conflicto de escritura por versión. |
| 415 | POST/PUT con un tipo de contenido distinto de `application/json`. |

PowerShell muestra las respuestas HTTP de error como excepciones. Revisa el cuerpo de la respuesta para distinguir una validación esperada de un problema de conexión. El frontend debe usar `status` y `errores`, sin depender del texto exacto de `detail`.

## Pruebas

Con Docker Desktop funcionando:

```powershell
.\mvnw.cmd verify
```

Las pruebas levantan PostgreSQL 17 temporal mediante Testcontainers y ejecutan la migración. Verifican persistencia, unicidad por pedido, historial, CRUD HTTP, validaciones, permisos de CLIENTE/ADMIN, recorrido de estados, errores y restricciones del modo local. Usan una base aislada y no leen `.env.local`: no acceden a RDS ni al volumen de desarrollo. Los contenedores de prueba se eliminan al terminar.

No necesitas levantar Compose ni el servicio para ejecutar `verify`. Los servidores HTTP de prueba usan puertos aleatorios. El bloque actual contiene 26 pruebas; también cubre JSON inválido, campos no permitidos, conflictos sin filtrar detalles, protección contra versiones obsoletas en persistencia y limpieza de la identidad simulada entre peticiones. Los resultados quedan en `target/surefire-reports/` y no se suben a Git.

No se omiten automáticamente las pruebas cuando falta Docker. Para comprobar únicamente la compilación:

```powershell
.\mvnw.cmd test-compile
```

Esto no acredita que la persistencia funcione.

## Salud

Con la base disponible y el servicio iniciado:

```powershell
Invoke-RestMethod http://localhost:8088/actuator/health
```

Debe devolver `status: UP`. La salud ahora depende también de la base de datos. Antes de exponer operaciones de seguimiento fuera del entorno local debe completarse su control de acceso.

## Si algo falla

| Síntoma | Qué revisar |
| --- | --- |
| Maven no encuentra Java o indica versión incompatible | `java -version` y `JAVA_HOME` deben apuntar al JDK 21. Abre otra terminal después de cambiar la configuración. |
| Docker no responde o las pruebas fallan al buscar un entorno Docker | Abre Docker Desktop, espera que el motor esté listo y comprueba `docker info`. No hace falta restablecerlo de fábrica. |
| Puerto 8088 o 5434 ocupado | Detén solo el proceso propio que usa el puerto, o ajusta `SERVER_PORT` / `DB_PORT` y `DB_URL`. Actualiza también la URL de los ejemplos si cambias 8088. |
| Error de contraseña o conexión a PostgreSQL | Comprueba el archivo local y `docker compose --env-file .env.local ps`. Cambiar la contraseña del archivo no modifica la almacenada en un volumen existente. No borres el volumen para resolverlo. |
| Todos los endpoints de seguimiento devuelven 401 | Verifica `LOCAL_IDENTITY_ENABLED=true`, el arranque con perfil `local` y que ejecutas Maven desde la carpeta del servicio. Reinicia tras cambiar variables. |
| El modo local impide arrancar | Debe ser el único perfil activo, usar loopback, UUID válidos y roles admitidos. No lo habilites en despliegues compartidos. |
| GET `/seguimientos/{pedidoId}` devuelve 404 | Falta iniciar el seguimiento mediante POST para ese pedido. |

Para detener la aplicación usa `Ctrl+C` en su terminal. Después puedes detener PostgreSQL con el comando `docker compose --env-file .env.local down` indicado antes, conservando sus datos.

## Integración pendiente con el equipo

- BFF: exponer los endpoints de seguimiento vía API Gateway; el frontend no debe conectarse directamente a PostgreSQL ni inventar `pedidoId`.
- Identidad: integrar y probar la validación de Entra ID y el transporte de identidad entre BFF y servicio. Enviar `X-User-Id`, `X-Roles` o un Bearer token no resuelve ese paso. No exponer el modo simulado ni usarlo como seguridad del BFF.
- RDS: coordinar base, credenciales, permisos de migración, TLS y acceso de red. La configuración por entorno ya existe; la conexión real con AWS no está validada.
- Contrato de estados: el flujo de estados (RECIBIDO, EN_PREPARACION, LISTO, EN_CAMINO, ENTREGADO, CANCELADO, RECHAZADO, DEVOLUCION) debe coordinarse con pedidos-service (Integrante 3) para validar la secuencia válida por negocio; actualmente se acepta cualquier transición entre estados válidos.
- Concurrencia: `@Version` detecta escrituras solapadas en persistencia. La API no recibe versión ni `If-Match`, por lo que no detecta por sí sola que un formulario quedó antiguo antes de iniciar una nueva petición. No asumir que ese caso está resuelto.

Esta rama no incorpora login de frontend, despliegue ni autenticación de producción. El contrato descrito aquí es el comportamiento actual del servicio local.

Seguimiento: Issue #7; rama `feature/i4-7-seguimiento-service`.
